package com.drivetest.lte5g.updater

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Manifest-registered receiver for PackageInstaller session status callbacks so
 * STATUS_PENDING_USER_ACTION and final install results are never missed if the Activity
 * is backgrounded or recreated during confirmation.
 */
class OtaInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == OtaUpdateManager.ACTION_INSTALL_STATUS) {
            OtaUpdateManager.handleInstallCallback(context, intent)
        }
    }
}
