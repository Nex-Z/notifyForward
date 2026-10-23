package com.notifyforward.app.ui.stats

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.notifyforward.app.R
import com.notifyforward.app.data.DataEvents
import com.notifyforward.app.data.DbHelper
import com.notifyforward.app.databinding.FragmentStatsBinding
import com.notifyforward.app.databinding.ItemAppStatBinding
import com.notifyforward.app.databinding.ItemRuleStatBinding
import com.notifyforward.app.ui.Refreshable
import com.notifyforward.app.util.AppInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class StatsFragment : Fragment(), Refreshable {

    private var _binding: FragmentStatsBinding? = null
    private val binding get() = _binding!!
    private var receiverRegistered = false

    private var range = RANGE_TODAY
    private var customDays = 14

    private val dataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            load()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStatsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.miniChart.setCompact(true)
        binding.miniChart.setData(
            IntArray(7) { 0 }, IntArray(7) { 0 }, emptyArray()
        )
        binding.chart.setData(
            IntArray(7) { 0 }, IntArray(7) { 0 },
            lastLabels(7)
        )
        renderSegments()

        binding.segToday.setOnClickListener { selectRange(RANGE_TODAY) }
        binding.seg7.setOnClickListener { selectRange(RANGE_7) }
        binding.seg30.setOnClickListener { selectRange(RANGE_30) }
        binding.segCustom.setOnClickListener { chooseCustomRange() }

        load()

        ContextCompat.registerReceiver(
            requireContext(),
            dataReceiver,
            IntentFilter(DataEvents.ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true
    }

    override fun refresh() {
        if (_binding != null) load()
    }

    private fun selectRange(value: Int) {
        range = value
        renderSegments()
        load()
    }

    private fun chooseCustomRange() {
        val options = arrayOf("近 14 天", "近 30 天", "近 90 天")
        val days = intArrayOf(14, 30, 90)
        val checked = days.indexOf(customDays).coerceAtLeast(0)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("自定义范围")
            .setSingleChoiceItems(options, checked) { dialog, which ->
                customDays = days[which]
                dialog.dismiss()
                selectRange(RANGE_CUSTOM)
            }
            .show()
    }

    private fun renderSegments() {
        val ctx = requireContext()
        val views = listOf(binding.segToday, binding.seg7, binding.seg30, binding.segCustom)
        val index = when (range) {
            RANGE_7 -> 1
            RANGE_30 -> 2
            RANGE_CUSTOM -> 3
            else -> 0
        }
        views.forEachIndexed { i, tv ->
            val on = i == index
            tv.isSelected = on
            tv.setBackgroundResource(if (on) R.drawable.bg_segment_selected else 0)
            tv.setTextColor(
                ContextCompat.getColor(ctx, if (on) R.color.white else R.color.text_primary)
            )
            tv.setTypeface(Typeface.DEFAULT, if (on) Typeface.BOLD else Typeface.NORMAL)
        }
        if (range == RANGE_CUSTOM) {
            binding.segCustom.text = "近 ${customDays} 天"
        } else {
            binding.segCustom.text = "自定义"
        }
    }

    private fun spanDays(): Int = when (range) {
        RANGE_7 -> 7
        RANGE_30 -> 30
        RANGE_CUSTOM -> customDays
        else -> 1
    }

    private fun load() {
        val ctx = context ?: return
        val days = spanDays()
        viewLifecycleOwner.lifecycleScope.launch {
            val data = withContext(Dispatchers.IO) {
                val db = DbHelper.get(ctx)
                val end = startOfToday() + DAY_MS
                val start = end - days * DAY_MS
                val prevStart = start - days * DAY_MS

                val current = db.rangeStat(start, end)
                val previous = db.rangeStat(prevStart, start)
                val chartDays = if (days == 1) 7 else days.coerceAtMost(30)
                val chart = db.lastDays(chartDays)
                val apps = db.appStats(days, 6)
                val rules = db.getAllRules().filter { it.hitCount > 0 }
                    .sortedByDescending { it.hitCount }.take(6)
                StatsData(current, previous, chart, apps, rules)
            }
            if (_binding == null) return@launch

            val (current, previous, chart, apps, rules) = data

            binding.tvTotal.text = current.total.toString()
            setDelta(binding.tvDelta, current.total, previous.total, "较上期")
            binding.tvUnfiltered.text = (current.total - current.filtered).coerceAtLeast(0).toString()
            setDelta(
                binding.tvUnfilteredDelta,
                current.total - current.filtered,
                previous.total - previous.filtered,
                "较上期"
            )
            binding.tvFiltered.text = current.filtered.toString()
            setDelta(binding.tvFilteredDelta, current.filtered, previous.filtered, "较上期")
            binding.tvRuleHits.text = current.ruleHits.toString()
            setDelta(binding.tvRuleDelta, current.ruleHits, previous.ruleHits, "较上期")

            val totals = chart.map { it.total }.toIntArray()
            val filtered = chart.map { it.filtered }.toIntArray()
            val labels = chart.map {
                SimpleDateFormat("M/d", Locale.getDefault()).format(Date(it.dayStart))
            }.toTypedArray()
            binding.chartTitle.text = "通知趋势 · 近 ${chart.size} 天"
            binding.chart.setData(totals, filtered, labels)
            binding.miniChart.setData(
                totals.takeLast(7).toIntArray(),
                filtered.takeLast(7).toIntArray(),
                emptyArray()
            )

            binding.appList.removeAllViews()
            binding.appEmpty.visibility = if (apps.isEmpty()) View.VISIBLE else View.GONE
            // 数据量很小时也保留比例感，避免所有进度条都顶满
            val maxTotal = (apps.maxOfOrNull { it.total } ?: 1).coerceAtLeast(5)
            apps.forEach { stat ->
                val row = ItemAppStatBinding.inflate(layoutInflater, binding.appList, false)
                val icon = AppInfo.icon(ctx, stat.packageName)
                if (icon != null) {
                    row.icon.visibility = View.VISIBLE
                    row.iconLetter.visibility = View.GONE
                    row.icon.setImageDrawable(icon)
                } else {
                    row.icon.visibility = View.GONE
                    row.iconLetter.visibility = View.VISIBLE
                    row.iconLetter.text = AppInfo.initial(stat.appName)
                    row.iconLetter.backgroundTintList =
                        android.content.res.ColorStateList.valueOf(
                            AppInfo.tileColor(ctx, stat.packageName)
                        )
                }
                row.name.text = stat.appName
                row.count.text = stat.total.toString()
                row.progress.max = maxTotal
                row.progress.progress = stat.total
                binding.appList.addView(row.root)
            }

            binding.ruleList.removeAllViews()
            binding.ruleEmpty.visibility = if (rules.isEmpty()) View.VISIBLE else View.GONE
            rules.forEach { rule ->
                val row = ItemRuleStatBinding.inflate(layoutInflater, binding.ruleList, false)
                row.name.text = rule.name
                row.hits.text = "${rule.hitCount} 次"
                binding.ruleList.addView(row.root)
            }
        }
    }

    private fun setDelta(tv: android.widget.TextView, current: Int, previous: Int, prefix: String) {
        val ctx = context ?: return
        if (previous == 0) {
            tv.text = if (current == 0) "—" else "$prefix 新增"
            tv.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            return
        }
        val pct = ((current - previous) * 100f / previous).roundToInt()
        when {
            pct > 0 -> {
                tv.text = "$prefix ↑ $pct%"
                tv.setTextColor(ContextCompat.getColor(ctx, R.color.warning))
            }
            pct < 0 -> {
                tv.text = "$prefix ↓ ${-pct}%"
                tv.setTextColor(ContextCompat.getColor(ctx, R.color.accent))
            }
            else -> {
                tv.text = "$prefix 持平"
                tv.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            }
        }
    }

    private fun startOfToday(): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun lastLabels(days: Int): Array<String> {
        val fmt = SimpleDateFormat("M/d", Locale.getDefault())
        return (days - 1 downTo 0).map {
            fmt.format(Date(System.currentTimeMillis() - it * DAY_MS))
        }.toTypedArray()
    }

    private data class StatsData(
        val current: DbHelper.RangeStat,
        val previous: DbHelper.RangeStat,
        val chart: List<DbHelper.DayCount>,
        val apps: List<DbHelper.AppStat>,
        val rules: List<com.notifyforward.app.model.FilterRule>
    )

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) refresh()
    }

    override fun onDestroyView() {
        if (receiverRegistered) {
            runCatching { requireContext().unregisterReceiver(dataReceiver) }
            receiverRegistered = false
        }
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        const val RANGE_TODAY = 0
        const val RANGE_7 = 1
        const val RANGE_30 = 2
        const val RANGE_CUSTOM = 3
        const val DAY_MS = 24L * 3600_000L
    }
}
