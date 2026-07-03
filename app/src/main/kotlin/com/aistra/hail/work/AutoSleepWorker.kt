package com.aistra.hail.work

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
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
        // Check if feature is enabled
        if (!HailData.autoSleepEnabled) return Result.success()

        val thresholdDays = HailData.autoSleepThresholdDays
        val scope = HailData.autoSleepScope // "all" or "checked"
        val excludeSystem = HailData.autoSleepExcludeSystem

        val now = System.currentTimeMillis()
        val thresholdMs = now - (thresholdDays * 24L * 60 * 60 * 1000)

        // Get usage stats
        val usageStatsManager = applicationContext.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        if (usageStatsManager == null || !HSystem.checkOpUsageStats(applicationContext)) {
            // Can't read usage stats — schedule retry
            return Result.retry()
        }

        // Query usage for the threshold period
        val usageStats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_BEST,
            thresholdMs,
            now
        )

        // Build set of recently used packages
        val recentlyUsed = mutableSetOf<String>()
        usageStats?.forEach { stat ->
            val lastTime = maxOf(stat.lastTimeUsed, stat.lastTimeVisible)
            if (lastTime >= thresholdMs) {
                recentlyUsed.add(stat.packageName)
            }
        }

        // Get the current foreground app to avoid freezing it
        val foregroundPackage = getForegroundPackage()

        // Determine candidates to freeze
        val installedApps = HPackages.getInstalledApplications()
        var frozenCount = 0

        for (appInfo in installedApps) {
            val pkg = appInfo.packageName

            // Skip Hail itself
            if (pkg == applicationContext.packageName) continue

            // Skip if recently used
            if (recentlyUsed.contains(pkg)) continue

            // Skip if currently in foreground
            if (pkg == foregroundPackage) continue

            // Skip if already frozen
            if (AppManager.isAppFrozen(pkg)) continue

            // Skip system apps if configured
            if (excludeSystem && isSystemApp(appInfo)) continue

            // Skip whitelisted apps
            val checkedApp = HailData.checkedList.find { it.packageName == pkg }
            if (checkedApp?.whitelisted == true) continue

            // Scope: only freeze checked apps
            if (scope == "checked" && HailData.checkedList.none { it.packageName == pkg }) continue

            // Freeze the app
            val success = AppManager.setAppFrozen(pkg, true)
            if (success) {
                // If app wasn't already on the checked list, add it
                if (HailData.checkedList.none { it.packageName == pkg }) {
                    HailData.addCheckedApp(pkg, saveApps = true)
                }
                // Mark as auto-slept
                AutoSleepData.addAutoSlept(pkg)
                frozenCount++
            }
        }

        // Schedule next run
        scheduleNextRun(applicationContext)

        return Result.success()
    }

    private fun getForegroundPackage(): String? {
        return runCatching {
            val usageStatsManager = applicationContext.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            if (usageStatsManager == null) return@runCatching null
            val now = System.currentTimeMillis()
            val stats = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_BEST,
                now - 60_000, // last minute
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
        const val KEY_INTERVAL_HOURS = "interval_hours"

        /** Schedule or cancel the periodic Auto Sleep check */
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
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    30, TimeUnit.MINUTES
                )
                .build()

            workManager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }
}
