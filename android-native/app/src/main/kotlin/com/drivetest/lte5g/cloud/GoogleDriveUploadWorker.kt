package com.drivetest.lte5g.cloud

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.drivetest.lte5g.data.SessionRepository
import java.util.concurrent.TimeUnit

/**
 * Background WorkManager worker for uploading drive test session bundles
 * (KMZ, CSV, GeoJSON, MapInfo ZIP, PDF) to Google Drive exclusively over Wi-Fi (UNMETERED).
 */
class GoogleDriveUploadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val sessionId = inputData.getLong(KEY_SESSION_ID, -1L)
        if (sessionId <= 0L) {
            Log.e(TAG, "Invalid session ID passed to GoogleDriveUploadWorker: $sessionId")
            return Result.failure()
        }

        // Avoid duplicate upload if already marked UPLOADED
        val currentStatus = CloudConfigRepository.getSessionUploadStatus(sessionId)
        if (currentStatus == CloudUploadStatus.UPLOADED) {
            Log.i(TAG, "Session #$sessionId is already uploaded to Google Drive. Skipping.")
            return Result.success()
        }

        val config = CloudConfigRepository.config.value
        if (config.cloudUploadUrl.isBlank()) {
            Log.w(TAG, "No cloud upload endpoint configured (CLOUD_UPLOAD_URL). Skipping upload.")
            return Result.failure()
        }

        val session = SessionRepository.sessions.value.firstOrNull { it.id == sessionId }
        if (session == null || session.samples.isEmpty()) {
            Log.w(TAG, "Session #$sessionId not found in repository or has no samples.")
            return Result.failure()
        }

        Log.i(TAG, "Starting Google Drive upload for Session #${session.sessionNumber} (${session.samples.size} samples)")
        CloudConfigRepository.setSessionUploadStatus(sessionId, CloudUploadStatus.UPLOADING)

        val uploadResult = GoogleDriveAutoUploader.exportAndUploadSessionBundle(
            context = applicationContext,
            session = session,
            config = config
        )

        return if (uploadResult.isSuccess) {
            Log.i(TAG, "Successfully uploaded 5-format bundle for Session #${session.sessionNumber}")
            CloudConfigRepository.setSessionUploadStatus(sessionId, CloudUploadStatus.UPLOADED)
            Result.success()
        } else {
            val err = uploadResult.exceptionOrNull()?.message ?: "Unknown error"
            Log.e(TAG, "Upload failed for Session #${session.sessionNumber}: $err (attempt $runAttemptCount)")

            if (runAttemptCount < MAX_RETRIES) {
                CloudConfigRepository.setSessionUploadStatus(sessionId, CloudUploadStatus.PENDING_WIFI)
                Result.retry()
            } else {
                CloudConfigRepository.setSessionUploadStatus(sessionId, CloudUploadStatus.FAILED)
                Result.failure()
            }
        }
    }

    companion object {
        private const val TAG = "GDriveUploadWorker"
        const val KEY_SESSION_ID = "drive_session_id"
        private const val MAX_RETRIES = 3

        /**
         * Enqueue upload task with NetworkType.UNMETERED constraint and exponential backoff retry.
         * Uses ExistingWorkPolicy.KEEP to guarantee zero duplicate uploads.
         */
        fun enqueueUpload(context: Context, sessionId: Long) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .build()

            val uploadWorkRequest = OneTimeWorkRequestBuilder<GoogleDriveUploadWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .setInputData(workDataOf(KEY_SESSION_ID to sessionId))
                .addTag("upload_session_$sessionId")
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "upload_session_$sessionId",
                ExistingWorkPolicy.KEEP,
                uploadWorkRequest
            )
            Log.d(TAG, "Enqueued WorkManager upload for session #$sessionId on UNMETERED network")
        }
    }
}
