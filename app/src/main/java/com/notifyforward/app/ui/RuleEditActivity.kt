package com.notifyforward.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.notifyforward.app.R
import com.notifyforward.app.data.DbHelper
import com.notifyforward.app.data.Settings
import com.notifyforward.app.databinding.ActivityRuleEditBinding
import com.notifyforward.app.engine.BarkClient
import com.notifyforward.app.engine.RuleEngine
import com.notifyforward.app.engine.WebhookClient
import com.notifyforward.app.model.FilterRule
import com.notifyforward.app.model.NotifRecord
import com.notifyforward.app.ui.rules.AppPickerDialog
import com.notifyforward.app.util.AppInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RuleEditActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRuleEditBinding
    private var ruleId: Long = 0L
    private var original: FilterRule? = null
    private var selectedApps: List<String> = emptyList()
    private var matchMode: Int = FilterRule.MatchMode.ANY

    private val modeLabels = arrayOf(
        "包含任一关键词",
        "包含全部关键词",
        "不含任一关键词",
        "匹配正则表达式",
        "高级匹配（JSON 字段正则）",
        "所有通知（不筛选文本）"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRuleEditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ruleId = intent.getLongExtra(EXTRA_RULE_ID, 0L)
        setupDropdown()
        setupListeners()

        if (ruleId > 0L) {
            val rule = DbHelper.get(this).getRule(ruleId)
            if (rule == null) {
                finish()
                return
            }
            original = rule
            bindRule(rule)
            binding.toolbar.title = getString(R.string.title_rule_edit)
            binding.btnDelete.visibility = View.VISIBLE
        } else {
            binding.toolbar.title = getString(R.string.title_rule_new)
            binding.etWebhookBody.setText(WebhookClient.DEFAULT_BODY)
        }

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.btnSave.setOnClickListener { save() }
        binding.btnDelete.setOnClickListener { confirmDelete() }
    }

    private fun setupDropdown() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, modeLabels)
        binding.dropdownMatch.setAdapter(adapter)
        binding.dropdownMatch.setText(modeLabels[0], false)
        binding.dropdownMatch.setOnItemClickListener { _, _, position, _ ->
            matchMode = position
            updateRegexVisibility()
        }
    }

    private fun updateRegexVisibility() {
        val needRegex = matchMode == FilterRule.MatchMode.REGEX ||
            matchMode == FilterRule.MatchMode.JSON_REGEX
        binding.tilRegex.visibility = if (needRegex) View.VISIBLE else View.GONE
        // 「所有通知」模式不看关键词，直接隐藏，避免误解
        binding.tilKeywords.visibility =
            if (matchMode == FilterRule.MatchMode.ALL_NOTIFICATIONS) View.GONE else View.VISIBLE
    }

    private fun setupListeners() {
        binding.radioScope.setOnCheckedChangeListener { _, _ -> updateScopeUi() }
        binding.btnPickApps.setOnClickListener {
            AppPickerDialog.show(this, selectedApps) { list ->
                selectedApps = list
                updateScopeUi()
            }
        }
        binding.switchMute.setOnCheckedChangeListener { _, checked ->
            binding.tilMuteSeconds.visibility = if (checked) View.VISIBLE else View.GONE
        }
        binding.switchWebhook.setOnCheckedChangeListener { _, checked ->
            binding.webhookGroup.visibility = if (checked) View.VISIBLE else View.GONE
            if (checked && binding.etWebhookBody.text.isNullOrBlank()) {
                binding.etWebhookBody.setText(WebhookClient.DEFAULT_BODY)
            }
        }
        binding.btnTestWebhook.setOnClickListener { testWebhook() }
    }

    private fun updateScopeUi() {
        val only = binding.radioOnly.isChecked || binding.radioExcept.isChecked
        binding.btnPickApps.isEnabled = only
        binding.btnPickApps.alpha = if (only) 1f else 0.5f

        val picked = if (selectedApps.isEmpty()) {
            "未选择应用"
        } else {
            val labels = selectedApps.take(3).map { AppInfo.label(this, it) }
            val more = if (selectedApps.size > 3) " 等 ${selectedApps.size} 个" else ""
            labels.joinToString("、") + more
        }
        binding.tvPickedApps.text = picked
    }

    private fun bindRule(rule: FilterRule) {
        binding.etName.setText(rule.name)
        binding.switchEnabled.isChecked = rule.enabled
        when (rule.appMode) {
            FilterRule.AppMode.ONLY -> binding.radioOnly.isChecked = true
            FilterRule.AppMode.EXCEPT -> binding.radioExcept.isChecked = true
            else -> binding.radioAll.isChecked = true
        }
        selectedApps = rule.apps
        updateScopeUi()

        matchMode = rule.matchMode.coerceIn(0, modeLabels.size - 1)
        binding.dropdownMatch.setText(modeLabels[matchMode], false)
        updateRegexVisibility()
        binding.etKeywords.setText(rule.keywords.joinToString("\n"))
        binding.etRegex.setText(rule.regex)

        binding.cbOngoing.isChecked = rule.skipOngoing
        binding.cbMedia.isChecked = rule.skipMedia
        binding.cbConversation.isChecked = rule.skipConversation

        binding.switchDismiss.isChecked = rule.effectDismiss
        binding.switchMute.isChecked = rule.muteSeconds > 0
        binding.tilMuteSeconds.visibility = if (rule.muteSeconds > 0) View.VISIBLE else View.GONE
        binding.etMuteSeconds.setText(rule.muteSeconds.takeIf { it > 0 }?.toString() ?: "15")

        binding.switchWebhook.isChecked = rule.webhookUrl.isNotBlank()
        binding.webhookGroup.visibility =
            if (rule.webhookUrl.isNotBlank()) View.VISIBLE else View.GONE
        binding.etWebhookUrl.setText(rule.webhookUrl)
        binding.etWebhookBody.setText(rule.webhookBody.ifBlank { WebhookClient.DEFAULT_BODY })

        binding.etButtons.setText(rule.actionButtons)
        binding.switchTts.isChecked = rule.tts
        binding.switchBark.isChecked = rule.bark
    }

    private fun collectRule(): FilterRule? {
        val name = binding.etName.text?.toString()?.trim().orEmpty()
        if (name.isBlank()) {
            binding.tilName.error = "请填写规则名称"
            return null
        }
        binding.tilName.error = null

        val appMode = when {
            binding.radioOnly.isChecked -> FilterRule.AppMode.ONLY
            binding.radioExcept.isChecked -> FilterRule.AppMode.EXCEPT
            else -> FilterRule.AppMode.ALL
        }
        if (appMode != FilterRule.AppMode.ALL && selectedApps.isEmpty()) {
            Toast.makeText(this, "请选择应用，或把范围改为所有应用", Toast.LENGTH_SHORT).show()
            return null
        }

        val keywords = binding.etKeywords.text?.toString().orEmpty()
            .split('\n', '，', ',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val regex = binding.etRegex.text?.toString()?.trim().orEmpty()

        if (keywords.isEmpty() &&
            appMode != FilterRule.AppMode.ONLY &&
            matchMode != FilterRule.MatchMode.ALL_NOTIFICATIONS
        ) {
            binding.tilKeywords.error = "请填写关键词，或把范围设为「仅过滤选中的应用」"
            return null
        }
        binding.tilKeywords.error = null

        if ((matchMode == FilterRule.MatchMode.REGEX || matchMode == FilterRule.MatchMode.JSON_REGEX) &&
            regex.isBlank()
        ) {
            binding.tilRegex.error = "请填写正则表达式"
            return null
        }
        binding.tilRegex.error = null

        val muteSeconds = if (binding.switchMute.isChecked) {
            binding.etMuteSeconds.text?.toString()?.toIntOrNull()?.coerceIn(1, 3600) ?: 15
        } else 0

        val webhookUrl = if (binding.switchWebhook.isChecked) {
            binding.etWebhookUrl.text?.toString()?.trim().orEmpty()
        } else ""
        if (binding.switchWebhook.isChecked && webhookUrl.isBlank()) {
            Toast.makeText(this, "请填写 Webhook 地址", Toast.LENGTH_SHORT).show()
            return null
        }

        val bark = binding.switchBark.isChecked
        if (bark) {
            val settings = Settings.get(this)
            if (settings.barkKey.isBlank()) {
                Toast.makeText(this, "请先在「设置 → Bark 推送」中填写 Device Key", Toast.LENGTH_LONG).show()
                return null
            }
            if (settings.barkEncrypt) {
                val err = BarkClient.validateSecret(settings.barkSecret)
                if (err != null) {
                    Toast.makeText(this, err, Toast.LENGTH_LONG).show()
                    return null
                }
            }
        }

        val draft = FilterRule(
            name = name,
            enabled = binding.switchEnabled.isChecked,
            appMode = appMode,
            apps = if (appMode == FilterRule.AppMode.ALL) emptyList() else selectedApps,
            matchMode = matchMode,
            keywords = keywords,
            regex = regex,
            skipOngoing = binding.cbOngoing.isChecked,
            skipMedia = binding.cbMedia.isChecked,
            skipConversation = binding.cbConversation.isChecked,
            effectDismiss = binding.switchDismiss.isChecked,
            muteSeconds = muteSeconds,
            webhookUrl = webhookUrl,
            webhookBody = binding.etWebhookBody.text?.toString().orEmpty(),
            actionButtons = binding.etButtons.text?.toString()?.trim().orEmpty(),
            tts = binding.switchTts.isChecked,
            bark = bark
        )
        val error = RuleEngine.validateRegex(draft)
        if (error != null) {
            binding.tilRegex.error = error
            return null
        }
        return draft
    }

    private fun save() {
        val draft = collectRule() ?: return
        val riskAll = draft.matchMode == FilterRule.MatchMode.ALL_NOTIFICATIONS &&
            draft.appMode == FilterRule.AppMode.ALL && draft.effectDismiss
        if (riskAll) {
            MaterialAlertDialogBuilder(this)
                .setTitle("确认拦截全部通知？")
                .setMessage("该规则会对所有应用的所有通知执行「消除」，可能导致你收不到任何通知。")
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton("仍然保存") { _, _ -> persist(draft) }
                .show()
        } else {
            persist(draft)
        }
    }

    private fun persist(draft: FilterRule) {
        val rule = draft.copy(
            id = ruleId,
            hitCount = original?.hitCount ?: 0L,
            createdAt = original?.createdAt ?: System.currentTimeMillis(),
            priority = original?.priority ?: 0
        )
        DbHelper.get(this).saveRule(rule)
        Toast.makeText(this, R.string.toast_saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun confirmDelete() {
        MaterialAlertDialogBuilder(this)
            .setTitle("删除规则？")
            .setMessage("删除后无法恢复。")
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                DbHelper.get(this).deleteRule(ruleId)
                Toast.makeText(this, R.string.toast_deleted, Toast.LENGTH_SHORT).show()
                finish()
            }
            .show()
    }

    private fun testWebhook() {
        val url = binding.etWebhookUrl.text?.toString()?.trim().orEmpty()
        if (url.isBlank()) {
            Toast.makeText(this, "请先填写 Webhook 地址", Toast.LENGTH_SHORT).show()
            return
        }
        val draft = collectRule()
        val sample = NotifRecord(
            packageName = "com.example.demo",
            appName = "示例应用",
            title = "测试通知",
            text = "这是一条来自通知哨兵的 Webhook 测试消息",
            postTime = System.currentTimeMillis()
        )
        val rule = draft ?: FilterRule(name = binding.etName.text?.toString().orEmpty())
        binding.btnTestWebhook.isEnabled = false
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                WebhookClient.send(url, binding.etWebhookBody.text?.toString().orEmpty(), sample, rule)
            }
            binding.btnTestWebhook.isEnabled = true
            MaterialAlertDialogBuilder(this@RuleEditActivity)
                .setTitle(if (result.ok) "发送成功" else "发送失败")
                .setMessage("HTTP ${result.code}\n${result.message}")
                .setPositiveButton("知道了", null)
                .show()
        }
    }

    companion object {
        private const val EXTRA_RULE_ID = "rule_id"

        fun start(context: Context, id: Long) {
            context.startActivity(
                Intent(context, RuleEditActivity::class.java).putExtra(EXTRA_RULE_ID, id)
            )
        }
    }
}
