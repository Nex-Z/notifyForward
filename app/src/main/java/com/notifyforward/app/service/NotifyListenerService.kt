package com.notifyforward.app.service

import android.app.Notification
import android.content.ComponentName
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.notifyforward.app.data.DbHelper
import com.notifyforward.app.data.DataEvents
import com.notifyforward.app.data.Settings
import com.notifyforward.app.engine.AiEngine
import com.notifyforward.app.engine.BarkClient
import com.notifyforward.app.engine.DndController
import com.notifyforward.app.engine.RuleEngine
import com.notifyforward.app.engine.TtsSpeaker
import com.notifyforward.app.engine.WebhookClient
import com.notifyforward.app.model.FilterRule
import com.notifyforward.app.model.NotifRecord
import com.notifyforward.app.util.AppInfo
import com.notifyforward.app.util.Notices
import java.util.concurrent.Executors

/**
 * 通知监听服务：系统里所有通知都会经过这里。
 *
 * 处理顺序与参考应用一致：总开关 → 排除应用 → 规则引擎（按优先级）→ 本地 AI 评分 → 执行效果。
 */
class NotifyListenerService : NotificationListenerService() {

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "nf-filter").apply { isDaemon = true }
    }
    private val webhookExecutor = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "nf-push").apply { isDaemon = true }
    }

    @Volatile
    private var retryAttempt = 0
    private val retryHandler = Handler(Looper.getMainLooper())
    private val retryDelays = longArrayOf(1000L, 3000L, 10000L, 30000L, 60000L)

    private val recentPosts = HashMap<String, MutableList<Long>>()

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = true
        retryAttempt = 0
        retryHandler.removeCallbacksAndMessages(null)
        Notices.ensureChannels(this)
        Notices.cancelListenerAlert(this)
        DndController.restoreIfExpired(this)
        if (Settings.get(this).keepAlive) {
            KeepAliveService.start(this)
            WatchdogService.schedule(this)
        }
    }

    override fun onListenerDisconnected() {
        connected = false
        super.onListenerDisconnected()
        // 系统偶尔会断开监听，按退避策略主动请求重连；多次失败后提醒用户
        retryAttempt = 0
        scheduleRebind()
    }

    private fun scheduleRebind() {
        if (retryAttempt >= retryDelays.size) {
            Notices.postListenerDisconnected(this)
            return
        }
        val delay = retryDelays[retryAttempt++]
        retryHandler.postDelayed({
            if (connected) return@postDelayed
            runCatching {
                requestRebind(ComponentName(this, NotifyListenerService::class.java))
            }
            scheduleRebind()
        }, delay)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val sbn = sbn ?: return
        if (sbn.packageName == packageName) return
        worker.execute { handle(sbn) }
    }

    private fun handle(sbn: StatusBarNotification) {
        val settings = Settings.get(this)
        if (settings.isExcluded(sbn.packageName)) return

        val n = sbn.notification ?: return
        val extras = n.extras ?: return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim().orEmpty()
        if (title.isBlank() && text.isBlank() && bigText.isBlank()) return

        val category = n.category.orEmpty()
        val template = extras.getString("android.template").orEmpty()
        val conversation = isConversation(n, category)
        val media = category == Notification.CATEGORY_TRANSPORT || template.contains("MediaStyle")

        // 分身/多用户识别：同包名但 uid 与主应用不同，即来自克隆实例
        val uid = sbn.uid
        @Suppress("DEPRECATION")
        val userId = sbn.userId
        val clone = AppInfo.isClone(this, sbn.packageName, uid)
        if (clone) Log.i(TAG, "clone notification pkg=${sbn.packageName} uid=$uid user=$userId")

        val record = NotifRecord(
            packageName = sbn.packageName,
            appName = AppInfo.label(this, sbn.packageName),
            title = title,
            text = text,
            bigText = bigText,
            subText = subText,
            channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) n.channelId.orEmpty() else "",
            category = category,
            groupKey = sbn.groupKey.orEmpty(),
            postTime = sbn.postTime.takeIf { it > 0 } ?: System.currentTimeMillis(),
            ongoing = sbn.isOngoing,
            clearable = sbn.isClearable,
            conversation = conversation,
            media = media,
            uid = uid,
            userId = userId,
            clone = clone
        )
        val db = DbHelper.get(this)
        val id = db.insertNotification(record)

        // 总开关关闭：仍然记录历史，但不做任何过滤
        if (!settings.masterEnabled) {
            db.updateResult(id, 0, "", 0, 0f)
            DataEvents.notifyChanged(this)
            return
        }
        if (!connected) {
            DataEvents.notifyChanged(this)
            return
        }

        val rules = currentRules()
        val matched = RuleEngine.firstMatch(rules, record)
        var action = NotifRecord.Action.NONE
        var ruleName = ""
        var source = NotifRecord.Source.NONE
        var score = 0f

        if (matched != null) {
            source = NotifRecord.Source.RULE
            ruleName = matched.name
            if (matched.effectDismiss) action = action or NotifRecord.Action.DISMISS
            if (matched.muteSeconds > 0) {
                if (DndController.muteFor(this, matched.muteSeconds)) {
                    action = action or NotifRecord.Action.MUTE
                }
            }
            if (matched.actionButtons.isNotBlank()) {
                if (triggerAction(n, matched.actionButtons)) action = action or NotifRecord.Action.READ
            }
            if (matched.tts) TtsSpeaker.speak(this, "${record.appName}：$title $text")
            if (matched.webhookUrl.isNotBlank()) {
                val recordId = id
                val url = matched.webhookUrl
                val body = matched.webhookBody
                webhookExecutor.execute {
                    val res = WebhookClient.send(url, body, record, matched)
                    if (res.ok) {
                        db.addAction(recordId, NotifRecord.Action.WEBHOOK)
                        DataEvents.notifyChanged(this)
                    }
                    Log.d(TAG, "webhook ${res.code} ${res.message}")
                }
            }
            if (matched.bark) {
                val recordId = id
                webhookExecutor.execute {
                    val res = BarkClient.send(this, record)
                    if (res.ok) {
                        db.addAction(recordId, NotifRecord.Action.BARK)
                        DataEvents.notifyChanged(this)
                    }
                    Log.d(TAG, "bark ${res.code} ${res.message}")
                }
            }
            db.incrementHit(matched.id)
        } else if (settings.aiEnabled) {
            score = AiEngine.score(this, title, "$text $bigText")
            if (score >= settings.aiThreshold) {
                source = NotifRecord.Source.AI
                ruleName = "AI 智能识别"
                action = action or NotifRecord.Action.DISMISS
            }
        }

        // 智能静音：同一应用在短时间内高频推送时，自动压制铃声
        if (action and NotifRecord.Action.DISMISS == 0 && settings.smartMute) {
            if (isBurst(sbn.packageName, settings.smartMuteBurst, settings.smartMuteWindowSec)) {
                if (DndController.muteFor(this, settings.smartMuteSeconds)) {
                    action = action or NotifRecord.Action.MUTE
                    if (ruleName.isBlank()) ruleName = "智能静音"
                    if (source == NotifRecord.Source.NONE) source = NotifRecord.Source.RULE
                }
            }
        }

        if (action and NotifRecord.Action.DISMISS != 0) {
            runCatching { cancelNotification(sbn.key) }
                .onFailure { Log.w(TAG, "cancel failed: ${it.message}") }
        }

        if (action != NotifRecord.Action.NONE) {
            db.updateResult(id, action, ruleName, source, score)
            if (settings.showFilterNotice) {
                val (_, filtered) = db.todayCounts()
                Notices.postSummary(this, filtered)
            }
        } else {
            db.updateResult(id, 0, "", 0, score)
        }
        DataEvents.notifyChanged(this)
    }

    private fun currentRules(): List<FilterRule> =
        DbHelper.get(this).getEnabledRules()

    private fun isBurst(pkg: String, burst: Int, windowSec: Int): Boolean {
        val now = System.currentTimeMillis()
        val window = windowSec * 1000L
        val list = recentPosts.getOrPut(pkg) { ArrayList() }
        list.removeAll { now - it > window }
        list.add(now)
        if (recentPosts.size > 200) recentPosts.clear()
        return list.size >= burst
    }

    private fun isConversation(n: Notification, category: String): Boolean {
        if (category == Notification.CATEGORY_MESSAGE || category == Notification.CATEGORY_EMAIL) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (n.extras.getBoolean("android.isConversation", false)) return true
        }
        return false
    }

    private fun triggerAction(n: Notification, labels: String): Boolean {
        val wanted = labels.split(',', '，', '/')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
        if (wanted.isEmpty()) return false
        n.actions?.forEach { a ->
            val t = a.title?.toString()?.lowercase() ?: return@forEach
            if (wanted.any { t.contains(it) }) {
                return runCatching { a.actionIntent.send(); true }.getOrDefault(false)
            }
        }
        return false
    }

    override fun onDestroy() {
        connected = false
        retryHandler.removeCallbacksAndMessages(null)
        worker.shutdownNow()
        webhookExecutor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "NotifyListener"

        /** 供看门狗判断监听是否仍然连接。 */
        @Volatile
        var connected: Boolean = false
    }
}
