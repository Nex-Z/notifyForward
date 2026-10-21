package com.notifyforward.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.notifyforward.app.model.FilterRule
import com.notifyforward.app.model.NotifRecord
import com.notifyforward.app.model.toStringList
import org.json.JSONArray

class DbHelper private constructor(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    init {
        // UI 进程与 :service 进程会同时读写，开启 WAL 提升并发读性能
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
        runCatching { db.execSQL("PRAGMA busy_timeout = 5000") }
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        runCatching { db.execSQL("PRAGMA busy_timeout = 5000") }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE notifications (
                _id INTEGER PRIMARY KEY AUTOINCREMENT,
                package TEXT NOT NULL,
                app_name TEXT NOT NULL,
                title TEXT NOT NULL DEFAULT '',
                text TEXT NOT NULL DEFAULT '',
                big_text TEXT NOT NULL DEFAULT '',
                sub_text TEXT NOT NULL DEFAULT '',
                channel_id TEXT NOT NULL DEFAULT '',
                category TEXT NOT NULL DEFAULT '',
                group_key TEXT NOT NULL DEFAULT '',
                post_time INTEGER NOT NULL,
                ongoing INTEGER NOT NULL DEFAULT 0,
                clearable INTEGER NOT NULL DEFAULT 1,
                conversation INTEGER NOT NULL DEFAULT 0,
                media INTEGER NOT NULL DEFAULT 0,
                uid INTEGER NOT NULL DEFAULT -1,
                user_id INTEGER NOT NULL DEFAULT 0,
                is_clone INTEGER NOT NULL DEFAULT 0,
                action INTEGER NOT NULL DEFAULT 0,
                rule_name TEXT NOT NULL DEFAULT '',
                ai_score REAL NOT NULL DEFAULT 0,
                source INTEGER NOT NULL DEFAULT 0,
                feedback INTEGER NOT NULL DEFAULT 0,
                hidden INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_notifications_time ON notifications(post_time DESC)")
        db.execSQL("CREATE INDEX idx_notifications_pkg ON notifications(package)")

        db.execSQL(
            """
            CREATE TABLE rules (
                _id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                enabled INTEGER NOT NULL DEFAULT 1,
                priority INTEGER NOT NULL DEFAULT 0,
                app_mode INTEGER NOT NULL DEFAULT 0,
                apps TEXT NOT NULL DEFAULT '[]',
                match_mode INTEGER NOT NULL DEFAULT 0,
                keywords TEXT NOT NULL DEFAULT '[]',
                regex TEXT NOT NULL DEFAULT '',
                skip_ongoing INTEGER NOT NULL DEFAULT 1,
                skip_media INTEGER NOT NULL DEFAULT 1,
                skip_conversation INTEGER NOT NULL DEFAULT 0,
                effect_dismiss INTEGER NOT NULL DEFAULT 1,
                mute_seconds INTEGER NOT NULL DEFAULT 0,
                webhook_url TEXT NOT NULL DEFAULT '',
                webhook_body TEXT NOT NULL DEFAULT '',
                action_buttons TEXT NOT NULL DEFAULT '',
                tts INTEGER NOT NULL DEFAULT 0,
                bark INTEGER NOT NULL DEFAULT 0,
                hit_count INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 两个进程可能同时升级，逐列判断 + 忽略重复列，保证幂等
        if (oldVersion < 2) {
            db.addColumnIfMissing("rules", "bark", "ALTER TABLE rules ADD COLUMN bark INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 3) {
            // 分身/多用户：记录发通知的 uid 与 Android 用户 id
            db.addColumnIfMissing(
                "notifications", "uid",
                "ALTER TABLE notifications ADD COLUMN uid INTEGER NOT NULL DEFAULT -1"
            )
            db.addColumnIfMissing(
                "notifications", "user_id",
                "ALTER TABLE notifications ADD COLUMN user_id INTEGER NOT NULL DEFAULT 0"
            )
            db.addColumnIfMissing(
                "notifications", "is_clone",
                "ALTER TABLE notifications ADD COLUMN is_clone INTEGER NOT NULL DEFAULT 0"
            )
        }
    }

    private fun SQLiteDatabase.addColumnIfMissing(table: String, column: String, ddl: String) {
        val exists = runCatching {
            rawQuery("PRAGMA table_info($table)", null).use { c ->
                var found = false
                while (c.moveToNext()) {
                    if (c.getString(1).equals(column, ignoreCase = true)) found = true
                }
                found
            }
        }.getOrDefault(false)
        if (!exists) runCatching { execSQL(ddl) }
    }

    // ---------------- 通知记录 ----------------

    fun insertNotification(r: NotifRecord): Long {
        val cv = ContentValues().apply {
            put("package", r.packageName)
            put("app_name", r.appName)
            put("title", r.title)
            put("text", r.text)
            put("big_text", r.bigText)
            put("sub_text", r.subText)
            put("channel_id", r.channelId)
            put("category", r.category)
            put("group_key", r.groupKey)
            put("post_time", r.postTime)
            put("ongoing", if (r.ongoing) 1 else 0)
            put("clearable", if (r.clearable) 1 else 0)
            put("conversation", if (r.conversation) 1 else 0)
            put("media", if (r.media) 1 else 0)
            put("uid", r.uid)
            put("user_id", r.userId)
            put("is_clone", if (r.clone) 1 else 0)
            put("action", r.action)
            put("rule_name", r.ruleName)
            put("ai_score", r.aiScore)
            put("source", r.source)
            put("feedback", r.feedback)
            put("hidden", if (r.hidden) 1 else 0)
        }
        return writableDatabase.insert("notifications", null, cv)
    }

    fun updateResult(
        id: Long,
        action: Int,
        ruleName: String,
        source: Int,
        aiScore: Float
    ) {
        // 用按位或合并，避免与异步 Webhook 结果互相覆盖
        writableDatabase.execSQL(
            "UPDATE notifications SET action = action | ?, rule_name = ?, source = ?, ai_score = ? WHERE _id = ?",
            arrayOf<Any>(action, ruleName, source, aiScore, id)
        )
    }

    fun addAction(id: Long, flag: Int) {
        writableDatabase.execSQL(
            "UPDATE notifications SET action = action | ? WHERE _id = ?",
            arrayOf<Any>(flag, id)
        )
    }

    fun setFeedback(id: Long, feedback: Int) {
        val cv = ContentValues().apply { put("feedback", feedback) }
        writableDatabase.update("notifications", cv, "_id=?", arrayOf(id.toString()))
    }

    fun setHidden(id: Long, hidden: Boolean) {
        val cv = ContentValues().apply { put("hidden", if (hidden) 1 else 0) }
        writableDatabase.update("notifications", cv, "_id=?", arrayOf(id.toString()))
    }

    fun deleteNotification(id: Long) {
        writableDatabase.delete("notifications", "_id=?", arrayOf(id.toString()))
    }

    fun queryNotifications(limit: Int = 400, onlyFiltered: Boolean? = null): List<NotifRecord> {
        val where = StringBuilder("hidden=0")
        when (onlyFiltered) {
            true -> where.append(" AND (action & 3) != 0")
            false -> where.append(" AND (action & 3) = 0")
            null -> Unit
        }
        val list = ArrayList<NotifRecord>()
        readableDatabase.query(
            "notifications", null, where.toString(), null, null, null,
            "post_time DESC", limit.toString()
        ).use { c ->
            while (c.moveToNext()) list.add(readRecord(c))
        }
        return list
    }

    fun searchNotifications(query: String, limit: Int = 300): List<NotifRecord> {
        val q = "%${query.replace("%", "\\%")}%"
        val list = ArrayList<NotifRecord>()
        readableDatabase.query(
            "notifications", null,
            "hidden=0 AND (title LIKE ? OR text LIKE ? OR big_text LIKE ? OR app_name LIKE ?)",
            arrayOf(q, q, q, q), null, null, "post_time DESC", limit.toString()
        ).use { c ->
            while (c.moveToNext()) list.add(readRecord(c))
        }
        return list
    }

    fun recentForRuleTest(limit: Int = 500): List<NotifRecord> {
        val list = ArrayList<NotifRecord>()
        readableDatabase.query(
            "notifications", null, "hidden=0", null, null, null,
            "post_time DESC", limit.toString()
        ).use { c ->
            while (c.moveToNext()) list.add(readRecord(c))
        }
        return list
    }

    fun todayCounts(): Pair<Int, Int> {
        val start = startOfToday()
        var total = 0
        var filtered = 0
        readableDatabase.rawQuery(
            "SELECT COUNT(*), SUM(CASE WHEN (action & 3) != 0 THEN 1 ELSE 0 END) " +
                "FROM notifications WHERE post_time >= ? AND hidden=0",
            arrayOf(start.toString())
        ).use { c ->
            if (c.moveToFirst()) {
                total = c.getInt(0)
                filtered = if (c.isNull(1)) 0 else c.getInt(1)
            }
        }
        return total to filtered
    }

    data class RangeStat(val total: Int, val filtered: Int, val ruleHits: Int)

    /** 统计 [start, end) 区间内的通知数、拦截数与规则命中数。 */
    fun rangeStat(start: Long, end: Long): RangeStat {
        var total = 0
        var filtered = 0
        var ruleHits = 0
        readableDatabase.rawQuery(
            "SELECT COUNT(*), " +
                "SUM(CASE WHEN (action & 3) != 0 THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN source = 1 THEN 1 ELSE 0 END) " +
                "FROM notifications WHERE post_time >= ? AND post_time < ? AND hidden = 0",
            arrayOf(start.toString(), end.toString())
        ).use { c ->
            if (c.moveToFirst()) {
                total = c.getInt(0)
                filtered = if (c.isNull(1)) 0 else c.getInt(1)
                ruleHits = if (c.isNull(2)) 0 else c.getInt(2)
            }
        }
        return RangeStat(total, filtered, ruleHits)
    }

    data class DayCount(val dayStart: Long, val total: Int, val filtered: Int)

    fun lastDays(days: Int): List<DayCount> {
        val out = ArrayList<DayCount>(days)
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        for (i in days - 1 downTo 0) {
            val c = cal.clone() as java.util.Calendar
            c.add(java.util.Calendar.DAY_OF_YEAR, -i)
            val start = c.timeInMillis
            c.add(java.util.Calendar.DAY_OF_YEAR, 1)
            val end = c.timeInMillis
            var total = 0
            var filtered = 0
            readableDatabase.rawQuery(
                "SELECT COUNT(*), SUM(CASE WHEN (action & 3) != 0 THEN 1 ELSE 0 END) " +
                    "FROM notifications WHERE post_time >= ? AND post_time < ? AND hidden=0",
                arrayOf(start.toString(), end.toString())
            ).use { cur ->
                if (cur.moveToFirst()) {
                    total = cur.getInt(0)
                    filtered = if (cur.isNull(1)) 0 else cur.getInt(1)
                }
            }
            out.add(DayCount(start, total, filtered))
        }
        return out
    }

    data class AppStat(
        val packageName: String,
        val appName: String,
        val total: Int,
        val filtered: Int,
        val clone: Boolean = false
    )

    fun appStats(days: Int = 7, limit: Int = 30): List<AppStat> {
        val out = ArrayList<AppStat>()
        val since = System.currentTimeMillis() - days * 24L * 3600_000L
        // 按 package + is_clone 分组，主应用与分身各自统计，避免相互淹没
        readableDatabase.rawQuery(
            "SELECT package, MAX(app_name), COUNT(*), " +
                "SUM(CASE WHEN (action & 3) != 0 THEN 1 ELSE 0 END) c, MAX(is_clone) " +
                "FROM notifications WHERE post_time >= ? AND hidden=0 " +
                "GROUP BY package, is_clone ORDER BY c DESC, COUNT(*) DESC LIMIT ?",
            arrayOf(since.toString(), limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val clone = c.getInt(4) == 1
                val base = c.getString(1) ?: c.getString(0)
                out.add(
                    AppStat(
                        c.getString(0),
                        if (clone) "$base（分身）" else base,
                        c.getInt(2),
                        if (c.isNull(3)) 0 else c.getInt(3),
                        clone
                    )
                )
            }
        }
        return out
    }

    fun countFeedback(pkg: String, feedback: Int): Int {
        var n = 0
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM notifications WHERE package=? AND feedback=?",
            arrayOf(pkg, feedback.toString())
        ).use { c -> if (c.moveToFirst()) n = c.getInt(0) }
        return n
    }

    /** 历史上出现过的应用（用于应用选择器补全）。 */
    fun knownPackages(): List<String> {
        val out = ArrayList<String>()
        readableDatabase.rawQuery(
            "SELECT package, MAX(app_name) FROM notifications GROUP BY package", null
        ).use { c -> while (c.moveToNext()) out.add(c.getString(0)) }
        return out
    }

    fun filteredCount(): Int {        var n = 0
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM notifications WHERE (action & 3) != 0", null
        ).use { c -> if (c.moveToFirst()) n = c.getInt(0) }
        return n
    }

    fun totalCount(): Int {
        var n = 0
        readableDatabase.rawQuery("SELECT COUNT(*) FROM notifications", null).use { c ->
            if (c.moveToFirst()) n = c.getInt(0)
        }
        return n
    }

    data class UidGroup(
        val packageName: String,
        val appName: String,
        val uids: String,
        val total: Int,
        val clones: Int
    )

    /**
     * 分身自检：按包名汇总历史上出现过的 uid。
     * 同一包名出现多个 uid，或 clones > 0，说明系统确实把分身通知投递给了监听器。
     */
    fun uidGroups(limit: Int = 40): List<UidGroup> {
        val out = ArrayList<UidGroup>()
        readableDatabase.rawQuery(
            "SELECT package, MAX(app_name), GROUP_CONCAT(DISTINCT uid), COUNT(*), SUM(is_clone) " +
                "FROM notifications WHERE uid > 0 GROUP BY package " +
                "ORDER BY SUM(is_clone) DESC, COUNT(DISTINCT uid) DESC, COUNT(*) DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    UidGroup(
                        c.getString(0),
                        c.getString(1) ?: c.getString(0),
                        c.getString(2).orEmpty(),
                        c.getInt(3),
                        if (c.isNull(4)) 0 else c.getInt(4)
                    )
                )
            }
        }
        return out
    }

    fun deleteOlderThan(days: Int) {
        val cutoff = System.currentTimeMillis() - days * 24L * 3600_000L
        writableDatabase.delete("notifications", "post_time < ?", arrayOf(cutoff.toString()))
    }

    fun clearNotifications() {
        writableDatabase.delete("notifications", null, null)
    }

    fun topRules(limit: Int = 5): List<FilterRule> =
        getAllRules().sortedByDescending { it.hitCount }.take(limit)

    // ---------------- 规则 ----------------

    fun getAllRules(): List<FilterRule> {
        val list = ArrayList<FilterRule>()
        readableDatabase.query("rules", null, null, null, null, null, "priority ASC, _id ASC")
            .use { c -> while (c.moveToNext()) list.add(readRule(c)) }
        return list
    }

    fun getEnabledRules(): List<FilterRule> = getAllRules().filter { it.enabled }

    fun getRule(id: Long): FilterRule? {
        readableDatabase.query("rules", null, "_id=?", arrayOf(id.toString()), null, null, null)
            .use { c -> if (c.moveToNext()) return readRule(c) }
        return null
    }

    fun saveRule(rule: FilterRule): Long {
        val cv = ContentValues().apply {
            put("name", rule.name)
            put("enabled", if (rule.enabled) 1 else 0)
            put("priority", rule.priority)
            put("app_mode", rule.appMode)
            put("apps", JSONArray(rule.apps).toString())
            put("match_mode", rule.matchMode)
            put("keywords", JSONArray(rule.keywords).toString())
            put("regex", rule.regex)
            put("skip_ongoing", if (rule.skipOngoing) 1 else 0)
            put("skip_media", if (rule.skipMedia) 1 else 0)
            put("skip_conversation", if (rule.skipConversation) 1 else 0)
            put("effect_dismiss", if (rule.effectDismiss) 1 else 0)
            put("mute_seconds", rule.muteSeconds)
            put("webhook_url", rule.webhookUrl)
            put("webhook_body", rule.webhookBody)
            put("action_buttons", rule.actionButtons)
            put("tts", if (rule.tts) 1 else 0)
            put("bark", if (rule.bark) 1 else 0)
            put("hit_count", rule.hitCount)
            put("created_at", rule.createdAt)
        }
        return if (rule.id == 0L) {
            writableDatabase.insert("rules", null, cv)
        } else {
            writableDatabase.update("rules", cv, "_id=?", arrayOf(rule.id.toString()))
            rule.id
        }
    }

    fun setRuleEnabled(id: Long, enabled: Boolean) {
        val cv = ContentValues().apply { put("enabled", if (enabled) 1 else 0) }
        writableDatabase.update("rules", cv, "_id=?", arrayOf(id.toString()))
    }

    fun deleteRule(id: Long) {
        writableDatabase.delete("rules", "_id=?", arrayOf(id.toString()))
    }

    fun incrementHit(id: Long) {
        writableDatabase.execSQL("UPDATE rules SET hit_count = hit_count + 1 WHERE _id = ?", arrayOf(id))
    }

    // ---------------- 行映射 ----------------

    private fun readRecord(c: Cursor) = NotifRecord(
        id = c.getLong(c.getColumnIndexOrThrow("_id")),
        packageName = c.getString(c.getColumnIndexOrThrow("package")),
        appName = c.getString(c.getColumnIndexOrThrow("app_name")),
        title = c.getString(c.getColumnIndexOrThrow("title")) ?: "",
        text = c.getString(c.getColumnIndexOrThrow("text")) ?: "",
        bigText = c.getString(c.getColumnIndexOrThrow("big_text")) ?: "",
        subText = c.getString(c.getColumnIndexOrThrow("sub_text")) ?: "",
        channelId = c.getString(c.getColumnIndexOrThrow("channel_id")) ?: "",
        category = c.getString(c.getColumnIndexOrThrow("category")) ?: "",
        groupKey = c.getString(c.getColumnIndexOrThrow("group_key")) ?: "",
        postTime = c.getLong(c.getColumnIndexOrThrow("post_time")),
        ongoing = c.getInt(c.getColumnIndexOrThrow("ongoing")) == 1,
        clearable = c.getInt(c.getColumnIndexOrThrow("clearable")) == 1,
        conversation = c.getInt(c.getColumnIndexOrThrow("conversation")) == 1,
        media = c.getInt(c.getColumnIndexOrThrow("media")) == 1,
        uid = c.getInt(c.getColumnIndexOrThrow("uid")),
        userId = c.getInt(c.getColumnIndexOrThrow("user_id")),
        clone = c.getInt(c.getColumnIndexOrThrow("is_clone")) == 1,
        action = c.getInt(c.getColumnIndexOrThrow("action")),
        ruleName = c.getString(c.getColumnIndexOrThrow("rule_name")) ?: "",
        aiScore = c.getFloat(c.getColumnIndexOrThrow("ai_score")),
        source = c.getInt(c.getColumnIndexOrThrow("source")),
        feedback = c.getInt(c.getColumnIndexOrThrow("feedback")),
        hidden = c.getInt(c.getColumnIndexOrThrow("hidden")) == 1
    )

    private fun readRule(c: Cursor) = FilterRule(
        id = c.getLong(c.getColumnIndexOrThrow("_id")),
        name = c.getString(c.getColumnIndexOrThrow("name")) ?: "未命名规则",
        enabled = c.getInt(c.getColumnIndexOrThrow("enabled")) == 1,
        priority = c.getInt(c.getColumnIndexOrThrow("priority")),
        appMode = c.getInt(c.getColumnIndexOrThrow("app_mode")),
        apps = parseList(c.getString(c.getColumnIndexOrThrow("apps"))),
        matchMode = c.getInt(c.getColumnIndexOrThrow("match_mode")),
        keywords = parseList(c.getString(c.getColumnIndexOrThrow("keywords"))),
        regex = c.getString(c.getColumnIndexOrThrow("regex")) ?: "",
        skipOngoing = c.getInt(c.getColumnIndexOrThrow("skip_ongoing")) == 1,
        skipMedia = c.getInt(c.getColumnIndexOrThrow("skip_media")) == 1,
        skipConversation = c.getInt(c.getColumnIndexOrThrow("skip_conversation")) == 1,
        effectDismiss = c.getInt(c.getColumnIndexOrThrow("effect_dismiss")) == 1,
        muteSeconds = c.getInt(c.getColumnIndexOrThrow("mute_seconds")),
        webhookUrl = c.getString(c.getColumnIndexOrThrow("webhook_url")) ?: "",
        webhookBody = c.getString(c.getColumnIndexOrThrow("webhook_body")) ?: "",
        actionButtons = c.getString(c.getColumnIndexOrThrow("action_buttons")) ?: "",
        tts = c.getInt(c.getColumnIndexOrThrow("tts")) == 1,
        bark = c.getInt(c.getColumnIndexOrThrow("bark")) == 1,
        hitCount = c.getLong(c.getColumnIndexOrThrow("hit_count")),
        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"))
    )

    private fun parseList(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            JSONArray(raw).toStringList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    companion object {
        private const val DB_NAME = "notifyforward.db"
        private const val DB_VERSION = 3

        @Volatile
        private var instance: DbHelper? = null

        fun get(context: Context): DbHelper = instance ?: synchronized(this) {
            instance ?: DbHelper(context.applicationContext).also { instance = it }
        }

        fun startOfToday(): Long {
            val c = java.util.Calendar.getInstance()
            c.set(java.util.Calendar.HOUR_OF_DAY, 0)
            c.set(java.util.Calendar.MINUTE, 0)
            c.set(java.util.Calendar.SECOND, 0)
            c.set(java.util.Calendar.MILLISECOND, 0)
            return c.timeInMillis
        }
    }
}
