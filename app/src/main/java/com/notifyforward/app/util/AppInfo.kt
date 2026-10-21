package com.notifyforward.app.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.Settings
import android.util.LruCache
import androidx.core.content.ContextCompat
import com.notifyforward.app.R
import java.text.Collator
import java.util.Locale
import kotlin.math.abs

/** 应用名/图标解析与启动。 */
object AppInfo {

    data class Entry(val packageName: String, val label: String, val icon: Drawable?)

    private val labelCache = HashMap<String, String>()
    private val iconCache = LruCache<String, Drawable>(120)
    private val uidCache = HashMap<String, Int>()

    /** 当前用户下该包名主应用的 uid；查询失败返回 -1。 */
    fun primaryUid(context: Context, pkg: String): Int {
        uidCache[pkg]?.let { return it }
        val uid = try {
            context.packageManager.getApplicationInfo(pkg, 0).uid
        } catch (_: Exception) {
            -1
        }
        // -1 多为瞬时失败（包刚安装/被卸载），不缓存，下次重新查
        if (uid > 0) uidCache[pkg] = uid
        return uid
    }

    /**
     * 同包名但 uid 不同，说明通知来自分身/克隆实例。
     * 系统分身通常跑在独立 uid（或多用户空间），这个判断是识别它的唯一线索。
     */
    fun isClone(context: Context, pkg: String, uid: Int): Boolean {
        if (uid <= 0) return false
        val primary = primaryUid(context, pkg)
        return primary > 0 && primary != uid
    }

    fun forget(pkg: String) {
        labelCache.remove(pkg)
        iconCache.remove(pkg)
        uidCache.remove(pkg)
    }

    fun label(context: Context, pkg: String): String {
        labelCache[pkg]?.let { return it }
        val name = try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) {
            pkg
        }
        labelCache[pkg] = name
        return name
    }

    fun icon(context: Context, pkg: String): Drawable? {
        iconCache.get(pkg)?.let { return it }
        val d = try {
            context.packageManager.getApplicationIcon(pkg)
        } catch (_: Exception) {
            null
        }
        if (d != null) iconCache.put(pkg, d)
        return d
    }

    fun openApp(context: Context, pkg: String) {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
        } else {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    /** 可启动的应用列表（含自己）。 */
    fun launchableApps(context: Context): List<Entry> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = pm.queryIntentActivities(intent, 0)
        val seen = HashSet<String>()
        val list = ArrayList<Entry>()
        for (ri in resolved) {
            val pkg = ri.activityInfo.packageName
            if (!seen.add(pkg)) continue
            list.add(Entry(pkg, label(context, pkg), icon(context, pkg)))
        }
        list.add(Entry(context.packageName, label(context, context.packageName), icon(context, context.packageName)))
        val collator = Collator.getInstance(Locale.CHINA)
        list.sortWith(compareBy(collator) { it.label })
        return list
    }

    fun hasLaunchIntent(context: Context, pkg: String): Boolean =
        try {
            context.packageManager.getLaunchIntentForPackage(pkg) != null
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

    /** 未安装/取不到图标时，用首字母色块占位。 */
    fun initial(label: String): String {
        val trimmed = label.trim()
        if (trimmed.isEmpty()) return "?"
        val first = trimmed.codePointAt(0)
        return String(Character.toChars(first)).uppercase()
    }

    fun tileColor(context: Context, key: String): Int {
        val palette = intArrayOf(
            R.color.brand_primary,
            R.color.accent,
            R.color.warning,
            R.color.purple,
            R.color.danger,
            R.color.teal
        )
        val index = abs(key.hashCode()) % palette.size
        return ContextCompat.getColor(context, palette[index])
    }
}
