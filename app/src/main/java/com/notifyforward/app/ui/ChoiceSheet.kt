package com.notifyforward.app.ui

import android.content.Context
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.notifyforward.app.R

/** One choice surface shared by settings and notification actions. */
object ChoiceSheet {
    fun show(context: Context, title: String, options: List<String>, selected: Int = -1, onPick: (Int) -> Unit) {
        fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
        val dialog = BottomSheetDialog(context)
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(24)) }
        column.addView(TextView(context).apply {
            text = title; textSize = 20f; setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, R.color.text_primary)); setPadding(dp(4), 0, dp(4), dp(16))
        })
        options.forEachIndexed { index, label ->
            column.addView(TextView(context).apply {
                text = label; textSize = 16f; minHeight = dp(56); gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
                setTextColor(ContextCompat.getColor(context, if(index == selected) R.color.brand_primary else R.color.text_primary))
                setBackgroundResource(R.drawable.bg_row_single)
                isFocusable = true
                if(index == selected) { setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_check, 0); contentDescription = "$label，当前选中" }
                setOnClickListener { dialog.dismiss(); onPick(index) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) })
        }
        val scroll = androidx.core.widget.NestedScrollView(context).apply { addView(column) }
        dialog.setContentView(scroll)
        dialog.show()
        dialog.findViewById<android.view.View>(com.google.android.material.R.id.design_bottom_sheet)?.backgroundTintList = ContextCompat.getColorStateList(context, R.color.page_bg)
        dialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
    }
}
