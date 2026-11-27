package com.drivetest.lte5g.data

import android.content.Context
import android.util.Log
import com.drivetest.lte5g.model.CellMetrics
import com.drivetest.lte5g.model.DriveTestSample
import com.drivetest.lte5g.model.DriveTestSession
import com.drivetest.lte5g.model.LteMetrics
import com.drivetest.lte5g.model.MeasurementCadence
import com.drivetest.lte5g.model.NeighborCell
import com.drivetest.lte5g.model.NrMetrics
import com.drivetest.lte5g.model.RatLockMode
import com.drivetest.lte5g.model.RatType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class ActiveSessionPointer(
    val sessionId: Long,
    val name: String,
    val ratLockMode: RatLockMode,
    val cadence: MeasurementCadence,
    val simSlot: Int,
    val simDisplayName: String?,
    val isDualSimLogging: Boolean,
    val sim2DisplayName: String?,
    val startTime: Long
)

/**
 * Append-only chunked storage yielding O(1) immutable snapshot views without copying the list.
 * Each 256-element chunk is never reallocated once populated; readers holding a [SnapshotView]
 * observe a fixed [size] whose indices are guaranteed written prior to StateFlow publication.
 */
private class ChunkedSampleLog {
    companion object {
        private const val CHUNK_SHIFT = 8
        private const val CHUNK_SIZE = 1 shl CHUNK_SHIFT // 256
        private const val CHUNK_MASK = CHUNK_SIZE - 1
    }

    @Volatile
    private var chunks: Array<Array<DriveTestSample?>> = emptyArray()
    private var count: Int = 0

    fun append(sample: DriveTestSample): List<DriveTestSample> {
        val idx = count
        val chunkIdx = idx ushr CHUNK_SHIFT
        val offset = idx and CHUNK_MASK
        var currentChunks = chunks
        if (chunkIdx >= currentChunks.size) {
            val newCap = maxOf(4, if (currentChunks.isEmpty()) 4 else currentChunks.size * 2)
            val resized = arrayOfNulls<Array<DriveTestSample?>>(maxOf(newCap, chunkIdx + 1))
            currentChunks.copyInto(resized)
            for (i in currentChunks.size until resized.size) {
                resized[i] = arrayOfNulls(CHUNK_SIZE)
            }
            @Suppress("UNCHECKED_CAST")
            currentChunks = resized as Array<Array<DriveTestSample?>>
            chunks = currentChunks
        }
        currentChunks[chunkIdx][offset] = sample
        count = idx + 1
        return SnapshotView(currentChunks, count)
    }

    fun appendAll(samples: List<DriveTestSample>): List<DriveTestSample> {
        var view: List<DriveTestSample> = SnapshotView(chunks, count)
        for (s in samples) {
            view = append(s)
        }
        return view
    }

    private class SnapshotView(
        private val snapshotChunks: Array<Array<DriveTestSample?>>,
        override val size: Int
    ) : AbstractList<DriveTestSample>(), RandomAccess {
        override fun get(index: Int): DriveTestSample {
            if (index !in 0 until size) {
                throw IndexOutOfBoundsException("Index $index out of bounds for length $size")
            }
            return snapshotChunks[index ushr CHUNK_SHIFT][index and CHUNK_MASK]!!
        }
    }
}

private class ActiveSessionAccumulator(
    val sessionId: Long,
    initialSamples: List<DriveTestSample> = emptyList()
) {
    val log = ChunkedSampleLog()
    val pendingFlush = ArrayList<DriveTestSample>(32)
    var lastFlushAtMs: Long = System.currentTimeMillis()
    var lastSampleTimestamp: Long? = null

    var rsrpSum: Long = 0L
    var rsrpCount: Int = 0
    var rsrqSum: Long = 0L
    var rsrqCount: Int = 0
    var sinrSum: Double = 0.0
    var sinrCount: Int = 0

    var rsrpSim2Sum: Long = 0L
    var rsrpSim2Count: Int = 0
    var sinrSim2Sum: Double = 0.0
    var sinrSim2Count: Int = 0

    init {
        if (initialSamples.isNotEmpty()) {
            log.appendAll(initialSamples)
            for (s in initialSamples) {
                accumulateStats(s)
            }
        }
    }

    fun add(sample: DriveTestSample): List<DriveTestSample> {
        val snapshot = log.append(sample)
        pendingFlush.add(sample)
        accumulateStats(sample)
        return snapshot
    }

    private fun accumulateStats(sample: DriveTestSample) {
        lastSampleTimestamp = sample.timestamp
        (sample.metrics.nr?.ssRsrp ?: sample.metrics.lte?.rsrp)?.let {
            rsrpSum += it
            rsrpCount++
        }
        (sample.metrics.nr?.ssRsrq ?: sample.metrics.lte?.rsrq)?.let {
            rsrqSum += it
            rsrqCount++
        }
        (sample.metrics.nr?.ssSinr ?: sample.metrics.lte?.rssnr)?.let {
            sinrSum += it
            sinrCount++
        }
        sample.metricsSim2?.let { m2 ->
            (m2.nr?.ssRsrp ?: m2.lte?.rsrp)?.let {
                rsrpSim2Sum += it
                rsrpSim2Count++
            }
            (m2.nr?.ssSinr ?: m2.lte?.rssnr)?.let {
                sinrSim2Sum += it
                sinrSim2Count++
            }
        }
    }

    fun avgRsrp(): Int? = if (rsrpCount > 0) (rsrpSum.toDouble() / rsrpCount).toInt() else null
    fun avgRsrq(): Int? = if (rsrqCount > 0) (rsrqSum.toDouble() / rsrqCount).toInt() else null
    fun avgSinr(): Double? = if (sinrCount > 0) Math.round((sinrSum / sinrCount) * 10.0) / 10.0 else null
    fun avgRsrpSim2(): Int? = if (rsrpSim2Count > 0) (rsrpSim2Sum.toDouble() / rsrpSim2Count).toInt() else null
    fun avgSinrSim2(): Double? = if (sinrSim2Count > 0) Math.round((sinrSim2Sum / sinrSim2Count) * 10.0) / 10.0 else null
}

object SessionRepository {
    private const val TAG = "SessionRepository"
    private const val META_SUFFIX = ".meta.json"
    private const val SAMPLES_SUFFIX = ".samples.ndjson"
    private const val LEGACY_SUFFIX = ".json"
    private const val POINTER_FILE_NAME = "active_session.pointer.json"
    private const val FLUSH_AWAIT_TIMEOUT_SEC = 10L

