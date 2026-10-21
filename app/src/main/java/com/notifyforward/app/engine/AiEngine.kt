package com.notifyforward.app.engine

import android.content.Context
import com.tencent.mmkv.MMKV
import org.json.JSONObject
import java.io.File
import kotlin.math.exp
import kotlin.math.ln

/**
 * 完全离线的本地垃圾通知判别器。
 *
 * 采用多项式朴素贝叶斯 + 规则先验：
 *  - 内置少量“营销/正常”语料做冷启动，使首次安装即可用；
 *  - 用户每条“骚扰/正常”反馈都会在线更新模型（token 计数），越用越贴合个人习惯；
 *  - 模型与推理全部在设备本地完成，不联网、不上传通知内容。
 */
object AiEngine {

    private const val LEGACY_FILE = "ai_model.json"
    private const val MODEL_ID = "ai_model_mp"
    private const val KEY_JSON = "model_json"
    private const val KEY_VERSION = "model_version"
    private const val ALPHA = 0.35
    private const val MAX_VOCAB = 6000
    private const val MAX_TOKENS_PER_DOC = 80

    private val lock = Any()
    private var loaded = false
    private var localVersion = 0

    private val kv: MMKV by lazy { MMKV.mmkvWithID(MODEL_ID, MMKV.MULTI_PROCESS_MODE) }

    private var spamTotal = 0.0
    private var normalTotal = 0.0
    private var spamDocs = 0.0
    private var normalDocs = 0.0
    private var userFeedback = 0
    private val spamTokens = HashMap<String, Double>()
    private val normalTokens = HashMap<String, Double>()

    fun ensureLoaded(context: Context) {
        val remoteVersion = runCatching { kv.decodeInt(KEY_VERSION, 0) }.getOrDefault(0)
        synchronized(lock) {
            if (loaded && remoteVersion == localVersion) return
            val raw = runCatching { kv.decodeString(KEY_JSON, null) }.getOrNull()

            var restored = false
            if (!raw.isNullOrBlank()) {
                restored = runCatching { load(raw); true }.getOrDefault(false)
            }
            if (!restored) {
                // 从旧版 JSON 文件迁移一次
                val legacy = File(context.filesDir, LEGACY_FILE)
                if (legacy.exists()) {
                    restored = runCatching { load(legacy.readText()); true }.getOrDefault(false)
                }
            }
            if (!restored || (spamDocs == 0.0 && normalDocs == 0.0)) {
                seed()
                persistLocked()
            }
            localVersion = runCatching { kv.decodeInt(KEY_VERSION, 0) }.getOrDefault(localVersion)
            loaded = true
        }
    }

    /** 返回 0..1 的骚扰概率。 */
    fun score(context: Context, title: String, text: String): Float {
        ensureLoaded(context)
        val content = "$title $text".trim()
        if (content.isEmpty()) return 0f

        val tokens = tokenize(content)
        if (tokens.isEmpty()) return 0f

        val vocab = (spamTokens.size + normalTokens.size + 1).toDouble()
        var llSpam = 0.0
        var llNormal = 0.0
        var tokenCount = 0.0

        for ((tok, count) in tokens) {
            val sc = spamTokens[tok] ?: 0.0
            val nc = normalTokens[tok] ?: 0.0
            llSpam += count * ln((sc + ALPHA) / (spamTotal + ALPHA * vocab))
            llNormal += count * ln((nc + ALPHA) / (normalTotal + ALPHA * vocab))
            tokenCount += count
        }
        // 按 token 数取平均，避免文档长度差异造成概率漂移
        val avgDiff = if (tokenCount > 0) (llSpam - llNormal) / tokenCount else 0.0
        val prior = ln((spamDocs + 1.0) / (normalDocs + 1.0))
        val nb = sigmoid(prior * 0.6 + avgDiff * 2.2)
        val heur = heuristic(content)
        val blended = 0.62 * nb + 0.38 * heur
        return blended.coerceIn(0.0, 1.0).toFloat()
    }

    /** 用户反馈：spam=true 表示这条是骚扰通知。 */
    fun train(context: Context, title: String, text: String, spam: Boolean, weight: Double = 3.0) {
        val tokens = tokenize("$title $text")
        if (tokens.isEmpty()) return
        synchronized(lock) {
            // 重新检查版本，避免覆盖另一个进程刚写入的训练结果
            ensureLoaded(context)
            if (spam) {
                spamDocs += weight
                spamTotal += weight * tokens.values.sum()
                tokens.forEach { (t, c) -> spamTokens[t] = (spamTokens[t] ?: 0.0) + c * weight }
            } else {
                normalDocs += weight
                normalTotal += weight * tokens.values.sum()
                tokens.forEach { (t, c) -> normalTokens[t] = (normalTokens[t] ?: 0.0) + c * weight }
            }
            userFeedback++
            pruneIfNeeded()
            persistLocked()
        }
    }

