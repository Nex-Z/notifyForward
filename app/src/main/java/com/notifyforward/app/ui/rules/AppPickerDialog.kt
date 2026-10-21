package com.notifyforward.app.ui.rules

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.notifyforward.app.R
import com.notifyforward.app.data.DbHelper
import com.notifyforward.app.databinding.DialogAppPickerBinding
import com.notifyforward.app.databinding.ItemAppCheckBinding
import com.notifyforward.app.util.AppInfo
import java.text.Collator
import java.util.Locale

/** 应用多选对话框（启动器应用 + 历史里出现过的应用）。 */
object AppPickerDialog {

    fun show(
        context: Context,
        selected: List<String>,
        onResult: (List<String>) -> Unit
    ) {
        val binding = DialogAppPickerBinding.inflate(LayoutInflater.from(context))
        val chosen = selected.toMutableSet()
        val all = loadApps(context)

        val adapter = Adapter(all, chosen)
        binding.recycler.layoutManager = LinearLayoutManager(context)
        binding.recycler.adapter = adapter

        binding.editSearch.doAfterTextChanged { text ->
            val q = text?.toString()?.trim().orEmpty()
            adapter.submit(
                if (q.isBlank()) all
                else all.filter { it.label.contains(q, true) || it.packageName.contains(q, true) }
            )
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle("选择应用")
            .setView(binding.root)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton("确定") { _, _ -> onResult(chosen.toList()) }
            .create()

        binding.btnCancel.setOnClickListener { dialog.dismiss() }
        binding.btnConfirm.setOnClickListener {
            onResult(chosen.toList())
            dialog.dismiss()
        }

        dialog.setOnShowListener {
            val w = (context.resources.displayMetrics.widthPixels * 0.94f).toInt()
            dialog.window?.setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
    }

    private fun loadApps(context: Context): List<AppInfo.Entry> {
        val list = AppInfo.launchableApps(context).toMutableList()
        val existing = list.map { it.packageName }.toHashSet()
        runCatching {
            DbHelper.get(context).knownPackages().forEach { pkg ->
                if (existing.add(pkg)) {
                    list.add(AppInfo.Entry(pkg, AppInfo.label(context, pkg), AppInfo.icon(context, pkg)))
                }
            }
        }
        val collator = Collator.getInstance(Locale.CHINA)
        list.sortWith(compareBy(collator) { it.label })
        return list
    }

    private class Adapter(
        apps: List<AppInfo.Entry>,
        private val chosen: MutableSet<String>
    ) : RecyclerView.Adapter<Adapter.VH>() {

        private var items: List<AppInfo.Entry> = apps

        fun submit(list: List<AppInfo.Entry>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemAppCheckBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

        inner class VH(private val b: ItemAppCheckBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(entry: AppInfo.Entry) {
                b.name.text = entry.label
                b.icon.setImageDrawable(entry.icon)
                b.check.setOnCheckedChangeListener(null)
                b.check.isChecked = chosen.contains(entry.packageName)
                b.root.setOnClickListener {
                    if (chosen.contains(entry.packageName)) chosen.remove(entry.packageName)
                    else chosen.add(entry.packageName)
                    b.check.isChecked = chosen.contains(entry.packageName)
                }
            }
        }
    }
}
