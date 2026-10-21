package com.notifyforward.app.util

import android.content.Context
import android.net.Uri
import com.notifyforward.app.data.DbHelper
import com.notifyforward.app.model.FilterRule
import org.json.JSONArray
import org.json.JSONObject

/** 规则导入导出（JSON）。格式与参考应用一致：{"filterRules":[...]} */
object RuleIO {

    fun exportJson(context: Context): String {
        val rules = DbHelper.get(context).getAllRules()
        val arr = JSONArray()
        rules.forEach { arr.put(it.toJson()) }
        return JSONObject().put("filterRules", arr).toString(2)
    }

    /** 返回成功导入的条数。 */
    fun importJson(context: Context, raw: String): Int {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return 0
        val array = when {
            trimmed.startsWith("[") -> JSONArray(trimmed)
            trimmed.startsWith("{") -> {
                val o = JSONObject(trimmed)
                o.optJSONArray("filterRules") ?: JSONArray().put(o)
            }
            else -> return 0
        }
        val db = DbHelper.get(context)
        var count = 0
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val rule = FilterRule.fromJson(o)
            if (rule.name.isBlank()) continue
            db.saveRule(rule.copy(id = 0L, hitCount = 0L))
            count++
        }
        return count
    }

    fun writeToUri(context: Context, uri: Uri, text: String): Boolean = runCatching {
        context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        true
    }.getOrDefault(false)

    fun readFromUri(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
    }.getOrNull()
}
