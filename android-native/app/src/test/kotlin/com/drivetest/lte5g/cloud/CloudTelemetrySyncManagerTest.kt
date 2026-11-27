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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
                simDisplayName = "AIS-SIM1",
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
                simDisplayName = "TRUE-SIM1",
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
                selectedSimSlot = 1,
                simDisplayName = "TRUE-SIM2",
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
        CloudTelemetrySyncManager.resetForTesting()
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
            firebaseDbUrl = "https://example-firebase.io",
            firebaseAuthToken = "secret123",
            liveTelemetryEnabled = true,
            cloudUploadUrl = "",
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
        val sample = createSample(1, 13.7563, 100.5018, -112, -2.5, is5g = false, hasSim2 = false)
        val jsonStr = CloudTelemetrySyncManager.buildTrailPointJson(sample)
        val json = JSONObject(jsonStr)

        // Legacy backward-compatible SIM 1 fields
        assertEquals(13.7563, json.getDouble("lat"), 0.0001)
        assertEquals(100.5018, json.getDouble("lon"), 0.0001)
        assertEquals(15.0, json.getDouble("alt"), 0.01)
        assertEquals(45.0, json.getDouble("speed"), 0.01)
        assertEquals(180.0, json.getDouble("bearing"), 0.01)
        assertEquals(3.5, json.getDouble("accuracyM"), 0.01)
        assertEquals(1L, json.getLong("cadenceSec"))
        assertEquals(-112, json.getInt("rsrp"))
        assertEquals(-10, json.getInt("rsrq"))
        assertEquals(-2.5, json.getDouble("sinr"), 0.01)
        assertEquals(101, json.getInt("pci"))
        assertEquals(1850, json.getInt("arfcn"))
        assertEquals("B3", json.getString("band"))
        assertEquals(12, json.getInt("cqi"))
        assertEquals(2002, json.getInt("tac"))
        assertEquals("987654", json.getString("cellId"))
        assertEquals("LTE", json.getString("rat"))
        assertEquals("TRUE-H", json.getString("operator"))
        assertEquals("520-03", json.getString("plmn"))
        assertEquals("TRUE-SIM1", json.getString("simName"))

        // Single-SIM must omit all sim2* keys cleanly
        assertFalse(json.has("sim2Rat"))
        assertFalse(json.has("sim2Rsrp"))
        assertFalse(json.has("sim2Rsrq"))
        assertFalse(json.has("sim2Sinr"))
        assertFalse(json.has("sim2Pci"))
        assertFalse(json.has("sim2Operator"))
    }

    @Test
    fun buildTrailPointJson_dualSim_serializesBothSimsWithoutOverwriting() {
        val sample = createSample(1, 13.7563, 100.5018, -82, 16.5, is5g = true, hasSim2 = true)
        val jsonStr = CloudTelemetrySyncManager.buildTrailPointJson(sample)
        val json = JSONObject(jsonStr)

        // SIM 1 preserved at top-level (backward compatible)
        assertEquals("NR_SA", json.getString("rat"))
        assertEquals("AIS 5G", json.getString("operator"))
        assertEquals("520-01", json.getString("plmn"))
        assertEquals("AIS-SIM1", json.getString("simName"))
        assertEquals(420, json.getInt("pci"))
        assertEquals(640000, json.getInt("arfcn"))
        assertEquals("n78", json.getString("band"))
        assertEquals(-82, json.getInt("rsrp"))
        assertEquals(-11, json.getInt("rsrq"))
        assertEquals(16.5, json.getDouble("sinr"), 0.01)
        assertEquals(1001, json.getInt("tac"))
        assertEquals("1234567890", json.getString("cellId"))

        // SIM 2 complete RF fields matching CSV parity
        assertEquals("LTE", json.getString("sim2Rat"))
        assertEquals("TRUE-H", json.getString("sim2Operator"))
        assertEquals("520-03", json.getString("sim2Plmn"))
        assertEquals("TRUE-SIM2", json.getString("sim2Name"))
        assertEquals(202, json.getInt("sim2Pci"))
        assertEquals(1850, json.getInt("sim2Arfcn"))
        assertEquals("B3", json.getString("sim2Band"))
        assertEquals(-88, json.getInt("sim2Rsrp"))
        assertEquals(-12, json.getInt("sim2Rsrq"))
        assertEquals(8.5, json.getDouble("sim2Sinr"), 0.01)
        assertEquals(10, json.getInt("sim2Cqi"))
        assertEquals(2002, json.getInt("sim2Tac"))
        assertEquals("456789", json.getString("sim2CellId"))
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

    @Test
    fun buildSessionMetadataJson_withSample_serializesAllFields() {
        val config = CloudConfig(
            vehicleId = "DT-V01",
            firebaseDbUrl = "https://example-firebase.io",
            firebaseAuthToken = "",
            liveTelemetryEnabled = true,
            cloudUploadUrl = "",
            autoUploadOnWifi = false
        )

        val session = DriveTestSession(
            id = 1762849200000L,
            sessionNumber = 1,
            name = "Session #1",
            startTime = 1762849200000L
        )

        val sample = createSample(1, 13.7563, 100.5018, -82, 16.5, is5g = true)

        val jsonStr = CloudTelemetrySyncManager.buildSessionMetadataJson(config, session, sample, isRecording = true)
        val json = JSONObject(jsonStr)

        assertEquals("1762849200000", json.getString("sessionId"))
        assertEquals("Session #1", json.getString("sessionName"))
        assertEquals("PRECISE", json.getString("cadence"))
        assertEquals("DT-V01", json.getString("vehicleId"))
        assertEquals(1762849200000L, json.getLong("startTime"))
        assertEquals(sample.timestamp, json.getLong("updatedAt"))
        assertEquals(1, json.getInt("sampleCount"))
        assertEquals("AIS 5G", json.getString("operator"))
        assertEquals("NR_SA", json.getString("rat"))
        assertEquals(-82.0, json.getDouble("avgRsrp"), 0.01)
        assertTrue(json.getBoolean("isRecording"))
    }

    @Test
    fun buildSessionMetadataJson_stoppedSession_serializesAllFields() {
        val config = CloudConfig(
            vehicleId = "DT-V02",
            firebaseDbUrl = "https://example-firebase.io",
            firebaseAuthToken = "",
            liveTelemetryEnabled = true,
            cloudUploadUrl = "",
            autoUploadOnWifi = false
        )

        val s1 = createSample(1, 13.75, 100.50, -80, 15.0, is5g = true)
        val s2 = createSample(2, 13.76, 100.51, -90, 10.0, is5g = true)

        val session = DriveTestSession(
            id = 1762849300000L,
            sessionNumber = 2,
            name = "Session #2",
            startTime = 1762849300000L,
            sampleCount = 2,
            samples = listOf(s1, s2),
            avgRsrp = -85
        )

        val jsonStr = CloudTelemetrySyncManager.buildSessionMetadataJson(config, session, null, isRecording = false)
        val json = JSONObject(jsonStr)

        assertEquals("1762849300000", json.getString("sessionId"))
        assertEquals("Session #2", json.getString("sessionName"))
        assertEquals("PRECISE", json.getString("cadence"))
        assertEquals("DT-V02", json.getString("vehicleId"))
        assertEquals(1762849300000L, json.getLong("startTime"))
        assertEquals(2, json.getInt("sampleCount"))
        assertEquals("AIS 5G", json.getString("operator"))
        assertEquals("NR_SA", json.getString("rat"))
        assertEquals(-85.0, json.getDouble("avgRsrp"), 0.01)
        assertFalse(json.getBoolean("isRecording"))
    }

    @Test
    fun offlineQueue_persistsSessionMetadata_andFlushesCorrectly() {
        val queueFile = File(tempFolder.root, "cloud_offline_queue.jsonl")
        assertFalse(queueFile.exists())

        // Save entry with session metadata
        CloudTelemetrySyncManager.saveToOfflineQueue(
            heartbeatJson = "{\"hb\":1}",
            sessionId = "sess_1",
            trailPointJson = "{\"tp\":1}",
            sessionMetaJson = "{\"sm\":1}"
        )

        // Save legacy entry without session metadata (backward compat)
        CloudTelemetrySyncManager.saveToOfflineQueue(
            heartbeatJson = "{\"hb\":2}",
            sessionId = "sess_2",
            trailPointJson = "{\"tp\":2}"
        )

        assertEquals(2, CloudTelemetrySyncManager.getOfflineQueueCount())

        val lines = queueFile.readLines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("\"sm\":\"{\\\"sm\\\":1}\""))
        assertFalse(lines[1].contains("\"sm\":"))
    }

    @Test
    fun extractOfflineQueuedSamplesForSession_filtersBySessionAndPreservesQueue() {
        val sid = 1795574251243L
        CloudTelemetrySyncManager.saveToOfflineQueue(
            heartbeatJson = "{}",
            sessionId = sid.toString(),
            trailPointJson = """{"ts":${sid + 2000L},"lat":17.95,"lon":103.11,"rsrp":-94,"sinr":1.8,"pci":86,"rat":"LTE","speed":20.0}"""
        )
        CloudTelemetrySyncManager.saveToOfflineQueue(
            heartbeatJson = "{}",
            sessionId = "9999999999999",
            trailPointJson = """{"ts":${sid + 3000L},"lat":17.96,"lon":103.12,"rsrp":-88,"sinr":5.0,"pci":12,"rat":"NR_SA","speed":30.0}"""
        )

        val extracted = CloudTelemetrySyncManager.extractOfflineQueuedSamplesForSession(sid)
        assertEquals(1, extracted.size)
        assertEquals(sid + 2000L, extracted[0].timestamp)
        assertEquals(-94, extracted[0].metrics.lte?.rsrp)
        assertEquals(86, extracted[0].metrics.lte?.pci)
        assertEquals(2, CloudTelemetrySyncManager.getOfflineQueueCount())
    }

    @Test
    fun parseTrailJsonMapToSamples_sortsChronologicallyAndMapsRfFields() {
        val sid = 1795574251243L
        val json = """
            {
              "-K2": {"ts": ${sid + 5000L}, "lat": 17.96, "lon": 103.12, "rsrp": -95, "sinr": 2.0, "pci": 86, "rat": "LTE", "speed": 40.0},
              "-K1": {"ts": ${sid + 1000L}, "lat": 17.95, "lon": 103.11, "rsrp": -93, "sinr": 3.0, "pci": 86, "rat": "LTE", "speed": 30.0}
            }
        """.trimIndent()

        val samples = CloudTelemetrySyncManager.parseTrailJsonMapToSamples(sid, json)
        assertEquals(2, samples.size)
        assertEquals(sid + 1000L, samples[0].timestamp)
        assertEquals(sid + 5000L, samples[1].timestamp)
        assertEquals(-93, samples[0].metrics.lte?.rsrp)
        assertEquals(86, samples[1].metrics.lte?.pci)
        assertNull(samples[0].metricsSim2)
        assertNull(samples[1].metricsSim2)
    }

    @Test
    fun parseTrailJsonMapToSamples_dualSimRoundTrip_preservesBothSims() {
        val sid = 1762849200000L
        val dualSample = createSample(1, 13.7563, 100.5018, -82, 16.5, is5g = true, hasSim2 = true)
        val singleSample = createSample(2, 13.7570, 100.5025, -95, 4.0, is5g = false, hasSim2 = false)

        val trailMap = JSONObject().apply {
            put("-P2", JSONObject(CloudTelemetrySyncManager.buildTrailPointJson(singleSample)))
            put("-P1", JSONObject(CloudTelemetrySyncManager.buildTrailPointJson(dualSample)))
        }.toString()

        val parsed = CloudTelemetrySyncManager.parseTrailJsonMapToSamples(sid, trailMap)
        assertEquals(2, parsed.size)

        // First sample: dual-SIM
        val s1 = parsed[0]
        assertEquals(RatType.NR_SA, s1.metrics.ratType)
        assertEquals("AIS 5G", s1.metrics.operatorAlpha)
        assertEquals(-82, s1.metrics.nr?.ssRsrp)
        assertEquals(-11, s1.metrics.nr?.ssRsrq)
        assertEquals(78, s1.metrics.nr?.band)
        assertEquals(640000, s1.metrics.nr?.nrArfcn)
        assertEquals(1234567890L, s1.metrics.nr?.nci)

        val sim2 = s1.metricsSim2
        assertNotNull("Dual-SIM trail sample must preserve metricsSim2", sim2)
        assertEquals(RatType.LTE, sim2!!.ratType)
        assertEquals("TRUE-H", sim2.operatorAlpha)
        assertEquals("520-03", sim2.plmn)
        assertEquals("TRUE-SIM2", sim2.simDisplayName)
        assertEquals(202, sim2.lte?.pci)
        assertEquals(1850, sim2.lte?.earfcn)
        assertEquals(3, sim2.lte?.band)
        assertEquals(-88, sim2.lte?.rsrp)
        assertEquals(-12, sim2.lte?.rsrq)
        assertEquals(8.5, sim2.lte?.rssnr ?: 0.0, 0.01)
        assertEquals(10, sim2.lte?.cqi)
        assertEquals(2002, sim2.lte?.tac)
        assertEquals(456789L, sim2.lte?.eci)

        // Second sample: single-SIM
        val s2 = parsed[1]
        assertEquals(RatType.LTE, s2.metrics.ratType)
        assertEquals(-95, s2.metrics.lte?.rsrp)
        assertNull("Single-SIM trail sample must keep metricsSim2 null", s2.metricsSim2)
    }

    @Test
    fun parseTrailJsonMapToSamples_legacyPayloadWithoutSinrOrSim2_parsesGracefully() {
        val sid = 1795655977938L
        val legacyJson = """
            {
              "-P7KQxwy-D61dp5POoTb":{"lat":17.8809848,"lon":102.7520226,"pci":110,"rat":"LTE","rsrp":-93,"speed":0,"ts":1795655981988},
              "-P7KR_unknown":{"lat":17.8810000,"lon":102.7521000,"rat":"UNKNOWN","speed":0,"ts":1795655983004}
            }
        """.trimIndent()

        val parsed = CloudTelemetrySyncManager.parseTrailJsonMapToSamples(sid, legacyJson)
        assertEquals(2, parsed.size)
        assertEquals(-93, parsed[0].metrics.lte?.rsrp)
        assertNull(parsed[0].metrics.lte?.rssnr)
        assertNull(parsed[0].metricsSim2)
        assertEquals(RatType.UNKNOWN, parsed[1].metrics.ratType)
        assertNull(parsed[1].metrics.lte)
        assertNull(parsed[1].metricsSim2)
    }
}
