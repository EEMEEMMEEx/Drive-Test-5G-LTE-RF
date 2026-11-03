package com.drivetest.rf.data

import androidx.compose.ui.graphics.Color
import com.drivetest.rf.model.*
import kotlin.math.*

object RfAnalyticsEngine {

    fun calculateDistanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0 // Earth radius in km
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    fun calculateTotalRouteDistanceKm(measurements: List<Measurement>): Double {
        if (measurements.size < 2) return 0.0
        var total = 0.0
        for (i in 1 until measurements.size) {
            val prev = measurements[i - 1].gps
            val curr = measurements[i].gps
            val d = calculateDistanceKm(prev.latitude, prev.longitude, curr.latitude, curr.longitude)
            if (d < 5.0) { // ignore GPS teleport glitches > 5 km
                total += d
            }
        }
        return total
    }

    fun detectHandovers(measurements: List<Measurement>): List<HandoverEvent> {
        if (measurements.size < 2) return emptyList()
        val events = mutableListOf<HandoverEvent>()
        for (i in 1 until measurements.size) {
            val prev = measurements[i - 1]
            val curr = measurements[i]
            val pciChanged = prev.radio.pci != 0 && curr.radio.pci != 0 && prev.radio.pci != curr.radio.pci
            val cellChanged = prev.radio.cellId != 0L && curr.radio.cellId != 0L && prev.radio.cellId != curr.radio.cellId
            if (pciChanged || cellChanged) {
                // Check ping-pong: did we return to a PCI seen within the last 30 seconds?
                val recentPingPong = events.lastOrNull()?.let { last ->
                    (curr.timestampMs - last.timestampMs) <= 30_000L &&
                            last.fromPci == curr.radio.pci &&
                            last.toPci == prev.radio.pci
                } ?: false

                events.add(
                    HandoverEvent(
                        id = curr.timestampMs,
                        timestampMs = curr.timestampMs,
                        latitude = curr.gps.latitude,
                        longitude = curr.gps.longitude,
                        fromPci = prev.radio.pci,
                        toPci = curr.radio.pci,
                        fromCellId = prev.radio.cellId,
                        toCellId = curr.radio.cellId,
                        fromTech = prev.radio.technology,
                        toTech = curr.radio.technology,
                        fromBand = prev.radio.band,
                        toBand = curr.radio.band,
                        rsrpBefore = prev.radio.rsrp,
                        rsrpAfter = curr.radio.rsrp,
                        deltaDb = curr.radio.rsrp - prev.radio.rsrp,
                        isPingPong = recentPingPong
                    )
                )
            }
        }
        return events
    }

    fun detectMissedHandovers(measurements: List<Measurement>): List<MissedHandoverEvent> {
        val missed = mutableListOf<MissedHandoverEvent>()
        measurements.forEach { m ->
            val bestNeighbor = m.radio.neighborCells.maxByOrNull { it.rsrp }
            if (bestNeighbor != null && m.radio.rsrp <= -92) {
                val delta = bestNeighbor.rsrp - m.radio.rsrp
                if (delta >= 6 && bestNeighbor.pci != m.radio.pci) {
                    missed.add(
                        MissedHandoverEvent(
                            id = m.id,
                            timestampMs = m.timestampMs,
                            latitude = m.gps.latitude,
                            longitude = m.gps.longitude,
                            servingPci = m.radio.pci,
                            servingRsrp = m.radio.rsrp,
                            bestNeighborPci = bestNeighbor.pci,
                            bestNeighborRsrp = bestNeighbor.rsrp,
                            deltaDb = delta
                        )
                    )
                }
            }
        }
        return missed
    }

