package com.drivetest.lte5g.updater

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import kotlin.math.abs

data class ReleaseInfo(
    val tagName: String,
    val versionName: String,
    val releaseTitle: String,
    val releaseNotes: String,
    val downloadUrl: String,
    val apkSize: Long
)

data class ApkValidationInfo(
    val isValid: Boolean,
    val errorMessage: String? = null,
    val errorDetails: String? = null,
    val installedSigner: String? = null,
    val installedFingerprint: String? = null,
    val updateSigner: String? = null,
    val updateFingerprint: String? = null,
    val currentVersionCode: Long = 0L,
    val updateVersionCode: Long = 0L,
    val currentPackage: String = "",
    val updatePackage: String = "",
    val isSignatureMismatch: Boolean = false,
    val isDowngrade: Boolean = false,
    val isCorrupt: Boolean = false,
    val canInstallPackages: Boolean = true
)

sealed interface OtaState {
    object Idle : OtaState
    object Checking : OtaState
    data class UpdateAvailable(val releaseInfo: ReleaseInfo) : OtaState
    data class UpToDate(val version: String) : OtaState
    data class Downloading(val progress: Float, val downloadedBytes: Long, val totalBytes: Long) : OtaState
    data class ReadyToInstall(
        val apkFile: File,
        val releaseInfo: ReleaseInfo,
        val validation: ApkValidationInfo
    ) : OtaState
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
     * Automatic OTA Update Flow:
     * 1. Checks GitHub Releases in background on app startup.
     * 2. If a newer release is found:
     *    - If valid APK is already cached locally, immediately transitions to ReadyToInstall.
     *    - Otherwise, automatically downloads the APK in background.
     * 3. Validates package name, signature, and versionCode.
     * 4. Transitions state to ReadyToInstall so UI can prompt user with confirmation dialog.
     */
    suspend fun startAutoUpdateFlow(
        context: Context,
        currentVersion: String
    ): Result<File?> = withContext(Dispatchers.IO) {
        val currentState = _state.value
        if (currentState is OtaState.Downloading) {
            return@withContext Result.success(null)
        }
        if (currentState is OtaState.ReadyToInstall && currentState.validation.isValid) {
            return@withContext Result.success(currentState.apkFile)
        }

        try {
            val checkResult = checkForUpdates(currentVersion)
            val release = checkResult.getOrNull()
            if (release != null) {
                // Check if already downloaded and valid
                val updateDir = File(context.cacheDir, "updates")
                val targetApk = File(updateDir, "update_${release.versionName}.apk")
                if (targetApk.exists() && targetApk.length() > 0) {
                    val validation = validateApkFile(context, targetApk, release.apkSize)
                    if (validation.isValid) {
                        _state.value = OtaState.ReadyToInstall(targetApk, release, validation)
                        return@withContext Result.success(targetApk)
                    }
                }

                // Not yet downloaded or corrupted cache: download in background
                val downloadRes = downloadApk(context, release)
                return@withContext downloadRes.map { it }
            }
            Result.success(null)
        } catch (e: Exception) {
            Log.e(TAG, "Automatic update flow error", e)
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

            // Immediately validate the downloaded file
            val validation = validateApkFile(context, apkFile, releaseInfo.apkSize)
            _state.value = OtaState.ReadyToInstall(apkFile, releaseInfo, validation)
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
     * Extract certificate Subject DN and SHA-256 fingerprint from signature bytes
     */
    fun extractCertInfo(signatureBytes: ByteArray): Pair<String, String> {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(signatureBytes)
        val fingerprint = digest.joinToString(":") { "%02X".format(it) }

        val subject = try {
            val certFactory = CertificateFactory.getInstance("X.509")
            val cert = certFactory.generateCertificate(ByteArrayInputStream(signatureBytes)) as? X509Certificate
            cert?.subjectDN?.name ?: "Unknown Signer"
        } catch (_: Exception) {
            "Unknown Signer"
        }
        return Pair(subject, fingerprint)
    }

    /**
     * Get signing certificate info of the currently installed app
     */
    fun getInstalledSignerInfo(context: Context): Pair<String, String>? {
        return try {
            val pm = context.packageManager
            val sigBytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
            } else {
                @Suppress("DEPRECATION")
                val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                info.signatures?.firstOrNull()?.toByteArray()
            }
            sigBytes?.let { extractCertInfo(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed getting installed signature", e)
            null
        }
    }

    /**
     * Get signing certificate info from an uninstalled APK archive
     */
    fun getArchiveSignerInfo(context: Context, apkFile: File): Pair<String, String>? {
        return try {
            val pm = context.packageManager
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_SIGNATURES
            }
            val archiveInfo = pm.getPackageArchiveInfo(apkFile.absolutePath, flags) ?: return null
            val sigBytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                archiveInfo.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
            } else {
                @Suppress("DEPRECATION")
                archiveInfo.signatures?.firstOrNull()?.toByteArray()
            }
            sigBytes?.let { extractCertInfo(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed getting archive signature", e)
            null
        }
    }

    /**
     * Validates APK integrity, package name, version code, and Keystore signing match
     */
    fun validateApkFile(
        context: Context,
        apkFile: File,
        expectedSize: Long = 0L
    ): ApkValidationInfo {
        if (!apkFile.exists() || apkFile.length() == 0L) {
            return ApkValidationInfo(
                isValid = false,
                isCorrupt = true,
                errorMessage = "ไม่พบไฟล์ APK หรือไฟล์มีขนาด 0 bytes",
                errorDetails = "ตำแหน่งไฟล์: ${apkFile.absolutePath}"
            )
        }

        // Check file size if expectedSize is known (> 1MB)
        if (expectedSize > 1024 * 1024 && abs(apkFile.length() - expectedSize) > 65536) {
            return ApkValidationInfo(
                isValid = false,
                isCorrupt = true,
                errorMessage = "ไฟล์ APK ดาวน์โหลดมาไม่สมบูรณ์ (ขนาดไฟล์ไม่ตรงกับเซิร์ฟเวอร์)",
                errorDetails = "ดาวน์โหลดได้: ${apkFile.length() / (1024 * 1024)} MB (ขนาดต้นทาง: ${expectedSize / (1024 * 1024)} MB)"
            )
        }

        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val archiveInfo = pm.getPackageArchiveInfo(apkFile.absolutePath, flags)
            ?: return ApkValidationInfo(
                isValid = false,
                isCorrupt = true,
                errorMessage = "ไฟล์ APK เสียหายหรือไม่สามารถอ่านข้อมูลได้ (Parse Error)",
                errorDetails = "Android Package Manager ไม่สามารถอ่านโครงสร้างไฟล์ได้ ไฟล์อาจเสียหายระหว่างดาวน์โหลด"
            )

        // Check package name
        val updatePkg = archiveInfo.packageName.orEmpty()
        if (updatePkg != context.packageName) {
            return ApkValidationInfo(
                isValid = false,
                errorMessage = "ชื่อแพ็กเกจ (applicationId) ไม่ตรงกับแอปเดิม",
                errorDetails = "แอปในเครื่อง: ${context.packageName}\nไฟล์อัปเดต: $updatePkg",
                currentPackage = context.packageName,
                updatePackage = updatePkg
            )
        }

        // Check versionCode
        val currentInfo = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, 0)
            }
        } catch (_: Exception) {
            null
        }

        val currentCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            currentInfo?.longVersionCode ?: 0L
        } else {
            @Suppress("DEPRECATION")
            currentInfo?.versionCode?.toLong() ?: 0L
        }

