package com.drivetest.lte5g

import android.app.Application
import com.drivetest.lte5g.cloud.CloudConfigRepository
import com.drivetest.lte5g.cloud.CloudTelemetrySyncManager
import com.drivetest.lte5g.cloud.GoogleDriveAutoUploader
import com.drivetest.lte5g.data.SessionRepository

/**
 * Initializes persistent session storage, crash recovery, and cloud managers once per process
 * before any Activity, ForegroundService, or WorkManager worker reads repositories.
 */
class DriveTestApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SessionRepository.initStorage(applicationContext)
        CloudConfigRepository.init(applicationContext)
        CloudTelemetrySyncManager.init(applicationContext)
        GoogleDriveAutoUploader.init(applicationContext)
    }
}
