package com.drivetest.lte5g.updater

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
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
    data class Error(val message: String) : OtaState
}

/**
 * Reads release metadata (version, title, notes) from GitHub Releases so the UI can show
 * "what is new". Installing is done by Google Play In-App Updates (see PlayUpdateManager);
 * this object never downloads or installs an APK.
 */
object OtaUpdateManager {
    private const val TAG = "OtaUpdateManager"
    private const val GITHUB_REPO = "EEMEEMMEEx/Drive-Test-5G-LTE-RF"
    private const val RELEASES_API_URL = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"

    private val _state = MutableStateFlow<OtaState>(OtaState.Idle)
    val state: StateFlow<OtaState> = _state.asStateFlow()

    private val _lastCheckedTimestamp = MutableStateFlow<Long?>(null)
    val lastCheckedTimestamp: StateFlow<Long?> = _lastCheckedTimestamp.asStateFlow()

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
                val errorMsg = "Server returned HTTP $responseCode"
                _state.value = OtaState.Error(errorMsg)
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            _lastCheckedTimestamp.value = System.currentTimeMillis()
            Log.e(TAG, "Failed to check for updates", e)
            val errorMsg = e.localizedMessage ?: "Failed to connect to update server"
            _state.value = OtaState.Error(errorMsg)
            Result.failure(e)
        }
    }
}