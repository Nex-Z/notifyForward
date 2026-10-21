package com.notifyforward.app.util

import android.content.Context
import com.notifyforward.app.data.DbHelper
import com.notifyforward.app.model.NotifRecord
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 把通知历史导出为 Excel 可直接打开的 CSV。 */
object CsvExporter {

    fun export(context: Context): File {
        val rows = DbHelper.get(context).queryNotifications(limit = 20000)
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val name = "通知历史-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.getDefault())
            .format(Date()) + ".csv"
        val file = File(dir, name)
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        file.bufferedWriter(Charsets.UTF_8).use { w ->
            w.write("\uFEFF")
            w.write("时间,应用,包名,标题,内容,渠道,状态,命中规则,AI评分\n")
            rows.forEach { r ->
                w.write(
                    listOf(
                        fmt.format(Date(r.postTime)),
                        r.appName,
                        r.packageName,
                        r.title,
                        r.displayText,
                        r.channelId,
                        statusText(r),
                        r.ruleName,
                        if (r.aiScore > 0f) "${(r.aiScore * 100).toInt()}%" else ""
                    ).joinToString(",") { escape(it) }
                )
                w.write("\n")
            }
        }
        return file
    }

    private fun statusText(r: NotifRecord): String = when {
        r.action and NotifRecord.Action.DISMISS != 0 -> "已拦截"
        r.action and NotifRecord.Action.MUTE != 0 -> "已静音"
        r.action and NotifRecord.Action.WEBHOOK != 0 -> "已转发"
        else -> "正常"
    }

    private fun escape(s: String): String {
        val clean = s.replace("\r", " ").replace("\n", " ")
        return "\"" + clean.replace("\"", "\"\"") + "\""
    }
}
