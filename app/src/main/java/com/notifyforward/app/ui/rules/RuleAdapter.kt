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

            // 图标表达规则效果，颜色保持稳定，不按 id 随机轮换。
            val (icon, color) = when {
                rule.bark || rule.webhookUrl.isNotBlank() -> R.drawable.ic_send to R.color.brand_primary
                rule.muteSeconds > 0 -> R.drawable.ic_moon to R.color.purple
                rule.tts -> R.drawable.ic_bell to R.color.warning
                rule.effectDismiss -> R.drawable.ic_filter to R.color.brand_primary
                else -> R.drawable.ic_layers to R.color.text_secondary
            }
            b.icon.setImageResource(icon)
            b.iconTile.backgroundTintList = ContextCompat.getColorStateList(ctx, color)
            b.name.alpha = 1f
            b.summary.alpha = 1f
            b.switchRule.contentDescription = "启用规则：${b.name.text}"
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
