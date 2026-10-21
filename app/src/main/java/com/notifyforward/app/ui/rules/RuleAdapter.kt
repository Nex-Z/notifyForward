package com.notifyforward.app.ui.rules

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.notifyforward.app.R
import com.notifyforward.app.databinding.ItemRuleBinding
import com.notifyforward.app.model.FilterRule
import kotlin.math.abs

class RuleAdapter(
    private val onToggle: (FilterRule, Boolean) -> Unit,
    private val onClick: (FilterRule) -> Unit,
    private val onLongClick: (FilterRule, android.view.View) -> Unit
) : ListAdapter<FilterRule, RuleAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemRuleBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) =
        holder.bind(getItem(position), position, itemCount)

    inner class VH(private val b: ItemRuleBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(rule: FilterRule, position: Int, total: Int) {
            val ctx = b.root.context
            b.name.text = rule.name.ifBlank { "未命名规则" }
            b.summary.text = rule.summary()

            b.root.setBackgroundResource(
                when {
                    total <= 1 -> R.drawable.bg_row_single
                    position == 0 -> R.drawable.bg_row_top
                    position == total - 1 -> R.drawable.bg_row_bottom
                    else -> R.drawable.bg_row_middle
                }
            )
            b.rowDivider.visibility = if (position == total - 1) View.GONE else View.VISIBLE

            // 按规则 id 轮转强调色，相邻规则不撞色
            val palette = intArrayOf(
                R.color.danger,
                R.color.accent,
                R.color.warning,
                R.color.purple,
                R.color.brand_primary,
                R.color.teal
            )
            val index = if (rule.id > 0) {
                (rule.id % palette.size).toInt()
            } else {
                abs(rule.name.hashCode()) % palette.size
            }
            b.iconTile.backgroundTintList =
                ContextCompat.getColorStateList(ctx, palette[index])

            b.name.alpha = if (rule.enabled) 1f else 0.45f
            b.summary.alpha = if (rule.enabled) 1f else 0.5f

            b.switchRule.setOnCheckedChangeListener(null)
            b.switchRule.isChecked = rule.enabled
            b.switchRule.setOnCheckedChangeListener { _, checked -> onToggle(rule, checked) }

            b.root.setOnClickListener { onClick(rule) }
            b.root.setOnLongClickListener {
                onLongClick(rule, b.root)
                true
            }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<FilterRule>() {
            override fun areItemsTheSame(a: FilterRule, b: FilterRule) = a.id == b.id
            override fun areContentsTheSame(a: FilterRule, b: FilterRule) = a == b
        }
    }
}
