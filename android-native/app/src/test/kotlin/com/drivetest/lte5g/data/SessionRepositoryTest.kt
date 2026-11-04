package com.drivetest.lte5g.data

import com.drivetest.lte5g.model.CellMetrics
import com.drivetest.lte5g.model.DriveTestSample
import com.drivetest.lte5g.model.LteMetrics
import com.drivetest.lte5g.model.MeasurementCadence
import com.drivetest.lte5g.model.NrMetrics
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
}
