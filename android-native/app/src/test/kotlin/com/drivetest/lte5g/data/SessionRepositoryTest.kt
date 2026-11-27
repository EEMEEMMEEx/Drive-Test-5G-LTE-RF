package com.drivetest.lte5g.data

import com.drivetest.lte5g.model.CellMetrics
import com.drivetest.lte5g.model.DriveTestSample
import com.drivetest.lte5g.model.LteMetrics
import com.drivetest.lte5g.model.MeasurementCadence
import com.drivetest.lte5g.model.RatLockMode
import com.drivetest.lte5g.model.RatType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SessionRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun setUp() {
        SessionRepository.resetForTesting()
        val testDir = tempFolder.newFolder("sessions_test")
        SessionRepository.initStorage(testDir)
    }

    @Test
    fun testCreateNewSession_setsActiveAndIncrementsNumber() {
        val s1 = SessionRepository.createNewSession(name = "Test Route 1", lockMode = RatLockMode.LTE_ONLY)
        assertEquals("Test Route 1", s1.name)
        assertTrue(s1.isRecording)
        assertEquals(s1.id, SessionRepository.activeSession.value?.id)

        val s2 = SessionRepository.createNewSession(name = "Test Route 2", lockMode = RatLockMode.NR_ONLY)
        assertEquals("Test Route 2", s2.name)
        assertEquals(s2.id, SessionRepository.activeSession.value?.id)
        assertTrue(s2.sessionNumber > s1.sessionNumber)

        // Previous session was finished
        val previous = SessionRepository.sessions.value.find { it.id == s1.id }
        assertNotNull(previous)
        assertFalse(previous!!.isRecording)
        assertNotNull(previous.endTime)
    }

    @Test
    fun testRenameSession_updatesActiveAndList() {
        val s1 = SessionRepository.createNewSession(name = "Original Name")
        SessionRepository.renameSession(s1.id, "Updated Name")

        assertEquals("Updated Name", SessionRepository.activeSession.value?.name)
        assertEquals("Updated Name", SessionRepository.sessions.value.find { it.id == s1.id }?.name)
    }

    @Test
    fun testAddSample_computesSampleCountAndAverages() {
        val session = SessionRepository.createNewSession(name = "Averages Test")

        val sample1 = DriveTestSample(
            id = 1L,
            sessionId = session.id,
            timestamp = System.currentTimeMillis(),
            latitude = 13.75,
            longitude = 100.50,
            altitude = 10.0,
            speedKmh = 30f,
            bearing = 90f,
            accuracyMeters = 3f,
            metrics = CellMetrics(
                ratType = RatType.LTE,
                lte = LteMetrics(
                    pci = 101, eci = 12345L, tac = 200, earfcn = 1850, band = 3,
                    rsrp = -80, rsrq = -10, rssnr = 15.0, cqi = 10
                )
            ),
            ratLockMode = RatLockMode.LTE_ONLY,
            cadence = MeasurementCadence.PRECISE
        )

        val sample2 = DriveTestSample(
            id = 2L,
            sessionId = session.id,
            timestamp = System.currentTimeMillis() + 1000,
            latitude = 13.751,
            longitude = 100.501,
            altitude = 10.0,
            speedKmh = 35f,
            bearing = 90f,
            accuracyMeters = 3f,
            metrics = CellMetrics(
                ratType = RatType.LTE,
                lte = LteMetrics(
                    pci = 101, eci = 12345L, tac = 200, earfcn = 1850, band = 3,
                    rsrp = -90, rsrq = -12, rssnr = 11.0, cqi = 8
                )
            ),
            ratLockMode = RatLockMode.LTE_ONLY,
            cadence = MeasurementCadence.PRECISE
        )

        SessionRepository.addSample(sample1)
        SessionRepository.addSample(sample2)

        val current = SessionRepository.activeSession.value
        assertNotNull(current)
        assertEquals(2, current!!.sampleCount)
        assertEquals(2, current.samples.size)
        // Average of -80 and -90 = -85
        assertEquals(-85, current.avgRsrp)
        // Average of -10 and -12 = -11
        assertEquals(-11, current.avgRsrq)
        // Average of 15.0 and 11.0 = 13.0
        assertEquals(13.0, current.avgSinr ?: 0.0, 0.1)
    }

    @Test
    fun testFinishSession_marksNotRecording() {
        val session = SessionRepository.createNewSession(name = "Finish Test")
        assertTrue(SessionRepository.activeSession.value!!.isRecording)

        SessionRepository.finishCurrentSession()
        val finished = SessionRepository.activeSession.value
        assertNotNull(finished)
        assertFalse(finished!!.isRecording)
        assertNotNull(finished.endTime)
    }

    @Test
    fun testDeleteSession_removesFromList() {
        val s1 = SessionRepository.createNewSession(name = "To Delete")
        assertEquals(s1.id, SessionRepository.activeSession.value?.id)

        SessionRepository.deleteSession(s1.id)
        assertNull(SessionRepository.activeSession.value)
        assertNull(SessionRepository.sessions.value.find { it.id == s1.id })
    }

    @Test
    fun testCreateNewSession_withSimSlot_preservesSimInfo() {
        val session = SessionRepository.createNewSession(
            name = "Dual SIM Test",
            lockMode = RatLockMode.NR_ONLY,
            cadence = MeasurementCadence.PRECISE,
            simSlot = 1,
            simDisplayName = "SIM 2: TRUE 5G"
        )

        assertEquals(1, session.simSlot)
        assertEquals("SIM 2: TRUE 5G", session.simDisplayName)
        assertEquals(session.id, SessionRepository.activeSession.value?.id)
        assertEquals(1, SessionRepository.activeSession.value?.simSlot)
        assertEquals("SIM 2: TRUE 5G", SessionRepository.activeSession.value?.simDisplayName)
    }

    @Test
    fun testPersistence_savesAndLoadsSimSlotInfo() {
        val s = SessionRepository.createNewSession(
            name = "Persist SIM Test",
            simSlot = 0,
            simDisplayName = "SIM 1: AIS"
        )
        val sample = DriveTestSample(
            id = 10L,
            sessionId = s.id,
            timestamp = System.currentTimeMillis(),
            latitude = 13.75,
            longitude = 100.50,
            altitude = 12.0,
            speedKmh = 25f,
            bearing = 0f,
            accuracyMeters = 2f,
            metrics = CellMetrics(
                ratType = RatType.NR_NSA,
                plmn = "52003",
                selectedSimSlot = 0,
                simDisplayName = "SIM 1: AIS"
            ),
            simSlot = 0,
            simDisplayName = "SIM 1: AIS"
        )
        SessionRepository.addSample(sample)
        SessionRepository.finishCurrentSession()

        // True cold reload from disk (clears in-memory state first)
        val testDir = tempFolder.root.listFiles()?.firstOrNull() ?: tempFolder.root
        SessionRepository.resetForTesting()
        SessionRepository.initStorage(testDir)

        val loadedMeta = SessionRepository.sessions.value.find { it.id == s.id }
        assertNotNull(loadedMeta)
        assertEquals(0, loadedMeta!!.simSlot)
        assertEquals("SIM 1: AIS", loadedMeta.simDisplayName)
        assertTrue("Expected lazy-load to skip reading samples on startup", loadedMeta.samples.isEmpty())

        val loaded = SessionRepository.ensureSessionSamplesLoaded(s.id)!!
        assertEquals(1, loaded.samples.size)
        assertEquals(0, loaded.samples[0].simSlot)
        assertEquals("SIM 1: AIS", loaded.samples[0].simDisplayName)
        assertEquals(0, loaded.samples[0].metrics.selectedSimSlot)
        assertEquals("SIM 1: AIS", loaded.samples[0].metrics.simDisplayName)
    }

    @Test
    fun testDualSimLogging_savesAndLoadsMetricsSim2AndAverages() {
        val s = SessionRepository.createNewSession(
            name = "Dual SIM Benchmark Test",
            simSlot = 0,
            simDisplayName = "SIM 1: AIS 5G",
            isDualSimLogging = true,
            sim2DisplayName = "SIM 2: TRUE 5G"
        )
        assertTrue(s.isDualSimLogging)
        assertEquals("SIM 2: TRUE 5G", s.sim2DisplayName)

        val sample = DriveTestSample(
            id = 100L,
            sessionId = s.id,
            timestamp = System.currentTimeMillis(),
            latitude = 13.75,
            longitude = 100.50,
            altitude = 12.0,
            speedKmh = 30f,
            bearing = 0f,
            accuracyMeters = 2f,
            metrics = CellMetrics(
                ratType = RatType.NR_NSA,
                plmn = "52001",
                operatorAlpha = "AIS",
                lte = LteMetrics(pci = 100, eci = 1234L, tac = 20, earfcn = 1800, band = 3, rsrp = -80, rsrq = -10, rssnr = 15.0, cqi = 10)
            ),
            metricsSim2 = CellMetrics(
                ratType = RatType.LTE,
                plmn = "52004",
                operatorAlpha = "TRUE",
                lte = LteMetrics(pci = 200, eci = 5678L, tac = 30, earfcn = 2100, band = 1, rsrp = -92, rsrq = -12, rssnr = 10.0, cqi = 8)
            ),
            simSlot = 0,
            simDisplayName = "SIM 1: AIS 5G"
        )
        SessionRepository.addSample(sample)

        val active = SessionRepository.activeSession.value
        assertNotNull(active)
        assertEquals(-80, active?.avgRsrp)
        assertEquals(-92, active?.avgRsrpSim2)
        assertEquals(10.0, active?.avgSinrSim2)

        SessionRepository.finishCurrentSession()

        // True cold reload from disk
        val testDir = tempFolder.root.listFiles()?.firstOrNull() ?: tempFolder.root
        SessionRepository.resetForTesting()
        SessionRepository.initStorage(testDir)

        val loadedMeta = SessionRepository.sessions.value.find { it.id == s.id }
        assertNotNull(loadedMeta)
        assertTrue(loadedMeta!!.isDualSimLogging)
        assertEquals("SIM 2: TRUE 5G", loadedMeta.sim2DisplayName)
        assertEquals(-80, loadedMeta.avgRsrp)
        assertEquals(-92, loadedMeta.avgRsrpSim2)
        assertTrue(loadedMeta.samples.isEmpty())

        val loaded = SessionRepository.ensureSessionSamplesLoaded(s.id)!!
        assertEquals(1, loaded.samples.size)
        assertNotNull(loaded.samples[0].metricsSim2)
        assertEquals("52004", loaded.samples[0].metricsSim2?.plmn)
        assertEquals(-92, loaded.samples[0].metricsSim2?.lte?.rsrp)
    }

    private fun makeLteSample(sessionId: Long, id: Long, timestamp: Long, rsrp: Int = -88): DriveTestSample {
        return DriveTestSample(
            id = id,
            sessionId = sessionId,
            timestamp = timestamp,
            latitude = 13.75 + id * 0.00001,
            longitude = 100.50 + id * 0.00001,
            altitude = 10.0,
            speedKmh = 40f,
            bearing = 90f,
            accuracyMeters = 4f,
            metrics = CellMetrics(
                ratType = RatType.LTE,
                plmn = "520-03",
                operatorAlpha = "AIS",
                lte = LteMetrics(
                    pci = 101,
                    eci = 123456L,
                    tac = 200,
                    earfcn = 1850,
                    band = 3,
                    rsrp = rsrp,
                    rsrq = -10,
                    rssnr = 12.0,
                    cqi = 11
                )
            ),
            ratLockMode = RatLockMode.AUTO,
            cadence = MeasurementCadence.PRECISE
        )
    }

    @Test
    fun testCrashRecovery_preservesIncrementalSamplesAndFreezesEndTime() {
        val testDir = tempFolder.root.listFiles()?.firstOrNull() ?: tempFolder.root
        val session = SessionRepository.createNewSession(name = "Crash Kill Test", cadence = MeasurementCadence.PRECISE)
        val baseTs = session.startTime

        for (i in 1L..25L) {
            SessionRepository.addSample(makeLteSample(session.id, i, baseTs + i * 1000L, rsrp = -80 - (i % 10).toInt()))
        }
        SessionRepository.flushActiveSessionSync()

        // Simulate abrupt OS/OEM SIGKILL: skip finishCurrentSession() and wipe RAM
        SessionRepository.resetForTesting()
        SessionRepository.initStorage(testDir)

        val recovered = SessionRepository.sessions.value.find { it.id == session.id }
        assertNotNull(recovered)
        assertEquals(25, recovered!!.sampleCount)
        assertEquals(25, recovered.samples.size)
        assertFalse(recovered.isRecording)
        assertTrue(recovered.recoveredFromCrash)
        assertEquals(baseTs + 25_000L, recovered.endTime)
        assertNotNull(recovered.avgRsrp)

        val diagText = SessionDiagnostics.readDiagnosticsText(session.id)
        assertNotNull(diagText)
        assertTrue(diagText!!.contains("CRASH_RECOVERED"))
    }

    @Test
    fun testTruncatedNdjsonTail_preservesAllPriorSamples() {
        val testDir = tempFolder.root.listFiles()?.firstOrNull() ?: tempFolder.root
        val session = SessionRepository.createNewSession(name = "Corrupt Tail Test")
        val baseTs = session.startTime
        for (i in 1L..5L) {
            SessionRepository.addSample(makeLteSample(session.id, i, baseTs + i * 1000L, rsrp = -90))
        }
        SessionRepository.flushActiveSessionSync()

        // Append a half-written JSON line simulating mid-write crash
        val ndjsonFile = File(testDir, "session_${session.id}.samples.ndjson")
        assertTrue(ndjsonFile.exists())
        ndjsonFile.appendText("{\"id\":6,\"sessionId\":${session.id},\"latitude\":13.7")

        SessionRepository.resetForTesting()
        SessionRepository.initStorage(testDir)

        val recovered = SessionRepository.sessions.value.find { it.id == session.id }
        assertNotNull(recovered)
        assertEquals(5, recovered!!.sampleCount)
        assertEquals(5, recovered.samples.size)
        assertEquals(-90, recovered.avgRsrp)
    }

    @Test
    fun testStartStickyResume_reopensInterruptedSessionWithoutDataLoss() {
        val testDir = tempFolder.root.listFiles()?.firstOrNull() ?: tempFolder.root
        val session = SessionRepository.createNewSession(name = "Sticky Resume Test", cadence = MeasurementCadence.ECO)
        val baseTs = session.startTime
        for (i in 1L..15L) {
            SessionRepository.addSample(makeLteSample(session.id, i, baseTs + i * 5000L, rsrp = -85))
        }
        SessionRepository.flushActiveSessionSync()

        // Simulate OS kill + START_STICKY restart
        SessionRepository.resetForTesting()
        SessionRepository.initStorage(testDir)

        val pointer = SessionRepository.readActiveSessionPointer()
        assertNotNull(pointer)
        assertEquals(session.id, pointer!!.sessionId)
        assertEquals(MeasurementCadence.ECO, pointer.cadence)

        val resumed = SessionRepository.resumeSessionForRecovery(pointer.sessionId)
        assertNotNull(resumed)
        assertTrue(resumed!!.isRecording)
        assertEquals(15, resumed.samples.size)

        for (i in 16L..20L) {
            SessionRepository.addSample(makeLteSample(session.id, i, baseTs + i * 5000L, rsrp = -85))
        }
        SessionRepository.finishCurrentSession()

        // Cold reload confirms all 20 samples persisted and pointer cleaned up
        SessionRepository.resetForTesting()
        SessionRepository.initStorage(testDir)
        assertNull(SessionRepository.readActiveSessionPointer())
        val finalSession = SessionRepository.sessions.value.find { it.id == session.id }
        assertNotNull(finalSession)
        assertFalse(finalSession!!.isRecording)
        assertNotNull(finalSession.endTime)
        assertEquals(20, finalSession.sampleCount)
        assertTrue(finalSession.samples.isEmpty())
        val hydratedFinal = SessionRepository.ensureSessionSamplesLoaded(session.id)!!
        assertEquals(20, hydratedFinal.samples.size)
    }

    @Test
    fun testLegacyMonolithicJson_reconcilesOrphanedZeroSampleSession() {
        val testDir = tempFolder.root.listFiles()?.firstOrNull() ?: tempFolder.root
        val startTs = 1795655977938L
        val legacyFile = File(testDir, "session_$startTs.json")
        legacyFile.writeText(
            """
            {
              "id": $startTs,
              "sessionNumber": 1,
              "name": "26/11/69",
              "startTime": $startTs,
              "endTime": null,
              "ratLockMode": "AUTO",
              "cadence": "PRECISE",
              "sampleCount": 0,
              "isRecording": true,
              "samples": []
            }
            """.trimIndent()
        )

        SessionRepository.resetForTesting()
        SessionRepository.initStorage(testDir)

        val recovered = SessionRepository.sessions.value.find { it.id == startTs }
        assertNotNull(recovered)
        assertFalse(recovered!!.isRecording)
        assertNotNull(recovered.endTime)
        assertTrue(recovered.recoveredFromCrash)
    }

    @Test
    fun testHighVolumeScale_10000SamplesO1IngestionAndFlush() {
        val testDir = tempFolder.root.listFiles()?.firstOrNull() ?: tempFolder.root
        SessionRepository.flushBatchSize = 250
        val session = SessionRepository.createNewSession(name = "10k Scale Test")
        val baseTs = session.startTime

        val startNanos = System.nanoTime()
        for (i in 1L..10_000L) {
            SessionRepository.addSample(makeLteSample(session.id, i, baseTs + i * 1000L, rsrp = -86))
        }
        SessionRepository.finishCurrentSession()
        val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L

        assertTrue("Expected fast linear ingestion (<15000ms), took ${elapsedMs}ms", elapsedMs < 15_000L)

        SessionRepository.resetForTesting()
        SessionRepository.initStorage(testDir)
        val reloadedMeta = SessionRepository.sessions.value.find { it.id == session.id }
        assertNotNull(reloadedMeta)
        assertEquals(10_000, reloadedMeta!!.sampleCount)
        assertTrue(reloadedMeta.samples.isEmpty())
        assertEquals(-86, reloadedMeta.avgRsrp)

        val reloaded = SessionRepository.ensureSessionSamplesLoaded(session.id)!!
        assertEquals(10_000, reloaded.samples.size)
        assertEquals(-86, reloaded.avgRsrp)
    }

    @Test
    fun testLoadSavedSessions_lazyLoadsOnlyRequestedSession() {
        val testDir = tempFolder.root.listFiles()?.firstOrNull() ?: tempFolder.root
        val s1 = SessionRepository.createNewSession(name = "Session One")
        SessionRepository.addSample(makeLteSample(s1.id, 1L, s1.startTime + 1000L, rsrp = -82))
        SessionRepository.finishCurrentSession()

        val s2 = SessionRepository.createNewSession(name = "Session Two")
        SessionRepository.addSample(makeLteSample(s2.id, 1L, s2.startTime + 1000L, rsrp = -94))
        SessionRepository.finishCurrentSession()

        SessionRepository.resetForTesting()
        SessionRepository.initStorage(testDir)

        val meta1 = SessionRepository.sessions.value.find { it.id == s1.id }!!
        val meta2 = SessionRepository.sessions.value.find { it.id == s2.id }!!
        assertEquals(1, meta1.sampleCount)
        assertEquals(1, meta2.sampleCount)
        assertTrue(meta1.samples.isEmpty())
        assertTrue(meta2.samples.isEmpty())

        val hydrated1 = SessionRepository.ensureSessionSamplesLoaded(s1.id)!!
        assertEquals(1, hydrated1.samples.size)
        val stillUnloaded2 = SessionRepository.sessions.value.find { it.id == s2.id }!!
        assertTrue(stillUnloaded2.samples.isEmpty())
    }

    @Test
    fun testFinishCurrentSession_usesLastSampleTimestampInsteadOfWallClock() {
        val session = SessionRepository.createNewSession(name = "Duration From Last Sample")
        val lastTs = session.startTime + 4_465_000L // ~74m 25s
        SessionRepository.addSample(makeLteSample(session.id, 1L, session.startTime + 1000L, rsrp = -90))
        SessionRepository.addSample(makeLteSample(session.id, 2L, lastTs, rsrp = -94))
        SessionRepository.finishCurrentSession()

        val finished = SessionRepository.sessions.value.find { it.id == session.id }!!
        assertFalse(finished.isRecording)
        assertEquals(lastTs, finished.endTime)
        assertEquals(lastTs, finished.lastSampleTime)
    }

    @Test
    fun testCrashRecovery_recoversSamplesFromOfflineQueueWhenLocalNdjsonEmpty() {
        val testDir = tempFolder.root.listFiles()?.firstOrNull() ?: tempFolder.root
        val queueDir = tempFolder.newFolder("cloud_queue_recovery")
        com.drivetest.lte5g.cloud.CloudTelemetrySyncManager.resetForTesting()
        com.drivetest.lte5g.cloud.CloudTelemetrySyncManager.setStorageDirForTesting(queueDir)

        val sid = 1795574251243L
        val metaFile = File(testDir, "session_$sid.meta.json")
        metaFile.writeText(
            """
            {
              "id": $sid,
              "sessionNumber": 1,
              "name": "Session #1 (2026-11-25 09:37)",
              "startTime": $sid,
              "endTime": null,
              "ratLockMode": "AUTO",
              "cadence": "PRECISE",
              "sampleCount": 0,
              "isRecording": true
            }
            """.trimIndent()
        )

        val t1 = sid + 1000L
        val t2 = sid + 4_465_000L
        com.drivetest.lte5g.cloud.CloudTelemetrySyncManager.saveToOfflineQueue(
            heartbeatJson = "{}",
            sessionId = sid.toString(),
            trailPointJson = """{"ts":$t1,"lat":17.95,"lon":103.11,"rsrp":-92,"sinr":4.0,"pci":86,"rat":"LTE","speed":30.0}"""
        )
        com.drivetest.lte5g.cloud.CloudTelemetrySyncManager.saveToOfflineQueue(
            heartbeatJson = "{}",
            sessionId = sid.toString(),
            trailPointJson = """{"ts":$t2,"lat":17.96,"lon":103.12,"rsrp":-96,"sinr":2.0,"pci":86,"rat":"LTE","speed":35.0}"""
        )

        SessionRepository.resetForTesting()
        SessionRepository.initStorage(testDir)

        val recovered = SessionRepository.sessions.value.find { it.id == sid }!!
        assertFalse(recovered.isRecording)
        assertTrue(recovered.recoveredFromCrash)
        assertEquals(2, recovered.sampleCount)
        assertEquals(t2, recovered.endTime)
        assertEquals(t2, recovered.lastSampleTime)
        assertEquals(-94, recovered.avgRsrp)
    }

    @Test
    fun testReconcileSessionFromSamples_restoresZeroSampleRecoveredSessionFromCloudTrail() {
        val testDir = tempFolder.root.listFiles()?.firstOrNull() ?: tempFolder.root
        val sid = 1795574251243L
        val metaFile = File(testDir, "session_$sid.meta.json")
        metaFile.writeText(
            """
            {
              "id": $sid,
              "sessionNumber": 1,
              "name": "Session #1 (2026-11-25 09:37)",
              "startTime": $sid,
              "endTime": $sid,
              "ratLockMode": "AUTO",
              "cadence": "PRECISE",
              "sampleCount": 0,
              "isRecording": false,
              "recoveredFromCrash": true,
              "recoveryNote": "Recovered (USER_REQUESTED (stop com.drivetest.lte5g due to installPackageLI, pss=93520KB))"
            }
            """.trimIndent()
        )

        SessionRepository.resetForTesting()
        SessionRepository.initStorage(testDir)

        val trailJson = """
            {
              "-Oabc1": {"ts": ${sid + 1000L}, "lat": 17.95, "lon": 103.11, "rsrp": -92, "sinr": 3.5, "pci": 86, "rat": "LTE", "speed": 25.0},
              "-Oabc2": {"ts": ${sid + 4465000L}, "lat": 17.98, "lon": 103.15, "rsrp": -96, "sinr": 1.5, "pci": 86, "rat": "LTE", "speed": 40.0}
            }
        """.trimIndent()
        val parsed = com.drivetest.lte5g.cloud.CloudTelemetrySyncManager.parseTrailJsonMapToSamples(sid, trailJson)
        val reconciled = SessionRepository.reconcileSessionFromSamples(sid, parsed, "Firebase trail")

        assertNotNull(reconciled)
        assertEquals(2, reconciled!!.sampleCount)
        assertEquals(sid + 4465000L, reconciled.endTime)
        assertEquals(-94, reconciled.avgRsrp)

        // Reload from disk to confirm NDJSON & meta persisted
        SessionRepository.resetForTesting()
        SessionRepository.initStorage(testDir)
        val reloaded = SessionRepository.sessions.value.find { it.id == sid }!!
        assertEquals(2, reloaded.sampleCount)
        assertEquals(sid + 4465000L, reloaded.endTime)
    }
}
