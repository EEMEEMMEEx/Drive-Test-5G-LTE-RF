package com.drivetest.lte5g.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

data class ReleaseInfo(
    val tagName: String,
    val versionName: String,
    val releaseTitle: String,
    val releaseNotes: String,
    val downloadUrl: String,
    val apkSize: Long
)

enum class OtaErrorKind {
    GENERAL,
    NETWORK,
    INSTALL_PERMISSION
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
    data class Error(val message: String, val kind: OtaErrorKind = OtaErrorKind.GENERAL) : OtaState
}

/**
 * Reads release metadata from GitHub Releases and handles sideloaded APK download/install
 * when not installed from Google Play.
 */
object OtaUpdateManager {
    private const val TAG = "OtaUpdateManager"
    private const val PREFS_NAME = "ota_updater_prefs"
    private const val KEY_AUTO_UPDATE = "auto_update_enabled"
    private const val GITHUB_REPO = "EEMEEMMEEx/Drive-Test-5G-LTE-RF"
    private const val RELEASES_API_URL = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null

    private val _state = MutableStateFlow<OtaState>(OtaState.Idle)
    val state: StateFlow<OtaState> = _state.asStateFlow()

    private val _lastCheckedTimestamp = MutableStateFlow<Long?>(null)
    val lastCheckedTimestamp: StateFlow<Long?> = _lastCheckedTimestamp.asStateFlow()

    private val _autoUpdateEnabled = MutableStateFlow(true)
    val autoUpdateEnabled: StateFlow<Boolean> = _autoUpdateEnabled.asStateFlow()

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _autoUpdateEnabled.value = prefs.getBoolean(KEY_AUTO_UPDATE, true)
    }

    fun setAutoUpdateEnabled(enabled: Boolean, context: Context? = null) {
        _autoUpdateEnabled.value = enabled
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()
            ?.putBoolean(KEY_AUTO_UPDATE, enabled)
            ?.apply()
    }

    fun resetState() {
        downloadJob?.cancel()
        downloadJob = null
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
        var apkSize: Long = 0L

        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.optString("name", "")
            if (name.endsWith(".apk", ignoreCase = true)) {
                downloadUrl = asset.optString("browser_download_url", "")
                apkSize = asset.optLong("size", 0L)
                break
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
            apkSize = apkSize
        )
    }

    /**
     * Checks for updates from GitHub Releases API (metadata only, no download)
     */
    suspend fun checkForUpdates(currentVersion: String): Result<ReleaseInfo?> = withContext(Dispatchers.IO) {
        _state.value = OtaState.Checking
        try {
            val url = URL(RELEASES_API_URL)
            val connection = (url.openConnection() as HttpURLConnection).apply {
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
        }
    }

    fun startDownload(context: Context, releaseInfo: ReleaseInfo) {
        if (downloadJob?.isActive == true) return
        val appContext = context.applicationContext
        downloadJob = scope.launch {
            _state.value = OtaState.Downloading(releaseInfo, 0, 0L, releaseInfo.apkSize)
            var conn: HttpURLConnection? = null
            try {
                val updatesDir = File(appContext.cacheDir, "updates").apply { if (!exists()) mkdirs() }
                val targetFile = File(updatesDir, "update_${releaseInfo.versionName}.apk")
                val tmpFile = File(updatesDir, "update_${releaseInfo.versionName}.apk.part")

                conn = (URL(releaseInfo.downloadUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15000
                    readTimeout = 15000
                    instanceFollowRedirects = true
                }
                val code = conn.responseCode
                if (code !in 200..299) {
                    _state.value = OtaState.Error("Download failed (HTTP $code).", OtaErrorKind.GENERAL)
                    return@launch
                }
                val totalBytes = conn.contentLengthLong.takeIf { it > 0 } ?: releaseInfo.apkSize
                var downloaded = 0L
                var lastEmitMs = 0L

                conn.inputStream.use { input ->
                    FileOutputStream(tmpFile).use { output ->
                        val buffer = ByteArray(8192)
                        while (isActive) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            val now = System.currentTimeMillis()
                            if (now - lastEmitMs >= 150L) {
                                lastEmitMs = now
                                val pct = if (totalBytes > 0) ((downloaded * 100L) / totalBytes).toInt().coerceIn(0, 100) else 0
                                _state.value = OtaState.Downloading(releaseInfo, pct, downloaded, totalBytes)
                            }
                        }
                    }
                }
                if (!isActive) {
                    tmpFile.delete()
                    return@launch
                }
                if (targetFile.exists()) targetFile.delete()
                if (!tmpFile.renameTo(targetFile)) {
                    tmpFile.copyTo(targetFile, overwrite = true)
                    tmpFile.delete()
                }
                _state.value = OtaState.ReadyToInstall(releaseInfo, targetFile.absolutePath)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Log.e(TAG, "APK download failed", e)
                val (msg, kind) = when (e) {
                    is UnknownHostException, is ConnectException, is SocketTimeoutException ->
                        "Network interrupted while downloading update." to OtaErrorKind.NETWORK
                    else ->
                        (e.localizedMessage ?: "Failed to download update package.") to OtaErrorKind.GENERAL
                }
                _state.value = OtaState.Error(msg, kind)
            } finally {
                conn?.disconnect()
            }
        }
    }

    fun canInstallPackages(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun openInstallPermissionSettings(context: Context) {
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

    fun installDownloadedApk(context: Context, releaseInfo: ReleaseInfo, apkFilePath: String) {
        if (!canInstallPackages(context)) {
            _state.value = OtaState.Error(
                "Allow 'Install unknown apps' permission to install v${releaseInfo.versionName}.",
                OtaErrorKind.INSTALL_PERMISSION
            )
            return
        }
        val apkFile = File(apkFilePath)
        if (!apkFile.exists()) {
            _state.value = OtaState.Error("Downloaded APK file was removed. Please download again.", OtaErrorKind.GENERAL)
            return
        }
        try {
            _state.value = OtaState.Installing(releaseInfo)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", apkFile)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed launching APK installer", e)
            _state.value = OtaState.Error(
                e.localizedMessage ?: "Unable to launch package installer.",
                OtaErrorKind.GENERAL
            )
        }
    }
}