package com.notifyforward.app.engine

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.notifyforward.app.data.DbHelper
import com.notifyforward.app.model.NotifRecord
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** The final label is the source of truth; correcting it replaces, never adds, evidence. */
class LearningStore(context: Context) {
    private val db = DbHelper.get(context).writableDatabase

    data class Sample(val pkg: String, val clone: Boolean, val title: String, val text: String,
                      val label: Int, val updated: Long) {
        val key: String get() = hash(JSONArray().put(pkg).put(clone).put(title).put(text).toString())
        fun json() = JSONObject().put("package", pkg).put("clone", clone).put("title", title)
            .put("text", text).put("label", label).put("updated", updated)
    }

    init {
        transaction {
            db.execSQL("CREATE TABLE IF NOT EXISTS learning_samples (sample_key TEXT PRIMARY KEY, package TEXT NOT NULL, is_clone INTEGER NOT NULL, title TEXT NOT NULL, text TEXT NOT NULL, label INTEGER NOT NULL CHECK(label IN (1,2)), updated INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS learning_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS semantic_predictions (notification_id INTEGER PRIMARY KEY REFERENCES notifications(_id) ON DELETE CASCADE, summary TEXT NOT NULL)")
            val migrated = db.rawQuery("SELECT 1 FROM learning_meta WHERE key='migrated'", null).use { it.moveToFirst() }
            if (!migrated) {
                db.rawQuery("SELECT package,is_clone,title,CASE WHEN big_text<>'' THEN big_text ELSE text END,feedback,post_time FROM notifications WHERE feedback IN (1,2) ORDER BY _id", null).use { c ->
                    while (c.moveToNext()) put(Sample(c.getString(0), c.getInt(1) != 0, c.getString(2), c.getString(3), c.getInt(4), c.getLong(5)))
                }
                db.execSQL("INSERT INTO learning_meta VALUES ('migrated','1')")
            }
        }
    }

    fun label(record: NotifRecord, label: Int) {
        require(label in 0..2)
        val sample = sample(record, label)
        require(sample.title.isNotBlank() || sample.text.isNotBlank()) { "空通知无法学习" }
        require(sample.pkg.length in 1..512 && sample.title.length <= 16384 && sample.text.length <= 65536) { "通知内容过长，无法加入学习" }
        transaction {
            if (label != 0) {
                val exists = db.rawQuery("SELECT 1 FROM learning_samples WHERE sample_key=?", arrayOf(sample.key)).use { it.moveToFirst() }
                val size = db.rawQuery("SELECT COUNT(*) FROM learning_samples", null).use { it.moveToFirst(); it.getInt(0) }
                require(exists || size < 10000) { "学习样本已达上限，请先备份整理" }
            }
            if (label == 0) db.delete("learning_samples", "sample_key=?", arrayOf(sample.key)) else put(sample)
            syncHistory(sample)
        }
    }

    fun currentLabel(record: NotifRecord): Int = db.rawQuery(
        "SELECT label FROM learning_samples WHERE sample_key=?", arrayOf(sample(record, 0).key)
    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun samples(): List<Sample> = db.rawQuery(
        "SELECT package,is_clone,title,text,label,updated FROM learning_samples ORDER BY updated DESC,sample_key", null
    ).use { c -> buildList { while (c.moveToNext()) add(Sample(c.getString(0), c.getInt(1)!=0, c.getString(2), c.getString(3), c.getInt(4), c.getLong(5))) } }

    fun counts(): Pair<Int, Int> {
        var normal = 0; var spam = 0
        db.rawQuery("SELECT label,COUNT(*) FROM learning_samples GROUP BY label", null).use { c ->
            while (c.moveToNext()) if (c.getInt(0) == 1) normal = c.getInt(1) else spam = c.getInt(1)
        }
        return normal to spam
    }

    fun exportJson(): String = JSONObject().put("format", "notifyforward-learning").put("version", 1)
        .put("model", SemanticEngine.MODEL_ID).put("samples", JSONArray().apply { samples().forEach { put(it.json()) } }).toString(2)

    /** Validate everything before writing. Import merges; local labels win conflicts. */
    fun importJson(raw: String): Int {
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BACKUP_BYTES) { "备份不能超过 16 MB" }
        val root = JSONObject(raw)
        require(root.getString("format") == "notifyforward-learning" && root.getInt("version") == 1) { "不是支持的学习数据备份" }
        val rows = root.getJSONArray("samples")
        require(rows.length() <= 10000) { "备份样本过多" }
        val parsed = (0 until rows.length()).map { i ->
            val o = rows.getJSONObject(i)
            Sample(o.getString("package"), o.getBoolean("clone"), o.getString("title"), o.getString("text"), o.getInt("label"), o.getLong("updated")).also {
                require(it.pkg.length in 1..512 && it.title.length <= 16384 && it.text.length <= 65536 && it.label in 1..2 && it.updated >= 0 && (it.title.isNotBlank() || it.text.isNotBlank())) { "备份包含无效样本" }
            }
        }.distinctBy { it.key }
        var added = 0
        transaction {
            val size = db.rawQuery("SELECT COUNT(*) FROM learning_samples", null).use { it.moveToFirst(); it.getInt(0) }
            parsed.forEach { s ->
                val exists = db.rawQuery("SELECT 1 FROM learning_samples WHERE sample_key=?", arrayOf(s.key)).use { it.moveToFirst() }
                if (!exists) {
                    require(size + added < 10000) { "合并后样本超过上限" }
                    put(s); syncHistory(s); added++
                }
            }
        }
        return added
    }

    fun reset() = transaction {
        db.delete("learning_samples", null, null)
        db.execSQL("UPDATE notifications SET feedback=0")
    }

    fun prediction(id: Long): String = db.rawQuery("SELECT summary FROM semantic_predictions WHERE notification_id=?", arrayOf(id.toString())).use { if (it.moveToFirst()) it.getString(0) else "" }

    fun savePrediction(id: Long, summary: String) = transaction {
        val exists = db.rawQuery("SELECT 1 FROM notifications WHERE _id=?", arrayOf(id.toString())).use { it.moveToFirst() }
        if (exists) db.insertWithOnConflict("semantic_predictions", null, ContentValues().apply {
            put("notification_id", id); put("summary", summary)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun syncHistory(s: Sample) {
        db.execSQL("UPDATE notifications SET feedback=? WHERE package=? AND is_clone=? AND title=? AND (CASE WHEN big_text<>'' THEN big_text ELSE text END)=?", arrayOf<Any>(s.label, s.pkg, if(s.clone) 1 else 0, s.title, s.text))
    }

    private fun put(s: Sample) {
        db.insertWithOnConflict("learning_samples", null, ContentValues().apply {
            put("sample_key", s.key); put("package", s.pkg); put("is_clone", if(s.clone) 1 else 0)
            put("title", s.title); put("text", s.text); put("label", s.label); put("updated", s.updated)
        }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) { "无法保存学习数据" } }
    }

    private fun transaction(block: () -> Unit) {
        db.beginTransaction()
        try { block(); db.setTransactionSuccessful() } finally { db.endTransaction() }
    }

    companion object {
        const val MAX_BACKUP_BYTES = 16 * 1024 * 1024
        fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        fun sample(r: NotifRecord, label: Int) = Sample(r.packageName, r.clone, r.title, r.displayText, label, System.currentTimeMillis())
    }
}
