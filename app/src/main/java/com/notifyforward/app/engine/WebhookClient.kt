package com.notifyforward.app.engine

import com.notifyforward.app.model.FilterRule
import com.notifyforward.app.model.NotifRecord
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/** Webhook 转发：把通知内容按模板 POST 到指定地址。 */
object WebhookClient {

    data class Result(val ok: Boolean, val code: Int, val message: String)

    const val DEFAULT_BODY = """{
  "app": "{app}",
  "package": "{package}",
  "title": "{title}",
  "message": "{message}",
  "channel": "{channel}",
  "uid": {uid},
  "clone": {clone},
  "when": {when}
}"""

    fun send(url: String, bodyTemplate: String, r: NotifRecord, rule: FilterRule): Result {
        if (url.isBlank()) return Result(false, 0, "未填写 Webhook 地址")
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return Result(false, 0, "地址必须以 http(s):// 开头")
        }
        val body = fill(bodyTemplate.ifBlank { DEFAULT_BODY }, r, rule)
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(fillUrl(url, r, rule)).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                instanceFollowRedirects = false
                connectTimeout = 8000
                readTimeout = 8000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("User-Agent", "NotifyForward/1.0")
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            Result(code in 200..299, code, text.take(180).ifBlank { "HTTP $code" })
        } catch (e: Exception) {
            Result(false, 0, e.message ?: "网络请求失败")
        } finally {
            conn?.disconnect()
        }
    }

    private fun values(r: NotifRecord, rule: FilterRule): Map<String, String> = mapOf(
        "app" to r.displayAppName, "package" to r.packageName,
        "title" to r.title, "message" to r.displayText, "channel" to r.channelId,
        "uid" to r.uid.toString(), "user" to r.userId.toString(),
        "clone" to r.clone.toString(), "when" to r.postTime.toString(), "rule" to rule.name,
        "filterbox.field.APP_NAME" to r.displayAppName,
        "filterbox.field.PACKAGE_NAME" to r.packageName,
        "filterbox.field.CHANNEL_ID" to r.channelId,
        "filterbox.field.WHEN" to r.postTime.toString(),
        "android.title" to r.title, "android.text" to r.displayText
    )

    // 只展开模板本身；通知正文中的 {message} 等文本必须原样保留。
    private fun expand(template: String, values: Map<String, String>, escape: (String) -> String): String =
        Regex("\\{([a-zA-Z_.]+)\\}").replace(template) { match ->
            values[match.groupValues[1]]?.let(escape) ?: match.value
        }

    fun fill(template: String, r: NotifRecord, rule: FilterRule): String =
        expand(template, values(r, rule), ::jsonEscape)

    fun fillUrl(template: String, r: NotifRecord, rule: FilterRule): String =
        expand(template, values(r, rule)) { java.net.URLEncoder.encode(it, "UTF-8") }

    private fun jsonEscape(s: String): String = JSONObject.quote(s).removeSurrounding("\"")
}
