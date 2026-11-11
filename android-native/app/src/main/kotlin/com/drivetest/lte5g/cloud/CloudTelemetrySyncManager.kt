package com.drivetest.lte5g.cloud

import android.content.Context
import android.util.Log
import com.drivetest.lte5g.model.DriveTestSample
import com.drivetest.lte5g.model.DriveTestSession
import com.drivetest.lte5g.model.RatType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object CloudTelemetrySyncManager {
    private const val TAG = "CloudTelemetrySync"
    const val MAX_OFFLINE_QUEUE_SIZE = 5000

    private var ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    private var storageDir: File? = null
    private var isInitialized = false

    fun init(context: Context, customDir: File? = null, dispatcher: CoroutineDispatcher = Dispatchers.IO) {
        CloudConfigRepository.init(context)
        storageDir = customDir ?: File(context.filesDir, "cloud_queue").apply { if (!exists()) mkdirs() }
        ioDispatcher = dispatcher
        isInitialized = true
    }

    fun setStorageDirForTesting(dir: File, dispatcher: CoroutineDispatcher = Dispatchers.IO) {
        storageDir = dir
        ioDispatcher = dispatcher
        isInitialized = true
    }

    /**
     * Categorize RF anomaly state for live NOC alerts.
     */
    fun computeAlertState(rsrp: Int?, sinr: Double?): String {
        return when {
            rsrp != null && rsrp <= -110 -> "COVERAGE_HOLE"
            rsrp != null && rsrp >= -95 && sinr != null && sinr < 0.0 -> "INTERFERENCE"
            else -> "NORMAL"
        }
    }

    fun extractPrimaryMetrics(sample: DriveTestSample): Triple<String, Int?, Double?> {
        val rat = sample.metrics.ratType.name
        val rsrp = sample.metrics.nr?.ssRsrp ?: sample.metrics.lte?.rsrp
        val sinr = sample.metrics.nr?.ssSinr ?: sample.metrics.lte?.rssnr
        return Triple(rat, rsrp, sinr)
    }

    /**
     * Build the JSON payload for the live vehicle status node (/fleet/vehicles/{vehicleId}).
     */
    fun buildVehicleHeartbeatJson(
        config: CloudConfig,
        session: DriveTestSession,
        sample: DriveTestSample
    ): String {
        val (rat, rsrp, sinr) = extractPrimaryMetrics(sample)
        val rsrq = sample.metrics.nr?.ssRsrq ?: sample.metrics.lte?.rsrq
        val pci = sample.metrics.nr?.pci ?: sample.metrics.lte?.pci
        val band = sample.metrics.nr?.band?.let { "n$it" } ?: sample.metrics.lte?.band?.let { "B$it" }
        val cellId = sample.metrics.nr?.nci?.toString() ?: sample.metrics.lte?.eci?.toString()
        val alertState = computeAlertState(rsrp, sinr)

        val sim2 = sample.metricsSim2
        val sim2Rsrp = sim2?.let { it.nr?.ssRsrp ?: it.lte?.rsrp }
        val sim2Sinr = sim2?.let { it.nr?.ssSinr ?: it.lte?.rssnr }
        val sim2Pci = sim2?.let { it.nr?.pci ?: it.lte?.pci }
        val sim2Band = sim2?.let { it.nr?.band?.let { "n$it" } ?: it.lte?.band?.let { "B$it" } }

        val root = JSONObject().apply {
            put("vehicleId", config.vehicleId)
            put("sessionId", session.id.toString())
            put("isRecording", true)
            put("updatedAt", sample.timestamp)
            put("lat", sample.latitude)
            put("lon", sample.longitude)
            put("speedKmh", sample.speedKmh.toDouble())
            put("accuracyM", sample.accuracyMeters.toDouble())
            put("bearing", sample.bearing.toDouble())
            put("rat", rat)
            band?.let { put("band", it) }
            pci?.let { put("pci", it) }
            cellId?.let { put("cellId", it) }
            rsrp?.let { put("rsrp", it) }
            rsrq?.let { put("rsrq", it) }
            sinr?.let { put("sinr", it) }
            sample.metrics.operatorAlpha?.let { put("operator", it) }

            // Dual-SIM secondary info
            if (sim2 != null) {
                sim2.operatorAlpha?.let { put("sim2Operator", it) }
                put("sim2Rat", sim2.ratType.name)
                sim2Band?.let { put("sim2Band", it) }
                sim2Pci?.let { put("sim2Pci", it) }
                sim2Rsrp?.let { put("sim2Rsrp", it) }
                sim2Sinr?.let { put("sim2Sinr", it) }
            }

            put("sampleCount", session.samples.size + 1)
            put("alertState", alertState)
        }
        return root.toString()
    }

    /**
     * Build the JSON payload for a breadcrumb trail sample (/fleet/trails/{sessionId}/{sampleId}).
     */
    fun buildTrailPointJson(sample: DriveTestSample): String {
        val (rat, rsrp, sinr) = extractPrimaryMetrics(sample)
        val pci = sample.metrics.nr?.pci ?: sample.metrics.lte?.pci
        val root = JSONObject().apply {
            put("ts", sample.timestamp)
            put("lat", sample.latitude)
            put("lon", sample.longitude)
            rsrp?.let { put("rsrp", it) }
            sinr?.let { put("sinr", it) }
            pci?.let { put("pci", it) }
            put("rat", rat)
            put("speed", sample.speedKmh.toDouble())
        }
        return root.toString()
    }

    /**
     * Enqueue a new sample from the foreground service loop.
     */
    fun enqueueSample(session: DriveTestSession, sample: DriveTestSample) {
        val config = CloudConfigRepository.config.value
        if (!config.liveTelemetryEnabled || config.firebaseDbUrl.isBlank()) {
            return
        }

        scope.launch(ioDispatcher) {
            val heartbeatJson = buildVehicleHeartbeatJson(config, session, sample)
            val trailPointJson = buildTrailPointJson(sample)

            val success = trySendTelemetry(config, heartbeatJson, session.id.toString(), trailPointJson)
            if (!success) {
                saveToOfflineQueue(heartbeatJson, session.id.toString(), trailPointJson)
            } else {
                // If live push succeeded and we have queued offline points, attempt background flush
                flushOfflineQueueAsync(config)
            }
        }
    }

    private suspend fun trySendTelemetry(
        config: CloudConfig,
        heartbeatJson: String,
        sessionId: String,
        trailPointJson: String
    ): Boolean = withContext(ioDispatcher) {
        try {
            val authParam = if (config.firebaseAuthToken.isNotBlank()) "?auth=${config.firebaseAuthToken}" else ""
            val baseUrl = config.firebaseDbUrl.trimEnd('/')

            // 1. Update vehicle heartbeat via PUT
            val vehicleUrl = "$baseUrl/fleet/vehicles/${config.vehicleId}.json$authParam"
            val heartbeatSuccess = executeHttpRequest(vehicleUrl, "PUT", heartbeatJson)

            // 2. Append trail point via POST
            val trailUrl = "$baseUrl/fleet/trails/$sessionId.json$authParam"
            val trailSuccess = executeHttpRequest(trailUrl, "POST", trailPointJson)

            heartbeatSuccess && trailSuccess
        } catch (e: Exception) {
            Log.w(TAG, "Telemetry transmission failed: ${e.message}")
            false
        }
    }

    fun markSessionStopped() {
        val config = CloudConfigRepository.config.value
        if (config.firebaseDbUrl.isBlank()) return

        scope.launch(ioDispatcher) {
            try {
                val authParam = if (config.firebaseAuthToken.isNotBlank()) "?auth=${config.firebaseAuthToken}" else ""
                val baseUrl = config.firebaseDbUrl.trimEnd('/')
                val vehicleUrl = "$baseUrl/fleet/vehicles/${config.vehicleId}.json$authParam"

                val payload = JSONObject().apply {
                    put("isRecording", false)
                    put("updatedAt", System.currentTimeMillis())
                }.toString()

                executeHttpRequest(vehicleUrl, "PATCH", payload)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to mark vehicle stopped: ${e.message}")
            }
        }
    }

    private fun executeHttpRequest(urlString: String, method: String, payload: String?): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlString)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 6000
                readTimeout = 6000
                doInput = true
                if (payload != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                }
            }

            if (payload != null) {
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(payload)
                    writer.flush()
                }
            }

            val code = conn.responseCode
            code in 200..299
        } catch (e: Exception) {
            false
        } finally {
            conn?.disconnect()
        }
    }

    @Synchronized
    fun saveToOfflineQueue(heartbeatJson: String, sessionId: String, trailPointJson: String) {
        val dir = storageDir ?: return
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "cloud_offline_queue.jsonl")

        val entry = JSONObject().apply {
            put("hb", heartbeatJson)
            put("sid", sessionId)
            put("tp", trailPointJson)
        }.toString()

        try {
            // Check size limit: read lines if large
            if (file.exists() && file.length() > 500_000) {
                val lines = file.readLines()
                if (lines.size >= MAX_OFFLINE_QUEUE_SIZE) {
                    // Retain the newest entries
                    val pruned = lines.takeLast(MAX_OFFLINE_QUEUE_SIZE - 500)
                    file.writeText(pruned.joinToString("\n") + "\n")
                }
            }
            file.appendText(entry + "\n")
        } catch (e: Exception) {
            Log.e(TAG, "Failed writing to offline queue", e)
        }
    }

    @Synchronized
    fun getOfflineQueueCount(): Int {
        val file = File(storageDir ?: return 0, "cloud_offline_queue.jsonl")
        if (!file.exists()) return 0
        return try {
            file.useLines { it.count() }
        } catch (_: Exception) {
            0
        }
    }

    private fun flushOfflineQueueAsync(config: CloudConfig) {
        val dir = storageDir ?: return
        val file = File(dir, "cloud_offline_queue.jsonl")
        if (!file.exists() || file.length() == 0L) return

        scope.launch(ioDispatcher) {
            flushOfflineQueueSync(config)
        }
    }

    @Synchronized
    fun flushOfflineQueueSync(config: CloudConfig): Int {
        val dir = storageDir ?: return 0
        val file = File(dir, "cloud_offline_queue.jsonl")
        if (!file.exists() || file.length() == 0L) return 0

        val lines = try {
            file.readLines()
        } catch (_: Exception) {
            return 0
        }
        if (lines.isEmpty()) return 0

        var flushedCount = 0
        val remainingLines = mutableListOf<String>()

        for (line in lines) {
            if (line.isBlank()) continue
            try {
                val obj = JSONObject(line)
                val hb = obj.getString("hb")
                val sid = obj.getString("sid")
                val tp = obj.getString("tp")

                val success = trySendTelemetrySync(config, hb, sid, tp)
                if (success) {
                    flushedCount++
                } else {
                    remainingLines.add(line)
                }
            } catch (e: Exception) {
                // Malformed line, skip
            }
        }

        if (remainingLines.isEmpty()) {
            file.delete()
        } else {
            file.writeText(remainingLines.joinToString("\n") + "\n")
        }

        return flushedCount
    }

    private fun trySendTelemetrySync(
        config: CloudConfig,
        heartbeatJson: String,
        sessionId: String,
        trailPointJson: String
    ): Boolean {
        val authParam = if (config.firebaseAuthToken.isNotBlank()) "?auth=${config.firebaseAuthToken}" else ""
        val baseUrl = config.firebaseDbUrl.trimEnd('/')

        val vehicleUrl = "$baseUrl/fleet/vehicles/${config.vehicleId}.json$authParam"
        val heartbeatSuccess = executeHttpRequest(vehicleUrl, "PUT", heartbeatJson)

        val trailUrl = "$baseUrl/fleet/trails/$sessionId.json$authParam"
        val trailSuccess = executeHttpRequest(trailUrl, "POST", trailPointJson)

        return heartbeatSuccess && trailSuccess
    }

    suspend fun testFirebaseConnection(dbUrl: String, authToken: String?): Result<String> = withContext(ioDispatcher) {
        val cleanUrl = dbUrl.trim().trimEnd('/')
        if (cleanUrl.isBlank() || !cleanUrl.startsWith("http")) {
            return@withContext Result.failure(IllegalArgumentException("URL ต้องเริ่มต้นด้วย https://"))
        }

        val authParam = if (!authToken.isNullOrBlank()) "?auth=${authToken.trim()}" else ""
        val testUrl = "$cleanUrl/.json$authParam"

        var conn: HttpURLConnection? = null
        try {
            val url = URL(testUrl)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                doInput = true
                setRequestProperty("Accept", "application/json")
            }

            val code = conn.responseCode
            if (code in 200..299) {
                Result.success("เชื่อมต่อ Firebase Realtime Database สำเร็จ (HTTP $code)")
            } else {
                val err = conn.errorStream?.bufferedReader()?.use(BufferedReader::readText) ?: "HTTP $code"
                Result.failure(Exception("การเชื่อมต่อล้มเหลว: $err (HTTP $code)"))
            }
        } catch (e: Exception) {
            Result.failure(Exception("ไม่สามารถเชื่อมต่อได้: ${e.message}"))
        } finally {
            conn?.disconnect()
        }
    }
}
