package com.drivetest.lte5g.updater

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Fires in the newly installed app process after self-update completes: cleans up
 * downloaded update APKs and relaunches MainActivity when an OTA install was pending.
 */
class OtaPackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            OtaUpdateManager.handlePackageReplaced(context)
        }
    }
}
