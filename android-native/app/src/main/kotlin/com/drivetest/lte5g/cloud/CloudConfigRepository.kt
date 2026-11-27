package com.drivetest.lte5g.cloud

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.drivetest.lte5g.BuildConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.UUID

enum class CloudUploadStatus(val label: String) {
    NONE("Not Uploaded"),
    PENDING_WIFI("Waiting for Wi-Fi"),
    UPLOADING("Uploading..."),
    UPLOADED("Uploaded to Drive"),
    FAILED("Upload Failed")
}

enum class VehicleIdSyncState {
    UNCLAIMED,
    PENDING_SYNC,
    SYNCED,
    CONFLICT
}

enum class VehicleIdSyncIssue {
    NONE,
    OFFLINE,
    PERMISSION_DENIED,
    DUPLICATE_CONFLICT,
    SERVER_ERROR
}

data class CloudConfig(
    val vehicleId: String,
    val firebaseDbUrl: String,
    val firebaseAuthToken: String,
    val liveTelemetryEnabled: Boolean = true,
    val cloudUploadUrl: String,
    val autoUploadOnWifi: Boolean = true,
    val vehicleIdSyncState: VehicleIdSyncState = VehicleIdSyncState.UNCLAIMED,
    val vehicleIdSyncIssue: VehicleIdSyncIssue = VehicleIdSyncIssue.NONE,
    val suggestedAlternativeId: String? = null,
    val isUserEditedVehicleId: Boolean = false
)

sealed class VehicleIdAvailability {
    data object Idle : VehicleIdAvailability()
    data object Checking : VehicleIdAvailability()
    data class Invalid(val reason: String) : VehicleIdAvailability()
    data class Available(
        val normalizedId: String,
        val ownedBySelf: Boolean = false
    ) : VehicleIdAvailability()
    data class Taken(
        val normalizedId: String,
        val suggestedAlternative: String,
        val message: String = CloudConfigRepository.DUPLICATE_ERROR_MESSAGE
    ) : VehicleIdAvailability()
    data class Offline(val normalizedId: String) : VehicleIdAvailability()
    data class PermissionDenied(val normalizedId: String) : VehicleIdAvailability()
}

sealed class VehicleIdClaimOutcome {
    data class Claimed(val vehicleId: String) : VehicleIdClaimOutcome()
    data class TakenByOtherDevice(
        val requestedId: String,
        val suggestedAlternative: String,
        val message: String = CloudConfigRepository.DUPLICATE_ERROR_MESSAGE
    ) : VehicleIdClaimOutcome()
    data class SavedLocallyPendingSync(
        val vehicleId: String,
        val issue: VehicleIdSyncIssue,
        val message: String
    ) : VehicleIdClaimOutcome()
    data class InvalidInput(val reason: String) : VehicleIdClaimOutcome()
}

internal data class FirebaseGetResponse(
    val responseCode: Int,
    val body: String,
    val etag: String?
)

internal interface FleetRegistryTransport {
    fun getWithEtag(urlString: String): FirebaseGetResponse
    fun getJson(urlString: String): FirebaseGetResponse
    fun putWithIfMatch(urlString: String, body: String, etag: String?): Int
    fun deleteWithIfMatch(urlString: String, etag: String?): Int
}

internal object HttpFleetRegistryTransport : FleetRegistryTransport {
    override fun getWithEtag(urlString: String): FirebaseGetResponse =
        executeGet(urlString, requestEtag = true)

    override fun getJson(urlString: String): FirebaseGetResponse =
        executeGet(urlString, requestEtag = false)

    private fun executeGet(urlString: String, requestEtag: Boolean): FirebaseGetResponse {
        val url = URL(urlString)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            if (requestEtag) {
                setRequestProperty("X-Firebase-ETag", "true")
            }
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
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
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
            val code = conn.responseCode
            runCatching {
                (if (code in 200..299) conn.inputStream else conn.errorStream)?.close()
            }
            code
        } finally {
            conn.disconnect()
        }
    }

    override fun deleteWithIfMatch(urlString: String, etag: String?): Int {
        val url = URL(urlString)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "DELETE"
            if (!etag.isNullOrBlank()) {
                setRequestProperty("If-Match", etag)
            }
            connectTimeout = 6000
            readTimeout = 6000
        }
        return try {
            val code = conn.responseCode
            runCatching {
                (if (code in 200..299) conn.inputStream else conn.errorStream)?.close()
            }
            code
        } finally {
            conn.disconnect()
        }
    }
}

object CloudConfigRepository {
    private const val TAG = "CloudConfigRepo"
    private const val PREF_NAME = "dt_cloud_config"

    private const val KEY_VEHICLE_ID = "cloud_vehicle_id"
    private const val KEY_VEHICLE_ID_USER_EDITED = "cloud_vehicle_id_user_edited"
    private const val KEY_VEHICLE_ID_SYNC_STATE = "cloud_vehicle_id_sync_state"
    private const val KEY_VEHICLE_ID_SYNC_ISSUE = "cloud_vehicle_id_sync_issue"
    private const val KEY_VEHICLE_ID_CLAIMED_ID = "cloud_vehicle_id_claimed_id"
    private const val KEY_VEHICLE_ID_SUGGESTED_ALT = "cloud_vehicle_id_suggested_alt"
    private const val KEY_VEHICLE_ID_MIGRATED_V2 = "cloud_vehicle_id_migrated_v2"
    private const val KEY_DEVICE_INSTALL_ID = "cloud_device_install_id"
    private const val KEY_UPLOAD_STATUS_PREFIX = "upload_status_session_"

    const val DUPLICATE_ERROR_MESSAGE = "This Vehicle ID is already used by another device"

