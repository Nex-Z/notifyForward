package com.notifyforward.app.engine

import android.content.Context
import android.util.Base64
import com.notifyforward.app.data.Settings
import com.notifyforward.app.model.NotifRecord
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Bark 推送（iOS）。
 *
 * 支持 Bark 官方「推送加密」：把整个推送内容序列化为 JSON，用与 Bark App 相同的
 * AES-CBC(PKCS7) 密钥加密，仅上送 ciphertext + iv，服务器与 APNs 都看不到明文。
 * 密钥长度 16/24/32 个 ASCII 字符，分别对应 AES-128/192/256。
 */
object BarkClient {

    data class Result(val ok: Boolean, val code: Int, val message: String)

    private const val IV_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

    fun send(context: Context, record: NotifRecord): Result {
        val s = Settings.get(context)
        val server = s.barkServer.trim().trimEnd('/')
        val deviceKey = s.barkKey.trim()
        if (deviceKey.isBlank()) return Result(false, 0, "未配置 Bark Device Key")
        if (!server.startsWith("http://") && !server.startsWith("https://")) {
            return Result(false, 0, "Bark 服务器地址需以 http(s):// 开头")
        }

        val title = record.title.ifBlank { record.displayAppName }
        val body = record.displayText.ifBlank { "（无正文）" }
        val payload = JSONObject().apply {
            put("title", title)
            put("subtitle", record.displayAppName)
            put("body", body)
            put("group", s.barkGroup.ifBlank { "通知哨兵" })
            put("level", s.barkLevel)
            put("isArchive", if (s.barkArchive) 1 else 0)
        }

        val form: String
        if (s.barkEncrypt) {
            val error = validateSecret(s.barkSecret)
            if (error != null) return Result(false, 0, error)
            val encrypted = encrypt(payload.toString(), s.barkSecret)
                ?: return Result(false, 0, "加密失败，请检查密钥")
            // 加密模式下不发送任何明文字段，避免泄露
            form = "ciphertext=" + enc(encrypted.first) + "&iv=" + enc(encrypted.second)
        } else {
            form = buildString {
                append("title=").append(enc(payload.optString("title")))
                append("&subtitle=").append(enc(payload.optString("subtitle")))
                append("&body=").append(enc(payload.optString("body")))
                append("&group=").append(enc(payload.optString("group")))
                append("&level=").append(enc(payload.optString("level")))
                append("&isArchive=").append(if (s.barkArchive) "1" else "0")
            }
        }

        val url = "$server/${enc(deviceKey)}"
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                instanceFollowRedirects = false
                connectTimeout = 8000
                readTimeout = 8000
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
                setRequestProperty("User-Agent", "NotifyForward/1.0")
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(form) }
            val httpCode = conn.responseCode
            val text = (if (httpCode in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            val apiCode = runCatching { JSONObject(text).optInt("code", -1) }.getOrDefault(-1)
            val ok = httpCode in 200..299 && apiCode == 200
            Result(ok, httpCode, text.take(200).ifBlank { "HTTP $httpCode" })
        } catch (e: Exception) {
            Result(false, 0, e.message ?: "网络请求失败")
        } finally {
            conn?.disconnect()
        }
    }

    fun test(context: Context): Result = send(
        context,
        NotifRecord(
            packageName = "com.example.demo",
            appName = "通知哨兵",
            title = "Bark 测试推送",
            text = "如果你看到这条消息，说明 Bark 转发已配置成功。",
            postTime = System.currentTimeMillis()
        )
    )

    /** 返回 null 表示合法；否则为错误提示。 */
    fun validateSecret(secret: String): String? {
        val bytes = secret.toByteArray(Charsets.UTF_8)
        if (secret.any { it.code > 127 }) return "加密密钥只能使用 ASCII 字符"
        if (bytes.size != 16 && bytes.size != 24 && bytes.size != 32) {
            return "加密密钥需为 16 / 24 / 32 个字符（当前 ${bytes.size}）"
        }
        return null
    }

    fun algorithmOf(secret: String): String? = when (secret.toByteArray(Charsets.UTF_8).size) {
        16 -> "AES-128"
        24 -> "AES-192"
        32 -> "AES-256"
        else -> null
    }

    /** 返回 Pair(Base64 密文, IV 字符串)。 */
    private fun encrypt(plain: String, secret: String): Pair<String, String>? = runCatching {
        val keyBytes = secret.toByteArray(Charsets.UTF_8)
        val iv = randomIv()
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(keyBytes, "AES"),
            IvParameterSpec(iv.toByteArray(Charsets.UTF_8))
        )
        val cipherBytes = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(cipherBytes, Base64.NO_WRAP) to iv
    }.getOrNull()

    private fun randomIv(): String {
        val r = SecureRandom()
        val sb = StringBuilder(16)
        repeat(16) { sb.append(IV_CHARS[r.nextInt(IV_CHARS.length)]) }
        return sb.toString()
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
