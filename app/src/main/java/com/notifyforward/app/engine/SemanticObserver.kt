package com.notifyforward.app.engine

import android.content.Context
import com.notifyforward.app.data.DataEvents
import com.notifyforward.app.data.Settings
import com.notifyforward.app.model.NotifRecord
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Best-effort observation, completely separate from forwarding and cancellation. */
object SemanticObserver {
    private val worker = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(16),
        { r -> Thread(r, "nf-semantic").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy()).apply {
        allowCoreThreadTimeOut(true)
    }
    private val idle = ScheduledThreadPoolExecutor(1) { r -> Thread(r, "nf-model-idle").apply { isDaemon = true } }
        .apply { removeOnCancelPolicy = true }
    private var releaseTask: java.util.concurrent.ScheduledFuture<*>? = null

    @Synchronized
    fun enqueue(context: Context, record: NotifRecord) {
        val ctx = context.applicationContext
        val store = LearningStore(ctx)
        store.savePrediction(record.id, "语义判断：等待处理 · 仅观察，未执行拦截；若服务中断，本条不自动补算")
        try {
            worker.execute {
                try {
                    val settings = Settings.get(ctx)
                    val summary = if (!settings.aiEnabled || !settings.masterEnabled) "语义判断：已关闭，跳过本条" else {
                        SemanticEngine.judge(ctx, LearningStore.sample(record, 0), store.samples()).summary
                    }
                    store.savePrediction(record.id, summary)
                } catch (e: Exception) {
                    android.util.Log.w("SemanticObserver", "Observation failed: ${e.javaClass.simpleName}")
                    store.savePrediction(record.id, "语义判断：暂不可用 · 转发照常运行；后续通知会重试")
                } finally {
                    DataEvents.notifyChanged(ctx)
                    synchronized(this) {
                        releaseTask?.cancel(false)
                        releaseTask = idle.schedule({
                            if (worker.activeCount == 0 && worker.queue.isEmpty()) SemanticEngine.release()
                        }, 2, TimeUnit.MINUTES)
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            store.savePrediction(record.id, "语义判断：通知较多，本条已跳过 · 转发照常运行")
        }
    }
}
