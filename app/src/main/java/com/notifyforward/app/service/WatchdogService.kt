package com.notifyforward.app.service

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.notifyforward.app.data.Settings

/**
 * 15 分钟一次的看门狗：
 *  - 保活服务不在了就尝试重新拉起；
 *  - 通知监听被系统断开就请求重新绑定。
 * 使用 JobScheduler（setPersisted），重启后仍然有效。
 */
class WatchdogService : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        val ctx = applicationContext
        val settings = Settings.get(ctx)
        if (settings.keepAlive && !KeepAliveService.running) KeepAliveService.start(ctx)
        ListenerDiagnostics.record(ctx, "watchdog_check", "connected=${NotifyListenerService.connected}")
        ListenerHealth.start(ctx, "watchdog")
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = true

    companion object {
        private const val JOB_ID = 1001

        fun schedule(context: Context) {
            runCatching {
                val js = context.getSystemService(JobScheduler::class.java)
                if (js.getPendingJob(JOB_ID) != null) return
                val info = JobInfo.Builder(
                    JOB_ID,
                    ComponentName(context, WatchdogService::class.java)
                )
                    .setPeriodic(15 * 60 * 1000L)
                    .setPersisted(true)
                    .build()
                js.schedule(info)
            }
        }

        fun cancel(context: Context) {
            runCatching {
                context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
            }
        }
    }
}
