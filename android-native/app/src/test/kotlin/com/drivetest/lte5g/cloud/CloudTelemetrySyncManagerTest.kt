package com.drivetest.lte5g.cloud

import com.drivetest.lte5g.model.CellMetrics
import com.drivetest.lte5g.model.DriveTestSample
import com.drivetest.lte5g.model.DriveTestSession
import com.drivetest.lte5g.model.LteMetrics
import com.drivetest.lte5g.model.MeasurementCadence
import com.drivetest.lte5g.model.NrMetrics
import com.drivetest.lte5g.model.RatLockMode
import com.drivetest.lte5g.model.RatType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class CloudTelemetrySyncManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = StandardTestDispatcher()

    private fun createSample(
        id: Long,
        lat: Double,
        lon: Double,
        rsrp: Int,
        sinr: Double,
        is5g: Boolean = true,
        hasSim2: Boolean = false
    ): DriveTestSample {
        val primaryMetrics = if (is5g) {
            CellMetrics(
                ratType = RatType.NR_SA,
                plmn = "520-01",
                operatorAlpha = "AIS 5G",
                nr = NrMetrics(
                    pci = 420,
                    nci = 1234567890L,
                    tac = 1001,
                    nrArfcn = 640000,
                    band = 78,
                    ssRsrp = rsrp,
                    ssRsrq = -11,
                    ssSinr = sinr,
                    csiRsrp = rsrp - 2,
                    csiRsrq = -12,
                    csiSinr = sinr - 0.5,
                    isNsa = false
                )
            )
        } else {
            CellMetrics(
                ratType = RatType.LTE,
                plmn = "520-03",
                operatorAlpha = "TRUE-H",
                lte = LteMetrics(
                    pci = 101,
                    eci = 987654L,
                    tac = 2002,
                    earfcn = 1850,
                    band = 3,
                    rsrp = rsrp,
                    rsrq = -10,
                    rssnr = sinr,
                    cqi = 12
                )
            )
        }

        val secondaryMetrics = if (hasSim2) {
            CellMetrics(
                ratType = RatType.LTE,
                plmn = "520-03",
                operatorAlpha = "TRUE-H",
                lte = LteMetrics(
                    pci = 202,
                    eci = 456789L,
                    tac = 2002,
                    earfcn = 1850,
                    band = 3,
                    rsrp = -88,
                    rsrq = -12,
                    rssnr = 8.5,
                    cqi = 10
                )
            )
        } else null

        return DriveTestSample(
            id = id,
            sessionId = 1762849200000L,
            timestamp = 1762849500000L + id * 1000L,
            latitude = lat,
            longitude = lon,
            altitude = 15.0,
            speedKmh = 45.0f,
            bearing = 180.0f,
            accuracyMeters = 3.5f,
            metrics = primaryMetrics,
            metricsSim2 = secondaryMetrics,
            ratLockMode = RatLockMode.AUTO,
            cadence = MeasurementCadence.PRECISE
        )
    }

    @Before
    fun setUp() {
        CloudConfigRepository.resetForTesting()
        CloudTelemetrySyncManager.setStorageDirForTesting(tempFolder.root, testDispatcher)
    }

    @Test
    fun computeAlertState_classifiesCorrectly() {
        // Normal
        assertEquals("NORMAL", CloudTelemetrySyncManager.computeAlertState(-85, 15.0))
        assertEquals("NORMAL", CloudTelemetrySyncManager.computeAlertState(-105, 5.0))

        // Coverage Hole (RSRP <= -110 dBm)
        assertEquals("COVERAGE_HOLE", CloudTelemetrySyncManager.computeAlertState(-110, 10.0))
        assertEquals("COVERAGE_HOLE", CloudTelemetrySyncManager.computeAlertState(-118, -2.0))

        // Interference (RSRP >= -95 dBm and SINR < 0.0 dB)
        assertEquals("INTERFERENCE", CloudTelemetrySyncManager.computeAlertState(-80, -1.5))
        assertEquals("INTERFERENCE", CloudTelemetrySyncManager.computeAlertState(-95, -0.1))

        // Low RSRP with negative SINR is considered Coverage Hole
        assertEquals("COVERAGE_HOLE", CloudTelemetrySyncManager.computeAlertState(-112, -4.0))
    }

    @Test
    fun buildVehicleHeartbeatJson_serializesExpectedFields() {
        val config = CloudConfig(
            vehicleId = "DT-V01",
            vehicleName = "Test Unit 01",
            firebaseDbUrl = "https://example-firebase.io",
            firebaseAuthToken = "secret123",
            liveTelemetryEnabled = true,
            googleDriveAccessToken = "token123",
            googleDriveFolderId = "folder123",
            autoUploadOnWifi = true
        )

        val session = DriveTestSession(
            id = 1762849200000L,
            sessionNumber = 1,
            name = "Session #1",
            startTime = 1762849200000L
        )

        val sample = createSample(1, 13.7563, 100.5018, -82, 16.5, is5g = true, hasSim2 = true)

        val jsonStr = CloudTelemetrySyncManager.buildVehicleHeartbeatJson(config, session, sample)
        val json = JSONObject(jsonStr)

        assertEquals("DT-V01", json.getString("vehicleId"))
        assertEquals("Test Unit 01", json.getString("vehicleName"))
        assertEquals("1762849200000", json.getString("sessionId"))
        assertTrue(json.getBoolean("isRecording"))
        assertEquals(13.7563, json.getDouble("lat"), 0.0001)
        assertEquals(100.5018, json.getDouble("lon"), 0.0001)
        assertEquals(45.0, json.getDouble("speedKmh"), 0.01)
        assertEquals("NR_SA", json.getString("rat"))
        assertEquals("n78", json.getString("band"))
        assertEquals(420, json.getInt("pci"))
        assertEquals(-82, json.getInt("rsrp"))
        assertEquals(16.5, json.getDouble("sinr"), 0.01)
        assertEquals("NORMAL", json.getString("alertState"))

        // SIM 2 fields
        assertEquals("TRUE-H", json.getString("sim2Operator"))
        assertEquals("LTE", json.getString("sim2Rat"))
        assertEquals("B3", json.getString("sim2Band"))
        assertEquals(202, json.getInt("sim2Pci"))
        assertEquals(-88, json.getInt("sim2Rsrp"))
    }

    @Test
    fun buildTrailPointJson_serializesCompactPoint() {
        val sample = createSample(1, 13.7563, 100.5018, -112, -2.5, is5g = false)
        val jsonStr = CloudTelemetrySyncManager.buildTrailPointJson(sample)
        val json = JSONObject(jsonStr)

        assertEquals(13.7563, json.getDouble("lat"), 0.0001)
        assertEquals(100.5018, json.getDouble("lon"), 0.0001)
        assertEquals(-112, json.getInt("rsrp"))
        assertEquals(-2.5, json.getDouble("sinr"), 0.01)
        assertEquals(101, json.getInt("pci"))
        assertEquals("LTE", json.getString("rat"))
    }

    @Test
    fun offlineQueue_persistsAndCountsCorrectly() {
        val queueFile = File(tempFolder.root, "cloud_offline_queue.jsonl")
        assertFalse(queueFile.exists())
        assertEquals(0, CloudTelemetrySyncManager.getOfflineQueueCount())

        // Save 5 items
        for (i in 1..5) {
            CloudTelemetrySyncManager.saveToOfflineQueue("{\"hb\":$i}", "sess_1", "{\"tp\":$i}")
        }

        assertTrue(queueFile.exists())
        assertEquals(5, CloudTelemetrySyncManager.getOfflineQueueCount())
    }
}
