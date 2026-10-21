package com.notifyforward.app.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.notifyforward.app.data.Settings

/** 用闹钟做一次性的“拉起”兜底（进程被杀、服务 onDestroy / onTaskRemoved 时）。 */
object RestartScheduler {

    private const val ACTION_RESTART = "com.notifyforward.app.action.RESTART_KEEPALIVE"
    private const val REQUEST_CODE = 42

    fun schedule(context: Context, delayMs: Long) {
        runCatching {
            val am = context.getSystemService(AlarmManager::class.java)
            val pi = pending(context)
            val at = System.currentTimeMillis() + delayMs
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.set(AlarmManager.RTC_WAKEUP, at, pi)
            }
        }
    }

    fun cancel(context: Context) {
        runCatching {
            context.getSystemService(AlarmManager::class.java).cancel(pending(context))
        }
    }

    private fun pending(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, RestartReceiver::class.java).setAction(ACTION_RESTART),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}

class RestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != "com.notifyforward.app.action.RESTART_KEEPALIVE") return
        if (Settings.get(context).keepAlive) {
            KeepAliveService.start(context)
            WatchdogService.schedule(context)
        }
    }
}
