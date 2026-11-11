package com.drivetest.lte5g.cloud

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

enum class CloudUploadStatus(val label: String) {
    NONE("Not Uploaded"),
    PENDING_WIFI("Waiting for Wi-Fi"),
    UPLOADING("Uploading..."),
    UPLOADED("Uploaded to Drive"),
    FAILED("Upload Failed")
}

data class CloudConfig(
    val vehicleId: String,
    val vehicleName: String,
    val firebaseDbUrl: String,
    val firebaseAuthToken: String,
    val liveTelemetryEnabled: Boolean,
    val googleDriveAccessToken: String,
    val googleDriveFolderId: String,
    val autoUploadOnWifi: Boolean
)

object CloudConfigRepository {
    private const val PREF_NAME = "dt_cloud_config"

    private const val KEY_VEHICLE_ID = "cloud_vehicle_id"
    private const val KEY_VEHICLE_NAME = "cloud_vehicle_name"
    private const val KEY_FIREBASE_DB_URL = "cloud_firebase_db_url"
    private const val KEY_FIREBASE_AUTH_TOKEN = "cloud_firebase_auth_token"
    private const val KEY_LIVE_TELEMETRY_ENABLED = "cloud_live_telemetry_enabled"
    private const val KEY_GDRIVE_ACCESS_TOKEN = "cloud_gdrive_access_token"
    private const val KEY_GDRIVE_FOLDER_ID = "cloud_gdrive_folder_id"
    private const val KEY_AUTO_UPLOAD_ON_WIFI = "cloud_auto_upload_on_wifi"
    private const val KEY_UPLOAD_STATUS_PREFIX = "upload_status_session_"

    private var prefs: SharedPreferences? = null

    private val _config = MutableStateFlow(
        CloudConfig(
            vehicleId = "DT-01",
            vehicleName = "DT-Unit-01 (Field Test)",
            firebaseDbUrl = "",
            firebaseAuthToken = "",
            liveTelemetryEnabled = false,
            googleDriveAccessToken = "",
            googleDriveFolderId = "",
            autoUploadOnWifi = true
        )
    )
    val config: StateFlow<CloudConfig> = _config.asStateFlow()

    private val _sessionStatuses = MutableStateFlow<Map<Long, CloudUploadStatus>>(emptyMap())
    val sessionStatuses: StateFlow<Map<Long, CloudUploadStatus>> = _sessionStatuses.asStateFlow()

    fun init(context: Context) {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        init(sp)
    }

    fun init(sharedPreferences: SharedPreferences) {
        prefs = sharedPreferences
        loadConfig()
    }

    private fun loadConfig() {
        val sp = prefs ?: return
        var vId = sp.getString(KEY_VEHICLE_ID, null)
        if (vId.isNullOrBlank()) {
            vId = "DT-" + UUID.randomUUID().toString().substring(0, 6).uppercase()
            sp.edit().putString(KEY_VEHICLE_ID, vId).apply()
        }
        val vName = sp.getString(KEY_VEHICLE_NAME, "DT-Unit-01 (Field Test)") ?: "DT-Unit-01 (Field Test)"
        val fbUrl = sp.getString(KEY_FIREBASE_DB_URL, "") ?: ""
        val fbToken = sp.getString(KEY_FIREBASE_AUTH_TOKEN, "") ?: ""
        val liveEnabled = sp.getBoolean(KEY_LIVE_TELEMETRY_ENABLED, fbUrl.isNotBlank())
        val driveToken = sp.getString(KEY_GDRIVE_ACCESS_TOKEN, "") ?: ""
        val driveFolder = sp.getString(KEY_GDRIVE_FOLDER_ID, "") ?: ""
        val autoWifi = sp.getBoolean(KEY_AUTO_UPLOAD_ON_WIFI, true)

        _config.value = CloudConfig(
            vehicleId = vId,
            vehicleName = vName,
            firebaseDbUrl = fbUrl,
            firebaseAuthToken = fbToken,
            liveTelemetryEnabled = liveEnabled,
            googleDriveAccessToken = driveToken,
            googleDriveFolderId = driveFolder,
            autoUploadOnWifi = autoWifi
        )

        // Load all session statuses
        val statusMap = mutableMapOf<Long, CloudUploadStatus>()
        sp.all.forEach { (key, value) ->
            if (key.startsWith(KEY_UPLOAD_STATUS_PREFIX) && value is String) {
                val sessionIdStr = key.removePrefix(KEY_UPLOAD_STATUS_PREFIX)
                val sessionId = sessionIdStr.toLongOrNull()
                if (sessionId != null) {
                    try {
                        statusMap[sessionId] = CloudUploadStatus.valueOf(value)
                    } catch (_: Exception) {}
                }
            }
        }
        _sessionStatuses.value = statusMap
    }