    fun extractCellSites(measurements: List<Measurement>): List<CellSiteSummary> {
        if (measurements.isEmpty()) return emptyList()
        return measurements
            .groupBy { "${it.radio.cellId}_${it.radio.pci}" }
            .map { (_, list) ->
                val first = list.first()
                val avgRsrp = list.map { it.radio.rsrp }.average().roundToInt()
                val maxRsrp = list.maxOf { it.radio.rsrp }
                val avgSinr = list.map { it.radio.sinr }.average().roundToInt()
                val lat = list.map { it.gps.latitude }.average()
                val lon = list.map { it.gps.longitude }.average()
                CellSiteSummary(
                    cellId = first.radio.cellId,
                    pci = first.radio.pci,
                    tac = first.radio.tac,
                    technology = first.radio.technology,
                    band = first.radio.band,
                    arfcn = first.radio.arfcn,
                    operatorName = first.radio.operatorName,
                    sampleCount = list.size,
                    avgRsrp = avgRsrp,
                    maxRsrp = maxRsrp,
                    avgSinr = avgSinr,
                    centroidLat = lat,
                    centroidLon = lon,
                    firstSeenMs = list.minOf { it.timestampMs },
                    lastSeenMs = list.maxOf { it.timestampMs }
                )
            }
            .sortedByDescending { it.sampleCount }
    }

    fun calculateBandUsage(measurements: List<Measurement>): List<BandUsageStat> {
        if (measurements.isEmpty()) return emptyList()
        val total = measurements.size.toDouble()
        return measurements
            .groupBy { it.radio.band.ifBlank { "Unknown" } }
            .map { (band, list) ->
                BandUsageStat(
                    band = band,
                    technology = list.first().radio.technology,
                    sampleCount = list.size,
                    percentage = (list.size / total) * 100.0,
                    avgRsrp = list.map { it.radio.rsrp }.average().roundToInt(),
                    avgSinr = list.map { it.radio.sinr }.average().roundToInt()
                )
            }
            .sortedByDescending { it.sampleCount }
    }

