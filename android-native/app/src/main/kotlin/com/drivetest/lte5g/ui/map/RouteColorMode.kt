package com.drivetest.lte5g.ui.map

import com.drivetest.lte5g.model.CellMetrics
import com.drivetest.lte5g.model.DriveTestSample
import com.drivetest.lte5g.model.DriveTestSession
import com.drivetest.lte5g.model.RatType

/**
 * Route Map color modes, mirroring the 5 modes of the web Route Inspector.
 * Pure Kotlin (no Compose dependency) so the tier/legend logic stays unit-testable.
 */
enum class RouteColorMode(val label: String, val legendTitle: String) {
    RSRP("RSRP", "RSRP SIGNAL LEVEL (dBm)"),
    RSRQ("RSRQ", "RSRQ QUALITY (dB)"),
    SINR("SINR", "SINR NOISE RATIO (dB)"),
    TECHNOLOGY("TECH", "RADIO ACCESS TECHNOLOGY"),
    SPEED("SPEED", "VEHICLE SPEED (km/h)")
}

/** Radio access technology buckets used by [RouteColorMode.TECHNOLOGY]. */
enum class RatCategory(val legendKey: Int, val label: String) {
    NR_SA(1, "5G SA"),
    NR_NSA(2, "5G NSA"),
    LTE(3, "LTE"),
    UNKNOWN(4, "UNKNOWN")
}

/**
 * Resolves the RF metrics of a sample for SIM slot 1 or slot 2.
 * Slot 2 returns null when the session was recorded without dual-SIM logging.
 */
fun metricsForSim(sample: DriveTestSample, simIndex: Int): CellMetrics? =
    if (simIndex == 2) sample.metricsSim2 else sample.metrics

fun rsrpOf(metrics: CellMetrics?): Int? = metrics?.nr?.ssRsrp ?: metrics?.lte?.rsrp

fun rsrqOf(metrics: CellMetrics?): Int? = metrics?.nr?.ssRsrq ?: metrics?.lte?.rsrq

fun sinrOf(metrics: CellMetrics?): Double? = metrics?.nr?.ssSinr ?: metrics?.lte?.rssnr

/**
 * SIM 2 live gauge values. [preferNr] mirrors the dashboard's NR/LTE title rule:
 * when the session is locked to or camped on NR, NR metrics take precedence.
 */
fun sim2RsrpOf(metrics: CellMetrics?, preferNr: Boolean): Int? =
    if (preferNr) metrics?.nr?.ssRsrp ?: metrics?.lte?.rsrp else metrics?.lte?.rsrp

fun sim2RsrqOf(metrics: CellMetrics?, preferNr: Boolean): Int? =
    if (preferNr) metrics?.nr?.ssRsrq ?: metrics?.lte?.rsrq else metrics?.lte?.rsrq

fun sim2SinrOf(metrics: CellMetrics?, preferNr: Boolean): Double? =
    if (preferNr) metrics?.nr?.ssSinr ?: metrics?.lte?.rssnr else metrics?.lte?.rssnr

/** True when SIM 2 is camped on NR (SA or NSA). */
fun isNrMetrics(metrics: CellMetrics?): Boolean =
    metrics?.nr != null || metrics?.ratType == RatType.NR_NSA || metrics?.ratType == RatType.NR_SA

/** Derives the RAT bucket from the metrics payload (same rule as the KMZ exporter). */
fun ratCategoryOf(metrics: CellMetrics?): RatCategory {
    if (metrics == null) return RatCategory.UNKNOWN
    val nr = metrics.nr
    return when {
        nr != null -> if (nr.isNsa) RatCategory.NR_NSA else RatCategory.NR_SA
        metrics.lte != null -> RatCategory.LTE
        else -> RatCategory.UNKNOWN
    }
}

