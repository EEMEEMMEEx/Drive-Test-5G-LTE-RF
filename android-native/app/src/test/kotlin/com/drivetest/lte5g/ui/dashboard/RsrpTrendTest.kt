package com.drivetest.lte5g.ui.dashboard

import com.drivetest.lte5g.model.CellMetrics
import com.drivetest.lte5g.model.DriveTestSample
import com.drivetest.lte5g.model.LteMetrics
import com.drivetest.lte5g.model.NrMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RsrpTrendTest {

    @Test
    fun rsrpTrendSeries_capsWindowToLastNSamples() {
        val samples = (1..70).map { i ->
            sample(metrics = CellMetrics(lte = lte(rsrp = -60 - i)))
        }
        val series = rsrpTrendSeries(samples)
        assertEquals(60, series.sim1.size)
        // window keeps the last 60 samples: first kept is sample #11 (rsrp -71), last is #70 (rsrp -130)
        assertEquals(-71, series.sim1.first())
        assertEquals(-130, series.sim1.last())
    }

    @Test
    fun rsrpTrendSeries_emptySamplesProduceEmptySeries() {
        val series = rsrpTrendSeries(emptyList())
        assertEquals(0, series.sim1.size)
        assertEquals(0, series.sim2.size)
    }

    @Test
    fun rsrpTrendSeries_sim1PrefersNrAndFallsBackToLte() {
        val nrSample = sample(metrics = CellMetrics(lte = lte(rsrp = -90), nr = nr(ssRsrp = -70)))
        val lteSample = sample(metrics = CellMetrics(lte = lte(rsrp = -95)))
        val series = rsrpTrendSeries(listOf(nrSample, lteSample))
        assertEquals(listOf(-70, -95), series.sim1)
    }

    @Test
    fun rsrpTrendSeries_sim2NullWithoutMetricsSim2() {
        val series = rsrpTrendSeries(listOf(sample(metrics = CellMetrics(lte = lte(rsrp = -90)))))
        assertEquals(1, series.sim2.size)
        assertNull(series.sim2.first())
    }

    @Test
    fun rsrpTrendSeries_sim2AlignedWithSim1PerSample() {
        val s1 = sample(
            metrics = CellMetrics(lte = lte(rsrp = -80)),
            metricsSim2 = CellMetrics(lte = lte(rsrp = -100))
        )
        val s2 = sample(metrics = CellMetrics(lte = lte(rsrp = -85)))
        val series = rsrpTrendSeries(listOf(s1, s2))
        assertEquals(listOf(-80, -85), series.sim1)
        assertEquals(listOf(-100, null), series.sim2)
    }

    @Test
    fun trendNormY_mapsRangeToChartCoordinates() {
        assertEquals(1f, trendNormY(-130), 0.0001f)
        assertEquals(0f, trendNormY(-60), 0.0001f)
        assertEquals(0.5f, trendNormY(-95), 0.0001f)
    }

    @Test
    fun trendNormY_clampsValuesOutsideRange() {
        assertEquals(1f, trendNormY(-140), 0.0001f)
        assertEquals(0f, trendNormY(-50), 0.0001f)
    }

    // --- builders (same pattern as RouteColorModeTest) ---

    private fun lte(rsrp: Int? = null) = LteMetrics(
        pci = 1,
        eci = 1L,
        tac = 1,
        earfcn = 1,
        band = 1,
        rsrp = rsrp,
        rsrq = null,
        rssnr = null,
        cqi = 1
    )

    private fun nr(ssRsrp: Int? = null) = NrMetrics(
        pci = 1,
        nci = 1L,
        tac = 1,
        nrArfcn = 1,
        band = 1,
        ssRsrp = ssRsrp,
        ssRsrq = null,
        ssSinr = null,
        csiRsrp = null,
        csiRsrq = null,
        csiSinr = null,
        isNsa = false
    )

    private fun sample(
        metrics: CellMetrics = CellMetrics(),
        metricsSim2: CellMetrics? = null
    ) = DriveTestSample(
        id = 1,
        sessionId = 1,
        timestamp = 0L,
        latitude = 13.0,
        longitude = 100.0,
        altitude = 0.0,
        speedKmh = 0f,
        bearing = 0f,
        accuracyMeters = 0f,
        metrics = metrics,
        metricsSim2 = metricsSim2
    )
}
