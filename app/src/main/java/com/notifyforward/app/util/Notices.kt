package com.notifyforward.app.util

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.notifyforward.app.R
import com.notifyforward.app.ui.MainActivity

/** 应用自身的通知：过滤汇总提示、保活前台服务。 */
object Notices {

    const val CHANNEL_SUMMARY = "filter_summary"
    const val CHANNEL_SERVICE = "keep_alive"
    const val CHANNEL_RESTORED = "restored"
    const val CHANNEL_ALERT = "access_alert"
    const val ID_SUMMARY = 1001
    const val ID_SERVICE = 1002
    const val ID_ALERT = 1003

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_RESTORED) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_RESTORED, "恢复的通知", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "在应用内恢复显示的历史通知"
                }
            )
        }
        if (nm.getNotificationChannel(CHANNEL_SUMMARY) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_SUMMARY, "过滤提示", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "有通知被过滤时在此提示"
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                }
            )
        }
        if (nm.getNotificationChannel(CHANNEL_SERVICE) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_SERVICE, "后台保活", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "保持过滤服务运行"
                    setShowBadge(false)
                    setSound(null, null)
                }
            )
        }
        if (nm.getNotificationChannel(CHANNEL_ALERT) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ALERT, "服务异常提醒", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "通知使用权断开时提醒"
                }
            )
        }
    }

    fun postSummary(context: Context, filteredToday: Int) {
        ensureChannels(context)
        if (filteredToday <= 0) {
            cancelSummary(context)
            return
        }
        val pi = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(context, CHANNEL_SUMMARY)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("已过滤 $filteredToday 条通知")
            .setContentText("点按查看被拦截的通知，可在设置中关闭此提示")
            .setContentIntent(pi)
            .setOngoing(false)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .build()
        notifySafely(context, ID_SUMMARY, n)
    }

    fun cancelSummary(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(ID_SUMMARY) }
    }

    /** 通知使用权被系统断开时的提醒。 */
    fun postListenerDisconnected(context: Context) {
        ensureChannels(context)
        val pi = PendingIntent.getActivity(
            context, 2,
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("通知访问已断开")
            .setContentText("点击重新开启通知使用权，否则无法继续过滤通知")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        notifySafely(context, ID_ALERT, n)
    }

    fun cancelListenerAlert(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(ID_ALERT) }
    }

    /** 把历史里的一条通知重新显示到状态栏（由本应用代发）。 */
    fun postRestored(context: Context, record: com.notifyforward.app.model.NotifRecord) {
        ensureChannels(context)
        val launch = runCatching { context.packageManager.getLaunchIntentForPackage(record.packageName) }
            .getOrNull()
        val contentIntent = if (launch != null) {
            PendingIntent.getActivity(
                context, record.id.toInt(),
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            PendingIntent.getActivity(
                context, record.id.toInt(),
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val n = NotificationCompat.Builder(context, CHANNEL_RESTORED)
            .setSmallIcon(R.drawable.ic_bell)
            .setContentTitle(record.title.ifBlank { record.appName })
            .setContentText(record.displayText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(record.displayText))
            .setSubText("来自 ${record.appName} · 由通知哨兵恢复")
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        notifySafely(context, 2000 + (record.id % 1000).toInt(), n)
    }

    private fun notifySafely(context: Context, id: Int, notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) return
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            // 权限可能在检查之后被收回；自身通知失败不应中断转发。
        }
    }

    fun buildServiceNotification(context: Context): android.app.Notification {
        ensureChannels(context)
        val pi = PendingIntent.getActivity(
            context, 1, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("通知哨兵正在守护通知")
            .setContentText("点击返回应用")
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }
}
