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

data class VehicleIdClaimResult(
    val requestedId: String,
    val assignedId: String,
    val wasReassigned: Boolean,
    val syncedToCloud: Boolean = true
) {
    val reassignmentNotice: String?
        get() = if (wasReassigned) "$requestedId was in use, assigned $assignedId" else null
}

internal data class FirebaseGetResponse(
    val responseCode: Int,
    val body: String,
    val etag: String?
)

internal interface FleetRegistryTransport {
    fun getWithEtag(urlString: String): FirebaseGetResponse
    fun putWithIfMatch(urlString: String, body: String, etag: String?): Int
}

internal object HttpFleetRegistryTransport : FleetRegistryTransport {
    override fun getWithEtag(urlString: String): FirebaseGetResponse {
        val url = URL(urlString)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("X-Firebase-ETag", "true")
            connectTimeout = 6000
            readTimeout = 6000
        }
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            val etag = conn.getHeaderField("ETag")
            FirebaseGetResponse(code, body, etag)
        } finally {
            conn.disconnect()
        }
    }

    override fun putWithIfMatch(urlString: String, body: String, etag: String?): Int {
        val url = URL(urlString)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            if (!etag.isNullOrBlank()) {
                setRequestProperty("If-Match", etag)
            }
            connectTimeout = 6000
            readTimeout = 6000
        }
        return try {
            conn.outputStream.use { os ->
                os.write(body.toByteArray(Charsets.UTF_8))
            }
            conn.responseCode
        } finally {
            conn.disconnect()
        }
    }
}

object CloudConfigRepository {
    private const val TAG = "CloudConfigRepo"
    private const val PREF_NAME = "dt_cloud_config"

    private const val KEY_VEHICLE_ID = "cloud_vehicle_id"
    private const val KEY_DEVICE_INSTALL_ID = "cloud_device_install_id"
    private const val KEY_UPLOAD_STATUS_PREFIX = "upload_status_session_"

    /**
     * A vehicle ID held by another device is only considered actively in use
     * if its last heartbeat/update timestamp is within this window (10 minutes).
     */
    const val VEHICLE_ACTIVE_TIMEOUT_MS = 10L * 60L * 1000L
    private const val MAX_TRANSACTION_RETRIES = 12
    private val INVALID_FIREBASE_KEY_CHARS = Regex("""[.#$\[\]/]""")

    private var prefs: SharedPreferences? = null
    @Volatile
    private var inMemoryInstallId: String? = null

    internal var transport: FleetRegistryTransport = HttpFleetRegistryTransport
    internal var clock: () -> Long = { System.currentTimeMillis() }

    private fun logWarn(msg: String) {
        runCatching { Log.w(TAG, msg) }
    }

    private fun logInfo(msg: String) {
        runCatching { Log.i(TAG, msg) }
    }

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
        val sp = prefs
        if (sp == null) {
            return inMemoryInstallId ?: UUID.randomUUID().toString().also { inMemoryInstallId = it }
        }
        var id = sp.getString(KEY_DEVICE_INSTALL_ID, null)
        if (id.isNullOrBlank()) {
            id = inMemoryInstallId ?: UUID.randomUUID().toString()
            sp.edit().putString(KEY_DEVICE_INSTALL_ID, id).apply()
        }
        inMemoryInstallId = id
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

    internal fun isVehicleNodeActiveByOtherDevice(
        nodeJson: JSONObject?,
        myInstallId: String,
        nowMs: Long,
        activeTimeoutMs: Long = VEHICLE_ACTIVE_TIMEOUT_MS
    ): Boolean {
        if (nodeJson == null) return false
        val ownerInstallId = nodeJson.optString("deviceInstallId", "").trim()
        if (ownerInstallId.isNotEmpty() && ownerInstallId == myInstallId) {
            return false
        }
        val lastSeen = maxOf(
            nodeJson.optLong("updatedAt", 0L),
            nodeJson.optLong("lastSeen", 0L)
        )
        if (lastSeen <= 0L) {
            return false
        }
        val ageMs = nowMs - lastSeen
        return ageMs <= activeTimeoutMs
    }