    fun stats(context: Context): ModelStats {
        ensureLoaded(context)
        synchronized(lock) {
            return ModelStats(
                feedback = userFeedback,
                vocabulary = spamTokens.size + normalTokens.size,
                spamDocs = spamDocs.toInt(),
                normalDocs = normalDocs.toInt()
            )
        }
    }

    fun reset(context: Context) {
        synchronized(lock) {
            spamTokens.clear(); normalTokens.clear()
            spamTotal = 0.0; normalTotal = 0.0
            spamDocs = 0.0; normalDocs = 0.0
            userFeedback = 0
            seed()
            persistLocked()
        }
    }

    data class ModelStats(
        val feedback: Int,
        val vocabulary: Int,
        val spamDocs: Int,
        val normalDocs: Int
    )

    // ---------------- 内部实现 ----------------

    /** 调用方需持有 lock。写入 MMKV 并递增版本号，供其他进程感知。 */
    private fun persistLocked() {
        kv.encode(KEY_JSON, toJson())
        val next = kv.decodeInt(KEY_VERSION, 0) + 1
        kv.encode(KEY_VERSION, next)
        localVersion = next
        loaded = true
    }

    private fun toJson(): String = JSONObject().apply {
        put("spamTotal", spamTotal)
        put("normalTotal", normalTotal)
        put("spamDocs", spamDocs)
        put("normalDocs", normalDocs)
        put("userFeedback", userFeedback)
        put("spamTokens", JSONObject(spamTokens.mapValues { it.value }))
        put("normalTokens", JSONObject(normalTokens.mapValues { it.value }))
    }.toString()

    private fun load(raw: String) {
        val o = JSONObject(raw)
        spamTotal = o.optDouble("spamTotal", 0.0)
        normalTotal = o.optDouble("normalTotal", 0.0)
        spamDocs = o.optDouble("spamDocs", 0.0)
        normalDocs = o.optDouble("normalDocs", 0.0)
        userFeedback = o.optInt("userFeedback", 0)
        o.optJSONObject("spamTokens")?.let { j ->
            j.keys().forEach { k -> spamTokens[k] = j.optDouble(k, 0.0) }
        }
        o.optJSONObject("normalTokens")?.let { j ->
            j.keys().forEach { k -> normalTokens[k] = j.optDouble(k, 0.0) }
        }
        loaded = true
    }

    private fun pruneIfNeeded() {
        val total = spamTokens.size + normalTokens.size
        if (total <= MAX_VOCAB) return
        val all = HashMap<String, Double>()
        spamTokens.forEach { (k, v) -> all[k] = v }
        normalTokens.forEach { (k, v) -> all[k] = (all[k] ?: 0.0) + v }
        val keep = all.entries.sortedByDescending { it.value }.take(MAX_VOCAB / 2).map { it.key }.toSet()
        spamTokens.keys.retainAll(keep)
        normalTokens.keys.retainAll(keep)
    }

    /** 分词：英文按单词，中文按单字 + 二元组。 */
    private fun tokenize(text: String): Map<String, Double> {
        val out = HashMap<String, Double>()
        val lower = text.lowercase()
        val latin = Regex("[a-z0-9_]+").findAll(lower)
        latin.forEach { m ->
            val w = m.value
            if (w.length >= 2) out["w:$w"] = (out["w:$w"] ?: 0.0) + 1.0
        }
        val cjk = StringBuilder()
        for (ch in lower) {
            if (ch in '\u4e00'..'\u9fff') cjk.append(ch)
        }
        val s = cjk.toString()
        for (i in s.indices) {
            val c = s[i].toString()
            out["c:$c"] = (out["c:$c"] ?: 0.0) + 1.0
            if (i + 1 < s.length) {
                val bi = s.substring(i, i + 2)
                out["b:$bi"] = (out["b:$bi"] ?: 0.0) + 1.0
            }
        }
        if (out.size <= MAX_TOKENS_PER_DOC) return out
        return out.entries.sortedByDescending { it.value }.take(MAX_TOKENS_PER_DOC)
            .associate { it.key to it.value }
    }

