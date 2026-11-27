package com.drivetest.lte5g.service

import com.drivetest.lte5g.cloud.CloudTelemetrySyncManager
import com.drivetest.lte5g.data.SessionDiagnostics
import com.drivetest.lte5g.model.CellMetrics
import com.drivetest.lte5g.model.DriveTestSample
import com.drivetest.lte5g.model.DriveTestSession
import com.drivetest.lte5g.model.LteMetrics
import com.drivetest.lte5g.model.RatType
import com.drivetest.lte5g.telephony.CellCollector
import com.drivetest.lte5g.updater.PlayUpdateManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LoggingResilienceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun cellCollectorSanitization_rejectsIntMaxValueEciAndNegativeIds() {
        assertNull(CellCollector.sanitizeCiToLong(Int.MAX_VALUE))
        assertNull(CellCollector.sanitizeCiToLong(-1))
        assertEquals(111108197L, CellCollector.sanitizeCiToLong(111108197))

        assertNull(CellCollector.sanitizeNonNegativeInt(Int.MAX_VALUE))
        assertNull(CellCollector.sanitizeNonNegativeInt(-1))
        assertEquals(156, CellCollector.sanitizeNonNegativeInt(156))

        assertNull(CellCollector.sanitizeIntValue(Int.MAX_VALUE))
        assertNull(CellCollector.sanitizeIntValue(-200))
        assertEquals(-89, CellCollector.sanitizeIntValue(-89))
    }

    @Test
    fun playUpdateManager_defersRestartWhileRecording() {
        assertTrue(PlayUpdateManager.shouldDeferUpdateRestart(isRecording = true))
        assertFalse(PlayUpdateManager.shouldDeferUpdateRestart(isRecording = false))
    }

    @Test
    fun sessionDiagnostics_writesEventsAndHeartbeatJsonl() {
        val dir = tempFolder.newFolder("diag_test")
        SessionDiagnostics.resetForTesting()
        SessionDiagnostics.init(dir)

        val sid = 1795655977938L
        SessionDiagnostics.incTicks()
        SessionDiagnostics.incTicks()
        SessionDiagnostics.incDroppedNoLocation()
        SessionDiagnostics.incSamplesSaved()
        SessionDiagnostics.addSamplesFlushed(1)
        SessionDiagnostics.recordEvent(sid, "SESSION_CREATED", mapOf("cadence" to "PRECISE"))
        SessionDiagnostics.recordHeartbeat(
            sessionId = sid,
            heapUsedMb = 42L,
            batteryPct = 78,
            ignoringBatteryOpt = true,
            lastGpsFixAgeMs = 1200L
        )

        val text = SessionDiagnostics.readDiagnosticsText(sid)
        assertNotNull(text)
        assertTrue(text!!.contains("\"event\":\"SESSION_CREATED\""))
        assertTrue(text.contains("\"event\":\"HEARTBEAT\""))
        assertTrue(text.contains("\"droppedNoLocation\":1"))
        assertTrue(text.contains("\"heapUsedMb\":42"))

        SessionDiagnostics.deleteDiagnostics(sid)
        assertNull(SessionDiagnostics.readDiagnosticsText(sid))
    }

    @Test
    fun cloudTelemetry_usesLightweightSessionHeaderWithoutRetainingSamplesList() {
        val headerOnly = DriveTestSession(
            id = 1795667466126L,
            sessionNumber = 2,
            name = "26/11/69_2",
            startTime = 1795667466126L,
            sampleCount = 3887,
            avgRsrp = -86,
            isRecording = true,
            samples = emptyList()
        )
        val latestSample = DriveTestSample(
            id = 3887L,
            sessionId = headerOnly.id,
            timestamp = 1795688294105L,
            latitude = 13.75,
            longitude = 100.50,
            altitude = 10.0,
            speedKmh = 30f,
            bearing = 0f,
            accuracyMeters = 5f,
            metrics = CellMetrics(
                ratType = RatType.LTE,
                lte = LteMetrics(10, 123L, 1, 1850, 3, -95, -10, 10.0, 10)
            )
        )

        assertEquals(3887, CloudTelemetrySyncManager.calculateSampleCount(headerOnly, latestSample))
        assertEquals(-86.0, CloudTelemetrySyncManager.calculateAverageRsrp(headerOnly, latestSample) ?: 0.0, 0.01)
    }
}
