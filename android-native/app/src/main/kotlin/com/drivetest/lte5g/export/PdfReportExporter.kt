package com.drivetest.lte5g.export

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.drivetest.lte5g.data.analytics.MobilityEventType
import com.drivetest.lte5g.data.analytics.RfAnalyticsEngine
import com.drivetest.lte5g.data.analytics.RfProblemType
import com.drivetest.lte5g.data.analytics.RfSessionAnalytics
import com.drivetest.lte5g.model.DriveTestSession
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PdfReportExporter {

    private const val PAGE_WIDTH = 595  // A4 standard points (72 dpi)
    private const val PAGE_HEIGHT = 842 // A4 standard points (72 dpi)
    private const val MARGIN_X = 36f
    private const val CONTENT_WIDTH = PAGE_WIDTH - (MARGIN_X * 2)

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    private val timeOnlyFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun exportPdfReport(
        session: DriveTestSession,
        analytics: RfSessionAnalytics,
        aiReport: String? = null,
        outputStream: OutputStream
    ) {
        val document = PdfDocument()

        // ----------------------------------------------------
        // PAGE 1: Executive Summary & Site Acceptance Scorecard
        // ----------------------------------------------------
        val pageInfo1 = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 1).create()
        val page1 = document.startPage(pageInfo1)
        renderPage1(page1.canvas, session, analytics)
        document.finishPage(page1)

        // ----------------------------------------------------
        // PAGE 2: Vector Map Snapshot, CDF & Engineering Diagnostics
        // ----------------------------------------------------
        val pageInfo2 = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 2).create()
        val page2 = document.startPage(pageInfo2)
        renderPage2(page2.canvas, session, analytics, aiReport)
        document.finishPage(page2)

        document.writeTo(outputStream)
        document.close()
    }

    // =========================================================================
    // PAGE 1 RENDERING
    // =========================================================================

    private fun renderPage1(canvas: Canvas, session: DriveTestSession, analytics: RfSessionAnalytics) {
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

        var currentY = 32f

        // 1. BRAND HEADER BAR
        fillPaint.color = Color.rgb(15, 23, 42) // Dark Slate #0F172A
        canvas.drawRoundRect(RectF(MARGIN_X, currentY, MARGIN_X + CONTENT_WIDTH, currentY + 54f), 8f, 8f, fillPaint)

        // Header Title
        textPaint.color = Color.WHITE
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textPaint.textSize = 14f
        canvas.drawText("RF FIELD SUITE — SITE ACCEPTANCE & AUDIT REPORT", MARGIN_X + 14f, currentY + 22f, textPaint)

        textPaint.textSize = 9.5f
        textPaint.color = Color.rgb(148, 163, 184) // Slate 400
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        canvas.drawText("5G NR / LTE Cellular Telephony & Quality Diagnostics · Form Ref: FORTH-DOPA-RF-1.11", MARGIN_X + 14f, currentY + 40f, textPaint)

        // Report ID Badge
        textPaint.color = Color.rgb(56, 189, 248) // Sky 400
        textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textPaint.textSize = 9.5f
        val reportIdStr = "DOC-RF-${session.id}"
        val idWidth = textPaint.measureText(reportIdStr)
        canvas.drawText(reportIdStr, MARGIN_X + CONTENT_WIDTH - idWidth - 14f, currentY + 32f, textPaint)

        currentY += 66f

        // 2. SESSION METADATA CARD (2-Column Grid)
        fillPaint.color = Color.rgb(248, 250, 252) // Light Gray #F8FAFC
        strokePaint.color = Color.rgb(226, 232, 240) // Slate 200
        strokePaint.strokeWidth = 1f
        canvas.drawRoundRect(RectF(MARGIN_X, currentY, MARGIN_X + CONTENT_WIDTH, currentY + 68f), 6f, 6f, fillPaint)
        canvas.drawRoundRect(RectF(MARGIN_X, currentY, MARGIN_X + CONTENT_WIDTH, currentY + 68f), 6f, 6f, strokePaint)

        val col1X = MARGIN_X + 12f
        val col2X = MARGIN_X + (CONTENT_WIDTH / 2f) + 10f

        textPaint.color = Color.rgb(71, 85, 105)
        textPaint.textSize = 8.5f
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)

        // Row 1
        canvas.drawText("Campaign Name: ", col1X, currentY + 18f, textPaint)
        canvas.drawText("Test Date & Time: ", col2X, currentY + 18f, textPaint)

        textPaint.color = Color.rgb(15, 23, 42)
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText(session.name, col1X + 75f, currentY + 18f, textPaint)
        canvas.drawText(dateFormat.format(Date(session.startTime)), col2X + 85f, currentY + 18f, textPaint)

        // Row 2
        textPaint.color = Color.rgb(71, 85, 105)
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        canvas.drawText("Total Distance: ", col1X, currentY + 36f, textPaint)
        canvas.drawText("Survey Duration: ", col2X, currentY + 36f, textPaint)

        textPaint.color = Color.rgb(15, 23, 42)
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        val distKm = String.format(Locale.US, "%.2f km", analytics.distanceMeters / 1000.0)
        canvas.drawText(distKm, col1X + 75f, currentY + 36f, textPaint)
        canvas.drawText(formatDuration(analytics.durationSeconds), col2X + 85f, currentY + 36f, textPaint)

        // Row 3
        textPaint.color = Color.rgb(71, 85, 105)
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        canvas.drawText("Sample Count: ", col1X, currentY + 54f, textPaint)
        canvas.drawText("Primary Operator: ", col2X, currentY + 54f, textPaint)

        textPaint.color = Color.rgb(15, 23, 42)
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText("${analytics.totalSamples} pts (${session.cadence.label})", col1X + 75f, currentY + 54f, textPaint)
        val opStr = session.samples.firstOrNull()?.metrics?.operatorAlpha ?: "Cellular Network"
        canvas.drawText(opStr, col2X + 85f, currentY + 54f, textPaint)

        currentY += 80f

        // 3. SECTION 1: EXECUTIVE KPI ACCEPTANCE SCORECARD
        drawSectionTitle(canvas, "1. Executive KPI Acceptance Scorecard & Health Grade", currentY)
        currentY += 18f

        // Health Score & Status Badge banner
        val score = analytics.routeHealthScore
        val isPass = score >= 70
        val statusColor = if (isPass) Color.rgb(22, 163, 74) else Color.rgb(220, 38, 38)

        fillPaint.color = if (isPass) Color.rgb(240, 253, 244) else Color.rgb(254, 242, 242)
        strokePaint.color = statusColor
        canvas.drawRoundRect(RectF(MARGIN_X, currentY, MARGIN_X + CONTENT_WIDTH, currentY + 44f), 6f, 6f, fillPaint)
        canvas.drawRoundRect(RectF(MARGIN_X, currentY, MARGIN_X + CONTENT_WIDTH, currentY + 44f), 6f, 6f, strokePaint)

        textPaint.color = statusColor
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textPaint.textSize = 22f
        canvas.drawText("$score / 100", MARGIN_X + 16f, currentY + 30f, textPaint)

        textPaint.textSize = 10f
        textPaint.color = Color.rgb(51, 65, 85)
        canvas.drawText("Route Quality Grade: ${analytics.scoreGrade}", MARGIN_X + 115f, currentY + 22f, textPaint)
        textPaint.textSize = 8.5f
        textPaint.color = Color.rgb(100, 116, 139)
        canvas.drawText("Standard 3GPP Cellular Acceptance Criteria (SLA Minimum >= 70 pts)", MARGIN_X + 115f, currentY + 34f, textPaint)

        // Status Pill Right
        fillPaint.color = statusColor
        val badgeRect = RectF(MARGIN_X + CONTENT_WIDTH - 95f, currentY + 10f, MARGIN_X + CONTENT_WIDTH - 12f, currentY + 34f)
        canvas.drawRoundRect(badgeRect, 4f, 4f, fillPaint)
        textPaint.color = Color.WHITE
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textPaint.textSize = 10.5f
        val verdictText = if (isPass) "ACCEPT: PASS" else "ACCEPT: FAIL"
        val vWidth = textPaint.measureText(verdictText)
        canvas.drawText(verdictText, badgeRect.left + ((badgeRect.width() - vWidth) / 2f), currentY + 25f, textPaint)

        currentY += 52f

        // KPI Compliance Table
        val rsrpList = session.samples.mapNotNull { it.metrics.nr?.ssRsrp ?: it.metrics.lte?.rsrp }
        val sinrList = session.samples.mapNotNull { it.metrics.nr?.ssSinr ?: it.metrics.lte?.rssnr }
        val pingPongCount = analytics.mobilityEvents.count { it.type == MobilityEventType.PING_PONG_HO }
        val missedHoCount = analytics.mobilityEvents.count { it.type == MobilityEventType.MISSED_HO }

        val rsrpPassCount = rsrpList.count { it >= -95 }
        val rsrpPct = if (rsrpList.isNotEmpty()) (rsrpPassCount.toFloat() / rsrpList.size) * 100f else 0f
        val sinrPassCount = sinrList.count { it >= 0.0 }
        val sinrPct = if (sinrList.isNotEmpty()) (sinrPassCount.toFloat() / sinrList.size) * 100f else 0f

        val kpis = listOf(
            Triple("Radio Coverage RSRP", "Target: >= 90% samples >= -95 dBm", Pair(String.format(Locale.US, "%.1f%%", rsrpPct), rsrpPct >= 90f)),
            Triple("Signal Cleanliness (SINR)", "Target: >= 95% samples >= 0 dB", Pair(String.format(Locale.US, "%.1f%%", sinrPct), sinrPct >= 95f)),
            Triple("Handover Stability", "Target: Ping-Pong count <= 3 events", Pair("$pingPongCount events", pingPongCount <= 3)),
            Triple("Handover Execution", "Target: Missed Handover = 0", Pair("$missedHoCount events", missedHoCount == 0))
        )

        currentY = drawKpiTable(canvas, kpis, currentY)
        currentY += 16f

        // 4. SECTION 2: RADIO SIGNAL STATISTICS TABLE
        drawSectionTitle(canvas, "2. Radio Frequency Signal Statistics (RF Distribution)", currentY)
        currentY += 18f

        currentY = drawSignalStatsTable(canvas, analytics, currentY)
        currentY += 16f

        // 5. SECTION 3: COVERAGE TIER DISTRIBUTION (Stacked Color Bar)
        drawSectionTitle(canvas, "3. Coverage Quality Tier Distribution (RSRP Tiers)", currentY)
        currentY += 16f

        currentY = drawCoverageDistributionBar(canvas, rsrpList, currentY)
        currentY += 24f

        // Dual-SIM Benchmark banner if present
        analytics.dualSimSummary?.let { dual ->
            fillPaint.color = Color.rgb(238, 242, 255)
            strokePaint.color = Color.rgb(99, 102, 241)
            canvas.drawRoundRect(RectF(MARGIN_X, currentY, MARGIN_X + CONTENT_WIDTH, currentY + 28f), 4f, 4f, fillPaint)
            canvas.drawRoundRect(RectF(MARGIN_X, currentY, MARGIN_X + CONTENT_WIDTH, currentY + 28f), 4f, 4f, strokePaint)

            textPaint.color = Color.rgb(67, 56, 202)
            textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textPaint.textSize = 8.5f
            canvas.drawText("DUAL-SIM BENCHMARK COMPARISON:", MARGIN_X + 10f, currentY + 18f, textPaint)
            textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            canvas.drawText(dual, MARGIN_X + 175f, currentY + 18f, textPaint)
            currentY += 36f
        }

        // FOOTER PAGE 1
        drawFooter(canvas, 1, 2)
    }

    // =========================================================================
    // PAGE 2 RENDERING
    // =========================================================================

    private fun renderPage2(canvas: Canvas, session: DriveTestSession, analytics: RfSessionAnalytics, aiReport: String?) {
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

        var currentY = 32f

        // Header Compact
        fillPaint.color = Color.rgb(15, 23, 42)
        canvas.drawRoundRect(RectF(MARGIN_X, currentY, MARGIN_X + CONTENT_WIDTH, currentY + 36f), 6f, 6f, fillPaint)

        textPaint.color = Color.WHITE
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textPaint.textSize = 12f
        canvas.drawText("RF ROUTE TRAJECTORY & ENGINEERING DIAGNOSTICS", MARGIN_X + 12f, currentY + 22f, textPaint)

        textPaint.textSize = 8.5f
        textPaint.color = Color.rgb(148, 163, 184)
        val pageSub = "Route Trajectory Map · CDF Percentiles · Defect Log"
        val subW = textPaint.measureText(pageSub)
        canvas.drawText(pageSub, MARGIN_X + CONTENT_WIDTH - subW - 12f, currentY + 22f, textPaint)

        currentY += 46f

        // 1. SECTION 4: ROUTE MAP VECTOR SNAPSHOT
        drawSectionTitle(canvas, "4. Route Trajectory Map Snapshot (Vector GPS Plotted)", currentY)
        currentY += 14f

        val mapHeight = 170f
        renderRouteMapVector(canvas, session.samples, RectF(MARGIN_X, currentY, MARGIN_X + CONTENT_WIDTH, currentY + mapHeight))
        currentY += mapHeight + 16f

        // 2. SECTION 5: CDF DISTRIBUTION & TOP DEFECTS (Side-by-Side)
        drawSectionTitle(canvas, "5. Cumulative Distribution Function (CDF) & Critical Weak Spots", currentY)
        currentY += 14f

        val cdfWidth = (CONTENT_WIDTH * 0.42f)
        val defectsWidth = CONTENT_WIDTH - cdfWidth - 12f

        // Render CDF Curve
        analytics.rsrpCdf?.let { cdf ->
            renderCdfCanvas(canvas, cdf, RectF(MARGIN_X, currentY, MARGIN_X + cdfWidth, currentY + 125f))
        }

        // Render Defects Table
        renderDefectsTable(canvas, analytics, RectF(MARGIN_X + cdfWidth + 12f, currentY, MARGIN_X + CONTENT_WIDTH, currentY + 125f))

        currentY += 141f

        // 3. SECTION 6: GEMINI AI RF OPTIMIZATION ADVISOR REPORT
        drawSectionTitle(canvas, "6. Gemini AI RF Optimization Advisor (Virtual Senior Engineer)", currentY)
        currentY += 14f

        val aiCardHeight = 220f
        fillPaint.color = Color.rgb(240, 249, 255) // Sky 50 #F0F9FF
        strokePaint.color = Color.rgb(2, 132, 199)  // Sky 600 #0284C7
        strokePaint.strokeWidth = 1f
        canvas.drawRoundRect(RectF(MARGIN_X, currentY, MARGIN_X + CONTENT_WIDTH, currentY + aiCardHeight), 6f, 6f, fillPaint)
        canvas.drawRoundRect(RectF(MARGIN_X, currentY, MARGIN_X + CONTENT_WIDTH, currentY + aiCardHeight), 6f, 6f, strokePaint)

        // Gemini AI Badge
        textPaint.color = Color.rgb(2, 132, 199)
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textPaint.textSize = 9.5f
        canvas.drawText("✦ Gemini 3.5 Flash Diagnostic Recommendations:", MARGIN_X + 12f, currentY + 18f, textPaint)

        val reportContent = aiReport?.trim()?.ifBlank { null }
            ?: "ยังไม่มีการวิเคราะห์ด้วย AI สำหรับเซสชันนี้ (แนะนำให้เปิดแอปในหน้า AI Analytics และกดปุ่ม 'วิเคราะห์ด้วย Gemini AI' เพื่อรับคำแนะนำการปรับแต่ง Antenna Tilt, Power และ Handover Parameter อย่างละเอียด)"

        textPaint.color = Color.rgb(30, 41, 59)
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        textPaint.textSize = 8.5f
        drawMultilineText(canvas, reportContent, MARGIN_X + 12f, currentY + 34f, CONTENT_WIDTH - 24f, 12, textPaint)

        // FOOTER PAGE 2
        drawFooter(canvas, 2, 2)
    }

    // =========================================================================
    // HELPER DRAWING METHODS
    // =========================================================================

    private fun drawSectionTitle(canvas: Canvas, title: String, y: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(15, 23, 42)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = 10.5f
        }
        canvas.drawText(title, MARGIN_X, y, paint)

        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(203, 213, 225)
            strokeWidth = 1f
        }
        canvas.drawLine(MARGIN_X, y + 4f, MARGIN_X + CONTENT_WIDTH, y + 4f, linePaint)
    }

    private fun drawKpiTable(
        canvas: Canvas,
        kpis: List<Triple<String, String, Pair<String, Boolean>>>,
        startY: Float
    ): Float {
        var y = startY
        val rowHeight = 22f
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(226, 232, 240)
            strokeWidth = 1f
            style = Paint.Style.STROKE
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8.5f }

        // Header Row
        fillPaint.color = Color.rgb(241, 245, 249)
        canvas.drawRect(MARGIN_X, y, MARGIN_X + CONTENT_WIDTH, y + rowHeight, fillPaint)
        canvas.drawRect(MARGIN_X, y, MARGIN_X + CONTENT_WIDTH, y + rowHeight, strokePaint)

        textPaint.color = Color.rgb(51, 65, 85)
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText("Acceptance KPI Metric", MARGIN_X + 8f, y + 14f, textPaint)
        canvas.drawText("Target Criteria (Benchmark)", MARGIN_X + 170f, y + 14f, textPaint)
        canvas.drawText("Measured Actual", MARGIN_X + 360f, y + 14f, textPaint)
        canvas.drawText("Compliance", MARGIN_X + 450f, y + 14f, textPaint)

        y += rowHeight

        // Data Rows
        kpis.forEachIndexed { i, kpi ->
            fillPaint.color = if (i % 2 == 0) Color.WHITE else Color.rgb(248, 250, 252)
            canvas.drawRect(MARGIN_X, y, MARGIN_X + CONTENT_WIDTH, y + rowHeight, fillPaint)
            canvas.drawRect(MARGIN_X, y, MARGIN_X + CONTENT_WIDTH, y + rowHeight, strokePaint)

            textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textPaint.color = Color.rgb(15, 23, 42)
            canvas.drawText(kpi.first, MARGIN_X + 8f, y + 14f, textPaint)

            textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            textPaint.color = Color.rgb(71, 85, 105)
            canvas.drawText(kpi.second, MARGIN_X + 170f, y + 14f, textPaint)

            textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            canvas.drawText(kpi.third.first, MARGIN_X + 360f, y + 14f, textPaint)

            // Pass/Fail Badge
            val passed = kpi.third.second
            val badgeColor = if (passed) Color.rgb(22, 163, 74) else Color.rgb(220, 38, 38)
            val badgeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = badgeColor }
            val bRect = RectF(MARGIN_X + 450f, y + 4f, MARGIN_X + 505f, y + 18f)
            canvas.drawRoundRect(bRect, 3f, 3f, badgeFill)

            textPaint.color = Color.WHITE
            textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textPaint.textSize = 7.5f
            val txt = if (passed) "PASS" else "FAIL"
            val tw = textPaint.measureText(txt)
            canvas.drawText(txt, bRect.left + ((bRect.width() - tw) / 2f), y + 14f, textPaint)

            y += rowHeight
        }
        return y
    }

    private fun drawSignalStatsTable(canvas: Canvas, analytics: RfSessionAnalytics, startY: Float): Float {
        var y = startY
        val rowHeight = 20f
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(226, 232, 240)
            strokeWidth = 1f
            style = Paint.Style.STROKE
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8.5f }

        // Header
        fillPaint.color = Color.rgb(241, 245, 249)
        canvas.drawRect(MARGIN_X, y, MARGIN_X + CONTENT_WIDTH, y + rowHeight, fillPaint)
        canvas.drawRect(MARGIN_X, y, MARGIN_X + CONTENT_WIDTH, y + rowHeight, strokePaint)

        textPaint.color = Color.rgb(51, 65, 85)
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText("Radio Parameter", MARGIN_X + 8f, y + 13f, textPaint)
        canvas.drawText("Average", MARGIN_X + 170f, y + 13f, textPaint)
        canvas.drawText("P50 (Median)", MARGIN_X + 250f, y + 13f, textPaint)
        canvas.drawText("P10 (Cutoff)", MARGIN_X + 340f, y + 13f, textPaint)
        canvas.drawText("Min / Max", MARGIN_X + 420f, y + 13f, textPaint)

        y += rowHeight

        val rows = listOf(
            Triple("RSRP (Power)", analytics.rsrpCdf, "dBm"),
            Triple("RSRQ (Quality)", analytics.rsrqCdf, "dB"),
            Triple("SINR (Interference)", analytics.sinrCdf, "dB")
        )

        rows.forEachIndexed { i, r ->
            fillPaint.color = if (i % 2 == 0) Color.WHITE else Color.rgb(248, 250, 252)
            canvas.drawRect(MARGIN_X, y, MARGIN_X + CONTENT_WIDTH, y + rowHeight, fillPaint)
            canvas.drawRect(MARGIN_X, y, MARGIN_X + CONTENT_WIDTH, y + rowHeight, strokePaint)

            textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textPaint.color = Color.rgb(15, 23, 42)
            canvas.drawText(r.first, MARGIN_X + 8f, y + 13f, textPaint)

            val cdf = r.second
            textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            textPaint.color = Color.rgb(51, 65, 85)
            val avgStr = cdf?.let { String.format(Locale.US, "%.1f %s", it.avg, r.third) } ?: "-"
            val p50Str = cdf?.let { String.format(Locale.US, "%.1f %s", it.p50, r.third) } ?: "-"
            val p10Str = cdf?.let { String.format(Locale.US, "%.1f %s", it.p10, r.third) } ?: "-"
            val minMaxStr = cdf?.let { String.format(Locale.US, "%.0f / %.0f", it.min, it.max) } ?: "-"

            canvas.drawText(avgStr, MARGIN_X + 170f, y + 13f, textPaint)
            canvas.drawText(p50Str, MARGIN_X + 250f, y + 13f, textPaint)
            canvas.drawText(p10Str, MARGIN_X + 340f, y + 13f, textPaint)
            canvas.drawText(minMaxStr, MARGIN_X + 420f, y + 13f, textPaint)

            y += rowHeight
        }

        return y
    }

    private fun drawCoverageDistributionBar(canvas: Canvas, rsrpList: List<Int>, startY: Float): Float {
        var y = startY
        val total = rsrpList.size.coerceAtLeast(1)
        val excellent = rsrpList.count { it >= -85 }
        val good = rsrpList.count { it in -95..-86 }
        val fair = rsrpList.count { it in -105..-96 }
        val poor = rsrpList.count { it < -105 }

        val pExc = (excellent.toFloat() / total) * 100f
        val pGood = (good.toFloat() / total) * 100f
        val pFair = (fair.toFloat() / total) * 100f
        val pPoor = (poor.toFloat() / total) * 100f

        val barHeight = 16f
        var curX = MARGIN_X

        val tiers = listOf(
            Pair(pExc, Color.rgb(16, 185, 129)),  // Green #10B981
            Pair(pGood, Color.rgb(34, 197, 94)),  // Light Green #22C55E
            Pair(pFair, Color.rgb(234, 179, 8)),   // Yellow #EAB308
            Pair(pPoor, Color.rgb(239, 68, 68))    // Red #EF4444
        )

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        tiers.forEach { (pct, color) ->
            val w = (pct / 100f) * CONTENT_WIDTH
            if (w > 0f) {
                fillPaint.color = color
                canvas.drawRect(curX, y, curX + w, y + barHeight, fillPaint)
                curX += w
            }
        }

        y += barHeight + 10f

        // Legend row below bar
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8f }
        val legendItems = listOf(
            Triple(">= -85 dBm (Exc)", String.format(Locale.US, "%.1f%% (%d)", pExc, excellent), Color.rgb(16, 185, 129)),
            Triple("-85 to -95 (Good)", String.format(Locale.US, "%.1f%% (%d)", pGood, good), Color.rgb(34, 197, 94)),
            Triple("-95 to -105 (Fair)", String.format(Locale.US, "%.1f%% (%d)", pFair, fair), Color.rgb(234, 179, 8)),
            Triple("< -105 dBm (Poor)", String.format(Locale.US, "%.1f%% (%d)", pPoor, poor), Color.rgb(239, 68, 68))
        )

        val colW = CONTENT_WIDTH / 4f
        legendItems.forEachIndexed { i, item ->
            val lx = MARGIN_X + (i * colW)
            fillPaint.color = item.third
            canvas.drawCircle(lx + 4f, y + 4f, 4f, fillPaint)

            textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textPaint.color = Color.rgb(51, 65, 85)
            canvas.drawText(item.first, lx + 12f, y + 4f, textPaint)

            textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            textPaint.color = Color.rgb(100, 116, 139)
            canvas.drawText(item.second, lx + 12f, y + 14f, textPaint)
        }

        return y + 18f
    }

    private fun renderRouteMapVector(canvas: Canvas, samples: List<com.drivetest.lte5g.model.DriveTestSample>, rect: RectF) {
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(11, 19, 41) }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(30, 41, 59)
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }

        // Draw Map Box
        canvas.drawRoundRect(rect, 8f, 8f, bgPaint)
        canvas.drawRoundRect(rect, 8f, 8f, strokePaint)

        if (samples.isEmpty()) return

        // Compute Bounding Box
        var minLat = Double.MAX_VALUE
        var maxLat = -Double.MAX_VALUE
        var minLon = Double.MAX_VALUE
        var maxLon = -Double.MAX_VALUE

        samples.forEach { s ->
            if (s.latitude < minLat) minLat = s.latitude
            if (s.latitude > maxLat) maxLat = s.latitude
            if (s.longitude < minLon) minLon = s.longitude
            if (s.longitude > maxLon) maxLon = s.longitude
        }

        val dLat = (maxLat - minLat).coerceAtLeast(0.0005)
        val dLon = (maxLon - minLon).coerceAtLeast(0.0005)

        val padX = 24f
        val padY = 20f
        val plotW = rect.width() - (padX * 2)
        val plotH = rect.height() - (padY * 2)

        // Draw coordinate grid lines
        val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(30, 41, 59)
            pathEffect = DashPathEffect(floatArrayOf(4f, 4f), 0f)
            strokeWidth = 1f
            style = Paint.Style.STROKE
        }
        canvas.drawLine(rect.left, rect.centerY(), rect.right, rect.centerY(), gridPaint)
        canvas.drawLine(rect.centerX(), rect.top, rect.centerX(), rect.bottom, gridPaint)

        // Draw Route Polyline
        val trackPath = Path()
        val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        samples.forEachIndexed { i, s ->
            val px = rect.left + padX + (((s.longitude - minLon) / dLon).toFloat() * plotW)
            val py = rect.bottom - padY - (((s.latitude - minLat) / dLat).toFloat() * plotH)

            if (i == 0) trackPath.moveTo(px, py) else trackPath.lineTo(px, py)
        }

        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(100, 116, 139)
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
            strokeCap = Paint.Cap.ROUND
            alpha = 150
        }
        canvas.drawPath(trackPath, linePaint)

        // Plot colored survey points
        samples.forEach { s ->
            val px = rect.left + padX + (((s.longitude - minLon) / dLon).toFloat() * plotW)
            val py = rect.bottom - padY - (((s.latitude - minLat) / dLat).toFloat() * plotH)
            val rsrp = s.metrics.nr?.ssRsrp ?: s.metrics.lte?.rsrp ?: -120

            pointPaint.color = getTierColor(rsrp)
            canvas.drawCircle(px, py, 2.8f, pointPaint)
        }

        // START & END Markers
        val s0 = samples.first()
        val sEnd = samples.last()
        val startX = rect.left + padX + (((s0.longitude - minLon) / dLon).toFloat() * plotW)
        val startY = rect.bottom - padY - (((s0.latitude - minLat) / dLat).toFloat() * plotH)
        val endX = rect.left + padX + (((sEnd.longitude - minLon) / dLon).toFloat() * plotW)
        val endY = rect.bottom - padY - (((sEnd.latitude - minLat) / dLat).toFloat() * plotH)

        // Start circle (Green)
        pointPaint.color = Color.rgb(34, 197, 94)
        canvas.drawCircle(startX, startY, 5f, pointPaint)
        // End circle (Red)
        pointPaint.color = Color.rgb(239, 68, 68)
        canvas.drawCircle(endX, endY, 5f, pointPaint)

        // Map Legend Bottom Left
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(148, 163, 184)
            textSize = 7.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }
        canvas.drawText("● Start (Green)  ● End (Red)  | GPS Points: ${samples.size}", rect.left + 10f, rect.bottom - 6f, textPaint)
    }

    private fun renderCdfCanvas(canvas: Canvas, cdf: com.drivetest.lte5g.data.analytics.CdfDistribution, rect: RectF) {
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(15, 23, 42) }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(51, 65, 85)
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }
        canvas.drawRoundRect(rect, 6f, 6f, bgPaint)
        canvas.drawRoundRect(rect, 6f, 6f, borderPaint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 8.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText("CDF Curve: RSRP (dBm)", rect.left + 8f, rect.top + 14f, textPaint)

        val plotL = rect.left + 16f
        val plotR = rect.right - 14f
        val plotT = rect.top + 24f
        val plotB = rect.bottom - 16f
        val w = plotR - plotL
        val h = plotB - plotT

        val minV = cdf.min.toFloat()
        val maxV = cdf.max.toFloat()
        val range = (maxV - minV).coerceAtLeast(1f)

        // Grid lines (50%)
        val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(51, 65, 85)
            strokeWidth = 0.75f
            pathEffect = DashPathEffect(floatArrayOf(3f, 3f), 0f)
        }
        canvas.drawLine(plotL, plotT + (h * 0.5f), plotR, plotT + (h * 0.5f), gridPaint)

        // CDF Path
        val path = Path()
        var isFirst = true
        cdf.points.forEach { (v, pct) ->
            val px = plotL + (((v.toFloat() - minV) / range) * w)
            val py = plotB - ((pct.toFloat() / 100f) * h)
            if (isFirst) {
                path.moveTo(px, py)
                isFirst = false
            } else {
                path.lineTo(px, py)
            }
        }

        val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(6, 182, 212) // Cyan 500
            style = Paint.Style.STROKE
            strokeWidth = 2f
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawPath(path, curvePaint)

        // P50 Dot
        val p50X = plotL + (((cdf.p50.toFloat() - minV) / range) * w)
        val p50Y = plotB - (0.5f * h)
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(56, 189, 248) }
        canvas.drawCircle(p50X, p50Y, 3.5f, dotPaint)

        textPaint.textSize = 7f
        textPaint.color = Color.rgb(148, 163, 184)
        canvas.drawText("P50: ${String.format(Locale.US, "%.0f dBm", cdf.p50)}", p50X - 18f, p50Y - 6f, textPaint)
        canvas.drawText("${String.format(Locale.US, "%.0f", cdf.min)}", plotL, rect.bottom - 4f, textPaint)
        canvas.drawText("${String.format(Locale.US, "%.0f dBm", cdf.max)}", plotR - 26f, rect.bottom - 4f, textPaint)
    }

    private fun renderDefectsTable(canvas: Canvas, analytics: RfSessionAnalytics, rect: RectF) {
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(248, 250, 252) }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(226, 232, 240)
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }
        canvas.drawRoundRect(rect, 6f, 6f, fillPaint)
        canvas.drawRoundRect(rect, 6f, 6f, strokePaint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(15, 23, 42)
            textSize = 8.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText("Top Critical Defects & Weak Spots", rect.left + 8f, rect.top + 14f, textPaint)

        val topProblems = analytics.problemAreas.take(4)
        var rowY = rect.top + 28f

        if (topProblems.isEmpty()) {
            textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            textPaint.color = Color.rgb(100, 116, 139)
            textPaint.textSize = 8f
            canvas.drawText("ไม่พบจุดสัญญาณวิกฤต (Coverage Holes / Interference)", rect.left + 8f, rowY + 20f, textPaint)
            return
        }

        topProblems.forEachIndexed { i, p ->
            val dotColor = if (p.type == RfProblemType.COVERAGE_HOLE) Color.rgb(239, 68, 68) else Color.rgb(234, 179, 8)
            val pPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = dotColor }
            canvas.drawCircle(rect.left + 12f, rowY + 5f, 3.5f, pPaint)

            textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textPaint.color = Color.rgb(15, 23, 42)
            textPaint.textSize = 7.5f
            val label = "#${i + 1} ${p.type.name} · ${timeOnlyFormat.format(Date(p.timestamp))}"
            canvas.drawText(label, rect.left + 22f, rowY + 5f, textPaint)

            textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            textPaint.color = Color.rgb(71, 85, 105)
            textPaint.textSize = 7f
            val coords = String.format(Locale.US, "GPS: %.4f, %.4f", p.latitude, p.longitude)
            canvas.drawText(coords, rect.left + 22f, rowY + 14f, textPaint)

            rowY += 20f
        }
    }

    private fun drawFooter(canvas: Canvas, pageNum: Int, totalPages: Int) {
        val y = PAGE_HEIGHT - 22f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(148, 163, 184)
            textSize = 7.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }
        canvas.drawText("Confidential — Generated by Drive Test LTE/5G v1.12.1 Android Native", MARGIN_X, y, paint)

        val pageStr = "Page $pageNum of $totalPages"
        val pw = paint.measureText(pageStr)
        canvas.drawText(pageStr, MARGIN_X + CONTENT_WIDTH - pw, y, paint)
    }

    private fun drawMultilineText(canvas: Canvas, text: String, x: Float, startY: Float, maxWidth: Float, maxLines: Int, paint: Paint) {
        val words = text.replace("\n", " \n ").split(Regex("\\s+"))
        var line = ""
        var currentY = startY
        var linesDrawn = 0
        val lineHeight = paint.textSize * 1.4f

        for (word in words) {
            if (linesDrawn >= maxLines) break

            if (word == "\n") {
                canvas.drawText(line, x, currentY, paint)
                line = ""
                currentY += lineHeight
                linesDrawn++
                continue
            }

            val testLine = if (line.isEmpty()) word else "$line $word"
            val width = paint.measureText(testLine)

            if (width > maxWidth) {
                canvas.drawText(line, x, currentY, paint)
                line = word
                currentY += lineHeight
                linesDrawn++
            } else {
                line = testLine
            }
        }

        if (line.isNotEmpty() && linesDrawn < maxLines) {
            canvas.drawText(line, x, currentY, paint)
        }
    }

    private fun getTierColor(rsrp: Int): Int {
        return when {
            rsrp >= -85 -> Color.rgb(16, 185, 129)
            rsrp >= -95 -> Color.rgb(34, 197, 94)
            rsrp >= -105 -> Color.rgb(234, 179, 8)
            rsrp >= -115 -> Color.rgb(249, 115, 22)
            else -> Color.rgb(239, 68, 68)
        }
    }

    private fun formatDuration(seconds: Long): String {
        val hrs = seconds / 3600
        val mins = (seconds % 3600) / 60
        val secs = seconds % 60
        return if (hrs > 0) String.format(Locale.US, "%02d:%02d:%02d", hrs, mins, secs)
        else String.format(Locale.US, "%02d:%02d", mins, secs)
    }
}
