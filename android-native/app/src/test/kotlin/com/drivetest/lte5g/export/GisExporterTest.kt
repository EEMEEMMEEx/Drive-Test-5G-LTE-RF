package com.drivetest.lte5g.export

import com.drivetest.lte5g.model.CellMetrics
import com.drivetest.lte5g.model.DriveTestSample
import com.drivetest.lte5g.model.DriveTestSession
import com.drivetest.lte5g.model.LteMetrics
import com.drivetest.lte5g.model.MeasurementCadence
import com.drivetest.lte5g.model.NrMetrics
import com.drivetest.lte5g.model.RatLockMode
import com.drivetest.lte5g.model.RatType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

class GisExporterTest {

    private fun createSample(id: Long, lat: Double, lon: Double, rsrp: Int, is5g: Boolean = false): DriveTestSample {
        val metrics = if (is5g) {
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
                    ssSinr = 15.0,
                    csiRsrp = rsrp - 2,
                    csiRsrq = -12,
                    csiSinr = 14.5,
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
                    rsrq = -12,
                    rssnr = 12.0,
                    cqi = 10
                )
            )
        }

        return DriveTestSample(
            id = id,
            sessionId = 99L,
            timestamp = 1700000000000L + (id * 1000L),
            latitude = lat,
            longitude = lon,
            altitude = 20.0,
            speedKmh = 35.5f,
            bearing = 90.0f,
            accuracyMeters = 2.5f,
            metrics = metrics,
            ratLockMode = if (is5g) RatLockMode.NR_ONLY else RatLockMode.LTE_ONLY,
            cadence = MeasurementCadence.PRECISE
        )
    }

    @Test
    fun exportGeoJson_createsValidFeatureCollectionWithTrackAndPoints() {
        val s1 = createSample(1, 13.7563, 100.5018, -80, is5g = true)
        val s2 = createSample(2, 13.7570, 100.5025, -95, is5g = false)
        val session = DriveTestSession(
            id = 99L,
            name = "Test Route Phaya Thai",
            startTime = 1700000000000L,
            endTime = 1700000010000L,
            samples = listOf(s1, s2),
            ratLockMode = RatLockMode.AUTO
        )

        val out = ByteArrayOutputStream()
        GisExporter.exportGeoJson(session, out)
        val jsonStr = out.toString(Charsets.UTF_8.name())
        val json = JSONObject(jsonStr)

        assertEquals("FeatureCollection", json.getString("type"))
        assertEquals("Test Route Phaya Thai", json.getString("name"))

        val features = json.getJSONArray("features")
        // 1 LineString + 2 Points = 3 features
        assertEquals(3, features.length())

        // Feature 0: LineString
        val track = features.getJSONObject(0)
        assertEquals("Feature", track.getString("type"))
        assertEquals("LineString", track.getJSONObject("geometry").getString("type"))
        assertEquals("RouteTrack", track.getJSONObject("properties").getString("feature_type"))
        assertEquals(2, track.getJSONObject("geometry").getJSONArray("coordinates").length())

        // Feature 1: Point 5G NR
        val pt1 = features.getJSONObject(1)
        assertEquals("Point", pt1.getJSONObject("geometry").getString("type"))
        val coords1 = pt1.getJSONObject("geometry").getJSONArray("coordinates")
        assertEquals(100.5018, coords1.getDouble(0), 0.0001)
        assertEquals(13.7563, coords1.getDouble(1), 0.0001)
        val props1 = pt1.getJSONObject("properties")
        assertEquals("NR_SA", props1.getString("rat"))
        assertEquals("AIS 5G", props1.getString("operator"))
        assertEquals(420, props1.getInt("pci"))
        assertEquals(-80, props1.getInt("rsrp_dbm"))

        // Feature 2: Point LTE
        val pt2 = features.getJSONObject(2)
        val props2 = pt2.getJSONObject("properties")
        assertEquals("LTE", props2.getString("rat"))
        assertEquals(-95, props2.getInt("rsrp_dbm"))
        assertEquals(10, props2.getInt("cqi"))
    }

    @Test
    fun exportMapInfoMifMid_createsValidZipWithMifAndMid() {
        val s1 = createSample(1, 13.7563, 100.5018, -82, is5g = true)
        val session = DriveTestSession(
            id = 99L,
            name = "Site Acceptance BKK",
            startTime = 1700000000000L,
            samples = listOf(s1)
        )

        val out = ByteArrayOutputStream()
        GisExporter.exportMapInfoMifMid(session, out)

        val zipBytes = out.toByteArray()
        assertTrue("Zip file should not be empty", zipBytes.isNotEmpty())

        val zipIn = ZipInputStream(ByteArrayInputStream(zipBytes))
        var foundMif = false
        var foundMid = false
        var mifContent = ""
        var midContent = ""

        var entry = zipIn.nextEntry
        while (entry != null) {
            val content = zipIn.bufferedReader(Charsets.UTF_8).readText()
            if (entry.name.endsWith(".mif")) {
                foundMif = true
                mifContent = content
            } else if (entry.name.endsWith(".mid")) {
                foundMid = true
                midContent = content
            }
            zipIn.closeEntry()
            entry = zipIn.nextEntry
        }

        assertTrue("MIF file must be present in ZIP", foundMif)
        assertTrue("MID file must be present in ZIP", foundMid)

        // Validate MIF Header & Syntax
        assertTrue(mifContent.contains("Version 300"))
        assertTrue(mifContent.contains("Charset \"WindowsLatin1\""))
        assertTrue(mifContent.contains("CoordSys Earth Projection 1, 104"))
        assertTrue(mifContent.contains("Columns 19"))
        assertTrue(mifContent.contains("Data"))
        assertTrue(mifContent.contains("Point 100.501800 13.756300"))
        assertTrue(mifContent.contains("Symbol ("))

        // Validate MID Content row
        assertTrue(midContent.contains("1,"))
        assertTrue(midContent.contains("\"NR_SA\""))
        assertTrue(midContent.contains("-82"))
    }

    @Test
    fun exportGeoJson_singleSim_omitsSim2FieldsGracefully() {
        val s1 = createSample(1, 13.7563, 100.5018, -80, is5g = true)
        val session = DriveTestSession(
            id = 99L,
            name = "Single SIM Route",
            startTime = 1700000000000L,
            samples = listOf(s1)
        )

        val out = ByteArrayOutputStream()
        GisExporter.exportGeoJson(session, out)
        val json = JSONObject(out.toString(Charsets.UTF_8.name()))
        val props = json.getJSONArray("features").getJSONObject(0).getJSONObject("properties")

        assertEquals("NR_SA", props.getString("rat"))
        assertEquals(-80, props.getInt("rsrp_dbm"))
        assertEquals(1L, props.getLong("cadence_s"))
        assertFalse(props.has("sim2_active"))
        assertFalse(props.has("sim2_rat"))
        assertFalse(props.has("sim2_rsrp_dbm"))
        assertFalse(props.has("sim2_rsrq_db"))
    }

    @Test
    fun exportGeoJson_dualSim_exportsCompleteSim1AndSim2Fields() {
        val sim2Metrics = CellMetrics(
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
        val s1 = createSample(1, 13.7563, 100.5018, -80, is5g = true).copy(
            simDisplayName = "AIS-SIM1",
            metricsSim2 = sim2Metrics
        )
        val s2 = createSample(2, 13.7570, 100.5025, -86, is5g = true).copy(
            simDisplayName = "AIS-SIM1",
            metricsSim2 = sim2Metrics.copy(lte = sim2Metrics.lte?.copy(rsrp = -92))
        )
        val session = DriveTestSession(
            id = 101L,
            name = "Dual SIM Route",
            startTime = 1700000000000L,
            samples = listOf(s1, s2),
            isDualSimLogging = true,
            avgRsrp = -83,
            avgSinr = 15.0,
            avgRsrpSim2 = -90,
            avgSinrSim2 = 8.5
        )

        val out = ByteArrayOutputStream()
        GisExporter.exportGeoJson(session, out)
        val json = JSONObject(out.toString(Charsets.UTF_8.name()))
        val features = json.getJSONArray("features")
        assertEquals(3, features.length())

        // RouteTrack includes SIM 1 + SIM 2 averages
        val trackProps = features.getJSONObject(0).getJSONObject("properties")
        assertEquals(-83, trackProps.getInt("avg_rsrp_dbm"))
        assertEquals(-90, trackProps.getInt("avg_rsrp_sim2_dbm"))
        assertEquals(8.5, trackProps.getDouble("avg_sinr_sim2_db"), 0.01)

        // Point 1 includes full SIM 1 + SIM 2 RF fields
        val ptProps = features.getJSONObject(1).getJSONObject("properties")
        assertEquals("AIS-SIM1", ptProps.getString("sim_name"))
        assertEquals("NR_SA", ptProps.getString("rat"))
        assertEquals(-80, ptProps.getInt("rsrp_dbm"))
        assertEquals(-11, ptProps.getInt("rsrq_db"))
        assertEquals(15.0, ptProps.getDouble("sinr_db"), 0.01)

        assertTrue(ptProps.getBoolean("sim2_active"))
        assertEquals("TRUE-SIM2", ptProps.getString("sim2_name"))
        assertEquals("LTE", ptProps.getString("sim2_rat"))
        assertEquals("TRUE-H", ptProps.getString("sim2_operator"))
        assertEquals("520-03", ptProps.getString("sim2_plmn"))
        assertEquals(202, ptProps.getInt("sim2_pci"))
        assertEquals(2002, ptProps.getInt("sim2_tac"))
        assertEquals(456789L, ptProps.getLong("sim2_cell_id"))
        assertEquals(1850, ptProps.getInt("sim2_earfcn"))
        assertEquals("B3", ptProps.getString("sim2_band"))
        assertEquals(-88, ptProps.getInt("sim2_rsrp_dbm"))
        assertEquals(-12, ptProps.getInt("sim2_rsrq_db"))
        assertEquals(8.5, ptProps.getDouble("sim2_sinr_db"), 0.01)
        assertEquals(10, ptProps.getInt("sim2_cqi"))
    }
}
