package com.drivetest.rf.data

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import com.drivetest.rf.model.CellTechnology
import com.drivetest.rf.model.GpsInfo
import com.drivetest.rf.model.Measurement
import com.drivetest.rf.model.RadioInfo
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ExportEngine {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)

    fun exportToCsv(measurements: List<Measurement>, outputFile: File) {
        outputFile.bufferedWriter().use { writer ->
            // 24-column standard header matching RF Field Suite v1.8.1
            writer.write("Timestamp,Latitude,Longitude,Altitude,SpeedKmh,Bearing,Accuracy,Tech,Operator,MCC,MNC,SIM_Slot,CellID,TAC,PCI,Band,ARFCN,DL_Freq_kHz,DL_BW_kHz,RSRP,RSRQ,SINR,RSSI,CQI\n")
            for (m in measurements) {
                val r = m.radio
                val g = m.gps
                val ts = dateFormat.format(Date(m.timestampMs))
                writer.write("$ts,${g.latitude},${g.longitude},${g.altitude},${g.speedKmh},${g.bearing},${g.accuracy},${r.technology.name},${r.operatorName},${r.mcc},${r.mnc},${r.simSlot},${r.cellId},${r.tac},${r.pci},\"${r.band}\",${r.arfcn},${r.dlFrequencyKhz},${r.dlBandwidthKhz},${r.rsrp},${r.rsrq},${r.sinr},${r.rssi},${r.cqi}\n")
            }
        }
    }

    fun exportToKml(campaignName: String, measurements: List<Measurement>, outputFile: File) {
        outputFile.bufferedWriter().use { writer ->
            writer.write("""
<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
  <Document>
    <name>$campaignName — RF Field Suite KML 2.2</name>
    <Style id="rfGood"><IconStyle><color>ff5ec522</color><scale>0.8</scale></IconStyle></Style>
    <Style id="rfFair"><IconStyle><color>ff15ccfa</color><scale>0.8</scale></IconStyle></Style>
    <Style id="rfPoor"><IconStyle><color>ff4444ef</color><scale>0.8</scale></IconStyle></Style>
    <Style id="routeLine"><LineStyle><color>fff8bd38</color><width>4</width></LineStyle></Style>
    <Placemark>
      <name>Drive Route</name>
      <styleUrl>#routeLine</styleUrl>
      <LineString>
        <tessellate>1</tessellate>
        <coordinates>
""".trimIndent() + "\n")
            measurements.forEach { m ->
                writer.write("          ${m.gps.longitude},${m.gps.latitude},${m.gps.altitude}\n")
            }
            writer.write("""
        </coordinates>
      </LineString>
    </Placemark>
""".trimIndent() + "\n")

            measurements.forEachIndexed { idx, m ->
                val styleId = when {
                    m.radio.rsrp >= -85 -> "rfGood"
                    m.radio.rsrp >= -105 -> "rfFair"
                    else -> "rfPoor"
                }
                writer.write("""
    <Placemark>
      <name>#${idx + 1} ${m.radio.rsrp} dBm (${m.radio.technology.displayName} PCI ${m.radio.pci})</name>
      <styleUrl>#$styleId</styleUrl>
      <description><![CDATA[
        <b>RSRP:</b> ${m.radio.rsrp} dBm<br/>
        <b>RSRQ:</b> ${m.radio.rsrq} dB<br/>
        <b>SINR:</b> ${m.radio.sinr} dB<br/>
        <b>Cell ID:</b> ${m.radio.cellId} / TAC ${m.radio.tac}<br/>
        <b>Band:</b> ${m.radio.band} (ARFCN ${m.radio.arfcn})<br/>
        <b>Speed:</b> ${String.format(Locale.US, "%.1f", m.gps.speedKmh)} km/h
      ]]></description>
      <Point>
        <coordinates>${m.gps.longitude},${m.gps.latitude},${m.gps.altitude}</coordinates>
      </Point>
    </Placemark>
""".trimIndent() + "\n")
            }
            writer.write("  </Document>\n</kml>\n")
        }
    }

    fun exportToGeoJson(measurements: List<Measurement>, outputFile: File) {
        outputFile.bufferedWriter().use { writer ->
            writer.write("{\n  \"type\": \"FeatureCollection\",\n  \"features\": [\n")
            measurements.forEachIndexed { index, m ->
                val comma = if (index < measurements.size - 1) "," else ""
                writer.write("""
    {
      "type": "Feature",
      "geometry": {
        "type": "Point",
        "coordinates": [${m.gps.longitude}, ${m.gps.latitude}, ${m.gps.altitude}]
      },
      "properties": {
        "timestamp": ${m.timestampMs},
        "rsrp": ${m.radio.rsrp},
        "rsrq": ${m.radio.rsrq},
        "sinr": ${m.radio.sinr},
        "cqi": ${m.radio.cqi},
        "tech": "${m.radio.technology.displayName}",
        "band": "${m.radio.band}",
        "pci": ${m.radio.pci},
        "cellId": ${m.radio.cellId},
        "speedKmh": ${m.gps.speedKmh}
      }
    }$comma
""".trimIndent() + "\n")
            }
            writer.write("  ]\n}\n")
        }
    }

    fun exportToPdfReport(context: Context, measurements: List<Measurement>, outputFile: File) {
        val document = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create() // A4
        val page = document.startPage(pageInfo)
        val canvas = page.canvas

        val paint = Paint().apply {
            isAntiAlias = true
        }

        // Header Background
        paint.color = Color.rgb(15, 23, 42) // Slate 900
        canvas.drawRect(0f, 0f, 595f, 92f, paint)

        // Header Text
        paint.color = Color.rgb(56, 189, 248) // Sky blue
        paint.textSize = 19f
        paint.isFakeBoldText = true
        canvas.drawText("RF FIELD SUITE v1.9.3 — 5G / LTE DRIVE TEST REPORT", 30f, 44f, paint)

        paint.color = Color.WHITE
        paint.textSize = 10f
        paint.isFakeBoldText = false
        val distKm = RfAnalyticsEngine.calculateTotalRouteDistanceKm(measurements)
        val hoCount = RfAnalyticsEngine.detectHandovers(measurements).size
        val diag = RfAnalyticsEngine.calculateRouteDiagnostic(measurements)
        canvas.drawText(
            String.format(
                Locale.US,
                "Samples: %d | Distance: %.2f km | Handovers: %d | Diagnostic Score: %d/100 (%s)",
                measurements.size,
                distKm,
                hoCount,
                diag.overallScore,
                diag.grade
            ),
            30f,
            68f,
            paint
        )

        // Metrics Summary Cards
        var avgRsrp = 0
        var avgSinr = 0
        if (measurements.isNotEmpty()) {
            avgRsrp = measurements.map { it.radio.rsrp }.average().toInt()
            avgSinr = measurements.map { it.radio.sinr }.average().toInt()
        }

        paint.color = Color.rgb(241, 245, 249)
        canvas.drawRect(30f, 110f, 280f, 190f, paint)
        canvas.drawRect(315f, 110f, 565f, 190f, paint)

        paint.color = Color.BLACK
        paint.textSize = 12f
        paint.isFakeBoldText = true
        canvas.drawText("RF COVERAGE SUMMARY", 45f, 135f, paint)
        paint.textSize = 11f
        paint.isFakeBoldText = false
        canvas.drawText("Average RSRP: $avgRsrp dBm", 45f, 155f, paint)
        canvas.drawText("Average SINR: $avgSinr dB", 45f, 175f, paint)

        paint.textSize = 12f
        paint.isFakeBoldText = true
        canvas.drawText("TEST ENVIRONMENT", 330f, 135f, paint)
        paint.textSize = 11f
        paint.isFakeBoldText = false
        val servingTech = measurements.firstOrNull()?.radio?.technology?.displayName ?: "5G NR"
        val servingBand = measurements.firstOrNull()?.radio?.band ?: "n78"
        canvas.drawText("Technology: $servingTech", 330f, 155f, paint)
        canvas.drawText("Serving Band: $servingBand", 330f, 175f, paint)

        // Table Header
        paint.color = Color.rgb(30, 41, 59)
        canvas.drawRect(30f, 220f, 565f, 245f, paint)
        paint.color = Color.WHITE
        paint.textSize = 10f
        paint.isFakeBoldText = true
        canvas.drawText("Time", 40f, 237f, paint)
        canvas.drawText("Lat / Lon", 120f, 237f, paint)
        canvas.drawText("RSRP", 240f, 237f, paint)
        canvas.drawText("SINR", 300f, 237f, paint)
        canvas.drawText("PCI", 360f, 237f, paint)
        canvas.drawText("Cell ID", 420f, 237f, paint)
        canvas.drawText("Speed", 510f, 237f, paint)

        // Table Rows (First 22 samples)
        paint.isFakeBoldText = false
        val rowTimeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
        var y = 265f
        measurements.take(22).forEachIndexed { i, m ->
            paint.color = if (i % 2 == 0) Color.rgb(248, 250, 252) else Color.WHITE
            canvas.drawRect(30f, y - 14f, 565f, y + 6f, paint)

            paint.color = Color.rgb(15, 23, 42)
            canvas.drawText(rowTimeFormat.format(Date(m.timestampMs)), 40f, y, paint)
            canvas.drawText(String.format(Locale.US, "%.4f, %.4f", m.gps.latitude, m.gps.longitude), 120f, y, paint)

            paint.color = when {
                m.radio.rsrp >= -85 -> Color.rgb(22, 163, 74)
                m.radio.rsrp >= -105 -> Color.rgb(202, 138, 4)
                else -> Color.rgb(220, 38, 38)
            }
            canvas.drawText("${m.radio.rsrp} dBm", 240f, y, paint)

            paint.color = Color.rgb(15, 23, 42)
            canvas.drawText("${m.radio.sinr} dB", 300f, y, paint)
            canvas.drawText("${m.radio.pci}", 360f, y, paint)
            canvas.drawText("${m.radio.cellId}", 420f, y, paint)
            canvas.drawText(String.format(Locale.US, "%.1f km/h", m.gps.speedKmh), 510f, y, paint)

            y += 22f
        }

        document.finishPage(page)
        FileOutputStream(outputFile).use { out ->
            document.writeTo(out)
        }
        document.close()
    }

    fun parseSampleCsvImport(): List<Measurement> {
        val now = System.currentTimeMillis() - 1800_000L
        return (0 until 24).map { idx ->
            val lat = 13.7650 + idx * 0.00052
            val lon = 100.5380 + idx * 0.00048
            val rsrp = -80 - (idx % 9) * 3
            val sinr = 20 - (idx % 7) * 2
            Measurement(
                id = now + idx,
                campaignId = 0L,
                timestampMs = now + idx * 2000L,
                radio = RadioInfo(
                    technology = CellTechnology.NR_5G,
                    operatorName = "AIS 5G Imported",
                    mcc = "520",
                    mnc = "03",
                    cellId = 52003770L + (idx / 6),
                    tac = 10500,
                    pci = 152 + (idx / 6) * 12,
                    band = "n78 (3500 MHz)",
                    arfcn = 633334,
                    rsrp = rsrp,
                    rsrq = -10 - (idx % 4),
                    sinr = sinr,
                    rssi = rsrp + 22,
                    csiRsrp = rsrp - 1,
                    cqi = 13,
                    timingAdvance = 3,
                    is5gSa = true,
                    isConnected = true
                ),
                gps = GpsInfo(
                    latitude = lat,
                    longitude = lon,
                    altitude = 16.0,
                    speedKmh = 48.5f,
                    bearing = 60f,
                    accuracy = 3.2f,
                    satellites = 14,
                    timestampMs = now + idx * 2000L,
                    isValid = true
                )
            )
        }
    }
}
