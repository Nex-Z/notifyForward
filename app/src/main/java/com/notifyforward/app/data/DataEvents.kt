package com.notifyforward.app.data

import android.content.Context
import android.content.Intent

/**
 * 跨进程的数据变更通知。
 *
 * 通知监听运行在 `:service` 进程，UI 在默认进程，SharedFlow 无法跨进程，
 * 因此这里用显式广播（仅本应用包内）驱动界面刷新。
 */
object DataEvents {

    const val ACTION = "com.notifyforward.app.action.DATA_CHANGED"

    fun notifyChanged(context: Context) {
        runCatching {
            context.sendBroadcast(Intent(ACTION).setPackage(context.packageName))
        }
    }
}
