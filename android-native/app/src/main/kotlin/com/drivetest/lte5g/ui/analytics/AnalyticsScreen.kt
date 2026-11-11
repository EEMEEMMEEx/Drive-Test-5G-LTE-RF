package com.drivetest.lte5g.ui.analytics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drivetest.lte5g.ai.GeminiAdvisorState
import com.drivetest.lte5g.ai.GeminiRfAdvisor
import com.drivetest.lte5g.data.SessionRepository
import com.drivetest.lte5g.data.analytics.*
import com.drivetest.lte5g.model.DriveTestSession
import com.drivetest.lte5g.ui.theme.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyticsScreen(
    modifier: Modifier = Modifier,
    onNavigateToMap: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val activeSession by SessionRepository.activeSession.collectAsStateWithLifecycle()
    val allSessions by SessionRepository.sessions.collectAsStateWithLifecycle()
    val selectedSessionForMap by SessionRepository.selectedSessionForMap.collectAsStateWithLifecycle()
    val geminiState by GeminiRfAdvisor.state.collectAsStateWithLifecycle()

    // Determine target session to analyze (selected session for map -> active session -> first saved session)
    var targetedSessionId by remember {
        mutableStateOf<Long?>(selectedSessionForMap?.id ?: activeSession?.id ?: allSessions.firstOrNull()?.id)
    }

    val currentSession = remember(targetedSessionId, activeSession, allSessions, selectedSessionForMap) {
        if (targetedSessionId != null) {
            if (activeSession?.id == targetedSessionId) activeSession
            else allSessions.find { it.id == targetedSessionId } ?: selectedSessionForMap
        } else {
            activeSession ?: allSessions.firstOrNull()
        }
    }

    // Run local heuristic analytics
    val analytics = remember(currentSession, currentSession?.samples?.size) {
        currentSession?.let { RfAnalyticsEngine.analyze(it) }
    }

    var showSessionPicker by remember { mutableStateOf(false) }
    var selectedFilter by remember { mutableStateOf(0) } // 0: All, 1: Coverage, 2: Interference, 3: Mobility

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "RF Analytics & AI Diagnostics",
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            color = Color.White
                        )
                        Text(
                            text = currentSession?.name ?: "No session selected",
                            fontSize = 12.sp,
                            color = TelemetryCyan
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showSessionPicker = true }) {
                        Icon(
                            Icons.AutoMirrored.Filled.List,
                            contentDescription = "Select Session",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0E1626))
            )
        },
        containerColor = TelemetryDarkBg
    ) { innerPadding ->
        if (currentSession == null || analytics == null || analytics.totalSamples == 0) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.QueryStats,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = Color(0xFF475569)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "ไม่มีข้อมูล Drive Test สำหรับวิเคราะห์",
                        color = Color(0xFF94A3B8),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "เริ่มบันทึกในหน้า Dashboard หรือเลือกเซสชันจากประวัติ",
                        color = Color(0xFF64748B),
                        fontSize = 13.sp
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item { Spacer(modifier = Modifier.height(4.dp)) }

                // 1. ROUTE HEALTH SCORE & SUMMARY CARD
                item {
                    RouteHealthCard(analytics = analytics)
                }

                // 2. GEMINI AI RF OPTIMIZATION ADVISOR CARD
                item {
                    GeminiAdvisorCard(
                        geminiState = geminiState,
                        onAnalyzeClick = {
                            coroutineScope.launch {
                                val payload = RfAnalyticsEngine.buildGeminiPromptPayload(currentSession, analytics)
                                GeminiRfAdvisor.analyzeDriveTest(payload)
                            }
                        },
                        onCopyClick = { report ->
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Gemini RF Report", report))
                            Toast.makeText(context, "คัดลอกรายงาน Gemini AI เรียบร้อย", Toast.LENGTH_SHORT).show()
                        }
                    )
                }

                // 3. CDF DISTRIBUTION VISUALIZER
                item {
                    analytics.rsrpCdf?.let { cdf ->
                        CdfDistributionCard(cdf = cdf)
                    }
                }

                // 4. PROBLEM AREAS & MOBILITY FILTER CHIPS
                item {
                    Text(
                        text = "Detected Problem Areas & Mobility Log",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = selectedFilter == 0,
                            onClick = { selectedFilter = 0 },
                            label = { Text("All (${analytics.problemAreas.size + analytics.mobilityEvents.size})", fontSize = 11.sp) }
                        )
                        FilterChip(
                            selected = selectedFilter == 1,
                            onClick = { selectedFilter = 1 },
                            label = {
                                Text("Coverage (${analytics.problemAreas.count { it.type == RfProblemType.COVERAGE_HOLE }})", fontSize = 11.sp)
                            }
                        )
                        FilterChip(
                            selected = selectedFilter == 2,
                            onClick = { selectedFilter = 2 },
                            label = {
                                Text("Interference (${analytics.problemAreas.count { it.type == RfProblemType.PILOT_POLLUTION_INTERFERENCE }})", fontSize = 11.sp)
                            }
                        )
                        FilterChip(
                            selected = selectedFilter == 3,
                            onClick = { selectedFilter = 3 },
                            label = { Text("Handover (${analytics.mobilityEvents.size})", fontSize = 11.sp) }
                        )
                    }
                }

                // 5. PROBLEM AREAS / MOBILITY ITEMS LIST
                val filteredProblems = when (selectedFilter) {
                    1 -> analytics.problemAreas.filter { it.type == RfProblemType.COVERAGE_HOLE }
                    2 -> analytics.problemAreas.filter { it.type == RfProblemType.PILOT_POLLUTION_INTERFERENCE }
                    3 -> emptyList()
                    else -> analytics.problemAreas
                }

                val filteredMobility = when (selectedFilter) {
                    1, 2 -> emptyList()
                    else -> analytics.mobilityEvents
                }

                if (filteredProblems.isEmpty() && filteredMobility.isEmpty()) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = TelemetryCardBg),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("ไม่พบความผิดปกติในหมวดหมู่นี้", color = Color(0xFF64748B), fontSize = 13.sp)
                            }
                        }
                    }
                }

                items(filteredProblems) { problem ->
                    ProblemAreaCard(problem = problem)
                }

                items(filteredMobility) { mobility ->
                    MobilityEventCard(event = mobility)
                }

                item { Spacer(modifier = Modifier.height(16.dp)) }
            }
        }

        // SESSION PICKER DIALOG
        if (showSessionPicker) {
            AlertDialog(
                onDismissRequest = { showSessionPicker = false },
                title = { Text("เลือกเซสชันสำหรับวิเคราะห์", color = Color.White, fontWeight = FontWeight.Bold) },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        activeSession?.let { live ->
                            SessionPickerItem(
                                title = "🔴 LIVE: ${live.name}",
                                subtitle = "${live.samples.size} samples · กำลังบันทึก",
                                isSelected = targetedSessionId == live.id,
                                onClick = {
                                    targetedSessionId = live.id
                                    showSessionPicker = false
                                }
                            )
                        }
                        allSessions.forEach { sess ->
                            SessionPickerItem(
                                title = sess.name,
                                subtitle = "${sess.samples.size} samples · ${formatTime(sess.startTime)}",
                                isSelected = targetedSessionId == sess.id,
                                onClick = {
                                    targetedSessionId = sess.id
                                    showSessionPicker = false
                                }
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showSessionPicker = false }) {
                        Text("ปิด", color = TelemetryCyan)
                    }
                },
                containerColor = Color(0xFF1E293B)
            )
        }
    }
}

