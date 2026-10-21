package com.notifyforward.app.ui.rules

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.notifyforward.app.R
import com.notifyforward.app.data.DbHelper
import com.notifyforward.app.databinding.FragmentRulesBinding
import com.notifyforward.app.engine.RuleEngine
import com.notifyforward.app.model.FilterRule
import com.notifyforward.app.ui.Refreshable
import com.notifyforward.app.ui.RuleEditActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class RulesFragment : Fragment(), Refreshable {

    private var _binding: FragmentRulesBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: RuleAdapter
    private var query: String = ""
    private var stateFilter: Int = STATE_ALL

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRulesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = RuleAdapter(
            onToggle = { rule, checked ->
                DbHelper.get(requireContext()).setRuleEnabled(rule.id, checked)
                load()
            },
            onClick = { rule -> RuleEditActivity.start(requireContext(), rule.id) },
            onLongClick = { rule, _ -> showActions(rule) }
        )
        binding.recycler.layoutManager = LinearLayoutManager(requireContext())
        binding.recycler.adapter = adapter
        binding.fab.setOnClickListener { RuleEditActivity.start(requireContext(), 0L) }

        binding.editSearch.doAfterTextChanged { text ->
            query = text?.toString()?.trim().orEmpty()
            load()
        }
        binding.chipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            stateFilter = when (checkedIds.firstOrNull()) {
                R.id.chipEnabled -> STATE_ENABLED
                R.id.chipDisabled -> STATE_DISABLED
                else -> STATE_ALL
            }
            load()
        }
        load()
    }

    override fun refresh() {
        if (_binding != null) load()
    }

    private fun load() {
        val ctx = context ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val all = withContext(Dispatchers.IO) { DbHelper.get(ctx).getAllRules() }
            val rules = all.filter { rule ->
                val stateOk = when (stateFilter) {
                    STATE_ENABLED -> rule.enabled
                    STATE_DISABLED -> !rule.enabled
                    else -> true
                }
                val queryOk = query.isBlank() ||
                    rule.name.contains(query, ignoreCase = true) ||
                    rule.keywords.any { it.contains(query, ignoreCase = true) }
                stateOk && queryOk
            }
            adapter.submitList(rules)
            binding.emptyView.visibility = if (rules.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun showActions(rule: FilterRule) {
        val items = arrayOf(
            getString(R.string.action_test),
            getString(R.string.action_duplicate),
            getString(R.string.action_export),
            getString(R.string.action_delete)
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(rule.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> testHits(rule)
                    1 -> duplicate(rule)
                    2 -> share(rule)
                    3 -> confirmDelete(rule)
                }
            }
            .show()
    }

    private fun testHits(rule: FilterRule) {
        val ctx = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            val hits = withContext(Dispatchers.IO) {
                RuleEngine.countHits(rule, DbHelper.get(ctx).recentForRuleTest(500))
            }
            MaterialAlertDialogBuilder(ctx)
                .setTitle(getString(R.string.action_test))
                .setMessage("在最近保存的 500 条通知中，该规则命中了 $hits 条。")
                .setPositiveButton("知道了", null)
                .show()
        }
    }

    private fun duplicate(rule: FilterRule) {
        val ctx = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                DbHelper.get(ctx).saveRule(
                    rule.copy(id = 0L, name = "${rule.name} 副本", hitCount = 0L)
                )
            }
            load()
            Toast.makeText(ctx, "已复制规则", Toast.LENGTH_SHORT).show()
        }
    }

    private fun share(rule: FilterRule) {
        val json = JSONObject().put("filterRules", JSONArray().put(rule.toJson())).toString(2)
        runCatching {
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, json)
                    },
                    getString(R.string.action_export)
                )
            )
        }.onFailure {
            val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("rules", json))
            Toast.makeText(requireContext(), R.string.toast_copied, Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDelete(rule: FilterRule) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("删除规则？")
            .setMessage("「${rule.name}」删除后不可恢复。")
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                DbHelper.get(requireContext()).deleteRule(rule.id)
                load()
                Toast.makeText(requireContext(), R.string.toast_deleted, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) refresh()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        const val STATE_ALL = 0
        const val STATE_ENABLED = 1
        const val STATE_DISABLED = 2
    }
}
