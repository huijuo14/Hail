package com.aistra.hail.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.app.AppManager
import com.aistra.hail.app.HailData
import com.aistra.hail.data.AutoSleepData
import com.aistra.hail.utils.HPackages
import com.aistra.hail.utils.HSystem
import java.util.concurrent.TimeUnit

/**
 * Auto Deep Sleep Worker — periodically checks which apps haven't been used
 * in N days and automatically freezes them (like Samsung's Auto Deep Sleep).
 */
class AutoSleepWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        if (!HailData.autoSleepEnabled) return Result.success()

        val thresholdDays = HailData.autoSleepThresholdDays
        val scope = HailData.autoSleepScope
        val excludeSystem = HailData.autoSleepExcludeSystem
        val graceHours = HailData.autoSleepGraceHours
        val graceMs = graceHours * 60L * 60 * 1000

        val now = System.currentTimeMillis()
        val thresholdMs = now - (thresholdDays * 24L * 60 * 60 * 1000)

        val usageStatsManager =
            applicationContext.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        if (usageStatsManager == null || !HSystem.checkOpUsageStats(applicationContext)) {
            return Result.retry()
        }

        // Query usage stats over the full threshold period for reliable data
        val usageStats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_BEST,
            thresholdMs,
            now
        )

        val recentlyUsed = mutableSetOf<String>()
        usageStats?.forEach { stat ->
            val lastTime = maxOf(stat.lastTimeUsed, stat.lastTimeVisible)
            if (lastTime >= thresholdMs) {
                recentlyUsed.add(stat.packageName)
            }
        }

        val foregroundPackage = getForegroundPackage()

        // Track recently auto-unfrozen apps (grace period: don't re-freeze)
        val graceExpired = AutoSleepData.autoSleptApps.filter {
            (now - it.sleptAt) >= graceMs
        }.map { it.packageName }.toSet()

        val installedApps = HPackages.getInstalledApplications()
        val newlyFrozen = mutableListOf<String>()

        for (appInfo in installedApps) {
            val pkg = appInfo.packageName

            if (pkg == applicationContext.packageName) continue
            if (recentlyUsed.contains(pkg)) continue
            if (pkg == foregroundPackage) continue
            if (AppManager.isAppFrozen(pkg)) continue
            if (excludeSystem && isSystemApp(appInfo)) continue

            // Check whitelist
            val checkedApp = HailData.checkedList.find { it.packageName == pkg }
            if (checkedApp?.whitelisted == true) continue

            // Check if this app was recently unfrozen by user launch (grace period)
            if (AutoSleepData.isAutoSlept(pkg) && !graceExpired.contains(pkg)) continue

            if (scope == "checked" && HailData.checkedList.none { it.packageName == pkg }) continue

            val success = AppManager.setAppFrozen(pkg, true)
            if (success) {
                if (HailData.checkedList.none { it.packageName == pkg }) {
                    HailData.addCheckedApp(pkg, saveApps = true)
                }
                AutoSleepData.addAutoSlept(pkg)
                newlyFrozen.add(pkg)
            }
        }

        // Show notification if any apps were frozen
        if (newlyFrozen.isNotEmpty()) {
            showAutoSleepNotification(newlyFrozen)
        }

        // Run usage analysis periodically (every 7 days)
        val lastAnalysis = HailData.autoSleepLastAnalysis
        if (lastAnalysis == 0L || (now - lastAnalysis) >= 7 * 24 * 60 * 60 * 1000L) {
            analyzeUsagePatterns(usageStatsManager, now)
            HailData.autoSleepLastAnalysis = now
        }

        return Result.success()
    }

    private fun showAutoSleepNotification(frozenPackages: List<String>) {
        val pm = applicationContext.packageManager
        val names = frozenPackages.mapNotNull { pkg ->
            try {
                val ai = pm.getApplicationInfo(pkg, 0)
                ai.loadLabel(pm).toString()
            } catch (_: Exception) { null }
        }

        val channelId = "auto_deep_sleep"
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(channelId, "Auto Deep Sleep", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows which apps were auto-frozen"
            }
        )

        val title = "Auto Deep Sleep: ${frozenPackages.size} app${if (frozenPackages.size > 1) "s" else ""} frozen"
        val body = if (names.size <= 3) names.joinToString(", ") else
            "${names.take(3).joinToString(", ")} and ${names.size - 3} more"

        NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_sleep)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .build().also { nm.notify(1001, it) }
    }

    private fun analyzeUsagePatterns(usageStatsManager: UsageStatsManager, now: Long) {
        // Analyze 30 days of usage data
        val thirtyDaysMs = 30L * 24 * 60 * 60 * 1000
        val stats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_BEST,
            now - thirtyDaysMs,
            now
        ) ?: return

        // Count total apps vs rarely used apps
        val installedApps = HPackages.getInstalledApplications()
        val totalUserApps = installedApps.count {
            it.packageName != applicationContext.packageName && !isSystemApp(it)
        }

        val allPackagesWithUsage = stats.associate { stat ->
            stat.packageName to maxOf(stat.totalTimeInForeground, 0L)
        }

        val rarelyUsed = installedApps.count {
            it.packageName != applicationContext.packageName &&
            !isSystemApp(it) &&
            (allPackagesWithUsage[it.packageName] ?: 0L) < 60_000 // less than 1 minute in 30 days = rarely used
        }

        // Store analysis result
        _lastAnalysisResult = AnalysisResult(
            totalUserApps = totalUserApps,
            rarelyUsedCount = rarelyUsed,
            analysisTime = now,
            suggestedThreshold = calculateSuggestedThreshold(usageStatsManager, now)
        )
    }

    private fun calculateSuggestedThreshold(
        usageStatsManager: UsageStatsManager, now: Long
    ): Int {
        // Look at intervals and find when most apps go unused
        val intervals = listOf(1, 3, 7, 14, 30)
        val installedApps = HPackages.getInstalledApplications()
        val hailPkg = applicationContext.packageName

        val bestInterval = intervals.maxByOrNull { days ->
            val ms = days * 24L * 60 * 60 * 1000
            val stats = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_BEST, now - ms, now
            ) ?: return@maxByOrNull 0

            val used = stats.mapNotNull { stat ->
                val lastTime = maxOf(stat.lastTimeUsed, stat.lastTimeVisible)
                if ((now - lastTime) <= ms) stat.packageName else null
            }.toSet()

            val unusedCount = installedApps.count {
                it.packageName != hailPkg && !isSystemApp(it) && !used.contains(it.packageName)
            }
            unusedCount
        }
        return bestInterval ?: 7
    }

    private fun getForegroundPackage(): String? {
        return runCatching {
            val usageStatsManager =
                applicationContext.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            if (usageStatsManager == null) return@runCatching null
            val now = System.currentTimeMillis()
            val stats = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_BEST,
                now - 60_000,
                now
            )
            stats?.maxByOrNull { it.lastTimeUsed }?.packageName
        }.getOrNull()
    }

    private fun isSystemApp(appInfo: android.content.pm.ApplicationInfo): Boolean {
        return (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
    }

    companion object {
        private const val WORK_NAME = "auto_sleep"
        const val ONE_SHOT_WORK_NAME = "auto_sleep_now"
        const val KEY_INTERVAL_HOURS = "interval_hours"

        /** Stores the last usage analysis result for display in settings */
        var _lastAnalysisResult: AnalysisResult? = null

        fun getAnalysisResult(): AnalysisResult? = _lastAnalysisResult

        data class AnalysisResult(
            val totalUserApps: Int,
            val rarelyUsedCount: Int,
            val analysisTime: Long,
            val suggestedThreshold: Int
        )

        fun runOnce(context: Context) {
            val request = OneTimeWorkRequestBuilder<AutoSleepWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONE_SHOT_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        fun schedule(context: Context) {
            val workManager = WorkManager.getInstance(context)
            if (!HailData.autoSleepEnabled) {
                workManager.cancelUniqueWork(WORK_NAME)
                return
            }
            val intervalHours = HailData.autoSleepIntervalHours
            val request = PeriodicWorkRequestBuilder<AutoSleepWorker>(
                intervalHours.toLong(), TimeUnit.HOURS
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()
            workManager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }
}
