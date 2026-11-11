package com.tenderxiamu.eyecaretv

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process

/**
 * 应用白名单相关能力：
 * 取前台应用需要「使用情况访问」权限，用户未授权时一律视为不在白名单内。
 */
object AppWhitelist {

    data class AppEntry(val packageName: String, val label: String)

    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** 最近一次回到前台的应用包名，取不到时返回 null。 */
    fun foregroundPackage(context: Context): String? {
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return null
        val end = System.currentTimeMillis()
        val events = runCatching { manager.queryEvents(end - 60_000L, end) }.getOrNull()
            ?: return null
        val eventType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            UsageEvents.Event.ACTIVITY_RESUMED
        } else {
            UsageEvents.Event.MOVE_TO_FOREGROUND
        }
        val event = UsageEvents.Event()
        var result: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == eventType) result = event.packageName
        }
        return result
    }

    /** 桌面可见的应用列表，按名称排序。 */
    fun launchers(context: Context): List<AppEntry> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val pm = context.packageManager
        return pm.queryIntentActivities(intent, 0)
            .mapNotNull { info ->
                val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
                AppEntry(packageName, info.loadLabel(pm).toString())
            }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }
}
