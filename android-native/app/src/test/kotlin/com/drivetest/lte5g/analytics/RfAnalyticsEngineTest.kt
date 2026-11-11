package com.drivetest.lte5g.analytics

import com.drivetest.lte5g.data.analytics.MobilityEventType
import com.drivetest.lte5g.data.analytics.ProblemSeverity
import com.drivetest.lte5g.data.analytics.RfAnalyticsEngine
import com.drivetest.lte5g.data.analytics.RfProblemType
import com.drivetest.lte5g.model.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RfAnalyticsEngineTest {

    private fun createSample(
        id: Long,
        timestamp: Long,
        lat: Double = 13.75,
        lon: Double = 100.50,
        pci: Int = 100,
        rsrp: Int = -85,
        sinr: Double = 15.0,
        neighbors: List<NeighborCell> = emptyList(),
        isSim2: Boolean = false,
        sim2Rsrp: Int? = null
    ): DriveTestSample {
        return DriveTestSample(
            id = id,
            sessionId = 1,
            timestamp = timestamp,
            latitude = lat,
            longitude = lon,
            altitude = 10.0,
            speedKmh = 30f,
            bearing = 0f,
            accuracyMeters = 5f,
            metrics = CellMetrics(
                ratType = RatType.LTE,
                plmn = "520-01",
                operatorAlpha = "AIS",
                lte = LteMetrics(
                    pci = pci,
                    eci = 123456L,
                    tac = 101,
                    earfcn = 1850,
                    band = 3,
                    rsrp = rsrp,
                    rsrq = -10,
                    rssnr = sinr,
                    cqi = 10
                ),
                neighbors = neighbors
            ),
            metricsSim2 = if (isSim2 && sim2Rsrp != null) {
                CellMetrics(
                    ratType = RatType.NR_SA,
                    plmn = "520-04",
                    operatorAlpha = "TRUE-DTAC",
                    nr = NrMetrics(
                        pci = 200,
                        nci = 987654L,
                        tac = 102,
                        nrArfcn = 500000,
                        band = 78,
                        ssRsrp = sim2Rsrp,
                        ssRsrq = -11,
                        ssSinr = 12.0,
                        csiRsrp = sim2Rsrp,
                        csiRsrq = -11,
                        csiSinr = 12.0,
                        isNsa = false
                    )
                )
            } else null
        )
    }

    @Test
    fun analyze_emptySession_returnsDefaultZeroedAnalytics() {
        val session = DriveTestSession(
            id = 1,
            name = "Empty Session",
            startTime = 1000L,
            samples = emptyList()
        )

        val result = RfAnalyticsEngine.analyze(session)
        assertEquals(0, result.totalSamples)
        assertEquals(100, result.routeHealthScore)
        assertNull(result.rsrpCdf)
    }

    @Test
    fun calculateCdf_computesAccuratePercentiles() {
        // Values from -120 to -60 dBm
        val values = listOf(-120.0, -110.0, -100.0, -90.0, -80.0, -70.0, -60.0)
        val cdf = RfAnalyticsEngine.calculateCdf(values)

        assertNotNull(cdf)
        assertEquals(-120.0, cdf!!.min, 0.001)
        assertEquals(-60.0, cdf.max, 0.001)
        assertEquals(-90.0, cdf.p50, 0.001) // median
        assertTrue(cdf.p10 <= cdf.p50)
        assertTrue(cdf.p90 >= cdf.p50)
        assertTrue(cdf.points.isNotEmpty())
    }

    @Test
    fun detectProblemAreas_identifiesCoverageHoleAndInterference() {
        val samples = listOf(
            // Good sample
            createSample(1, 1000L, rsrp = -80, sinr = 18.0),
            // Coverage hole (-115 dBm)
            createSample(2, 2000L, rsrp = -115, sinr = 2.0),
            // High severity coverage hole (-122 dBm)
            createSample(3, 3000L, rsrp = -122, sinr = -1.0),
            // Pilot Pollution / Interference (Strong RSRP -85 dBm, but negative SINR -4.0 dB)
            createSample(4, 4000L, rsrp = -85, sinr = -4.0)
        )

        val problems = RfAnalyticsEngine.detectProblemAreas(samples)
        assertEquals(3, problems.size)

        val holeMed = problems[0]
        assertEquals(RfProblemType.COVERAGE_HOLE, holeMed.type)
        assertEquals(ProblemSeverity.MEDIUM, holeMed.severity)

        val holeHigh = problems[1]
        assertEquals(RfProblemType.COVERAGE_HOLE, holeHigh.type)
        assertEquals(ProblemSeverity.HIGH, holeHigh.severity)

        val interference = problems[2]
        assertEquals(RfProblemType.PILOT_POLLUTION_INTERFERENCE, interference.type)
        assertEquals(ProblemSeverity.HIGH, interference.severity)
    }

    @Test
    fun detectMobilityEvents_identifiesNormalHandoverAndPingPong() {
        val samples = listOf(
            createSample(1, 1000L, pci = 100),
            createSample(2, 2000L, pci = 100),
            // Handover to PCI 200 at 5000L
            createSample(3, 5000L, pci = 200),
            createSample(4, 8000L, pci = 200),
            // Ping-pong back to PCI 100 within 10s (at 15000L, < 30s)
            createSample(5, 15000L, pci = 100)
        )

        val events = RfAnalyticsEngine.detectMobilityEvents(samples)
        assertEquals(2, events.size)

        val ho1 = events[0]
        assertEquals(MobilityEventType.HANDOVER, ho1.type)
        assertEquals(100, ho1.sourcePci)
        assertEquals(200, ho1.targetPci)

        val pingPong = events[1]
        assertEquals(MobilityEventType.PING_PONG_HO, pingPong.type)
        assertEquals(200, pingPong.sourcePci)
        assertEquals(100, pingPong.targetPci)
    }

    @Test
    fun detectMobilityEvents_identifiesMissedHandover() {
        val strongNeighbor = NeighborCell(
            rat = RatType.LTE,
            pci = 350,
            arfcn = 1850,
            rsrp = -75, // +15 dB better than serving cell (-90 dBm)
            rsrq = -8,
            sinr = 15.0
        )

        val samples = listOf(
            createSample(1, 1000L, pci = 100, rsrp = -90, neighbors = listOf(strongNeighbor)),
            createSample(2, 2000L, pci = 100, rsrp = -90, neighbors = listOf(strongNeighbor)),
            createSample(3, 3000L, pci = 100, rsrp = -90, neighbors = listOf(strongNeighbor))
        )

        val events = RfAnalyticsEngine.detectMobilityEvents(samples)
        assertTrue(events.any { it.type == MobilityEventType.MISSED_HO && it.targetPci == 350 })
    }

    @Test
    fun buildGeminiPromptPayload_serializesValidJson() {
        val samples = listOf(
            createSample(1, 1000L, rsrp = -82, sinr = 16.0),
            createSample(2, 2000L, rsrp = -88, sinr = 12.0)
        )
        val session = DriveTestSession(
            id = 12345,
            name = "Test Route",
            startTime = 1000L,
            endTime = 2000L,
            samples = samples
        )

        val analytics = RfAnalyticsEngine.analyze(session)
        val payloadStr = RfAnalyticsEngine.buildGeminiPromptPayload(session, analytics)

        assertNotNull(payloadStr)
        val json = JSONObject(payloadStr)
        assertEquals("Test Route", json.getString("session_name"))
        assertEquals(2, json.getInt("total_samples"))
        assertTrue(json.has("rsrp_statistics"))
        assertTrue(json.has("mobility_summary"))
    }

    @Test
    fun buildGeminiPromptPayload_localeWithComma_preservesValidJsonNumbers() {
        val originalLocale = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            val samples = listOf(
                createSample(1, 1000L, rsrp = -83, sinr = 14.5),
                createSample(2, 2000L, rsrp = -87, sinr = 11.2)
            )
            val session = DriveTestSession(
                id = 12345,
                name = "German Locale Route",
                startTime = 1000L,
                endTime = 2000L,
                samples = samples
            )
            val analytics = RfAnalyticsEngine.analyze(session)
            val payloadStr = RfAnalyticsEngine.buildGeminiPromptPayload(session, analytics)

            val json = JSONObject(payloadStr)
            val rsrpStats = json.getJSONObject("rsrp_statistics")
            assertTrue(rsrpStats.getDouble("avg_dbm") < 0)
        } finally {
            java.util.Locale.setDefault(originalLocale)
        }
    }
}
