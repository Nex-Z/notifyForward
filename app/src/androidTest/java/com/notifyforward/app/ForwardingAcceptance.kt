package com.notifyforward.app

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Base64
import com.notifyforward.app.data.DbHelper
import com.notifyforward.app.data.Settings
import com.notifyforward.app.engine.BarkClient
import com.notifyforward.app.engine.RuleEngine
import com.notifyforward.app.engine.WebhookClient
import com.notifyforward.app.model.FilterRule
import com.notifyforward.app.model.NotifRecord
import org.json.JSONObject
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Runs on a dedicated emulator with notification access granted. No real recipient is contacted. */
class ForwardingAcceptance : Instrumentation() {
    private val results = StringBuilder()
    private var passed = 0
    private var realBarkUrl = ""
    private var benchmark = false
    private var benchmarkDataset = "diagnostic-v1.json"

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        realBarkUrl = arguments?.getString("realBarkUrl").orEmpty()
        benchmarkDataset = arguments?.getString("dataset") ?: "diagnostic-v1.json"
        benchmark = arguments?.getString("benchmark") == "true"
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            if (benchmark) {
                SemanticBenchmark.run(targetContext, context, benchmarkDataset) { line -> sendStatus(0, Bundle().apply { putString("stream", line) }) }
                result.putString("stream", "BENCHMARK_COMPLETE")
                finish(Activity.RESULT_OK, result)
                return
            }
            waitForIdleSync()
            runSuite()
            result.putString("stream", "\n$results\nPASS: $passed acceptance cases\n")
            finish(Activity.RESULT_OK, result)
        } catch (e: Throwable) {
            result.putString("stream", "\n$results\nFAIL: ${e.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun case(name: String, block: () -> Unit) {
        block()
        passed++
        results.append("PASS $name\n")
        sendStatus(0, Bundle().apply { putString("stream", "PASS $name\n") })
    }

    private fun runSuite() {
        val ctx = targetContext
        com.tencent.mmkv.MMKV.initialize(ctx)
        check(androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)) {
            "Grant notification access on a dedicated test emulator before running this suite"
        }
        val settings = Settings.get(ctx)
        // Snapshot every setting changed by this suite, never print credentials.
        val serverBefore = settings.barkServer
        val keyBefore = settings.barkKey
        val encryptBefore = settings.barkEncrypt
        val secretBefore = settings.barkSecret
        val masterBefore = settings.masterEnabled
        val aiBefore = settings.aiEnabled
        val muteBefore = settings.smartMute
        val noticeBefore = settings.showFilterNotice
        val excludesBefore = settings.excludeApps
        results.append("Existing Bark key configured: ${keyBefore.isNotBlank()}; encryption: $encryptBefore\n")
        val db = DbHelper.get(ctx)
        val marker = "NF_ACCEPT_${System.currentTimeMillis()}"
        var ruleId = 0L
        Receiver().use { receiver ->
            try {
                settings.barkServer = receiver.url
                settings.barkKey = "acceptance-only"
                settings.barkEncrypt = false
                settings.masterEnabled = true
                settings.aiEnabled = false
                settings.smartMute = false
                settings.showFilterNotice = false
                settings.excludeApps = excludesBefore - "com.android.shell"
                LearningAcceptance.run(ctx, context) { name, block -> case(name, block) }
                val record = NotifRecord(packageName = "test.only", appName = "验收应用", title = "标题 {message}", text = "中文 \"引号\"\n换行与 emoji 🔔")
                val rule = FilterRule(name = marker, effectDismiss = false)

                case("app scoped forwarding is independent of filtering priority") {
                    val filter = rule.copy(id = 1, priority = 20, effectDismiss = true, matchMode = FilterRule.MatchMode.ALL_NOTIFICATIONS)
                    val forward = rule.copy(id = 2, appMode = FilterRule.AppMode.ONLY, apps = listOf(record.packageName), bark = true, matchMode = FilterRule.MatchMode.ALL_NOTIFICATIONS)
                    check(RuleEngine.executionMatches(listOf(filter, forward), record).map { it.id } == listOf(1L, 2L))
                    check(RuleEngine.executionMatches(listOf(filter, forward), record.copy(packageName = "other.app")).map { it.id } == listOf(1L))
                }
                case("call protection preserves forwarding but disables dismissal mute and notification actions") {
                    val dangerous = rule.copy(effectDismiss = true, muteSeconds = 30, actionButtons = "拒绝", bark = true, skipOngoing = false, matchMode = FilterRule.MatchMode.ALL_NOTIFICATIONS)
                    val planned = RuleEngine.executionMatches(listOf(dangerous), record.copy(category = "call")).single()
                    check(!planned.effectDismiss && planned.muteSeconds == 0 && planned.actionButtons.isEmpty() && planned.bark)
                }
                case("forwarding attempts persist pending and HTTP result and cascade on deletion") {
                    val id = db.insertNotification(record)
                    val attempt = db.beginForward(id, "Webhook", marker)
                    check(db.forwardingSummary(id).contains("发送中"))
                    check(db.queryForwarded().any { it.id == id })
                    db.finishForward(attempt, true, 200)
                    check(db.forwardingSummary(id).contains("HTTP 200"))
                    db.deleteNotification(id)
                    check(db.forwardingSummary(id).isEmpty())
                }
                case("regex rules match without keywords and reject nonmatches") {
                    val regex = rule.copy(matchMode = FilterRule.MatchMode.REGEX, regex = "中文")
                    check(RuleEngine.match(regex, record))
                    check(!RuleEngine.match(regex.copy(regex = "absent", appMode = FilterRule.AppMode.ONLY, apps = listOf(record.packageName)), record))
                    check(RuleEngine.match(regex.copy(matchMode = FilterRule.MatchMode.JSON_REGEX, regex = "test.only"), record))
                }
                case("webhook UTF-8, JSON escaping and literal template tokens") {
                    check(WebhookClient.send(receiver.url, "", record, rule).ok)
                    val body = JSONObject(receiver.next().body)
                    check(body.getString("title") == record.title)
                    check(body.getString("message") == record.displayText)
                }
                case("Bark plain POST payload received intact") {
                    check(BarkClient.send(ctx, record).ok)
                    val request = receiver.next()
                    check(request.path == "/acceptance-only")
                    val form = request.form()
                    check(form["title"] == record.title && form["body"] == record.displayText)
                }
                case("Bark AES encryption decrypts with configured key and random IV") {
                    settings.barkEncrypt = true
                    settings.barkSecret = "0123456789abcdef"
                    check(BarkClient.send(ctx, record).ok)
                    val form = receiver.next().form()
                    check(form.keys == setOf("ciphertext", "iv"))
                    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(settings.barkSecret.toByteArray(), "AES"), IvParameterSpec(form.getValue("iv").toByteArray()))
                    val plain = JSONObject(String(cipher.doFinal(Base64.decode(form.getValue("ciphertext"), Base64.DEFAULT)), Charsets.UTF_8))
                    check(plain.getString("body") == record.displayText)
                    check(plain.getString("title") == record.title)
                    settings.barkEncrypt = false
                }
                case("invalid encryption key never falls back to plaintext") {
                    settings.barkEncrypt = true
                    settings.barkSecret = "invalid"
                    check(!BarkClient.send(ctx, record).ok)
                    check(receiver.requests.poll(300, TimeUnit.MILLISECONDS) == null)
                    settings.barkEncrypt = false
                }
                case("Bark rejects HTML/empty 200 and business errors") {
                    for (body in listOf("<html>proxy error</html>", "", "{\"code\":400}")) {
                        receiver.response = body
                        check(!BarkClient.send(ctx, record).ok)
                        receiver.next()
                    }
                    receiver.response = "{\"code\":200}"
                }
                case("HTTP errors and redirects are not delivery success") {
                    for (status in listOf(302, 401, 500)) {
                        receiver.status = status
                        check(!BarkClient.send(ctx, record).ok)
                        receiver.next()
                    }
                    receiver.status = 200
                }
                case("connection refused reports failure") {
                    val closedPort = ServerSocket(0).use { it.localPort }
                    settings.barkServer = "http://127.0.0.1:$closedPort"
                    check(!BarkClient.send(ctx, record).ok)
                    settings.barkServer = receiver.url
                }
                // Instrumentation restarts the target process; rebind the already authorized listener.
                android.service.notification.NotificationListenerService.requestRebind(
                    android.content.ComponentName(ctx, com.notifyforward.app.service.NotifyListenerService::class.java)
                )
                val listener = "com.notifyforward.app/com.notifyforward.app.service.NotifyListenerService"
                shell("cmd notification disallow_listener $listener")
                shell("cmd notification allow_listener $listener")
                val readyDeadline = System.currentTimeMillis() + 20000
                while (System.currentTimeMillis() < readyDeadline) {
                    val live = shell("dumpsys notification").substringAfter("Live notification listeners", "").substringBefore("Notification assistant")
                    if (live.contains("ComponentInfo{com.notifyforward.app/com.notifyforward.app.service.NotifyListenerService}")) break
                    Thread.sleep(500)
                }
                Thread.sleep(1000)
                ruleId = db.saveRule(rule.copy(
                    priority = Int.MAX_VALUE,
                    appMode = FilterRule.AppMode.ONLY,
                    apps = listOf("com.android.shell"),
                    matchMode = FilterRule.MatchMode.REGEX,
                    regex = marker,
                    bark = true,
                    webhookUrl = receiver.url + "/webhook"
                ))
                case("system notification -> listener -> rule -> Bark and webhook -> persisted success") {
                    post(marker + "_normal")
                    val observed = awaitRecord(db, marker + "_normal") { true }
                    results.append("Observed system event: source=${observed.source}, rule=${observed.ruleName}, action=${observed.action}, error=${observed.forwardError}\n")
                    val requests = listOf(receiver.next(), receiver.next())
                    val bark = requests.first { it.path == "/acceptance-only" }.form()
                    check(bark["title"] == marker + "_normal")
                    check(bark["body"] == "acceptance_payload")
                    check(JSONObject(requests.first { it.path == "/webhook" }.body).getString("title") == marker + "_normal")
                    val stored = awaitRecord(db, marker + "_normal") {
                        it.action and (NotifRecord.Action.BARK or NotifRecord.Action.WEBHOOK) == 20
                    }
                    check(stored.ruleName == marker && stored.source == NotifRecord.Source.RULE)
                    check(stored.forwardError.isEmpty())
                }
                case("background system notification still reaches Bark") {
                    shell("input keyevent KEYCODE_HOME")
                    post(marker + "_background")
                    receiver.next(); receiver.next()
                    awaitRecord(db, marker + "_background") { it.action and NotifRecord.Action.BARK != 0 }
                }
                case("failed delivery persists visibly and never marks sent") {
                    receiver.status = 500
                    post(marker + "_failure")
                    receiver.next(); receiver.next()
                    val stored = awaitRecord(db, marker + "_failure") {
                        it.forwardError.contains("Bark") && it.forwardError.contains("Webhook")
                    }
                    check(stored.action and 20 == 0)
                    check(stored.ruleName == marker)
                    receiver.status = 200
                }
                case("disabled rule does not send") {
                    db.setRuleEnabled(ruleId, false)
                    post(marker + "_disabled")
                    awaitRecord(db, marker + "_disabled") { true }
                    check(receiver.requests.poll(1500, TimeUnit.MILLISECONDS) == null)
                }
                case("semantic observation across service process never cancels notification") {
                    settings.aiEnabled = true
                    val title = marker + "_observe"
                    post(title)
                    val stored = awaitRecord(db, title) { true }
                    val learning = com.notifyforward.app.engine.LearningStore(ctx)
                    val deadline = System.currentTimeMillis() + 60000
                    var summary = ""
                    while (System.currentTimeMillis() < deadline) {
                        summary = learning.prediction(stored.id)
                        if (summary.contains("BGE-small-zh")) break
                        Thread.sleep(200)
                    }
                    check(summary.contains("BGE-small-zh")) { "No semantic result: $summary" }
                    check(awaitRecord(db, title) { true }.action == 0)
                    check(shell("dumpsys notification --noredact").contains(title))
                    // Correct a label in UI process; the service must see the replacement.
                    learning.label(stored, 2)
                    learning.label(stored, 1)
                    post(title)
                    val correctionDeadline = System.currentTimeMillis() + 10000
                    var corrected = false
                    while (System.currentTimeMillis() < correctionDeadline) {
                        db.readableDatabase.rawQuery("SELECT summary FROM semantic_predictions p JOIN notifications n ON p.notification_id=n._id WHERE n.title=? AND n._id<>?", arrayOf(title, stored.id.toString())).use { c ->
                            while (c.moveToNext()) if (c.getString(0).contains("采用你")) corrected = c.getString(0).contains("语义判断：正常")
                        }
                        if (corrected) break
                        Thread.sleep(200)
                    }
                    check(corrected) { "Service did not observe corrected label" }
                    learning.label(stored, 0)
                    settings.aiEnabled = false
                }
                case("forwarding still works with semantic observation enabled") {
                    settings.aiEnabled = true
                    db.setRuleEnabled(ruleId, true)
                    post(marker + "_observe_forward")
                    receiver.next(); receiver.next()
                    val stored = awaitRecord(db, marker + "_observe_forward") { it.action and 20 == 20 }
                    check(stored.action and NotifRecord.Action.DISMISS == 0)
                    settings.aiEnabled = false
                }
                if (realBarkUrl.isNotBlank()) {
                    case("REAL Bark endpoint accepts background notification from listener") {
                        val target = java.net.URI(realBarkUrl)
                        settings.barkServer = "${target.scheme}://${target.rawAuthority}"
                        settings.barkKey = target.path.trim('/')
                        settings.barkEncrypt = false
                        db.saveRule(db.getRule(ruleId)!!.copy(enabled = true, webhookUrl = ""))
                        val title = marker + "_IPHONE"
                        shell("input keyevent KEYCODE_HOME")
                        shell("cmd notification post -t $title $title 通知哨兵上线验收：Android通知监听、规则匹配、Bark转发测试。请回复是否收到本条通知。")
                        val stored = awaitRecord(db, title) {
                            it.action and NotifRecord.Action.BARK != 0 || it.forwardError.isNotBlank()
                        }
                        check(stored.action and NotifRecord.Action.BARK != 0) { "Real Bark rejected: ${stored.forwardError}" }
                        results.append("Real receipt marker: $title; HTTP/API accepted; iPhone confirmation pending\n")
                    }
                }
            } finally {
                if (ruleId > 0) db.deleteRule(ruleId)
                settings.barkServer = serverBefore
                settings.barkKey = keyBefore
                settings.barkEncrypt = encryptBefore
                settings.barkSecret = secretBefore
                settings.masterEnabled = masterBefore
                settings.aiEnabled = aiBefore
                settings.smartMute = muteBefore
                settings.showFilterNotice = noticeBefore
                settings.excludeApps = excludesBefore
                db.writableDatabase.delete("notifications", "title LIKE ?", arrayOf("$marker%"))
            }
        }
    }

    private fun post(title: String) {
        shell("cmd notification post -t $title $title acceptance_payload")
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    private fun awaitRecord(db: DbHelper, title: String, predicate: (NotifRecord) -> Boolean): NotifRecord {
        val deadline = System.currentTimeMillis() + 15000
        while (System.currentTimeMillis() < deadline) {
            val record = db.searchNotifications(title).firstOrNull { it.title == title }
            if (record != null && predicate(record)) return record
            Thread.sleep(100)
        }
        error("Notification not processed: $title")
    }

    private data class Request(val path: String, val body: String) {
        fun form(): Map<String, String> = body.split('&').associate {
            val parts = it.split('=', limit = 2)
            URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
        }
    }

    private class Receiver : java.io.Closeable {
        private val server = ServerSocket(0)
        val url = "http://127.0.0.1:${server.localPort}"
        val requests = LinkedBlockingQueue<Request>()
        @Volatile var response = "{\"code\":200,\"message\":\"success\"}"
        @Volatile var status = 200
        init {
            Thread({
                while (!server.isClosed) {
                    try { server.accept().use { accept(it) } } catch (e: Exception) {
                        if (!server.isClosed) android.util.Log.e("AcceptanceReceiver", "Receive failed", e)
                    }
                }
            }, "acceptance-receiver").apply { isDaemon = true; start() }
        }
        private fun accept(socket: Socket) {
            socket.soTimeout = 15000
            val input = socket.getInputStream()
            fun line(): String {
                val out = java.io.ByteArrayOutputStream()
                while (true) {
                    val c = input.read()
                    if (c == -1 || c == 10) break
                    if (c != 13) out.write(c)
                    check(out.size() < 16384)
                }
                return out.toString("UTF-8")
            }
            val first = line()
            var length = 0
            while (true) {
                val header = line()
                if (header.isEmpty()) break
                if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toInt()
            }
            val bytes = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(bytes, read, length - read)
                check(n > 0)
                read += n
            }
            requests.put(Request(first.split(' ')[1], String(bytes, Charsets.UTF_8)))
            val body = response.toByteArray(Charsets.UTF_8)
            socket.getOutputStream().apply {
                write("HTTP/1.1 $status Result\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                write(body)
                flush()
            }
        }
        fun next(): Request = requests.poll(15, TimeUnit.SECONDS) ?: error("Receiver did not receive a request")
        override fun close() { server.close() }
    }
}