    fun updateVehicleInfo(vehicleId: String, vehicleName: String) {
        val current = _config.value
        val updated = current.copy(
            vehicleId = vehicleId.trim().ifBlank { current.vehicleId },
            vehicleName = vehicleName.trim().ifBlank { current.vehicleName }
        )
        _config.value = updated
        prefs?.edit()?.apply {
            putString(KEY_VEHICLE_ID, updated.vehicleId)
            putString(KEY_VEHICLE_NAME, updated.vehicleName)
            apply()
        }
    }

    fun updateFirebaseConfig(dbUrl: String, authToken: String, liveEnabled: Boolean) {
        val current = _config.value
        val cleanUrl = dbUrl.trim().trimEnd('/')
        val updated = current.copy(
            firebaseDbUrl = cleanUrl,
            firebaseAuthToken = authToken.trim(),
            liveTelemetryEnabled = liveEnabled
        )
        _config.value = updated
        prefs?.edit()?.apply {
            putString(KEY_FIREBASE_DB_URL, updated.firebaseDbUrl)
            putString(KEY_FIREBASE_AUTH_TOKEN, updated.firebaseAuthToken)
            putBoolean(KEY_LIVE_TELEMETRY_ENABLED, updated.liveTelemetryEnabled)
            apply()
        }
    }

    fun updateGoogleDriveConfig(accessToken: String, folderId: String, autoUploadOnWifi: Boolean) {
        val current = _config.value
        val updated = current.copy(
            googleDriveAccessToken = accessToken.trim(),
            googleDriveFolderId = folderId.trim(),
            autoUploadOnWifi = autoUploadOnWifi
        )
        _config.value = updated
        prefs?.edit()?.apply {
            putString(KEY_GDRIVE_ACCESS_TOKEN, updated.googleDriveAccessToken)
            putString(KEY_GDRIVE_FOLDER_ID, updated.googleDriveFolderId)
            putBoolean(KEY_AUTO_UPLOAD_ON_WIFI, updated.autoUploadOnWifi)
            apply()
        }
    }

    fun setSessionUploadStatus(sessionId: Long, status: CloudUploadStatus) {
        val currentMap = _sessionStatuses.value.toMutableMap()
        currentMap[sessionId] = status
        _sessionStatuses.value = currentMap

        prefs?.edit()?.apply {
            putString(KEY_UPLOAD_STATUS_PREFIX + sessionId, status.name)
            apply()
        }
    }

    fun getSessionUploadStatus(sessionId: Long): CloudUploadStatus {
        return _sessionStatuses.value[sessionId] ?: CloudUploadStatus.NONE
    }

    fun resetForTesting() {
        prefs = null
        _config.value = CloudConfig(
            vehicleId = "DT-TEST",
            vehicleName = "Test Unit",
            firebaseDbUrl = "",
            firebaseAuthToken = "",
            liveTelemetryEnabled = false,
            googleDriveAccessToken = "",
            googleDriveFolderId = "",
            autoUploadOnWifi = true
        )
        _sessionStatuses.value = emptyMap()
    }
}