    internal fun parseJsonObjectOrNull(body: String): JSONObject? {
        val trimmed = body.trim()
        if (trimmed.isBlank() || trimmed == "null") return null
        return try {
            JSONObject(trimmed)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Find the next free vehicle ID matching the pattern of [requestedId]
     * (e.g. DT-01 -> next available DT-NN) given the current fleet registry JSON.
     */
    internal fun computeNextAvailableId(
        requestedId: String,
        fleetJson: JSONObject?,
        myInstallId: String,
        nowMs: Long,
        excludedIds: Set<String> = emptySet()
    ): String {
        val normalized = requestedId.trim().uppercase(Locale.US).ifBlank { "DT-01" }
        val pattern = Regex("""^([A-Z0-9_-]*?)-(\d+)$""")
        val match = pattern.matchEntire(normalized)
        val prefix = match?.groupValues?.get(1) ?: normalized
        val padWidth = match?.groupValues?.get(2)?.length?.coerceAtLeast(2) ?: 2

        val activeOtherIds = mutableSetOf<String>()
        if (fleetJson != null) {
            val keys = fleetJson.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val node = fleetJson.optJSONObject(key)
                if (isVehicleNodeActiveByOtherDevice(node, myInstallId, nowMs)) {
                    activeOtherIds.add(key.trim().uppercase(Locale.US))
                }
            }
        }
        activeOtherIds.addAll(excludedIds.map { it.trim().uppercase(Locale.US) })

        var candidateNum = 1
        while (candidateNum <= 9999) {
            val candidate = String.format(Locale.US, "%s-%0${padWidth}d", prefix, candidateNum)
            if (candidate !in activeOtherIds) {
                return candidate
            }
            candidateNum++
        }
        return String.format(Locale.US, "%s-%0${padWidth}d", prefix, 1)
    }

    /**
     * Query existing vehicle IDs from Firebase Realtime Database
     * and auto-generate the next available sequential ID: DT-01, DT-02, DT-03...
     */
    suspend fun queryNextAvailableVehicleId(patternSeed: String = "DT-01"): Result<String> = withContext(Dispatchers.IO) {
        val fbUrl = _config.value.firebaseDbUrl.trimEnd('/')
        if (fbUrl.isBlank()) return@withContext Result.success(patternSeed)

        try {
            val authParam = if (_config.value.firebaseAuthToken.isNotBlank()) "?auth=${_config.value.firebaseAuthToken}" else ""
            val resp = transport.getWithEtag("$fbUrl/fleet/vehicles.json$authParam")
            if (resp.responseCode in 200..299) {
                val fleetJson = parseJsonObjectOrNull(resp.body)
                val nextId = computeNextAvailableId(
                    requestedId = patternSeed,
                    fleetJson = fleetJson,
                    myInstallId = getDeviceInstallId(),
                    nowMs = clock()
                )
                Result.success(nextId)
            } else {
                Result.success(patternSeed)
            }
        } catch (e: Exception) {
            logWarn("Failed to query Firebase for sequential vehicle ID: ${e.message}")
            Result.success(patternSeed)
        }
    }

    /**
     * Validate whether candidate Vehicle ID is available for this device in Firebase fleet.
     * Returns true if free, owned by this device, or stale/inactive; false only if actively
     * held by a different device within [VEHICLE_ACTIVE_TIMEOUT_MS].
     */
    suspend fun validateVehicleId(candidateId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        val normalized = candidateId.trim().uppercase(Locale.US)
        if (normalized.isBlank()) return@withContext Result.success(false)

        val fbUrl = _config.value.firebaseDbUrl.trimEnd('/')
        if (fbUrl.isBlank()) return@withContext Result.success(true)

        try {
            val authParam = if (_config.value.firebaseAuthToken.isNotBlank()) "?auth=${_config.value.firebaseAuthToken}" else ""
            val resp = transport.getWithEtag("$fbUrl/fleet/vehicles/$normalized.json$authParam")
            if (resp.responseCode in 200..299) {
                val nodeJson = parseJsonObjectOrNull(resp.body)
                val inUseByOther = isVehicleNodeActiveByOtherDevice(
                    nodeJson = nodeJson,
                    myInstallId = getDeviceInstallId(),
                    nowMs = clock()
                )
                Result.success(!inUseByOther)
            } else {
                Result.success(true)
            }
        } catch (e: Exception) {
            logWarn("Could not reach Firebase to validate vehicle ID: ${e.message}")
            Result.success(true) // Allow if offline
        }
    }

    /**
     * Atomically verify and claim [newId] in Firebase Realtime Database using ETag
     * compare-and-swap (`X-Firebase-ETag` + `If-Match`).
     *
     * - If [newId] is free, owned by this device, or stale/inactive, claims [newId].
     * - If [newId] is actively held by another device, automatically picks the next free ID
     *   in the same pattern (e.g. DT-01 -> DT-06) and claims it atomically.
     * - If offline or Firebase is unreachable, persists [newId] locally without blocking.
     */
    suspend fun saveVehicleId(newId: String): Result<VehicleIdClaimResult> = withContext(Dispatchers.IO) {
        val normalized = newId.trim().uppercase(Locale.US)
        if (normalized.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Vehicle ID cannot be blank"))
        }
        if (INVALID_FIREBASE_KEY_CHARS.containsMatchIn(normalized)) {
            return@withContext Result.failure(IllegalArgumentException("Vehicle ID cannot contain . # \$ [ ] /"))
        }

        val current = _config.value
        val fbUrl = current.firebaseDbUrl.trimEnd('/')
        val myInstallId = getDeviceInstallId()

        if (fbUrl.isBlank()) {
            persistVehicleIdLocally(normalized)
            return@withContext Result.success(
                VehicleIdClaimResult(
                    requestedId = normalized,
                    assignedId = normalized,
                    wasReassigned = false,
                    syncedToCloud = false
                )
            )
        }

        val authParam = if (current.firebaseAuthToken.isNotBlank()) "?auth=${current.firebaseAuthToken}" else ""
        val excludedIds = mutableSetOf<String>()
        var candidateId = normalized

        try {
            repeat(MAX_TRANSACTION_RETRIES) {
                val nowMs = clock()
                val nodeUrl = "$fbUrl/fleet/vehicles/$candidateId.json$authParam"
                val getResp = transport.getWithEtag(nodeUrl)
                if (getResp.responseCode !in 200..299) {
                    // Server error -> fall back to local save gracefully
                    persistVehicleIdLocally(candidateId)
                    return@withContext Result.success(
                        VehicleIdClaimResult(
                            requestedId = normalized,
                            assignedId = candidateId,
                            wasReassigned = candidateId != normalized,
                            syncedToCloud = false
                        )
                    )
                }

                val existingNode = parseJsonObjectOrNull(getResp.body)
                val activeByOther = isVehicleNodeActiveByOtherDevice(
                    nodeJson = existingNode,
                    myInstallId = myInstallId,
                    nowMs = nowMs
                )

                if (activeByOther) {
                    excludedIds.add(candidateId)
                    val fleetResp = transport.getWithEtag("$fbUrl/fleet/vehicles.json$authParam")
                    val fleetJson = if (fleetResp.responseCode in 200..299) {
                        parseJsonObjectOrNull(fleetResp.body)
                    } else null
                    candidateId = computeNextAvailableId(
                        requestedId = normalized,
                        fleetJson = fleetJson,
                        myInstallId = myInstallId,
                        nowMs = nowMs,
                        excludedIds = excludedIds
                    )
                    return@repeat
                }

                // Candidate is free, owned by this device, or stale -> build claim payload
                val ownedBySelf = existingNode != null &&
                    existingNode.optString("deviceInstallId", "").trim() == myInstallId
                val claimPayload = if (ownedBySelf) {
                    JSONObject(existingNode.toString()).apply {
                        put("vehicleId", candidateId)
                        put("deviceInstallId", myInstallId)
                        put("updatedAt", nowMs)
                    }
                } else {
                    JSONObject().apply {
                        put("vehicleId", candidateId)
                        put("deviceInstallId", myInstallId)
                        put("updatedAt", nowMs)
                        put("isRecording", false)
                    }
                }

                val putCode = transport.putWithIfMatch(nodeUrl, claimPayload.toString(), getResp.etag)
                if (putCode in 200..299) {
                    persistVehicleIdLocally(candidateId)
                    return@withContext Result.success(
                        VehicleIdClaimResult(
                            requestedId = normalized,
                            assignedId = candidateId,
                            wasReassigned = candidateId != normalized,
                            syncedToCloud = true
                        )
                    )
                } else if (putCode == HttpURLConnection.HTTP_PRECON_FAILED) {
                    // 412 Precondition Failed: concurrent write won the race -> retry transaction
                    logInfo("Race condition claiming $candidateId (HTTP 412), retrying...")
                } else {
                    // Other HTTP error -> persist locally gracefully
                    persistVehicleIdLocally(candidateId)
                    return@withContext Result.success(
                        VehicleIdClaimResult(
                            requestedId = normalized,
                            assignedId = candidateId,
                            wasReassigned = candidateId != normalized,
                            syncedToCloud = false
                        )
                    )
                }
            }
        } catch (e: Exception) {
            logWarn("Offline or error claiming vehicle ID in Firebase: ${e.message}")
        }

        // Offline or exhausted retries -> persist best candidate locally so user is never blocked
        persistVehicleIdLocally(candidateId)
        Result.success(
            VehicleIdClaimResult(
                requestedId = normalized,
                assignedId = candidateId,
                wasReassigned = candidateId != normalized,
                syncedToCloud = false
            )
        )
    }

    private fun persistVehicleIdLocally(normalizedId: String) {
        val current = _config.value
        _config.value = current.copy(vehicleId = normalizedId)
        prefs?.edit()?.apply {
            putString(KEY_VEHICLE_ID, normalizedId)
            apply()
        }
    }

    fun setVehicleIdImmediate(id: String) {
        val normalized = id.trim().uppercase(Locale.US)
        persistVehicleIdLocally(normalized)
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
        inMemoryInstallId = null
        transport = HttpFleetRegistryTransport
        clock = { System.currentTimeMillis() }
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

    internal fun setConfigForTesting(config: CloudConfig, installId: String? = null) {
        _config.value = config
        if (installId != null) {
            inMemoryInstallId = installId
        }
    }
}
