package com.notifyforward.app.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.notifyforward.app.R
import kotlin.math.max

/** 近 7 天双柱状图：全部通知 vs 被拦截通知。 */
class WeekBarChart @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()

    private var totals: IntArray = IntArray(0)
    private var filtered: IntArray = IntArray(0)
    private var labels: Array<String> = emptyArray()
    private var compact = false

    private val colorTotal = ContextCompat.getColor(context, R.color.brand_primary)
    private val colorFiltered = ContextCompat.getColor(context, R.color.accent)
    private val colorTrack = ContextCompat.getColor(context, R.color.chart_track)
    private val colorText = ContextCompat.getColor(context, R.color.text_secondary)
    private val colorValue = ContextCompat.getColor(context, R.color.text_secondary)

    fun setData(totals: IntArray, filtered: IntArray, labels: Array<String>) {
        this.totals = totals
        this.filtered = IntArray(totals.size) { filtered.getOrElse(it) { 0 }.coerceIn(0, totals[it].coerceAtLeast(0)) }
        this.labels = labels
        contentDescription = "通知趋势：" + totals.indices.joinToString("；") { "${labels.getOrElse(it) { (it + 1).toString() }}，全部 ${totals[it]}，已过滤 ${this.filtered[it]}" }
        invalidate()
    }

    /** 迷你模式：只画总量柱，不画数值与日期，用于概览卡。 */
    fun setCompact(value: Boolean) {
        compact = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (totals.isEmpty()) return
        if (compact) {
            drawCompact(canvas)
            return
        }

        val padBottom = dp(24f)
        val padTop = dp(24f)
        val chartH = (height - padBottom - padTop).coerceAtLeast(0f)
        val side = dp(12f)
        val slot = (width - side * 2) / totals.size
        val barW = slot * 0.22f
        val gap = slot * 0.06f
        val radius = dp(4f)
        val maxValue = max(totals.maxOrNull() ?: 0, 1)
        val labelStep = kotlin.math.ceil(totals.size / 7f).toInt().coerceAtLeast(1)

        // 轻量网格给柱高提供参照，密集时间段不叠加数字标签。
        paint.color = colorTrack
        paint.strokeWidth = dp(0.5f)
        for (line in 0..3) {
            val y = padTop + chartH * line / 4f
            canvas.drawLine(0f, y, width.toFloat(), y, paint)
        }
        // 基线
        paint.color = colorTrack
        canvas.drawRoundRect(
            RectF(0f, padTop + chartH, width.toFloat(), padTop + chartH + dp(1f)),
            dp(1f), dp(1f), paint
        )

        for (i in totals.indices) {
            val cx = side + slot * i + slot / 2f
            val total = totals[i]
            val filt = filtered[i]

            val totalH = chartH * (total.toFloat() / maxValue)
            val left = cx - barW - gap / 2f
            rect.set(left, padTop + chartH - totalH, left + barW, padTop + chartH)
            paint.color = colorTotal
            if (totalH > 0f) canvas.drawRoundRect(rect, radius, radius, paint)

            val filtH = chartH * (filt.toFloat() / maxValue)
            val left2 = cx + gap / 2f
            rect.set(left2, padTop + chartH - filtH, left2 + barW, padTop + chartH)
            paint.color = colorFiltered
            if (filtH > 0f) canvas.drawRoundRect(rect, radius, radius, paint)

            if (total > 0 && totals.size <= 7) {
                textPaint.color = colorValue
                canvas.drawText(
                    total.toString(),
                    cx,
                    padTop + chartH - totalH - dp(4f),
                    textPaint
                )
            }

            textPaint.color = colorText
            if (i % labelStep == 0) {
                val label = labels.getOrElse(i) { "" }
                canvas.drawText(label, cx, height - dp(8f), textPaint)
            }
        }
    }

    /** 概览卡里的迷你柱图：浅色底柱 + 蓝色实柱，不画文字。 */
    private fun drawCompact(canvas: Canvas) {
        val chartH = height.toFloat() - dp(2f)
        if (chartH <= 0f) return
        val slot = width.toFloat() / totals.size
        val barW = slot * 0.52f
        val radius = dp(2f)
        val maxValue = max(totals.maxOrNull() ?: 0, 1)

        for (i in totals.indices) {
            val cx = slot * i + slot / 2f
            rect.set(cx - barW / 2f, 0f, cx + barW / 2f, chartH)
            paint.color = colorTrack
            canvas.drawRoundRect(rect, radius, radius, paint)

            val h = (chartH * 0.94f * (totals[i].toFloat() / maxValue))
                .coerceAtLeast(if (totals[i] > 0) dp(4f) else 0f)
            if (h > 0f) {
                rect.set(cx - barW / 2f, chartH - h, cx + barW / 2f, chartH)
                paint.color = colorTotal
                canvas.drawRoundRect(rect, radius, radius, paint)
            }
        }
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
