package com.notifyforward.app.engine

import com.notifyforward.app.model.FilterRule
import com.notifyforward.app.model.NotifRecord
import org.json.JSONObject

/** 规则匹配引擎：无状态、纯函数，便于在历史记录上回放测试。 */
object RuleEngine {

    fun match(rule: FilterRule, r: NotifRecord): Boolean {
        if (!rule.enabled) return false
        if (!scopeMatches(rule, r.packageName)) return false
        if (rule.skipOngoing && r.ongoing) return false
        if (rule.skipMedia && r.media) return false
        if (rule.skipConversation && r.conversation) return false
        return textMatches(rule, r)
    }

    fun scopeMatches(rule: FilterRule, pkg: String): Boolean = when (rule.appMode) {
        FilterRule.AppMode.ONLY -> rule.apps.contains(pkg)
        FilterRule.AppMode.EXCEPT -> !rule.apps.contains(pkg)
        else -> true
    }

    private fun textMatches(rule: FilterRule, r: NotifRecord): Boolean {
        if (rule.matchMode == FilterRule.MatchMode.ALL_NOTIFICATIONS) return true

        val haystack = buildString {
            append(r.title).append('\n').append(r.text).append('\n').append(r.bigText)
            if (r.subText.isNotBlank()) append('\n').append(r.subText)
        }
        val lower = haystack.lowercase()
        val keywords = rule.keywords.map { it.trim() }.filter { it.isNotEmpty() }

        // 正则规则使用独立的 regex 字段，不依赖关键词列表。
        if (rule.matchMode == FilterRule.MatchMode.REGEX) return regexMatches(rule.regex, haystack)
        if (rule.matchMode == FilterRule.MatchMode.JSON_REGEX) return regexMatches(rule.regex, buildJson(r))
        if (keywords.isEmpty()) {
            // 没有关键词时，只有「仅指定应用」的规则可以视为“该应用全部通知”
            return rule.appMode == FilterRule.AppMode.ONLY
        }
        return when (rule.matchMode) {
            FilterRule.MatchMode.ANY -> keywords.any { lower.contains(it.lowercase()) }
            FilterRule.MatchMode.ALL -> keywords.all { lower.contains(it.lowercase()) }
            FilterRule.MatchMode.NONE -> keywords.none { lower.contains(it.lowercase()) }
            FilterRule.MatchMode.REGEX -> regexMatches(rule.regex, haystack)
            FilterRule.MatchMode.JSON_REGEX -> regexMatches(rule.regex, buildJson(r))
            else -> false
        }
    }

    private fun regexMatches(pattern: String, input: String): Boolean {
        if (pattern.isBlank()) return false
        return try {
            Regex(pattern, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .containsMatchIn(input)
        } catch (_: Exception) {
            false
        }
    }

    fun buildJson(r: NotifRecord): String = JSONObject().apply {
        put("app", r.appName)
        put("package", r.packageName)
        put("title", r.title)
        put("text", r.text)
        put("bigText", r.bigText)
        put("subText", r.subText)
        put("channel", r.channelId)
        put("category", r.category)
        put("ongoing", r.ongoing)
        put("media", r.media)
        put("conversation", r.conversation)
        put("when", r.postTime)
    }.toString()

    /** 按优先级返回第一个命中的规则。 */
    fun firstMatch(rules: List<FilterRule>, r: NotifRecord): FilterRule? {
        val ordered = rules.filter { it.enabled }.sortedWith(
            compareByDescending<FilterRule> { it.priority }.thenBy { it.id }
        )
        return ordered.firstOrNull { match(it, r) }
    }

    /** Filtering uses the first matching rule; forwarding evaluates its own app/content scope. */
    fun executionMatches(rules: List<FilterRule>, r: NotifRecord): List<FilterRule> {
        val matches = rules.filter { match(it, r) }.sortedWith(compareByDescending<FilterRule> { it.priority }.thenBy { it.id })
        val filter = matches.firstOrNull { it.effectDismiss || it.muteSeconds > 0 || it.tts || it.actionButtons.isNotBlank() }
        return matches.mapNotNull { rule ->
            if (rule == filter) {
                if (r.category == "call") rule.copy(effectDismiss = false, muteSeconds = 0, actionButtons = "") else rule
            }
            else if (rule.bark || rule.webhookUrl.isNotBlank()) rule.copy(effectDismiss = false, muteSeconds = 0, tts = false, actionButtons = "")
            else null
        }
    }

    /** 在历史记录上回放统计命中数量（用于编辑规则时的“测试命中”）。 */
    fun countHits(rule: FilterRule, records: List<NotifRecord>): Int =
        records.count { match(rule, it) }

    fun validateRegex(rule: FilterRule): String? {
        val needsRegex = rule.matchMode == FilterRule.MatchMode.REGEX ||
            rule.matchMode == FilterRule.MatchMode.JSON_REGEX
        if (!needsRegex) return null
        if (rule.regex.isBlank()) return "请填写正则表达式"
        return try {
            Regex(rule.regex)
            null
        } catch (e: Exception) {
            "正则表达式有误：${e.message}"
        }
    }
}
