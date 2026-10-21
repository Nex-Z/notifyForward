package com.notifyforward.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.notifyforward.app.R
import com.notifyforward.app.databinding.ActivityMainBinding
import com.notifyforward.app.ui.history.HistoryFragment
import com.notifyforward.app.ui.rules.RulesFragment
import com.notifyforward.app.ui.settings.SettingsFragment
import com.notifyforward.app.ui.stats.StatsFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var historyFragment: HistoryFragment
    private lateinit var rulesFragment: RulesFragment
    private lateinit var statsFragment: StatsFragment
    private lateinit var settingsFragment: SettingsFragment

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState == null) {
            historyFragment = HistoryFragment()
            rulesFragment = RulesFragment()
            statsFragment = StatsFragment()
            settingsFragment = SettingsFragment()
            supportFragmentManager.beginTransaction()
                .add(R.id.container, historyFragment, TAG_HISTORY)
                .add(R.id.container, rulesFragment, TAG_RULES)
                .add(R.id.container, statsFragment, TAG_STATS)
                .add(R.id.container, settingsFragment, TAG_SETTINGS)
                .hide(rulesFragment).hide(statsFragment).hide(settingsFragment)
                .commit()
        } else {
            historyFragment = supportFragmentManager.findFragmentByTag(TAG_HISTORY) as? HistoryFragment
                ?: HistoryFragment()
            rulesFragment = supportFragmentManager.findFragmentByTag(TAG_RULES) as? RulesFragment
                ?: RulesFragment()
            statsFragment = supportFragmentManager.findFragmentByTag(TAG_STATS) as? StatsFragment
                ?: StatsFragment()
            settingsFragment = supportFragmentManager.findFragmentByTag(TAG_SETTINGS) as? SettingsFragment
                ?: SettingsFragment()
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_history -> show(historyFragment)
                R.id.nav_rules -> show(rulesFragment)
                R.id.nav_stats -> show(statsFragment)
                R.id.nav_settings -> show(settingsFragment)
                else -> return@setOnItemSelectedListener false
            }
            true
        }
        binding.bottomNav.selectedItemId = R.id.nav_history
        // iOS 风格：选中项只改变图标/文字颜色，不显示 M3 的胶囊指示块
        runCatching {
            binding.bottomNav.itemActiveIndicatorColor =
                android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
        }

        binding.btnGrant.setOnClickListener {
            runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        }

        applySystemBars()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun show(fragment: Fragment) {
        if (!fragment.isAdded) return
        supportFragmentManager.beginTransaction()
            .hide(historyFragment).hide(rulesFragment)
            .hide(statsFragment).hide(settingsFragment)
            .show(fragment)
            .commitAllowingStateLoss()
        updatePermissionCard()
        (fragment as? Refreshable)?.refresh()
    }

    /** 状态栏/导航栏图标跟随深浅色模式，页面本身始终使用统一底色。 */
    private fun applySystemBars() {
        val night = resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }
    }

    private fun updatePermissionCard() {
        binding.permissionCard.visibility = if (isListenerEnabled()) View.GONE else View.VISIBLE
    }

    fun isListenerEnabled(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    override fun onResume() {
        super.onResume()
        updatePermissionCard()
        supportFragmentManager.fragments.firstOrNull { !it.isHidden }?.let {
            if (it is Refreshable) it.refresh()
        }
    }

    private companion object {
        const val TAG_HISTORY = "history"
        const val TAG_RULES = "rules"
        const val TAG_STATS = "stats"
        const val TAG_SETTINGS = "settings"
    }
}

interface Refreshable {
    fun refresh()
}