    /** 规则先验：营销话术抬高，验证码/交易/日程类压低。 */
    private fun heuristic(text: String): Double {
        val t = text.lowercase()
        var s = 0.0
        SPAM_WORDS.forEach { w -> if (t.contains(w)) s += 0.12 }
        NORMAL_WORDS.forEach { w -> if (t.contains(w)) s -= 0.16 }
        if (OTP_PATTERN.containsMatchIn(t)) s -= 0.55
        if (t.contains("退订") || t.contains("unsubscribe") || t.contains("回t退订")) s += 0.35
        if (t.contains("点击") && (t.contains("领取") || t.contains("链接") || t.contains("购买"))) s += 0.18
        val emoji = text.count { it.code in 0x1F300..0x1FAFF || it.code in 0x2600..0x27BF }
        if (emoji >= 4) s += 0.12
        if (text.length in 1..3) s -= 0.25
        var letters = 0
        var upper = 0
        for (ch in text) {
            if (ch.isLetter() && ch.code < 128) {
                letters++
                if (ch.isUpperCase()) upper++
            }
        }
        if (letters >= 12 && upper.toDouble() / letters > 0.7) s += 0.2
        return sigmoid(ln(1.0 / 1.0) + s * 4.0)
    }

    private fun sigmoid(x: Double): Double = 1.0 / (1.0 + exp(-x))

    private fun seed() {
        SEED_SPAM.forEach { doc ->
            val tokens = tokenize(doc)
            spamDocs += 2.0
            spamTotal += 2.0 * tokens.values.sum()
            tokens.forEach { (t, c) -> spamTokens[t] = (spamTokens[t] ?: 0.0) + c * 2.0 }
        }
        SEED_NORMAL.forEach { doc ->
            val tokens = tokenize(doc)
            normalDocs += 2.0
            normalTotal += 2.0 * tokens.values.sum()
            tokens.forEach { (t, c) -> normalTokens[t] = (normalTokens[t] ?: 0.0) + c * 2.0 }
        }
    }

    private val OTP_PATTERN = Regex(
        "(验证码|校验码|动态码|verification code|otp|code)[^\\n]{0,12}(\\d{4,8})|(\\d{4,8})[^\\n]{0,12}(验证码|校验码)"
    )

    private val SPAM_WORDS = listOf(
        "促销", "优惠", "优惠券", "红包", "领券", "限时", "折扣", "秒杀", "抽奖", "免费领",
        "特价", "抢购", "大促", "福利", "推广", "广告", "满减", "降价", "砍价", "拼团",
        "直播", "上新", "会员日", "0元", "包邮", "疯抢", "最后一天", "仅剩", "速抢",
        "sale", "discount", "coupon", "promo", "limited time", "buy now", "free gift",
        "offer", "deal", "shop now", "subscribe", "unbeatable"
    )

    private val NORMAL_WORDS = listOf(
        "验证码", "取件", "快递", "已签收", "订单", "支付", "到账", "转账", "账单", "还款",
        "会议", "日程", "提醒", "预约", "挂号", "就诊", "航班", "车次", "登机", "出行",
        "工资", "报销", "打卡", "审批", "作业", "成绩", "考试", "家长", "验证", "登录",
        "otp", "verification code", "delivered", "appointment", "meeting", "invoice", "payment"
    )

    private val SEED_SPAM = listOf(
        "【限时特惠】全场低至3折，点击领取优惠券，先到先得！",
        "双十一大促开启，满199减50，速来抢购，错过再等一年",
        "恭喜您获得抽奖资格，点击链接免费领取红包",
        "会员专享福利，今日特价秒杀，立即下单包邮到家",
        "🔥🔥爆款清仓，限时秒杀，买一送一，快来抢购🔥🔥",
        "您有一张优惠券即将过期，点击使用，享额外折扣",
        "Big sale! Limited time offer, buy now and get 50% off",
        "Free gift for you, subscribe now and claim your coupon"
    )

    private val SEED_NORMAL = listOf(
        "您的验证码是 384756，5分钟内有效，请勿泄露给他人",
        "您的快递已到达菜鸟驿站，取件码 8-2-1024，请及时领取",
        "您尾号 3321 的储蓄卡转账收入 1,280.00 元，余额 5,620.31 元",
        "会议提醒：今天 15:00 产品评审，地点 3A 会议室",
        "您的航班 CA1234 于 18:20 开始登机，登机口 B12",
        "预约成功：10月24日 09:30 口腔科，请提前15分钟到院",
        "Your verification code is 552013. Do not share it with anyone.",
        "Your order has been delivered. Thanks for shopping with us."
    )
}
