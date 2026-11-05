package com.drivetest.lte5g.export

import com.drivetest.lte5g.model.CellMetrics
import com.drivetest.lte5g.model.DriveTestSample
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object KmlExporter {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
    private val displayFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    // SIM 1 keeps the historical signal-quality palette (green -> red)
    private val SIM1_KML_COLORS = listOf("ff81b910", "ff5ec522", "ff08b3ea", "ff1673f9", "ff4444ef")
    // SIM 2 uses a dedicated blue -> magenta ramp so the two layers never read alike
    private val SIM2_KML_COLORS = listOf("fff8bd38", "fff88c81", "fffa8ba7", "fff979e8", "ffb672f4")

    private const val SIM1_LINE_WIDTH = 6
    // Wider stroke drawn underneath SIM 1 so both tracks stay visible on the same GPS geometry
    private const val SIM2_LINE_WIDTH = 14

    fun exportKmz(samples: List<DriveTestSample>, outputStream: OutputStream) {
        val zipOut = ZipOutputStream(BufferedOutputStream(outputStream))
        val entry = ZipEntry("doc.kml")
        zipOut.putNextEntry(entry)
        exportKml(samples, zipOut)
        zipOut.closeEntry()
        zipOut.finish()
        zipOut.flush()
    }

    fun exportKml(samples: List<DriveTestSample>, outputStream: OutputStream) {
        val writer = outputStream.bufferedWriter(Charsets.UTF_8)
        val hasSim2 = samples.any { it.metricsSim2 != null }

        writer.write(kmlHeader(hasSim2))

        // GPS-level Start & End markers (shared coordinates, drawn once)
        writeTripMarkers(writer, samples)

        // SIM 2 layers are written first (wider stroke) so SIM 1 sits on top without hiding them
        if (hasSim2) {
            writeSimLayers(
                writer = writer,
                samples = samples,
                simIndex = 2,
                layerLabel = simLayerLabel(samples, 2)
            )
        }
        writeSimLayers(
            writer = writer,
            samples = samples,
            simIndex = 1,
            layerLabel = simLayerLabel(samples, 1)
        )

        writer.write("""
</Document>
</kml>""")
        writer.flush()
    }

    // ------------------------------------------------------------------
    // Document header & styles
    // ------------------------------------------------------------------

    private fun kmlHeader(hasSim2: Boolean): String = buildString {
        append(
            """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
<Document>
    <name>Drive Test Route</name>
    <description>Recorded via Android Drive Test LTE/5G${if (hasSim2) " · Dual-SIM Benchmark" else ""}</description>

    <!-- SIM 1 route styles (Signal Quality - aabbggrr format) -->
"""
        )
        SIM1_KML_COLORS.forEachIndexed { index, color ->
            append("\n    <Style id=\"line_tier${index + 1}\"><LineStyle><color>$color</color><width>$SIM1_LINE_WIDTH</width></LineStyle></Style>")
        }
        append("\n\n    <!-- SIM 1 survey point styles -->")
        SIM1_KML_COLORS.forEachIndexed { index, color ->
            append(pointStyleXml("point_tier${index + 1}", color))
        }

        if (hasSim2) {
            append("\n\n    <!-- SIM 2 route styles (dedicated palette, wider halo stroke) -->")
            SIM2_KML_COLORS.forEachIndexed { index, color ->
                append("\n    <Style id=\"line_s2_tier${index + 1}\"><LineStyle><color>$color</color><width>$SIM2_LINE_WIDTH</width></LineStyle></Style>")
            }
            append("\n\n    <!-- SIM 2 survey point styles -->")
            SIM2_KML_COLORS.forEachIndexed { index, color ->
                append(pointStyleXml("point_s2_tier${index + 1}", color))
            }
        }

        append(
            """

    <!-- Start & End Pin Markers -->
    <Style id="marker_start">
        <IconStyle>
            <scale>1.1</scale>
            <Icon><href>http://maps.google.com/mapfiles/kml/paddle/grn-circle.png</href></Icon>
        </IconStyle>
    </Style>
    <Style id="marker_end">
        <IconStyle>
            <scale>1.1</scale>
            <Icon><href>http://maps.google.com/mapfiles/kml/paddle/red-circle.png</href></Icon>
        </IconStyle>
    </Style>
"""
        )
    }

    private fun pointStyleXml(id: String, kmlColor: String): String = """

    <Style id="$id">
        <IconStyle>
            <color>$kmlColor</color>
            <scale>0.6</scale>
            <Icon><href>http://maps.google.com/mapfiles/kml/shapes/placemark_circle.png</href></Icon>
        </IconStyle>
        <LabelStyle><scale>0</scale></LabelStyle>
        <BalloonStyle><text><![CDATA[$[description]]]></text></BalloonStyle>
    </Style>"""

    // ------------------------------------------------------------------
    // Trip markers
    // ------------------------------------------------------------------

    private fun writeTripMarkers(writer: Writer, samples: List<DriveTestSample>) {
        if (samples.isEmpty()) return

        val first = samples.first()
        val startTimeStr = displayFormat.format(Date(first.timestamp))
        writer.write("""
    <Folder>
        <name>Trip Markers</name>
        <Placemark>
            <name>🚩 START: $startTimeStr</name>
            <styleUrl>#marker_start</styleUrl>
            <Point><coordinates>${first.longitude},${first.latitude},${first.altitude}</coordinates></Point>
        </Placemark>""")

        if (samples.size >= 2) {
            val last = samples.last()
            val endTimeStr = displayFormat.format(Date(last.timestamp))
            writer.write("""
        <Placemark>
            <name>🏁 END: $endTimeStr</name>
            <styleUrl>#marker_end</styleUrl>
            <Point><coordinates>${last.longitude},${last.latitude},${last.altitude}</coordinates></Point>
        </Placemark>""")
        }

        writer.write("""
    </Folder>
""")
    }

    // ------------------------------------------------------------------
    // Per-SIM layers
    // ------------------------------------------------------------------

    private fun writeSimLayers(
        writer: Writer,
        samples: List<DriveTestSample>,
        simIndex: Int,
        layerLabel: String
    ) {
        writer.write("""
    <Folder>
        <name>$layerLabel</name>
        <description>SIM $simIndex RF layer — toggle this folder in Google Earth to isolate or overlay the tracks.</description>
""")

        // Route Track (Signal Polylines)
        writer.write("""
        <Folder>
            <name>$layerLabel · Route Track</name>
""")
        writeRouteSegments(writer, samples, simIndex)
        writer.write("""
        </Folder>
""")

        // Survey Points (RSRP Dots)
        writer.write("""
        <Folder>
            <name>$layerLabel · Survey Points</name>
""")
        writeSurveyPoints(writer, samples, simIndex)
        writer.write("""
        </Folder>
    </Folder>
""")
    }

    private fun writeRouteSegments(writer: Writer, samples: List<DriveTestSample>, simIndex: Int) {
        for (i in 0 until samples.size - 1) {
            val p1 = samples[i]
            val p2 = samples[i + 1]
            val m1 = metricsFor(p1, simIndex) ?: continue
            val m2 = metricsFor(p2, simIndex) ?: continue
            val rsrp = rsrpOf(m1) ?: rsrpOf(m2) ?: -120
            val tier = tierIndex(rsrp)

            writer.write("""
        <Placemark>
            <name>SIM $simIndex Segment #$i (${rsrp} dBm)</name>
            <styleUrl>#${lineStyleId(simIndex, tier)}</styleUrl>
            <LineString>
                <altitudeMode>relativeToGround</altitudeMode>
                <coordinates>
                    ${p1.longitude},${p1.latitude},${p1.altitude}
                    ${p2.longitude},${p2.latitude},${p2.altitude}
                </coordinates>
            </LineString>
        </Placemark>""")
        }
    }

    private fun writeSurveyPoints(writer: Writer, samples: List<DriveTestSample>, simIndex: Int) {
        for (sample in samples) {
            val metrics = metricsFor(sample, simIndex) ?: continue
            val isNr = metrics.nr != null
            val rat = when {
                isNr -> if (metrics.nr.isNsa) "5G NSA" else "5G SA"
                metrics.lte != null -> "LTE"
                else -> "Unknown"
            }
            val operatorName = metrics.operatorAlpha ?: ""
            val simName = metrics.simDisplayName ?: sample.simDisplayName ?: "SIM $simIndex"
            val pci = metrics.nr?.pci ?: metrics.lte?.pci ?: 0
            val arfcn = metrics.nr?.nrArfcn ?: metrics.lte?.earfcn ?: 0
            val band = metrics.nr?.band ?: metrics.lte?.band ?: 0
            val rsrp = metrics.nr?.ssRsrp ?: metrics.lte?.rsrp ?: -140
            val rsrq = metrics.nr?.ssRsrq ?: metrics.lte?.rsrq ?: -30
            val sinr = metrics.nr?.ssSinr ?: metrics.lte?.rssnr ?: -20.0
            val timeUtc = dateFormat.format(Date(sample.timestamp))
            val timeDisplay = displayFormat.format(Date(sample.timestamp))
            val tier = tierIndex(rsrp)
            val rsrpColorHex = colorHexFor(simIndex, rsrp)

            // Single-SIM balloons stay as before; dual-SIM balloons compare the other slot inline
            val counterpartRow = if (simIndex == 1) sim2ComparisonRow(sample) else ""

            writer.write("""
        <Placemark>
            <name>SIM $simIndex · $rat PCI:$pci (${rsrp}dBm)</name>
            <styleUrl>#${pointStyleId(simIndex, tier)}</styleUrl>
            <description><![CDATA[
                <div style="font-family:Arial,sans-serif; min-width:240px; color:#0f172a;">
                    <div style="background-color:${if (simIndex == 2) "#312e81" else "#1e293b"}; color:white; padding:8px 12px; border-radius:6px 6px 0 0;">
                        <h4 style="margin:0; font-size:14px;"><b>$simName — $rat RF Signal Metrics</b></h4>
                        <span style="font-size:11px; color:#94a3b8;">$timeDisplay${if (operatorName.isNotBlank()) " · $operatorName" else ""}</span>
                    </div>
                    <table border="1" cellpadding="6" cellspacing="0" style="border-collapse:collapse; width:100%; font-size:12px; border-color:#e2e8f0;">
                        <tr bgcolor="#f8fafc"><td><b>RSRP (Signal)</b></td><td><b style="color:$rsrpColorHex; font-size:13px;">$rsrp dBm</b></td></tr>
                        <tr><td><b>RSRQ (Quality)</b></td><td>$rsrq dB</td></tr>
                        <tr bgcolor="#f8fafc"><td><b>SINR (Noise Ratio)</b></td><td>$sinr dB</td></tr>
                        <tr><td><b>Band / ARFCN</b></td><td>${if (band > 0) "B$band" else "N/A"} / $arfcn</td></tr>
                        <tr bgcolor="#f8fafc"><td><b>Serving Cell PCI</b></td><td>$pci</td></tr>
                        $counterpartRow
                        <tr><td><b>Vehicle Speed</b></td><td>${String.format(Locale.US, "%.1f", sample.speedKmh)} km/h</td></tr>
                        <tr bgcolor="#f8fafc"><td><b>GPS Accuracy</b></td><td>±${String.format(Locale.US, "%.1f", sample.accuracyMeters)} m</td></tr>
                        <tr><td><b>Measurement Cadence</b></td><td>${sample.cadence.label}</td></tr>
                    </table>
                </div>
            ]]></description>
            <TimeStamp><when>$timeUtc</when></TimeStamp>
            <Point>
                <coordinates>${sample.longitude},${sample.latitude},${sample.altitude}</coordinates>
            </Point>
            <ExtendedData>
                <Data name="SIM_Slot"><value>SIM $simIndex</value></Data>
                <Data name="SIM_Name"><value>$simName</value></Data>
                <Data name="RAT"><value>$rat</value></Data>
                <Data name="PCI"><value>$pci</value></Data>
                <Data name="ARFCN"><value>$arfcn</value></Data>
                <Data name="Band"><value>$band</value></Data>
                <Data name="RSRP_dBm"><value>$rsrp</value></Data>
                <Data name="RSRQ_dB"><value>$rsrq</value></Data>
                <Data name="SINR_dB"><value>$sinr</value></Data>
                <Data name="Speed_kmh"><value>${sample.speedKmh}</value></Data>
                <Data name="Accuracy_m"><value>${sample.accuracyMeters}</value></Data>
                <Data name="Cadence_s"><value>${sample.cadence.intervalMs / 1000}</value></Data>
            </ExtendedData>
        </Placemark>""")
        }
    }

    private fun sim2ComparisonRow(sample: DriveTestSample): String {
        val m2 = sample.metricsSim2 ?: return ""
        val isNr2 = m2.nr != null
        val rat2 = when {
            isNr2 -> if (m2.nr.isNsa) "5G NSA" else "5G SA"
            m2.lte != null -> "LTE"
            else -> "Unknown"
        }
        val pci2 = m2.nr?.pci ?: m2.lte?.pci
        val rsrp2 = m2.nr?.ssRsrp ?: m2.lte?.rsrp
        val op2 = m2.operatorAlpha ?: ""
        return """<tr bgcolor="#eef2ff"><td><b>SIM 2 Benchmark</b></td><td>$rat2 (${rsrp2 ?: "--"} dBm, $op2, PCI ${pci2 ?: "--"})</td></tr>"""
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Resolves the RF metrics for slot 1 or slot 2 of a sample. */
    fun metricsFor(sample: DriveTestSample, simIndex: Int): CellMetrics? =
        if (simIndex == 2) sample.metricsSim2 else sample.metrics

    fun rsrpOf(metrics: CellMetrics?): Int? =
        metrics?.nr?.ssRsrp ?: metrics?.lte?.rsrp

    fun tierIndex(rsrp: Int): Int = when {
        rsrp >= -85 -> 1
        rsrp >= -95 -> 2
        rsrp >= -105 -> 3
        rsrp >= -115 -> 4
        else -> 5
    }

    fun lineStyleId(simIndex: Int, tier: Int): String =
        if (simIndex == 2) "line_s2_tier$tier" else "line_tier$tier"

    fun pointStyleId(simIndex: Int, tier: Int): String =
        if (simIndex == 2) "point_s2_tier$tier" else "point_tier$tier"

    fun colorHexFor(simIndex: Int, rsrp: Int): String =
        if (simIndex == 2) getSim2ColorHexForRsrp(rsrp) else getColorHexForRsrp(rsrp)

    fun simLayerLabel(samples: List<DriveTestSample>, simIndex: Int): String {
        val metrics = samples.asSequence()
            .mapNotNull { metricsFor(it, simIndex) }
            .firstOrNull { it.operatorAlpha?.isNotBlank() == true || it.simDisplayName?.isNotBlank() == true }
        val name = metrics?.operatorAlpha?.takeIf { it.isNotBlank() }
            ?: metrics?.simDisplayName?.takeIf { it.isNotBlank() }
        return if (name != null) "SIM $simIndex — $name" else "SIM $simIndex"
    }

    fun exportCsv(samples: List<DriveTestSample>, outputStream: OutputStream) {
        val writer = outputStream.bufferedWriter(Charsets.UTF_8)
        val hasSim2 = samples.any { it.metricsSim2 != null }

        if (hasSim2) {
            writer.write("Timestamp,DateTime,Latitude,Longitude,Altitude_m,Speed_kmh,Bearing,Accuracy_m,Cadence_s,SIM1_Name,SIM1_RAT,SIM1_Operator,SIM1_PCI,SIM1_ARFCN,SIM1_Band,SIM1_RSRP_dBm,SIM1_RSRQ_dB,SIM1_SINR_dB,SIM1_CQI,SIM1_TAC,SIM1_CellID,SIM2_Name,SIM2_RAT,SIM2_Operator,SIM2_PCI,SIM2_ARFCN,SIM2_Band,SIM2_RSRP_dBm,SIM2_RSRQ_dB,SIM2_SINR_dB,SIM2_CQI,SIM2_TAC,SIM2_CellID\n")
            for (s in samples) {
                val dt = displayFormat.format(Date(s.timestamp))
                val cadenceSec = s.cadence.intervalMs / 1000

                // SIM 1
                val m1 = s.metrics
                val isNr1 = m1.nr != null
                val rat1 = if (isNr1) (if (m1.nr.isNsa) "5G_NSA" else "5G_SA") else if (m1.lte != null) "LTE" else "Unknown"
                val name1 = m1.simDisplayName ?: s.simDisplayName ?: "SIM 1"
                val op1 = m1.operatorAlpha ?: ""
                val pci1 = m1.nr?.pci ?: m1.lte?.pci ?: ""
                val arfcn1 = m1.nr?.nrArfcn ?: m1.lte?.earfcn ?: ""
                val band1 = m1.nr?.band ?: m1.lte?.band ?: ""
                val rsrp1 = m1.nr?.ssRsrp ?: m1.lte?.rsrp ?: ""
                val rsrq1 = m1.nr?.ssRsrq ?: m1.lte?.rsrq ?: ""
                val sinr1 = m1.nr?.ssSinr ?: m1.lte?.rssnr ?: ""
                val cqi1 = m1.lte?.cqi ?: ""
                val tac1 = m1.nr?.tac ?: m1.lte?.tac ?: ""
                val cellId1 = m1.nr?.nci ?: m1.lte?.eci ?: ""

                // SIM 2
                val m2 = s.metricsSim2
                val isNr2 = m2?.nr != null
                val rat2 = if (m2 == null) "" else if (isNr2) (if (m2.nr!!.isNsa) "5G_NSA" else "5G_SA") else if (m2.lte != null) "LTE" else "Unknown"
                val name2 = m2?.simDisplayName ?: "SIM 2"
                val op2 = m2?.operatorAlpha ?: ""
                val pci2 = m2?.nr?.pci ?: m2?.lte?.pci ?: ""
                val arfcn2 = m2?.nr?.nrArfcn ?: m2?.lte?.earfcn ?: ""
                val band2 = m2?.nr?.band ?: m2?.lte?.band ?: ""
                val rsrp2 = m2?.nr?.ssRsrp ?: m2?.lte?.rsrp ?: ""
                val rsrq2 = m2?.nr?.ssRsrq ?: m2?.lte?.rsrq ?: ""
                val sinr2 = m2?.nr?.ssSinr ?: m2?.lte?.rssnr ?: ""
                val cqi2 = m2?.lte?.cqi ?: ""
                val tac2 = m2?.nr?.tac ?: m2?.lte?.tac ?: ""
                val cellId2 = m2?.nr?.nci ?: m2?.lte?.eci ?: ""

                writer.write("${s.timestamp},$dt,${s.latitude},${s.longitude},${s.altitude},${s.speedKmh},${s.bearing},${s.accuracyMeters},$cadenceSec,$name1,$rat1,$op1,$pci1,$arfcn1,$band1,$rsrp1,$rsrq1,$sinr1,$cqi1,$tac1,$cellId1,$name2,$rat2,$op2,$pci2,$arfcn2,$band2,$rsrp2,$rsrq2,$sinr2,$cqi2,$tac2,$cellId2\n")
            }
        } else {
            writer.write("Timestamp,DateTime,Latitude,Longitude,Altitude_m,Speed_kmh,Bearing,Accuracy_m,Cadence_s,RAT,PCI,ARFCN,Band,RSRP_dBm,RSRQ_dB,SINR_dB,CQI,TAC,CellID\n")
            for (s in samples) {
                val m = s.metrics
                val isNr = m.nr != null
                val rat = if (isNr) (if (m.nr.isNsa) "5G_NSA" else "5G_SA") else if (m.lte != null) "LTE" else "Unknown"
                val pci = m.nr?.pci ?: m.lte?.pci ?: ""
                val arfcn = m.nr?.nrArfcn ?: m.lte?.earfcn ?: ""
                val band = m.nr?.band ?: m.lte?.band ?: ""
                val rsrp = m.nr?.ssRsrp ?: m.lte?.rsrp ?: ""
                val rsrq = m.nr?.ssRsrq ?: m.lte?.rsrq ?: ""
                val sinr = m.nr?.ssSinr ?: m.lte?.rssnr ?: ""
                val cqi = m.lte?.cqi ?: ""
                val tac = m.nr?.tac ?: m.lte?.tac ?: ""
                val cellId = m.nr?.nci ?: m.lte?.eci ?: ""
                val dt = displayFormat.format(Date(s.timestamp))
                val cadenceSec = s.cadence.intervalMs / 1000

                writer.write("${s.timestamp},$dt,${s.latitude},${s.longitude},${s.altitude},${s.speedKmh},${s.bearing},${s.accuracyMeters},$cadenceSec,$rat,$pci,$arfcn,$band,$rsrp,$rsrq,$sinr,$cqi,$tac,$cellId\n")
            }
        }
        writer.flush()
    }

    fun getStyleForRsrp(rsrp: Int): String = "tier${tierIndex(rsrp)}"

    fun getColorHexForRsrp(rsrp: Int): String {
        return when {
            rsrp >= -85 -> "#10b981"
            rsrp >= -95 -> "#22c55e"
            rsrp >= -105 -> "#eab308"
            rsrp >= -115 -> "#f97316"
            else -> "#ef4444"
        }
    }

    /** SIM 2 uses a blue -> magenta ramp so its dots never look like SIM 1 dots. */
    fun getSim2ColorHexForRsrp(rsrp: Int): String {
        return when {
            rsrp >= -85 -> "#38bdf8"
            rsrp >= -95 -> "#818cf8"
            rsrp >= -105 -> "#a78bfa"
            rsrp >= -115 -> "#e879f9"
            else -> "#f472b6"
        }
    }
}