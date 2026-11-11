package com.drivetest.lte5g.data.analytics

import com.drivetest.lte5g.model.DriveTestSample
import com.drivetest.lte5g.model.DriveTestSession
import com.drivetest.lte5g.model.RatType
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

object RfAnalyticsEngine {

    fun analyze(session: DriveTestSession): RfSessionAnalytics {
        val samples = session.samples
        if (samples.isEmpty()) {
            return RfSessionAnalytics(
                sessionId = session.id,
                sessionName = session.name,
                totalSamples = 0,
                durationSeconds = 0,
                distanceMeters = 0.0,
                rsrpCdf = null,
                rsrqCdf = null,
                sinrCdf = null,
                ratDistribution = emptyMap(),
                bandDistribution = emptyMap(),
                problemAreas = emptyList(),
                mobilityEvents = emptyList(),
                routeHealthScore = 100,
                scoreGrade = "N/A"
            )
        }

        val durationSeconds = ((session.endTime ?: System.currentTimeMillis()) - session.startTime).coerceAtLeast(0L) / 1000L
        val distanceMeters = calculateTotalDistance(samples)

        // Extract metrics for primary SIM (metrics)
        val rsrpList = samples.mapNotNull { sample ->
            sample.metrics.nr?.ssRsrp?.toDouble() ?: sample.metrics.lte?.rsrp?.toDouble()
        }
        val rsrqList = samples.mapNotNull { sample ->
            sample.metrics.nr?.ssRsrq?.toDouble() ?: sample.metrics.lte?.rsrq?.toDouble()
        }
        val sinrList = samples.mapNotNull { sample ->
            sample.metrics.nr?.ssSinr ?: sample.metrics.lte?.rssnr
        }

        val rsrpCdf = calculateCdf(rsrpList)
        val rsrqCdf = calculateCdf(rsrqList)
        val sinrCdf = calculateCdf(sinrList)

        // RAT Distribution
        val ratDistribution = samples.groupingBy { it.metrics.ratType }.eachCount()

        // Band Distribution
        val bandDistribution = samples.mapNotNull { sample ->
            val nrBand = sample.metrics.nr?.band?.let { "n$it" }
            val lteBand = sample.metrics.lte?.band?.let { "B$it" }
            nrBand ?: lteBand
        }.groupingBy { it }.eachCount()

        val problemAreas = detectProblemAreas(samples)
        val mobilityEvents = detectMobilityEvents(samples)

        // Calculate Route Health Score (0-100)
        val coverageHoleSamples = rsrpList.count { it < -110.0 }
        val lowSinrSamples = sinrList.count { it < 0.0 }
        val pingPongCount = mobilityEvents.count { it.type == MobilityEventType.PING_PONG_HO }
        val missedHoCount = mobilityEvents.count { it.type == MobilityEventType.MISSED_HO }

        var score = 100.0
        if (samples.isNotEmpty()) {
            val coverageHolePct = (coverageHoleSamples.toDouble() / samples.size) * 100.0
            val lowSinrPct = (lowSinrSamples.toDouble() / samples.size) * 100.0

            // Penalty formula based on standard cellular KPI SLA weights
            score -= (coverageHolePct * 0.40) // Coverage hole penalty up to 40 pts
            score -= (lowSinrPct * 0.30)       // Interference penalty up to 30 pts
            score -= (pingPongCount * 4.0).coerceAtMost(20.0) // Ping-pong mobility penalty
            score -= (missedHoCount * 5.0).coerceAtMost(10.0) // Missed handover penalty
        }
        val finalScore = score.roundToInt().coerceIn(0, 100)
        val grade = when {
            finalScore >= 85 -> "Excellent"
            finalScore >= 70 -> "Good"
            finalScore >= 50 -> "Fair"
            else -> "Poor"
        }

        // Dual-SIM Summary if benchmark present
        val dualSimSummary = if (samples.any { it.metricsSim2 != null }) {
            val sim1AvgRsrp = rsrpList.average()
            val sim2RsrpList = samples.mapNotNull { it.metricsSim2?.nr?.ssRsrp?.toDouble() ?: it.metricsSim2?.lte?.rsrp?.toDouble() }
            val sim2AvgRsrp = if (sim2RsrpList.isNotEmpty()) sim2RsrpList.average() else 0.0
            val delta = sim1AvgRsrp - sim2AvgRsrp
            "SIM 1 (${String.format("%.1f", sim1AvgRsrp)} dBm) vs SIM 2 (${String.format("%.1f", sim2AvgRsrp)} dBm) · Δ ${String.format("%+.1f", delta)} dB"
        } else null

        return RfSessionAnalytics(
            sessionId = session.id,
            sessionName = session.name,
            totalSamples = samples.size,
            durationSeconds = durationSeconds,
            distanceMeters = distanceMeters,
            rsrpCdf = rsrpCdf,
            rsrqCdf = rsrqCdf,
            sinrCdf = sinrCdf,
            ratDistribution = ratDistribution,
            bandDistribution = bandDistribution,
            problemAreas = problemAreas,
            mobilityEvents = mobilityEvents,
            routeHealthScore = finalScore,
            scoreGrade = grade,
            dualSimSummary = dualSimSummary
        )
    }

