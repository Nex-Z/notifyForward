package com.notifyforward.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.notifyforward.app.data.Settings

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        ListenerDiagnostics.record(context, "system_event", "action=$action build=${android.os.Build.DISPLAY}")
        if (ListenerHealth.authorized(context) || Settings.get(context).keepAlive) WatchdogService.schedule(context)
        if (Settings.get(context).keepAlive) {
            KeepAliveService.start(context)
            WatchdogService.schedule(context)
        }
    }
}
