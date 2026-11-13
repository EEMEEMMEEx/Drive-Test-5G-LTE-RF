package com.drivetest.lte5g.updater

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** What the app should do with the update Play Core reports (pure, unit-testable). */
enum class PlayUpdateAction {
    START_IMMEDIATE,
    START_FLEXIBLE,
    COMPLETE_PENDING,
    NOT_FROM_PLAY,
    UP_TO_DATE
}

/** Normalised availability, so the decision logic does not depend on Play Core constants. */
enum class RemoteUpdateStatus {
    AVAILABLE,
    IN_PROGRESS,
    NOT_AVAILABLE
}

sealed interface PlayUpdateState {
    object Idle : PlayUpdateState
    object Checking : PlayUpdateState
    data class Available(
        val versionCode: Int,
        val updateInProgress: Boolean
    ) : PlayUpdateState

    data class Downloading(val progressPercent: Int) : PlayUpdateState
    object Downloaded : PlayUpdateState
    data class Unavailable(val reason: String) : PlayUpdateState
    object UpToDate : PlayUpdateState
}

/**
 * Google Play In-App Updates. This is the only install path of the app: no APK is downloaded
 * or installed by the app itself, which keeps Play Protect from blocking the update.
 *
 * If the app was installed by sideloading an APK, Play Core cannot update it; callers must then
 * send the user to the Play listing (see [playStoreUrl] / [openPlayStore]).
 */
object PlayUpdateManager {
    private const val TAG = "PlayUpdateManager"

    /** Request code for startUpdateFlowForResult; the result is observed via the install listener. */
    const val REQUEST_CODE = 4201

    /** Installer package name of the Play Store, the only source Play can update from. */
    const val PLAY_STORE_INSTALLER = "com.android.vending"

    /** Shown when this install did not come from Play and therefore cannot be updated by Play. */
    const val NOT_FROM_PLAY_REASON =
        "แอปนี้ไม่ได้ติดตั้งจาก Google Play จึงอัปเดตผ่าน Play ไม่ได้ ให้ติดตั้งจาก Play ก่อนหนึ่งครั้ง"

    private val _state = MutableStateFlow<PlayUpdateState>(PlayUpdateState.Idle)
    val state: StateFlow<PlayUpdateState> = _state.asStateFlow()

    private var installListener: InstallStateUpdatedListener? = null

    fun resetState() {
        _state.value = PlayUpdateState.Idle
    }

    /** Play listing of this build's package (single source: the installed applicationId). */
    fun playStoreUrl(context: Context): String = playStoreUrl(context.packageName)

    /** Same URL from a bare package name (unit-testable). */
    fun playStoreUrl(packageName: String): String =
        "https://play.google.com/store/apps/details?id=$packageName"

