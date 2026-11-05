package com.drivetest.lte5g.ui.map

import com.drivetest.lte5g.model.CellMetrics
import com.drivetest.lte5g.model.DriveTestSample
import com.drivetest.lte5g.model.LteMetrics
import com.drivetest.lte5g.model.NrMetrics
import com.drivetest.lte5g.model.RatType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-logic tests for the Route Map color modes. No Compose dependency:
 * the map screen resolves colors through these functions, so covering them
 * here keeps the tier boundaries and legend contents verifiable on the JVM.
 */
class RouteColorModeTest {

    private fun lte(
        rsrp: Int? = null,
        rsrq: Int? = null,
        rssnr: Double? = null
    ) = LteMetrics(
        pci = 1,
        eci = 1L,
        tac = 1,
        earfcn = 1,
        band = 1,
        rsrp = rsrp,
        rsrq = rsrq,
        rssnr = rssnr,
        cqi = 1
    )

    private fun nr(
        ssRsrp: Int? = null,
        ssRsrq: Int? = null,
        ssSinr: Double? = null,
        isNsa: Boolean = false
    ) = NrMetrics(
        pci = 1,
        nci = 1L,
        tac = 1,
        nrArfcn = 1,
        band = 1,
        ssRsrp = ssRsrp,
        ssRsrq = ssRsrq,
        ssSinr = ssSinr,
        csiRsrp = null,
        csiRsrq = null,
        csiSinr = null,
        isNsa = isNsa
    )

    private fun sample(
        speedKmh: Float = 0f,
        metrics: CellMetrics = CellMetrics(),
        metricsSim2: CellMetrics? = null
    ) = DriveTestSample(
        id = 1,
        sessionId = 1,
        timestamp = 0L,
        latitude = 13.0,
        longitude = 100.0,
        altitude = 0.0,
        speedKmh = speedKmh,
        bearing = 0f,
        accuracyMeters = 0f,
        metrics = metrics,
        metricsSim2 = metricsSim2
    )

    // ---- metricsForSim ----------------------------------------------------

    @Test
    fun metricsForSim_slot1_returnsPrimaryMetrics() {
        val primary = CellMetrics(ratType = RatType.LTE, lte = lte(rsrp = -90))
        val secondary = CellMetrics(nr = nr(ssRsrp = -70))
        val s = sample(metrics = primary, metricsSim2 = secondary)

        assertEquals(primary, metricsForSim(s, 1))
    }

    @Test
    fun metricsForSim_slot2_returnsSecondaryMetrics() {
        val primary = CellMetrics(ratType = RatType.LTE, lte = lte(rsrp = -90))
        val secondary = CellMetrics(nr = nr(ssRsrp = -70))
        val s = sample(metrics = primary, metricsSim2 = secondary)

        assertEquals(secondary, metricsForSim(s, 2))
    }

    @Test
    fun metricsForSim_slot2_returnsNullWhenSingleSimSession() {
        val s = sample(metrics = CellMetrics(lte = lte(rsrp = -90)))

        assertNull(metricsForSim(s, 2))
    }

    // ---- metric extraction ------------------------------------------------

    @Test
    fun rsrpOf_prefersNrOverLte() {
        val m = CellMetrics(lte = lte(rsrp = -100), nr = nr(ssRsrp = -80))

        assertEquals(-80, rsrpOf(m))
    }

    @Test
    fun rsrpOf_fallsBackToLte() {
        val m = CellMetrics(lte = lte(rsrp = -100))

        assertEquals(-100, rsrpOf(m))
    }

    @Test
    fun sinrOf_prefersNrOverLte() {
        val m = CellMetrics(lte = lte(rssnr = 5.0), nr = nr(ssSinr = 21.5))

        assertEquals(21.5, sinrOf(m)!!, 0.001)
    }

    @Test
    fun metricAccessors_returnNullForNullMetrics() {
        assertNull(rsrpOf(null))
        assertNull(rsrqOf(null))
        assertNull(sinrOf(null))
    }

    // ---- tier boundaries --------------------------------------------------

    @Test
    fun rsrpTier_boundariesMatchDashboardGauge() {
        assertEquals(1, rsrpTier(-85))
        assertEquals(2, rsrpTier(-86))
        assertEquals(2, rsrpTier(-95))
        assertEquals(3, rsrpTier(-96))
        assertEquals(3, rsrpTier(-105))
        assertEquals(4, rsrpTier(-106))
        assertEquals(4, rsrpTier(-115))
        assertEquals(5, rsrpTier(-116))
    }

    @Test
    fun rsrqTier_boundariesMatchDashboardGauge() {
        assertEquals(1, rsrqTier(-10))
        assertEquals(2, rsrqTier(-11))
        assertEquals(2, rsrqTier(-15))
        assertEquals(3, rsrqTier(-16))
        assertEquals(3, rsrqTier(-19))
        assertEquals(5, rsrqTier(-20))
    }

    @Test
    fun sinrTier_boundariesMatchDashboardGauge() {
        assertEquals(1, sinrTier(20.0))
        assertEquals(2, sinrTier(19.9))
        assertEquals(2, sinrTier(13.0))
        assertEquals(3, sinrTier(12.9))
        assertEquals(3, sinrTier(5.0))
        assertEquals(4, sinrTier(4.9))
        assertEquals(4, sinrTier(0.0))
        assertEquals(5, sinrTier(-0.1))
    }

