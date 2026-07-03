package com.aistra.hail.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.data.AutoSleepData
import com.aistra.hail.utils.HShizuku.setAppRestricted
import com.aistra.hail.utils.HTarget

class UnsuspendedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_PACKAGE_UNSUSPENDED_MANUALLY) runCatching {
            val packageName = intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME)!!
            if (HTarget.P) setAppRestricted(packageName, false)
            // Remove from auto-sleep tracking since user manually unsuspended it
            AutoSleepData.removeAutoSlept(packageName)
            app.setAutoFreezeService()
        }
    }

    companion object {
        private const val ACTION_PACKAGE_UNSUSPENDED_MANUALLY =
            "android.intent.action.PACKAGE_UNSUSPENDED_MANUALLY"
    }
}