/** RSRP quality tier 1..5, boundaries identical to the dashboard gauge. */
fun rsrpTier(rsrp: Int): Int = when {
    rsrp >= -85 -> 1
    rsrp >= -95 -> 2
    rsrp >= -105 -> 3
    rsrp >= -115 -> 4
    else -> 5
}

/** RSRQ quality tier, boundaries identical to the dashboard gauge (1, 2, 3, 5). */
fun rsrqTier(rsrq: Int): Int = when {
    rsrq >= -10 -> 1
    rsrq >= -15 -> 2
    rsrq >= -19 -> 3
    else -> 5
}

/** SINR quality tier 1..5, boundaries identical to the dashboard gauge. */
fun sinrTier(sinr: Double): Int = when {
    sinr >= 20.0 -> 1
    sinr >= 13.0 -> 2
    sinr >= 5.0 -> 3
    sinr >= 0.0 -> 4
    else -> 5
}

/** Speed tier 1..5: slower to faster. Speed is informational, not a quality grade. */
fun speedTier(speedKmh: Double): Int = when {
    speedKmh < 20.0 -> 1
    speedKmh < 40.0 -> 2
    speedKmh < 60.0 -> 3
    speedKmh < 80.0 -> 4
    else -> 5
}

/**
 * Legend key for a sample under the given mode, or null when the metric is missing.
 * Quality modes return a 1..5 tier; [RouteColorMode.TECHNOLOGY] returns
 * [RatCategory.legendKey].
 */
fun legendKeyFor(sample: DriveTestSample, mode: RouteColorMode, simIndex: Int): Int? {
    val metrics = metricsForSim(sample, simIndex)
    return when (mode) {
        RouteColorMode.RSRP -> rsrpOf(metrics)?.let(::rsrpTier)
        RouteColorMode.RSRQ -> rsrqOf(metrics)?.let(::rsrqTier)
        RouteColorMode.SINR -> sinrOf(metrics)?.let(::sinrTier)
        RouteColorMode.TECHNOLOGY -> ratCategoryOf(metrics).legendKey
        RouteColorMode.SPEED -> speedTier(sample.speedKmh.toDouble())
    }
}

/** Ordered legend rows (key to label) for the given mode. */
fun legendEntries(mode: RouteColorMode): List<Pair<Int, String>> = when (mode) {
    RouteColorMode.RSRP -> listOf(
        1 to "≥ -85 dBm (Excellent)",
        2 to "-85 to -95 dBm (Good)",
        3 to "-95 to -105 dBm (Fair)",
        4 to "-105 to -115 dBm (Poor)",
        5 to "< -115 dBm (Bad)"
    )
    RouteColorMode.RSRQ -> listOf(
        1 to "≥ -10 dB (Excellent)",
        2 to "-10 to -15 dB (Good)",
        3 to "-15 to -19 dB (Fair)",
        5 to "< -19 dB (Poor)"
    )
    RouteColorMode.SINR -> listOf(
        1 to "≥ 20 dB (Excellent)",
        2 to "13 to 20 dB (Good)",
        3 to "5 to 13 dB (Fair)",
        4 to "0 to 5 dB (Poor)",
        5 to "< 0 dB (Bad)"
    )
    RouteColorMode.TECHNOLOGY -> RatCategory.entries
        .sortedBy { it.legendKey }
        .map { it.legendKey to it.label }
    RouteColorMode.SPEED -> listOf(
        1 to "< 20 km/h (Idle)",
        2 to "20 to 40 km/h (Urban)",
        3 to "40 to 60 km/h (Suburban)",
        4 to "60 to 80 km/h (Arterial)",
        5 to "≥ 80 km/h (Highway)"
    )
}


// ---- Dual-SIM benchmark comparison -----------------------------------------

/**
 * A single RSRP/SINR row of a dual-SIM session summary, resolved for display.
 */
