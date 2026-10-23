package com.notifyforward.app

import android.content.Context
import com.notifyforward.app.data.DbHelper
import com.notifyforward.app.engine.BertTokenizer
import com.notifyforward.app.engine.LearningStore
import com.notifyforward.app.engine.SemanticEngine
import com.notifyforward.app.model.NotifRecord
import org.json.JSONObject
import kotlin.math.abs

/** Dedicated emulator only. Preserve pre-existing labels and remove all test records. */
object LearningAcceptance {
    fun run(ctx: Context, testContext: Context, case: (String, () -> Unit) -> Unit) {
        val store = LearningStore(ctx)
        val backup = store.exportJson()
        val db = DbHelper.get(ctx)
        val marker = "learning.acceptance.${System.currentTimeMillis()}"
        val record = NotifRecord(packageName = marker, appName = "学习验收", title = "通知测试", text = "项目会议改到下午三点")
        try {
            store.reset()
            val id = db.insertNotification(record)
            val duplicate = db.insertNotification(record)
            case("feedback is deduplicated and corrections replace old evidence") {
                store.label(record.copy(id = id), 2)
                store.label(record.copy(id = duplicate), 2)
                check(store.counts() == (0 to 1))
                store.label(record.copy(id = id), 1)
                check(store.counts() == (1 to 0))
                check(store.samples().single().label == 1)
                db.readableDatabase.rawQuery("SELECT feedback FROM notifications WHERE package=?", arrayOf(marker)).use { c ->
                    while(c.moveToNext()) check(c.getInt(0) == 1)
                }
            }
            case("withdrawal removes evidence and survives reopening") {
                store.label(record, 0)
                check(LearningStore(ctx).counts() == (0 to 0))
                check(store.currentLabel(record) == 0)
            }
            case("main and clone labels are independent") {
                store.label(record, 1); store.label(record.copy(clone = true), 2)
                check(store.counts() == (1 to 1))
                check(store.currentLabel(record.copy(clone = true)) == 2)
            }
            case("backup roundtrip restores text and final labels, duplicate import is idempotent") {
                val exported = store.exportJson()
                store.reset(); check(store.counts() == (0 to 0))
                check(store.importJson(exported) == 2)
                check(store.importJson(exported) == 0)
                check(store.counts() == (1 to 1))
                check(store.samples().all { it.text == record.text })
                store.label(record, 2)
                check(store.importJson(exported) == 0)
                check(store.currentLabel(record) == 2)
            }
            case("invalid backups are rejected atomically, including late invalid rows") {
                val before = store.exportJson()
                val root = JSONObject(before)
                root.getJSONArray("samples").getJSONObject(0).put("package", "new.package")
                root.getJSONArray("samples").getJSONObject(1).put("label", 99)
                check(runCatching { store.importJson(root.toString()) }.isFailure)
                check(store.exportJson() == before)
                check(runCatching { store.importJson(JSONObject(before).put("version", 99).toString()) }.isFailure)
                check(runCatching { store.importJson("{\"filterRules\":[]}") }.isFailure)
                check(store.exportJson() == before)
            }
            case("deleting notification history preserves learned samples") {
                db.deleteNotification(id); db.deleteNotification(duplicate)
                check(LearningStore(ctx).counts() == (0 to 2))
            }
            case("legacy history labels migrate only once and do not revive after reset") {
                val old = db.insertNotification(record.copy(feedback = 1))
                db.writableDatabase.delete("learning_meta", "key='migrated'", null)
                check(LearningStore(ctx).currentLabel(record) == 1)
                store.reset()
                check(LearningStore(ctx).counts() == (0 to 0))
                db.deleteNotification(old)
            }
            case("BERT input boundaries and cased tokenization") {
                val tokenizer = ctx.assets.open("semantic/vocab.txt").bufferedReader().use { BertTokenizer(it.readLines()) }
                val ids = tokenizer.encode("你好，世界！")
                check(ids.first() == 101L && ids.last() == 102L)
                check(tokenizer.encode("通知".repeat(1000)).size == 128)
                check(!tokenizer.encode("ABC").contentEquals(tokenizer.encode("abc")))
            }
            case("bundled ONNX model really infers normalized 512D vectors") {
                val t = System.currentTimeMillis()
                val vector = SemanticEngine.embed(ctx, "下午三点开项目会议")
                check(vector.size == 512)
                check(vector.all { it.isFinite() })
                check(abs(vector.sumOf { (it * it).toDouble() } - 1.0) < 0.001)
                check(vector.contentEquals(SemanticEngine.embed(ctx, "下午三点开项目会议")))
                android.util.Log.i("LearningAcceptance", "Cold embedding ms=${System.currentTimeMillis()-t}")
            }
            case("Android tokenizer and inference match independent reference runtime") {
                val golden = org.json.JSONArray(testContext.assets.open("tokenizer-golden.json").bufferedReader().use { it.readText() })
                val tokenizer = ctx.assets.open("semantic/vocab.txt").bufferedReader().use { BertTokenizer(it.readLines()) }
                for (i in 0 until golden.length()) {
                    val row = golden.getJSONObject(i)
                    val expected = row.getJSONArray("ids")
                    check(tokenizer.encode(row.getString("text")).contentEquals(LongArray(expected.length()) { expected.getLong(it) })) { "Tokenizer mismatch at reference case $i" }
                    if (row.has("vector")) {
                        val actual = SemanticEngine.embed(ctx, row.getString("text"))
                        val vector = row.getJSONArray("vector")
                        val cosine = actual.indices.sumOf { actual[it] * vector.getDouble(it) }
                        check(cosine > 0.999) { "Reference embedding cosine=$cosine" }
                    }
                }
            }
            case("shopping feedback cannot label the same WeChat content") {
                val shop = LearningStore.sample(record.copy(packageName = "shop.example"), 2)
                val chat = LearningStore.sample(record.copy(packageName = "com.tencent.mm"), 0)
                val judgment = SemanticEngine.judge(ctx, chat, listOf(shop))
                check(judgment.label != 2 && judgment.personalSamples == 0)
            }
            case("semantic judgment uses corrected labels and never claims probability") {
                val sample = LearningStore.sample(record, 0)
                store.label(record, 2)
                check(SemanticEngine.judge(ctx, sample, store.samples()).label == 2)
                store.label(record, 1)
                check(SemanticEngine.judge(ctx, sample, store.samples()).label == 1)
                store.label(record, 0)
                val inferred = SemanticEngine.judge(ctx, sample, store.samples())
                check(inferred.label in 0..2)
                check(inferred.summary.contains("未执行拦截") && inferred.reason.contains("不是概率"))
            }
        } finally {
            store.reset()
            store.importJson(backup)
            db.writableDatabase.delete("notifications", "package=?", arrayOf(marker))
            SemanticEngine.release()
        }
    }
}
