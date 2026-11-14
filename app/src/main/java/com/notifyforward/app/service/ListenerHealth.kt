package com.notifyforward.app.service

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import com.notifyforward.app.util.Notices
import com.tencent.mmkv.MMKV

/** Runs only in :service. UI reads it through the private provider, never a cached connected flag. */
object ListenerHealth {
    private val handler = Handler(Looper.getMainLooper())
    private val delays = longArrayOf(1_000, 3_000, 10_000, 30_000, 60_000)
    private val kv by lazy { MMKV.mmkvWithID("listener_health", MMKV.MULTI_PROCESS_MODE) }
    private var started = false
    private var appContext: Context? = null
    private val tick = object : Runnable {
        override fun run() {
            val ctx = appContext ?: return
            check(ctx)
            handler.postDelayed(this, if (NotifyListenerService.connected || !authorized(ctx)) 30_000 else 1_000)
        }
    }

    fun authorized(context: Context): Boolean {
        val component = ComponentName(context, NotifyListenerService::class.java)
        // Read the user's persisted grant separately from component availability / binding.
        // Some ROMs return false from isNotificationListenerAccessGranted for an unavailable
        // component even while retaining its grant in this system setting.
        return Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            .orEmpty().split(':').any { ComponentName.unflattenFromString(it) == component }
    }

    fun start(context: Context, reason: String) {
        val ctx = context.applicationContext
        handler.post {
            if (!started) {
                started = true
                appContext = ctx
                val boot = Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT, -1)
                if (kv.decodeInt("boot", -2) != boot) {
                    kv.encode("boot", boot); resetRetry()
                }
                ListenerDiagnostics.record(ctx, "monitor_started", "reason=$reason boot=$boot sdk=${android.os.Build.VERSION.SDK_INT} build=${android.os.Build.DISPLAY}")
                handler.post(tick)
            }
        }
    }

    private fun resetRetry() {
        kv.encode("attempt", 0); kv.encode("next", 0L); kv.encode("failed", false); kv.encode("alerted", false)
    }

    fun connected(context: Context) {
        resetRetry()
        kv.encode("connected_at", System.currentTimeMillis())
        ListenerDiagnostics.record(context, "listener_connected")
        Notices.cancelListenerAlert(context)
        start(context, "connected")
    }

    fun disconnected(context: Context, reason: String) {
        // Preserve the retry budget across service recreation / repeated disconnect callbacks.
        kv.encode("disconnected_at", System.currentTimeMillis())
        ListenerDiagnostics.record(context, "listener_disconnected", reason)
        start(context, reason)
    }

    fun received() { kv.encode("received_at", System.currentTimeMillis()) }

    fun repair(context: Context) {
        start(context, "manual_repair")
        handler.post {
            if (!NotifyListenerService.connected) {
                resetRetry()
                ListenerDiagnostics.record(context, "manual_repair")
                check(context)
            }
        }
    }

    private fun check(context: Context) {
        val granted = authorized(context)
        if (!kv.containsKey("authorized") || kv.decodeBool("authorized") != granted) {
            ListenerDiagnostics.record(context, "authorization_changed", "granted=$granted")
            kv.encode("authorized", granted)
            resetRetry()
            if (!granted) Notices.cancelListenerAlert(context)
        }
        if (!granted || NotifyListenerService.connected) return
        val now = SystemClock.elapsedRealtime()
        if (kv.decodeLong("next") == 0L) {
            kv.encode("disconnected_at", System.currentTimeMillis())
            ListenerDiagnostics.record(context, "connection_missing", "authorized=true")
            kv.encode("next", now + delays[0])
            return
        }
        if (now < kv.decodeLong("next")) return
        val attempt = kv.decodeInt("attempt")
        if (attempt < delays.size) {
            val component = ComponentName(context, NotifyListenerService::class.java)
            if (attempt == delays.lastIndex && Build.VERSION.SDK_INT >= 34) {
                // Android can ignore rebind when it already considers the component enabled.
                // Reset that state using the public API without changing the user's grant.
                runCatching { NotificationListenerService.requestUnbind(component) }
                    .onSuccess { ListenerDiagnostics.record(context, "binding_reset_requested") }
                    .onFailure { ListenerDiagnostics.record(context, "binding_reset_error", it.javaClass.simpleName) }
            }
            runCatching {
                NotificationListenerService.requestRebind(component)
            }.onSuccess { ListenerDiagnostics.record(context, "rebind_requested", "attempt=${attempt + 1}") }
                .onFailure { ListenerDiagnostics.record(context, "rebind_error", "attempt=${attempt + 1} type=${it.javaClass.simpleName}") }
            kv.encode("attempt", attempt + 1)
            // The final request also gets a full grace period before we declare failure.
            kv.encode("next", now + delays[(attempt + 1).coerceAtMost(delays.lastIndex)])
        } else {
            if (!kv.decodeBool("failed")) {
                kv.encode("failed", true)
                ListenerDiagnostics.record(context, "recovery_failed", "attempts=$attempt")
            } else {
                // Slow background retries continue; never repeatedly alert for the same outage.
                runCatching { NotificationListenerService.requestRebind(ComponentName(context, NotifyListenerService::class.java)) }
                    .onSuccess { ListenerDiagnostics.record(context, "background_rebind_requested") }
                    .onFailure { ListenerDiagnostics.record(context, "background_rebind_error", it.javaClass.simpleName) }
            }
            if (!kv.decodeBool("alerted")) {
                val shown = Notices.postListenerDisconnected(context)
                kv.encode("alerted", shown)
                ListenerDiagnostics.record(context, if (shown) "alert_posted" else "alert_blocked", "check_notification_permission_and_channel")
            }
            kv.encode("next", now + 15 * 60_000L)
        }
    }

    fun summary(context: Context): String = when {
        !authorized(context) -> "未授权 · 请开启通知访问权限"
        NotifyListenerService.connected -> "已授权 · 监听已连接"
        kv.decodeBool("failed") -> "已授权但未连接 · 自动恢复失败，请关闭再开启通知访问权限"
        else -> "已授权但未连接 · 正在自动重连（${kv.decodeInt("attempt")}/5）"
    }

    fun detail(context: Context): String {
        fun time(key: String): String = kv.decodeLong(key).takeIf { it > 0 }?.let {
            java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(it))
        } ?: "暂无记录"
        val nm = context.getSystemService(NotificationManager::class.java)
        val alerts = androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (android.os.Build.VERSION.SDK_INT < 26 || (nm.getNotificationChannel(Notices.CHANNEL_ALERT)?.importance ?: 0) > 0)
        return "${summary(context)}\n最近连接：${time("connected_at")}\n最近断开：${time("disconnected_at")}\n最近通知：${time("received_at")}\n异常通知：${if (alerts) "已允许" else "已关闭，请允许应用通知及服务异常提醒"}"
    }
}
