package com.drivetest.lte5g.export

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
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

class KmlExporterTest {

    @Test
    fun exportKmz_createsValidZipWithDocKml() {
        val sample1 = DriveTestSample(
            id = 1,
            sessionId = 100,
            timestamp = 1700000000000L,
            latitude = 13.7563,
            longitude = 100.5018,
            altitude = 15.0,
            speedKmh = 42.0f,
            bearing = 120.0f,
            accuracyMeters = 3.2f,
            metrics = CellMetrics(
                ratType = RatType.LTE,
                plmn = "520-01",
                operatorAlpha = "AIS",
                lte = LteMetrics(
                    pci = 142,
                    eci = 28491024L,
                    tac = 45210,
                    earfcn = 1850,
                    band = 3,
                    rsrp = -82,
                    rsrq = -10,
                    rssnr = 18.5,
                    cqi = 12
                )
            ),
            ratLockMode = RatLockMode.LTE_ONLY,
            cadence = MeasurementCadence.PRECISE
        )

        val sample2 = sample1.copy(
            id = 2,
            timestamp = 1700000001000L,
            latitude = 13.7570,
            longitude = 100.5025,
            metrics = sample1.metrics.copy(
                lte = sample1.metrics.lte?.copy(rsrp = -88)
            )
        )

        val out = ByteArrayOutputStream()
        KmlExporter.exportKmz(listOf(sample1, sample2), out)

        val bytes = out.toByteArray()
        assertTrue("KMZ output must not be empty", bytes.isNotEmpty())

        // Validate ZIP structure
        val zipIn = ZipInputStream(ByteArrayInputStream(bytes))
        val entry = zipIn.nextEntry
        assertNotNull("KMZ must contain an entry", entry)
        assertTrue("Entry must be doc.kml", entry?.name == "doc.kml")

        val kmlContent = zipIn.bufferedReader(Charsets.UTF_8).readText()
        assertTrue("Must have KML root namespace", kmlContent.contains("<kml xmlns=\"http://www.opengis.net/kml/2.2\">"))
        assertTrue("Must include route coordinate", kmlContent.contains("100.5018,13.7563,15.0"))
        assertTrue("Must include Cadence_s", kmlContent.contains("<Data name=\"Cadence_s\"><value>1</value></Data>"))
        assertTrue("Must include RSRP data", kmlContent.contains("<Data name=\"RSRP_dBm\"><value>-82</value></Data>"))
        assertTrue("Must contain circle icon for points", kmlContent.contains("placemark_circle.png"))
        assertTrue("Must contain point_tier1 style", kmlContent.contains("<styleUrl>#point_tier1</styleUrl>"))
        assertTrue("Must contain line_tier1 style", kmlContent.contains("<styleUrl>#line_tier1</styleUrl>"))
        assertTrue("Must contain start marker", kmlContent.contains("🚩 START"))
        assertTrue("Must contain end marker", kmlContent.contains("🏁 END"))

        zipIn.closeEntry()
        zipIn.close()
    }


    @Test

    fun exportCsv_withDualSim_exportsBothSimColumns() {

        val sample = DriveTestSample(

            id = 1,

            sessionId = 100,

            timestamp = 1700000000000L,

            latitude = 13.7563,

            longitude = 100.5018,

            altitude = 15.0,

            speedKmh = 42.0f,

            bearing = 120.0f,

            accuracyMeters = 3.2f,

            metrics = CellMetrics(

                ratType = RatType.LTE,

                plmn = "520-01",

                operatorAlpha = "AIS",

                simDisplayName = "SIM 1: AIS",

                lte = LteMetrics(

                    pci = 142,

                    eci = 28491024L,

                    tac = 45210,

                    earfcn = 1850,

                    band = 3,

                    rsrp = -82,

                    rsrq = -10,

                    rssnr = 18.5,

                    cqi = 12

                )

            ),

            metricsSim2 = CellMetrics(

                ratType = RatType.NR_NSA,

                plmn = "520-04",

                operatorAlpha = "TRUE",

                simDisplayName = "SIM 2: TRUE",

                nr = com.drivetest.lte5g.model.NrMetrics(

                    pci = 220,

                    nci = 9876543L,

                    tac = 500,

                    nrArfcn = 630000,

                    band = 78,

                    ssRsrp = -90,

                    ssRsrq = -11,

                    ssSinr = 14.0,

                    csiRsrp = -92,

                    csiRsrq = -12,

                    csiSinr = 13.0,

                    isNsa = true

                )

            ),

            ratLockMode = RatLockMode.AUTO,

            cadence = MeasurementCadence.PRECISE

        )



        val out = ByteArrayOutputStream()

        KmlExporter.exportCsv(listOf(sample), out)

        val csv = out.toString(Charsets.UTF_8.name())



        assertTrue("CSV header must contain SIM1 columns", csv.contains("SIM1_Name,SIM1_RAT,SIM1_Operator,SIM1_PCI"))

        assertTrue("CSV header must contain SIM2 columns", csv.contains("SIM2_Name,SIM2_RAT,SIM2_Operator,SIM2_PCI"))

        assertTrue("CSV must contain AIS metrics", csv.contains("SIM 1: AIS,LTE,AIS,142,1850,3,-82"))

        assertTrue("CSV must contain TRUE metrics", csv.contains("SIM 2: TRUE,5G_NSA,TRUE,220,630000,78,-90"))

    }