    /**
     * For very old legacy /fleet/vehicles nodes that have no deviceInstallId field,
     * treat them as active/taken only if recording or updated within this window (10 minutes).
     */
    const val LEGACY_ACTIVE_TIMEOUT_MS = 10L * 60L * 1000L
    internal const val NULL_ETAG = "null_etag"
    private const val MAX_CAS_RETRIES = 5
    private const val MAX_DEFAULT_COLLISION_RETRIES = 3
    private const val AUTO_SYNC_COOLDOWN_MS = 5_000L

    private var prefs: SharedPreferences? = null
    @Volatile
    private var inMemoryInstallId: String? = null
    @Volatile
    private var inMemoryClaimedId: String? = null
    @Volatile
    private var isNetworkCallbackRegistered = false
    @Volatile
    private var cachedDeviceDefaultId: String? = null
    @Volatile
    private var lastAutoSyncAttemptMs: Long = 0L

    private val syncMutex = Mutex()
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    internal var transport: FleetRegistryTransport = HttpFleetRegistryTransport
    internal var clock: () -> Long = { System.currentTimeMillis() }
    internal var deviceDefaultIdProvider: () -> String = {
        VehicleIdGenerator.generateDefaultId(
            deviceName = null,
            bluetoothName = null,
            manufacturer = null,
            model = null,
            androidId = null,
            installId = getDeviceInstallId()
        )
    }

    private fun logWarn(msg: String) {
        runCatching { Log.w(TAG, msg) }
    }

    private fun logInfo(msg: String) {
        runCatching { Log.i(TAG, msg) }
    }

    private val _config = MutableStateFlow(
        CloudConfig(
            vehicleId = "",
            firebaseDbUrl = BuildConfig.FIREBASE_DB_URL,
            firebaseAuthToken = BuildConfig.FIREBASE_AUTH_TOKEN,
            liveTelemetryEnabled = true,
            cloudUploadUrl = BuildConfig.CLOUD_UPLOAD_URL,
            autoUploadOnWifi = true,
            vehicleIdSyncState = VehicleIdSyncState.UNCLAIMED,
            vehicleIdSyncIssue = VehicleIdSyncIssue.NONE
        )
    )
    val config: StateFlow<CloudConfig> = _config.asStateFlow()

    private val _sessionStatuses = MutableStateFlow<Map<Long, CloudUploadStatus>>(emptyMap())
    val sessionStatuses: StateFlow<Map<Long, CloudUploadStatus>> = _sessionStatuses.asStateFlow()

