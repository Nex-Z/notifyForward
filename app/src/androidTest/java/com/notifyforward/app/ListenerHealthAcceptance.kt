package com.notifyforward.app

import android.app.Activity
import android.app.Instrumentation
import android.app.NotificationManager
import android.os.Bundle
import android.os.ParcelFileDescriptor
import com.notifyforward.app.service.ListenerStatusClient

/** Exercises the real system binding, IPC, retry timeout and alert without editing user rules. */
class ListenerHealthAcceptance : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    private val component = "com.notifyforward.app/com.notifyforward.app.service.NotifyListenerService"
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText() }
    private fun awaitState(timeout: Long, predicate: () -> Boolean) {
        val end = android.os.SystemClock.elapsedRealtime() + timeout
        while (android.os.SystemClock.elapsedRealtime() < end) {
            if (predicate()) return
            Thread.sleep(500)
        }
        error("Timed out: ${ListenerStatusClient.read(targetContext)}")
    }
    override fun onStart() {
        val results = StringBuilder()
        var failure: Throwable? = null
        try {
            check(shell("settings get secure enabled_notification_listeners").contains(component)) { "Grant notification access before running" }
            results.appendLine("Context package: ${targetContext.packageName}")
            shell("am start -n com.notifyforward.app/.ui.MainActivity")
            shell("cmd notification disallow_listener $component 0")
            shell("cmd notification allow_listener $component 0")
            awaitState(30_000) { ListenerStatusClient.read(targetContext).connected }
            results.appendLine("PASS: cross-process status reports actual connection")

            shell("cmd notification disallow_listener $component 0")
            awaitState(10_000) { ListenerStatusClient.read(targetContext).summary.contains("未授权") }
            results.appendLine("PASS: revoked permission is not displayed as connected")
            shell("cmd notification allow_listener $component 0")
            awaitState(20_000) { ListenerStatusClient.read(targetContext).connected }

            // Unbind first: disabling a component does not tear down an existing binder on every ROM.
            shell("cmd notification disallow_listener $component 0")
            targetContext.packageManager.setComponentEnabledSetting(android.content.ComponentName.unflattenFromString(component)!!, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED, android.content.pm.PackageManager.DONT_KILL_APP)
            shell("cmd notification allow_listener $component 0")
            awaitState(10_000) { !ListenerStatusClient.read(targetContext).connected }
            check(shell("settings get secure enabled_notification_listeners").contains(component))
            ListenerStatusClient.repair(targetContext)
            awaitState(210_000) { ListenerStatusClient.read(targetContext).summary.contains("自动恢复失败") }
            val log = ListenerStatusClient.logs(targetContext)
            check((1..5).all { log.contains("attempt=$it") })
            check(log.contains("recovery_failed") && log.contains("alert_posted"))
            val alerts = targetContext.getSystemService(NotificationManager::class.java).activeNotifications
            check(alerts.any { it.id == com.notifyforward.app.util.Notices.ID_ALERT })
            results.appendLine("PASS: five retries are verified, failed recovery is persisted and notification is posted")

            targetContext.packageManager.setComponentEnabledSetting(android.content.ComponentName.unflattenFromString(component)!!, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, android.content.pm.PackageManager.DONT_KILL_APP)
            shell("cmd notification disallow_listener $component 0")
            shell("cmd notification allow_listener $component 0")
            ListenerStatusClient.repair(targetContext)
            awaitState(20_000) { ListenerStatusClient.read(targetContext).connected }
            awaitState(5_000) {
                targetContext.getSystemService(NotificationManager::class.java).activeNotifications.none {
                    it.id == com.notifyforward.app.util.Notices.ID_ALERT
                }
            }
            results.appendLine("PASS: recovered connection clears alert automatically")
            check(ListenerStatusClient.logs(targetContext).contains("listener_connected"))
            results.appendLine("PASS: diagnostics can be read across processes")
        } catch (t: Throwable) { failure = t; results.appendLine("FAIL: $t") }
        finally {
            targetContext.packageManager.setComponentEnabledSetting(android.content.ComponentName.unflattenFromString(component)!!, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, android.content.pm.PackageManager.DONT_KILL_APP)
            shell("cmd notification disallow_listener $component 0")
            shell("cmd notification allow_listener $component 0")
            runCatching { ListenerStatusClient.repair(targetContext) }
        }
        finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply { putString("stream", "\n$results") })
    }

}