    @Test
    fun exportKmz_dualSim_isolatesSimLayersWithDistinctStyles() {
        val base = DriveTestSample(
            id = 1,
            sessionId = 100,
            timestamp = 1700000000000L,
            latitude = 13.7563,
            longitude = 100.5018,
            altitude = 15.0,
            speedKmh = 42.0f,
            bearing = 120.0f,
            accuracyMeters = 3.2f,
            metrics = CellMetrics(
                ratType = RatType.LTE,
                plmn = "520-01",
                operatorAlpha = "AIS",
                simDisplayName = "SIM 1: AIS",
                lte = LteMetrics(
                    pci = 142, eci = 28491024L, tac = 45210, earfcn = 1850, band = 3,
                    rsrp = -82, rsrq = -10, rssnr = 18.5, cqi = 12
                )
            ),
            metricsSim2 = CellMetrics(
                ratType = RatType.NR_NSA,
                plmn = "520-04",
                operatorAlpha = "TRUE",
                simDisplayName = "SIM 2: TRUE",
                nr = com.drivetest.lte5g.model.NrMetrics(
                    pci = 220, nci = 9876543L, tac = 500, nrArfcn = 630000, band = 78,
                    ssRsrp = -90, ssRsrq = -11, ssSinr = 14.0,
                    csiRsrp = -92, csiRsrq = -12, csiSinr = 13.0, isNsa = true
                )
            ),
            ratLockMode = RatLockMode.AUTO,
            cadence = MeasurementCadence.PRECISE
        )
        val sample2 = base.copy(
            id = 2,
            timestamp = 1700000001000L,
            latitude = 13.7570,
            longitude = 100.5025
        )

        val out = ByteArrayOutputStream()
        KmlExporter.exportKmz(listOf(base, sample2), out)

        val zipIn = ZipInputStream(ByteArrayInputStream(out.toByteArray()))
        val entry = zipIn.nextEntry
        assertNotNull("KMZ must contain an entry", entry)
        val kml = zipIn.bufferedReader(Charsets.UTF_8).readText()
        zipIn.closeEntry()
        zipIn.close()

        // Distinct top-level folders per SIM, labelled with the operator name
        assertTrue("Must isolate SIM 1 folder", kml.contains("<name>SIM 1 — AIS</name>"))
        assertTrue("Must isolate SIM 2 folder", kml.contains("<name>SIM 2 — TRUE</name>"))
        assertTrue("SIM 1 folder must nest a route track layer", kml.contains("<name>SIM 1 — AIS · Route Track</name>"))
        assertTrue("SIM 2 folder must nest a survey points layer", kml.contains("<name>SIM 2 — TRUE · Survey Points</name>"))

        // Dedicated SIM 2 style namespace so layers cannot be confused
        assertTrue("Must declare SIM 2 line styles", kml.contains("<Style id=\"line_s2_tier3\">"))
        assertTrue("Must declare SIM 2 point styles", kml.contains("<Style id=\"point_s2_tier3\">"))
        assertTrue("SIM 1 must keep legacy style ids", kml.contains("<Style id=\"line_tier1\">"))

        // SIM 2 line stroke must be wider than SIM 1 so the tracks stay separable
        assertTrue("SIM 2 line must use a wider halo stroke", kml.contains("<Style id=\"line_s2_tier3\"><LineStyle><color>fffa8ba7</color><width>14</width></LineStyle></Style>"))
        assertTrue("SIM 1 line must keep the original stroke width", kml.contains("<Style id=\"line_tier1\"><LineStyle><color>ff81b910</color><width>6</width></LineStyle></Style>"))

        // Both SIMs must emit their own RSRP values and placemarks
        assertTrue("SIM 1 placemark must carry operator label", kml.contains("SIM 1 · LTE PCI:142 (-82dBm)"))
        assertTrue("SIM 2 placemark must carry operator label", kml.contains("SIM 2 · 5G NSA PCI:220 (-90dBm)"))
        assertTrue("SIM 2 styleUrl must reference the s2 namespace", kml.contains("<styleUrl>#point_s2_tier2</styleUrl>"))
        assertTrue("SIM 1 styleUrl must reference the legacy namespace", kml.contains("<styleUrl>#point_tier1</styleUrl>"))

        // SIM identity must travel in ExtendedData for GIS filtering
        assertTrue("Must tag SIM slot 2", kml.contains("<Data name=\"SIM_Slot\"><value>SIM 2</value></Data>"))
        assertTrue("Must tag SIM name 2", kml.contains("<Data name=\"SIM_Name\"><value>SIM 2: TRUE</value></Data>"))

        // Trip markers stay GPS-level and must appear exactly once
        assertEquals("Trip markers must not be duplicated per SIM", 1, Regex("🚩 START").findAll(kml).count())
        assertEquals("End marker must not be duplicated per SIM", 1, Regex("🏁 END").findAll(kml).count())
    }

