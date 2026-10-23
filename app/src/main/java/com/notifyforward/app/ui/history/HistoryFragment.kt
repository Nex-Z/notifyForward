package com.notifyforward.app.ui.history

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.notifyforward.app.R
import com.notifyforward.app.data.DataEvents
import com.notifyforward.app.data.DbHelper
import com.notifyforward.app.data.Settings
import com.notifyforward.app.databinding.FragmentHistoryBinding
import com.notifyforward.app.model.NotifRecord
import com.notifyforward.app.ui.Refreshable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HistoryFragment : Fragment(), Refreshable {

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: HistoryAdapter
    private var filter: Int = FILTER_ALL
    private var query: String = ""
    private var loadJob: Job? = null
    private val debounce = Handler(Looper.getMainLooper())
    private var debounceTask: Runnable? = null
    private var receiverRegistered = false

    private val dataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            load()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = HistoryAdapter(
            onClick = { record -> openDetail(record) },
            onLongClick = { record, _ -> showActions(record) }
        )
        binding.recycler.layoutManager = LinearLayoutManager(requireContext())
        binding.recycler.adapter = adapter
        binding.recycler.setHasFixedSize(false)

        binding.swipe.setOnRefreshListener { load() }

        binding.editSearch.doAfterTextChanged { text ->
            binding.btnClearSearch.visibility =
                if (text.isNullOrBlank()) View.GONE else View.VISIBLE
            query = text?.toString()?.trim().orEmpty()
            debounceTask?.let { debounce.removeCallbacks(it) }
            val r = Runnable { load() }
            debounceTask = r
            debounce.postDelayed(r, 260)
        }
        binding.btnClearSearch.setOnClickListener {
            binding.editSearch.setText("")
        }

        binding.chipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            filter = when (checkedIds.firstOrNull()) {
                R.id.chipFiltered -> FILTER_FILTERED
                R.id.chipNormal -> FILTER_NORMAL
                R.id.chipForwarded -> FILTER_FORWARDED
                else -> FILTER_ALL
            }
            load()
        }

        binding.btnMore.setOnClickListener { showMoreMenu(it) }

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

    private fun load() {
        val ctx = context ?: return
        loadJob?.cancel()
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) {
                val db = DbHelper.get(ctx)
                if (filter == FILTER_FORWARDED) {
                    db.queryForwarded(query)
                } else if (query.isBlank()) {
                    db.queryNotifications(
                        limit = 400,
                        onlyFiltered = when (filter) {
                            FILTER_FILTERED -> true
                            FILTER_NORMAL -> false
                            else -> null
                        }
                    )
                } else {
                    db.searchNotifications(query)
                }
            }
            val firstVisible = (binding.recycler.layoutManager as? LinearLayoutManager)
                ?.findFirstVisibleItemPosition() ?: 0
            adapter.submit(rows) {
                // 用户本来就在顶部时，跟随到最新一条；浏览旧记录时不打断
                if (firstVisible <= 1) {
                    _binding?.recycler?.scrollToPosition(0)
                }
            }
            binding.swipe.isRefreshing = false

            val empty = rows.isEmpty()
            binding.emptyView.visibility = if (empty) View.VISIBLE else View.GONE
            if (empty) {
                val searching = query.isNotBlank()
                binding.emptyTitle.text =
                    if (searching) getString(R.string.empty_search) else getString(R.string.empty_history_title)
                binding.emptyDesc.text =
                    if (searching) "" else getString(R.string.empty_history_desc)
                binding.emptyDesc.visibility = if (searching) View.GONE else View.VISIBLE
            }
        }
    }

    private fun openDetail(record: NotifRecord) {
        NotificationDetailSheet(requireContext(), record) {
            load()
        }.show()
    }

    private fun showMoreMenu(anchor: View) {
        val popup = android.widget.PopupMenu(requireContext(), anchor)
        popup.menu.add(0, 1, 0, getString(R.string.action_clear_history))
        popup.setOnMenuItemClickListener {
            confirmClear()
            true
        }
        popup.show()
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.action_clear_history))
            .setMessage("将删除本机保存的全部通知记录，过滤规则不受影响。")
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_clear) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) { DbHelper.get(requireContext()).clearNotifications() }
                    load()
                    Toast.makeText(requireContext(), "已清空", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun showActions(record: NotifRecord) {
        val items = arrayOf(
            getString(R.string.action_mark_normal),
            getString(R.string.action_mark_spam),
            getString(R.string.action_copy),
            getString(R.string.action_delete)
        )
        com.notifyforward.app.ui.ChoiceSheet.show(requireContext(), record.appName, items.toList()) { which ->
                when (which) {
                    0 -> feedback(record, NotifRecord.Feedback.NORMAL)
                    1 -> feedback(record, NotifRecord.Feedback.SPAM)
                    2 -> {
                        copy(record)
                        Toast.makeText(requireContext(), R.string.toast_copied, Toast.LENGTH_SHORT).show()
                    }
                    3 -> {
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle("删除这条历史记录？")
                            .setMessage("同时删除该通知的转发记录，个人标注仍保留。")
                            .setNegativeButton("保留", null)
                            .setPositiveButton("删除记录") { _, _ ->
                                DbHelper.get(requireContext()).deleteNotification(record.id)
                                load()
                            }.show()
                    }
                }
            }
    }

    private fun copy(record: NotifRecord) {
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("notification", "${record.title}\n${record.displayText}"))
    }

    private fun feedback(record: NotifRecord, kind: Int) {
        val ctx = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                com.notifyforward.app.engine.LearningStore(ctx).label(record, kind)
            } }
            if (result.isFailure) {
                Toast.makeText(ctx, "标注保存失败，请稍后重试", Toast.LENGTH_SHORT).show()
                return@launch
            }
            load()
            Toast.makeText(ctx, "标注已保存，重复标注不会增加样本数", Toast.LENGTH_SHORT).show()
        }
    }

    private fun maybeSuggestExclude(pkg: String, appName: String) {
        val ctx = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) {
                DbHelper.get(ctx).countFeedback(pkg, NotifRecord.Feedback.NORMAL)
            }
            val settings = Settings.get(ctx)
            if (count >= 3 && !settings.isExcluded(pkg) && _binding != null) {
                Snackbar.make(
                    binding.root,
                    "已多次将「$appName」的通知标记为正常，要不要不再过滤它？",
                    Snackbar.LENGTH_LONG
                ).setAction("不再过滤") {
                    settings.addExclude(pkg)
                    Toast.makeText(ctx, "已排除 $appName", Toast.LENGTH_SHORT).show()
                }.show()
            }
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) refresh()
    }

    override fun onDestroyView() {
        debounceTask?.let { debounce.removeCallbacks(it) }
        if (receiverRegistered) {
            runCatching { requireContext().unregisterReceiver(dataReceiver) }
            receiverRegistered = false
        }
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        const val FILTER_ALL = 0
        const val FILTER_FILTERED = 1
        const val FILTER_NORMAL = 2
        const val FILTER_FORWARDED = 3
    }
}
