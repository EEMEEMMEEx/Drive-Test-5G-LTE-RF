package com.drivetest.lte5g.service

import com.drivetest.lte5g.model.CellMetrics
import com.drivetest.lte5g.model.LiveAlertStyle
import com.drivetest.lte5g.model.LteMetrics
import com.drivetest.lte5g.model.MeasurementCadence
import com.drivetest.lte5g.model.NrMetrics
import com.drivetest.lte5g.model.RatType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveAlertNotificationHelperTest {

    @Test
    fun testRsrpTierColors() {
        // >= -85: Tier 1 (Excellent - Emerald Green)
        assertEquals(LiveAlertNotificationHelper.COLOR_EXCELLENT, LiveAlertNotificationHelper.getRsrpTierColor(-70))
        assertEquals(LiveAlertNotificationHelper.COLOR_EXCELLENT, LiveAlertNotificationHelper.getRsrpTierColor(-85))

        // -86..-95: Tier 2 (Good - Light Green)
        assertEquals(LiveAlertNotificationHelper.COLOR_GOOD, LiveAlertNotificationHelper.getRsrpTierColor(-86))
        assertEquals(LiveAlertNotificationHelper.COLOR_GOOD, LiveAlertNotificationHelper.getRsrpTierColor(-95))

        // -96..-105: Tier 3 (Fair - Amber Yellow)
        assertEquals(LiveAlertNotificationHelper.COLOR_FAIR, LiveAlertNotificationHelper.getRsrpTierColor(-96))
        assertEquals(LiveAlertNotificationHelper.COLOR_FAIR, LiveAlertNotificationHelper.getRsrpTierColor(-105))

        // -106..-115: Tier 4 (Poor - Orange)
        assertEquals(LiveAlertNotificationHelper.COLOR_POOR, LiveAlertNotificationHelper.getRsrpTierColor(-106))
        assertEquals(LiveAlertNotificationHelper.COLOR_POOR, LiveAlertNotificationHelper.getRsrpTierColor(-115))

        // < -115: Tier 5 (Bad - Red)
        assertEquals(LiveAlertNotificationHelper.COLOR_BAD, LiveAlertNotificationHelper.getRsrpTierColor(-116))
        assertEquals(LiveAlertNotificationHelper.COLOR_BAD, LiveAlertNotificationHelper.getRsrpTierColor(-130))

        // null: Default Cyan
        assertEquals(LiveAlertNotificationHelper.COLOR_DEFAULT, LiveAlertNotificationHelper.getRsrpTierColor(null))
    }

    @Test
    fun testRsrpQualityLabels() {
        assertEquals("Excellent", LiveAlertNotificationHelper.getRsrpQualityLabel(-78))
        assertEquals("Good", LiveAlertNotificationHelper.getRsrpQualityLabel(-90))
        assertEquals("Fair", LiveAlertNotificationHelper.getRsrpQualityLabel(-100))
        assertEquals("Poor", LiveAlertNotificationHelper.getRsrpQualityLabel(-110))
        assertEquals("Bad", LiveAlertNotificationHelper.getRsrpQualityLabel(-120))
        assertEquals("Unknown", LiveAlertNotificationHelper.getRsrpQualityLabel(null))
    }

    @Test
    fun testFormatRatLabel() {
        // Null metrics
        assertEquals("Scanning...", LiveAlertNotificationHelper.formatRatLabel(null))

        // 5G NSA with LTE Anchor
        val nrMetrics = NrMetrics(
            pci = 301,
            nci = 12345678L,
            tac = 500,
            nrArfcn = 630000,
            band = 78,
            ssRsrp = -80,
            ssRsrq = -11,
            ssSinr = 15.0,
            csiRsrp = -82,
            csiRsrq = -12,
            csiSinr = 14.0,
            isNsa = true
        )
        val lteAnchor = LteMetrics(
            pci = 120,
            eci = 987654L,
            tac = 200,
            earfcn = 1750,
            band = 3,
            rsrp = -88,
            rsrq = -10,
            rssnr = 12.0,
            cqi = 14
        )
        val nsaMetrics = CellMetrics(
            ratType = RatType.NR_NSA,
            plmn = "52001",
            operatorAlpha = "AIS 5G",
            nr = nrMetrics,
            lte = lteAnchor
        )
        assertEquals("5G NSA (n78+B3)", LiveAlertNotificationHelper.formatRatLabel(nsaMetrics))

        // 5G SA standalone
        val saMetrics = CellMetrics(
            ratType = RatType.NR_SA,
            plmn = "52003",
            operatorAlpha = "TRUE 5G",
            nr = nrMetrics.copy(band = 28, isNsa = false)
        )
        assertEquals("5G SA (n28)", LiveAlertNotificationHelper.formatRatLabel(saMetrics))

        // LTE only
        val lteOnly = CellMetrics(
            ratType = RatType.LTE,
            plmn = "52005",
            operatorAlpha = "DTAC",
            lte = lteAnchor.copy(band = 1)
        )
        assertEquals("LTE (B1)", LiveAlertNotificationHelper.formatRatLabel(lteOnly))
    }

    @Test
    fun testFormatSpeed() {
        assertEquals("48.5 km/h", LiveAlertNotificationHelper.formatSpeed(48.54f))
        assertEquals("0.0 km/h", LiveAlertNotificationHelper.formatSpeed(0.0f))
        assertEquals("GPS Acquiring...", LiveAlertNotificationHelper.formatSpeed(null))
        assertEquals("GPS Acquiring...", LiveAlertNotificationHelper.formatSpeed(-1f))
    }

    @Test
    fun testFormatElapsedTime() {
        val start = 1_000_000L
        // 45 seconds later
        assertEquals("00:45", LiveAlertNotificationHelper.formatElapsedTime(start, start + 45_000L))
        // 14 minutes 32 seconds later
        assertEquals("14:32", LiveAlertNotificationHelper.formatElapsedTime(start, start + (14 * 60 + 32) * 1000L))
        // 1 hour 25 minutes 09 seconds later
        assertEquals("01:25:09", LiveAlertNotificationHelper.formatElapsedTime(start, start + (85 * 60 + 9) * 1000L))
    }

    @Test
    fun testGetRsrpExtraction() {
        val lteMetrics = LteMetrics(
            pci = 1, eci = 1L, tac = 1, earfcn = 1, band = 3,
            rsrp = -92, rsrq = -10, rssnr = 10.0, cqi = 10
        )
        val nrMetrics = NrMetrics(
            pci = 1, nci = 1L, tac = 1, nrArfcn = 1, band = 28,
            ssRsrp = -83, ssRsrq = -10, ssSinr = 12.0, csiRsrp = -85, csiRsrq = -11, csiSinr = 11.0, isNsa = false
        )

        // NR takes precedence if present
        val nrCell = CellMetrics(ratType = RatType.NR_SA, nr = nrMetrics, lte = lteMetrics)
        assertEquals(-83, LiveAlertNotificationHelper.getRsrp(nrCell))

        // LTE fallback when NR is absent
        val lteCell = CellMetrics(ratType = RatType.LTE, lte = lteMetrics)
        assertEquals(-92, LiveAlertNotificationHelper.getRsrp(lteCell))

        // Null when neither is present
        assertNull(LiveAlertNotificationHelper.getRsrp(CellMetrics()))
        assertNull(LiveAlertNotificationHelper.getRsrp(null))
    }

    @Test
    fun testCadenceIntervals() {
        assertEquals(1000L, MeasurementCadence.PRECISE.intervalMs)
        assertEquals(2000L, MeasurementCadence.STANDARD.intervalMs)
        assertEquals(5000L, MeasurementCadence.ECO.intervalMs)
    }

    @Test
    fun testGenerateSignalArtworkSafe() {
        val metrics = CellMetrics(ratType = RatType.NR_NSA)
        // Must safely handle environment without crashing
        LiveAlertNotificationHelper.generateSignalArtwork(
            metrics = metrics,
            tierColor = LiveAlertNotificationHelper.COLOR_EXCELLENT,
            speedKmh = 45.2f,
            sampleCount = 150
        )
    }

    @Test
    fun testLiveAlertStyles() {
        assertEquals("Navigation Live Updates", LiveAlertStyle.CAPSULE.label)
        assertEquals("Standard Notification", LiveAlertStyle.COMPACT.label)
    }
}