    @Test
    fun exportKmz_singleSim_omitsSim2Layers() {
        val sample = DriveTestSample(
            id = 1,
            sessionId = 100,
            timestamp = 1700000000000L,
            latitude = 13.7563,
            longitude = 100.5018,
            altitude = 15.0,
            speedKmh = 42.0f,
            bearing = 120.0f,
            accuracyMeters = 3.2f,
            metrics = CellMetrics(
                ratType = RatType.LTE,
                operatorAlpha = "AIS",
                lte = LteMetrics(
                    pci = 142, eci = 28491024L, tac = 45210, earfcn = 1850, band = 3,
                    rsrp = -82, rsrq = -10, rssnr = 18.5, cqi = 12
                )
            ),
            cadence = MeasurementCadence.PRECISE
        )

        val out = ByteArrayOutputStream()
        KmlExporter.exportKmz(listOf(sample), out)

        val zipIn = ZipInputStream(ByteArrayInputStream(out.toByteArray()))
        zipIn.nextEntry
        val kml = zipIn.bufferedReader(Charsets.UTF_8).readText()
        zipIn.closeEntry()
        zipIn.close()

        assertTrue("Single-SIM export must still emit SIM 1 layer", kml.contains("<name>SIM 1 — AIS</name>"))
        assertFalse("Single-SIM export must not declare SIM 2 styles", kml.contains("line_s2_tier"))
        assertFalse("Single-SIM export must not emit SIM 2 folder", kml.contains("<name>SIM 2"))
    }

    @Test
    fun metricsFor_andStyleIds_routeToCorrectSimNamespace() {
        val sample = DriveTestSample(
            latitude = 1.0, longitude = 2.0, altitude = 3.0,
            speedKmh = 0f, bearing = 0f, accuracyMeters = 1f,
            metrics = CellMetrics(
                lte = LteMetrics(
                    pci = 142, eci = 28491024L, tac = 45210, earfcn = 1850, band = 3,
                    rsrp = -82, rsrq = -10, rssnr = 18.5, cqi = 12
                )
            ),
            metricsSim2 = CellMetrics(
                lte = LteMetrics(
                    pci = 220, eci = 9876543L, tac = 500, earfcn = 630000, band = 78,
                    rsrp = -110, rsrq = -12, rssnr = 5.0, cqi = 7
                )
            )
        )

        assertEquals(-82, KmlExporter.rsrpOf(KmlExporter.metricsFor(sample, 1)))
        assertEquals(-110, KmlExporter.rsrpOf(KmlExporter.metricsFor(sample, 2)))
        assertNull("SIM 2 metrics must be null when absent", KmlExporter.metricsFor(sample.copy(metricsSim2 = null), 2))

        assertEquals("line_tier1", KmlExporter.lineStyleId(1, 1))
        assertEquals("line_s2_tier5", KmlExporter.lineStyleId(2, 5))
        assertEquals("point_tier3", KmlExporter.pointStyleId(1, 3))
        assertEquals("point_s2_tier3", KmlExporter.pointStyleId(2, 3))

        // Tier boundaries must stay identical for both SIMs
        assertEquals(1, KmlExporter.tierIndex(-85))
        assertEquals(5, KmlExporter.tierIndex(-116))
    }
}
