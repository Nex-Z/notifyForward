package com.notifyforward.app.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 过滤规则。
 *
 * 结构对齐参考应用（通知滤盒）：规则名 + 过滤范围（全部/仅这些应用/排除这些应用）+
 * 文本匹配方式 + 通知类型豁免 + 一组效果（消除/静音/Webhook/触发通知按钮/朗读）。
 */
data class FilterRule(
    val id: Long = 0L,
    val name: String = "",
    val enabled: Boolean = true,
    val priority: Int = 0,
    val appMode: Int = AppMode.ALL,
    val apps: List<String> = emptyList(),
    val matchMode: Int = MatchMode.ANY,
    val keywords: List<String> = emptyList(),
    val regex: String = "",
    val skipOngoing: Boolean = true,
    val skipMedia: Boolean = true,
    val skipConversation: Boolean = false,
    val effectDismiss: Boolean = true,
    val muteSeconds: Int = 0,
    val webhookUrl: String = "",
    val webhookBody: String = "",
    val actionButtons: String = "",
    val tts: Boolean = false,
    val bark: Boolean = false,
    val hitCount: Long = 0L,
    val createdAt: Long = System.currentTimeMillis()
) {
    object AppMode {
        const val ALL = 0
        const val ONLY = 1
        const val EXCEPT = 2
    }

    object MatchMode {
        const val ANY = 0
        const val ALL = 1
        const val NONE = 2
        const val REGEX = 3
        const val JSON_REGEX = 4

        /** 不筛选文本，命中作用域内的全部通知（适合「全部转发」场景）。 */
        const val ALL_NOTIFICATIONS = 5
    }

    val hasEffect: Boolean
        get() = effectDismiss || muteSeconds > 0 || webhookUrl.isNotBlank() || tts ||
            actionButtons.isNotBlank() || bark

    fun summary(): String {
        val scope = when (appMode) {
            AppMode.ONLY -> if (apps.isEmpty()) "未指定应用" else "${apps.size} 个应用"
            AppMode.EXCEPT -> "排除 ${apps.size} 个"
            else -> "全部应用"
        }
        val match = when {
            matchMode == MatchMode.ALL_NOTIFICATIONS -> "全部通知"
            keywords.isEmpty() -> if (matchMode == MatchMode.REGEX) "正则匹配" else "自定义规则"
            matchMode == MatchMode.ALL -> "含全部 ${keywords.size} 个词"
            matchMode == MatchMode.NONE -> "不含「${keywords.first()}」"
            matchMode == MatchMode.REGEX -> "正则匹配"
            matchMode == MatchMode.JSON_REGEX -> "字段正则"
            keywords.size == 1 -> "含「${keywords.first()}」"
            else -> "含「${keywords.first()}」等 ${keywords.size} 个词"
        }
        val effects = buildList {
            if (effectDismiss) add("拦截")
            if (muteSeconds > 0) add("静音")
            if (webhookUrl.isNotBlank()) add("Webhook")
            if (bark) add("Bark")
            if (actionButtons.isNotBlank()) add("点击按钮")
            if (tts) add("朗读")
        }.joinToString(" · ").ifBlank { "仅记录" }
        return "$scope · $match · $effects"
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("enabled", enabled)
        put("priority", priority)
        put("appMode", appMode)
        put("apps", JSONArray(apps))
        put("matchMode", matchMode)
        put("keywords", JSONArray(keywords))
        put("regex", regex)
        put("skipOngoing", skipOngoing)
        put("skipMedia", skipMedia)
        put("skipConversation", skipConversation)
        put("effectDismiss", effectDismiss)
        put("muteSeconds", muteSeconds)
        put("webhookUrl", webhookUrl)
        put("webhookBody", webhookBody)
        put("actionButtons", actionButtons)
        put("tts", tts)
        put("bark", bark)
    }

    companion object {
        fun fromJson(o: JSONObject): FilterRule = FilterRule(
            name = o.optString("name", "导入的规则"),
            enabled = o.optBoolean("enabled", true),
            priority = o.optInt("priority", 0),
            appMode = o.optInt("appMode", AppMode.ALL),
            apps = o.optJSONArray("apps").toStringList(),
            matchMode = o.optInt("matchMode", MatchMode.ANY),
            keywords = o.optJSONArray("keywords").toStringList(),
            regex = o.optString("regex", ""),
            skipOngoing = o.optBoolean("skipOngoing", true),
            skipMedia = o.optBoolean("skipMedia", true),
            skipConversation = o.optBoolean("skipConversation", false),
            effectDismiss = o.optBoolean("effectDismiss", true),
            muteSeconds = o.optInt("muteSeconds", 0),
            webhookUrl = o.optString("webhookUrl", ""),
            webhookBody = o.optString("webhookBody", ""),
            actionButtons = o.optString("actionButtons", ""),
            tts = o.optBoolean("tts", false),
            bark = o.optBoolean("bark", false)
        )
    }
}

fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    val out = ArrayList<String>(length())
    for (i in 0 until length()) {
        val s = optString(i, "")
        if (s.isNotBlank()) out.add(s)
    }
    return out
}
