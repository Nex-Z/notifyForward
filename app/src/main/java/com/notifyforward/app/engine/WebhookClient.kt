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

    fun fill(template: String, r: NotifRecord, rule: FilterRule): String {
        val app = jsonEscape(r.displayAppName)
        val pkg = jsonEscape(r.packageName)
        val title = jsonEscape(r.title)
        val message = jsonEscape(r.displayText)
        val channel = jsonEscape(r.channelId)
        return template
            .replace("{app}", app)
            .replace("{package}", pkg)
            .replace("{title}", title)
            .replace("{message}", message)
            .replace("{channel}", channel)
            .replace("{uid}", r.uid.toString())
            .replace("{user}", r.userId.toString())
            .replace("{clone}", if (r.clone) "true" else "false")
            .replace("{when}", r.postTime.toString())
            .replace("{rule}", jsonEscape(rule.name))
            .replace("{filterbox.field.APP_NAME}", app)
            .replace("{filterbox.field.PACKAGE_NAME}", pkg)
            .replace("{filterbox.field.CHANNEL_ID}", channel)
            .replace("{filterbox.field.WHEN}", r.postTime.toString())
            .replace("{android.title}", title)
            .replace("{android.text}", message)
    }

    fun fillUrl(template: String, r: NotifRecord, rule: FilterRule): String {
        fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
        return template
            .replace("{app}", enc(r.displayAppName))
            .replace("{package}", enc(r.packageName))
            .replace("{title}", enc(r.title))
            .replace("{message}", enc(r.displayText))
            .replace("{channel}", enc(r.channelId))
            .replace("{uid}", r.uid.toString())
            .replace("{user}", r.userId.toString())
            .replace("{clone}", if (r.clone) "true" else "false")
            .replace("{when}", r.postTime.toString())
            .replace("{rule}", enc(rule.name))
    }

    private fun jsonEscape(s: String): String = JSONObject.quote(s).removeSurrounding("\"")
}
