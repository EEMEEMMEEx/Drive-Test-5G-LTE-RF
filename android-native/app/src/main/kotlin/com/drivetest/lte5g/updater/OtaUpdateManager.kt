package com.drivetest.lte5g.updater

import android.app.Activity
import android.app.ActivityOptions
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.core.content.pm.PackageInfoCompat
import com.drivetest.lte5g.BuildConfig
import com.drivetest.lte5g.MainActivity
import com.drivetest.lte5g.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

data class ReleaseInfo(
    val tagName: String,
    val versionName: String,
    val releaseTitle: String,
    val releaseNotes: String,
    val downloadUrl: String,
    val apkSize: Long,
    val expectedSha256: String? = null,
    val checksumUrl: String? = null
)

data class InstalledAppVersion(
    val versionName: String,
    val versionCode: Long
)

enum class OtaErrorKind {
    GENERAL,
    NETWORK,
    INSTALL_PERMISSION,
    VERIFICATION,
    INSTALL_FAILED
}

sealed interface OtaState {
    object Idle : OtaState
    object Checking : OtaState
    data class UpdateAvailable(val releaseInfo: ReleaseInfo) : OtaState
    data class UpToDate(val version: String) : OtaState
    data class Downloading(
        val releaseInfo: ReleaseInfo,
        val progressPercent: Int,
        val downloadedBytes: Long,
        val totalBytes: Long
    ) : OtaState
    data class ReadyToInstall(val releaseInfo: ReleaseInfo, val apkFilePath: String) : OtaState
    data class Installing(val releaseInfo: ReleaseInfo) : OtaState
    data class Error(
        val message: String,
        val kind: OtaErrorKind = OtaErrorKind.GENERAL,
        val releaseInfo: ReleaseInfo? = null,
        val apkFilePath: String? = null
    ) : OtaState
}

sealed interface ApkVerificationResult {
    data class Valid(val versionCode: Long, val sha256Hex: String) : ApkVerificationResult
    data class Invalid(val reason: String) : ApkVerificationResult
}

/**
 * Reads release metadata from GitHub Releases and handles sideloaded APK download,
 * cryptographic/package verification, and PackageInstaller session installation
 * when not installed from Google Play.
 */
object OtaUpdateManager {
    private const val TAG = "OtaUpdateManager"
    private const val PREFS_NAME = "ota_updater_prefs"
    private const val KEY_AUTO_UPDATE = "auto_update_enabled"
    private const val KEY_PENDING_RELAUNCH = "pending_ota_relaunch"
    private const val KEY_PENDING_VERSION = "pending_ota_target_version"
    private const val KEY_PENDING_VERSION_CODE = "pending_ota_target_code"
    private const val KEY_AWAITING_INSTALL_PERMISSION = "awaiting_install_permission"
    private const val KEY_CACHED_APK_PATH = "cached_apk_path"
    private const val KEY_CACHED_RELEASE_TAG = "cached_release_tag"
    private const val KEY_CACHED_RELEASE_VERSION = "cached_release_version"
    private const val KEY_CACHED_RELEASE_TITLE = "cached_release_title"
    private const val KEY_CACHED_RELEASE_NOTES = "cached_release_notes"
    private const val KEY_CACHED_RELEASE_URL = "cached_release_url"
    private const val KEY_CACHED_RELEASE_SIZE = "cached_release_size"
    private const val KEY_CACHED_RELEASE_SHA256 = "cached_release_sha256"
    private const val UPDATE_CHANNEL_ID = "ota_update_completed"
    private const val UPDATE_NOTIFICATION_ID = 4209
    private const val CONFIRM_NOTIFICATION_ID = 4210
    private const val GITHUB_REPO = "EEMEEMMEEx/Drive-Test-5G-LTE-RF"
    private const val RELEASES_API_URL = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"

    const val ACTION_INSTALL_STATUS = "com.drivetest.lte5g.updater.ACTION_INSTALL_STATUS"
    private const val EXTRA_RELEASE_VERSION = "extra_release_version"
    private const val EXTRA_APK_PATH = "extra_apk_path"

