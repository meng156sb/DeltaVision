package com.deltavision.app.util

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process

object ForegroundAppMonitor {
    private const val RECENT_WINDOW_MS = 10_000L
    private const val ACTIVE_THRESHOLD_MS = 5_000L
    private val ignoredPackagePrefixes = listOf(
        "com.android.systemui",
        "com.android.launcher",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.coloros.",
        "com.heytap.",
        "com.oplus.",
    )

    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun isTargetForeground(context: Context, targetPackage: String): Boolean {
        if (targetPackage.isBlank() || !hasUsageAccess(context)) return false
        val endTime = System.currentTimeMillis()
        val currentPackage = currentForegroundPackage(context, endTime)
        if (matchesTargetPackage(currentPackage, targetPackage)) return true
        return wasTargetUsedRecently(context, targetPackage, endTime)
    }

    fun currentForegroundPackage(context: Context): String? {
        if (!hasUsageAccess(context)) return null
        return currentForegroundPackage(context, System.currentTimeMillis())
    }

    private fun currentForegroundPackage(context: Context, endTime: Long): String? {
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = manager.queryEvents(endTime - RECENT_WINDOW_MS, endTime)
        val event = UsageEvents.Event()
        var lastMeaningfulPackage: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (!isForegroundEvent(event.eventType)) continue
            val packageName = event.packageName ?: continue
            if (isIgnoredPackage(context, packageName)) continue
            lastMeaningfulPackage = packageName
        }
        return lastMeaningfulPackage
    }

    private fun wasTargetUsedRecently(context: Context, targetPackage: String, endTime: Long): Boolean {
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val stats = manager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            endTime - RECENT_WINDOW_MS,
            endTime,
        )
        val targetStats = stats
            .filter { matchesTargetPackage(it.packageName, targetPackage) }
            .maxByOrNull { it.lastTimeUsed }
            ?: return false
        return endTime - targetStats.lastTimeUsed <= ACTIVE_THRESHOLD_MS
    }

    private fun matchesTargetPackage(candidatePackage: String?, targetPackage: String): Boolean {
        if (candidatePackage.isNullOrBlank()) return false
        return candidatePackage == targetPackage || candidatePackage.startsWith("$targetPackage.")
    }

    private fun isForegroundEvent(eventType: Int): Boolean {
        return eventType == UsageEvents.Event.ACTIVITY_RESUMED || eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
    }

    private fun isIgnoredPackage(context: Context, packageName: String): Boolean {
        if (packageName == context.packageName) return true
        return ignoredPackagePrefixes.any(packageName::startsWith)
    }
}
