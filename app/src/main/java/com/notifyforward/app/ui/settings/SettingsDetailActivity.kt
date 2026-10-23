package com.notifyforward.app.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.notifyforward.app.databinding.ActivitySettingsDetailBinding
import com.notifyforward.app.R

class SettingsDetailActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val b = ActivitySettingsDetailBinding.inflate(layoutInflater)
        setContentView(b.root)
        val section = intent.getIntExtra("section", 0).coerceIn(0, 6)
        b.toolbar.title = titles[section]
        b.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        if (savedInstanceState == null) supportFragmentManager.beginTransaction()
            .replace(R.id.settingsContent, SettingsFragment().apply { arguments = Bundle().apply { putInt("section", section) } }).commit()
        val night = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }
    }
    companion object {
        val titles = listOf("权限与连接", "功能与应用范围", "后台运行", "历史与规则备份", "Bark 转发", "个人学习数据", "关于")
        fun open(context: Context, section: Int) = context.startActivity(Intent(context, SettingsDetailActivity::class.java).putExtra("section", section))
    }
}