    /** Opens the Play listing in a browser. Returns false when no browser accepted the intent. */
    fun openPlayStore(context: Context): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(playStoreUrl(context))).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open Play listing", e)
            false
        }
    }

    fun statusOf(updateAvailability: Int): RemoteUpdateStatus = when (updateAvailability) {
        UpdateAvailability.UPDATE_AVAILABLE -> RemoteUpdateStatus.AVAILABLE
        UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS -> RemoteUpdateStatus.IN_PROGRESS
        else -> RemoteUpdateStatus.NOT_AVAILABLE
    }

    fun decide(
        status: RemoteUpdateStatus,
        immediateAllowed: Boolean,
        flexibleAllowed: Boolean,
        installedFromPlay: Boolean = true
    ): PlayUpdateAction = when {
        // An update flow already running must be resumed, whatever the install source
        status == RemoteUpdateStatus.IN_PROGRESS -> PlayUpdateAction.COMPLETE_PENDING
        // Sideloaded installs cannot be updated by Play, even when it reports "up to date"
        !installedFromPlay -> PlayUpdateAction.NOT_FROM_PLAY
        status == RemoteUpdateStatus.NOT_AVAILABLE -> PlayUpdateAction.UP_TO_DATE
        immediateAllowed -> PlayUpdateAction.START_IMMEDIATE
        flexibleAllowed -> PlayUpdateAction.START_FLEXIBLE
        else -> PlayUpdateAction.NOT_FROM_PLAY
    }

    fun actionFor(info: AppUpdateInfo, installedFromPlay: Boolean): PlayUpdateAction = decide(
        status = statusOf(info.updateAvailability()),
        immediateAllowed = info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE),
        flexibleAllowed = info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE),
        installedFromPlay = installedFromPlay
    )

    /** Package name of the installer that put this app on the device (null when unknown). */
    fun installerPackage(context: Context): String? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getInstallerPackageName(context.packageName)
        }
    } catch (e: Exception) {
        Log.w(TAG, "Installer info unavailable: ${e.message}")
        null
    }

    fun isInstalledFromPlay(installerPackage: String?): Boolean =
        installerPackage == PLAY_STORE_INSTALLER

    fun findActivity(context: Context): Activity? {
        var current: Context? = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }

    private fun manager(context: Context): AppUpdateManager? = try {
        AppUpdateManagerFactory.create(context)
    } catch (e: Exception) {
        Log.e(TAG, "Play Core unavailable", e)
        null
    }

    /** Bridges a Play Core Task into a coroutine (app-update-ktx 2.1.0 has no Task.await()). */
    private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { value -> continuation.resume(value) }
        addOnFailureListener { error -> continuation.resumeWithException(error) }
    }

    /** Asks Play Core whether an update is available. Never throws. */
    suspend fun check(context: Context): Result<AppUpdateInfo?> = withContext(Dispatchers.IO) {
        val appUpdateManager = manager(context)
            ?: return@withContext failUnavailable("Google Play ใช้งานไม่ได้บนเครื่องนี้")
        _state.value = PlayUpdateState.Checking
        try {
            val info = appUpdateManager.appUpdateInfo.awaitResult()
            val installedFromPlay = isInstalledFromPlay(installerPackage(context))
            val status = statusOf(info.updateAvailability())
            _state.value = when {
                !installedFromPlay && status == RemoteUpdateStatus.NOT_AVAILABLE -> PlayUpdateState.Unavailable(
                    NOT_FROM_PLAY_REASON
                )

                status == RemoteUpdateStatus.AVAILABLE -> PlayUpdateState.Available(
                    versionCode = info.availableVersionCode(),
                    updateInProgress = false
                )

                status == RemoteUpdateStatus.IN_PROGRESS -> PlayUpdateState.Available(
                    versionCode = info.availableVersionCode(),
                    updateInProgress = true
                )

                else -> PlayUpdateState.UpToDate
            }
            Result.success(info)
        } catch (e: Exception) {
            Log.w(TAG, "Update check failed: ${e.message}")
            failUnavailable(e.localizedMessage ?: "ตรวจสอบอัปเดตผ่าน Google Play ไม่ได้")
        }
    }

    /**
     * Starts the Play update flow (immediate or flexible) or resumes a pending one.
     * Returns the action that was taken so the UI can react; never throws.
     */
    suspend fun startUpdate(context: Context): Result<PlayUpdateAction> {
        val activity = findActivity(context)
            ?: return Result.failure(IllegalStateException("ไม่พบ Activity สำหรับเปิดหน้าอัปเดตของ Play"))
        val appUpdateManager = manager(context)
            ?: return unavailableAction("Google Play ใช้งานไม่ได้บนเครื่องนี้")
        return try {
            val info = appUpdateManager.appUpdateInfo.awaitResult()
            when (val action = actionFor(info, isInstalledFromPlay(installerPackage(context)))) {
                PlayUpdateAction.START_IMMEDIATE -> {
                    registerInstallListener(appUpdateManager)
                    appUpdateManager.startUpdateFlowForResult(
                        info,
                        activity,
                        AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build(),
                        REQUEST_CODE
                    )
                    Result.success(action)
                }

                PlayUpdateAction.START_FLEXIBLE -> {
                    registerInstallListener(appUpdateManager)
                    appUpdateManager.startUpdateFlowForResult(
                        info,
                        activity,
                        AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build(),
                        REQUEST_CODE
                    )
                    Result.success(action)
                }

                PlayUpdateAction.COMPLETE_PENDING -> {
                    completeUpdate(context)
                    Result.success(action)
                }

                PlayUpdateAction.NOT_FROM_PLAY -> {
                    _state.value = PlayUpdateState.Unavailable(NOT_FROM_PLAY_REASON)
                    Result.success(action)
                }

                PlayUpdateAction.UP_TO_DATE -> {
                    _state.value = PlayUpdateState.UpToDate
                    Result.success(action)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Starting update flow failed: ${e.message}")
            unavailableAction(e.localizedMessage ?: "เปิดหน้าอัปเดตของ Google Play ไม่ได้")
        }
    }

    /** Completes a flexible update that Play already downloaded. */
    suspend fun completeUpdate(context: Context): Result<Unit> {
        val appUpdateManager = manager(context)
            ?: return Result.failure(IllegalStateException("Google Play ใช้งานไม่ได้บนเครื่องนี้"))
        return try {
            appUpdateManager.completeUpdate()
            _state.value = PlayUpdateState.Downloaded
            Result.success(Unit)
        } catch (e: Exception) {
            Log.w(TAG, "Completing update failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Called on ON_RESUME: finishes a flexible update that has been downloaded while the user
     * was in another app, and mirrors download progress into [state].
     */
    suspend fun onResume(context: Context) {
        val appUpdateManager = manager(context) ?: return
        try {
            val info = appUpdateManager.appUpdateInfo.awaitResult()
            if (info.installStatus() == InstallStatus.DOWNLOADED) {
                completeUpdate(context)
                return
            }
            when (statusOf(info.updateAvailability())) {
                RemoteUpdateStatus.IN_PROGRESS -> {
                    registerInstallListener(appUpdateManager)
                    _state.value = PlayUpdateState.Available(
                        versionCode = info.availableVersionCode(),
                        updateInProgress = true
                    )
                }

                RemoteUpdateStatus.AVAILABLE -> {
                    _state.value = PlayUpdateState.Available(
                        versionCode = info.availableVersionCode(),
                        updateInProgress = false
                    )
                }

                // Do not let a sideloaded install look up to date; keep the Play hint visible
                RemoteUpdateStatus.NOT_AVAILABLE -> {
                    if (!isInstalledFromPlay(installerPackage(context))) {
                        _state.value = PlayUpdateState.Unavailable(NOT_FROM_PLAY_REASON)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Resume check failed: ${e.message}")
        }
    }

    private fun registerInstallListener(appUpdateManager: AppUpdateManager) {
        if (installListener != null) return
        val listener = InstallStateUpdatedListener { installState ->
            when (installState.installStatus()) {
                InstallStatus.DOWNLOADING -> {
                    val total = installState.totalBytesToDownload()
                    val percent = if (total > 0) {
                        ((installState.bytesDownloaded() * 100) / total).toInt().coerceIn(0, 100)
                    } else {
                        0
                    }
                    _state.value = PlayUpdateState.Downloading(percent)
                }

                InstallStatus.DOWNLOADED -> _state.value = PlayUpdateState.Downloaded
                InstallStatus.INSTALLED -> _state.value = PlayUpdateState.UpToDate
                InstallStatus.FAILED -> _state.value =
                    PlayUpdateState.Unavailable("ติดตั้งอัปเดตผ่าน Google Play ไม่สำเร็จ")

                else -> Unit
            }
        }
        installListener = listener
        appUpdateManager.registerListener(listener)
    }

    private fun failUnavailable(reason: String): Result<AppUpdateInfo?> {
        _state.value = PlayUpdateState.Unavailable(reason)
        return Result.failure(IllegalStateException(reason))
    }

    private fun unavailableAction(reason: String): Result<PlayUpdateAction> {
        _state.value = PlayUpdateState.Unavailable(reason)
        return Result.failure(IllegalStateException(reason))
    }
}