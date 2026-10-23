package com.notifyforward.app.engine

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.LongBuffer
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.exp
import org.json.JSONObject
import kotlin.math.sqrt

/** Frozen encoder + supervised lightweight head + app-scoped personal evidence. Scores are not calibrated probabilities. */
object SemanticEngine {
    const val MODEL_ID = "bge-small-zh-v1.5-int8-75c43b0-v1"
    private const val MODEL_SHA = "15b717c382bcb518ba457b93ea6850ede7f4f1cd8937454aa06972366cd19bcc"
    const val CLASSIFIER_ID = "notification-head-synthetic80-v1"
    private var classifier: Head? = null
    private data class Head(val weights: FloatArray, val bias: Float, val normalMax: Float, val spamMin: Float)
    private var session: OrtSession? = null
    private var tokenizer: BertTokenizer? = null
    private val cache = object : LinkedHashMap<String, FloatArray>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>?) = size > 256
    }

    data class Judgment(val label: Int, val reason: String, val personalSamples: Int) {
        val summary: String get() = "语义判断：${when(label) { 1 -> "正常"; 2 -> "疑似骚扰"; else -> "不确定" }} · 仅观察，未执行拦截\n$reason\nBGE-small-zh + 通知分类头 · 参考 $personalSamples 条个人样本；判断为当时快照"
    }

    @Synchronized
    fun judge(context: Context, sample: LearningStore.Sample, samples: List<LearningStore.Sample>): Judgment {
        val content = "${sample.title}\n${sample.text}".trim()
        if (content.isBlank()) return Judgment(0, "没有可判断的文字", 0)
        samples.firstOrNull { it.key == sample.key }?.let {
            return Judgment(it.label, "采用你对同一应用、相同内容的最终标注", 1)
        }
        val selected = listOf(1, 2).flatMap { label ->
            samples.filter { it.label == label && it.pkg == sample.pkg && it.clone == sample.clone }.take(32)
        }
        val vector = embed(context, content)
        val head = classifier ?: context.assets.open("semantic/classifier.json").bufferedReader().use { reader ->
            val json = JSONObject(reader.readText())
            check(json.getInt("version") == 1 && json.getString("encoder") == MODEL_ID)
            val values = json.getJSONArray("weights")
            check(values.length() == vector.size)
            Head(FloatArray(values.length()) { values.getDouble(it).toFloat() }, json.getDouble("bias").toFloat(),
                json.getDouble("normal_max").toFloat(), json.getDouble("spam_min").toFloat()).also {
                check(it.weights.all { v -> v.isFinite() } && it.bias.isFinite())
                check(it.normalMax > 0f && it.normalMax < it.spamMin && it.spamMin < 1f)
                classifier = it
            }
        }
        val score = (1.0 / (1.0 + exp(-(dot(vector, head.weights) + head.bias).toDouble()))).toFloat()
        var label = when { score >= head.spamMin -> 2; score <= head.normalMax -> 1; else -> 0 }
        val normal = selected.filter { it.label == 1 }.maxOfOrNull { dot(vector, embed(context, "${it.title}\n${it.text}".trim())) } ?: -1f
        val spam = selected.filter { it.label == 2 }.maxOfOrNull { dot(vector, embed(context, "${it.title}\n${it.text}".trim())) } ?: -1f
        val personalLabel = when {
            normal >= 0.80f && normal - spam >= 0.12f -> 1
            spam >= 0.80f && spam - normal >= 0.12f -> 2
            else -> 0
        }
        if (personalLabel != 0) label = personalLabel
        val social = sample.pkg in setOf("com.tencent.mm", "com.tencent.mobileqq", "com.whatsapp", "org.telegram.messenger")
        val heldForContext = social && label == 2 && personalLabel != 2
        if (heldForContext) label = 0
        val explanation = when {
            heldForContext -> "聊天来源可能是好友分享，缺少足够相近的本应用骚扰标注，保留为不确定"
            personalLabel != 0 -> "采用本应用相近个人标注，不使用其他应用的反馈"
            label == 0 -> "分类分值位于不确定区间，等待你确认"
            else -> "采用通知分类头；个人样本没有足够相近的证据"
        }
        return Judgment(label, String.format(Locale.ROOT,
            "骚扰倾向分值 %.2f（不是概率） · %s\n分类头使用构造样例训练，尚未通过真实通知评测", score, explanation), selected.size)
    }

    @Synchronized
    fun embed(context: Context, text: String): FloatArray {
        // Key by the actual truncated model input, limiting cache memory and matching inference.
        ensureLoaded(context)
        val ids = tokenizer!!.encode(text.take(8192))
        val key = ids.joinToString(",")
        cache[key]?.let { return it.copyOf() }
        val env = OrtEnvironment.getEnvironment()
        val shape = longArrayOf(1, ids.size.toLong())
        val inputs = linkedMapOf<String, OnnxTensor>()
        try {
            inputs["input_ids"] = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape)
            inputs["attention_mask"] = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(ids.size) { 1L }), shape)
            if (session!!.inputNames.contains("token_type_ids")) inputs["token_type_ids"] = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(ids.size)), shape)
            session!!.run(inputs).use { outputs ->
                @Suppress("UNCHECKED_CAST")
                val hidden = outputs[0].value as Array<Array<FloatArray>>
                // BGE requires CLS pooling, not mean pooling.
                val result = hidden[0][0].copyOf()
                val norm = sqrt(result.sumOf { (it * it).toDouble() }).toFloat()
                check(norm.isFinite() && norm > 0f && result.all { it.isFinite() }) { "模型输出无效" }
                for (i in result.indices) result[i] /= norm
                cache[key] = result
                return result.copyOf()
            }
        } finally { inputs.values.forEach { it.close() } }
    }

    private fun ensureLoaded(context: Context) {
        if (session != null) return
        val dir = File(context.noBackupFilesDir, "semantic").apply { mkdirs() }
        val file = File(dir, "$MODEL_ID.onnx")
        // The copy is private to the process; only :service performs production inference.
        if (!file.exists() || sha(file) != MODEL_SHA) {
            val tmp = File(dir, "$MODEL_ID.${android.os.Process.myPid()}.tmp")
            try {
                context.assets.open("semantic/model.onnx").use { input -> tmp.outputStream().use { input.copyTo(it) } }
                check(sha(tmp) == MODEL_SHA) { "语义模型校验失败" }
                check(tmp.renameTo(file)) { "无法保存语义模型" }
            } finally { tmp.delete() }
        }
        tokenizer = context.assets.open("semantic/vocab.txt").bufferedReader().use { BertTokenizer(it.readLines()) }
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2)
            options.setInterOpNumThreads(1)
            session = OrtEnvironment.getEnvironment().createSession(file.absolutePath, options)
        }
    }

    @Synchronized
    fun release() { session?.close(); session = null; tokenizer = null; classifier = null; cache.clear() }

    private fun sha(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(65536); var n = input.read(buffer)
            while (n >= 0) { digest.update(buffer, 0, n); n = input.read(buffer) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun dot(a: FloatArray, b: FloatArray): Float { require(a.size == b.size); var sum = 0f; for(i in a.indices) sum += a[i] * b[i]; return sum }

}
