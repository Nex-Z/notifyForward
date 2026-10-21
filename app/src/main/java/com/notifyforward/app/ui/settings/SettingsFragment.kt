package com.notifyforward.app.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.notifyforward.app.R
import com.notifyforward.app.data.DbHelper
import com.notifyforward.app.data.Settings
import com.notifyforward.app.databinding.DialogInputBinding
import com.notifyforward.app.databinding.FragmentSettingsBinding
import com.notifyforward.app.engine.AiEngine
import com.notifyforward.app.engine.BarkClient
import com.notifyforward.app.engine.DndController
import com.notifyforward.app.service.KeepAliveService
import com.notifyforward.app.service.WatchdogService
import com.notifyforward.app.ui.Refreshable
import com.notifyforward.app.ui.rules.AppPickerDialog
import com.notifyforward.app.util.CsvExporter
import com.notifyforward.app.util.RuleIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsFragment : Fragment(), Refreshable {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var settings: Settings
    private var pendingExportJson: String = ""

    private val createDoc =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null && pendingExportJson.isNotEmpty()) {
                val ok = RuleIO.writeToUri(requireContext(), uri, pendingExportJson)
                toast(if (ok) "规则已导出" else "导出失败")
            }
        }

    private val openDoc =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importFrom(uri)
        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settings = Settings.get(requireContext())
        bindValues()
        bindListeners()
    }

    override fun refresh() {
        if (_binding != null) bindValues()
    }

    private fun bindValues() {
        val ctx = requireContext()
        val listenerOn = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
        binding.tvListenerStatus.text =
            getString(if (listenerOn) R.string.set_listener_on else R.string.set_listener_off)

        binding.tvDndStatus.text =
            getString(if (DndController.isGranted(ctx)) R.string.set_dnd_on else R.string.set_dnd_off)

        binding.switchMaster.isChecked = settings.masterEnabled
        binding.switchAi.isChecked = settings.aiEnabled
        binding.sliderThreshold.value = (settings.aiThreshold * 100).coerceIn(50f, 99f)
        binding.tvThreshold.text = getString(R.string.ai_threshold_value_short, (settings.aiThreshold * 100).toInt())
        binding.tvExcludeValue.text = if (settings.excludeApps.isEmpty()) {
            getString(R.string.set_excludes_desc)
        } else {
            "已排除 ${settings.excludeApps.size} 个应用"
        }
        binding.switchNotice.isChecked = settings.showFilterNotice
        binding.switchSmartMute.isChecked = settings.smartMute
        binding.switchKeepAlive.isChecked = settings.keepAlive

        val pm = ctx.getSystemService(PowerManager::class.java)
        val ignoring = pm.isIgnoringBatteryOptimizations(ctx.packageName)
        binding.tvBatteryStatus.text =
            if (ignoring) "已忽略电池优化" else "未忽略，系统可能限制后台运行"
        binding.tvRetention.text = getString(R.string.set_retention_value, settings.retentionDays)

        val stats = AiEngine.stats(ctx)
        binding.tvAiStats.text = getString(R.string.ai_stats, stats.feedback, stats.vocabulary)

        // Bark
        binding.tvBarkServer.text = settings.barkServer
            .removePrefix("https://").removePrefix("http://")
        binding.tvBarkKey.text =
            if (settings.barkKey.isBlank()) "未配置" else mask(settings.barkKey)
        binding.tvBarkGroup.text = settings.barkGroup.ifBlank { "通知哨兵" }
        binding.tvBarkLevel.text = levelLabel(settings.barkLevel)
        binding.switchBarkEncrypt.isChecked = settings.barkEncrypt
        binding.barkSecretGroup.visibility =
            if (settings.barkEncrypt) View.VISIBLE else View.GONE
        binding.tvBarkSecret.text = if (settings.barkSecret.isBlank()) {
            "未设置（必须与 Bark App 一致）"
        } else {
            val algo = BarkClient.algorithmOf(settings.barkSecret) ?: "长度不合法"
            "$algo · ${mask(settings.barkSecret)}"
        }

        val version = runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        }.getOrNull() ?: "1.0.0"
        binding.tvVersion.text = getString(R.string.set_version, version)
    }

    private fun bindListeners() {
        binding.rowListener.setOnClickListener {
            runCatching { startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        }
        binding.rowDnd.setOnClickListener {
            runCatching { startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
        }
        binding.switchMaster.setOnCheckedChangeListener { _, checked -> settings.masterEnabled = checked }
        binding.switchAi.setOnCheckedChangeListener { _, checked -> settings.aiEnabled = checked }
        binding.sliderThreshold.addOnChangeListener { _, value, _ ->
            settings.aiThreshold = value / 100f
            binding.tvThreshold.text = getString(R.string.ai_threshold_value_short, value.toInt())
        }
        binding.rowExcludes.setOnClickListener {
            AppPickerDialog.show(requireContext(), settings.excludeApps.toList()) { list ->
                settings.excludeApps = list.toSet()
                refresh()
            }
        }
        binding.switchNotice.setOnCheckedChangeListener { _, checked -> settings.showFilterNotice = checked }
        binding.switchSmartMute.setOnCheckedChangeListener { _, checked -> settings.smartMute = checked }
        binding.switchKeepAlive.setOnCheckedChangeListener { _, checked ->
            settings.keepAlive = checked
            if (checked) {
                KeepAliveService.start(requireContext())
                WatchdogService.schedule(requireContext())
            } else {
                KeepAliveService.stop(requireContext())
                WatchdogService.cancel(requireContext())
            }
        }
        binding.rowRetention.setOnClickListener { pickRetention() }
        binding.rowBattery.setOnClickListener { requestIgnoreBattery() }
        binding.rowCloneCheck.setOnClickListener { showCloneDiagnosis() }
        binding.rowKeepAliveGuide.setOnClickListener { showKeepAliveGuide() }
        binding.rowExportCsv.setOnClickListener { exportCsv() }
        binding.rowClearHistory.setOnClickListener { confirmClearHistory() }
        binding.rowRulesIo.setOnClickListener { showRulesIo() }
        binding.btnResetAi.setOnClickListener { confirmResetAi() }

        binding.rowBarkServer.setOnClickListener {
            inputDialog("Bark 服务器", settings.barkServer, "https://api.day.app") { v ->
                settings.barkServer = v.ifBlank { "https://api.day.app" }
                refresh()
            }
        }
        binding.rowBarkKey.setOnClickListener {
            inputDialog("Device Key", settings.barkKey, "在 Bark App 首页复制 Device Key") { v ->
                settings.barkKey = v
                refresh()
            }
        }
        binding.rowBarkGroup.setOnClickListener {
            inputDialog("通知分组", settings.barkGroup, "例如：通知哨兵") { v ->
                settings.barkGroup = v.ifBlank { "通知哨兵" }
                refresh()
            }
        }
        binding.rowBarkLevel.setOnClickListener { pickBarkLevel() }
        binding.switchBarkEncrypt.setOnCheckedChangeListener { _, checked ->
            settings.barkEncrypt = checked
            refresh()
            if (checked && settings.barkSecret.isBlank()) {
                toast("请在 Bark App 中设置加密密钥后，填入下方「加密密钥」")
            }
        }
        binding.rowBarkSecret.setOnClickListener {
            inputDialog("加密密钥", settings.barkSecret, "16 / 24 / 32 位 ASCII 字符", password = true) { v ->
                settings.barkSecret = v
                val err = if (v.isBlank()) "密钥不能为空" else BarkClient.validateSecret(v)
                if (err != null) toast(err) else toast("密钥已保存（${BarkClient.algorithmOf(v)}）")
                refresh()
            }
        }
        binding.rowBarkTest.setOnClickListener { testBark() }
    }

    private fun mask(s: String): String =
        if (s.length <= 4) "••••" else "••••" + s.takeLast(4)

    private fun levelLabel(v: String): String = when (v) {
        "timeSensitive" -> "时效性"
        "passive" -> "静默"
        "critical" -> "紧急"
        else -> "活跃"
    }

    private fun pickBarkLevel() {
        val values = arrayOf("active", "timeSensitive", "passive", "critical")
        val labels = arrayOf("活跃（立即提醒）", "时效性（可穿透专注模式）", "静默（不响铃）", "紧急（需 Bark 授权）")
        val index = values.indexOf(settings.barkLevel).coerceAtLeast(0)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("通知级别")
            .setSingleChoiceItems(labels, index) { dialog, which ->
                settings.barkLevel = values[which]
                dialog.dismiss()
                refresh()
            }
            .show()
    }

    private fun inputDialog(
        title: String,
        value: String,
        hint: String,
        password: Boolean = false,
        onSave: (String) -> Unit
    ) {
        val b = DialogInputBinding.inflate(layoutInflater)
        b.til.hint = hint
        b.edit.setText(value)
        b.edit.inputType = if (password) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        } else {
            InputType.TYPE_CLASS_TEXT
        }
        b.edit.setSelection(b.edit.text?.length ?: 0)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setView(b.root)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton("保存") { _, _ ->
                onSave(b.edit.text?.toString()?.trim().orEmpty())
            }
            .show()
    }

    private fun testBark() {
        if (settings.barkKey.isBlank()) {
            toast("请先填写 Device Key")
            return
        }
        if (settings.barkEncrypt) {
            val err = BarkClient.validateSecret(settings.barkSecret)
            if (err != null) {
                toast(err)
                return
            }
        }
        val ctx = requireContext()
        binding.rowBarkTest.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) { BarkClient.test(ctx) }
            binding.rowBarkTest.isEnabled = true
            MaterialAlertDialogBuilder(ctx)
                .setTitle(if (res.ok) "Bark 推送成功" else "Bark 推送失败")
                .setMessage(
                    if (res.ok) "请查看 iPhone 上的 Bark 通知。\nHTTP ${res.code}"
                    else "HTTP ${res.code}\n${res.message}"
                )
                .setPositiveButton("知道了", null)
                .show()
        }
    }

    private fun requestIgnoreBattery() {
        val ctx = requireContext()
        val pm = ctx.getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
            toast("已忽略电池优化")
            return
        }
        val request = Intent(
            AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${ctx.packageName}")
        )
        runCatching { startActivity(request) }.onFailure {
            runCatching {
                startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }

    private fun showCloneDiagnosis() {
        viewLifecycleOwner.lifecycleScope.launch {
            val groups = withContext(Dispatchers.IO) { DbHelper.get(requireContext()).uidGroups() }
            if (_binding == null) return@launch
            if (groups.isEmpty()) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("分身通知检测")
                    .setMessage("还没有收到任何通知，暂时无法判断。\n\n先让微信（或分身）来一条消息，再回来查看。")
                    .setPositiveButton("知道了", null)
                    .show()
                return@launch
            }

            fun uidCount(uids: String) =
                uids.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct().size

            val cloneGroups = groups.filter { it.clones > 0 }
            val multiUid = groups.filter { uidCount(it.uids) > 1 }
            val header = when {
                cloneGroups.isNotEmpty() ->
                    "已收到分身通知，可以正常记录、过滤和转发（应用名会标注「分身」）。"
                multiUid.isNotEmpty() ->
                    "检测到同包名多个 uid，但没有标记为分身，请查看下面明细。"
                else ->
                    "目前只收到主应用的通知，没有看到分身来源。\n\n" +
                        "如果分身也发过消息，说明系统把分身放在独立用户空间，没有把通知转投给主空间的监听器。" +
                        "这是系统限制，非 root 应用无法绕过。"
            }

            val body = buildString {
                append(header).append("\n\n")
                groups.take(12).forEach { g ->
                    val n = uidCount(g.uids)
                    append("• ").append(g.appName)
                    append("　uid ").append(if (n > 1) "$n 个（${g.uids}）" else g.uids)
                    if (g.clones > 0) append("　分身 ").append(g.clones).append(" 条")
                    append("　共 ").append(g.total).append(" 条\n")
                }
                if (groups.size > 12) append("… 其余 ").append(groups.size - 12).append(" 个应用未显示")
            }

            MaterialAlertDialogBuilder(requireContext())
                .setTitle("分身通知检测")
                .setMessage(body.trim())
                .setPositiveButton("知道了", null)
                .show()
        }
    }

    private fun showKeepAliveGuide() {
        val ctx = requireContext()
        MaterialAlertDialogBuilder(ctx)
            .setTitle("后台保活指南")
            .setMessage(
                "1. 在最近任务中长按本应用并「锁定」，避免一键清理时被杀。\n\n" +
                    "2. 忽略电池优化，并在系统设置中允许「自启动 / 后台运行」。\n\n" +
                    "3. 若系统仍频繁断开通知访问，关闭再重新开启通知使用权。\n\n" +
                    "4. Android 15+ 会限制第三方读取疑似含验证码的通知，这与保活无关。"
            )
            .setNeutralButton("应用设置") { _, _ ->
                runCatching {
                    startActivity(
                        Intent(
                            AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${ctx.packageName}")
                        )
                    )
                }
            }
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun pickRetention() {
        val options = intArrayOf(7, 30, 90)
        val labels = arrayOf("7 天", "30 天", "90 天")
        val index = options.indexOf(settings.retentionDays).coerceAtLeast(0)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.set_retention)
            .setSingleChoiceItems(labels, index) { dialog, which ->
                settings.retentionDays = options[which]
                val ctx = requireContext()
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    DbHelper.get(ctx).deleteOlderThan(options[which])
                }
                dialog.dismiss()
                refresh()
            }
            .show()
    }

    private fun exportCsv() {
        val ctx = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) { CsvExporter.export(ctx) }
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching {
                startActivity(Intent.createChooser(intent, "导出通知历史"))
            }.onFailure {
                toast("导出失败：${it.message}")
            }
        }
    }

    private fun confirmClearHistory() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.set_clear_history)
            .setMessage("将删除本机保存的全部通知记录，规则不受影响。")
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                val ctx = requireContext()
                viewLifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) { DbHelper.get(ctx).clearNotifications() }
                    com.notifyforward.app.util.Notices.cancelSummary(ctx)
                    toast("历史已清空")
                }
            }
            .show()
    }

    private fun showRulesIo() {
        val items = arrayOf("导出到剪贴板", "导出到文件", "从剪贴板导入", "从文件导入")
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.set_export_rules)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        val json = RuleIO.exportJson(requireContext())
                        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("filterbox rules", json))
                        toast("规则已复制到剪贴板")
                    }
                    1 -> {
                        pendingExportJson = RuleIO.exportJson(requireContext())
                        createDoc.launch("notifyforward-rules.json")
                    }
                    2 -> {
                        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(requireContext())?.toString()
                        if (text.isNullOrBlank()) {
                            toast("剪贴板为空")
                        } else {
                            importText(text)
                        }
                    }
                    3 -> openDoc.launch(arrayOf("application/json", "text/plain", "*/*"))
                }
            }
            .show()
    }

    private fun importFrom(uri: Uri) {
        val text = RuleIO.readFromUri(requireContext(), uri)
        if (text.isNullOrBlank()) {
            toast("读取文件失败")
            return
        }
        importText(text)
    }

    private fun importText(text: String) {
        val ctx = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) { RuleIO.importJson(ctx, text) }
            toast(if (count > 0) "已导入 $count 条规则" else "没有识别到有效规则")
        }
    }

    private fun confirmResetAi() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.ai_reset)
            .setMessage("将清除你此前的全部反馈学习数据，恢复为内置模型。")
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton("重置") { _, _ ->
                AiEngine.reset(requireContext())
                refresh()
                toast("AI 模型已重置")
            }
            .show()
    }

    private fun toast(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) refresh()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
