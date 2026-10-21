package com.notifyforward.app.engine

import android.app.NotificationManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.notifyforward.app.data.Settings

/**
 * 「静音」效果：借助勿扰（DND）在指定时长内压制通知铃声/振动。
 * 需要用户授予「通知策略访问权限」，未授权时静音降级为仅记录。
 */
object DndController {

    private val handler = Handler(Looper.getMainLooper())
    private var restoreRunnable: Runnable? = null

    fun isGranted(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted

    fun muteFor(context: Context, seconds: Int): Boolean {
        if (seconds <= 0) return false
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) return false

        val until = System.currentTimeMillis() + seconds * 1000L
        Settings.get(context).dndUntil = until

        runCatching { nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE) }

        restoreRunnable?.let { handler.removeCallbacks(it) }
        val r = object : Runnable {
            override fun run() {
                val left = until - System.currentTimeMillis()
                if (left > 250) {
                    handler.postDelayed(this, left)
                } else {
                    restore(context)
                }
            }
        }
        restoreRunnable = r
        handler.postDelayed(r, seconds * 1000L)
        return true
    }

    fun restore(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) return
        runCatching { nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL) }
        Settings.get(context).dndUntil = 0L
    }

    /** 如果上次静音没有被正常恢复（例如进程被杀），启动时兜底恢复。 */
    fun restoreIfExpired(context: Context) {
        val until = Settings.get(context).dndUntil
        if (until in 1..System.currentTimeMillis()) restore(context)
    }
}
