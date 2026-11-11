package com.drivetest.lte5g.export

import com.drivetest.lte5g.data.analytics.RfAnalyticsEngine
import com.drivetest.lte5g.model.DriveTestSample
import com.drivetest.lte5g.model.DriveTestSession
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object GisExporter {

    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val localFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    /**
     * Exports the session as an RFC 7946 compliant GeoJSON FeatureCollection.
     */
    fun exportGeoJson(session: DriveTestSession, outputStream: OutputStream) {
        val root = JSONObject()
        root.put("type", "FeatureCollection")
        root.put("name", session.name)

        val features = JSONArray()

        // 1. LineString feature representing continuous route track
        val samples = session.samples
        if (samples.size >= 2) {
            val trackFeature = JSONObject()
            trackFeature.put("type", "Feature")

            val lineGeom = JSONObject()
            lineGeom.put("type", "LineString")
            val lineCoords = JSONArray()
            samples.forEach { s ->
                val pt = JSONArray()
                pt.put(s.longitude)
                pt.put(s.latitude)
                pt.put(s.altitude)
                lineCoords.put(pt)
            }
            lineGeom.put("coordinates", lineCoords)
            trackFeature.put("geometry", lineGeom)

            val trackProps = JSONObject()
            trackProps.put("feature_type", "RouteTrack")
            trackProps.put("session_id", session.id)
            trackProps.put("session_name", session.name)
            trackProps.put("sample_count", samples.size)
            trackProps.put("distance_meters", RfAnalyticsEngine.calculateTotalDistance(samples))
            session.avgRsrp?.let { trackProps.put("avg_rsrp_dbm", it) }
            session.avgSinr?.let { trackProps.put("avg_sinr_db", it) }
            trackFeature.put("properties", trackProps)

            features.put(trackFeature)
        }

        // 2. Point features for each survey sample
        samples.forEach { s ->
            val pointFeature = JSONObject()
            pointFeature.put("type", "Feature")

            val ptGeom = JSONObject()
            ptGeom.put("type", "Point")
            val ptCoords = JSONArray()
            ptCoords.put(s.longitude)
            ptCoords.put(s.latitude)
            ptCoords.put(s.altitude)
            ptGeom.put("coordinates", ptCoords)
            pointFeature.put("geometry", ptGeom)

            val m = s.metrics
            val pci = m.nr?.pci ?: m.lte?.pci
            val tac = m.nr?.tac ?: m.lte?.tac
            val cellId = m.nr?.nci ?: m.lte?.eci
            val earfcn = m.nr?.nrArfcn ?: m.lte?.earfcn
            val band = m.nr?.band?.let { "n$it" } ?: m.lte?.band?.let { "B$it" }
            val rsrp = m.nr?.ssRsrp ?: m.lte?.rsrp
            val rsrq = m.nr?.ssRsrq ?: m.lte?.rsrq
            val sinr = m.nr?.ssSinr ?: m.lte?.rssnr

            val props = JSONObject()
            props.put("sample_id", s.id)
            props.put("session_id", session.id)
            props.put("timestamp", s.timestamp)
            props.put("datetime_iso", isoFormat.format(Date(s.timestamp)))
            props.put("datetime_local", localFormat.format(Date(s.timestamp)))
            props.put("speed_kmh", s.speedKmh)
            props.put("bearing", s.bearing)
            props.put("accuracy_m", s.accuracyMeters)
            props.put("rat", m.ratType.name)
            props.put("operator", m.operatorAlpha ?: "")
            props.put("plmn", m.plmn ?: "")
            pci?.let { props.put("pci", it) }
            tac?.let { props.put("tac", it) }
            cellId?.let { props.put("cell_id", it) }
            earfcn?.let { props.put("earfcn", it) }
            band?.let { props.put("band", it) }
            rsrp?.let { props.put("rsrp_dbm", it) }
            rsrq?.let { props.put("rsrq_db", it) }
            sinr?.let { props.put("sinr_db", it) }
            m.lte?.cqi?.let { props.put("cqi", it) }
            props.put("sim_slot", s.simSlot)

            // SIM 2 fields if present
            s.metricsSim2?.let { m2 ->
                props.put("sim2_active", true)
                props.put("sim2_rat", m2.ratType.name)
                props.put("sim2_operator", m2.operatorAlpha ?: "")
                val pci2 = m2.nr?.pci ?: m2.lte?.pci
                val rsrp2 = m2.nr?.ssRsrp ?: m2.lte?.rsrp
                val sinr2 = m2.nr?.ssSinr ?: m2.lte?.rssnr
                pci2?.let { props.put("sim2_pci", it) }
                rsrp2?.let { props.put("sim2_rsrp_dbm", it) }
                sinr2?.let { props.put("sim2_sinr_db", it) }
            }

            pointFeature.put("properties", props)
            features.put(pointFeature)
        }

        root.put("features", features)

        val writer = OutputStreamWriter(outputStream, Charsets.UTF_8)
        writer.write(root.toString(2))
        writer.flush()
    }

    /**
     * Exports MapInfo Interchange Format (MIF/MID) packaged inside a single ZIP archive.
     * Compatible with Forsk Atoll Drive Test Import Wizard, InfoVista Planet, and MapInfo Pro.
     */
    fun exportMapInfoMifMid(session: DriveTestSession, outputStream: OutputStream) {
        val cleanName = session.name.replace(Regex("[^a-zA-Z0-9_-]"), "_").ifBlank { "drivetest_${session.id}" }
        val zipOut = ZipOutputStream(BufferedOutputStream(outputStream))

        // 1. Write MIF Entry
        zipOut.putNextEntry(ZipEntry("$cleanName.mif"))
        val mifWriter = OutputStreamWriter(zipOut, Charsets.UTF_8)
        writeMifContent(session, mifWriter)
        mifWriter.flush()
        zipOut.closeEntry()

        // 2. Write MID Entry
        zipOut.putNextEntry(ZipEntry("$cleanName.mid"))
        val midWriter = OutputStreamWriter(zipOut, Charsets.UTF_8)
        writeMidContent(session, midWriter)
        midWriter.flush()
        zipOut.closeEntry()

        zipOut.finish()
        zipOut.flush()
    }

    private fun writeMifContent(session: DriveTestSession, writer: OutputStreamWriter) {
        val samples = session.samples
        writer.write(
            """
            Version 300
            Charset "WindowsLatin1"
            Delimiter ","
            CoordSys Earth Projection 1, 104
            Columns 19
              Sample_ID Integer
              Timestamp Decimal(14,0)
              DateTime Char(24)
              Speed_kmh Decimal(5,1)
              Bearing Decimal(5,1)
              Operator Char(30)
              RAT Char(12)
              PCI Integer
              ARFCN Integer
              Band Char(10)
              RSRP_dBm Integer
              RSRQ_dB Integer
              SINR_dB Decimal(5,1)
              CQI Integer
              TAC Integer
              Cell_ID Decimal(14,0)
              SIM2_RAT Char(12)
              SIM2_RSRP Integer
              SIM2_SINR Decimal(5,1)
            Data
            
            """.trimIndent()
        )

        for (s in samples) {
            val rsrp = s.metrics.nr?.ssRsrp ?: s.metrics.lte?.rsrp ?: -120
            val colorInt = getMapInfoColor(rsrp)
            writer.write("Point ${String.format(Locale.US, "%.6f", s.longitude)} ${String.format(Locale.US, "%.6f", s.latitude)}\n")
            writer.write("    Symbol (34, $colorInt, 6)\n")
        }
    }

    private fun writeMidContent(session: DriveTestSession, writer: OutputStreamWriter) {
        val samples = session.samples
        for (s in samples) {
            val m = s.metrics
            val dt = localFormat.format(Date(s.timestamp))
            val speed = String.format(Locale.US, "%.1f", s.speedKmh)
            val bearing = String.format(Locale.US, "%.1f", s.bearing)
            val operator = escapeMid(m.operatorAlpha ?: "")
            val rat = escapeMid(m.ratType.name)
            val pci = m.nr?.pci ?: m.lte?.pci ?: 0
            val arfcn = m.nr?.nrArfcn ?: m.lte?.earfcn ?: 0
            val band = escapeMid(m.nr?.band?.let { "n$it" } ?: m.lte?.band?.let { "B$it" } ?: "")
            val rsrp = m.nr?.ssRsrp ?: m.lte?.rsrp ?: -999
            val rsrq = m.nr?.ssRsrq ?: m.lte?.rsrq ?: -999
            val sinr = (m.nr?.ssSinr ?: m.lte?.rssnr)?.let { String.format(Locale.US, "%.1f", it) } ?: "0.0"
            val cqi = m.lte?.cqi ?: 0
            val tac = m.nr?.tac ?: m.lte?.tac ?: 0
            val cellId = m.nr?.nci ?: m.lte?.eci ?: 0

            val m2 = s.metricsSim2
            val rat2 = escapeMid(m2?.ratType?.name ?: "")
            val rsrp2 = m2?.nr?.ssRsrp ?: m2?.lte?.rsrp ?: -999
            val sinr2 = (m2?.nr?.ssSinr ?: m2?.lte?.rssnr)?.let { String.format(Locale.US, "%.1f", it) } ?: "0.0"

            writer.write("${s.id},${s.timestamp},\"$dt\",$speed,$bearing,\"$operator\",\"$rat\",$pci,$arfcn,\"$band\",$rsrp,$rsrq,$sinr,$cqi,$tac,$cellId,\"$rat2\",$rsrp2,$sinr2\n")
        }
    }

    private fun escapeMid(str: String): String {
        return str.replace("\"", "\"\"")
    }

    private fun getMapInfoColor(rsrp: Int): Int {
        // MapInfo color integer = R * 65536 + G * 256 + B
        return when {
            rsrp >= -85 -> (0x10 * 65536) + (0xB9 * 256) + 0x81 // Emerald Green
            rsrp >= -95 -> (0x22 * 65536) + (0xC5 * 256) + 0x5E // Light Green
            rsrp >= -105 -> (0xEA * 65536) + (0xB3 * 256) + 0x08 // Yellow / Amber
            rsrp >= -115 -> (0xF9 * 65536) + (0x73 * 256) + 0x16 // Orange
            else -> (0xEF * 65536) + (0x44 * 256) + 0x44         // Red
        }
    }
}