data class DualSimBenchmarkComparison(
    val sim1Label: String,
    val sim2Label: String,
    val sim1Rsrp: Int?,
    val sim2Rsrp: Int?,
    val rsrpDeltaDb: Int?,
    val sim1Sinr: Double?,
    val sim2Sinr: Double?,
    val sinrDeltaDb: Double?,
    /** Positive when SIM 1 is stronger on RSRP, negative when SIM 2 is, null when tied or missing. */
    val winner: Int?
) {
    /** True when at least one side carries RSRP or SINR data. */
    val hasData: Boolean
        get() = sim1Rsrp != null || sim2Rsrp != null || sim1Sinr != null || sim2Sinr != null
}

/**
 * Builds the SIM 1 vs SIM 2 summary for a session. Delta is null when either side
 * is missing; [DualSimBenchmarkComparison.winner] is null on a tie or missing data.
 */
fun dualSimBenchmarkComparison(session: DriveTestSession): DualSimBenchmarkComparison {
    val sim1Rsrp = session.avgRsrp
    val sim2Rsrp = session.avgRsrpSim2
    val sim1Sinr = session.avgSinr
    val sim2Sinr = session.avgSinrSim2
    val rsrpDeltaDb = if (sim1Rsrp != null && sim2Rsrp != null) sim1Rsrp - sim2Rsrp else null
    val sinrDeltaDb = if (sim1Sinr != null && sim2Sinr != null) sim1Sinr - sim2Sinr else null
    val winner = when {
        rsrpDeltaDb == null -> null
        rsrpDeltaDb > 0 -> 1
        rsrpDeltaDb < 0 -> 2
        else -> null
    }
    return DualSimBenchmarkComparison(
        sim1Label = session.simDisplayName?.ifBlank { null } ?: "SIM 1",
        sim2Label = session.sim2DisplayName?.ifBlank { null } ?: "SIM 2",
        sim1Rsrp = sim1Rsrp,
        sim2Rsrp = sim2Rsrp,
        rsrpDeltaDb = rsrpDeltaDb,
        sim1Sinr = sim1Sinr,
        sim2Sinr = sim2Sinr,
        sinrDeltaDb = sinrDeltaDb,
        winner = winner
    )
}


// ---- Route statistics -------------------------------------------------------

/** Aggregate route statistics for the map stats chips. */
data class RouteStats(
    val distanceMeters: Double,
    val durationMs: Long,
    val avgRsrp: Int?
)

/** Great-circle distance in meters (WGS-84 sphere, R = 6371000 m). */
fun haversineDistanceMeters(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Double {
    val r = 6371000.0
    val dLat = Math.toRadians(toLat - fromLat)
    val dLon = Math.toRadians(toLon - fromLon)
    val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(fromLat)) * Math.cos(Math.toRadians(toLat)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
    return 2 * r * Math.asin(Math.sqrt(a))
}

/** "850 m" below 1 km, "12.3 km" above. */
fun formatDistance(meters: Double): String =
    if (meters < 1000.0) "${meters.toLong()} m" else String.format(java.util.Locale.US, "%.1f km", meters / 1000.0)

/** "1h 05m" with hours, otherwise "5m 32s"; clamped at zero. */
fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "${hours}h ${String.format(java.util.Locale.US, "%02d", minutes)}m"
    else "${minutes}m ${String.format(java.util.Locale.US, "%02d", seconds)}s"
}

/**
 * Distance (sum of segment haversine), duration (first to last timestamp) and
 * average SIM 1 RSRP for a route. Null for an empty route.
 */
fun routeStats(samples: List<DriveTestSample>): RouteStats? {
    if (samples.isEmpty()) return null
    var distance = 0.0
    for (i in 0 until samples.size - 1) {
        val a = samples[i]
        val b = samples[i + 1]
        distance += haversineDistanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)
    }
    val durationMs = samples.last().timestamp - samples.first().timestamp
    val rsrps = samples.mapNotNull { rsrpOf(metricsForSim(it, 1)) }
    return RouteStats(distance, durationMs, if (rsrps.isEmpty()) null else rsrps.average().toInt())
}
