package com.notifyforward.app.service

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A bounded, content-free journal, shared safely across both app processes. */
object ListenerDiagnostics {
    private class Store(context: Context) : SQLiteOpenHelper(context, "listener-diagnostics.db", null, 1) {
        init { setWriteAheadLoggingEnabled(true) }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE events (_id INTEGER PRIMARY KEY, time INTEGER, uptime INTEGER, pid INTEGER, event TEXT, detail TEXT)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
    @Volatile private var store: Store? = null
    private fun db(context: Context): SQLiteDatabase = (store ?: synchronized(this) {
        store ?: Store(context.applicationContext).also { store = it }
    }).writableDatabase

    fun record(context: Context, event: String, detail: String = "") {
        Log.i("ListenerHealth", "$event $detail")
        runCatching {
            val db = db(context)
            db.beginTransaction()
            try {
                db.insertOrThrow("events", null, ContentValues().apply {
                    put("time", System.currentTimeMillis()); put("uptime", SystemClock.elapsedRealtime())
                    put("pid", Process.myPid()); put("event", event); put("detail", detail.take(400))
                })
                db.execSQL("DELETE FROM events WHERE _id NOT IN (SELECT _id FROM events ORDER BY _id DESC LIMIT 500)")
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }.onFailure { Log.e("ListenerHealth", "Diagnostic journal unavailable", it) }
    }

    fun export(context: Context): String {
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT)
        return buildString {
            appendLine("NotifyForward listener diagnostics")
            appendLine("Exported: ${format.format(Date())}")
            appendLine("Android ${Build.VERSION.RELEASE} / SDK ${Build.VERSION.SDK_INT}")
            appendLine("Build: ${Build.FINGERPRINT}")
            appendLine("App: ${context.packageManager.getPackageInfo(context.packageName, 0).versionName}")
            db(context).query("events", null, null, null, null, null, "_id DESC").use { c ->
                while (c.moveToNext()) {
                    appendLine("${format.format(Date(c.getLong(1)))} uptime=${c.getLong(2)} pid=${c.getInt(3)} ${c.getString(4)} ${c.getString(5)}")
                }
            }
        }
    }
}