    fun calculateTotalDistance(samples: List<DriveTestSample>): Double {
        if (samples.size < 2) return 0.0
        var totalMeters = 0.0
        for (i in 0 until samples.size - 1) {
            val s1 = samples[i]
            val s2 = samples[i + 1]
            val lat1 = Math.toRadians(s1.latitude)
            val lon1 = Math.toRadians(s1.longitude)
            val lat2 = Math.toRadians(s2.latitude)
            val lon2 = Math.toRadians(s2.longitude)
            val dLat = lat2 - lat1
            val dLon = lon2 - lon1
            val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                    Math.cos(lat1) * Math.cos(lat2) *
                    Math.sin(dLon / 2) * Math.sin(dLon / 2)
            val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
            val dist = 6371000.0 * c
            if (dist in 0.5..1000.0) {
                totalMeters += dist
            }
        }
        return totalMeters
    }

    fun calculateCdf(values: List<Double>): CdfDistribution? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val n = sorted.size

        fun percentile(p: Double): Double {
            val index = ((p / 100.0) * (n - 1)).roundToInt().coerceIn(0, n - 1)
            return sorted[index]
        }

        val p10 = percentile(10.0)
        val p50 = percentile(50.0)
        val p90 = percentile(90.0)
        val min = sorted.first()
        val max = sorted.last()
        val avg = sorted.average()

        // Downsample points for smooth curve rendering (20-30 points)
        val points = mutableListOf<Pair<Double, Double>>()
        val step = (n / 25).coerceAtLeast(1)
        for (i in 0 until n step step) {
            val pct = ((i + 1).toDouble() / n) * 100.0
            points.add(Pair(sorted[i], pct))
        }
        if (points.lastOrNull()?.first != max) {
            points.add(Pair(max, 100.0))
        }

