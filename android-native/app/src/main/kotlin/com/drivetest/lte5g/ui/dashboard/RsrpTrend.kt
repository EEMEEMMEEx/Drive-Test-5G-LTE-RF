package com.drivetest.lte5g.ui.dashboard

import com.drivetest.lte5g.model.CellMetrics
import com.drivetest.lte5g.model.DriveTestSample

/**
 * RSRP trend series for the dashboard real-time chart.
 * [sim1] and [sim2] are aligned per sample (same index = same sample);
 * a null entry means that SIM had no RSRP value in that sample.
 */
data class RsrpTrendSeries(
    val sim1: List<Int?>,
    val sim2: List<Int?>
)

/** Serving RSRP from a metrics snapshot: NR SS-RSRP preferred, LTE RSRP as fallback. */
fun rsrpOf(metrics: CellMetrics?): Int? = metrics?.nr?.ssRsrp ?: metrics?.lte?.rsrp

/** Build a per-sample RSRP trend series from the last [window] samples. */
fun rsrpTrendSeries(samples: List<DriveTestSample>, window: Int = 60): RsrpTrendSeries {
    val recent = samples.takeLast(window)
    return RsrpTrendSeries(
        sim1 = recent.map { rsrpOf(it.metrics) },
        sim2 = recent.map { rsrpOf(it.metricsSim2) }
    )
}

/**
 * Normalize an RSRP value to 0f..1f for chart drawing.
 * [minDbm] (weakest) maps to 1f (bottom of the canvas) and [maxDbm] (strongest)
 * maps to 0f (top). Values outside the range are clamped.
 */
fun trendNormY(rsrp: Int, minDbm: Int = -130, maxDbm: Int = -60): Float {
    val clamped = rsrp.coerceIn(minDbm, maxDbm)
    return 1f - (clamped - minDbm).toFloat() / (maxDbm - minDbm).toFloat()
}
