package com.drivetest.lte5g.export

import com.drivetest.lte5g.model.DriveTestSample
import com.drivetest.lte5g.model.RatType
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object KmlExporter {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
    private val displayFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

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

        writer.write("""<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
<Document>
    <name>Drive Test Route</name>
    <description>Recorded via Android Drive Test LTE/5G</description>

    <!-- Styles for Route Lines (Signal Quality - aabbggrr format) -->
    <Style id="line_tier1"><LineStyle><color>ff81b910</color><width>6</width></LineStyle></Style>
    <Style id="line_tier2"><LineStyle><color>ff5ec522</color><width>6</width></LineStyle></Style>
    <Style id="line_tier3"><LineStyle><color>ff08b3ea</color><width>6</width></LineStyle></Style>
    <Style id="line_tier4"><LineStyle><color>ff1673f9</color><width>6</width></LineStyle></Style>
    <Style id="line_tier5"><LineStyle><color>ff4444ef</color><width>6</width></LineStyle></Style>

    <!-- Styles for Survey Point Dots (placemark_circle - aabbggrr format) -->
    <Style id="point_tier1">
        <IconStyle>
            <color>ff81b910</color>
            <scale>0.6</scale>
            <Icon><href>http://maps.google.com/mapfiles/kml/shapes/placemark_circle.png</href></Icon>
        </IconStyle>
        <LabelStyle><scale>0</scale></LabelStyle>
        <BalloonStyle><text><![CDATA[$[description]]]></text></BalloonStyle>
    </Style>
    <Style id="point_tier2">
        <IconStyle>
            <color>ff5ec522</color>
            <scale>0.6</scale>
            <Icon><href>http://maps.google.com/mapfiles/kml/shapes/placemark_circle.png</href></Icon>
        </IconStyle>
        <LabelStyle><scale>0</scale></LabelStyle>
        <BalloonStyle><text><![CDATA[$[description]]]></text></BalloonStyle>
    </Style>
    <Style id="point_tier3">
        <IconStyle>
            <color>ff08b3ea</color>
            <scale>0.6</scale>
            <Icon><href>http://maps.google.com/mapfiles/kml/shapes/placemark_circle.png</href></Icon>
        </IconStyle>
        <LabelStyle><scale>0</scale></LabelStyle>
        <BalloonStyle><text><![CDATA[$[description]]]></text></BalloonStyle>
    </Style>
    <Style id="point_tier4">
        <IconStyle>
            <color>ff1673f9</color>
            <scale>0.6</scale>
            <Icon><href>http://maps.google.com/mapfiles/kml/shapes/placemark_circle.png</href></Icon>
        </IconStyle>
        <LabelStyle><scale>0</scale></LabelStyle>
        <BalloonStyle><text><![CDATA[$[description]]]></text></BalloonStyle>
    </Style>
    <Style id="point_tier5">
        <IconStyle>
            <color>ff4444ef</color>
            <scale>0.6</scale>
            <Icon><href>http://maps.google.com/mapfiles/kml/shapes/placemark_circle.png</href></Icon>
        </IconStyle>
        <LabelStyle><scale>0</scale></LabelStyle>
        <BalloonStyle><text><![CDATA[$[description]]]></text></BalloonStyle>
    </Style>

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
""")

        // Write Start & End Keypoints
        if (samples.isNotEmpty()) {
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

        // Folder 1: Route Track Polylines
        writer.write("""
    <Folder>
        <name>Route Track (Signal Polylines)</name>
""")

        for (i in 0 until samples.size - 1) {
            val p1 = samples[i]
            val p2 = samples[i + 1]
            val rsrp = p1.metrics.nr?.ssRsrp ?: p1.metrics.lte?.rsrp ?: -120
            val tier = getStyleForRsrp(rsrp)

            writer.write("""
        <Placemark>
            <name>Segment #$i (${rsrp} dBm)</name>
            <styleUrl>#line_$tier</styleUrl>
            <LineString>
                <altitudeMode>relativeToGround</altitudeMode>
                <coordinates>
                    ${p1.longitude},${p1.latitude},${p1.altitude}
                    ${p2.longitude},${p2.latitude},${p2.altitude}
                </coordinates>
            </LineString>
        </Placemark>""")
        }

        writer.write("""
    </Folder>

    <Folder>
        <name>Survey Points (RSRP Dots)</name>
""")

        // Folder 2: Individual Points with Colored Dots and ExtendedData
        for (sample in samples) {
            val metrics = sample.metrics
            val isNr = metrics.nr != null
            val rat = if (isNr) (if (metrics.nr.isNsa) "5G NSA" else "5G SA") else if (metrics.lte != null) "LTE" else "Unknown"
            val pci = metrics.nr?.pci ?: metrics.lte?.pci ?: 0
            val arfcn = metrics.nr?.nrArfcn ?: metrics.lte?.earfcn ?: 0
            val band = metrics.nr?.band ?: metrics.lte?.band ?: 0
            val rsrp = metrics.nr?.ssRsrp ?: metrics.lte?.rsrp ?: -140
            val rsrq = metrics.nr?.ssRsrq ?: metrics.lte?.rsrq ?: -30
            val sinr = metrics.nr?.ssSinr ?: metrics.lte?.rssnr ?: -20.0
            val timeUtc = dateFormat.format(Date(sample.timestamp))
            val timeDisplay = displayFormat.format(Date(sample.timestamp))
            val tier = getStyleForRsrp(rsrp)
            val rsrpColorHex = getColorHexForRsrp(rsrp)

            val m2 = sample.metricsSim2
            val isNr2 = m2?.nr != null
            val rat2 = if (m2 == null) null else if (isNr2) (if (m2.nr!!.isNsa) "5G NSA" else "5G SA") else if (m2.lte != null) "LTE" else "Unknown"
            val pci2 = m2?.nr?.pci ?: m2?.lte?.pci
            val rsrp2 = m2?.nr?.ssRsrp ?: m2?.lte?.rsrp
            val op2 = m2?.operatorAlpha ?: ""

            val sim2Row = if (m2 != null && rat2 != null) {
                """<tr bgcolor="#f1f5f9"><td><b>SIM 2 Benchmark</b></td><td>$rat2 (${rsrp2 ?: "--"} dBm, $op2)</td></tr>"""
            } else ""

            val sim2ExtendedData = if (m2 != null) {
                buildString {
                    append("\n                <Data name=\"SIM2_RAT\"><value>${rat2 ?: ""}</value></Data>")
                    append("\n                <Data name=\"SIM2_Operator\"><value>$op2</value></Data>")
                    append("\n                <Data name=\"SIM2_PCI\"><value>${pci2 ?: ""}</value></Data>")
                    append("\n                <Data name=\"SIM2_RSRP_dBm\"><value>${rsrp2 ?: ""}</value></Data>")
                }
            } else ""

            writer.write("""
        <Placemark>
            <name>$rat PCI:$pci (${rsrp}dBm)</name>
            <styleUrl>#point_$tier</styleUrl>
            <description><![CDATA[
                <div style="font-family:Arial,sans-serif; min-width:240px; color:#0f172a;">
                    <div style="background-color:#1e293b; color:white; padding:8px 12px; border-radius:6px 6px 0 0;">
                        <h4 style="margin:0; font-size:14px;"><b>$rat RF Signal Metrics</b></h4>
                        <span style="font-size:11px; color:#94a3b8;">$timeDisplay</span>
                    </div>
                    <table border="1" cellpadding="6" cellspacing="0" style="border-collapse:collapse; width:100%; font-size:12px; border-color:#e2e8f0;">
                        <tr bgcolor="#f8fafc"><td><b>RSRP (Signal)</b></td><td><b style="color:$rsrpColorHex; font-size:13px;">$rsrp dBm</b></td></tr>
                        <tr><td><b>RSRQ (Quality)</b></td><td>$rsrq dB</td></tr>
                        <tr bgcolor="#f8fafc"><td><b>SINR (Noise Ratio)</b></td><td>$sinr dB</td></tr>
                        <tr><td><b>Band / ARFCN</b></td><td>${if (band > 0) "B$band" else "N/A"} / $arfcn</td></tr>
                        <tr bgcolor="#f8fafc"><td><b>Serving Cell PCI</b></td><td>$pci</td></tr>
                        $sim2Row
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
                <Data name="RAT"><value>$rat</value></Data>
                <Data name="PCI"><value>$pci</value></Data>
                <Data name="ARFCN"><value>$arfcn</value></Data>
                <Data name="Band"><value>$band</value></Data>
                <Data name="RSRP_dBm"><value>$rsrp</value></Data>
                <Data name="RSRQ_dB"><value>$rsrq</value></Data>
                <Data name="SINR_dB"><value>$sinr</value></Data>
                <Data name="Speed_kmh"><value>${sample.speedKmh}</value></Data>
                <Data name="Accuracy_m"><value>${sample.accuracyMeters}</value></Data>
                <Data name="Cadence_s"><value>${sample.cadence.intervalMs / 1000}</value></Data>$sim2ExtendedData
            </ExtendedData>
        </Placemark>""")
        }

        writer.write("""
    </Folder>
</Document>
</kml>""")
        writer.flush()
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

    fun getStyleForRsrp(rsrp: Int): String {
        return when {
            rsrp >= -85 -> "tier1"
            rsrp >= -95 -> "tier2"
            rsrp >= -105 -> "tier3"
            rsrp >= -115 -> "tier4"
            else -> "tier5"
        }
    }

    fun getColorHexForRsrp(rsrp: Int): String {
        return when {
            rsrp >= -85 -> "#10b981"
            rsrp >= -95 -> "#22c55e"
            rsrp >= -105 -> "#eab308"
            rsrp >= -115 -> "#f97316"
            else -> "#ef4444"
        }
    }
}
