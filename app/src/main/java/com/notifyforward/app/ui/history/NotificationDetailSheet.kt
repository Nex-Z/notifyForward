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
            record.forwardError.isNotBlank() -> "转发失败"
            record.action and NotifRecord.Action.DISMISS != 0 -> "已拦截"
            record.action and NotifRecord.Action.MUTE != 0 -> "已静音"
            record.action and NotifRecord.Action.WEBHOOK != 0 -> "已转发"
            record.action and NotifRecord.Action.BARK != 0 -> "Bark 已推送"
            else -> "未过滤"
        }
        b.status.text = status
        val danger = record.forwardError.isNotBlank() || record.action and NotifRecord.Action.DISMISS != 0
        b.status.setTextColor(
            ContextCompat.getColor(context, if (danger) R.color.danger else R.color.accent)
        )
        b.status.backgroundTintList = ContextCompat.getColorStateList(
            context, if (danger) R.color.danger_container else R.color.accent_container
        )

        val learning = com.notifyforward.app.engine.LearningStore(context)
        val finalLabel = learning.currentLabel(record)
        val ruleText = buildList {
            if (record.forwardError.isNotBlank()) add(record.forwardError.trim())
            if (record.ruleName.isNotBlank()) {
                add(if (record.source == NotifRecord.Source.AI) "来源：AI 智能识别" else "命中规则：${record.ruleName}")
            }
            if (record.aiScore > 0f) add("AI 评分：${(record.aiScore * 100).toInt()}%")
            if (record.action and NotifRecord.Action.WEBHOOK != 0) add("Webhook 已发送")
            if (record.action and NotifRecord.Action.BARK != 0) add("Bark 已推送")
            if (record.action and NotifRecord.Action.READ != 0) add("已点击通知按钮")
            if (finalLabel == NotifRecord.Feedback.SPAM) add("已标记为骚扰")
            if (finalLabel == NotifRecord.Feedback.NORMAL) add("已标记为正常")
        }.joinToString("\n")
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
        b.btnNormal.text = if (finalLabel == 1) "撤销正常标注" else "标记为正常"
        b.btnSpam.text = if (finalLabel == 2) "撤销骚扰标注" else "标记为骚扰"
        b.btnNormal.setOnClickListener {
            feedback(if (finalLabel == 1) 0 else NotifRecord.Feedback.NORMAL)
            dialog.dismiss()
        }
        b.btnSpam.setOnClickListener {
            feedback(if (finalLabel == 2) 0 else NotifRecord.Feedback.SPAM)
            dialog.dismiss()
        }
        b.btnDelete.setOnClickListener {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
                .setTitle("删除这条历史记录？")
                .setMessage("通知正文和转发记录将删除，已保存的个人标注仍会保留。")
                .setNegativeButton("保留", null)
                .setPositiveButton("删除记录") { _, _ ->
            DbHelper.get(context).deleteNotification(record.id)
            Toast.makeText(context, R.string.toast_deleted, Toast.LENGTH_SHORT).show()
            onChanged()
            dialog.dismiss()
                }.show()
        }

        fun refreshPrediction() {
            val forwarding = DbHelper.get(context).forwardingSummary(record.id)
            b.forwardingLog.text = if (forwarding.isBlank()) "转发记录\n暂无逐次发送记录；旧版本的发送结果见上方状态。" else "转发记录\n$forwarding"
            if (forwarding.isNotBlank()) {
                b.status.text = when {
                    forwarding.contains("失败：") -> "转发有失败"
                    forwarding.contains("发送中") -> "发送中"
                    forwarding.contains("未确认") -> "结果待确认"
                    else -> "已转发"
                }
            }
            val summary = learning.prediction(record.id)
            b.semanticJudgment.text = summary
            b.semanticJudgment.visibility = if (summary.isBlank()) View.GONE else View.VISIBLE
        }
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: android.content.Intent?) { refreshPrediction() }
        }
        val owner = context as? androidx.lifecycle.LifecycleOwner
        val observer = object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) { dialog.dismiss() }
        }
        dialog.show()
        dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.backgroundTintList =
            ContextCompat.getColorStateList(context, R.color.surface)
        b.btnOpenApp.backgroundTintList = ContextCompat.getColorStateList(context, R.color.brand_container)
        b.btnCopy.backgroundTintList = ContextCompat.getColorStateList(context, R.color.brand_container)
        dialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.isFitToContents = true
        ContextCompat.registerReceiver(context, receiver,
            android.content.IntentFilter(com.notifyforward.app.data.DataEvents.ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
        owner?.lifecycle?.addObserver(observer)
        dialog.setOnDismissListener {
            runCatching { context.unregisterReceiver(receiver) }
            owner?.lifecycle?.removeObserver(observer)
        }
        refreshPrediction()
    }

    private fun feedback(kind: Int) {
        val result = runCatching { com.notifyforward.app.engine.LearningStore(context).label(record, kind) }
        if (result.isFailure) {
            Toast.makeText(context, "标注保存失败，请稍后重试", Toast.LENGTH_SHORT).show()
            return
        }
        onChanged()
        Toast.makeText(
            context,
            when (kind) { 0 -> "已撤销标注"; 2 -> "已标记为骚扰，后续判断使用新标注"; else -> "已标记为正常，后续判断使用新标注" },
            Toast.LENGTH_SHORT
        ).show()
    }
}
