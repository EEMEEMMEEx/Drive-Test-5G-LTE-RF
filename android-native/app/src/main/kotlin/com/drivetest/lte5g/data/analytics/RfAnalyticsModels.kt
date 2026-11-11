package com.drivetest.lte5g.data.analytics

import com.drivetest.lte5g.model.RatType

enum class RfProblemType(val title: String) {
    COVERAGE_HOLE("Coverage Hole (จุดอับสัญญาณ)"),
    PILOT_POLLUTION_INTERFERENCE("Pilot Pollution / High Interference (สัญญาณรบกวนสูง)"),
    OVERSHOOTING("Overshooting Cell (สัญญาณข้ามพื้นที่)"),
    WEAK_DOMINANCE("Weak Dominance (ขาดเซลล์นำโด่ง)")
}

enum class ProblemSeverity(val label: String) {
    HIGH("High"),
    MEDIUM("Medium"),
    LOW("Low")
}

data class RfProblemArea(
    val type: RfProblemType,
    val severity: ProblemSeverity,
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long,
    val title: String,
    val description: String,
    val metricSummary: String
)

enum class MobilityEventType(val label: String) {
    HANDOVER("Normal Handover"),
    PING_PONG_HO("Ping-Pong Handover (<30s)"),
    MISSED_HO("Missed Handover (Margin >= 6dB)")
}

data class MobilityEvent(
    val type: MobilityEventType,
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long,
    val sourcePci: Int?,
    val targetPci: Int?,
    val details: String
)

data class CdfDistribution(
    val p10: Double,
    val p50: Double,
    val p90: Double,
    val min: Double,
    val max: Double,
    val avg: Double,
    val points: List<Pair<Double, Double>> // (value, percentile 0..100)
)

data class RfSessionAnalytics(
    val sessionId: Long,
    val sessionName: String,
    val totalSamples: Int,
    val durationSeconds: Long,
    val distanceMeters: Double,
    val rsrpCdf: CdfDistribution?,
    val rsrqCdf: CdfDistribution?,
    val sinrCdf: CdfDistribution?,
    val ratDistribution: Map<RatType, Int>,
    val bandDistribution: Map<String, Int>,
    val problemAreas: List<RfProblemArea>,
    val mobilityEvents: List<MobilityEvent>,
    val routeHealthScore: Int,
    val scoreGrade: String,
    val dualSimSummary: String? = null
)