    @Test
    fun speedTier_boundaries() {
        assertEquals(1, speedTier(0.0))
        assertEquals(1, speedTier(19.9))
        assertEquals(2, speedTier(20.0))
        assertEquals(2, speedTier(39.9))
        assertEquals(3, speedTier(40.0))
        assertEquals(3, speedTier(59.9))
        assertEquals(4, speedTier(60.0))
        assertEquals(4, speedTier(79.9))
        assertEquals(5, speedTier(80.0))
    }

    // ---- RAT category -----------------------------------------------------

    @Test
    fun ratCategoryOf_classifiesSaNsaLteAndUnknown() {
        assertEquals(RatCategory.NR_SA, ratCategoryOf(CellMetrics(nr = nr(isNsa = false))))
        assertEquals(RatCategory.NR_NSA, ratCategoryOf(CellMetrics(nr = nr(isNsa = true))))
        assertEquals(RatCategory.LTE, ratCategoryOf(CellMetrics(lte = lte(rsrp = -90))))
        assertEquals(RatCategory.UNKNOWN, ratCategoryOf(CellMetrics()))
        assertEquals(RatCategory.UNKNOWN, ratCategoryOf(null))
    }

    @Test
    fun ratCategoryOf_prefersNrWhenBothPresent() {
        val m = CellMetrics(lte = lte(rsrp = -90), nr = nr(ssRsrp = -80, isNsa = true))

        assertEquals(RatCategory.NR_NSA, ratCategoryOf(m))
    }

    @Test
    fun ratCategory_legendKeysAreUniqueAndOrdered() {
        val keys = RatCategory.entries.map { it.legendKey }

        assertEquals(listOf(1, 2, 3, 4), keys.sorted())
        assertEquals(keys.size, keys.toSet().size)
    }

    // ---- legendKeyFor -----------------------------------------------------

    @Test
    fun legendKeyFor_rsrpMode_usesSimSlotMetrics() {
        val s = sample(
            metrics = CellMetrics(lte = lte(rsrp = -90)),
            metricsSim2 = CellMetrics(lte = lte(rsrp = -110))
        )

        assertEquals(2, legendKeyFor(s, RouteColorMode.RSRP, 1))
        assertEquals(4, legendKeyFor(s, RouteColorMode.RSRP, 2))
    }

    @Test
    fun legendKeyFor_returnsNullWhenMetricMissing() {
        val s = sample(metrics = CellMetrics())

        assertNull(legendKeyFor(s, RouteColorMode.RSRP, 1))
        assertNull(legendKeyFor(s, RouteColorMode.RSRQ, 1))
        assertNull(legendKeyFor(s, RouteColorMode.SINR, 1))
        assertNull(legendKeyFor(s, RouteColorMode.RSRP, 2))
    }

    @Test
    fun legendKeyFor_speedMode_ignoresSimSlot() {
        val s = sample(speedKmh = 45.0f, metricsSim2 = null)

        assertEquals(3, legendKeyFor(s, RouteColorMode.SPEED, 1))
        assertEquals(3, legendKeyFor(s, RouteColorMode.SPEED, 2))
    }

    @Test
    fun legendKeyFor_technologyMode_neverReturnsNull() {
        val s = sample(metrics = CellMetrics())

        assertEquals(RatCategory.UNKNOWN.legendKey, legendKeyFor(s, RouteColorMode.TECHNOLOGY, 1))
    }

    // ---- legendEntries ----------------------------------------------------

    @Test
    fun legendEntries_rsrpAndSinrAndSpeedCoverFiveTiers() {
        for (mode in listOf(RouteColorMode.RSRP, RouteColorMode.SINR, RouteColorMode.SPEED)) {
            val keys = legendEntries(mode).map { it.first }
            assertEquals("mode=$mode", listOf(1, 2, 3, 4, 5), keys)
        }
    }

    @Test
    fun legendEntries_rsrqHasNoFourthTier() {
        val keys = legendEntries(RouteColorMode.RSRQ).map { it.first }

        assertEquals(listOf(1, 2, 3, 5), keys)
    }

    @Test
    fun legendEntries_technologyCoversAllCategoriesInOrder() {
        val entries = legendEntries(RouteColorMode.TECHNOLOGY)

        assertEquals(listOf(1, 2, 3, 4), entries.map { it.first })
        assertEquals(listOf("5G SA", "5G NSA", "LTE", "UNKNOWN"), entries.map { it.second })
    }

    @Test
    fun legendEntries_labelsAreNonEmptyForEveryMode() {
        for (mode in RouteColorMode.entries) {
            val entries = legendEntries(mode)
            assertEquals("mode=$mode", true, entries.isNotEmpty())
            entries.forEach { (_, label) ->
                assertEquals("mode=$mode label blank", true, label.isNotBlank())
            }
        }
    }

    @Test
    fun everyModeHasLegendTitle() {
        for (mode in RouteColorMode.entries) {
            assertEquals("mode=$mode", true, mode.legendTitle.isNotBlank())
            assertEquals("mode=$mode", true, mode.label.isNotBlank())
        }
    }
}
