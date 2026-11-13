package com.drivetest.lte5g.cloud

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.drivetest.lte5g.data.SessionRepository
import com.drivetest.lte5g.data.analytics.RfAnalyticsEngine
import com.drivetest.lte5g.export.GisExporter
import com.drivetest.lte5g.export.KmlExporter
import com.drivetest.lte5g.export.PdfReportExporter
import com.drivetest.lte5g.model.DriveTestSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object GoogleDriveAutoUploader {
    private const val TAG = "GoogleDriveUploader"

    private var ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    private var appContext: Context? = null
    private var isNetworkCallbackRegistered = false

    fun init(context: Context, dispatcher: CoroutineDispatcher = Dispatchers.IO) {
        appContext = context.applicationContext
        ioDispatcher = dispatcher
        registerWifiCallback(context.applicationContext)
    }

    fun isWifiConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val activeNetwork = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun registerWifiCallback(context: Context) {
        if (isNetworkCallbackRegistered) return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        try {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.d(TAG, "Wi-Fi connection detected, checking for pending cloud uploads...")
                    processPendingUploads()
                }
            })
            isNetworkCallbackRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "Could not register Wi-Fi network callback: ${e.message}")
        }
    }

    /**
     * Enqueue a session when recording finishes via WorkManager (UNMETERED constraint + no duplicates).
     */
    fun enqueueSessionForWifiUpload(session: DriveTestSession) {
        val config = CloudConfigRepository.config.value
        if (config.cloudUploadUrl.isBlank()) {
            return
        }

        val context = appContext ?: return
        CloudConfigRepository.setSessionUploadStatus(session.id, CloudUploadStatus.PENDING_WIFI)
        GoogleDriveUploadWorker.enqueueUpload(context, session.id)
    }

    /**
     * Trigger upload of all pending sessions when Wi-Fi is restored.
     */
    fun processPendingUploads() {
        val context = appContext ?: return
        if (!isWifiConnected(context)) return

        val config = CloudConfigRepository.config.value
        if (config.cloudUploadUrl.isBlank()) return

        val allSessions = SessionRepository.sessions.value
        val pendingSessions = allSessions.filter { session ->
            CloudConfigRepository.getSessionUploadStatus(session.id) == CloudUploadStatus.PENDING_WIFI
        }

        if (pendingSessions.isEmpty()) return
        Log.i(TAG, "Found ${pendingSessions.size} sessions waiting for Wi-Fi upload")

        pendingSessions.forEach { session ->
            uploadSessionAsync(session)
        }
    }

    /**
     * Upload a session immediately (can be called manually from SessionsScreen).
     */
    fun uploadSessionAsync(session: DriveTestSession, onComplete: ((Boolean, String) -> Unit)? = null) {
        val context = appContext ?: return
        val config = CloudConfigRepository.config.value

        if (config.cloudUploadUrl.isBlank()) {
            onComplete?.invoke(false, "ยังไม่ได้ตั้งค่า Cloud Upload Endpoint (CLOUD_UPLOAD_URL)")
            return
        }

        if (session.samples.isEmpty()) {
            onComplete?.invoke(false, "Session ไม่มีข้อมูลตัวอย่างสำหรับอัปโหลด")
            return
        }

        scope.launch(ioDispatcher) {
            CloudConfigRepository.setSessionUploadStatus(session.id, CloudUploadStatus.UPLOADING)
            try {
                val result = exportAndUploadSessionBundle(context, session, config)
                if (result.isSuccess) {
                    CloudConfigRepository.setSessionUploadStatus(session.id, CloudUploadStatus.UPLOADED)
                    onComplete?.invoke(true, "อัปโหลดขึ้น Google Drive ครบ 5 รูปแบบสำเร็จ!")
                } else {
                    CloudConfigRepository.setSessionUploadStatus(session.id, CloudUploadStatus.FAILED)
                    val errorMsg = result.exceptionOrNull()?.message ?: "Upload failed"
                    onComplete?.invoke(false, "อัปโหลดล้มเหลว: $errorMsg")
                }
            } catch (e: Exception) {
                CloudConfigRepository.setSessionUploadStatus(session.id, CloudUploadStatus.FAILED)
                onComplete?.invoke(false, "เกิดข้อผิดพลาด: ${e.message}")
            }
        }
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").trim('_')
    }

    /**
     * Export all 5 standard file formats and upload each to Google Drive.
     */
    suspend fun exportAndUploadSessionBundle(
        context: Context,
        session: DriveTestSession,
        config: CloudConfig
    ): Result<Unit> = withContext(ioDispatcher) {
        val baseName = sanitizeFileName(session.name).ifBlank { "Session_${session.id}" }
        val tempDir = File(context.cacheDir, "drive_upload_${session.id}").apply { mkdirs() }

        val filesToUpload = mutableListOf<Pair<File, String>>()

        try {
            // 1. KMZ
            val kmzFile = File(tempDir, "$baseName.kmz")
            FileOutputStream(kmzFile).use { os ->
                KmlExporter.exportKmz(session.samples, os)
            }
            filesToUpload.add(kmzFile to "application/vnd.google-earth.kmz")

            // 2. CSV
            val csvFile = File(tempDir, "$baseName.csv")
            FileOutputStream(csvFile).use { os ->
                KmlExporter.exportCsv(session.samples, os)
            }
            filesToUpload.add(csvFile to "text/csv")

            // 3. GeoJSON
            val geoJsonFile = File(tempDir, "$baseName.geojson")
            FileOutputStream(geoJsonFile).use { os ->
                GisExporter.exportGeoJson(session, os)
            }
            filesToUpload.add(geoJsonFile to "application/geo+json")

            // 4. MapInfo MIF/MID ZIP
            val mapInfoZipFile = File(tempDir, "${baseName}_mapinfo.zip")
            FileOutputStream(mapInfoZipFile).use { os ->
                GisExporter.exportMapInfoMifMid(session, os)
            }
            filesToUpload.add(mapInfoZipFile to "application/zip")

            // 5. PDF Acceptance Report
            val pdfFile = File(tempDir, "${baseName}_acceptance.pdf")
            FileOutputStream(pdfFile).use { os ->
                val analytics = RfAnalyticsEngine.analyze(session)
                PdfReportExporter.exportPdfReport(session, analytics, null, os)
            }
            filesToUpload.add(pdfFile to "application/pdf")

            // Upload each file
            for ((file, mimeType) in filesToUpload) {
                val uploadResult = uploadFileMultipart(
                    file = file,
                    mimeType = mimeType,
                    uploadUrl = config.cloudUploadUrl,
                    appAuthToken = config.firebaseAuthToken
                )
                if (uploadResult.isFailure) {
                    return@withContext Result.failure(uploadResult.exceptionOrNull() ?: Exception("Upload failed for ${file.name}"))
                }
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            // Cleanup temp files
            tempDir.deleteRecursively()
        }
    }

    /**
     * Upload one file through the Drive proxy Worker (multipart/form-data, app auth header).
     * The Worker holds the Google credential; this app never sees or sends a Google token.
     * Returns the created Drive file id.
     */
    fun uploadFileMultipart(
        file: File,
        mimeType: String,
        uploadUrl: String,
        appAuthToken: String
    ): Result<String> {
        val boundary = "dt_boundary_" + System.currentTimeMillis()
        var conn: HttpURLConnection? = null

        try {
            val url = URL(uploadUrl)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 30_000
                readTimeout = 60_000
                doInput = true
                doOutput = true
                setRequestProperty("x-app-auth", appAuthToken.trim())
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }

            val os = conn.outputStream
            val crlf = "\r\n"

            // Single part: the exported file (the Worker derives name and MIME from its headers).
            os.write(("--$boundary$crlf").toByteArray(Charsets.UTF_8))
            os.write(("Content-Disposition: form-data; name=\"file\"; filename=\"${file.name}\"$crlf").toByteArray(Charsets.UTF_8))
            os.write(("Content-Type: $mimeType$crlf$crlf").toByteArray(Charsets.UTF_8))
            file.inputStream().use { input ->
                input.copyTo(os)
            }
            os.write(crlf.toByteArray(Charsets.UTF_8))
            os.write(("--$boundary--$crlf").toByteArray(Charsets.UTF_8))
            os.flush()

            val responseCode = conn.responseCode
            return if (responseCode in 200..299) {
                val resp = conn.inputStream.bufferedReader(Charsets.UTF_8).use(BufferedReader::readText)
                val json = JSONObject(resp)
                Result.success(json.optString("id", ""))
            } else {
                val err = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use(BufferedReader::readText) ?: "HTTP $responseCode"
                Result.failure(Exception("Cloud upload error: $err (HTTP $responseCode)"))
            }
        } catch (e: Exception) {
            return Result.failure(e)
        } finally {
            conn?.disconnect()
        }
    }
}
