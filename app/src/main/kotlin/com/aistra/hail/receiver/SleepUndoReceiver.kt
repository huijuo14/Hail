package com.aistra.hail.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.NotificationManager
import com.aistra.hail.app.AppManager
import com.aistra.hail.data.AutoSleepData
import org.json.JSONArray

/**
 * Receives Undo action from the Auto Deep Sleep notification.
 * Unfreezes all apps that were just frozen in the last batch.
 */
class SleepUndoReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val packagesJson = intent.getStringExtra(EXTRA_FROZEN_PACKAGES) ?: return
        val packages = mutableListOf<String>().apply {
            val arr = JSONArray(packagesJson)
            for (i in 0 until arr.length()) add(arr.getString(i))
        }
        if (packages.isEmpty()) return

        // Unfreeze each app and remove from auto-sleep tracking
        for (pkg in packages) {
            val success = AppManager.setAppFrozen(pkg, false)
            if (success) {
                AutoSleepData.removeAutoSlept(pkg)
            }
        }

        // Cancel the notification
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(1001)

        // Show a brief confirmation notification
        val channelId = "auto_deep_sleep_undo"
        val undoChannel = android.app.NotificationChannel(
            channelId,
            "Auto Deep Sleep Undo",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Undo confirmation" }
        nm.createNotificationChannel(undoChannel)

        val count = packages.size
        androidx.core.app.NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_revert)
            .setContentTitle("Undo successful")
            .setContentText("$count app${if (count > 1) "s" else ""} restored")
            .setAutoCancel(true)
            .build().let { nm.notify(1002, it) }
    }

    companion object {
        const val ACTION_SLEEP_UNDO = "com.aistra.hail.action.SLEEP_UNDO"
        const val EXTRA_FROZEN_PACKAGES = "frozen_packages"
    }
}