        return CdfDistribution(
            p10 = p10,
            p50 = p50,
            p90 = p90,
            min = min,
            max = max,
            avg = avg,
            points = points
        )
    }

    fun detectProblemAreas(samples: List<DriveTestSample>): List<RfProblemArea> {
        val problems = mutableListOf<RfProblemArea>()

        for (sample in samples) {
            val rsrp = sample.metrics.nr?.ssRsrp ?: sample.metrics.lte?.rsrp
            val sinr = sample.metrics.nr?.ssSinr ?: sample.metrics.lte?.rssnr
            val neighbors = sample.metrics.neighbors

            if (rsrp != null) {
                // 1. Coverage Hole (RSRP < -110 dBm)
                if (rsrp < -110) {
                    val severity = if (rsrp < -118) ProblemSeverity.HIGH else ProblemSeverity.MEDIUM
                    problems.add(
                        RfProblemArea(
                            type = RfProblemType.COVERAGE_HOLE,
                            severity = severity,
                            latitude = sample.latitude,
                            longitude = sample.longitude,
                            timestamp = sample.timestamp,
                            title = "Coverage Hole: RSRP ${rsrp} dBm",
                            description = "สัญญาณวิกฤตต่ำกว่าเกณฑ์มาตรฐาน (-110 dBm) เสี่ยงสายหลุดและ Data Stalling",
                            metricSummary = "RSRP: ${rsrp} dBm, SINR: ${sinr?.let { String.format("%.1f", it) } ?: "N/A"} dB"
                        )
                    )
                }
                // 2. Pilot Pollution / High Interference (RSRP is good >= -95 dBm, but SINR < 0.0 dB)
                else if (rsrp >= -95 && sinr != null && sinr < 0.0) {
                    val severity = if (sinr < -3.0) ProblemSeverity.HIGH else ProblemSeverity.MEDIUM
                    problems.add(
                        RfProblemArea(
                            type = RfProblemType.PILOT_POLLUTION_INTERFERENCE,
                            severity = severity,
                            latitude = sample.latitude,
                            longitude = sample.longitude,
                            timestamp = sample.timestamp,
                            title = "Pilot Pollution / High Interference: SINR ${String.format("%.1f", sinr)} dB",
                            description = "สัญญาณรับได้แรง (${rsrp} dBm) แต่มีคลื่นรบกวนสูง (SINR ติดลบ) ส่งผลให้ Throughput ดิ่งลง",
                            metricSummary = "RSRP: ${rsrp} dBm, SINR: ${String.format("%.1f", sinr)} dB"
                        )
                    )
                }
                // 3. Weak Dominance (Multiple neighbor cells having signal close to serving within 2 dB)
                else if (neighbors.isNotEmpty()) {
                    val strongNeighbors = neighbors.filter { n ->
                        val nRsrp = n.rsrp
                        nRsrp != null && (rsrp - nRsrp) <= 2
                    }
                    if (strongNeighbors.size >= 2) {
                        problems.add(
                            RfProblemArea(
                                type = RfProblemType.WEAK_DOMINANCE,
                                severity = ProblemSeverity.LOW,
                                latitude = sample.latitude,
                                longitude = sample.longitude,
                                timestamp = sample.timestamp,
                                title = "Weak Dominance (เซลล์ข้างเคียง ${strongNeighbors.size} เซลล์แย่งสัญญาณ)",
                                description = "ไม่มีเซลล์หลักที่มีระดับสัญญาณโดดเด่น เสี่ยงต่อ Handover Oscillation",
                                metricSummary = "Serving RSRP: ${rsrp} dBm, Neighbors: ${strongNeighbors.mapNotNull { it.pci }.joinToString()}"
                            )
                        )
                    }
                }
            }
        }

        return problems
    }

    fun detectMobilityEvents(samples: List<DriveTestSample>): List<MobilityEvent> {
        val events = mutableListOf<MobilityEvent>()
        if (samples.size < 2) return events

        var lastPci: Int? = null
        var lastPciChangeTime = 0L
        val recentPciHistory = mutableListOf<Pair<Int, Long>>() // (PCI, Timestamp)

        var consecutiveMissedHoSamples = 0
        var dominantNeighborPci: Int? = null

        for (sample in samples) {
            val currentPci = sample.metrics.nr?.pci ?: sample.metrics.lte?.pci
            val servingRsrp = sample.metrics.nr?.ssRsrp ?: sample.metrics.lte?.rsrp
            val neighbors = sample.metrics.neighbors

            if (currentPci != null) {
                if (lastPci != null && currentPci != lastPci) {
                    val timeSinceLastChange = sample.timestamp - lastPciChangeTime

                    // Check for Ping-Pong (< 30 seconds back to an earlier PCI)
                    val isPingPong = timeSinceLastChange < 30_000L && recentPciHistory.any { it.first == currentPci }
                    val eventType = if (isPingPong) MobilityEventType.PING_PONG_HO else MobilityEventType.HANDOVER

                    events.add(
                        MobilityEvent(
                            type = eventType,
                            latitude = sample.latitude,
                            longitude = sample.longitude,
                            timestamp = sample.timestamp,
                            sourcePci = lastPci,
                            targetPci = currentPci,
                            details = if (isPingPong) {
                                "Ping-Pong สลับกลับ PCI $currentPci ภายใน ${timeSinceLastChange / 1000}s"
                            } else {
                                "Handover จาก PCI $lastPci -> PCI $currentPci"
                            }
                        )
                    )

                    lastPciChangeTime = sample.timestamp
                    consecutiveMissedHoSamples = 0
                }

                if (lastPci == null || currentPci != lastPci) {
                    recentPciHistory.add(Pair(currentPci, sample.timestamp))
                    if (recentPciHistory.size > 10) recentPciHistory.removeAt(0)
                    lastPci = currentPci
                    if (lastPciChangeTime == 0L) lastPciChangeTime = sample.timestamp
                }
            }

            // Missed Handover detection: Neighbor RSRP >= Serving RSRP + 6 dB
            if (servingRsrp != null && neighbors.isNotEmpty()) {
                val bestNeighbor = neighbors.maxByOrNull { it.rsrp ?: -999 }
                val bestNeighborRsrp = bestNeighbor?.rsrp
                if (bestNeighborRsrp != null && (bestNeighborRsrp - servingRsrp) >= 6) {
                    if (bestNeighbor.pci == dominantNeighborPci) {
                        consecutiveMissedHoSamples++
                    } else {
                        dominantNeighborPci = bestNeighbor.pci
                        consecutiveMissedHoSamples = 1
                    }

                    if (consecutiveMissedHoSamples == 3) {
                        events.add(
                            MobilityEvent(
                                type = MobilityEventType.MISSED_HO,
                                latitude = sample.latitude,
                                longitude = sample.longitude,
                                timestamp = sample.timestamp,
                                sourcePci = currentPci,
                                targetPci = bestNeighbor.pci,
                                details = "Neighbor PCI ${bestNeighbor.pci} แรงกว่า Serving PCI $currentPci ถึง ${bestNeighborRsrp - servingRsrp} dB ต่อเนื่องแต่ไม่ Handover"
                            )
                        )
                    }
                } else {
                    consecutiveMissedHoSamples = 0
                    dominantNeighborPci = null
                }
            }
        }

        return events
    }

    fun buildGeminiPromptPayload(session: DriveTestSession, analytics: RfSessionAnalytics): String {
        val root = JSONObject()
        root.put("session_name", session.name)
        root.put("total_samples", analytics.totalSamples)
        root.put("duration_sec", analytics.durationSeconds)
        root.put("distance_meters", analytics.distanceMeters.roundToInt())
        root.put("route_health_score", analytics.routeHealthScore)
        root.put("score_grade", analytics.scoreGrade)

        analytics.rsrpCdf?.let { cdf ->
            val rsrpObj = JSONObject()
            rsrpObj.put("p10_dbm", cdf.p10)
            rsrpObj.put("p50_median_dbm", cdf.p50)
            rsrpObj.put("p90_dbm", cdf.p90)
            rsrpObj.put("min_dbm", cdf.min)
            rsrpObj.put("max_dbm", cdf.max)
            rsrpObj.put("avg_dbm", String.format("%.1f", cdf.avg).toDoubleOrNull() ?: cdf.avg)
            root.put("rsrp_statistics", rsrpObj)
        }

        analytics.sinrCdf?.let { cdf ->
            val sinrObj = JSONObject()
            sinrObj.put("p10_db", cdf.p10)
            sinrObj.put("p50_median_db", cdf.p50)
            sinrObj.put("p90_db", cdf.p90)
            sinrObj.put("avg_db", String.format("%.1f", cdf.avg).toDoubleOrNull() ?: cdf.avg)
            root.put("sinr_statistics", sinrObj)
        }

        val ratObj = JSONObject()
        analytics.ratDistribution.forEach { (rat, count) ->
            ratObj.put(rat.name, count)
        }
        root.put("technology_distribution", ratObj)

        val bandObj = JSONObject()
        analytics.bandDistribution.forEach { (band, count) ->
            bandObj.put(band, count)
        }
        root.put("band_distribution", bandObj)

        val problemsArray = JSONArray()
        analytics.problemAreas.take(15).forEach { prob ->
            val p = JSONObject()
            p.put("type", prob.type.name)
            p.put("severity", prob.severity.name)
            p.put("title", prob.title)
            p.put("coordinates", "${prob.latitude}, ${prob.longitude}")
            problemsArray.put(p)
        }
        root.put("sample_detected_problems", problemsArray)
        root.put("total_problem_count", analytics.problemAreas.size)

        val mobilityObj = JSONObject()
        mobilityObj.put("total_handovers", analytics.mobilityEvents.count { it.type == MobilityEventType.HANDOVER })
        mobilityObj.put("ping_pong_count", analytics.mobilityEvents.count { it.type == MobilityEventType.PING_PONG_HO })
        mobilityObj.put("missed_handover_count", analytics.mobilityEvents.count { it.type == MobilityEventType.MISSED_HO })
        root.put("mobility_summary", mobilityObj)

        analytics.dualSimSummary?.let {
            root.put("dual_sim_benchmark", it)
        }

        return root.toString(2)
    }
}
