package com.drivetest.lte5g.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class ReleaseInfo(
    val tagName: String,
    val versionName: String,
    val releaseTitle: String,
    val releaseNotes: String,
    val downloadUrl: String,
    val apkSize: Long
)

sealed interface OtaState {
    object Idle : OtaState
    object Checking : OtaState
    data class UpdateAvailable(val releaseInfo: ReleaseInfo) : OtaState
    data class UpToDate(val version: String) : OtaState
    data class Downloading(val progress: Float, val downloadedBytes: Long, val totalBytes: Long) : OtaState
    data class ReadyToInstall(val apkFile: File) : OtaState
    data class Error(val message: String) : OtaState
}

object OtaUpdateManager {
    private const val TAG = "OtaUpdateManager"
    private const val GITHUB_REPO = "EEMEEMMEEx/Drive-Test-5G-LTE-RF"
    private const val RELEASES_API_URL = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"

    private val _state = MutableStateFlow<OtaState>(OtaState.Idle)
    val state: StateFlow<OtaState> = _state.asStateFlow()

    fun resetState() {
        _state.value = OtaState.Idle
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
     * Checks for updates from GitHub Releases API
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
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                val releaseInfo = parseReleaseJson(responseText)

                if (releaseInfo != null) {
                    if (compareVersions(releaseInfo.versionName, currentVersion) > 0) {
                        _state.value = OtaState.UpdateAvailable(releaseInfo)
                        Result.success(releaseInfo)
                    } else {
                        _state.value = OtaState.UpToDate(currentVersion)
                        Result.success(null)
                    }
                } else {
                    _state.value = OtaState.UpToDate(currentVersion)
                    Result.success(null)
                }
            } else {
                val errorMsg = "Server returned HTTP $responseCode"
                _state.value = OtaState.Error(errorMsg)
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check for updates", e)
            val errorMsg = e.localizedMessage ?: "Failed to connect to update server"
            _state.value = OtaState.Error(errorMsg)
            Result.failure(e)
        }
    }

    /**
     * Downloads the APK file into app's cache directory with progress reporting
     */
    suspend fun downloadApk(
        context: Context,
        releaseInfo: ReleaseInfo
    ): Result<File> = withContext(Dispatchers.IO) {
        val totalBytes = releaseInfo.apkSize
        _state.value = OtaState.Downloading(0f, 0L, totalBytes)

        try {
            val updateDir = File(context.cacheDir, "updates")
            if (!updateDir.exists()) {
                updateDir.mkdirs()
            }

            val apkFile = File(updateDir, "update_${releaseInfo.versionName}.apk")
            if (apkFile.exists()) {
                apkFile.delete()
            }

            val url = URL(releaseInfo.downloadUrl)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 15000
                readTimeout = 30000
                setRequestProperty("User-Agent", "DriveTest-Android-Updater")
            }

            // Handle manual redirect for GitHub Releases CDN (AWS S3)
            var actualConnection = connection
            var redirectCount = 0
            while (actualConnection.responseCode in listOf(
                    HttpURLConnection.HTTP_MOVED_PERM,
                    HttpURLConnection.HTTP_MOVED_TEMP,
                    HttpURLConnection.HTTP_SEE_OTHER,
                    307, 308
                ) && redirectCount < 5
            ) {
                val newUrl = actualConnection.getHeaderField("Location")
                actualConnection.disconnect()
                actualConnection = (URL(newUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 30000
                    setRequestProperty("User-Agent", "DriveTest-Android-Updater")
                }
                redirectCount++
            }

            val contentLength = actualConnection.contentLengthLong.takeIf { it > 0 } ?: totalBytes
            actualConnection.inputStream.use { input ->
                FileOutputStream(apkFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalRead: Long = 0L

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        val progress = if (contentLength > 0) (totalRead.toFloat() / contentLength.toFloat()).coerceIn(0f, 1f) else 0f
                        _state.value = OtaState.Downloading(progress, totalRead, contentLength)
                    }
                    output.flush()
                }
            }

            _state.value = OtaState.ReadyToInstall(apkFile)
            Result.success(apkFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download update APK", e)
            val errorMsg = e.localizedMessage ?: "Download failed"
            _state.value = OtaState.Error(errorMsg)
            Result.failure(e)
        }
    }

    /**
     * Checks if the app has permission to install packages (Android 8.0+)
     */
    fun canRequestPackageInstalls(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    /**
     * Opens Settings screen to allow installing unknown apps
     */
    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open unknown app sources settings", e)
            }
        }
    }

    /**
     * Launches Android PackageInstaller using FileProvider
     */
    fun installApk(context: Context, apkFile: File): Result<Unit> {
        return try {
            if (!apkFile.exists() || apkFile.length() == 0L) {
                return Result.failure(Exception("APK file is invalid or empty"))
            }

            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                apkFile
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(intent)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch package installer", e)
            Result.failure(e)
        }
    }
}
