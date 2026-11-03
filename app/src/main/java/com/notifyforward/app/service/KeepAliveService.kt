package com.notifyforward.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.notifyforward.app.data.Settings
import com.notifyforward.app.util.Notices

/**
 * 可选的前台保活服务。
 *
 * 与参考应用一致使用 specialUse 类型（不受 Android 15 dataSync 6 小时限制），
 * START_STICKY + onTaskRemoved/onDestroy 定时重启，尽量降低被系统或 ROM 杀死的概率。
 */
class KeepAliveService : Service() {

    override fun onCreate() {
        super.onCreate()
        running = true
        ListenerHealth.start(this, "keep_alive")
        WatchdogService.schedule(this)
        startForegroundCompat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val n = Notices.buildServiceNotification(this)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    Notices.ID_SERVICE, n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(Notices.ID_SERVICE, n)
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 从最近任务划掉时，安排一次快速重启
        if (Settings.get(this).keepAlive) {
            RestartScheduler.schedule(this, 1200L)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        running = false
        if (Settings.get(this).keepAlive) {
            RestartScheduler.schedule(this, 2500L)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        @Volatile
        var running: Boolean = false
            private set

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context, Intent(context, KeepAliveService::class.java)
                )
            }
        }

        fun stop(context: Context) {
            RestartScheduler.cancel(context)
            runCatching { context.stopService(Intent(context, KeepAliveService::class.java)) }
        }
    }
}