    fun init(context: Context) {
        val appContext = context.applicationContext
        deviceDefaultIdProvider = {
            VehicleIdGenerator.generateFromContext(appContext, getDeviceInstallId())
        }
        val sp = appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        init(sp)
        registerConnectivityMonitor(appContext)
        triggerPendingSyncAsync()
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

    /**
     * Returns the deterministic, sanitized device-derived default Vehicle ID for this device
     * (used both as the initial default and for the "Use device name" suggestion chip).
     */
    fun getDeviceSuggestedVehicleId(): String {
        val computed = runCatching { deviceDefaultIdProvider() }
            .getOrNull()
            ?.takeIf { VehicleIdGenerator.validate(it) is VehicleIdValidationResult.Valid }
            ?: VehicleIdGenerator.generateDefaultId(
                deviceName = null,
                bluetoothName = null,
                manufacturer = null,
                model = null,
                androidId = null,
                installId = getDeviceInstallId()
            )
        cachedDeviceDefaultId = computed
        return computed
    }

    private fun registerConnectivityMonitor(context: Context) {
        if (isNetworkCallbackRegistered) return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    triggerPendingSyncAsync(enforceCooldown = false)
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities
                ) {
                    val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    val isValidated = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    if (hasInternet && isValidated) {
                        triggerPendingSyncAsync(enforceCooldown = true)
                    }
                }
            })
            isNetworkCallbackRegistered = true
        } catch (e: Exception) {
            logWarn("Failed to register connectivity monitor: ${e.message}")
        }
    }

    private fun triggerPendingSyncAsync(enforceCooldown: Boolean = false) {
        val current = _config.value
        if (current.firebaseDbUrl.isBlank() || current.vehicleId.isBlank()) return
        if (
            current.vehicleIdSyncState == VehicleIdSyncState.PENDING_SYNC ||
            current.vehicleIdSyncState == VehicleIdSyncState.UNCLAIMED
        ) {
            val nowMs = clock()
            if (enforceCooldown && (nowMs - lastAutoSyncAttemptMs) < AUTO_SYNC_COOLDOWN_MS) {
                return
            }
            lastAutoSyncAttemptMs = nowMs
            backgroundScope.launch(ioDispatcher) {
                syncPendingVehicleIdClaim()
            }
        }
    }

    private fun loadConfig() {
        val sp = prefs ?: return
        inMemoryClaimedId = sp.getString(KEY_VEHICLE_ID_CLAIMED_ID, null)?.takeIf { it.isNotBlank() }
        val migratedV2 = sp.getBoolean(KEY_VEHICLE_ID_MIGRATED_V2, false)
        val savedRaw = sp.getString(KEY_VEHICLE_ID, null)?.trim()

        val resolvedId: String
        val isUserEdited: Boolean
        val syncState: VehicleIdSyncState
        val syncIssue: VehicleIdSyncIssue
        val suggestedAlt: String?

        if (!migratedV2) {
            // Safe one-time migration:
            // - Blank or stale pre-v2 hardcoded "DT-01" default -> replace with device-derived default.
            // - Existing custom user ID (e.g. "VAN-BKK-02") -> preserve with highest priority.
            if (savedRaw.isNullOrBlank() || savedRaw.equals(VehicleIdGenerator.PLACEHOLDER_EXAMPLE_ID, ignoreCase = true)) {
                resolvedId = getDeviceSuggestedVehicleId()
                isUserEdited = false
            } else {
                when (val validation = VehicleIdGenerator.validate(savedRaw)) {
                    is VehicleIdValidationResult.Valid -> {
                        resolvedId = validation.normalized
                        isUserEdited = true
                    }
                    is VehicleIdValidationResult.Invalid -> {
                        val sanitized = VehicleIdGenerator.sanitizeFragment(savedRaw)
                            .take(VehicleIdGenerator.MAX_VEHICLE_ID_LENGTH)
                            .trimEnd('-', '_')
                        if (VehicleIdGenerator.validate(sanitized) is VehicleIdValidationResult.Valid) {
                            resolvedId = sanitized
                            isUserEdited = true
                        } else {
                            resolvedId = getDeviceSuggestedVehicleId()
                            isUserEdited = false
                        }
                    }
                }
            }
            syncState = VehicleIdSyncState.PENDING_SYNC
            syncIssue = VehicleIdSyncIssue.NONE
            suggestedAlt = null

            sp.edit()
                .putString(KEY_VEHICLE_ID, resolvedId)
                .putBoolean(KEY_VEHICLE_ID_USER_EDITED, isUserEdited)
                .putString(KEY_VEHICLE_ID_SYNC_STATE, syncState.name)
                .putString(KEY_VEHICLE_ID_SYNC_ISSUE, syncIssue.name)
                .remove(KEY_VEHICLE_ID_SUGGESTED_ALT)
                .putBoolean(KEY_VEHICLE_ID_MIGRATED_V2, true)
                .apply()
        } else {
            val storedId = savedRaw.orEmpty()
            if (VehicleIdGenerator.validate(storedId) is VehicleIdValidationResult.Valid) {
                resolvedId = storedId.uppercase(Locale.US)
                isUserEdited = sp.getBoolean(KEY_VEHICLE_ID_USER_EDITED, false)
                syncState = parseSyncState(sp.getString(KEY_VEHICLE_ID_SYNC_STATE, null))
                syncIssue = parseSyncIssue(sp.getString(KEY_VEHICLE_ID_SYNC_ISSUE, null))
                suggestedAlt = sp.getString(KEY_VEHICLE_ID_SUGGESTED_ALT, null)?.takeIf { it.isNotBlank() }
            } else {
                resolvedId = getDeviceSuggestedVehicleId()
                isUserEdited = false
                syncState = VehicleIdSyncState.PENDING_SYNC
                syncIssue = VehicleIdSyncIssue.NONE
                suggestedAlt = null
                sp.edit()
                    .putString(KEY_VEHICLE_ID, resolvedId)
                    .putBoolean(KEY_VEHICLE_ID_USER_EDITED, false)
                    .putString(KEY_VEHICLE_ID_SYNC_STATE, syncState.name)
                    .putString(KEY_VEHICLE_ID_SYNC_ISSUE, syncIssue.name)
                    .remove(KEY_VEHICLE_ID_SUGGESTED_ALT)
                    .apply()
            }
        }

        val defaultFbUrl = BuildConfig.FIREBASE_DB_URL.ifBlank {
            "https://drivetest-telemetry-default-rtdb.asia-southeast1.firebasedatabase.app"
        }
        val fbToken = BuildConfig.FIREBASE_AUTH_TOKEN
        val uploadUrl = BuildConfig.CLOUD_UPLOAD_URL

        _config.value = CloudConfig(
            vehicleId = resolvedId,
            firebaseDbUrl = defaultFbUrl,
            firebaseAuthToken = fbToken,
            liveTelemetryEnabled = true,
            cloudUploadUrl = uploadUrl,
            autoUploadOnWifi = true,
            vehicleIdSyncState = syncState,
            vehicleIdSyncIssue = syncIssue,
            suggestedAlternativeId = suggestedAlt,
            isUserEditedVehicleId = isUserEdited
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

    private fun parseSyncState(raw: String?): VehicleIdSyncState {
        if (raw.isNullOrBlank()) return VehicleIdSyncState.UNCLAIMED
        return runCatching { VehicleIdSyncState.valueOf(raw) }.getOrDefault(VehicleIdSyncState.UNCLAIMED)
    }

    private fun parseSyncIssue(raw: String?): VehicleIdSyncIssue {
        if (raw.isNullOrBlank()) return VehicleIdSyncIssue.NONE
        return runCatching { VehicleIdSyncIssue.valueOf(raw) }.getOrDefault(VehicleIdSyncIssue.NONE)
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

    private fun isPermissionDeniedCode(code: Int): Boolean =
        code == HttpURLConnection.HTTP_UNAUTHORIZED || code == HttpURLConnection.HTTP_FORBIDDEN

    private fun buildFirebaseUrl(
        baseUrl: String,
        path: String,
        authToken: String,
        shallow: Boolean = false
    ): String {
        val cleanBase = baseUrl.trimEnd('/')
        val cleanPath = path.trimStart('/')
        val queryParts = mutableListOf<String>()
        if (shallow) queryParts.add("shallow=true")
        if (authToken.isNotBlank()) queryParts.add("auth=$authToken")
        val query = if (queryParts.isEmpty()) "" else "?" + queryParts.joinToString("&")
        return "$cleanBase/$cleanPath.json$query"
    }

    private fun encodePathSegment(segment: String): String =
        URLEncoder.encode(segment, "UTF-8").replace("+", "%20")

    internal fun isClaimOwnedByOtherDevice(
        claimJson: JSONObject?,
        myInstallId: String
    ): Boolean {
        if (claimJson == null) return false
        val owner = claimJson.optString("deviceInstallId", "").trim()
        return owner.isNotEmpty() && owner != myInstallId
    }

    internal fun isLegacyVehicleOwnedByOtherDevice(
        nodeJson: JSONObject?,
        myInstallId: String,
        nowMs: Long,
        legacyActiveTimeoutMs: Long = LEGACY_ACTIVE_TIMEOUT_MS
    ): Boolean {
        if (nodeJson == null) return false
        if (nodeJson.optBoolean("claimReleased", false)) return false
        val ownerInstallId = nodeJson.optString("deviceInstallId", "").trim()
        if (ownerInstallId.isNotEmpty()) {
            return ownerInstallId != myInstallId
        }
        if (nodeJson.optBoolean("isRecording", false)) {
            return true
        }
        val lastSeen = maxOf(
            nodeJson.optLong("updatedAt", 0L),
            nodeJson.optLong("lastSeen", 0L)
        )
        if (lastSeen <= 0L) return false
        return (nowMs - lastSeen) <= legacyActiveTimeoutMs
    }

    private sealed class LegacyVehicleCheck {
        data class Clear(val ownedNode: JSONObject?) : LegacyVehicleCheck()
        data object TakenByOther : LegacyVehicleCheck()
        data object PermissionDenied : LegacyVehicleCheck()
        data object ServerError : LegacyVehicleCheck()
    }

    private fun checkLegacyVehiclesCaseInsensitive(
        fbUrl: String,
        authToken: String,
        normalizedId: String,
        myInstallId: String,
        nowMs: Long
    ): LegacyVehicleCheck {
        val shallowUrl = buildFirebaseUrl(fbUrl, "fleet/vehicles", authToken, shallow = true)
        val shallowResp = transport.getJson(shallowUrl)
        if (isPermissionDeniedCode(shallowResp.responseCode)) {
            return LegacyVehicleCheck.PermissionDenied
        }
        if (shallowResp.responseCode !in 200..299) {
            return LegacyVehicleCheck.ServerError
        }

        val shallowObj = parseJsonObjectOrNull(shallowResp.body) ?: return LegacyVehicleCheck.Clear(null)
        val matchingKeys = mutableListOf<String>()
        val keys = shallowObj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key.trim().equals(normalizedId, ignoreCase = true)) {
                matchingKeys.add(key)
            }
        }
        if (matchingKeys.isEmpty()) {
            return LegacyVehicleCheck.Clear(null)
        }

        var ownedExactNode: JSONObject? = null
        for (key in matchingKeys) {
            val nodeUrl = buildFirebaseUrl(
                fbUrl,
                "fleet/vehicles/${encodePathSegment(key)}",
                authToken
            )
            val nodeResp = transport.getWithEtag(nodeUrl)
            if (isPermissionDeniedCode(nodeResp.responseCode)) {
                return LegacyVehicleCheck.PermissionDenied
            }
            if (nodeResp.responseCode !in 200..299) {
                return LegacyVehicleCheck.ServerError
            }
            val nodeJson = parseJsonObjectOrNull(nodeResp.body)
            if (isLegacyVehicleOwnedByOtherDevice(nodeJson, myInstallId, nowMs)) {
                return LegacyVehicleCheck.TakenByOther
            }
            if (key == normalizedId && nodeJson != null) {
                ownedExactNode = nodeJson
            }
        }
        return LegacyVehicleCheck.Clear(ownedExactNode)
    }

    private fun fetchTakenCanonicalKeys(fbUrl: String, authToken: String): Set<String> {
        val taken = mutableSetOf<String>()
        runCatching {
            val claimsResp = transport.getJson(
                buildFirebaseUrl(fbUrl, "fleet/vehicleClaims", authToken, shallow = true)
            )
            if (claimsResp.responseCode in 200..299) {
                parseJsonObjectOrNull(claimsResp.body)?.keys()?.forEach { k ->
                    taken.add(VehicleIdGenerator.canonicalKey(k))
                }
            }
        }
        runCatching {
            val vehiclesResp = transport.getJson(
                buildFirebaseUrl(fbUrl, "fleet/vehicles", authToken, shallow = true)
            )
            if (vehiclesResp.responseCode in 200..299) {
                parseJsonObjectOrNull(vehiclesResp.body)?.keys()?.forEach { k ->
                    taken.add(VehicleIdGenerator.canonicalKey(k))
                }
            }
        }
        return taken
    }

    private fun buildAlternativeSuggestion(
        normalizedId: String,
        fbUrl: String,
        authToken: String
    ): String {
        val taken = fetchTakenCanonicalKeys(fbUrl, authToken)
        val suffix = VehicleIdGenerator.shortStableSuffix(androidId = null, installId = getDeviceInstallId())
        return VehicleIdGenerator.suggestFreeAlternative(
            requestedId = normalizedId,
            takenCanonicalKeys = taken,
            stableSuffix = suffix
        )
    }

    /**
     * Real-time availability check for the Settings screen debounced indicator.
     * Checks validation, /fleet/vehicleClaims/{canonicalKey}, and legacy /fleet/vehicles
     * case-insensitively without modifying any remote or local state.
     */
    suspend fun checkVehicleIdAvailability(candidateId: String): VehicleIdAvailability =
        withContext(ioDispatcher) {
            val normalized = when (val v = VehicleIdGenerator.validate(candidateId)) {
                is VehicleIdValidationResult.Invalid -> return@withContext VehicleIdAvailability.Invalid(v.reason)
                is VehicleIdValidationResult.Valid -> v.normalized
            }

            val current = _config.value
            val fbUrl = current.firebaseDbUrl.trimEnd('/')
            if (fbUrl.isBlank()) {
                return@withContext VehicleIdAvailability.Offline(normalized)
            }

            val authToken = current.firebaseAuthToken
            val myInstallId = getDeviceInstallId()
            val canonicalKey = VehicleIdGenerator.canonicalKey(normalized)

            try {
                val claimUrl = buildFirebaseUrl(fbUrl, "fleet/vehicleClaims/$canonicalKey", authToken)
                val claimResp = transport.getWithEtag(claimUrl)
                if (isPermissionDeniedCode(claimResp.responseCode)) {
                    return@withContext VehicleIdAvailability.PermissionDenied(normalized)
                }
                if (claimResp.responseCode !in 200..299) {
                    return@withContext VehicleIdAvailability.Offline(normalized)
                }

                val claimNode = parseJsonObjectOrNull(claimResp.body)
                if (isClaimOwnedByOtherDevice(claimNode, myInstallId)) {
                    val suggested = buildAlternativeSuggestion(normalized, fbUrl, authToken)
                    return@withContext VehicleIdAvailability.Taken(
                        normalizedId = normalized,
                        suggestedAlternative = suggested
                    )
                }

                val ownedInClaims = claimNode != null &&
                    claimNode.optString("deviceInstallId", "").trim() == myInstallId

                when (val legacy = checkLegacyVehiclesCaseInsensitive(fbUrl, authToken, normalized, myInstallId, clock())) {
                    LegacyVehicleCheck.PermissionDenied -> VehicleIdAvailability.PermissionDenied(normalized)
                    LegacyVehicleCheck.ServerError -> VehicleIdAvailability.Offline(normalized)
                    LegacyVehicleCheck.TakenByOther -> {
                        val suggested = buildAlternativeSuggestion(normalized, fbUrl, authToken)
                        VehicleIdAvailability.Taken(
                            normalizedId = normalized,
                            suggestedAlternative = suggested
                        )
                    }
                    is LegacyVehicleCheck.Clear -> {
                        val ownedInLegacy = legacy.ownedNode != null &&
                            legacy.ownedNode.optString("deviceInstallId", "").trim() == myInstallId
                        VehicleIdAvailability.Available(
                            normalizedId = normalized,
                            ownedBySelf = ownedInClaims || ownedInLegacy
                        )
                    }
                }
            } catch (e: Exception) {
                logWarn("Availability check failed (offline): ${e.message}")
                VehicleIdAvailability.Offline(normalized)
            }
        }

    /**
     * Atomically verify and claim [newId] in Firebase Realtime Database using ETag CAS
     * (`X-Firebase-ETag` + `If-Match`) on `/fleet/vehicleClaims/{canonicalKey}` plus
     * case-insensitive legacy `/fleet/vehicles` verification.
     *
     * - Same device owning [newId] may re-save it cleanly.
     * - Different device receives [VehicleIdClaimOutcome.TakenByOtherDevice] with an auto-suggested
     *   free alternative; existing remote and local IDs are not overwritten.
     * - Offline or permission-denied saves locally with [VehicleIdSyncState.PENDING_SYNC] and
     *   retries automatically on reconnect.
     */
    suspend fun saveVehicleId(
        newId: String,
        isUserEdit: Boolean = true
    ): VehicleIdClaimOutcome = syncMutex.withLock {
        saveVehicleIdLocked(newId = newId, isUserEdit = isUserEdit)
    }

    private suspend fun saveVehicleIdLocked(
        newId: String,
        isUserEdit: Boolean
    ): VehicleIdClaimOutcome = withContext(ioDispatcher) {
        val normalized = when (val v = VehicleIdGenerator.validate(newId)) {
            is VehicleIdValidationResult.Invalid -> return@withContext VehicleIdClaimOutcome.InvalidInput(v.reason)
            is VehicleIdValidationResult.Valid -> v.normalized
        }

        val current = _config.value
        val fbUrl = current.firebaseDbUrl.trimEnd('/')
        val authToken = current.firebaseAuthToken
        val myInstallId = getDeviceInstallId()
        val canonicalKey = VehicleIdGenerator.canonicalKey(normalized)
        val previousClaimedId = prefs?.getString(KEY_VEHICLE_ID_CLAIMED_ID, null)
            ?.takeIf { it.isNotBlank() }
            ?: inMemoryClaimedId?.takeIf { it.isNotBlank() }
            ?: current.vehicleId.takeIf {
                current.vehicleIdSyncState == VehicleIdSyncState.SYNCED && it.isNotBlank()
            }

        if (fbUrl.isBlank()) {
            persistVehicleIdLocally(
                normalizedId = normalized,
                isUserEdited = isUserEdit,
                syncState = VehicleIdSyncState.PENDING_SYNC,
                syncIssue = VehicleIdSyncIssue.OFFLINE,
                suggestedAlternative = null
            )
            return@withContext VehicleIdClaimOutcome.SavedLocallyPendingSync(
                vehicleId = normalized,
                issue = VehicleIdSyncIssue.OFFLINE,
                message = "Saved locally — Firebase URL not configured"
            )
        }

        try {
            repeat(MAX_CAS_RETRIES) {
                val nowMs = clock()
                val claimUrl = buildFirebaseUrl(fbUrl, "fleet/vehicleClaims/$canonicalKey", authToken)
                val claimResp = transport.getWithEtag(claimUrl)

                if (isPermissionDeniedCode(claimResp.responseCode)) {
                    persistVehicleIdLocally(
                        normalizedId = normalized,
                        isUserEdited = isUserEdit,
                        syncState = VehicleIdSyncState.PENDING_SYNC,
                        syncIssue = VehicleIdSyncIssue.PERMISSION_DENIED,
                        suggestedAlternative = null
                    )
                    return@withContext VehicleIdClaimOutcome.SavedLocallyPendingSync(
                        vehicleId = normalized,
                        issue = VehicleIdSyncIssue.PERMISSION_DENIED,
                        message = "Saved locally — Firebase permission denied, will retry sync"
                    )
                }

                if (claimResp.responseCode !in 200..299) {
                    persistVehicleIdLocally(
                        normalizedId = normalized,
                        isUserEdited = isUserEdit,
                        syncState = VehicleIdSyncState.PENDING_SYNC,
                        syncIssue = VehicleIdSyncIssue.SERVER_ERROR,
                        suggestedAlternative = null
                    )
                    return@withContext VehicleIdClaimOutcome.SavedLocallyPendingSync(
                        vehicleId = normalized,
                        issue = VehicleIdSyncIssue.SERVER_ERROR,
                        message = "Saved locally — server error (HTTP ${claimResp.responseCode}), will retry sync"
                    )
                }

                val existingClaim = parseJsonObjectOrNull(claimResp.body)
                if (isClaimOwnedByOtherDevice(existingClaim, myInstallId)) {
                    val suggested = buildAlternativeSuggestion(normalized, fbUrl, authToken)
                    return@withContext VehicleIdClaimOutcome.TakenByOtherDevice(
                        requestedId = normalized,
                        suggestedAlternative = suggested
                    )
                }

                val legacyCheck = checkLegacyVehiclesCaseInsensitive(fbUrl, authToken, normalized, myInstallId, nowMs)
                val existingOwnedVehicleNode: JSONObject? = when (legacyCheck) {
                    LegacyVehicleCheck.PermissionDenied -> {
                        persistVehicleIdLocally(
                            normalizedId = normalized,
                            isUserEdited = isUserEdit,
                            syncState = VehicleIdSyncState.PENDING_SYNC,
                            syncIssue = VehicleIdSyncIssue.PERMISSION_DENIED,
                            suggestedAlternative = null
                        )
                        return@withContext VehicleIdClaimOutcome.SavedLocallyPendingSync(
                            vehicleId = normalized,
                            issue = VehicleIdSyncIssue.PERMISSION_DENIED,
                            message = "Saved locally — Firebase permission denied, will retry sync"
                        )
                    }
                    LegacyVehicleCheck.ServerError -> {
                        persistVehicleIdLocally(
                            normalizedId = normalized,
                            isUserEdited = isUserEdit,
                            syncState = VehicleIdSyncState.PENDING_SYNC,
                            syncIssue = VehicleIdSyncIssue.SERVER_ERROR,
                            suggestedAlternative = null
                        )
                        return@withContext VehicleIdClaimOutcome.SavedLocallyPendingSync(
                            vehicleId = normalized,
                            issue = VehicleIdSyncIssue.SERVER_ERROR,
                            message = "Saved locally — server unavailable, will retry sync"
                        )
                    }
                    LegacyVehicleCheck.TakenByOther -> {
                        val suggested = buildAlternativeSuggestion(normalized, fbUrl, authToken)
                        return@withContext VehicleIdClaimOutcome.TakenByOtherDevice(
                            requestedId = normalized,
                            suggestedAlternative = suggested
                        )
                    }
                    is LegacyVehicleCheck.Clear -> legacyCheck.ownedNode
                }

                val claimedAt = existingClaim?.optLong("claimedAt", 0L)?.takeIf { it > 0L } ?: nowMs
                val claimPayload = JSONObject().apply {
                    put("vehicleId", normalized)
                    put("canonicalId", canonicalKey)
                    put("deviceInstallId", myInstallId)
                    put("claimedAt", claimedAt)
                    put("updatedAt", nowMs)
                }

                val ifMatchEtag = claimResp.etag?.takeIf { it.isNotBlank() }
                    ?: if (existingClaim == null) NULL_ETAG else null
                if (ifMatchEtag == null) {
                    persistVehicleIdLocally(
                        normalizedId = normalized,
                        isUserEdited = isUserEdit,
                        syncState = VehicleIdSyncState.PENDING_SYNC,
                        syncIssue = VehicleIdSyncIssue.SERVER_ERROR,
                        suggestedAlternative = null
                    )
                    return@withContext VehicleIdClaimOutcome.SavedLocallyPendingSync(
                        vehicleId = normalized,
                        issue = VehicleIdSyncIssue.SERVER_ERROR,
                        message = "Saved locally — missing Firebase ETag for atomic claim, will retry sync"
                    )
                }

                val putCode = transport.putWithIfMatch(claimUrl, claimPayload.toString(), ifMatchEtag)
                when {
                    putCode in 200..299 -> {
                        syncVehicleNodeOwnershipBestEffort(
                            fbUrl = fbUrl,
                            authToken = authToken,
                            normalizedId = normalized,
                            myInstallId = myInstallId,
                            nowMs = nowMs,
                            existingOwnedNode = existingOwnedVehicleNode
                        )
                        if (
                            !previousClaimedId.isNullOrBlank() &&
                            !previousClaimedId.equals(normalized, ignoreCase = true)
                        ) {
                            releaseClaimBestEffort(fbUrl, authToken, previousClaimedId, myInstallId)
                        }
                        persistVehicleIdLocally(
                            normalizedId = normalized,
                            isUserEdited = isUserEdit,
                            syncState = VehicleIdSyncState.SYNCED,
                            syncIssue = VehicleIdSyncIssue.NONE,
                            suggestedAlternative = null,
                            claimedId = normalized
                        )
                        return@withContext VehicleIdClaimOutcome.Claimed(normalized)
                    }
                    putCode == HttpURLConnection.HTTP_PRECON_FAILED -> {
                        logInfo("Race condition claiming $normalized (HTTP 412), re-checking owner...")
                    }
                    isPermissionDeniedCode(putCode) -> {
                        persistVehicleIdLocally(
                            normalizedId = normalized,
                            isUserEdited = isUserEdit,
                            syncState = VehicleIdSyncState.PENDING_SYNC,
                            syncIssue = VehicleIdSyncIssue.PERMISSION_DENIED,
                            suggestedAlternative = null
                        )
                        return@withContext VehicleIdClaimOutcome.SavedLocallyPendingSync(
                            vehicleId = normalized,
                            issue = VehicleIdSyncIssue.PERMISSION_DENIED,
                            message = "Saved locally — Firebase permission denied, will retry sync"
                        )
                    }
                    else -> {
                        persistVehicleIdLocally(
                            normalizedId = normalized,
                            isUserEdited = isUserEdit,
                            syncState = VehicleIdSyncState.PENDING_SYNC,
                            syncIssue = VehicleIdSyncIssue.SERVER_ERROR,
                            suggestedAlternative = null
                        )
                        return@withContext VehicleIdClaimOutcome.SavedLocallyPendingSync(
                            vehicleId = normalized,
                            issue = VehicleIdSyncIssue.SERVER_ERROR,
                            message = "Saved locally — server error (HTTP $putCode), will retry sync"
                        )
                    }
                }
            }

            // Exhausted CAS retries -> another device is actively contending for this ID
            val suggested = buildAlternativeSuggestion(normalized, fbUrl, authToken)
            VehicleIdClaimOutcome.TakenByOtherDevice(
                requestedId = normalized,
                suggestedAlternative = suggested
            )
        } catch (e: Exception) {
            logWarn("Offline or error claiming vehicle ID in Firebase: ${e.message}")
            persistVehicleIdLocally(
                normalizedId = normalized,
                isUserEdited = isUserEdit,
                syncState = VehicleIdSyncState.PENDING_SYNC,
                syncIssue = VehicleIdSyncIssue.OFFLINE,
                suggestedAlternative = null
            )
            VehicleIdClaimOutcome.SavedLocallyPendingSync(
                vehicleId = normalized,
                issue = VehicleIdSyncIssue.OFFLINE,
                message = "Saved locally (offline) — will verify & sync when reconnected"
            )
        }
    }

    private fun syncVehicleNodeOwnershipBestEffort(
        fbUrl: String,
        authToken: String,
        normalizedId: String,
        myInstallId: String,
        nowMs: Long,
        existingOwnedNode: JSONObject?
    ) {
        runCatching {
            val vehicleUrl = buildFirebaseUrl(fbUrl, "fleet/vehicles/$normalizedId", authToken)
            val getResp = transport.getWithEtag(vehicleUrl)
            if (getResp.responseCode !in 200..299) return
            val remoteNode = parseJsonObjectOrNull(getResp.body)
            if (isLegacyVehicleOwnedByOtherDevice(remoteNode, myInstallId, nowMs)) return

            val baseNode = remoteNode ?: existingOwnedNode
            val ownedBySelf = baseNode != null &&
                baseNode.optString("deviceInstallId", "").trim() == myInstallId

            val payload = if (ownedBySelf) {
                JSONObject(baseNode.toString()).apply {
                    put("vehicleId", normalizedId)
                    put("deviceInstallId", myInstallId)
                    put("updatedAt", nowMs)
                    remove("claimReleased")
                }
            } else {
                JSONObject().apply {
                    put("vehicleId", normalizedId)
                    put("deviceInstallId", myInstallId)
                    put("updatedAt", nowMs)
                    put("isRecording", false)
                }
            }
            val ifMatchEtag = getResp.etag?.takeIf { it.isNotBlank() }
                ?: if (remoteNode == null) NULL_ETAG else return
            transport.putWithIfMatch(vehicleUrl, payload.toString(), ifMatchEtag)
        }.onFailure { e ->
            logWarn("Best-effort vehicle node sync failed for $normalizedId: ${e.message}")
        }
    }

    private fun releaseClaimBestEffort(
        fbUrl: String,
        authToken: String,
        previousVehicleId: String,
        myInstallId: String
    ) {
        runCatching {
            val prevKey = VehicleIdGenerator.canonicalKey(previousVehicleId)
            val prevUrl = buildFirebaseUrl(fbUrl, "fleet/vehicleClaims/$prevKey", authToken)
            val resp = transport.getWithEtag(prevUrl)
            if (resp.responseCode in 200..299 && !resp.etag.isNullOrBlank()) {
                val existing = parseJsonObjectOrNull(resp.body)
                if (existing != null && existing.optString("deviceInstallId", "").trim() == myInstallId) {
                    transport.deleteWithIfMatch(prevUrl, resp.etag)
                }
            }
        }.onFailure { e ->
            logWarn("Best-effort release of previous claim $previousVehicleId failed: ${e.message}")
        }

        // Also mark our previous /fleet/vehicles node as released (preserving telemetry fields)
        // so another device can immediately claim the released Vehicle ID.
        runCatching {
            val prevVehicleUrl = buildFirebaseUrl(fbUrl, "fleet/vehicles/$previousVehicleId", authToken)
            val vehResp = transport.getWithEtag(prevVehicleUrl)
            if (vehResp.responseCode in 200..299 && !vehResp.etag.isNullOrBlank()) {
                val existingNode = parseJsonObjectOrNull(vehResp.body)
                if (existingNode != null && existingNode.optString("deviceInstallId", "").trim() == myInstallId) {
                    val releasedPayload = JSONObject(existingNode.toString()).apply {
                        put("claimReleased", true)
                        put("isRecording", false)
                    }
                    transport.putWithIfMatch(prevVehicleUrl, releasedPayload.toString(), vehResp.etag)
                }
            }
        }.onFailure { e ->
            logWarn("Best-effort release of previous vehicle node $previousVehicleId failed: ${e.message}")
        }
    }

    /**
     * Re-runs the Firebase duplicate check and atomic claim for any locally saved / unclaimed
     * Vehicle ID (called automatically on reconnect and on app startup).
     */
    suspend fun syncPendingVehicleIdClaim(): VehicleIdClaimOutcome? = syncMutex.withLock {
        val current = _config.value
        if (current.firebaseDbUrl.isBlank() || current.vehicleId.isBlank()) {
            return@withLock null
        }
        if (
            current.vehicleIdSyncState != VehicleIdSyncState.PENDING_SYNC &&
            current.vehicleIdSyncState != VehicleIdSyncState.UNCLAIMED
        ) {
            return@withLock null
        }

        var outcome = saveVehicleIdLocked(current.vehicleId, isUserEdit = current.isUserEditedVehicleId)
        if (outcome is VehicleIdClaimOutcome.TakenByOtherDevice) {
            if (!current.isUserEditedVehicleId) {
                // Untouched auto-generated default collided -> automatically try free alternatives
                // derived from the original base ID so suggestions increment cleanly (-2, -3, -4).
                var attempts = 0
                var candidate = outcome.suggestedAlternative
                val fbUrl = current.firebaseDbUrl.trimEnd('/')
                while (outcome is VehicleIdClaimOutcome.TakenByOtherDevice && attempts < MAX_DEFAULT_COLLISION_RETRIES) {
                    attempts++
                    outcome = saveVehicleIdLocked(candidate, isUserEdit = false)
                    if (outcome is VehicleIdClaimOutcome.TakenByOtherDevice) {
                        candidate = buildAlternativeSuggestion(
                            normalizedId = current.vehicleId,
                            fbUrl = fbUrl,
                            authToken = current.firebaseAuthToken
                        )
                    }
                }
                if (outcome is VehicleIdClaimOutcome.TakenByOtherDevice) {
                    persistVehicleIdLocally(
                        normalizedId = current.vehicleId,
                        isUserEdited = false,
                        syncState = VehicleIdSyncState.CONFLICT,
                        syncIssue = VehicleIdSyncIssue.DUPLICATE_CONFLICT,
                        suggestedAlternative = candidate
                    )
                }
            } else {
                // User manually edited this ID while offline and another device took it -> flag conflict.
                persistVehicleIdLocally(
                    normalizedId = current.vehicleId,
                    isUserEdited = true,
                    syncState = VehicleIdSyncState.CONFLICT,
                    syncIssue = VehicleIdSyncIssue.DUPLICATE_CONFLICT,
                    suggestedAlternative = outcome.suggestedAlternative
                )
            }
        }
        outcome
    }

    private fun persistVehicleIdLocally(
        normalizedId: String,
        isUserEdited: Boolean,
        syncState: VehicleIdSyncState,
        syncIssue: VehicleIdSyncIssue,
        suggestedAlternative: String?,
        claimedId: String? = null
    ) {
        val current = _config.value
        _config.value = current.copy(
            vehicleId = normalizedId,
            vehicleIdSyncState = syncState,
            vehicleIdSyncIssue = syncIssue,
            suggestedAlternativeId = suggestedAlternative,
            isUserEditedVehicleId = isUserEdited
        )
        if (!claimedId.isNullOrBlank()) {
            inMemoryClaimedId = claimedId
        }
        prefs?.edit()?.apply {
            putString(KEY_VEHICLE_ID, normalizedId)
            putBoolean(KEY_VEHICLE_ID_USER_EDITED, isUserEdited)
            putString(KEY_VEHICLE_ID_SYNC_STATE, syncState.name)
            putString(KEY_VEHICLE_ID_SYNC_ISSUE, syncIssue.name)
            if (suggestedAlternative.isNullOrBlank()) {
                remove(KEY_VEHICLE_ID_SUGGESTED_ALT)
            } else {
                putString(KEY_VEHICLE_ID_SUGGESTED_ALT, suggestedAlternative)
            }
            if (!claimedId.isNullOrBlank()) {
                putString(KEY_VEHICLE_ID_CLAIMED_ID, claimedId)
            }
            putBoolean(KEY_VEHICLE_ID_MIGRATED_V2, true)
            apply()
        }
    }

    fun setVehicleIdImmediate(id: String) {
        val normalized = id.trim().uppercase(Locale.US)
        persistVehicleIdLocally(
            normalizedId = normalized,
            isUserEdited = true,
            syncState = VehicleIdSyncState.PENDING_SYNC,
            syncIssue = VehicleIdSyncIssue.NONE,
            suggestedAlternative = null
        )
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
        inMemoryClaimedId = null
        cachedDeviceDefaultId = null
        isNetworkCallbackRegistered = false
        lastAutoSyncAttemptMs = 0L
        transport = HttpFleetRegistryTransport
        clock = { System.currentTimeMillis() }
        ioDispatcher = Dispatchers.IO
        deviceDefaultIdProvider = {
            VehicleIdGenerator.generateDefaultId(
                deviceName = null,
                bluetoothName = null,
                manufacturer = null,
                model = null,
                androidId = null,
                installId = getDeviceInstallId()
            )
        }
        _config.value = CloudConfig(
            vehicleId = "UNIT-TEST",
            firebaseDbUrl = "",
            firebaseAuthToken = "",
            liveTelemetryEnabled = true,
            cloudUploadUrl = "",
            autoUploadOnWifi = true,
            vehicleIdSyncState = VehicleIdSyncState.UNCLAIMED,
            vehicleIdSyncIssue = VehicleIdSyncIssue.NONE,
            suggestedAlternativeId = null,
            isUserEditedVehicleId = false
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