    internal var flushBatchSize: Int = 10
    internal var flushIntervalMs: Long = 15_000L

    @Volatile
    private var writerThread: Thread? = null
    private val writerExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SessionJournalWriter").apply {
            isDaemon = true
            writerThread = this
        }
    }
    private val diskLock = ReentrantLock()

    private val _sessions = MutableStateFlow<List<DriveTestSession>>(emptyList())
    val sessions: StateFlow<List<DriveTestSession>> = _sessions.asStateFlow()

    private val _activeSession = MutableStateFlow<DriveTestSession?>(null)
    val activeSession: StateFlow<DriveTestSession?> = _activeSession.asStateFlow()

    private val _selectedSessionForMap = MutableStateFlow<DriveTestSession?>(null)
    val selectedSessionForMap: StateFlow<DriveTestSession?> = _selectedSessionForMap.asStateFlow()

    @Volatile
    private var storageDir: File? = null
    @Volatile
    private var hasLoadedOnce: Boolean = false
    private var lastSessionId: Long = 0L
    private var activeAccumulator: ActiveSessionAccumulator? = null
    private val loadedSampleSessionIds = HashSet<Long>()

    @Synchronized
    private fun generateUniqueSessionId(): Long {
        var now = System.currentTimeMillis()
        if (now <= lastSessionId) {
            now = lastSessionId + 1
        }
        lastSessionId = now
        return now
    }

    @Synchronized
    fun resetForTesting() {
        _sessions.value = emptyList()
        _activeSession.value = null
        _selectedSessionForMap.value = null
        activeAccumulator = null
        loadedSampleSessionIds.clear()
        lastSessionId = 0L
        hasLoadedOnce = false
        flushBatchSize = 10
        flushIntervalMs = 15_000L
        SessionDiagnostics.resetForTesting()
    }

    fun initStorage(context: Context) {
        val dir = File(context.filesDir, "sessions")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val alreadySameDir = storageDir?.absolutePath == dir.absolutePath && hasLoadedOnce
        storageDir = dir
        SessionDiagnostics.init(dir, context)
        if (!alreadySameDir) {
            loadSavedSessions()
        }
    }

    fun initStorage(customDir: File) {
        if (!customDir.exists()) {
            customDir.mkdirs()
        }
        storageDir = customDir
        SessionDiagnostics.init(customDir, null)
        loadSavedSessionsSync()
    }

    /**
     * Ensures sessions have been loaded synchronously (used by WorkManager / START_STICKY recovery).
     */
    fun ensureLoadedSync(context: Context? = null) {
        if (storageDir == null && context != null) {
            val dir = File(context.filesDir, "sessions")
            if (!dir.exists()) dir.mkdirs()
            storageDir = dir
            SessionDiagnostics.init(dir, context)
        }
        if (!hasLoadedOnce) {
            loadSavedSessionsSync()
        }
    }

    fun getNextSessionNumber(): Int {
        return (_sessions.value.maxOfOrNull { it.sessionNumber } ?: 0) + 1
    }

    fun getDefaultSessionName(number: Int = getNextSessionNumber()): String {
        val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        return "Session #$number ($timeStr)"
    }

    @Synchronized
    fun createNewSession(
        name: String? = null,
        lockMode: RatLockMode = RatLockMode.AUTO,
        cadence: MeasurementCadence = MeasurementCadence.PRECISE,
        simSlot: Int = 0,
        simDisplayName: String? = null,
        isDualSimLogging: Boolean = false,
        sim2DisplayName: String? = null
    ): DriveTestSession {
        if (storageDir != null && !hasLoadedOnce) {
            loadSavedSessionsSync()
        }

        // If there's an ongoing active recording session, finish it first
        if (_activeSession.value?.isRecording == true) {
            finishCurrentSession()
        }

        val sessionNumber = getNextSessionNumber()
        val sessionName = name?.takeIf { it.isNotBlank() } ?: getDefaultSessionName(sessionNumber)
        val now = System.currentTimeMillis()
        val sessionId = generateUniqueSessionId()

        val newSession = DriveTestSession(
            id = sessionId,
            sessionNumber = sessionNumber,
            name = sessionName,
            startTime = now,
            endTime = null,
            ratLockMode = lockMode,
            cadence = cadence,
            sampleCount = 0,
            avgRsrp = null,
            avgRsrq = null,
            avgSinr = null,
            isRecording = true,
            samples = emptyList(),
            simSlot = simSlot,
            simDisplayName = simDisplayName,
            isDualSimLogging = isDualSimLogging,
            sim2DisplayName = sim2DisplayName,
            recoveredFromCrash = false,
            recoveryNote = null
        )

        activeAccumulator = ActiveSessionAccumulator(sessionId)
        _activeSession.value = newSession
        _selectedSessionForMap.value = newSession
        _sessions.value = listOf(newSession) + _sessions.value.filter { it.id != newSession.id }

        // Synchronously persist tiny initial metadata & active pointer so even immediate kill is recoverable
        persistMetadataSync(newSession)
        writeActiveSessionPointerSync(newSession)
        SessionDiagnostics.resetCounters()
        SessionDiagnostics.recordEvent(
            sessionId = sessionId,
            event = "SESSION_CREATED",
            details = mapOf(
                "name" to sessionName,
                "lockMode" to lockMode.name,
                "cadence" to cadence.name,
                "dualSim" to isDualSimLogging
            )
        )
        return newSession
    }

    /**
     * Resumes an interrupted session (identified via [readActiveSessionPointer]) after a START_STICKY
     * process restart without losing any previously flushed samples on disk.
     */
    @Synchronized
    fun resumeSessionForRecovery(sessionId: Long): DriveTestSession? {
        if (storageDir != null && !hasLoadedOnce) {
            loadSavedSessionsSync()
        }
        val existing = ensureSessionSamplesLoaded(sessionId)
            ?: _sessions.value.firstOrNull { it.id == sessionId }
            ?: return null
        val resumed = existing.copy(
            endTime = null,
            isRecording = true
        )
        val acc = ActiveSessionAccumulator(resumed.id, resumed.samples)
        activeAccumulator = acc
        val updated = resumed.copy(
            sampleCount = resumed.samples.size,
            avgRsrp = acc.avgRsrp(),
            avgRsrq = acc.avgRsrq(),
            avgSinr = acc.avgSinr(),
            avgRsrpSim2 = acc.avgRsrpSim2(),
            avgSinrSim2 = acc.avgSinrSim2()
        )
        _activeSession.value = updated
        _selectedSessionForMap.value = updated
        replaceSessionInList(updated)
        persistMetadataSync(updated)
        writeActiveSessionPointerSync(updated)
        SessionDiagnostics.recordEvent(
            sessionId = updated.id,
            event = "START_STICKY_RESUME",
            details = mapOf(
                "existingSamples" to updated.samples.size,
                "lastExitReason" to SessionDiagnostics.lastProcessExitReason
            )
        )
        return updated
    }

    @Synchronized
    fun renameSession(sessionId: Long, newName: String) {
        if (newName.isBlank()) return
        val currentList = _sessions.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == sessionId }
        if (index != -1) {
            val updated = currentList[index].copy(name = newName)
            currentList[index] = updated
            _sessions.value = currentList

            if (_activeSession.value?.id == sessionId) {
                _activeSession.value = updated
                if (updated.isRecording) {
                    writeActiveSessionPointerSync(updated)
                }
            }
            if (_selectedSessionForMap.value?.id == sessionId) {
                _selectedSessionForMap.value = updated
            }
            persistMetadataAsync(updated)
        }
    }

    @Synchronized
    fun addSample(sample: DriveTestSample) {
        val current = _activeSession.value ?: return
        val acc = activeAccumulator?.takeIf { it.sessionId == current.id }
            ?: ActiveSessionAccumulator(current.id, current.samples).also { activeAccumulator = it }

        val updatedSamples = acc.add(sample)
        val updatedSession = current.copy(
            sampleCount = updatedSamples.size,
            avgRsrp = acc.avgRsrp(),
            avgRsrq = acc.avgRsrq(),
            avgSinr = acc.avgSinr(),
            avgRsrpSim2 = acc.avgRsrpSim2(),
            avgSinrSim2 = acc.avgSinrSim2(),
            samples = updatedSamples
        )

        _activeSession.value = updatedSession
        if (_selectedSessionForMap.value?.id == current.id) {
            _selectedSessionForMap.value = updatedSession
        }
        replaceSessionInList(updatedSession)
        SessionDiagnostics.incSamplesSaved()

        val now = System.currentTimeMillis()
        if (acc.pendingFlush.size >= flushBatchSize || (now - acc.lastFlushAtMs) >= flushIntervalMs) {
            val batchToFlush = ArrayList(acc.pendingFlush)
            acc.pendingFlush.clear()
            acc.lastFlushAtMs = now
            enqueueJournalFlush(updatedSession, batchToFlush)
        }
    }

    /**
     * Synchronously drains any previously queued background flushes (strict FIFO) plus any
     * remaining buffered samples and metadata for the active session before returning.
     */
    fun flushActiveSessionSync(): Int {
        val snapshot: Pair<DriveTestSession, List<DriveTestSample>>? = synchronized(this) {
            val current = _activeSession.value ?: return@synchronized null
            val acc = activeAccumulator?.takeIf { it.sessionId == current.id }
            val batch = if (acc != null && acc.pendingFlush.isNotEmpty()) {
                val copy = ArrayList(acc.pendingFlush)
                acc.pendingFlush.clear()
                acc.lastFlushAtMs = System.currentTimeMillis()
                copy
            } else {
                emptyList()
            }
            current to batch
        }

        if (snapshot == null) {
            awaitPendingWrites()
            return 0
        }

        val (session, batch) = snapshot
        return try {
            writerExecutor.submit<Int> {
                writeBatchAndMetadataSync(session, batch)
            }.get(FLUSH_AWAIT_TIMEOUT_SEC, TimeUnit.SECONDS)
        } catch (e: Exception) {
            safeLogError("Timed out or failed awaiting journal flush for session ${session.id}", e)
            writeBatchAndMetadataSync(session, batch)
        }
    }

    fun finishCurrentSession() {
        val finishPayload: Pair<DriveTestSession, List<DriveTestSample>> = synchronized(this) {
            val current = _activeSession.value ?: return
            if (!current.isRecording) return

            val acc = activeAccumulator?.takeIf { it.sessionId == current.id }
            val remainingBatch = if (acc != null && acc.pendingFlush.isNotEmpty()) {
                val copy = ArrayList(acc.pendingFlush)
                acc.pendingFlush.clear()
                acc.lastFlushAtMs = System.currentTimeMillis()
                copy
            } else {
                emptyList()
            }

            val finished = current.copy(
                endTime = System.currentTimeMillis(),
                isRecording = false
            )

            activeAccumulator = null
            _activeSession.value = finished
            replaceSessionInList(finished)
            if (_selectedSessionForMap.value?.id == current.id) {
                _selectedSessionForMap.value = finished
            }
            finished to remainingBatch
        }

        val (finished, remainingBatch) = finishPayload
        // Strictly FIFO: awaits all earlier async NDJSON batches, appends remaining delta, writes final meta & removes pointer
        try {
            writerExecutor.submit {
                writeBatchAndMetadataSync(finished, remainingBatch)
                deleteActiveSessionPointerSync()
            }.get(FLUSH_AWAIT_TIMEOUT_SEC, TimeUnit.SECONDS)
        } catch (e: Exception) {
            safeLogError("Synchronous finalization fallback for session ${finished.id}", e)
            writeBatchAndMetadataSync(finished, remainingBatch)
            deleteActiveSessionPointerSync()
        }

        SessionDiagnostics.recordEvent(
            sessionId = finished.id,
            event = "SESSION_FINALIZED",
            details = mapOf(
                "sampleCount" to finished.sampleCount,
                "durationMs" to ((finished.endTime ?: finished.startTime) - finished.startTime)
            )
        )
    }

    fun deleteSession(sessionId: Long) {
        val deletedActive = synchronized(this) {
            _sessions.value = _sessions.value.filter { it.id != sessionId }
            val wasActive = _activeSession.value?.id == sessionId
            if (wasActive) {
                _activeSession.value = null
                activeAccumulator = null
            }
            if (_selectedSessionForMap.value?.id == sessionId) {
                _selectedSessionForMap.value = _sessions.value.firstOrNull()
            }
            loadedSampleSessionIds.remove(sessionId)
            wasActive
        }

        writerExecutor.execute {
            if (deletedActive) {
                deleteActiveSessionPointerSync()
            }
            diskLock.withLock {
                val dir = storageDir ?: return@withLock
                try {
                    File(dir, "session_${sessionId}$META_SUFFIX").delete()
                    File(dir, "session_${sessionId}$META_SUFFIX.tmp").delete()
                    File(dir, "session_${sessionId}$SAMPLES_SUFFIX").delete()
                    File(dir, "session_${sessionId}$LEGACY_SUFFIX").delete()
                    SessionDiagnostics.deleteDiagnostics(sessionId)
                } catch (e: Exception) {
                    safeLogError("Failed to delete session files $sessionId", e)
                }
            }
        }
    }

    fun selectSessionForMap(session: DriveTestSession?) {
        _selectedSessionForMap.value = session
        if (session != null && session.samples.isEmpty() && session.sampleCount > 0) {
            loadSessionSamplesAsync(session.id)
        }
    }

    /**
     * Lazy-loads samples for [sessionId] from disk if not already in memory.
     * Safe to call from background threads; updates [sessions] and [selectedSessionForMap].
     */
    fun ensureSessionSamplesLoaded(sessionId: Long): DriveTestSession? {
        val current = synchronized(this) {
            val found = _sessions.value.firstOrNull { it.id == sessionId } ?: return null
            if (found.samples.isNotEmpty() || found.sampleCount == 0 || sessionId in loadedSampleSessionIds) {
                return found
            }
            found
        }

        val dir = storageDir ?: return current
        awaitPendingWrites()
        val loadedSamples = diskLock.withLock {
            val samplesFile = File(dir, "session_${sessionId}$SAMPLES_SUFFIX")
            if (samplesFile.exists()) {
                readSamplesNdjson(samplesFile, sessionId)
            } else {
                val legacyFile = File(dir, "session_${sessionId}$LEGACY_SUFFIX")
                if (legacyFile.exists()) {
                    try {
                        jsonToLegacySession(JSONObject(legacyFile.readText(Charsets.UTF_8))).samples
                    } catch (e: Exception) {
                        safeLogError("Error lazy-loading legacy samples for $sessionId", e)
                        emptyList()
                    }
                } else {
                    emptyList()
                }
            }
        }

        return synchronized(this) {
            val latest = _sessions.value.firstOrNull { it.id == sessionId } ?: return@synchronized null
            if (latest.samples.isNotEmpty()) {
                loadedSampleSessionIds.add(sessionId)
                return@synchronized latest
            }
            val hydrated = latest.copy(
                samples = loadedSamples,
                sampleCount = maxOf(latest.sampleCount, loadedSamples.size)
            )
            loadedSampleSessionIds.add(sessionId)
            replaceSessionInList(hydrated)
            if (_selectedSessionForMap.value?.id == sessionId) {
                _selectedSessionForMap.value = hydrated
            }
            hydrated
        }
    }

    fun loadSessionSamplesAsync(sessionId: Long, onLoaded: ((DriveTestSession?) -> Unit)? = null) {
        val existing = synchronized(this) {
            _sessions.value.firstOrNull { it.id == sessionId }
        }
        if (existing != null && (existing.samples.isNotEmpty() || existing.sampleCount == 0 || sessionId in loadedSampleSessionIds)) {
            onLoaded?.invoke(existing)
            return
        }
        writerExecutor.execute {
            val hydrated = ensureSessionSamplesLoaded(sessionId)
            onLoaded?.invoke(hydrated)
        }
    }

    fun readActiveSessionPointer(): ActiveSessionPointer? {
        val dir = storageDir ?: return null
        return diskLock.withLock {
            val pointerFile = File(dir, POINTER_FILE_NAME)
            if (!pointerFile.exists()) return@withLock null
            try {
                val obj = JSONObject(pointerFile.readText(Charsets.UTF_8))
                ActiveSessionPointer(
                    sessionId = obj.getLong("sessionId"),
                    name = obj.optString("name", "Recovered Session"),
                    ratLockMode = parseRatLockMode(obj.optString("ratLockMode", RatLockMode.AUTO.name)),
                    cadence = parseCadence(obj.optString("cadence", MeasurementCadence.PRECISE.name)),
                    simSlot = obj.optInt("simSlot", 0),
                    simDisplayName = if (obj.isNull("simDisplayName")) null else obj.optString("simDisplayName"),
                    isDualSimLogging = obj.optBoolean("isDualSimLogging", false),
                    sim2DisplayName = if (obj.isNull("sim2DisplayName")) null else obj.optString("sim2DisplayName"),
                    startTime = obj.optLong("startTime", System.currentTimeMillis())
                )
            } catch (e: Exception) {
                safeLogWarn("Failed reading active session pointer: ${e.message}")
                null
            }
        }
    }

    fun clearActiveSessionPointer() {
        deleteActiveSessionPointerSync()
    }

    private fun replaceSessionInList(updated: DriveTestSession) {
        val list = _sessions.value.toMutableList()
        val index = list.indexOfFirst { it.id == updated.id }
        if (index != -1) {
            list[index] = updated
            _sessions.value = list
        }
    }

    private fun awaitPendingWrites() {
        if (Thread.currentThread() === writerThread) return
        try {
            writerExecutor.submit {}.get(FLUSH_AWAIT_TIMEOUT_SEC, TimeUnit.SECONDS)
        } catch (e: Exception) {
            safeLogWarn("Failed awaiting pending journal writes: ${e.message}")
        }
    }

    private fun enqueueJournalFlush(sessionSnapshot: DriveTestSession, batch: List<DriveTestSample>) {
        writerExecutor.execute {
            writeBatchAndMetadataSync(sessionSnapshot, batch)
        }
    }

    private fun persistMetadataAsync(session: DriveTestSession) {
        writerExecutor.execute {
            persistMetadataSync(session)
        }
    }

    private fun writeBatchAndMetadataSync(session: DriveTestSession, batch: List<DriveTestSample>): Int {
        val dir = storageDir ?: return 0
        return diskLock.withLock {
            try {
                if (batch.isNotEmpty()) {
                    val samplesFile = File(dir, "session_${session.id}$SAMPLES_SUFFIX")
                    val sb = StringBuilder(batch.size * 256)
                    for (sample in batch) {
                        sb.append(sampleToJson(sample).toString()).append('\n')
                    }
                    FileOutputStream(samplesFile, true).use { fos ->
                        fos.write(sb.toString().toByteArray(Charsets.UTF_8))
                        fos.flush()
                        try {
                            fos.fd.sync()
                        } catch (_: Exception) {
                            // Ignore fsync failure on virtual filesystems
                        }
                    }
                    SessionDiagnostics.addSamplesFlushed(batch.size)
                }
                writeMetaFileAtomically(dir, session)
                batch.size
            } catch (e: Exception) {
                SessionDiagnostics.incFlushErrors()
                SessionDiagnostics.recordEvent(
                    sessionId = session.id,
                    event = "FLUSH_FAIL",
                    details = mapOf("batchSize" to batch.size, "error" to (e.message ?: e.javaClass.simpleName))
                )
                safeLogError("Failed flushing batch for session ${session.id}", e)
                0
            }
        }
    }

    private fun persistMetadataSync(session: DriveTestSession) {
        val dir = storageDir ?: return
        diskLock.withLock {
            try {
                writeMetaFileAtomically(dir, session)
            } catch (e: Exception) {
                SessionDiagnostics.incFlushErrors()
                safeLogError("Failed persisting metadata for session ${session.id}", e)
            }
        }
    }

    private fun writeMetaFileAtomically(dir: File, session: DriveTestSession) {
        val targetFile = File(dir, "session_${session.id}$META_SUFFIX")
        val tmpFile = File(dir, "session_${session.id}$META_SUFFIX.tmp")
        val bytes = metadataToJson(session).toString().toByteArray(Charsets.UTF_8)
        FileOutputStream(tmpFile, false).use { fos ->
            fos.write(bytes)
            fos.flush()
            try {
                fos.fd.sync()
            } catch (_: Exception) {
                // Ignore fsync failure on virtual filesystems
            }
        }
        if (!tmpFile.renameTo(targetFile)) {
            if (targetFile.exists()) {
                targetFile.delete()
            }
            if (!tmpFile.renameTo(targetFile)) {
                targetFile.writeBytes(bytes)
                tmpFile.delete()
            }
        }
    }

    private fun writeActiveSessionPointerSync(session: DriveTestSession) {
        val dir = storageDir ?: return
        diskLock.withLock {
            try {
                val targetFile = File(dir, POINTER_FILE_NAME)
                val tmpFile = File(dir, "$POINTER_FILE_NAME.tmp")
                val obj = JSONObject().apply {
                    put("sessionId", session.id)
                    put("name", session.name)
                    put("ratLockMode", session.ratLockMode.name)
                    put("cadence", session.cadence.name)
                    put("simSlot", session.simSlot)
                    put("simDisplayName", session.simDisplayName ?: JSONObject.NULL)
                    put("isDualSimLogging", session.isDualSimLogging)
                    put("sim2DisplayName", session.sim2DisplayName ?: JSONObject.NULL)
                    put("startTime", session.startTime)
                }
                val bytes = obj.toString().toByteArray(Charsets.UTF_8)
                FileOutputStream(tmpFile, false).use { fos ->
                    fos.write(bytes)
                    fos.flush()
                }
                if (!tmpFile.renameTo(targetFile)) {
                    if (targetFile.exists()) targetFile.delete()
                    if (!tmpFile.renameTo(targetFile)) {
                        targetFile.writeBytes(bytes)
                        tmpFile.delete()
                    }
                }
            } catch (e: Exception) {
                safeLogWarn("Failed writing active session pointer: ${e.message}")
            }
        }
    }

    private fun deleteActiveSessionPointerSync() {
        val dir = storageDir ?: return
        diskLock.withLock {
            try {
                File(dir, POINTER_FILE_NAME).delete()
                File(dir, "$POINTER_FILE_NAME.tmp").delete()
            } catch (e: Exception) {
                safeLogWarn("Failed deleting active session pointer: ${e.message}")
            }
        }
    }

    fun loadSavedSessionsSync() {
        val dir = storageDir ?: return
        awaitPendingWrites()
        val loadedById = LinkedHashMap<Long, DriveTestSession>()
        diskLock.withLock {
            try {
                // 1. Load metadata only for crash-safe format; samples are lazy-loaded on demand
                val metaFiles = dir.listFiles { _, name ->
                    name.startsWith("session_") && name.endsWith(META_SUFFIX)
                } ?: emptyArray()
                val currentActiveId = _activeSession.value?.takeIf { it.isRecording }?.id

                for (metaFile in metaFiles) {
                    try {
                        val metaObj = JSONObject(metaFile.readText(Charsets.UTF_8))
                        val sessionId = metaObj.getLong("id")
                        val metaOnly = jsonToSessionMetadata(metaObj, emptyList())
                        val needsCrashRecovery = (metaOnly.isRecording || metaOnly.endTime == null) &&
                            metaOnly.id != currentActiveId

                        val session = if (needsCrashRecovery) {
                            val samplesFile = File(dir, "session_${sessionId}$SAMPLES_SUFFIX")
                            val samples = readSamplesNdjson(samplesFile, sessionId)
                            reconcileLoadedSession(
                                session = jsonToSessionMetadata(metaObj, samples),
                                sourceLastModified = maxOf(
                                    metaFile.lastModified(),
                                    if (samplesFile.exists()) samplesFile.lastModified() else 0L
                                ),
                                dir = dir,
                                legacyFileToClean = null
                            )
                        } else {
                            metaOnly
                        }
                        loadedById[session.id] = session
                    } catch (e: Exception) {
                        safeLogError("Error loading meta session file ${metaFile.name}", e)
                    }
                }

                // 2. Load legacy monolithic session_<id>.json files not yet migrated
                val legacyFiles = dir.listFiles { _, name ->
                    name.startsWith("session_") &&
                        name.endsWith(LEGACY_SUFFIX) &&
                        !name.endsWith(META_SUFFIX)
                } ?: emptyArray()

                for (legacyFile in legacyFiles) {
                    try {
                        val text = legacyFile.readText(Charsets.UTF_8)
                        val parsed = jsonToLegacySession(JSONObject(text))
                        if (parsed.id !in loadedById) {
                            val reconciled = reconcileLoadedSession(
                                session = parsed,
                                sourceLastModified = legacyFile.lastModified(),
                                dir = dir,
                                legacyFileToClean = if (parsed.isRecording) legacyFile else null
                            )
                            loadedById[reconciled.id] = reconciled
                        }
                    } catch (e: Exception) {
                        safeLogError("Error loading legacy session file ${legacyFile.name}", e)
                    }
                }
            } catch (e: Exception) {
                safeLogError("Error listing session files", e)
            }
        }

        // Merge loaded sessions into StateFlows outside diskLock to avoid lock-order inversion
        synchronized(this) {
            val currentSessions = _sessions.value
            val existingIds = currentSessions.map { it.id }.toSet()
            val merged = currentSessions + loadedById.values.filter { it.id !in existingIds }
            _sessions.value = merged.sortedByDescending { it.startTime }
            val maxId = _sessions.value.maxOfOrNull { it.id } ?: 0L
            if (maxId > lastSessionId) {
                lastSessionId = maxId
            }
            if (_selectedSessionForMap.value == null) {
                _selectedSessionForMap.value = _sessions.value.firstOrNull()
            }
            hasLoadedOnce = true
        }
    }

    /**
     * Reconciles orphaned sessions left with `isRecording = true` or `endTime = null` after process death,
     * recomputing accurate counts/averages from recovered NDJSON samples and freezing `endTime`.
     */
    private fun reconcileLoadedSession(
        session: DriveTestSession,
        sourceLastModified: Long,
        dir: File,
        legacyFileToClean: File?
    ): DriveTestSession {
        val currentActiveId = _activeSession.value?.takeIf { it.isRecording }?.id
        if (session.id == currentActiveId) {
            return session
        }

        val recomputed = recomputeAggregates(session)
        if (!recomputed.isRecording && recomputed.endTime != null) {
            return recomputed
        }

        val recoveredEndTime = recomputed.samples.lastOrNull()?.timestamp
            ?: sourceLastModified.takeIf { it > recomputed.startTime }
            ?: recomputed.startTime
        val exitReason = SessionDiagnostics.lastProcessExitReason
        val note = if (exitReason != null) {
            "Recovered ($exitReason)"
        } else {
            "Recovered after unexpected termination"
        }
        val finalized = recomputed.copy(
            endTime = recoveredEndTime,
            isRecording = false,
            recoveredFromCrash = true,
            recoveryNote = note
        )

        // Persist finalized metadata atomically so future opens never recompute duration against now()
        try {
            writeMetaFileAtomically(dir, finalized)
            if (legacyFileToClean != null && legacyFileToClean.exists() && finalized.samples.isNotEmpty()) {
                val samplesFile = File(dir, "session_${finalized.id}$SAMPLES_SUFFIX")
                if (!samplesFile.exists()) {
                    val sb = StringBuilder(finalized.samples.size * 256)
                    for (s in finalized.samples) {
                        sb.append(sampleToJson(s).toString()).append('\n')
                    }
                    samplesFile.writeText(sb.toString(), Charsets.UTF_8)
                }
            }
            SessionDiagnostics.recordEvent(
                sessionId = finalized.id,
                event = "CRASH_RECOVERED",
                details = mapOf(
                    "recoveredSamples" to finalized.sampleCount,
                    "recoveredEndTime" to recoveredEndTime,
                    "exitReason" to exitReason
                )
            )
        } catch (e: Exception) {
            safeLogWarn("Failed persisting recovered session ${finalized.id}: ${e.message}")
        }
        return finalized
    }

    private fun recomputeAggregates(session: DriveTestSession): DriveTestSession {
        if (session.samples.isEmpty()) {
            return session.copy(sampleCount = 0)
        }
        val acc = ActiveSessionAccumulator(session.id, session.samples)
        return session.copy(
            sampleCount = session.samples.size,
            avgRsrp = acc.avgRsrp(),
            avgRsrq = acc.avgRsrq(),
            avgSinr = acc.avgSinr(),
            avgRsrpSim2 = acc.avgRsrpSim2(),
            avgSinrSim2 = acc.avgSinrSim2()
        )
    }

    private fun readSamplesNdjson(file: File, sessionId: Long): List<DriveTestSample> {
        if (!file.exists()) return emptyList()
        val result = ArrayList<DriveTestSample>()
        var corruptedLines = 0
        BufferedReader(InputStreamReader(FileInputStream(file), Charsets.UTF_8)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val trimmed = line?.trim() ?: continue
                if (trimmed.isEmpty()) continue
                try {
                    result.add(jsonToSample(JSONObject(trimmed), sessionId))
                } catch (e: Exception) {
                    corruptedLines++
                    safeLogWarn("Skipping corrupted NDJSON line in ${file.name}: ${e.message}")
                }
            }
        }
        if (corruptedLines > 0) {
            SessionDiagnostics.recordEvent(
                sessionId = sessionId,
                event = "NDJSON_CORRUPT_LINES_SKIPPED",
                details = mapOf("skippedLines" to corruptedLines, "recoveredSamples" to result.size)
            )
        }
        return result
    }

    private fun loadSavedSessions() {
        writerExecutor.execute {
            loadSavedSessionsSync()
        }
    }

    private fun metricsToJson(m: CellMetrics): JSONObject {
        val mObj = JSONObject()
        mObj.put("ratType", m.ratType.name)
        mObj.put("plmn", m.plmn ?: JSONObject.NULL)
        mObj.put("operatorAlpha", m.operatorAlpha ?: JSONObject.NULL)
        mObj.put("selectedSimSlot", m.selectedSimSlot)
        mObj.put("simDisplayName", m.simDisplayName ?: JSONObject.NULL)

        m.lte?.let { lte ->
            val lObj = JSONObject()
            lObj.put("pci", lte.pci ?: JSONObject.NULL)
            lObj.put("eci", lte.eci ?: JSONObject.NULL)
            lObj.put("tac", lte.tac ?: JSONObject.NULL)
            lObj.put("earfcn", lte.earfcn ?: JSONObject.NULL)
            lObj.put("band", lte.band ?: JSONObject.NULL)
            lObj.put("rsrp", lte.rsrp ?: JSONObject.NULL)
            lObj.put("rsrq", lte.rsrq ?: JSONObject.NULL)
            lObj.put("rssnr", lte.rssnr ?: JSONObject.NULL)
            lObj.put("cqi", lte.cqi ?: JSONObject.NULL)
            mObj.put("lte", lObj)
        }

        m.nr?.let { nr ->
            val nObj = JSONObject()
            nObj.put("pci", nr.pci ?: JSONObject.NULL)
            nObj.put("nci", nr.nci ?: JSONObject.NULL)
            nObj.put("tac", nr.tac ?: JSONObject.NULL)
            nObj.put("nrArfcn", nr.nrArfcn ?: JSONObject.NULL)
            nObj.put("band", nr.band ?: JSONObject.NULL)
            nObj.put("ssRsrp", nr.ssRsrp ?: JSONObject.NULL)
            nObj.put("ssRsrq", nr.ssRsrq ?: JSONObject.NULL)
            nObj.put("ssSinr", nr.ssSinr ?: JSONObject.NULL)
            nObj.put("csiRsrp", nr.csiRsrp ?: JSONObject.NULL)
            nObj.put("csiRsrq", nr.csiRsrq ?: JSONObject.NULL)
            nObj.put("csiSinr", nr.csiSinr ?: JSONObject.NULL)
            nObj.put("isNsa", nr.isNsa)
            mObj.put("nr", nObj)
        }

        val neighArray = JSONArray()
        m.neighbors.forEach { n ->
            val nItem = JSONObject()
            nItem.put("rat", n.rat.name)
            nItem.put("pci", n.pci ?: JSONObject.NULL)
            nItem.put("arfcn", n.arfcn ?: JSONObject.NULL)
            nItem.put("rsrp", n.rsrp ?: JSONObject.NULL)
            nItem.put("rsrq", n.rsrq ?: JSONObject.NULL)
            nItem.put("sinr", n.sinr ?: JSONObject.NULL)
            neighArray.put(nItem)
        }
        mObj.put("neighbors", neighArray)
        return mObj
    }

    private fun jsonToMetrics(mObj: JSONObject?): CellMetrics {
        if (mObj == null) return CellMetrics()

        var lteMetrics: LteMetrics? = null
        val lObj = mObj.optJSONObject("lte")
        if (lObj != null) {
            lteMetrics = LteMetrics(
                pci = if (lObj.isNull("pci")) null else lObj.optInt("pci"),
                eci = if (lObj.isNull("eci")) null else lObj.optLong("eci"),
                tac = if (lObj.isNull("tac")) null else lObj.optInt("tac"),
                earfcn = if (lObj.isNull("earfcn")) null else lObj.optInt("earfcn"),
                band = if (lObj.isNull("band")) null else lObj.optInt("band"),
                rsrp = if (lObj.isNull("rsrp")) null else lObj.optInt("rsrp"),
                rsrq = if (lObj.isNull("rsrq")) null else lObj.optInt("rsrq"),
                rssnr = if (lObj.isNull("rssnr")) null else lObj.optDouble("rssnr"),
                cqi = if (lObj.isNull("cqi")) null else lObj.optInt("cqi")
            )
        }

        var nrMetrics: NrMetrics? = null
        val nObj = mObj.optJSONObject("nr")
        if (nObj != null) {
            nrMetrics = NrMetrics(
                pci = if (nObj.isNull("pci")) null else nObj.optInt("pci"),
                nci = if (nObj.isNull("nci")) null else nObj.optLong("nci"),
                tac = if (nObj.isNull("tac")) null else nObj.optInt("tac"),
                nrArfcn = if (nObj.isNull("nrArfcn")) null else nObj.optInt("nrArfcn"),
                band = if (nObj.isNull("band")) null else nObj.optInt("band"),
                ssRsrp = if (nObj.isNull("ssRsrp")) null else nObj.optInt("ssRsrp"),
                ssRsrq = if (nObj.isNull("ssRsrq")) null else nObj.optInt("ssRsrq"),
                ssSinr = if (nObj.isNull("ssSinr")) null else nObj.optDouble("ssSinr"),
                csiRsrp = if (nObj.isNull("csiRsrp")) null else nObj.optInt("csiRsrp"),
                csiRsrq = if (nObj.isNull("csiRsrq")) null else nObj.optInt("csiRsrq"),
                csiSinr = if (nObj.isNull("csiSinr")) null else nObj.optDouble("csiSinr"),
                isNsa = nObj.optBoolean("isNsa", false)
            )
        }

        val neighbors = mutableListOf<NeighborCell>()
        val neighArray = mObj.optJSONArray("neighbors")
        if (neighArray != null) {
            for (j in 0 until neighArray.length()) {
                val nItem = neighArray.getJSONObject(j)
                val rType = try {
                    RatType.valueOf(nItem.optString("rat", RatType.UNKNOWN.name))
                } catch (_: Exception) {
                    RatType.UNKNOWN
                }
                neighbors.add(
                    NeighborCell(
                        rat = rType,
                        pci = if (nItem.isNull("pci")) null else nItem.optInt("pci"),
                        arfcn = if (nItem.isNull("arfcn")) null else nItem.optInt("arfcn"),
                        rsrp = if (nItem.isNull("rsrp")) null else nItem.optInt("rsrp"),
                        rsrq = if (nItem.isNull("rsrq")) null else nItem.optInt("rsrq"),
                        sinr = if (nItem.isNull("sinr")) null else nItem.optDouble("sinr")
                    )
                )
            }
        }

        return CellMetrics(
            ratType = try {
                RatType.valueOf(mObj.optString("ratType", RatType.UNKNOWN.name))
            } catch (_: Exception) {
                RatType.UNKNOWN
            },
            plmn = if (mObj.isNull("plmn")) null else mObj.optString("plmn"),
            operatorAlpha = if (mObj.isNull("operatorAlpha")) null else mObj.optString("operatorAlpha"),
            lte = lteMetrics,
            nr = nrMetrics,
            neighbors = neighbors,
            selectedSimSlot = mObj.optInt("selectedSimSlot", 0),
            simDisplayName = if (mObj.isNull("simDisplayName")) null else mObj.optString("simDisplayName")
        )
    }

    private fun sampleToJson(s: DriveTestSample): JSONObject {
        val sObj = JSONObject()
        sObj.put("id", s.id)
        sObj.put("sessionId", s.sessionId)
        sObj.put("timestamp", s.timestamp)
        sObj.put("latitude", s.latitude)
        sObj.put("longitude", s.longitude)
        sObj.put("altitude", s.altitude)
        sObj.put("speedKmh", s.speedKmh.toDouble())
        sObj.put("bearing", s.bearing.toDouble())
        sObj.put("accuracyMeters", s.accuracyMeters.toDouble())
        sObj.put("ratLockMode", s.ratLockMode.name)
        sObj.put("cadence", s.cadence.name)
        sObj.put("simSlot", s.simSlot)
        sObj.put("simDisplayName", s.simDisplayName ?: JSONObject.NULL)
        sObj.put("metrics", metricsToJson(s.metrics))
        if (s.metricsSim2 != null) {
            sObj.put("metricsSim2", metricsToJson(s.metricsSim2))
        }
        return sObj
    }

    private fun jsonToSample(sObj: JSONObject, fallbackSessionId: Long): DriveTestSample {
        val primaryMetrics = jsonToMetrics(sObj.optJSONObject("metrics"))
        val secondaryMetrics = if (sObj.has("metricsSim2") && !sObj.isNull("metricsSim2")) {
            jsonToMetrics(sObj.optJSONObject("metricsSim2"))
        } else null

        return DriveTestSample(
            id = sObj.getLong("id"),
            sessionId = sObj.optLong("sessionId", fallbackSessionId),
            timestamp = sObj.getLong("timestamp"),
            latitude = sObj.getDouble("latitude"),
            longitude = sObj.getDouble("longitude"),
            altitude = sObj.getDouble("altitude"),
            speedKmh = sObj.optDouble("speedKmh", 0.0).toFloat(),
            bearing = sObj.optDouble("bearing", 0.0).toFloat(),
            accuracyMeters = sObj.optDouble("accuracyMeters", 0.0).toFloat(),
            metrics = primaryMetrics,
            metricsSim2 = secondaryMetrics,
            ratLockMode = parseRatLockMode(sObj.optString("ratLockMode", RatLockMode.AUTO.name)),
            cadence = parseCadence(sObj.optString("cadence", MeasurementCadence.PRECISE.name)),
            simSlot = sObj.optInt("simSlot", 0),
            simDisplayName = if (sObj.isNull("simDisplayName")) null else sObj.optString("simDisplayName")
        )
    }

    private fun metadataToJson(session: DriveTestSession): JSONObject {
        val obj = JSONObject()
        obj.put("id", session.id)
        obj.put("sessionNumber", session.sessionNumber)
        obj.put("name", session.name)
        obj.put("startTime", session.startTime)
        obj.put("endTime", session.endTime ?: JSONObject.NULL)
        obj.put("ratLockMode", session.ratLockMode.name)
        obj.put("cadence", session.cadence.name)
        obj.put("sampleCount", session.sampleCount)
        obj.put("avgRsrp", session.avgRsrp ?: JSONObject.NULL)
        obj.put("avgRsrq", session.avgRsrq ?: JSONObject.NULL)
        obj.put("avgSinr", session.avgSinr ?: JSONObject.NULL)
        obj.put("isRecording", session.isRecording)
        obj.put("simSlot", session.simSlot)
        obj.put("simDisplayName", session.simDisplayName ?: JSONObject.NULL)
        obj.put("isDualSimLogging", session.isDualSimLogging)
        obj.put("sim2DisplayName", session.sim2DisplayName ?: JSONObject.NULL)
        obj.put("avgRsrpSim2", session.avgRsrpSim2 ?: JSONObject.NULL)
        obj.put("avgSinrSim2", session.avgSinrSim2 ?: JSONObject.NULL)
        obj.put("recoveredFromCrash", session.recoveredFromCrash)
        obj.put("recoveryNote", session.recoveryNote ?: JSONObject.NULL)
        return obj
    }

    private fun jsonToSessionMetadata(obj: JSONObject, samples: List<DriveTestSample>): DriveTestSession {
        val id = obj.getLong("id")
        val sessionNumber = obj.optInt("sessionNumber", 1)
        val name = obj.optString("name", "Session #$sessionNumber")
        val startTime = obj.getLong("startTime")
        val endTime = if (obj.isNull("endTime")) null else obj.optLong("endTime")
        val ratLockMode = parseRatLockMode(obj.optString("ratLockMode", RatLockMode.AUTO.name))
        val cadence = parseCadence(obj.optString("cadence", MeasurementCadence.PRECISE.name))
        val sampleCount = if (samples.isNotEmpty()) samples.size else obj.optInt("sampleCount", 0)
        val avgRsrp = if (obj.isNull("avgRsrp")) null else obj.optInt("avgRsrp")
        val avgRsrq = if (obj.isNull("avgRsrq")) null else obj.optInt("avgRsrq")
        val avgSinr = if (obj.isNull("avgSinr")) null else obj.optDouble("avgSinr")
        val isRecording = obj.optBoolean("isRecording", false)
        val simSlot = obj.optInt("simSlot", 0)
        val simDisplayName = if (obj.isNull("simDisplayName")) null else obj.optString("simDisplayName")
        val isDualSimLogging = obj.optBoolean("isDualSimLogging", false)
        val sim2DisplayName = if (obj.isNull("sim2DisplayName")) null else obj.optString("sim2DisplayName")
        val avgRsrpSim2 = if (obj.isNull("avgRsrpSim2")) null else obj.optInt("avgRsrpSim2")
        val avgSinrSim2 = if (obj.isNull("avgSinrSim2")) null else obj.optDouble("avgSinrSim2")
        val recoveredFromCrash = obj.optBoolean("recoveredFromCrash", false)
        val recoveryNote = if (obj.isNull("recoveryNote")) null else obj.optString("recoveryNote")

        return DriveTestSession(
            id = id,
            sessionNumber = sessionNumber,
            name = name,
            startTime = startTime,
            endTime = endTime,
            ratLockMode = ratLockMode,
            cadence = cadence,
            sampleCount = sampleCount,
            avgRsrp = avgRsrp,
            avgRsrq = avgRsrq,
            avgSinr = avgSinr,
            isRecording = isRecording,
            samples = samples,
            simSlot = simSlot,
            simDisplayName = simDisplayName,
            isDualSimLogging = isDualSimLogging,
            sim2DisplayName = sim2DisplayName,
            avgRsrpSim2 = avgRsrpSim2,
            avgSinrSim2 = avgSinrSim2,
            recoveredFromCrash = recoveredFromCrash,
            recoveryNote = recoveryNote
        )
    }

    private fun jsonToLegacySession(obj: JSONObject): DriveTestSession {
        val id = obj.getLong("id")
        val samplesList = ArrayList<DriveTestSample>()
        val samplesArray = obj.optJSONArray("samples")
        if (samplesArray != null) {
            for (i in 0 until samplesArray.length()) {
                samplesList.add(jsonToSample(samplesArray.getJSONObject(i), id))
            }
        }
        return jsonToSessionMetadata(obj, samplesList)
    }

    private fun parseRatLockMode(raw: String): RatLockMode = try {
        RatLockMode.valueOf(raw)
    } catch (_: Exception) {
        RatLockMode.AUTO
    }

    private fun parseCadence(raw: String): MeasurementCadence = try {
        MeasurementCadence.valueOf(raw)
    } catch (_: Exception) {
        MeasurementCadence.PRECISE
    }

    private fun safeLogWarn(msg: String) {
        try {
            Log.w(TAG, msg)
        } catch (_: Throwable) {
            // Unit tests without Android Log mock
        }
    }

    private fun safeLogError(msg: String, e: Throwable) {
        try {
            Log.e(TAG, msg, e)
        } catch (_: Throwable) {
            // Unit tests without Android Log mock
        }
    }
}
