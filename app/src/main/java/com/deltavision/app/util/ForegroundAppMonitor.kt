package com.deltavision.app.util

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import android.util.Log

object ForegroundAppMonitor {
    private const val TAG = "ForegroundAppMonitor"
    private const val RECENT_WINDOW_MS = 10_000L
    private const val ROOT_QUERY_CACHE_MS = 350L
    private const val TRANSIENT_PACKAGE_HOLD_MS = 1_500L
    private const val USAGE_FALLBACK_THRESHOLD_MS = 2_000L
    private val transientIgnoredPackagePrefixes = listOf(
        "com.android.systemui",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.coloros.",
        "com.heytap.",
        "com.oplus.",
    )
    private val packageNameRegex = Regex("""([A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+)/""")

    @Volatile private var lastQueryTimestampMs: Long = 0L
    @Volatile private var lastRawPackage: String? = null
    @Volatile private var lastEffectivePackage: String? = null
    @Volatile private var lastMeaningfulPackage: String? = null
    @Volatile private var lastMeaningfulTimestampMs: Long = 0L
    @Volatile private var lastLoggedSnapshot: String? = null

    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun isTargetForeground(context: Context, targetPackage: String): Boolean {
        if (targetPackage.isBlank()) return false
        val endTime = System.currentTimeMillis()
        val snapshot = resolveSnapshot(context, endTime)
        if (matchesTargetPackage(snapshot.effectivePackage, targetPackage)) return true
        if (!snapshot.rawPackage.isNullOrBlank() && !isTransientIgnoredPackage(context, snapshot.rawPackage)) {
            return false
        }
        if (!hasUsageAccess(context)) return false
        return wasTargetUsedRecently(context, targetPackage, endTime)
    }

    fun currentForegroundPackage(context: Context): String? {
        return resolveSnapshot(context, System.currentTimeMillis()).effectivePackage
    }

    fun describeForeground(context: Context): String {
        val snapshot = resolveSnapshot(context, System.currentTimeMillis())
        val current = snapshot.effectivePackage ?: "unknown"
        val raw = snapshot.rawPackage ?: "unknown"
        return "current=$current raw=$raw"
    }

    private fun resolveSnapshot(context: Context, endTime: Long): ForegroundSnapshot {
        synchronized(this) {
            if (endTime - lastQueryTimestampMs <= ROOT_QUERY_CACHE_MS) {
                return ForegroundSnapshot(lastRawPackage, lastEffectivePackage)
            }

            val rootPackage = queryRootForegroundPackage()
            val effectiveRootPackage = normalizePackage(context, rootPackage, endTime)
            if (!effectiveRootPackage.isNullOrBlank()) {
                val snapshot = ForegroundSnapshot(rootPackage, effectiveRootPackage)
                cacheSnapshot(snapshot, endTime)
                return snapshot
            }

            val usagePackage = if (hasUsageAccess(context)) queryUsageForegroundPackage(context, endTime) else null
            val effectiveUsagePackage = normalizePackage(context, usagePackage, endTime)
            val snapshot = ForegroundSnapshot(usagePackage, effectiveUsagePackage ?: recentMeaningfulPackage(endTime))
            cacheSnapshot(snapshot, endTime)
            return snapshot
        }
    }

    private fun cacheSnapshot(snapshot: ForegroundSnapshot, timestampMs: Long) {
        lastQueryTimestampMs = timestampMs
        lastRawPackage = snapshot.rawPackage
        lastEffectivePackage = snapshot.effectivePackage
        val debugLine = "raw=${snapshot.rawPackage ?: "null"} effective=${snapshot.effectivePackage ?: "null"}"
        if (debugLine != lastLoggedSnapshot) {
            lastLoggedSnapshot = debugLine
            Log.d(TAG, debugLine)
        }
    }

    private fun normalizePackage(context: Context, packageName: String?, now: Long): String? {
        if (packageName.isNullOrBlank()) {
            return recentMeaningfulPackage(now)
        }
        if (!isTransientIgnoredPackage(context, packageName)) {
            lastMeaningfulPackage = packageName
            lastMeaningfulTimestampMs = now
            return packageName
        }
        return recentMeaningfulPackage(now)
    }

    private fun recentMeaningfulPackage(now: Long): String? {
        if (now - lastMeaningfulTimestampMs > TRANSIENT_PACKAGE_HOLD_MS) return null
        return lastMeaningfulPackage
    }

    private fun queryRootForegroundPackage(): String? {
        val windowDump = RootShell.execForText("dumpsys window")
        parseForegroundPackage(windowDump)?.let { return it }
        val activityDump = RootShell.execForText("dumpsys activity activities")
        parseForegroundPackage(activityDump)?.let { return it }
        return null
    }

    private fun parseForegroundPackage(dump: String?): String? {
        if (dump.isNullOrBlank()) return null
        dump.lineSequence().forEach { line ->
            if (
                line.contains("mCurrentFocus=") ||
                line.contains("mFocusedApp=") ||
                line.contains("topResumedActivity=") ||
                line.contains("ResumedActivity:")
            ) {
                extractPackageName(line)?.let { return it }
            }
        }
        return null
    }

    private fun extractPackageName(line: String): String? {
        return packageNameRegex.find(line)?.groupValues?.getOrNull(1)
    }

    private fun queryUsageForegroundPackage(context: Context, endTime: Long): String? {
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = manager.queryEvents(endTime - RECENT_WINDOW_MS, endTime)
        val event = UsageEvents.Event()
        var lastPackage: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (!isForegroundEvent(event.eventType)) continue
            val packageName = event.packageName ?: continue
            if (isTransientIgnoredPackage(context, packageName)) continue
            lastPackage = packageName
        }
        return lastPackage
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
        return endTime - targetStats.lastTimeUsed <= USAGE_FALLBACK_THRESHOLD_MS
    }

    private fun matchesTargetPackage(candidatePackage: String?, targetPackage: String): Boolean {
        if (candidatePackage.isNullOrBlank()) return false
        return candidatePackage == targetPackage || candidatePackage.startsWith("$targetPackage.")
    }

    private fun isForegroundEvent(eventType: Int): Boolean {
        return eventType == UsageEvents.Event.ACTIVITY_RESUMED || eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
    }

    private fun isTransientIgnoredPackage(context: Context, packageName: String): Boolean {
        if (packageName == context.packageName) return true
        return transientIgnoredPackagePrefixes.any(packageName::startsWith)
    }

    private data class ForegroundSnapshot(
        val rawPackage: String?,
        val effectivePackage: String?,
    )
}
