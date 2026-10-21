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
        textSize = dp(10f)
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
    private val colorText = ContextCompat.getColor(context, R.color.text_tertiary)
    private val colorValue = ContextCompat.getColor(context, R.color.text_secondary)

    fun setData(totals: IntArray, filtered: IntArray, labels: Array<String>) {
        this.totals = totals
        this.filtered = filtered
        this.labels = labels
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
        val padTop = dp(18f)
        val chartH = height - padBottom - padTop
        val slot = width.toFloat() / totals.size
        val barW = slot * 0.22f
        val gap = slot * 0.06f
        val radius = dp(4f)
        val maxValue = max(totals.maxOrNull() ?: 0, 1)
        val labelStep = kotlin.math.ceil(totals.size / 7f).toInt().coerceAtLeast(1)

        // 基线
        paint.color = colorTrack
        canvas.drawRoundRect(
            RectF(0f, padTop + chartH, width.toFloat(), padTop + chartH + dp(1f)),
            dp(1f), dp(1f), paint
        )

        for (i in totals.indices) {
            val cx = slot * i + slot / 2f
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

            if (total > 0) {
                textPaint.color = colorValue
                canvas.drawText(
                    total.toString(),
                    cx,
                    padTop + chartH - totalH - dp(4f),
                    textPaint
                )
            }

            textPaint.color = colorText
            if (i % labelStep == 0 || i == totals.size - 1) {
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
