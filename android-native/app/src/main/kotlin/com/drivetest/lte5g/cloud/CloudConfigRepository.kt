package com.drivetest.lte5g.cloud

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.drivetest.lte5g.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
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
    val firebaseDbUrl: String,
    val firebaseAuthToken: String,
    val liveTelemetryEnabled: Boolean = true,
    val cloudUploadUrl: String,
    val autoUploadOnWifi: Boolean = true
)

object CloudConfigRepository {
    private const val TAG = "CloudConfigRepo"
    private const val PREF_NAME = "dt_cloud_config"

    private const val KEY_VEHICLE_ID = "cloud_vehicle_id"
    private const val KEY_DEVICE_INSTALL_ID = "cloud_device_install_id"
    private const val KEY_UPLOAD_STATUS_PREFIX = "upload_status_session_"

    private var prefs: SharedPreferences? = null

    private val _config = MutableStateFlow(
        CloudConfig(
            vehicleId = "DT-01",
            firebaseDbUrl = BuildConfig.FIREBASE_DB_URL,
            firebaseAuthToken = BuildConfig.FIREBASE_AUTH_TOKEN,
            liveTelemetryEnabled = true,
            cloudUploadUrl = BuildConfig.CLOUD_UPLOAD_URL,
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

    fun getDeviceInstallId(): String {
        val sp = prefs ?: return UUID.randomUUID().toString()
        var id = sp.getString(KEY_DEVICE_INSTALL_ID, null)
        if (id.isNullOrBlank()) {
            id = UUID.randomUUID().toString()
            sp.edit().putString(KEY_DEVICE_INSTALL_ID, id).apply()
        }
        return id
    }

    private fun loadConfig() {
        val sp = prefs ?: return
        val vId = sp.getString(KEY_VEHICLE_ID, null) ?: "DT-01"

        val defaultFbUrl = BuildConfig.FIREBASE_DB_URL.ifBlank {
            "https://drivetest-telemetry-default-rtdb.asia-southeast1.firebasedatabase.app"
        }
        val fbToken = BuildConfig.FIREBASE_AUTH_TOKEN
        val uploadUrl = BuildConfig.CLOUD_UPLOAD_URL

        _config.value = CloudConfig(
            vehicleId = vId,
            firebaseDbUrl = defaultFbUrl,
            firebaseAuthToken = fbToken,
            liveTelemetryEnabled = true,
            cloudUploadUrl = uploadUrl,
            autoUploadOnWifi = true
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

    /**
     * Query existing vehicle IDs from Firebase Realtime Database
     * and auto-generate the next sequential ID: DT-01, DT-02, DT-03...
     */
    suspend fun queryNextAvailableVehicleId(): Result<String> = withContext(Dispatchers.IO) {
        val fbUrl = _config.value.firebaseDbUrl.trimEnd('/')
        if (fbUrl.isBlank()) return@withContext Result.success("DT-01")

        try {
            val authParam = if (_config.value.firebaseAuthToken.isNotBlank()) "?auth=${_config.value.firebaseAuthToken}" else ""
            val url = URL("$fbUrl/fleet/vehicles.json$authParam")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6000
                readTimeout = 6000
            }

            if (conn.responseCode in 200..299) {
                val responseText = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()

                if (responseText.isBlank() || responseText.trim() == "null") {
                    return@withContext Result.success("DT-01")
                }

                val json = JSONObject(responseText)
                var maxNum = 0
                val pattern = Regex("""DT-(\d+)""", RegexOption.IGNORE_CASE)

                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val match = pattern.matchEntire(key)
                    if (match != null) {
                        val num = match.groupValues[1].toIntOrNull() ?: 0
                        if (num > maxNum) maxNum = num
                    }
                }

                val nextId = String.format(Locale.US, "DT-%02d", maxNum + 1)
                Result.success(nextId)
            } else {
                conn.disconnect()
                Result.success("DT-01")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query Firebase for sequential vehicle ID: ${e.message}")
            Result.success("DT-01")
        }
    }

    /**
     * Validate whether candidate Vehicle ID is unique across Firebase fleet.
     * Returns true if available, false if already in use by another vehicle.
     */
    suspend fun validateVehicleId(candidateId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        val normalized = candidateId.trim().uppercase(Locale.US)
        if (normalized.isBlank()) return@withContext Result.success(false)

        val currentId = _config.value.vehicleId
        if (normalized == currentId) {
            // Already owned by this local device
            return@withContext Result.success(true)
        }

        val fbUrl = _config.value.firebaseDbUrl.trimEnd('/')
        if (fbUrl.isBlank()) return@withContext Result.success(true)

        try {
            val authParam = if (_config.value.firebaseAuthToken.isNotBlank()) "?auth=${_config.value.firebaseAuthToken}" else ""
            val url = URL("$fbUrl/fleet/vehicles/$normalized.json$authParam")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
            }

            if (conn.responseCode in 200..299) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val isAvailable = body.isBlank() || body.trim() == "null"
                Result.success(isAvailable)
            } else {
                conn.disconnect()
                Result.success(true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not reach Firebase to validate vehicle ID: ${e.message}")
            Result.success(true) // Allow if offline
        }
    }

    /**
     * Set and persist Vehicle ID locally and claim it in Firebase.
     */
    suspend fun saveVehicleId(newId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val normalized = newId.trim().uppercase(Locale.US)
        if (normalized.isBlank()) return@withContext Result.failure(IllegalArgumentException("Vehicle ID cannot be blank"))

        val validation = validateVehicleId(normalized)
        if (validation.isSuccess && !validation.getOrDefault(true)) {
            return@withContext Result.failure(IllegalStateException("This Vehicle ID is already in use"))
        }

        val current = _config.value
        _config.value = current.copy(vehicleId = normalized)
        prefs?.edit()?.apply {
            putString(KEY_VEHICLE_ID, normalized)
            apply()
        }

        // Register / claim vehicle in Firebase Realtime Database
        try {
            val fbUrl = current.firebaseDbUrl.trimEnd('/')
            if (fbUrl.isNotBlank()) {
                val authParam = if (current.firebaseAuthToken.isNotBlank()) "?auth=${current.firebaseAuthToken}" else ""
                val url = URL("$fbUrl/fleet/vehicles/$normalized/vehicleId.json$authParam")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "PUT"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    connectTimeout = 5000
                    readTimeout = 5000
                }
                conn.outputStream.use { os ->
                    os.write("\"$normalized\"".toByteArray(Charsets.UTF_8))
                }
                conn.responseCode
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to sync claimed vehicle ID to Firebase: ${e.message}")
        }

        Result.success(Unit)
    }

    fun setVehicleIdImmediate(id: String) {
        val normalized = id.trim().uppercase(Locale.US)
        _config.value = _config.value.copy(vehicleId = normalized)
        prefs?.edit()?.putString(KEY_VEHICLE_ID, normalized)?.apply()
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
            firebaseDbUrl = "",
            firebaseAuthToken = "",
            liveTelemetryEnabled = true,
            cloudUploadUrl = "",
            autoUploadOnWifi = true
        )
        _sessionStatuses.value = emptyMap()
    }
}