    fun calculateCdf(values: List<Double>): MetricCdfSummary {
        if (values.isEmpty()) {
            return MetricCdfSummary(emptyList(), 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        }
        val sorted = values.sorted()
        val n = sorted.size
        val points = sorted.mapIndexed { idx, v ->
            CdfPoint(value = v, percentile = ((idx + 1).toDouble() / n.toDouble()) * 100.0)
        }
        fun percentileValue(p: Double): Double {
            val idx = ((p / 100.0) * (n - 1)).roundToInt().coerceIn(0, n - 1)
            return sorted[idx]
        }
        return MetricCdfSummary(
            points = points,
            p10 = percentileValue(10.0),
            p50 = percentileValue(50.0),
            p90 = percentileValue(90.0),
            min = sorted.first(),
            max = sorted.last(),
            mean = sorted.average()
        )
    }

    fun calculateRouteDiagnostic(
        measurements: List<Measurement>,
        speedTests: List<SpeedTestResult> = emptyList(),
        weakThresholdDbm: Int = -105
    ): RouteDiagnosticReport {
        if (measurements.isEmpty()) {
            return RouteDiagnosticReport(100, "EXCELLENT", emptyList())
        }
        val issues = mutableListOf<DiagnosticIssue>()
        var score = 100

        // 1. Weak Signal detector
        val weakSamples = measurements.count { it.radio.rsrp <= weakThresholdDbm }
        val weakRatio = weakSamples.toDouble() / measurements.size
        if (weakSamples > 0) {
            val penalty = (weakRatio * 35).roundToInt().coerceIn(5, 30)
            score -= penalty
            issues.add(
                DiagnosticIssue(
                    type = DiagnosticIssueType.WEAK_SIGNAL,
                    severity = if (weakRatio >= 0.25) "HIGH" else "MEDIUM",
                    titleEn = "Coverage Hole / Weak RSRP Detected",
                    titleTh = "ตรวจพบจุดอับสัญญาณ / ค่า RSRP ต่ำกว่าเกณฑ์",
                    summaryEn = "$weakSamples samples (${(weakRatio * 100).roundToInt()}%) dropped below $weakThresholdDbm dBm.",
                    summaryTh = "พบ $weakSamples จุดวัด (${(weakRatio * 100).roundToInt()}%) ที่สัญญาณต่ำกว่า $weakThresholdDbm dBm",
                    recommendationEn = "Check serving sector antenna mechanical/electrical downtilt or evaluate neighbor cell dominance.",
                    recommendationTh = "ตรวจสอบมุมก้มสายอากาศ (Downtilt) ของเซกเตอร์ที่ให้บริการ หรือปรับพารามิเตอร์รับส่งต่อกับเซลล์ข้างเคียง",
                    affectedSamples = weakSamples
                )
            )
        }

        // 2. Poor Quality / Interference (SINR < 3 dB or RSRQ < -14 dB)
        val poorQualSamples = measurements.count { it.radio.sinr < 3 || it.radio.rsrq < -14 }
        val poorRatio = poorQualSamples.toDouble() / measurements.size
        if (poorQualSamples > 0) {
            val penalty = (poorRatio * 28).roundToInt().coerceIn(4, 25)
            score -= penalty
            issues.add(
                DiagnosticIssue(
                    type = DiagnosticIssueType.POOR_QUALITY,
                    severity = if (poorRatio >= 0.25) "HIGH" else "MEDIUM",
                    titleEn = "Co-Channel Interference / Poor SINR & RSRQ",
                    titleTh = "ตรวจพบสัญญาณรบกวน (Interference) / SINR และ RSRQ ต่ำ",
                    summaryEn = "$poorQualSamples samples exhibited SINR < 3 dB or RSRQ < -14 dB (overlapping cell pollution).",
                    summaryTh = "พบ $poorQualSamples จุดวัดที่มีค่า SINR < 3 dB หรือ RSRQ < -14 dB จากสัญญาณทับซ้อนหลายเซกเตอร์",
                    recommendationEn = "Audit PCI mod-3/mod-30 collisions and reduce pilot pollution from overshooting cells.",
                    recommendationTh = "ตรวจสอบการชนกันของ PCI (Mod-3/Mod-30) และลดกำลังส่งของเซลล์ที่ยิงสัญญาณข้ามเขต (Overshooting)",
                    affectedSamples = poorQualSamples
                )
            )
        }

        // 3. Frequent Handovers / Ping-Pong
        val handovers = detectHandovers(measurements)
        val pingPongs = handovers.count { it.isPingPong }
        val missedHos = detectMissedHandovers(measurements)
        if (pingPongs > 0 || missedHos.isNotEmpty() || handovers.size > (measurements.size / 4).coerceAtLeast(3)) {
            val penalty = (pingPongs * 6 + missedHos.size * 3).coerceIn(5, 20)
            score -= penalty
            issues.add(
                DiagnosticIssue(
                    type = DiagnosticIssueType.FREQUENT_HANDOVERS,
                    severity = if (pingPongs >= 2 || missedHos.size >= 3) "HIGH" else "LOW",
                    titleEn = "Mobility Instability (${handovers.size} HO · $pingPongs Ping-Pong · ${missedHos.size} Missed)",
                    titleTh = "ความไม่เสถียรขณะเคลื่อนที่ (${handovers.size} HO · $pingPongs สลับไปมา · พลาด ${missedHos.size})",
                    summaryEn = "Detected $pingPongs rapid ping-pong transitions (<30s) and ${missedHos.size} stronger neighbor opportunities (+6 dB).",
                    summaryTh = "พบการสลับเซลล์ไปมาเร็วเกินกำหนด $pingPongs ครั้ง และจุดที่มีเซลล์ข้างเคียงแรงกว่า +6 dB แต่ไม่เปลี่ยนเซลล์ ${missedHos.size} จุด",
                    recommendationEn = "Tune A3 Event offset, Time-To-Trigger (TTT), and hysteresis parameters.",
                    recommendationTh = "ปรับแต่งค่าพารามิเตอร์ A3 Offset, Time-To-Trigger (TTT) และ Hysteresis ของการทำ Handover",
                    affectedSamples = pingPongs + missedHos.size
                )
            )
        }

        // 4. Low Throughput
        val lowDlTests = speedTests.filter { it.downloadMbps > 0 && it.downloadMbps < 25.0 }
        if (lowDlTests.isNotEmpty()) {
            score -= 10
            issues.add(
                DiagnosticIssue(
                    type = DiagnosticIssueType.LOW_THROUGHPUT,
                    severity = "MEDIUM",
                    titleEn = "Sub-Optimal Downlink Throughput",
                    titleTh = "ความเร็วดาวน์โหลดต่ำกว่าเกณฑ์มาตรฐาน",
                    summaryEn = "${lowDlTests.size} speed test(s) recorded DL < 25 Mbps.",
                    summaryTh = "ผลการทดสอบความเร็ว ${lowDlTests.size} ครั้งได้ค่าดาวน์โหลดต่ำกว่า 25 Mbps",
                    recommendationEn = "Verify Secondary Cell (SCell) Carrier Aggregation activation and PRB scheduler utilization.",
                    recommendationTh = "ตรวจสอบการทำงานของ Carrier Aggregation (SCell) และความหนาแน่นของทราฟฟิกบนสถานีฐาน",
                    affectedSamples = lowDlTests.size
                )
            )
        }

        // 5. High Loaded Latency (Bufferbloat)
        val highLatencyTests = speedTests.filter { it.loadedLatencyMs > 85.0 }
        if (highLatencyTests.isNotEmpty()) {
            score -= 10
            issues.add(
                DiagnosticIssue(
                    type = DiagnosticIssueType.HIGH_LOADED_LATENCY,
                    severity = "LOW",
                    titleEn = "Elevated Loaded Latency (Bufferbloat)",
                    titleTh = "ความหน่วงขณะโหลดข้อมูลสูงผิดปกติ (Loaded Latency)",
                    summaryEn = "${highLatencyTests.size} test(s) exceeded 85 ms under load.",
                    summaryTh = "พบค่าความหน่วงขณะโหลดข้อมูลเกิน 85 ms จำนวน ${highLatencyTests.size} ครั้ง",
                    recommendationEn = "Inspect RLC buffer queuing, AQM configuration, and backhaul transport jitter.",
                    recommendationTh = "ตรวจสอบคิว RLC Buffer และการตั้งค่า QoS/Backhaul ของสถานีฐาน",
                    affectedSamples = highLatencyTests.size
                )
            )
        }

        val finalScore = score.coerceIn(10, 100)
        val grade = when {
            finalScore >= 88 -> "EXCELLENT"
            finalScore >= 75 -> "GOOD"
            finalScore >= 58 -> "FAIR"
            else -> "POOR"
        }

        return RouteDiagnosticReport(
            overallScore = finalScore,
            grade = grade,
            issues = issues
        )
    }

    fun compareCampaigns(
        campaignA: Campaign,
        measurementsA: List<Measurement>,
        campaignB: Campaign,
        measurementsB: List<Measurement>
    ): CampaignComparisonResult {
        val avgRsrpA = if (measurementsA.isNotEmpty()) measurementsA.map { it.radio.rsrp }.average() else -110.0
        val avgRsrpB = if (measurementsB.isNotEmpty()) measurementsB.map { it.radio.rsrp }.average() else -110.0
        val avgSinrA = if (measurementsA.isNotEmpty()) measurementsA.map { it.radio.sinr }.average() else 0.0
        val avgSinrB = if (measurementsB.isNotEmpty()) measurementsB.map { it.radio.sinr }.average() else 0.0
        val avgSpeedA = if (measurementsA.isNotEmpty()) measurementsA.map { it.gps.speedKmh }.average() else 0.0
        val avgSpeedB = if (measurementsB.isNotEmpty()) measurementsB.map { it.gps.speedKmh }.average() else 0.0

        val goodA = if (measurementsA.isNotEmpty()) {
            (measurementsA.count { it.radio.rsrp >= -95 }.toDouble() / measurementsA.size) * 100.0
        } else 0.0
        val goodB = if (measurementsB.isNotEmpty()) {
            (measurementsB.count { it.radio.rsrp >= -95 }.toDouble() / measurementsB.size) * 100.0
        } else 0.0

        val distA = calculateTotalRouteDistanceKm(measurementsA).coerceAtLeast(0.1)
        val distB = calculateTotalRouteDistanceKm(measurementsB).coerceAtLeast(0.1)
        val hoRateA = detectHandovers(measurementsA).size / distA
        val hoRateB = detectHandovers(measurementsB).size / distB

        return CampaignComparisonResult(
            campaignA = campaignA,
            campaignB = campaignB,
            avgRsrpA = avgRsrpA,
            avgRsrpB = avgRsrpB,
            deltaRsrp = avgRsrpA - avgRsrpB,
            avgSinrA = avgSinrA,
            avgSinrB = avgSinrB,
            deltaSinr = avgSinrA - avgSinrB,
            avgSpeedA = avgSpeedA,
            avgSpeedB = avgSpeedB,
            coverageGoodPctA = goodA,
            coverageGoodPctB = goodB,
            handoverRatePerKmA = hoRateA,
            handoverRatePerKmB = hoRateB
        )
    }

    fun getMetricColor(
        m: Measurement,
        mode: MapColorMode,
        palette: SignalPalette = SignalPalette.CLASSIC,
        goodRsrpThreshold: Int = -85,
        poorRsrpThreshold: Int = -105
    ): Color {
        // Normalize quality into 0..3 bucket (3 = Excellent, 2 = Good, 1 = Fair, 0 = Poor)
        val bucket = when (mode) {
            MapColorMode.BY_RSRP -> {
                val v = m.radio.rsrp
                when {
                    v >= goodRsrpThreshold -> 3
                    v >= (goodRsrpThreshold + poorRsrpThreshold) / 2 -> 2
                    v >= poorRsrpThreshold -> 1
                    else -> 0
                }
            }
            MapColorMode.BY_RSRQ -> {
                val v = m.radio.rsrq
                when {
                    v >= -9 -> 3
                    v >= -12 -> 2
                    v >= -15 -> 1
                    else -> 0
                }
            }
            MapColorMode.BY_SINR -> {
                val v = m.radio.sinr
                when {
                    v >= 18 -> 3
                    v >= 10 -> 2
                    v >= 2 -> 1
                    else -> 0
                }
            }
            MapColorMode.BY_TECH -> {
                return when (m.radio.technology) {
                    CellTechnology.NR_5G -> Color(0xFF38BDF8) // Cyan 5G
                    CellTechnology.LTE_4G -> Color(0xFF22C55E) // Green 4G
                    CellTechnology.WCDMA_3G -> Color(0xFFFACC15) // Yellow 3G
                    else -> Color(0xFFEF4444)
                }
            }
            MapColorMode.BY_SPEED -> {
                val v = m.gps.speedKmh
                when {
                    v >= 60f -> 3
                    v >= 30f -> 2
                    v >= 10f -> 1
                    else -> 0
                }
            }
        }

        return when (palette) {
            SignalPalette.CLASSIC -> when (bucket) {
                3 -> Color(0xFF22C55E) // Emerald
                2 -> Color(0xFF38BDF8) // Sky
                1 -> Color(0xFFFACC15) // Amber
                else -> Color(0xFFEF4444) // Red
            }
            SignalPalette.THERMIQUE -> when (bucket) {
                3 -> Color(0xFFFDE047) // Bright Yellow-White
                2 -> Color(0xFFFB923C) // Orange
                1 -> Color(0xFFEC4899) // Magenta
                else -> Color(0xFF6366F1) // Deep Indigo
            }
            SignalPalette.VIRIDIS -> when (bucket) {
                3 -> Color(0xFFFDE725) // Viridis Yellow
                2 -> Color(0xFF35B779) // Viridis Green
                1 -> Color(0xFF31688E) // Viridis Blue
                else -> Color(0xFF440154) // Viridis Purple
            }
            SignalPalette.OCEAN -> when (bucket) {
                3 -> Color(0xFF2DD4BF) // Aqua
                2 -> Color(0xFF38BDF8) // Sky Blue
                1 -> Color(0xFF3B82F6) // Royal Blue
                else -> Color(0xFF1D4ED8) // Deep Navy Blue
            }
        }
    }
}