@Composable
private fun SessionPickerItem(
    title: String,
    subtitle: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = if (isSelected) Color(0xFF0F2B48) else Color(0xFF0F172A),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, if (isSelected) TelemetryCyan else Color(0xFF334155)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(subtitle, color = Color(0xFF94A3B8), fontSize = 11.sp)
            }
            if (isSelected) {
                Icon(Icons.Default.Check, contentDescription = null, tint = TelemetryCyan, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun RouteHealthCard(analytics: RfSessionAnalytics) {
    Card(
        colors = CardDefaults.cardColors(containerColor = TelemetryCardBg),
        border = BorderStroke(1.dp, TelemetryCardStroke),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Route Health Score", fontSize = 12.sp, color = Color(0xFF94A3B8))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = "${analytics.routeHealthScore}",
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold,
                            color = getScoreColor(analytics.routeHealthScore)
                        )
                        Text(
                            text = " / 100",
                            fontSize = 14.sp,
                            color = Color(0xFF64748B),
                            modifier = Modifier.padding(bottom = 4.dp, start = 2.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            color = getScoreColor(analytics.routeHealthScore).copy(alpha = 0.2f),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.padding(bottom = 6.dp)
                        ) {
                            Text(
                                text = analytics.scoreGrade,
                                color = getScoreColor(analytics.routeHealthScore),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                // Summary Chips
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "${analytics.totalSamples} pts · ${(analytics.distanceMeters / 1000.0).let { String.format("%.2f", it) }} km",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White
                    )
                    Text(
                        text = formatDuration(analytics.durationSeconds),
                        fontSize = 11.sp,
                        color = Color(0xFF94A3B8)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = Color(0xFF1E293B), thickness = 1.dp)
            Spacer(modifier = Modifier.height(10.dp))

            // RSRP / SINR Key Metrics
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MetricPill(label = "RSRP P10 (SLA)", value = "${analytics.rsrpCdf?.p10?.let { String.format("%.0f", it) } ?: "-"} dBm")
                MetricPill(label = "RSRP P50 (Median)", value = "${analytics.rsrpCdf?.p50?.let { String.format("%.0f", it) } ?: "-"} dBm")
                MetricPill(label = "SINR P50", value = "${analytics.sinrCdf?.p50?.let { String.format("%.1f", it) } ?: "-"} dB")
                MetricPill(label = "Ping-Pong", value = "${analytics.mobilityEvents.count { it.type == MobilityEventType.PING_PONG_HO }}")
            }

            analytics.dualSimSummary?.let { dual ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = dual,
                    fontSize = 11.sp,
                    color = TelemetryCyan,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
private fun MetricPill(label: String, value: String) {
    Column {
        Text(text = label, fontSize = 10.sp, color = Color(0xFF94A3B8))
        Text(text = value, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

@Composable
private fun GeminiAdvisorCard(
    geminiState: GeminiAdvisorState,
    onAnalyzeClick: () -> Unit,
    onCopyClick: (String) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF121E36)),
        border = BorderStroke(1.5.dp, Color(0xFF1E3A8A)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = Color(0xFF60A5FA),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Gemini AI RF Advisor",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = Color.White
                    )
                }
                Surface(
                    color = Color(0xFF1E293B),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = "gemini-3.8-flash",
                        fontSize = 10.sp,
                        color = Color(0xFF93C5FD),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            when (geminiState) {
                is GeminiAdvisorState.Idle -> {
                    Text(
                        text = "วิเคราะห์ข้อมูลสรุปเส้นทางอย่างชาญฉลาด ให้คำแนะนำการจูนพารามิเตอร์เสาสัญญาณ (Antenna Tilt, Power, Handover) เสมือนมี Senior RF Engineer ตรวจสอบ",
                        fontSize = 12.sp,
                        color = Color(0xFFCBD5E1),
                        lineHeight = 17.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = onAnalyzeClick,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("วิเคราะห์เส้นทางด้วย Gemini AI", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }

                is GeminiAdvisorState.Analyzing -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            color = Color(0xFF60A5FA),
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.5.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "กำลังส่งข้อมูลวิเคราะห์ผ่าน Gemini 3.8 Flash...",
                            color = Color(0xFF93C5FD),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                is GeminiAdvisorState.Success -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "รายงานคำแนะนำทางวิศวกรรม RF:",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            color = Color(0xFF93C5FD)
                        )
                        Row {
                            IconButton(onClick = { onCopyClick(geminiState.report) }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = Color(0xFFCBD5E1), modifier = Modifier.size(16.dp))
                            }
                            IconButton(onClick = onAnalyzeClick, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Refresh, contentDescription = "Re-analyze", tint = Color(0xFFCBD5E1), modifier = Modifier.size(16.dp))
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                        color = Color(0xFF09101F),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Color(0xFF1E293B)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = geminiState.report,
                            color = Color(0xFFF1F5F9),
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                is GeminiAdvisorState.Error -> {
                    Surface(
                        color = Color(0xFF450A0A),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Color(0xFF7F1D1D)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFCA5A5), modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = geminiState.message,
                                color = Color(0xFFFEE2E2),
                                fontSize = 11.sp,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = onAnalyzeClick,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("ลองใหม่อีกครั้ง", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun CdfDistributionCard(cdf: CdfDistribution) {
    Card(
        colors = CardDefaults.cardColors(containerColor = TelemetryCardBg),
        border = BorderStroke(1.dp, TelemetryCardStroke),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Cumulative Distribution Function (CDF)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = Color.White
                )
                Text(
                    text = "RSRP (dBm)",
                    fontSize = 11.sp,
                    color = TelemetryCyan
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Canvas CDF Curve
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(130.dp)
                    .background(Color(0xFF090E17), RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val width = size.width
                    val height = size.height
                    val minVal = cdf.min.toFloat()
                    val maxVal = cdf.max.toFloat()
                    val range = if (maxVal > minVal) maxVal - minVal else 1f

                    // Draw reference grid lines (0%, 50%, 100%)
                    drawLine(Color(0xFF1E293B), Offset(0f, height * 0.1f), Offset(width, height * 0.1f), strokeWidth = 1f)
                    drawLine(Color(0xFF1E293B), Offset(0f, height * 0.5f), Offset(width, height * 0.5f), strokeWidth = 1f)
                    drawLine(Color(0xFF1E293B), Offset(0f, height * 0.9f), Offset(width, height * 0.9f), strokeWidth = 1f)

                    val path = Path()
                    var isFirst = true

                    cdf.points.forEach { (valD, pctD) ->
                        val x = ((valD.toFloat() - minVal) / range) * width
                        val y = height - (pctD.toFloat() / 100f * height)

                        if (isFirst) {
                            path.moveTo(x, y)
                            isFirst = false
                        } else {
                            path.lineTo(x, y)
                        }
                    }

                    drawPath(
                        path = path,
                        color = Color(0xFF06B6D4),
                        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                    )

                    // Draw P50 marker
                    val p50X = ((cdf.p50.toFloat() - minVal) / range) * width
                    val p50Y = height - (0.5f * height)
                    drawCircle(color = Color(0xFF38BDF8), radius = 4.dp.toPx(), center = Offset(p50X, p50Y))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Min: ${String.format("%.0f", cdf.min)} dBm", fontSize = 10.sp, color = Color(0xFF64748B))
                Text("P10: ${String.format("%.0f", cdf.p10)} dBm", fontSize = 10.sp, color = Color(0xFFEAB308))
                Text("P50: ${String.format("%.0f", cdf.p50)} dBm", fontSize = 10.sp, color = Color(0xFF06B6D4))
                Text("P90: ${String.format("%.0f", cdf.p90)} dBm", fontSize = 10.sp, color = Color(0xFF10B981))
                Text("Max: ${String.format("%.0f", cdf.max)} dBm", fontSize = 10.sp, color = Color(0xFF64748B))
            }
        }
    }
}

@Composable
private fun ProblemAreaCard(problem: RfProblemArea) {
    Card(
        colors = CardDefaults.cardColors(containerColor = TelemetryCardBg),
        border = BorderStroke(1.dp, TelemetryCardStroke),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            val (badgeColor, icon) = when (problem.severity) {
                ProblemSeverity.HIGH -> Pair(Color(0xFFEF4444), Icons.Default.Error)
                ProblemSeverity.MEDIUM -> Pair(Color(0xFFF97316), Icons.Default.Warning)
                ProblemSeverity.LOW -> Pair(Color(0xFFEAB308), Icons.Default.Info)
            }

            Icon(
                icon,
                contentDescription = null,
                tint = badgeColor,
                modifier = Modifier
                    .size(22.dp)
                    .padding(top = 2.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = problem.title,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = Color.White
                    )
                    Surface(
                        color = badgeColor.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = problem.severity.label,
                            color = badgeColor,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = problem.description,
                    fontSize = 11.sp,
                    color = Color(0xFF94A3B8),
                    lineHeight = 15.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${problem.metricSummary} · ${String.format("%.5f, %.5f", problem.latitude, problem.longitude)}",
                    fontSize = 10.sp,
                    color = Color(0xFF64748B),
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
private fun MobilityEventCard(event: MobilityEvent) {
    Card(
        colors = CardDefaults.cardColors(containerColor = TelemetryCardBg),
        border = BorderStroke(1.dp, TelemetryCardStroke),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            val tint = when (event.type) {
                MobilityEventType.HANDOVER -> Color(0xFF10B981)
                MobilityEventType.PING_PONG_HO -> Color(0xFFEF4444)
                MobilityEventType.MISSED_HO -> Color(0xFFF97316)
            }

            Icon(
                Icons.AutoMirrored.Filled.CompareArrows,
                contentDescription = null,
                tint = tint,
                modifier = Modifier
                    .size(22.dp)
                    .padding(top = 2.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = event.type.label,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = Color.White
                    )
                    Text(
                        text = formatTime(event.timestamp),
                        fontSize = 10.sp,
                        color = Color(0xFF64748B)
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = event.details,
                    fontSize = 11.sp,
                    color = Color(0xFFCBD5E1)
                )
            }
        }
    }
}

private fun getScoreColor(score: Int): Color {
    return when {
        score >= 85 -> Color(0xFF10B981)
        score >= 70 -> Color(0xFF22C55E)
        score >= 50 -> Color(0xFFEAB308)
        else -> Color(0xFFEF4444)
    }
}

private fun formatDuration(seconds: Long): String {
    val hrs = seconds / 3600
    val mins = (seconds % 3600) / 60
    val secs = seconds % 60
    return if (hrs > 0) String.format("%02d:%02d:%02d", hrs, mins, secs)
    else String.format("%02d:%02d", mins, secs)
}

private fun formatTime(millis: Long): String {
    val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    return sdf.format(Date(millis))
}
