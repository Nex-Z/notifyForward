package com.notifyforward.app.data

import android.content.Context
import com.tencent.mmkv.MMKV

/**
 * 应用设置。
 *
 * 使用 MMKV 的 MULTI_PROCESS_MODE：UI 进程修改后，`:service` 进程能立即读到，
 * 这是把通知监听拆到独立进程的前提（SharedPreferences 跨进程不刷新）。
 * 首次启动会把旧的 SharedPreferences 数据迁移过来。
 */
class Settings private constructor(context: Context) {

    private val kv: MMKV = MMKV.mmkvWithID(ID, MMKV.MULTI_PROCESS_MODE)

    init {
        migrateFromSharedPreferences(context)
    }

    var masterEnabled: Boolean
        get() = kv.decodeBool(KEY_MASTER, true)
        set(v) { kv.encode(KEY_MASTER, v) }

    var aiEnabled: Boolean
        get() = kv.decodeBool(KEY_AI, true)
        set(v) { kv.encode(KEY_AI, v) }

    /** 0.5 - 0.99 */
    var aiThreshold: Float
        get() = kv.decodeFloat(KEY_AI_THRESHOLD, 0.8f)
        set(v) { kv.encode(KEY_AI_THRESHOLD, v) }

    var aiShareData: Boolean
        get() = kv.decodeBool(KEY_AI_SHARE, false)
        set(v) { kv.encode(KEY_AI_SHARE, v) }

    var excludeApps: Set<String>
        get() = kv.decodeStringSet(KEY_EXCLUDES, emptySet()) ?: emptySet()
        set(v) { kv.encode(KEY_EXCLUDES, v) }

    var retentionDays: Int
        get() = kv.decodeInt(KEY_RETENTION, 30)
        set(v) { kv.encode(KEY_RETENTION, v) }

    var showFilterNotice: Boolean
        get() = kv.decodeBool(KEY_NOTICE, true)
        set(v) { kv.encode(KEY_NOTICE, v) }

    var smartMute: Boolean
        get() = kv.decodeBool(KEY_SMART_MUTE, false)
        set(v) { kv.encode(KEY_SMART_MUTE, v) }

    var smartMuteBurst: Int
        get() = kv.decodeInt(KEY_SMART_BURST, 5)
        set(v) { kv.encode(KEY_SMART_BURST, v) }

    var smartMuteWindowSec: Int
        get() = kv.decodeInt(KEY_SMART_WINDOW, 60)
        set(v) { kv.encode(KEY_SMART_WINDOW, v) }

    var smartMuteSeconds: Int
        get() = kv.decodeInt(KEY_SMART_DURATION, 30)
        set(v) { kv.encode(KEY_SMART_DURATION, v) }

    var keepAlive: Boolean
        get() = kv.decodeBool(KEY_KEEP_ALIVE, false)
        set(v) { kv.encode(KEY_KEEP_ALIVE, v) }

    var onboarded: Boolean
        get() = kv.decodeBool(KEY_ONBOARDED, false)
        set(v) { kv.encode(KEY_ONBOARDED, v) }

    /** 被过滤时是否持续显示一条最小化通知。 */
    var minimizedNotice: Boolean
        get() = kv.decodeBool(KEY_MINIMIZED, false)
        set(v) { kv.encode(KEY_MINIMIZED, v) }

    var ttsEnabled: Boolean
        get() = kv.decodeBool(KEY_TTS, false)
        set(v) { kv.encode(KEY_TTS, v) }

    /** 勿扰静音的结束时间，跨进程共享以便进程重启后兜底恢复。 */
    var dndUntil: Long
        get() = kv.decodeLong(KEY_DND_UNTIL, 0L)
        set(v) { kv.encode(KEY_DND_UNTIL, v) }

    // ---------------- Bark 推送 ----------------

    var barkServer: String
        get() = kv.decodeString(KEY_BARK_SERVER, "https://api.day.app") ?: "https://api.day.app"
        set(v) { kv.encode(KEY_BARK_SERVER, v.trim().trimEnd('/')) }

