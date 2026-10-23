package com.notifyforward.app.ui.history

import android.content.Context
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.notifyforward.app.R
import com.notifyforward.app.databinding.ItemHistoryHeaderBinding
import com.notifyforward.app.databinding.ItemNotificationBinding
import com.notifyforward.app.model.NotifRecord
import com.notifyforward.app.util.AppInfo
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 历史列表条目：日期分组标题 + 通知行。 */
sealed class HistoryEntry {
    data class Header(val label: String) : HistoryEntry()
    data class Item(
        val record: NotifRecord,
        val first: Boolean,
        val last: Boolean
    ) : HistoryEntry()
}

class HistoryAdapter(
    private val onClick: (NotifRecord) -> Unit,
    private val onLongClick: (NotifRecord, View) -> Unit
) : ListAdapter<HistoryEntry, RecyclerView.ViewHolder>(DIFF) {

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is HistoryEntry.Header -> TYPE_HEADER
        is HistoryEntry.Item -> TYPE_ITEM
    }

    /** 按日期分组后提交，回调在列表动画结束后触发。 */
    fun submit(records: List<NotifRecord>, commit: () -> Unit = {}) {
        submitList(buildEntries(records), commit)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderVH(ItemHistoryHeaderBinding.inflate(inflater, parent, false))
        } else {
            VH(ItemNotificationBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is HistoryEntry.Header -> (holder as HeaderVH).bind(item.label)
            is HistoryEntry.Item -> (holder as VH).bind(item)
        }
    }

    class HeaderVH(private val b: ItemHistoryHeaderBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(label: String) {
            b.header.text = label
        }
    }

    inner class VH(private val b: ItemNotificationBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(entry: HistoryEntry.Item) {
            val r = entry.record
            val ctx = b.root.context

            b.root.setBackgroundResource(
                when {
                    entry.first && entry.last -> R.drawable.bg_row_single
                    entry.first -> R.drawable.bg_row_top
                    entry.last -> R.drawable.bg_row_bottom
                    else -> R.drawable.bg_row_middle
                }
            )
            b.rowDivider.visibility = if (entry.last) View.GONE else View.VISIBLE

            b.title.text = r.displayAppName
            b.text.text = when {
                r.displayText.isBlank() -> r.title.ifBlank { "（无正文）" }
                r.title.isBlank() || r.displayText.startsWith(r.title) -> r.displayText
                else -> "${r.title}：${r.displayText}"
            }
            b.time.text = formatTime(r.postTime)
            b.appName.visibility = View.GONE

            val icon = AppInfo.icon(ctx, r.packageName)
            if (icon != null) {
                b.icon.visibility = View.VISIBLE
                b.iconLetter.visibility = View.GONE
                b.icon.setImageDrawable(icon)
                b.icon.setPadding(0, 0, 0, 0)
            } else {
                // 未安装或取不到图标：用首字母色块，避免一整列灰铃铛
                b.icon.visibility = View.GONE
                b.iconLetter.visibility = View.VISIBLE
                b.iconLetter.text = AppInfo.initial(r.appName)
                b.iconLetter.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(
                        AppInfo.tileColor(ctx, r.packageName)
                    )
            }

            when {
                r.forwardError.isNotBlank() ->
                    badge(ctx, "转发失败", R.color.danger, R.color.danger_container)
                r.action and NotifRecord.Action.DISMISS != 0 ->
                    badge(ctx, "已拦截", R.color.danger, R.color.danger_container)
                r.action and NotifRecord.Action.MUTE != 0 ->
                    badge(ctx, "已静音", R.color.warning, R.color.warning_container)
                r.feedback == NotifRecord.Feedback.SPAM ->
                    badge(ctx, "已标骚扰", R.color.danger, R.color.danger_container)
                r.action and NotifRecord.Action.BARK != 0 ->
                    badge(ctx, "Bark", R.color.brand_primary, R.color.brand_container)
                r.action and NotifRecord.Action.WEBHOOK != 0 ->
                    badge(ctx, "已转发", R.color.brand_primary, R.color.brand_container)
                r.feedback == NotifRecord.Feedback.NORMAL ->
                    badge(ctx, "正常", R.color.accent, R.color.accent_container)
                else -> b.statusBadge.visibility = View.GONE
            }

            val extras = buildList {
                if (r.ruleName.isNotBlank()) {
                    add(if (r.source == NotifRecord.Source.AI) "AI 智能识别" else "规则 ${r.ruleName}")
                }
                if (r.action and NotifRecord.Action.BARK != 0) add("Bark 已推送")
                if (r.action and NotifRecord.Action.WEBHOOK != 0) add("Webhook 已发送")
                if (r.action and NotifRecord.Action.READ != 0) add("已点击通知按钮")
            }
            b.ruleName.text = if (extras.isEmpty()) "" else "· " + extras.joinToString(" · ")
            b.ruleName.visibility = if (extras.isEmpty()) View.GONE else View.VISIBLE

            if (r.aiScore > 0f) {
                b.score.text = "AI ${(r.aiScore * 100).toInt()}%"
                b.score.visibility = View.VISIBLE
            } else {
                b.score.visibility = View.GONE
            }

            b.badgeRow.visibility = if (b.statusBadge.visibility == View.VISIBLE ||
                b.ruleName.visibility == View.VISIBLE || b.score.visibility == View.VISIBLE) View.VISIBLE else View.GONE

            val fresh = r.action == NotifRecord.Action.NONE &&
                System.currentTimeMillis() - r.postTime < 30 * 60 * 1000L
            b.unreadDot.visibility = if (fresh) View.VISIBLE else View.GONE

            b.root.setOnClickListener { onClick(r) }
            b.root.setOnLongClickListener {
                onLongClick(r, b.root)
                true
            }
        }

        private fun badge(ctx: Context, text: String, color: Int, bg: Int) {
            b.statusBadge.visibility = View.VISIBLE
            b.statusBadge.text = text
            b.statusBadge.setTextColor(ContextCompat.getColor(ctx, color))
            b.statusBadge.backgroundTintList = ContextCompat.getColorStateList(ctx, bg)
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ITEM = 1

        private val DIFF = object : DiffUtil.ItemCallback<HistoryEntry>() {
            override fun areItemsTheSame(a: HistoryEntry, b: HistoryEntry): Boolean = when {
                a is HistoryEntry.Header && b is HistoryEntry.Header -> a.label == b.label
                a is HistoryEntry.Item && b is HistoryEntry.Item -> a.record.id == b.record.id
                else -> false
            }

            override fun areContentsTheSame(a: HistoryEntry, b: HistoryEntry): Boolean = a == b
        }

        /** 按自然日分组，生成「今天 / 昨天 / M月d日」标题。 */
        fun buildEntries(records: List<NotifRecord>): List<HistoryEntry> {
            if (records.isEmpty()) return emptyList()
            val out = ArrayList<HistoryEntry>(records.size + 8)
            var currentDay = Long.MIN_VALUE
            var groupStart = 0
            records.forEachIndexed { index, record ->
                val day = startOfDay(record.postTime)
                if (day != currentDay) {
                    // 上一组收尾，补 last 标记
                    closeGroup(out, records, groupStart, index)
                    out.add(HistoryEntry.Header(dayLabel(day)))
                    currentDay = day
                    groupStart = index
                }
            }
            closeGroup(out, records, groupStart, records.size)
            return out
        }

        private fun closeGroup(
            out: MutableList<HistoryEntry>,
            records: List<NotifRecord>,
            from: Int,
            to: Int
        ) {
            for (i in from until to) {
                out.add(
                    HistoryEntry.Item(
                        records[i],
                        first = i == from,
                        last = i == to - 1
                    )
                )
            }
        }

        private fun startOfDay(ts: Long): Long {
            val c = Calendar.getInstance()
            c.timeInMillis = ts
            c.set(Calendar.HOUR_OF_DAY, 0)
            c.set(Calendar.MINUTE, 0)
            c.set(Calendar.SECOND, 0)
            c.set(Calendar.MILLISECOND, 0)
            return c.timeInMillis
        }

        private fun dayLabel(dayStart: Long): String {
            val today = startOfDay(System.currentTimeMillis())
            val yesterday = today - 24L * 3600_000L
            val cal = Calendar.getInstance().apply { timeInMillis = dayStart }
            val thisYear = Calendar.getInstance().get(Calendar.YEAR)
            return when {
                dayStart == today -> "今天"
                dayStart == yesterday -> "昨天"
                cal.get(Calendar.YEAR) == thisYear ->
                    SimpleDateFormat("M月d日", Locale.getDefault()).format(Date(dayStart))
                else -> SimpleDateFormat("yyyy年M月d日", Locale.getDefault()).format(Date(dayStart))
            }
        }

        fun formatTime(ts: Long): String {
            val now = System.currentTimeMillis()
            return when {
                DateUtils.isToday(ts) -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))
                DateUtils.isToday(ts + 24 * 3600_000L) -> "昨天 " +
                    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))
                now - ts < 365L * 24 * 3600_000 ->
                    SimpleDateFormat("M月d日 HH:mm", Locale.getDefault()).format(Date(ts))
                else -> SimpleDateFormat("yyyy年M月d日", Locale.getDefault()).format(Date(ts))
            }
        }
    }
}
