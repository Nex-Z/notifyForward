package com.notifyforward.app.service

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle

/** Non-exported IPC endpoint hosted by the same process as the notification listener. */
class ListenerStatusProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        ListenerHealth.start(requireNotNull(context), "status_provider")
        return true
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val ctx = requireNotNull(context)
        return MatrixCursor(arrayOf("connected", "summary", "detail")).apply {
            addRow(arrayOf<Any>(if (ListenerHealth.authorized(ctx) && NotifyListenerService.connected) 1 else 0,
                ListenerHealth.summary(ctx), ListenerHealth.detail(ctx)))
        }
    }
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = requireNotNull(context)
        return Bundle().apply {
            when (method) {
                "repair" -> ListenerHealth.repair(ctx)
                "logs" -> putString("text", ListenerDiagnostics.export(ctx))
                else -> throw IllegalArgumentException("Unknown method")
            }
        }
    }
    override fun getType(uri: Uri) = "vnd.android.cursor.item/vnd.notifyforward.listener"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}

object ListenerStatusClient {
    private fun uri(context: Context) = Uri.parse("content://${context.packageName}.listener-status/status")
    data class Status(val connected: Boolean, val summary: String, val detail: String)
    /** Call on a worker thread: binder failure must be shown as unknown, never healthy. */
    fun read(context: Context): Status = runCatching {
        context.contentResolver.query(uri(context), null, null, null, null)!!.use {
            check(it.moveToFirst())
            Status(it.getInt(0) == 1, it.getString(1), it.getString(2))
        }
    }.getOrElse { Status(false, "无法确认监听状态 · 请重试", "服务进程暂时无法响应，请重新打开应用；若持续失败，请关闭再开启通知访问权限。") }
    fun repair(context: Context) { context.contentResolver.call(uri(context), "repair", null, null) }
    fun logs(context: Context): String = context.contentResolver.call(uri(context), "logs", null, null)?.getString("text").orEmpty()
}
