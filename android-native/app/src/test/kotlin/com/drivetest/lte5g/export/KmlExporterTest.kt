package com.drivetest.lte5g.export

import com.drivetest.lte5g.model.CellMetrics
import com.drivetest.lte5g.model.DriveTestSample
import com.drivetest.lte5g.model.LteMetrics
import com.drivetest.lte5g.model.MeasurementCadence
import com.drivetest.lte5g.model.RatLockMode
import com.drivetest.lte5g.model.RatType
import org.junit.Assert.assertNotNull
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
}