        val updateCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archiveInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            archiveInfo.versionCode.toLong()
        }

        if (currentCode > 0 && updateCode <= currentCode) {
            return ApkValidationInfo(
                isValid = false,
                isDowngrade = true,
                errorMessage = "เวอร์ชันของไฟล์ใหม่ไม่ได้สูงกว่าเวอร์ชันปัจจุบัน",
                errorDetails = "เวอร์ชันในเครื่อง: code $currentCode (${currentInfo?.versionName})\nไฟล์อัปเดต: code $updateCode (${archiveInfo.versionName})\nAndroid ไม่อนุญาตให้ติดตั้งทับเวอร์ชันเดิมหรือเก่ากว่า",
                currentVersionCode = currentCode,
                updateVersionCode = updateCode
            )
        }

        // Check signing certificates / Keystore match
        val installedSignerPair = getInstalledSignerInfo(context)
        val updateSignerPair = getArchiveSignerInfo(context, apkFile)

        if (installedSignerPair != null && updateSignerPair != null) {
            val (installedSigner, installedFp) = installedSignerPair
            val (updateSigner, updateFp) = updateSignerPair

            if (installedFp != updateFp) {
                return ApkValidationInfo(
                    isValid = false,
                    isSignatureMismatch = true,
                    errorMessage = "ลายมือชื่อ Keystore ไม่ตรงกับแอปที่ติดตั้งอยู่ (Signature Mismatch)",
                    errorDetails = "• แอปในเครื่อง: $installedSigner\n  (SHA256: ${installedFp.take(23)}...)\n• ไฟล์อัปเดต: $updateSigner\n  (SHA256: ${updateFp.take(23)}...)\n\nสาเหตุ: แอปในเครื่องลงนามด้วย Keystore คนละตัวกับไฟล์อัปเดต (เช่น Debug vs Release Keystore)\nAndroid ไม่อนุญาตให้อัปเดตข้าม Keystore เพื่อความปลอดภัย",
                    installedSigner = installedSigner,
                    installedFingerprint = installedFp,
                    updateSigner = updateSigner,
                    updateFingerprint = updateFp
                )
            }
        }

        // Check install permission (REQUEST_INSTALL_PACKAGES)
        val canInstall = canRequestPackageInstalls(context)

        return ApkValidationInfo(
            isValid = true,
            canInstallPackages = canInstall,
            currentVersionCode = currentCode,
            updateVersionCode = updateCode,
            currentPackage = context.packageName,
            updatePackage = updatePkg,
            installedSigner = installedSignerPair?.first,
            installedFingerprint = installedSignerPair?.second,
            updateSigner = updateSignerPair?.first,
            updateFingerprint = updateSignerPair?.second
        )
    }

    /**
     * Revalidate downloaded APK in current state (e.g. after returning from settings)
     */
    fun revalidate(context: Context) {
        val currentState = _state.value
        if (currentState is OtaState.ReadyToInstall) {
            val newVal = validateApkFile(context, currentState.apkFile, currentState.releaseInfo.apkSize)
            _state.value = OtaState.ReadyToInstall(currentState.apkFile, currentState.releaseInfo, newVal)
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

            if (!canRequestPackageInstalls(context)) {
                openInstallPermissionSettings(context)
                return Result.failure(Exception("จำเป็นต้องเปิดสิทธิ์ 'อนุญาตให้ติดตั้งแอปที่ไม่รู้จัก' สำหรับ Drive Test ในการตั้งค่า Android ก่อน"))
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
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }

            // Explicitly grant read uri permission to all potential installer handlers
            val resInfoList = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            for (resolveInfo in resInfoList) {
                context.grantUriPermission(resolveInfo.activityInfo.packageName, apkUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            context.startActivity(intent)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch package installer", e)
            Result.failure(e)
        }
    }

    /**
     * Share or export the downloaded APK file
     */
    fun shareApkFile(context: Context, apkFile: File) {
        try {
            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                apkFile
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.android.package-archive"
                putExtra(Intent.EXTRA_STREAM, apkUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(intent, "เปิดหรือส่งต่อไฟล์ APK เพื่อติดตั้ง").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to share APK", e)
        }
    }
}
