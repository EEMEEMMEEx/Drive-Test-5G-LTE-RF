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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SessionRepository {
    private const val TAG = "SessionRepository"
    private val scope = CoroutineScope(Dispatchers.IO)

    private val _sessions = MutableStateFlow<List<DriveTestSession>>(emptyList())
    val sessions: StateFlow<List<DriveTestSession>> = _sessions.asStateFlow()

    private val _activeSession = MutableStateFlow<DriveTestSession?>(null)
    val activeSession: StateFlow<DriveTestSession?> = _activeSession.asStateFlow()

    private val _selectedSessionForMap = MutableStateFlow<DriveTestSession?>(null)
    val selectedSessionForMap: StateFlow<DriveTestSession?> = _selectedSessionForMap.asStateFlow()

    private var storageDir: File? = null
    private var lastSessionId: Long = 0L

    @Synchronized
    private fun generateUniqueSessionId(): Long {
        var now = System.currentTimeMillis()
        if (now <= lastSessionId) {
            now = lastSessionId + 1
        }
        lastSessionId = now
        return now
    }

    fun resetForTesting() {
        _sessions.value = emptyList()
        _activeSession.value = null
        _selectedSessionForMap.value = null
        lastSessionId = 0L
    }

    fun initStorage(context: Context) {
        val dir = File(context.filesDir, "sessions")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        storageDir = dir
        loadSavedSessions()
    }

    fun initStorage(customDir: File) {
        if (!customDir.exists()) {
            customDir.mkdirs()
        }
        storageDir = customDir
        loadSavedSessionsSync()
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
        simDisplayName: String? = null
    ): DriveTestSession {
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
            simDisplayName = simDisplayName
        )

        _activeSession.value = newSession
        _selectedSessionForMap.value = newSession
        _sessions.value = listOf(newSession) + _sessions.value.filter { it.id != newSession.id }

        persistSessionAsync(newSession)
        return newSession
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
            }
            if (_selectedSessionForMap.value?.id == sessionId) {
                _selectedSessionForMap.value = updated
            }
            persistSessionAsync(updated)
        }
    }

    @Synchronized
    fun addSample(sample: DriveTestSample) {
        val current = _activeSession.value ?: return
        val updatedSamples = current.samples + sample

        // Calculate averages
        val validRsrp = updatedSamples.mapNotNull { it.metrics.nr?.ssRsrp ?: it.metrics.lte?.rsrp }
        val validRsrq = updatedSamples.mapNotNull { it.metrics.nr?.ssRsrq ?: it.metrics.lte?.rsrq }
        val validSinr = updatedSamples.mapNotNull { it.metrics.nr?.ssSinr ?: it.metrics.lte?.rssnr }

        val updatedSession = current.copy(
            sampleCount = updatedSamples.size,
            avgRsrp = if (validRsrp.isNotEmpty()) validRsrp.average().toInt() else null,
            avgRsrq = if (validRsrq.isNotEmpty()) validRsrq.average().toInt() else null,
            avgSinr = if (validSinr.isNotEmpty()) Math.round(validSinr.average() * 10.0) / 10.0 else null,
            samples = updatedSamples
        )

        _activeSession.value = updatedSession
        if (_selectedSessionForMap.value?.id == current.id) {
            _selectedSessionForMap.value = updatedSession
        }

        val list = _sessions.value.toMutableList()
        val index = list.indexOfFirst { it.id == current.id }
        if (index != -1) {
            list[index] = updatedSession
            _sessions.value = list
        }
    }

    @Synchronized
    fun finishCurrentSession() {
        val current = _activeSession.value ?: return
        if (!current.isRecording) return

        val finished = current.copy(
            endTime = System.currentTimeMillis(),
            isRecording = false
        )

        _activeSession.value = finished
        val list = _sessions.value.toMutableList()
        val index = list.indexOfFirst { it.id == current.id }
        if (index != -1) {
            list[index] = finished
            _sessions.value = list
        }

        if (_selectedSessionForMap.value?.id == current.id) {
            _selectedSessionForMap.value = finished
        }

        persistSessionAsync(finished)
    }

    @Synchronized
    fun deleteSession(sessionId: Long) {
        _sessions.value = _sessions.value.filter { it.id != sessionId }
        if (_activeSession.value?.id == sessionId) {
            _activeSession.value = null
        }
        if (_selectedSessionForMap.value?.id == sessionId) {
            _selectedSessionForMap.value = _sessions.value.firstOrNull()
        }

        scope.launch {
            try {
                val file = File(storageDir ?: return@launch, "session_$sessionId.json")
                if (file.exists()) {
                    file.delete()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete session file $sessionId", e)
            }
        }
    }

    fun selectSessionForMap(session: DriveTestSession?) {
        _selectedSessionForMap.value = session
    }

    private fun persistSessionAsync(session: DriveTestSession) {
        scope.launch {
            val dir = storageDir ?: return@launch
            try {
                val file = File(dir, "session_${session.id}.json")
                val json = sessionToJson(session)
                file.writeText(json.toString(2))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist session ${session.id}", e)
            }
        }
    }

    fun loadSavedSessionsSync() {
        val dir = storageDir ?: return
        try {
            val files = dir.listFiles { _, name -> name.startsWith("session_") && name.endsWith(".json") }
                ?: return

            val loaded = files.mapNotNull { file ->
                try {
                    val text = file.readText()
                    jsonToSession(JSONObject(text))
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading session file ${file.name}", e)
                    null
                }
            }.sortedByDescending { it.startTime }

            val currentSessions = _sessions.value
            val existingIds = currentSessions.map { it.id }.toSet()
            val merged = currentSessions + loaded.filter { it.id !in existingIds }
            _sessions.value = merged.sortedByDescending { it.startTime }
            if (_selectedSessionForMap.value == null) {
                _selectedSessionForMap.value = _sessions.value.firstOrNull()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error listing session files", e)
        }
    }

    private fun loadSavedSessions() {
        scope.launch {
            loadSavedSessionsSync()
        }
    }

    private fun sessionToJson(session: DriveTestSession): JSONObject {
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

        val samplesArray = JSONArray()
        session.samples.forEach { s ->
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

            // Metrics
            val m = s.metrics
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
            sObj.put("metrics", mObj)

            samplesArray.put(sObj)
        }
        obj.put("samples", samplesArray)
        return obj
    }

    private fun jsonToSession(obj: JSONObject): DriveTestSession {
        val id = obj.getLong("id")
        val sessionNumber = obj.optInt("sessionNumber", 1)
        val name = obj.optString("name", "Session #$sessionNumber")
        val startTime = obj.getLong("startTime")
        val endTime = if (obj.isNull("endTime")) null else obj.optLong("endTime")
        val ratLockMode = try {
            RatLockMode.valueOf(obj.optString("ratLockMode", RatLockMode.AUTO.name))
        } catch (_: Exception) {
            RatLockMode.AUTO
        }
        val cadence = try {
            MeasurementCadence.valueOf(obj.optString("cadence", MeasurementCadence.PRECISE.name))
        } catch (_: Exception) {
            MeasurementCadence.PRECISE
        }
        val sampleCount = obj.optInt("sampleCount", 0)
        val avgRsrp = if (obj.isNull("avgRsrp")) null else obj.optInt("avgRsrp")
        val avgRsrq = if (obj.isNull("avgRsrq")) null else obj.optInt("avgRsrq")
        val avgSinr = if (obj.isNull("avgSinr")) null else obj.optDouble("avgSinr")
        val isRecording = obj.optBoolean("isRecording", false)

        val samplesList = mutableListOf<DriveTestSample>()
        val samplesArray = obj.optJSONArray("samples")
        if (samplesArray != null) {
            for (i in 0 until samplesArray.length()) {
                val sObj = samplesArray.getJSONObject(i)
                val mObj = sObj.optJSONObject("metrics")

                var lteMetrics: LteMetrics? = null
                val lObj = mObj?.optJSONObject("lte")
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
                val nObj = mObj?.optJSONObject("nr")
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
                val neighArray = mObj?.optJSONArray("neighbors")
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

                val cellMetrics = CellMetrics(
                    ratType = try {
                        RatType.valueOf(mObj?.optString("ratType", RatType.UNKNOWN.name) ?: RatType.UNKNOWN.name)
                    } catch (_: Exception) {
                        RatType.UNKNOWN
                    },
                    plmn = if (mObj?.isNull("plmn") != false) null else mObj.optString("plmn"),
                    operatorAlpha = if (mObj?.isNull("operatorAlpha") != false) null else mObj.optString("operatorAlpha"),
                    lte = lteMetrics,
                    nr = nrMetrics,
                    neighbors = neighbors,
                    selectedSimSlot = mObj?.optInt("selectedSimSlot", 0) ?: 0,
                    simDisplayName = if (mObj?.isNull("simDisplayName") != false) null else mObj.optString("simDisplayName")
                )

                samplesList.add(
                    DriveTestSample(
                        id = sObj.getLong("id"),
                        sessionId = sObj.optLong("sessionId", id),
                        timestamp = sObj.getLong("timestamp"),
                        latitude = sObj.getDouble("latitude"),
                        longitude = sObj.getDouble("longitude"),
                        altitude = sObj.getDouble("altitude"),
                        speedKmh = sObj.optDouble("speedKmh", 0.0).toFloat(),
                        bearing = sObj.optDouble("bearing", 0.0).toFloat(),
                        accuracyMeters = sObj.optDouble("accuracyMeters", 0.0).toFloat(),
                        metrics = cellMetrics,
                        ratLockMode = try {
                            RatLockMode.valueOf(sObj.optString("ratLockMode", RatLockMode.AUTO.name))
                        } catch (_: Exception) {
                            RatLockMode.AUTO
                        },
                        cadence = try {
                            MeasurementCadence.valueOf(sObj.optString("cadence", MeasurementCadence.PRECISE.name))
                        } catch (_: Exception) {
                            MeasurementCadence.PRECISE
                        },
                        simSlot = sObj.optInt("simSlot", 0),
                        simDisplayName = if (sObj.isNull("simDisplayName")) null else sObj.optString("simDisplayName")
                    )
                )
            }
        }

        val simSlot = obj.optInt("simSlot", 0)
        val simDisplayName = if (obj.isNull("simDisplayName")) null else obj.optString("simDisplayName")

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
            samples = samplesList,
            simSlot = simSlot,
            simDisplayName = simDisplayName
        )
    }
}
