package com.drivetest.lte5g.data

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class SessionDiagnosticSnapshot(
    val ticks: Int = 0,
    val samplesSaved: Int = 0,
    val samplesFlushed: Int = 0,
    val droppedNoLocation: Int = 0,
    val droppedInaccurateGps: Int = 0,
    val droppedRatFilter: Int = 0,
    val pollErrors: Int = 0,
    val loopExceptions: Int = 0,
    val flushErrors: Int = 0
)

/**
 * Append-only per-session structured diagnostic log (`session_<id>.diag.jsonl`) and
 * Android 11+ ApplicationExitInfo capture so any long-session stoppage can be traced.
 */
object SessionDiagnostics {
    private const val TAG = "SessionDiagnostics"
    private const val MAX_DIAG_BYTES = 512 * 1024L // 512 KB cap per session

    private val lock = ReentrantLock()
    @Volatile
    private var storageDir: File? = null

    @Volatile
    var lastProcessExitReason: String? = null
        private set

    private val ticks = AtomicInteger(0)
    private val samplesSaved = AtomicInteger(0)
    private val samplesFlushed = AtomicInteger(0)
    private val droppedNoLocation = AtomicInteger(0)
    private val droppedInaccurateGps = AtomicInteger(0)
    private val droppedRatFilter = AtomicInteger(0)
    private val pollErrors = AtomicInteger(0)
    private val loopExceptions = AtomicInteger(0)
    private val flushErrors = AtomicInteger(0)

    fun init(dir: File, context: Context? = null) {
        storageDir = dir
        if (!dir.exists()) {
            dir.mkdirs()
        }
        if (context != null && lastProcessExitReason == null) {
            lastProcessExitReason = captureLastProcessExitReason(context)
        }
    }

    fun resetForTesting() {
        resetCounters()
        lastProcessExitReason = null
    }

    fun resetCounters() {
        ticks.set(0)
        samplesSaved.set(0)
        samplesFlushed.set(0)
        droppedNoLocation.set(0)
        droppedInaccurateGps.set(0)
        droppedRatFilter.set(0)
        pollErrors.set(0)
        loopExceptions.set(0)
        flushErrors.set(0)
    }

    fun incTicks() { ticks.incrementAndGet() }
    fun incSamplesSaved() { samplesSaved.incrementAndGet() }
    fun addSamplesFlushed(count: Int) { if (count > 0) samplesFlushed.addAndGet(count) }
    fun incDroppedNoLocation() { droppedNoLocation.incrementAndGet() }
    fun incDroppedInaccurateGps() { droppedInaccurateGps.incrementAndGet() }
    fun incDroppedRatFilter() { droppedRatFilter.incrementAndGet() }
    fun incPollErrors() { pollErrors.incrementAndGet() }
    fun incLoopExceptions() { loopExceptions.incrementAndGet() }
    fun incFlushErrors() { flushErrors.incrementAndGet() }

    fun snapshotCounters(): SessionDiagnosticSnapshot = SessionDiagnosticSnapshot(
        ticks = ticks.get(),
        samplesSaved = samplesSaved.get(),
        samplesFlushed = samplesFlushed.get(),
        droppedNoLocation = droppedNoLocation.get(),
        droppedInaccurateGps = droppedInaccurateGps.get(),
        droppedRatFilter = droppedRatFilter.get(),
        pollErrors = pollErrors.get(),
        loopExceptions = loopExceptions.get(),
        flushErrors = flushErrors.get()
    )

    fun getDiagnosticFile(sessionId: Long): File? {
        val dir = storageDir ?: return null
        return File(dir, "session_${sessionId}.diag.jsonl")
    }

    fun recordEvent(sessionId: Long, event: String, details: Map<String, Any?> = emptyMap()) {
        val file = getDiagnosticFile(sessionId) ?: return
        lock.withLock {
            try {
                if (file.exists() && file.length() >= MAX_DIAG_BYTES) {
                    return
                }
                val obj = JSONObject()
                obj.put("ts", System.currentTimeMillis())
                obj.put("event", event)
                for ((k, v) in details) {
                    obj.put(k, v ?: JSONObject.NULL)
                }
                val line = obj.toString() + "\n"
                FileOutputStream(file, true).use { fos ->
                    fos.write(line.toByteArray(Charsets.UTF_8))
                }
            } catch (e: Exception) {
                safeLogWarn("Failed writing diagnostic event $event for session $sessionId: ${e.message}")
            }
        }
    }

    fun recordHeartbeat(
        sessionId: Long,
        heapUsedMb: Long,
        batteryPct: Int? = null,
        ignoringBatteryOpt: Boolean? = null,
        lastGpsFixAgeMs: Long? = null
    ) {
        val snap = snapshotCounters()
        recordEvent(
            sessionId = sessionId,
            event = "HEARTBEAT",
            details = mapOf(
                "ticks" to snap.ticks,
                "samplesSaved" to snap.samplesSaved,
                "samplesFlushed" to snap.samplesFlushed,
                "droppedNoLocation" to snap.droppedNoLocation,
                "droppedInaccurateGps" to snap.droppedInaccurateGps,
                "droppedRatFilter" to snap.droppedRatFilter,
                "pollErrors" to snap.pollErrors,
                "loopExceptions" to snap.loopExceptions,
                "flushErrors" to snap.flushErrors,
                "heapUsedMb" to heapUsedMb,
                "batteryPct" to batteryPct,
                "ignoringBatteryOpt" to ignoringBatteryOpt,
                "lastGpsFixAgeMs" to lastGpsFixAgeMs
            )
        )
    }

    fun readDiagnosticsText(sessionId: Long): String? {
        val file = getDiagnosticFile(sessionId) ?: return null
        return lock.withLock {
            try {
                if (file.exists()) file.readText(Charsets.UTF_8) else null
            } catch (e: Exception) {
                safeLogWarn("Failed reading diagnostics for session $sessionId: ${e.message}")
                null
            }
        }
    }

    fun deleteDiagnostics(sessionId: Long) {
        val file = getDiagnosticFile(sessionId) ?: return
        lock.withLock {
            try {
                if (file.exists()) file.delete()
            } catch (e: Exception) {
                safeLogWarn("Failed deleting diagnostics for session $sessionId: ${e.message}")
            }
        }
    }

    fun captureLastProcessExitReason(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
            val reasons = am.getHistoricalProcessExitReasons(context.packageName, 0, 5)
            val latest = reasons.firstOrNull() ?: return null
            formatExitInfo(latest)
        } catch (e: Exception) {
            safeLogWarn("Unable to query historical process exit reasons: ${e.message}")
            null
        }
    }

    private fun formatExitInfo(info: ApplicationExitInfo): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return "UNKNOWN"
        val reasonName = when (info.reason) {
            ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
            ApplicationExitInfo.REASON_CRASH -> "CRASH"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
            ApplicationExitInfo.REASON_ANR -> "ANR"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
            ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
            ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
            ApplicationExitInfo.REASON_OTHER -> "OTHER"
            else -> "REASON_${info.reason}"
        }
        val desc = info.description?.take(80)?.takeIf { it.isNotBlank() }
        return if (desc != null) {
            "$reasonName ($desc, pss=${info.pss}KB)"
        } else {
            "$reasonName (status=${info.status}, pss=${info.pss}KB)"
        }
    }

    private fun safeLogWarn(msg: String) {
        try {
            Log.w(TAG, msg)
        } catch (_: Throwable) {
            // Unit tests without Android Log mock
        }
    }
}