    private const val ACTION_INSTALL_STATUS_DEFAULT_INVALID_ID = -1

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }
    private val lifecycleRegistered = AtomicBoolean(false)
    private val relaunchDispatched = AtomicBoolean(false)

    private var downloadJob: Job? = null
    private var installJob: Job? = null
    private var resumeCheckJob: Job? = null

    @Volatile
    private var currentActivityRef: WeakReference<Activity>? = null

    @Volatile
    private var isActivityResumed: Boolean = false

    @Volatile
    private var activeSessionId: Int = ACTION_INSTALL_STATUS_DEFAULT_INVALID_ID

    @Volatile
    private var activeInstallRelease: ReleaseInfo? = null

    @Volatile
    private var activeInstallApkPath: String? = null

    @Volatile
    private var pendingConfirmIntent: Intent? = null

    @Volatile
    private var pendingConfirmSessionId: Int = ACTION_INSTALL_STATUS_DEFAULT_INVALID_ID

    private val _state = MutableStateFlow<OtaState>(OtaState.Idle)
    val state: StateFlow<OtaState> = _state.asStateFlow()

    private val _lastCheckedTimestamp = MutableStateFlow<Long?>(null)
    val lastCheckedTimestamp: StateFlow<Long?> = _lastCheckedTimestamp.asStateFlow()

    private val _autoUpdateEnabled = MutableStateFlow(true)
    val autoUpdateEnabled: StateFlow<Boolean> = _autoUpdateEnabled.asStateFlow()

    fun init(context: Context) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _autoUpdateEnabled.value = prefs.getBoolean(KEY_AUTO_UPDATE, true)
        findActivity(context)?.let { activity ->
            currentActivityRef = WeakReference(activity)
        }
        registerActivityTracker(appContext)
        cleanupStaleUpdatesOnStartup(appContext)
        scope.launch {
            restoreCachedPendingUpdateIfReady(appContext)
        }
    }

    private fun findActivity(context: Context): Activity? {
        var current: Context? = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }

    private fun registerActivityTracker(appContext: Context) {
        val app = appContext as? Application ?: return
        if (!lifecycleRegistered.compareAndSet(false, true)) return
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                currentActivityRef = WeakReference(activity)
            }

            override fun onActivityStarted(activity: Activity) {
                currentActivityRef = WeakReference(activity)
            }

            override fun onActivityResumed(activity: Activity) {
                currentActivityRef = WeakReference(activity)
                isActivityResumed = true
            }

            override fun onActivityPaused(activity: Activity) {
                if (currentActivityRef?.get() === activity) {
                    isActivityResumed = false
                }
            }

            override fun onActivityStopped(activity: Activity) {
                if (currentActivityRef?.get() === activity) {
                    isActivityResumed = false
                }
            }

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

            override fun onActivityDestroyed(activity: Activity) {
                if (currentActivityRef?.get() === activity) {
                    currentActivityRef = null
                    isActivityResumed = false
                }
            }
        })
    }

    /**
     * Reads the currently installed version directly from PackageManager (never cached),
     * falling back to BuildConfig only if PackageManager throws.
     */
    fun getInstalledAppVersion(context: Context): InstalledAppVersion {
        return try {
            val pm = context.packageManager
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, 0)
            }
            InstalledAppVersion(
                versionName = info.versionName ?: BuildConfig.VERSION_NAME,
                versionCode = PackageInfoCompat.getLongVersionCode(info)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed reading PackageInfo; falling back to BuildConfig", e)
            InstalledAppVersion(
                versionName = BuildConfig.VERSION_NAME,
                versionCode = BuildConfig.VERSION_CODE.toLong()
            )
        }
    }

    fun setAutoUpdateEnabled(enabled: Boolean, context: Context? = null) {
        _autoUpdateEnabled.value = enabled
        context?.applicationContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()
            ?.putBoolean(KEY_AUTO_UPDATE, enabled)
            ?.apply()
    }

    fun resetState() {
        downloadJob?.cancel()
        downloadJob = null
        installJob?.cancel()
        installJob = null
        resumeCheckJob?.cancel()
        resumeCheckJob = null
        _state.value = OtaState.Idle
    }

    fun cancelDownload() {
        val current = _state.value
        downloadJob?.cancel()
        downloadJob = null
        if (current is OtaState.Downloading) {
            _state.value = OtaState.UpdateAvailable(current.releaseInfo)
        } else {
            _state.value = OtaState.Idle
        }
    }

    /**
     * Extracts pure SemVer string (e.g. "v1.4.2-android" -> "1.4.2", "v1.5.0" -> "1.5.0")
     */
    fun cleanVersionString(raw: String): String {
        return raw.trim()
            .removePrefix("v")
            .removePrefix("V")
            .split("-")[0]
            .trim()
    }

    /**
     * Compares two SemVer strings (e.g. "1.5.0" vs "1.4.2").
     * Returns > 0 if v1 > v2, 0 if equal, < 0 if v1 < v2.
     */
    fun compareVersions(v1: String, v2: String): Int {
        val parts1 = cleanVersionString(v1).split(".").mapNotNull { it.toIntOrNull() }
        val parts2 = cleanVersionString(v2).split(".").mapNotNull { it.toIntOrNull() }

        val maxLen = maxOf(parts1.size, parts2.size)
        for (i in 0 until maxLen) {
            val p1 = parts1.getOrElse(i) { 0 }
            val p2 = parts2.getOrElse(i) { 0 }
            if (p1 != p2) {
                return p1.compareTo(p2)
            }
        }
        return 0
    }

    fun parseSha256Digest(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val candidate = raw.trim().removePrefix("sha256:").removePrefix("SHA256:").trim()
        return if (candidate.length == 64 && candidate.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            candidate.lowercase()
        } else {
            null
        }
    }

    fun parseChecksumText(text: String, apkFileName: String? = null): String? {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.isEmpty()) return null
        val regex = Regex("""^([A-Fa-f0-9]{64})(?:\s+\*?(.+))?$""")
        if (!apkFileName.isNullOrBlank()) {
            for (line in lines) {
                val match = regex.matchEntire(line) ?: continue
                val file = match.groupValues[2].trim()
                if (file.equals(apkFileName, ignoreCase = true) || file.endsWith("/$apkFileName", ignoreCase = true)) {
                    return match.groupValues[1].lowercase()
                }
            }
        }
        for (line in lines) {
            val match = regex.matchEntire(line) ?: continue
            val file = match.groupValues[2].trim()
            if (file.isEmpty() || file.endsWith(".apk", ignoreCase = true)) {
                return match.groupValues[1].lowercase()
            }
        }
        return null
    }

    /**
     * Parse GitHub Release JSON into ReleaseInfo
     */
    fun parseReleaseJson(jsonString: String): ReleaseInfo? {
        val root = JSONObject(jsonString)
        val tagName = root.optString("tag_name", "")
        val title = root.optString("name", tagName)
        val notes = root.optString("body", "")
        val assets = root.optJSONArray("assets") ?: return null

        var downloadUrl: String? = null
        var apkSize = 0L
        var expectedSha256: String? = null
        var checksumUrl: String? = null

        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.optString("name", "")
            val assetUrl = asset.optString("browser_download_url", "")
            if (name.endsWith(".apk", ignoreCase = true) && downloadUrl == null) {
                downloadUrl = assetUrl
                apkSize = asset.optLong("size", 0L)
                expectedSha256 = parseSha256Digest(asset.optString("digest", ""))
            } else if (
                checksumUrl == null &&
                assetUrl.isNotBlank() &&
                (name.endsWith(".sha256", ignoreCase = true) ||
                    name.contains("checksum", ignoreCase = true) ||
                    name.contains("sha256sum", ignoreCase = true))
            ) {
                checksumUrl = assetUrl
            }
        }

        if (downloadUrl.isNullOrBlank()) {
            return null
        }

        val cleanedVersion = cleanVersionString(tagName)
        return ReleaseInfo(
            tagName = tagName,
            versionName = cleanedVersion,
            releaseTitle = title,
            releaseNotes = notes,
            downloadUrl = downloadUrl,
            apkSize = apkSize,
            expectedSha256 = expectedSha256,
            checksumUrl = checksumUrl
        )
    }

    suspend fun checkForUpdatesIfIdle(context: Context): Result<ReleaseInfo?> {
        val current = _state.value
        if (current !is OtaState.Idle && current !is OtaState.UpToDate) {
            return Result.success(null)
        }
        val appContext = context.applicationContext
        restoreCachedPendingUpdateIfReady(appContext)
        if (_state.value is OtaState.ReadyToInstall) {
            return Result.success((_state.value as OtaState.ReadyToInstall).releaseInfo)
        }
        val installed = getInstalledAppVersion(appContext)
        val result = checkForUpdates(installed.versionName)
        val release = result.getOrNull()
        if (release != null) {
            tryReuseCachedApk(appContext, release)
        }
        return result
    }

    /**
     * Checks for updates from GitHub Releases API (metadata only, no download)
     */
    suspend fun checkForUpdates(currentVersion: String): Result<ReleaseInfo?> = withContext(Dispatchers.IO) {
        _state.value = OtaState.Checking
        var connection: HttpURLConnection? = null
        try {
            val url = URL(RELEASES_API_URL)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 10000
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                setRequestProperty("User-Agent", "DriveTest-Android-Updater")
            }

            val responseCode = connection.responseCode
            _lastCheckedTimestamp.value = System.currentTimeMillis()
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                val releaseInfo = parseReleaseJson(responseText)

                if (releaseInfo != null && compareVersions(releaseInfo.versionName, currentVersion) > 0) {
                    _state.value = OtaState.UpdateAvailable(releaseInfo)
                    Result.success(releaseInfo)
                } else {
                    _state.value = OtaState.UpToDate(currentVersion)
                    Result.success(null)
                }
            } else {
                val errorMsg = when (responseCode) {
                    403 -> "GitHub API rate limit reached (HTTP 403). Try again later."
                    404 -> "No published release found (HTTP 404)."
                    else -> "Update server returned HTTP $responseCode."
                }
                _state.value = OtaState.Error(errorMsg, OtaErrorKind.GENERAL)
                Result.failure(Exception(errorMsg))
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            _lastCheckedTimestamp.value = System.currentTimeMillis()
            Log.e(TAG, "Failed to check for updates", e)
            val (errorMsg, kind) = when (e) {
                is UnknownHostException, is ConnectException ->
                    "No internet connection. Connect to Wi-Fi or mobile data." to OtaErrorKind.NETWORK
                is SocketTimeoutException ->
                    "Connection timed out while contacting GitHub." to OtaErrorKind.NETWORK
                else ->
                    (e.localizedMessage ?: "Failed to connect to update server") to OtaErrorKind.GENERAL
            }
            _state.value = OtaState.Error(errorMsg, kind)
            Result.failure(e)
        } finally {
            connection?.disconnect()
        }
    }

    fun startDownload(context: Context, releaseInfo: ReleaseInfo) {
        if (downloadJob?.isActive == true) return
        val appContext = context.applicationContext
        downloadJob = scope.launch {
            _state.value = OtaState.Downloading(releaseInfo, 0, 0L, releaseInfo.apkSize)
            var conn: HttpURLConnection? = null
            var tmpFile: File? = null
            try {
                val resolvedRelease = resolveExpectedSha256(releaseInfo)
                val updatesDir = File(appContext.cacheDir, "updates").apply { if (!exists()) mkdirs() }
                val targetFile = File(updatesDir, "update_${resolvedRelease.versionName}.apk")
                val partFile = File(updatesDir, "update_${resolvedRelease.versionName}.apk.part")
                tmpFile = partFile
                if (partFile.exists()) partFile.delete()

                conn = (URL(resolvedRelease.downloadUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15000
                    readTimeout = 30000
                    instanceFollowRedirects = true
                }
                val code = conn.responseCode
                if (code !in 200..299) {
                    _state.value = OtaState.Error(
                        "Download failed (HTTP $code).",
                        OtaErrorKind.GENERAL,
                        releaseInfo = resolvedRelease
                    )
                    return@launch
                }
                val totalBytes = conn.contentLengthLong.takeIf { it > 0 } ?: resolvedRelease.apkSize
                var downloaded = 0L
                var lastEmitMs = 0L
                val digest = MessageDigest.getInstance("SHA-256")

                conn.inputStream.use { input ->
                    FileOutputStream(partFile).use { output ->
                        val buffer = ByteArray(16384)
                        while (isActive) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            downloaded += read
                            val now = System.currentTimeMillis()
                            if (now - lastEmitMs >= 150L) {
                                lastEmitMs = now
                                val pct = if (totalBytes > 0) ((downloaded * 100L) / totalBytes).toInt().coerceIn(0, 100) else 0
                                _state.value = OtaState.Downloading(resolvedRelease, pct, downloaded, totalBytes)
                            }
                        }
                    }
                }
                if (!isActive) {
                    partFile.delete()
                    return@launch
                }

                if (downloaded <= 0L || (resolvedRelease.apkSize > 0L && downloaded != resolvedRelease.apkSize)) {
                    partFile.delete()
                    _state.value = OtaState.Error(
                        "Downloaded APK size mismatch ($downloaded vs ${resolvedRelease.apkSize} bytes).",
                        OtaErrorKind.VERIFICATION,
                        releaseInfo = resolvedRelease
                    )
                    return@launch
                }

                val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
                val expectedSha = resolvedRelease.expectedSha256
                if (expectedSha != null && !actualSha256.equals(expectedSha, ignoreCase = true)) {
                    partFile.delete()
                    _state.value = OtaState.Error(
                        "Downloaded APK failed SHA-256 verification.",
                        OtaErrorKind.VERIFICATION,
                        releaseInfo = resolvedRelease
                    )
                    return@launch
                }

                if (targetFile.exists()) targetFile.delete()
                if (!partFile.renameTo(targetFile)) {
                    partFile.copyTo(targetFile, overwrite = true)
                    partFile.delete()
                }

                when (val verification = verifyApkArchive(appContext, targetFile, resolvedRelease, actualSha256)) {
                    is ApkVerificationResult.Valid -> {
                        activeInstallRelease = resolvedRelease
                        activeInstallApkPath = targetFile.absolutePath
                        saveCachedPendingApk(appContext, resolvedRelease, targetFile.absolutePath)
                        _state.value = OtaState.ReadyToInstall(resolvedRelease, targetFile.absolutePath)
                    }
                    is ApkVerificationResult.Invalid -> {
                        targetFile.delete()
                        clearCachedPendingApk(appContext)
                        _state.value = OtaState.Error(
                            verification.reason,
                            OtaErrorKind.VERIFICATION,
                            releaseInfo = resolvedRelease
                        )
                    }
                }
            } catch (ce: CancellationException) {
                tmpFile?.delete()
                throw ce
            } catch (e: Exception) {
                tmpFile?.delete()
                Log.e(TAG, "APK download failed", e)
                val (msg, kind) = when (e) {
                    is UnknownHostException, is ConnectException, is SocketTimeoutException ->
                        "Network interrupted while downloading update." to OtaErrorKind.NETWORK
                    else ->
                        (e.localizedMessage ?: "Failed to download update package.") to OtaErrorKind.GENERAL
                }
                _state.value = OtaState.Error(msg, kind, releaseInfo = releaseInfo)
            } finally {
                conn?.disconnect()
            }
        }
    }

    private fun resolveExpectedSha256(releaseInfo: ReleaseInfo): ReleaseInfo {
        if (releaseInfo.expectedSha256 != null || releaseInfo.checksumUrl.isNullOrBlank()) {
            return releaseInfo
        }
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(releaseInfo.checksumUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                instanceFollowRedirects = true
            }
            if (conn.responseCode in 200..299) {
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val apkFileName = releaseInfo.downloadUrl.substringAfterLast('/').substringBefore('?')
                val parsed = parseChecksumText(text, apkFileName)
                if (parsed != null) releaseInfo.copy(expectedSha256 = parsed) else releaseInfo
            } else {
                releaseInfo
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed fetching checksum asset; continuing with GitHub digest/cert verification", e)
            releaseInfo
        } finally {
            conn?.disconnect()
        }
    }

    fun verifyApkArchive(
        context: Context,
        apkFile: File,
        releaseInfo: ReleaseInfo,
        precomputedSha256: String? = null
    ): ApkVerificationResult {
        if (!apkFile.exists() || apkFile.length() <= 0L) {
            return ApkVerificationResult.Invalid("Downloaded APK file is missing or empty.")
        }

        val sha256Hex = precomputedSha256 ?: computeFileSha256(apkFile)
        val expectedSha = releaseInfo.expectedSha256
        if (expectedSha != null && !sha256Hex.equals(expectedSha, ignoreCase = true)) {
            return ApkVerificationResult.Invalid("Downloaded APK failed SHA-256 verification.")
        }

        val pm = context.packageManager
        val archiveInfo = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageArchiveInfo(
                    apkFile.absolutePath,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed parsing downloaded APK archive", e)
            null
        } ?: return ApkVerificationResult.Invalid("Downloaded file is not a valid Android APK.")

        if (archiveInfo.packageName != context.packageName) {
            return ApkVerificationResult.Invalid(
                "Package ID mismatch: expected ${context.packageName}, got ${archiveInfo.packageName}."
            )
        }

        val installed = getInstalledAppVersion(context)
        val archiveVersionCode = PackageInfoCompat.getLongVersionCode(archiveInfo)
        if (archiveVersionCode <= installed.versionCode) {
            return ApkVerificationResult.Invalid(
                "Downloaded APK Build $archiveVersionCode is not newer than installed Build ${installed.versionCode}."
            )
        }

        val archiveVersionName = archiveInfo.versionName
        if (!archiveVersionName.isNullOrBlank() && compareVersions(archiveVersionName, releaseInfo.versionName) != 0) {
            return ApkVerificationResult.Invalid(
                "APK version mismatch: expected v${releaseInfo.versionName}, got v$archiveVersionName."
            )
        }

        val installedCerts = getInstalledSigningCertDigests(context)
        val archiveCerts = extractSigningCertDigests(archiveInfo)
        if (installedCerts.isEmpty() || archiveCerts.isEmpty() || installedCerts.intersect(archiveCerts).isEmpty()) {
            return ApkVerificationResult.Invalid(
                "Downloaded APK signing certificate does not match the installed app."
            )
        }

        return ApkVerificationResult.Valid(archiveVersionCode, sha256Hex)
    }

    private fun computeFileSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(16384)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun getInstalledSigningCertDigests(context: Context): Set<String> {
        return try {
            val pm = context.packageManager
            val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            }
            extractSigningCertDigests(pkgInfo)
        } catch (e: Exception) {
            Log.e(TAG, "Failed reading installed signing certificates", e)
            emptySet()
        }
    }

    private fun extractSigningCertDigests(packageInfo: PackageInfo): Set<String> {
        val signingInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.signingInfo
        } else {
            null
        }
        val signatures = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && signingInfo != null -> {
                if (signingInfo.hasMultipleSigners()) {
                    signingInfo.apkContentsSigners
                } else {
                    signingInfo.signingCertificateHistory ?: signingInfo.apkContentsSigners
                }
            }
            else -> {
                @Suppress("DEPRECATION")
                packageInfo.signatures
            }
        } ?: return emptySet()

        val md = MessageDigest.getInstance("SHA-256")
        return signatures.map { sig ->
            md.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    fun canInstallPackages(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                context.packageManager.canRequestPackageInstalls()
            } catch (se: SecurityException) {
                Log.e(TAG, "canRequestPackageInstalls threw SecurityException", se)
                false
            }
        } else {
            true
        }
    }

    fun openInstallPermissionSettings(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AWAITING_INSTALL_PERMISSION, true)
            .apply()
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            )
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun buildApkContentUri(context: Context, apkFile: File): Uri {
        return FileProvider.getUriForFile(context, "${context.packageName}.provider", apkFile)
    }

    fun installDownloadedApk(context: Context, releaseInfo: ReleaseInfo, apkFilePath: String) {
        val appContext = context.applicationContext
        findActivity(context)?.let { activity ->
            currentActivityRef = WeakReference(activity)
            if (!activity.isFinishing && !activity.isDestroyed && activity.hasWindowFocus()) {
                isActivityResumed = true
            }
        }
        if (!canInstallPackages(appContext)) {
            activeInstallRelease = releaseInfo
            activeInstallApkPath = apkFilePath
            saveCachedPendingApk(appContext, releaseInfo, apkFilePath)
            _state.value = OtaState.Error(
                "Allow 'Install unknown apps' permission to install v${releaseInfo.versionName}.",
                OtaErrorKind.INSTALL_PERMISSION,
                releaseInfo = releaseInfo,
                apkFilePath = apkFilePath
            )
            return
        }

        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AWAITING_INSTALL_PERMISSION, false)
            .apply()

        val apkFile = File(apkFilePath)
        if (!apkFile.exists()) {
            clearCachedPendingApk(appContext)
            _state.value = OtaState.Error(
                "Downloaded APK file was removed. Please download again.",
                OtaErrorKind.GENERAL,
                releaseInfo = releaseInfo
            )
            return
        }

        if (installJob?.isActive == true) return
        pendingConfirmIntent = null
        pendingConfirmSessionId = ACTION_INSTALL_STATUS_DEFAULT_INVALID_ID
        relaunchDispatched.set(false)
        _state.value = OtaState.Installing(releaseInfo)

        installJob = scope.launch {
            var session: PackageInstaller.Session? = null
            var createdSessionId = PackageInstaller.SessionInfo.INVALID_ID
            try {
                val verification = verifyApkArchive(appContext, apkFile, releaseInfo)
                if (verification is ApkVerificationResult.Invalid) {
                    apkFile.delete()
                    _state.value = OtaState.Error(
                        verification.reason,
                        OtaErrorKind.VERIFICATION,
                        releaseInfo = releaseInfo
                    )
                    return@launch
                }
                val validInfo = verification as ApkVerificationResult.Valid

                val packageInstaller = appContext.packageManager.packageInstaller
                abandonStaleSessions(packageInstaller)

                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(appContext.packageName)
                    setSize(apkFile.length())
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
                    }
                }

                createdSessionId = packageInstaller.createSession(params)
                activeSessionId = createdSessionId
                activeInstallRelease = releaseInfo
                activeInstallApkPath = apkFile.absolutePath

                session = packageInstaller.openSession(createdSessionId)
                session.openWrite("base.apk", 0L, apkFile.length()).use { out ->
                    FileInputStream(apkFile).use { input ->
                        input.copyTo(out, bufferSize = 32768)
                    }
                    session.fsync(out)
                }

                markPendingRelaunch(appContext, releaseInfo.versionName, validInfo.versionCode)

                val callbackIntent = Intent(appContext, OtaInstallReceiver::class.java).apply {
                    action = ACTION_INSTALL_STATUS
                    putExtra(EXTRA_RELEASE_VERSION, releaseInfo.versionName)
                    putExtra(EXTRA_APK_PATH, apkFile.absolutePath)
                }
                val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                val statusPendingIntent = PendingIntent.getBroadcast(
                    appContext,
                    createdSessionId,
                    callbackIntent,
                    pendingIntentFlags
                )

                session.commit(statusPendingIntent.intentSender)
                session.close()
                session = null
                Log.i(TAG, "Committed PackageInstaller session $createdSessionId for v${releaseInfo.versionName}")
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Log.e(TAG, "Failed committing PackageInstaller session", e)
                clearPendingRelaunch(appContext)
                try {
                    session?.abandon()
                } catch (_: Exception) {
                }
                if (createdSessionId != PackageInstaller.SessionInfo.INVALID_ID) {
                    try {
                        appContext.packageManager.packageInstaller.abandonSession(createdSessionId)
                    } catch (_: Exception) {
                    }
                }
                activeSessionId = PackageInstaller.SessionInfo.INVALID_ID
                _state.value = OtaState.Error(
                    e.localizedMessage ?: "Unable to start package installer session.",
                    OtaErrorKind.INSTALL_FAILED,
                    releaseInfo = releaseInfo,
                    apkFilePath = apkFile.absolutePath
                )
            } finally {
                try {
                    session?.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun abandonStaleSessions(packageInstaller: PackageInstaller) {
        try {
            for (info in packageInstaller.mySessions) {
                if (info.sessionId != activeSessionId || !info.isActive) {
                    try {
                        packageInstaller.abandonSession(info.sessionId)
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to enumerate PackageInstaller sessions", e)
        }
    }

    fun handleInstallCallback(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val statusMessage = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, PackageInstaller.SessionInfo.INVALID_ID)
        val releaseVersion = intent.getStringExtra(EXTRA_RELEASE_VERSION)
        val apkPath = intent.getStringExtra(EXTRA_APK_PATH) ?: activeInstallApkPath
        val release = activeInstallRelease ?: releaseVersion?.let {
            ReleaseInfo(
                tagName = "v$it",
                versionName = it,
                releaseTitle = "Drive Test LTE 5G v$it",
                releaseNotes = "",
                downloadUrl = "",
                apkSize = 0L
            )
        }

        Log.i(TAG, "PackageInstaller callback: status=$status sessionId=$sessionId msg=$statusMessage")

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmIntent = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirmIntent == null) {
                    clearPendingRelaunch(appContext)
                    abandonSessionSafely(appContext, sessionId)
                    activeSessionId = PackageInstaller.SessionInfo.INVALID_ID
                    _state.value = OtaState.Error(
                        "System installer confirmation intent was missing.",
                        OtaErrorKind.INSTALL_FAILED,
                        releaseInfo = release,
                        apkFilePath = apkPath
                    )
                    return
                }
                mainHandler.post {
                    try {
                        val activity = currentActivityRef?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
                        if (activity != null && isActivityResumed) {
                            pendingConfirmIntent = null
                            pendingConfirmSessionId = ACTION_INSTALL_STATUS_DEFAULT_INVALID_ID
                            activity.startActivity(confirmIntent)
                        } else {
                            // Defer confirmation to foreground resume (avoids Android 14+ BAL silent drop)
                            // and also post a high-priority notification to let the user open it directly.
                            pendingConfirmIntent = confirmIntent
                            pendingConfirmSessionId = sessionId
                            postConfirmInstallNotification(
                                appContext,
                                release?.versionName ?: releaseVersion.orEmpty(),
                                confirmIntent
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed launching system installer confirmation UI", e)
                        pendingConfirmIntent = null
                        pendingConfirmSessionId = ACTION_INSTALL_STATUS_DEFAULT_INVALID_ID
                        clearPendingRelaunch(appContext)
                        abandonSessionSafely(appContext, sessionId)
                        activeSessionId = PackageInstaller.SessionInfo.INVALID_ID
                        _state.value = OtaState.Error(
                            e.localizedMessage ?: "Unable to open Android installer confirmation.",
                            OtaErrorKind.INSTALL_FAILED,
                            releaseInfo = release,
                            apkFilePath = apkPath
                        )
                    }
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                pendingConfirmIntent = null
                pendingConfirmSessionId = ACTION_INSTALL_STATUS_DEFAULT_INVALID_ID
                activeSessionId = PackageInstaller.SessionInfo.INVALID_ID
                cleanupDownloadedApks(appContext)
                clearCachedPendingApk(appContext)
                val installed = getInstalledAppVersion(appContext)
                _state.value = OtaState.UpToDate(installed.versionName)
                relaunchIfPending(appContext, installed.versionName)
            }

            else -> {
                pendingConfirmIntent = null
                pendingConfirmSessionId = ACTION_INSTALL_STATUS_DEFAULT_INVALID_ID
                clearPendingRelaunch(appContext)
                abandonSessionSafely(appContext, sessionId)
                activeSessionId = PackageInstaller.SessionInfo.INVALID_ID
                val (message, kind) = mapInstallFailure(status, statusMessage)
                if (status == PackageInstaller.STATUS_FAILURE_INVALID ||
                    status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ||
                    status == PackageInstaller.STATUS_FAILURE_CONFLICT
                ) {
                    apkPath?.let { File(it).delete() }
                    clearCachedPendingApk(appContext)
                }
                _state.value = OtaState.Error(
                    message = message,
                    kind = kind,
                    releaseInfo = release,
                    apkFilePath = apkPath?.takeIf { File(it).exists() }
                )
            }
        }
    }

    fun handlePackageReplaced(context: Context) {
        val appContext = context.applicationContext
        cleanupDownloadedApks(appContext)
        clearCachedPendingApk(appContext)
        val installed = getInstalledAppVersion(appContext)
        Log.i(TAG, "MY_PACKAGE_REPLACED received on v${installed.versionName} (Build ${installed.versionCode})")
        _state.value = OtaState.UpToDate(installed.versionName)
        relaunchIfPending(appContext, installed.versionName)
    }

    fun onResume(context: Context) {
        val appContext = context.applicationContext
        findActivity(context)?.let { activity ->
            currentActivityRef = WeakReference(activity)
            if (!activity.isFinishing && !activity.isDestroyed) {
                isActivityResumed = true
            }
        }
        val installed = getInstalledAppVersion(appContext)
        cancelUpdateNotifications(appContext)

        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val pendingVersionCode = prefs.getLong(KEY_PENDING_VERSION_CODE, -1L)
        if (pendingVersionCode > 0L && installed.versionCode >= pendingVersionCode) {
            clearPendingRelaunch(appContext)
            cleanupDownloadedApks(appContext)
            clearCachedPendingApk(appContext)
            activeSessionId = PackageInstaller.SessionInfo.INVALID_ID
            pendingConfirmIntent = null
            pendingConfirmSessionId = ACTION_INSTALL_STATUS_DEFAULT_INVALID_ID
            _state.value = OtaState.UpToDate(installed.versionName)
            return
        }

        // If STATUS_PENDING_USER_ACTION arrived while backgrounded, launch confirmation now on foreground Activity.
        val deferredConfirm = pendingConfirmIntent
        if (deferredConfirm != null && _state.value is OtaState.Installing) {
            val activity = currentActivityRef?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
            if (activity != null) {
                pendingConfirmIntent = null
                pendingConfirmSessionId = ACTION_INSTALL_STATUS_DEFAULT_INVALID_ID
                try {
                    activity.startActivity(deferredConfirm)
                    return
                } catch (e: Exception) {
                    Log.e(TAG, "Failed launching deferred installer confirmation UI", e)
                    clearPendingRelaunch(appContext)
                    abandonSessionSafely(appContext, activeSessionId)
                    activeSessionId = PackageInstaller.SessionInfo.INVALID_ID
                    _state.value = OtaState.Error(
                        e.localizedMessage ?: "Unable to open Android installer confirmation.",
                        OtaErrorKind.INSTALL_FAILED,
                        releaseInfo = activeInstallRelease,
                        apkFilePath = activeInstallApkPath?.takeIf { File(it).exists() }
                    )
                    return
                }
            }
        }

        val current = _state.value

        // Survives Android 11+ process kill when user toggles "Allow from this source" in System Settings.
        val awaitingPermission = prefs.getBoolean(KEY_AWAITING_INSTALL_PERMISSION, false)
        if ((awaitingPermission || (current is OtaState.Error && current.kind == OtaErrorKind.INSTALL_PERMISSION)) &&
            canInstallPackages(appContext)
        ) {
            prefs.edit().putBoolean(KEY_AWAITING_INSTALL_PERMISSION, false).apply()
            val cached = loadCachedPendingApk(appContext)
            val release = (current as? OtaState.Error)?.releaseInfo ?: cached?.first
            val apkPath = (current as? OtaState.Error)?.apkFilePath ?: cached?.second
            if (release != null && apkPath != null && File(apkPath).exists() &&
                compareVersions(release.versionName, installed.versionName) > 0
            ) {
                installDownloadedApk(context, release, apkPath)
                return
            }
        }

        if (current is OtaState.Installing) {
            if (compareVersions(installed.versionName, current.releaseInfo.versionName) >= 0) {
                cleanupDownloadedApks(appContext)
                clearCachedPendingApk(appContext)
                clearPendingRelaunch(appContext)
                activeSessionId = PackageInstaller.SessionInfo.INVALID_ID
                _state.value = OtaState.UpToDate(installed.versionName)
                return
            }

            val trackedSessionId = activeSessionId
            resumeCheckJob?.cancel()
            resumeCheckJob = scope.launch {
                // Allow PackageInstaller time to deliver STATUS_FAILURE_ABORTED or finish dexopt/install.
                delay(2500L)
                var elapsedMs = 2500L
                val maxWaitMs = 60_000L
                while (isActive) {
                    val latestState = _state.value
                    if (latestState !is OtaState.Installing) return@launch

                    val nowInstalled = getInstalledAppVersion(appContext)
                    if (compareVersions(nowInstalled.versionName, latestState.releaseInfo.versionName) >= 0) {
                        cleanupDownloadedApks(appContext)
                        clearCachedPendingApk(appContext)
                        clearPendingRelaunch(appContext)
                        activeSessionId = PackageInstaller.SessionInfo.INVALID_ID
                        _state.value = OtaState.UpToDate(nowInstalled.versionName)
                        return@launch
                    }

                    val sessionInfo = if (trackedSessionId != PackageInstaller.SessionInfo.INVALID_ID) {
                        try {
                            appContext.packageManager.packageInstaller.getSessionInfo(trackedSessionId)
                        } catch (_: Exception) {
                            null
                        }
                    } else {
                        null
                    }

                    if (sessionInfo == null) {
                        // Brief second grace window in case STATUS_FAILURE_* or STATUS_SUCCESS is in flight.
                        delay(1000L)
                        val afterGrace = _state.value
                        if (afterGrace !is OtaState.Installing) return@launch
                        val recheckInstalled = getInstalledAppVersion(appContext)
                        if (compareVersions(recheckInstalled.versionName, afterGrace.releaseInfo.versionName) >= 0) {
                            cleanupDownloadedApks(appContext)
                            clearCachedPendingApk(appContext)
                            clearPendingRelaunch(appContext)
                            activeSessionId = PackageInstaller.SessionInfo.INVALID_ID
                            _state.value = OtaState.UpToDate(recheckInstalled.versionName)
                            return@launch
                        }
                        clearPendingRelaunch(appContext)
                        activeSessionId = PackageInstaller.SessionInfo.INVALID_ID
                        val apkPath = activeInstallApkPath?.takeIf { File(it).exists() }
                        _state.value = OtaState.Error(
                            "Installation was cancelled or did not complete. Tap RETRY to install again.",
                            OtaErrorKind.INSTALL_FAILED,
                            releaseInfo = afterGrace.releaseInfo,
                            apkFilePath = apkPath
                        )
                        return@launch
                    }

                    if (elapsedMs >= maxWaitMs) {
                        clearPendingRelaunch(appContext)
                        abandonSessionSafely(appContext, trackedSessionId)
                        activeSessionId = PackageInstaller.SessionInfo.INVALID_ID
                        val apkPath = activeInstallApkPath?.takeIf { File(it).exists() }
                        _state.value = OtaState.Error(
                            "Installation timed out waiting for Android PackageInstaller. Tap RETRY to try again.",
                            OtaErrorKind.INSTALL_FAILED,
                            releaseInfo = latestState.releaseInfo,
                            apkFilePath = apkPath
                        )
                        return@launch
                    }

                    delay(2000L)
                    elapsedMs += 2000L
                }
            }
        }
    }

    fun retryAfterError(context: Context, currentVersionName: String, onCheckUpdates: () -> Unit) {
        val current = _state.value
        if (current is OtaState.Error) {
            val release = current.releaseInfo
            val apkPath = current.apkFilePath
            if (apkPath != null && release != null && File(apkPath).exists() &&
                (current.kind == OtaErrorKind.INSTALL_PERMISSION || current.kind == OtaErrorKind.INSTALL_FAILED)
            ) {
                installDownloadedApk(context, release, apkPath)
                return
            }
            if (release != null && release.downloadUrl.isNotBlank() &&
                (current.kind == OtaErrorKind.NETWORK || current.kind == OtaErrorKind.VERIFICATION || current.kind == OtaErrorKind.GENERAL)
            ) {
                if (compareVersions(release.versionName, currentVersionName) > 0) {
                    startDownload(context, release)
                    return
                }
            }
        }
        onCheckUpdates()
    }

    fun mapInstallFailure(status: Int, statusMessage: String?): Pair<String, OtaErrorKind> {
        val detail = statusMessage?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
        return when (status) {
            PackageInstaller.STATUS_FAILURE_ABORTED ->
                "Installation was cancelled. Tap RETRY to install again." to OtaErrorKind.INSTALL_FAILED
            PackageInstaller.STATUS_FAILURE_BLOCKED -> {
                val isPermIssue = statusMessage?.contains("permission", ignoreCase = true) == true ||
                    statusMessage?.contains("REQUEST_INSTALL_PACKAGES", ignoreCase = true) == true
                if (isPermIssue) {
                    "Install blocked by Android permission. Enable 'Install unknown apps' and retry.$detail" to
                        OtaErrorKind.INSTALL_PERMISSION
                } else {
                    "Installation was blocked by Android or Play Protect$detail." to OtaErrorKind.INSTALL_FAILED
                }
            }
            PackageInstaller.STATUS_FAILURE_CONFLICT ->
                "Update conflicts with an existing app signature or package$detail." to OtaErrorKind.INSTALL_FAILED
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                "Update APK is incompatible with this device$detail." to OtaErrorKind.INSTALL_FAILED
            PackageInstaller.STATUS_FAILURE_INVALID ->
                "Downloaded APK package is invalid or corrupted$detail." to OtaErrorKind.VERIFICATION
            PackageInstaller.STATUS_FAILURE_STORAGE ->
                "Not enough storage space to install update$detail." to OtaErrorKind.INSTALL_FAILED
            PackageInstaller.STATUS_FAILURE_TIMEOUT ->
                "Installation timed out$detail. Tap RETRY to try again." to OtaErrorKind.INSTALL_FAILED
            else ->
                "Package installation failed$detail." to OtaErrorKind.INSTALL_FAILED
        }
    }

    fun cleanupDownloadedApks(context: Context) {
        try {
            val dirs = listOfNotNull(
                File(context.applicationContext.cacheDir, "updates"),
                context.applicationContext.externalCacheDir?.let { File(it, "updates") }
            )
            for (updatesDir in dirs) {
                if (updatesDir.exists() && updatesDir.isDirectory) {
                    updatesDir.listFiles()?.forEach { file ->
                        if (file.isFile && (file.name.endsWith(".apk", ignoreCase = true) || file.name.endsWith(".part", ignoreCase = true))) {
                            file.delete()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed cleaning up downloaded APKs", e)
        }
    }

    private fun cleanupStaleUpdatesOnStartup(appContext: Context) {
        try {
            val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val pendingVersionCode = prefs.getLong(KEY_PENDING_VERSION_CODE, -1L)
            val installed = getInstalledAppVersion(appContext)
            if (pendingVersionCode > 0L && installed.versionCode >= pendingVersionCode) {
                // Clean downloaded APKs immediately, but DO NOT clear KEY_PENDING_RELAUNCH here:
                // DriveTestApp.onCreate() runs BEFORE OtaPackageReplacedReceiver / OtaInstallReceiver,
                // which still need KEY_PENDING_RELAUNCH to trigger the post-update relaunch.
                cleanupDownloadedApks(appContext)
                clearCachedPendingApk(appContext)
                return
            }
            val updatesDir = File(appContext.cacheDir, "updates")
            if (!updatesDir.exists()) return
            updatesDir.listFiles()?.forEach { file ->
                if (!file.isFile) return@forEach
                if (file.name.endsWith(".part", ignoreCase = true)) {
                    file.delete()
                } else if (file.name.startsWith("update_") && file.name.endsWith(".apk", ignoreCase = true)) {
                    val fileVer = file.name.removePrefix("update_").removeSuffix(".apk")
                    if (compareVersions(fileVer, installed.versionName) <= 0) {
                        file.delete()
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed cleaning stale updates on startup", e)
        }
    }

    private fun saveCachedPendingApk(appContext: Context, releaseInfo: ReleaseInfo, apkFilePath: String) {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CACHED_APK_PATH, apkFilePath)
            .putString(KEY_CACHED_RELEASE_TAG, releaseInfo.tagName)
            .putString(KEY_CACHED_RELEASE_VERSION, releaseInfo.versionName)
            .putString(KEY_CACHED_RELEASE_TITLE, releaseInfo.releaseTitle)
            .putString(KEY_CACHED_RELEASE_NOTES, releaseInfo.releaseNotes)
            .putString(KEY_CACHED_RELEASE_URL, releaseInfo.downloadUrl)
            .putLong(KEY_CACHED_RELEASE_SIZE, releaseInfo.apkSize)
            .putString(KEY_CACHED_RELEASE_SHA256, releaseInfo.expectedSha256)
            .apply()
    }

    private fun loadCachedPendingApk(appContext: Context): Pair<ReleaseInfo, String>? {
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val apkPath = prefs.getString(KEY_CACHED_APK_PATH, null)?.takeIf { it.isNotBlank() } ?: return null
        val versionName = prefs.getString(KEY_CACHED_RELEASE_VERSION, null)?.takeIf { it.isNotBlank() } ?: return null
        val release = ReleaseInfo(
            tagName = prefs.getString(KEY_CACHED_RELEASE_TAG, "v$versionName") ?: "v$versionName",
            versionName = versionName,
            releaseTitle = prefs.getString(KEY_CACHED_RELEASE_TITLE, "Drive Test LTE 5G v$versionName") ?: "Drive Test LTE 5G v$versionName",
            releaseNotes = prefs.getString(KEY_CACHED_RELEASE_NOTES, "") ?: "",
            downloadUrl = prefs.getString(KEY_CACHED_RELEASE_URL, "") ?: "",
            apkSize = prefs.getLong(KEY_CACHED_RELEASE_SIZE, 0L),
            expectedSha256 = prefs.getString(KEY_CACHED_RELEASE_SHA256, null)
        )
        return release to apkPath
    }

    private fun clearCachedPendingApk(appContext: Context) {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_CACHED_APK_PATH)
            .remove(KEY_CACHED_RELEASE_TAG)
            .remove(KEY_CACHED_RELEASE_VERSION)
            .remove(KEY_CACHED_RELEASE_TITLE)
            .remove(KEY_CACHED_RELEASE_NOTES)
            .remove(KEY_CACHED_RELEASE_URL)
            .remove(KEY_CACHED_RELEASE_SIZE)
            .remove(KEY_CACHED_RELEASE_SHA256)
            .putBoolean(KEY_AWAITING_INSTALL_PERMISSION, false)
            .apply()
    }

    private fun restoreCachedPendingUpdateIfReady(appContext: Context) {
        if (_state.value !is OtaState.Idle) return
        val cached = loadCachedPendingApk(appContext) ?: return
        val (release, apkPath) = cached
        val installed = getInstalledAppVersion(appContext)
        val apkFile = File(apkPath)
        if (!apkFile.exists() || compareVersions(release.versionName, installed.versionName) <= 0) {
            clearCachedPendingApk(appContext)
            return
        }
        val verification = verifyApkArchive(appContext, apkFile, release)
        if (verification is ApkVerificationResult.Valid) {
            activeInstallRelease = release
            activeInstallApkPath = apkFile.absolutePath
            _state.value = OtaState.ReadyToInstall(release, apkFile.absolutePath)
        } else {
            apkFile.delete()
            clearCachedPendingApk(appContext)
        }
    }

    private fun tryReuseCachedApk(appContext: Context, release: ReleaseInfo) {
        val candidate = File(File(appContext.cacheDir, "updates"), "update_${release.versionName}.apk")
        if (!candidate.exists()) return
        val verification = verifyApkArchive(appContext, candidate, release)
        if (verification is ApkVerificationResult.Valid) {
            activeInstallRelease = release
            activeInstallApkPath = candidate.absolutePath
            saveCachedPendingApk(appContext, release, candidate.absolutePath)
            _state.value = OtaState.ReadyToInstall(release, candidate.absolutePath)
        } else {
            candidate.delete()
        }
    }

    private fun markPendingRelaunch(appContext: Context, versionName: String, versionCode: Long) {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PENDING_RELAUNCH, true)
            .putString(KEY_PENDING_VERSION, versionName)
            .putLong(KEY_PENDING_VERSION_CODE, versionCode)
            .apply()
    }

    private fun clearPendingRelaunch(appContext: Context) {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PENDING_RELAUNCH, false)
            .remove(KEY_PENDING_VERSION)
            .remove(KEY_PENDING_VERSION_CODE)
            .apply()
    }

    private fun relaunchIfPending(appContext: Context, installedVersionName: String) {
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_PENDING_RELAUNCH, false)) return
        if (!relaunchDispatched.compareAndSet(false, true)) return
        clearPendingRelaunch(appContext)
        if (isActivityResumed) return

        val launchIntent = (appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)
            ?: Intent(appContext, MainActivity::class.java)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        try {
            val optionsBundle = if (Build.VERSION.SDK_INT >= 36) {
                ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
                    )
                    .toBundle()
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                @Suppress("DEPRECATION")
                ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    )
                    .toBundle()
            } else {
                null
            }
            if (optionsBundle != null) {
                appContext.startActivity(launchIntent, optionsBundle)
            } else {
                appContext.startActivity(launchIntent)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Background relaunch threw; posting update completion notification", e)
            postUpdateCompletedNotification(appContext, installedVersionName, launchIntent)
            return
        }

        // Android 10+ BAL silently drops background startActivity without throwing;
        // verify whether MainActivity actually resumed and post a heads-up notification if not.
        mainHandler.postDelayed({
            if (!isActivityResumed) {
                Log.i(TAG, "MainActivity not resumed after relaunch attempt; posting update completed notification")
                postUpdateCompletedNotification(appContext, installedVersionName, launchIntent)
            }
        }, 1200L)
    }

    private fun postConfirmInstallNotification(
        appContext: Context,
        versionName: String,
        confirmIntent: Intent
    ) {
        try {
            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            ensureUpdateChannel(nm)
            val launchConfirm = Intent(confirmIntent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pi = PendingIntent.getActivity(
                appContext,
                CONFIRM_NOTIFICATION_ID,
                launchConfirm,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val label = if (versionName.isNotBlank()) "v$versionName" else "update"
            val notification = NotificationCompat.Builder(appContext, UPDATE_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Confirm install ($label)")
                .setContentText("Tap to open Android installer and finish updating.")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            nm.notify(CONFIRM_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "Failed posting installer confirmation notification", e)
        }
    }

    private fun ensureUpdateChannel(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                UPDATE_CHANNEL_ID,
                "App Updates",
                NotificationManager.IMPORTANCE_HIGH
            )
            nm.createNotificationChannel(channel)
        }
    }

    private fun cancelUpdateNotifications(appContext: Context) {
        try {
            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            nm.cancel(UPDATE_NOTIFICATION_ID)
            nm.cancel(CONFIRM_NOTIFICATION_ID)
        } catch (_: Exception) {
        }
    }

    private fun postUpdateCompletedNotification(
        appContext: Context,
        installedVersionName: String,
        launchIntent: Intent
    ) {
        try {
            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            ensureUpdateChannel(nm)
            val pi = PendingIntent.getActivity(
                appContext,
                UPDATE_NOTIFICATION_ID,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(appContext, UPDATE_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Updated to v$installedVersionName")
                .setContentText("Tap to open Drive Test LTE 5G.")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            nm.notify(UPDATE_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "Failed posting update completed notification", e)
        }
    }

    private fun abandonSessionSafely(appContext: Context, sessionId: Int) {
        if (sessionId == PackageInstaller.SessionInfo.INVALID_ID) return
        try {
            appContext.packageManager.packageInstaller.abandonSession(sessionId)
        } catch (_: Exception) {
        }
    }
}
