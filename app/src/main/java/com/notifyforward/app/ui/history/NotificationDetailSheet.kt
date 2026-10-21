package com.notifyforward.app.ui.history

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.notifyforward.app.R
import com.notifyforward.app.data.DbHelper
import com.notifyforward.app.databinding.DialogNotificationDetailBinding
import com.notifyforward.app.engine.AiEngine
import com.notifyforward.app.model.NotifRecord
import com.notifyforward.app.util.AppInfo
import com.notifyforward.app.util.Notices

/** 通知详情底部弹层：查看全文、打开应用、恢复、标记反馈。 */
class NotificationDetailSheet(
    private val context: Context,
    private val record: NotifRecord,
    private val onChanged: () -> Unit
) {

    fun show() {
        val dialog = BottomSheetDialog(context)
        val b = DialogNotificationDetailBinding.inflate(LayoutInflater.from(context))
        dialog.setContentView(b.root)

        b.appName.text = record.displayAppName
        val meta = buildList {
            if (record.clone) add("分身 · uid ${record.uid}")
            else if (record.uid > 0) add("uid ${record.uid}")
            if (record.userId > 0) add("用户 ${record.userId}")
            if (record.channelId.isNotBlank()) add("渠道 ${record.channelId}")
            add(HistoryAdapter.formatTime(record.postTime))
            if (record.ongoing) add("常驻")
        }.joinToString(" · ")
        b.meta.text = meta
        b.title.text = record.title.ifBlank { record.displayAppName }
        b.content.text = record.displayText.ifBlank { "（通知内容已被系统回收或为空）" }

        val icon = AppInfo.icon(context, record.packageName)
        if (icon != null) b.icon.setImageDrawable(icon) else b.icon.setImageResource(R.drawable.ic_bell)

        val status = when {
            record.action and NotifRecord.Action.DISMISS != 0 -> "已拦截"
            record.action and NotifRecord.Action.MUTE != 0 -> "已静音"
            record.action and NotifRecord.Action.WEBHOOK != 0 -> "已转发"
            record.action and NotifRecord.Action.BARK != 0 -> "Bark 已推送"
            else -> "未过滤"
        }
        b.status.text = status
        val danger = record.action and NotifRecord.Action.DISMISS != 0
        b.status.setTextColor(
            ContextCompat.getColor(context, if (danger) R.color.danger else R.color.accent)
        )
        b.status.backgroundTintList = ContextCompat.getColorStateList(
            context, if (danger) R.color.danger_container else R.color.accent_container
        )

        val ruleText = buildList {
            if (record.ruleName.isNotBlank()) {
                add(if (record.source == NotifRecord.Source.AI) "来源：AI 智能识别" else "命中规则：${record.ruleName}")
            }
            if (record.aiScore > 0f) add("AI 评分：${(record.aiScore * 100).toInt()}%")
            if (record.action and NotifRecord.Action.WEBHOOK != 0) add("Webhook 已发送")
            if (record.action and NotifRecord.Action.BARK != 0) add("Bark 已推送")
            if (record.action and NotifRecord.Action.READ != 0) add("已点击通知按钮")
            if (record.feedback == NotifRecord.Feedback.SPAM) add("已标记为骚扰")
            if (record.feedback == NotifRecord.Feedback.NORMAL) add("已标记为正常")
        }.joinToString("　")
        b.rule.text = ruleText
        b.rule.visibility = if (ruleText.isBlank()) View.GONE else View.VISIBLE

        b.btnRestore.visibility =
            if (record.action and NotifRecord.Action.DISMISS != 0) View.VISIBLE else View.GONE

        b.btnOpenApp.text = if (record.clone) "打开主应用" else "打开应用"
        b.btnOpenApp.setOnClickListener {
            AppInfo.openApp(context, record.packageName)
            dialog.dismiss()
        }
        b.btnRestore.setOnClickListener {
            Notices.postRestored(context, record)
            Toast.makeText(context, "已重新显示到通知栏", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
        b.btnCopy.setOnClickListener {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("notification", "${record.title}\n${record.displayText}"))
            Toast.makeText(context, R.string.toast_copied, Toast.LENGTH_SHORT).show()
        }
        b.btnNormal.setOnClickListener {
            feedback(NotifRecord.Feedback.NORMAL)
            dialog.dismiss()
        }
        b.btnSpam.setOnClickListener {
            feedback(NotifRecord.Feedback.SPAM)
            dialog.dismiss()
        }
        b.btnDelete.setOnClickListener {
            DbHelper.get(context).deleteNotification(record.id)
            Toast.makeText(context, R.string.toast_deleted, Toast.LENGTH_SHORT).show()
            onChanged()
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun feedback(kind: Int) {
        DbHelper.get(context).setFeedback(record.id, kind)
        AiEngine.train(
            context, record.title, record.displayText,
            kind == NotifRecord.Feedback.SPAM
        )
        onChanged()
        Toast.makeText(
            context,
            if (kind == NotifRecord.Feedback.SPAM) "已记录为骚扰，AI 会更懂你" else "已记录为正常，AI 会更懂你",
            Toast.LENGTH_SHORT
        ).show()
    }
}