    var barkKey: String
        get() = kv.decodeString(KEY_BARK_KEY, "") ?: ""
        set(v) { kv.encode(KEY_BARK_KEY, v.trim()) }

    var barkGroup: String
        get() = kv.decodeString(KEY_BARK_GROUP, "通知哨兵") ?: "通知哨兵"
        set(v) { kv.encode(KEY_BARK_GROUP, v.trim()) }

    /** active / timeSensitive / passive / critical */
    var barkLevel: String
        get() = kv.decodeString(KEY_BARK_LEVEL, "active") ?: "active"
        set(v) { kv.encode(KEY_BARK_LEVEL, v) }

    var barkEncrypt: Boolean
        get() = kv.decodeBool(KEY_BARK_ENCRYPT, false)
        set(v) { kv.encode(KEY_BARK_ENCRYPT, v) }

    /** 与 Bark App 中「推送加密」设置的密钥一致。 */
    var barkSecret: String
        get() = kv.decodeString(KEY_BARK_SECRET, "") ?: ""
        set(v) { kv.encode(KEY_BARK_SECRET, v) }

    var barkArchive: Boolean
        get() = kv.decodeBool(KEY_BARK_ARCHIVE, true)
        set(v) { kv.encode(KEY_BARK_ARCHIVE, v) }

    fun isExcluded(pkg: String): Boolean = excludeApps.contains(pkg)

    fun addExclude(pkg: String) {
        excludeApps = excludeApps + pkg
    }

    fun removeExclude(pkg: String) {
        excludeApps = excludeApps - pkg
    }

    private fun migrateFromSharedPreferences(context: Context) {
        if (kv.decodeBool(KEY_MIGRATED, false)) return
        runCatching {
            val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            sp.all.forEach { (key, value) ->
                if (kv.containsKey(key)) return@forEach
                when (value) {
                    is Boolean -> kv.encode(key, value)
                    is Float -> kv.encode(key, value)
                    is Int -> kv.encode(key, value)
                    is Long -> kv.encode(key, value)
                    is String -> kv.encode(key, value)
                    is Set<*> -> kv.encode(key, value.filterIsInstance<String>().toSet())
                }
            }
        }
        kv.encode(KEY_MIGRATED, true)
    }

    companion object {
        private const val ID = "settings_mp"

        private const val KEY_MASTER = "master_enabled"
        private const val KEY_AI = "ai_enabled"
        private const val KEY_AI_THRESHOLD = "ai_threshold"
        private const val KEY_AI_SHARE = "ai_share"
        private const val KEY_EXCLUDES = "exclude_apps"
        private const val KEY_RETENTION = "retention_days"
        private const val KEY_NOTICE = "show_filter_notice"
        private const val KEY_MINIMIZED = "minimized_notice"
        private const val KEY_SMART_MUTE = "smart_mute"
        private const val KEY_SMART_BURST = "smart_burst"
        private const val KEY_SMART_WINDOW = "smart_window"
        private const val KEY_SMART_DURATION = "smart_duration"
        private const val KEY_KEEP_ALIVE = "keep_alive"
        private const val KEY_ONBOARDED = "onboarded"
        private const val KEY_TTS = "tts_enabled"
        private const val KEY_DND_UNTIL = "dnd_until"
        private const val KEY_BARK_SERVER = "bark_server"
        private const val KEY_BARK_KEY = "bark_key"
        private const val KEY_BARK_GROUP = "bark_group"
        private const val KEY_BARK_LEVEL = "bark_level"
        private const val KEY_BARK_ENCRYPT = "bark_encrypt"
        private const val KEY_BARK_SECRET = "bark_secret"
        private const val KEY_BARK_ARCHIVE = "bark_archive"
        private const val KEY_MIGRATED = "sp_migrated_v1"

        @Volatile
        private var instance: Settings? = null

        fun get(context: Context): Settings = instance ?: synchronized(this) {
            instance ?: Settings(context.applicationContext).also { instance = it }
        }
    }
}
