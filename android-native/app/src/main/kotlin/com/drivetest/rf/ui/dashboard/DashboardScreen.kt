package com.drivetest.rf.ui.dashboard

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.drivetest.rf.data.CampaignRepository
import com.drivetest.rf.data.RfAnalyticsEngine
import com.drivetest.rf.model.*
import com.drivetest.rf.telephony.DriveTestForegroundService
import com.drivetest.rf.ui.i18n.AppLanguage
import com.drivetest.rf.ui.i18n.AppStrings.tr
import java.util.Locale

@Composable
fun DashboardScreen(
    measurement: Measurement,
    history: List<Measurement>,
    isTesting: Boolean,
    simSlots: List<SimSlotInfo>,
    selectedSimSlot: Int,
    onSelectSimSlot: (Int) -> Unit,
    onStartTest: (String, CampaignPreset) -> Unit,
    onStopTest: () -> Unit,
    onOpenAnalytics: () -> Unit = {},
    language: AppLanguage = AppLanguage.EN
) {
    var isCarHudMode by remember { mutableStateOf(false) }
    var isHudMirrored by remember { mutableStateOf(false) }
    var showStartDialog by remember { mutableStateOf(false) }
    var showStopConfirmDialog by remember { mutableStateOf(false) }

    var weakSignalAlertEnabled by remember { mutableStateOf(true) }
    var weakSignalThresholdDbm by remember { mutableIntStateOf(-110) }

    val cadence by DriveTestForegroundService.cadence.collectAsState()
    val ratMode by DriveTestForegroundService.ratMode.collectAsState()
    val isPaused by DriveTestForegroundService.isPaused.collectAsState()
    val useSimulatedGps by DriveTestForegroundService.useSimulatedGps.collectAsState()
    val lightProbeEnabled by DriveTestForegroundService.lightProbeEnabled.collectAsState()

    val finishedSummary by CampaignRepository.lastFinishedSummary.collectAsState()
    val latestSpeedTest by CampaignRepository.latestSpeedTest.collectAsState()
    val speedTestProgress by CampaignRepository.speedTestProgress.collectAsState()
    val latestProbe by CampaignRepository.latestProbe.collectAsState()

    if (isCarHudMode) {
        CarHudFullScreen(
            measurement = measurement,
            history = history,
            isTesting = isTesting,
            isPaused = isPaused,
            isMirrored = isHudMirrored,
            onToggleMirror = { isHudMirrored = !isHudMirrored },
            onExitHud = { isCarHudMode = false },
            onStartQuick = { onStartTest("Car_HUD_Drive_${System.currentTimeMillis() % 1000}", CampaignPreset.HIGHWAY) },
            onTogglePause = { DriveTestForegroundService.setPaused(!isPaused) },
            onStop = onStopTest,
            language = language
        )
        return
    }

    val cardBg = Color(0xFF0F172A)
    val tileBg = Color(0xFF152036)
    val tileBorder = Color(0xFF1E293B)
    val accentCyan = Color(0xFF38BDF8)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF090F1D))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Finished Campaign Summary Banner (if available)
        finishedSummary?.let { summary ->
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0D1E2E)),
                    border = BorderStroke(1.dp, Color(0xFF16A34A)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = Color(0xFF22C55E),
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "${tr(language, "Test Finished", "บันทึกเสร็จสิ้น")}: ${summary.name}",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                                val mins = summary.durationSec / 60
                                val secs = summary.durationSec % 60
                                Text(
                                    text = String.format(
                                        Locale.US,
                                        "%d measurements · %.2f km · Duration %02d:%02d · Avg RSRP %d dBm (Min %d / Max %d) · %d handovers",
                                        summary.measurementCount,
                                        summary.distanceKm,
                                        mins,
                                        secs,
                                        summary.avgRsrp,
                                        summary.minRsrp,
                                        summary.maxRsrp,
                                        summary.handoverCount
                                    ),
                                    color = Color(0xFF94A3B8),
                                    fontSize = 11.sp
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(
                                onClick = onOpenAnalytics,
                                colors = ButtonDefaults.buttonColors(containerColor = accentCyan),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text(
                                    text = tr(language, "View Analysis", "ดูผลวิเคราะห์"),
                                    color = Color(0xFF090F1D),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            OutlinedButton(
                                onClick = { CampaignRepository.dismissFinishedSummary() },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text(
                                    text = tr(language, "Close", "ปิด"),
                                    color = Color(0xFFCBD5E1),
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
            }
        }

        // 2. Weak Signal Alert Banner (when enabled and RSRP <= threshold)
        if (weakSignalAlertEnabled && measurement.radio.rsrp <= weakSignalThresholdDbm) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF3B1219)),
                    border = BorderStroke(1.dp, Color(0xFFEF4444)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = Color(0xFFF87171),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = tr(
                                language,
                                "WEAK SIGNAL ALERT: Current RSRP (${measurement.radio.rsrp} dBm) is below threshold ($weakSignalThresholdDbm dBm) on PCI ${measurement.radio.pci}",
                                "แจ้งเตือนสัญญาณอ่อน: ค่า RSRP ปัจจุบัน (${measurement.radio.rsrp} dBm) ต่ำกว่าเกณฑ์ ($weakSignalThresholdDbm dBm) ที่ PCI ${measurement.radio.pci}"
                            ),
                            color = Color(0xFFFCA5A5),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        // 3. Session Control Bar (SIM Slot, Cadence, RAT Mode, Car HUD Mode, Start/Quick Start/Pause/Stop)
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = cardBg),
                border = BorderStroke(1.dp, tileBorder),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Row A: Active SIM Slot + Car HUD Mode button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = tr(language, "Active SIM:", "ซิมที่ใช้งาน:"),
                                color = Color(0xFF94A3B8),
                                fontSize = 11.sp
                            )
                            simSlots.forEach { sim ->
                                val selected = sim.slotIndex == selectedSimSlot
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (selected) Color(0xFF1E3A5F) else tileBg)
                                        .border(
                                            1.dp,
                                            if (selected) accentCyan else tileBorder,
                                            RoundedCornerShape(8.dp)
                                        )
                                        .clickable { onSelectSimSlot(sim.slotIndex) }
                                        .padding(horizontal = 9.dp, vertical = 5.dp)
                                ) {
                                    Text(
                                        text = "${sim.displayName} (${sim.mcc}-${sim.mnc})",
                                        color = if (selected) accentCyan else Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }

                        // Car HUD Mode Button
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(tileBg)
                                .border(1.dp, tileBorder, RoundedCornerShape(8.dp))
                                .clickable { isCarHudMode = true }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.DirectionsCar,
                                    contentDescription = null,
                                    tint = accentCyan,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = tr(language, "Car HUD Mode", "โหมด Car HUD"),
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    // Row B: Cadence Selector (Eco 5s / Standard 2s / Precise 1s) + Network Mode Selector
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Segmented Cadence Group
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(tileBg)
                                .border(1.dp, tileBorder, RoundedCornerShape(8.dp))
                                .padding(2.dp)
                        ) {
                            MeasurementCadence.values().forEach { mode ->
                                val active = cadence == mode
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (active) accentCyan else Color.Transparent)
                                        .clickable { DriveTestForegroundService.setCadence(mode) }
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = if (language == AppLanguage.TH) mode.labelTh else mode.labelEn,
                                        color = if (active) Color(0xFF090F1D) else Color(0xFF94A3B8),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        // Segmented Network RAT Mode Group
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(tileBg)
                                .border(1.dp, tileBorder, RoundedCornerShape(8.dp))
                                .padding(2.dp)
                        ) {
                            RatSelectionMode.values().forEach { mode ->
                                val active = ratMode == mode
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (active) accentCyan else Color.Transparent)
                                        .clickable { DriveTestForegroundService.setRatMode(mode) }
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = if (language == AppLanguage.TH) mode.labelTh else mode.labelEn,
                                        color = if (active) Color(0xFF090F1D) else Color(0xFF94A3B8),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    // Row C: Action Buttons (Start Drive Test + Quick Start OR Active Recording Status + Pause + Stop)
                    if (!isTesting) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { showStartDialog = true },
                                colors = ButtonDefaults.buttonColors(containerColor = accentCyan),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(42.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = Color(0xFF090F1D),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = tr(language, "Start Drive Test", "เริ่มทดสอบสัญญาณ"),
                                    color = Color(0xFF090F1D),
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }

                            OutlinedButton(
                                onClick = {
                                    onStartTest(
                                        "Urban_Drive_${System.currentTimeMillis() % 10000}",
                                        CampaignPreset.URBAN_ROUTE
                                    )
                                },
                                border = BorderStroke(1.dp, Color(0xFF334155)),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(42.dp)
                            ) {
                                Text(
                                    text = tr(language, "Quick Start (URBAN)", "เริ่มด่วน (ในเมือง)"),
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    } else {
                        val distKm = RfAnalyticsEngine.calculateTotalRouteDistanceKm(history)
                        val hoCount = RfAnalyticsEngine.detectHandovers(history).size
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(9.dp)
                                        .clip(CircleShape)
                                        .background(if (isPaused) Color(0xFFFACC15) else Color(0xFFEF4444))
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = String.format(
                                        Locale.US,
                                        "%s · %d pts · %.2f km · %d HO",
                                        if (isPaused) tr(language, "PAUSED", "หยุดชั่วคราว") else tr(language, "REC", "กำลังบันทึก"),
                                        history.size,
                                        distKm,
                                        hoCount
                                    ),
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedButton(
                                    onClick = { DriveTestForegroundService.setPaused(!isPaused) },
                                    border = BorderStroke(1.dp, Color(0xFFFACC15)),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.height(34.dp)
                                ) {
                                    Text(
                                        text = if (isPaused) tr(language, "Resume", "ทำต่อ") else tr(language, "Pause", "พัก"),
                                        color = Color(0xFFFACC15),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Button(
                                    onClick = { showStopConfirmDialog = true },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier.height(34.dp)
                                ) {
                                    Text(
                                        text = tr(language, "Stop", "จบการทดสอบ"),
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 4. CARD 1: RF Signal Telemetry (Arc Gauge + 6 Tiles + Weak Signal Alert Toggle & Threshold)
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = cardBg),
                border = BorderStroke(1.dp, tileBorder),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Speed,
                                contentDescription = null,
                                tint = accentCyan,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = tr(language, "RF Signal Telemetry", "พารามิเตอร์สัญญาณคลื่นวิทยุ (RF)"),
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                        StatusBadge(
                            text = "${measurement.radio.technology.displayName} · ${if (measurement.radio.is5gSa) "SA" else "NSA/LTE"}",
                            color = accentCyan
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Semi-Circular RSRP Gauge
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        RsrpGauge(
                            rsrp = measurement.radio.rsrp,
                            isConnected = measurement.radio.isConnected,
                            language = language
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // 6 Metric Tiles in 2 rows x 3 columns (RSRQ, SINR, RSSI, CSI-RSRP, CQI, TIMING ADV)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TelemetryTile(
                            modifier = Modifier.weight(1f),
                            label = "RSRQ",
                            value = "${measurement.radio.rsrq}",
                            unit = "dB"
                        )
                        TelemetryTile(
                            modifier = Modifier.weight(1f),
                            label = "SINR",
                            value = "${measurement.radio.sinr}.0",
                            unit = "dB",
                            valueColor = accentCyan
                        )
                        TelemetryTile(
                            modifier = Modifier.weight(1f),
                            label = "RSSI",
                            value = "${measurement.radio.rssi}",
                            unit = "dBm"
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TelemetryTile(
                            modifier = Modifier.weight(1f),
                            label = "CSI-RSRP",
                            value = "${measurement.radio.csiRsrp}",
                            unit = "dBm"
                        )
                        TelemetryTile(
                            modifier = Modifier.weight(1f),
                            label = "CQI",
                            value = "${measurement.radio.cqi} / 15",
                            unit = ""
                        )
                        TelemetryTile(
                            modifier = Modifier.weight(1f),
                            label = "TIMING ADV",
                            value = "TA ${measurement.radio.timingAdvance}",
                            unit = ""
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Weak Signal Alert Footer
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = tr(language, "Weak Signal Alert", "แจ้งเตือนสัญญาณอ่อน"),
                                color = Color(0xFFCBD5E1),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Switch(
                                checked = weakSignalAlertEnabled,
                                onCheckedChange = { weakSignalAlertEnabled = it },
                                modifier = Modifier.scale(0.8f)
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            listOf(-100, -105, -110, -115).forEach { dbm ->
                                val active = weakSignalThresholdDbm == dbm
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (active) Color(0xFF1E3A5F) else tileBg)
                                        .border(
                                            1.dp,
                                            if (active) accentCyan else tileBorder,
                                            RoundedCornerShape(6.dp)
                                        )
                                        .clickable { weakSignalThresholdDbm = dbm }
                                        .padding(horizontal = 6.dp, vertical = 3.dp)
                                ) {
                                    Text(
                                        text = "$dbm",
                                        color = if (active) accentCyan else Color(0xFF94A3B8),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 5. CARD 2: Serving Cell & Radio Parameters + Carrier Aggregation
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = cardBg),
                border = BorderStroke(1.dp, tileBorder),
                shape = RoundedCornerShape(14.dp),
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
                                imageVector = Icons.Default.CellTower,
                                contentDescription = null,
                                tint = accentCyan,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = tr(language, "Serving Cell & Radio Parameters", "ข้อมูลสถานีฐานที่ให้บริการ (Serving Cell)"),
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                        StatusBadge(
                            text = if (measurement.radio.isConnected) "CONNECTED" else "STANDBY",
                            color = if (measurement.radio.isConnected) Color(0xFF22C55E) else Color(0xFFFACC15)
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // 2x2 Grid: OPERATOR (PLMN), CELL ID / TAC, PCI / BAND, EARFCN / BW
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ServingDetailBox(
                            modifier = Modifier.weight(1f),
                            label = "OPERATOR (PLMN)",
                            primary = measurement.radio.operatorName,
                            secondary = "MCC ${measurement.radio.mcc} · MNC ${measurement.radio.mnc}"
                        )
                        ServingDetailBox(
                            modifier = Modifier.weight(1f),
                            label = "CELL ID / TAC",
                            primary = "${measurement.radio.cellId}",
                            secondary = "TAC / LAC ${measurement.radio.tac}"
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ServingDetailBox(
                            modifier = Modifier.weight(1f),
                            label = "PCI / BAND",
                            primary = "PCI ${measurement.radio.pci}",
                            secondary = measurement.radio.band,
                            primaryColor = accentCyan
                        )
                        ServingDetailBox(
                            modifier = Modifier.weight(1f),
                            label = "EARFCN / BW",
                            primary = "${measurement.radio.arfcn}",
                            secondary = "${measurement.radio.dlBandwidthKhz / 1000} MHz BW"
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Carrier Aggregation Summary
                    val caList = measurement.radio.carrierAggregation
                    val totalBwMhz = caList.sumOf { it.dlBandwidthKhz } / 1000
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Layers,
                                contentDescription = null,
                                tint = accentCyan,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = tr(language, "Carrier Aggregation (5G NR / LTE CA)", "การรวมคลื่นความถี่ (Carrier Aggregation)"),
                                color = Color(0xFF94A3B8),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Text(
                            text = "${caList.size} CC · $totalBwMhz MHz total",
                            color = accentCyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (caList.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            caList.forEachIndexed { idx, cc ->
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(tileBg)
                                        .border(1.dp, tileBorder, RoundedCornerShape(6.dp))
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = "${if (idx == 0) "PCell" else "SCell $idx"}: ${if (cc.isNr) "n${cc.band}" else "B${cc.band}"} (${cc.dlBandwidthKhz / 1000} MHz)",
                                        color = Color(0xFFE2E8F0),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 6. CARD 3: GNSS / GPS Telemetry + Simulated GPS Toggle
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = cardBg),
                border = BorderStroke(1.dp, tileBorder),
                shape = RoundedCornerShape(14.dp),
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
                                imageVector = Icons.Default.LocationOn,
                                contentDescription = null,
                                tint = accentCyan,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = tr(language, "GNSS / GPS Telemetry", "ข้อมูลพิกัดดาวเทียม (GNSS / GPS)"),
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = tr(language, "Simulated GPS", "จำลองพิกัด GPS"),
                                color = Color(0xFF94A3B8),
                                fontSize = 11.sp
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Switch(
                                checked = useSimulatedGps,
                                onCheckedChange = { DriveTestForegroundService.setUseSimulatedGps(it) },
                                modifier = Modifier.scale(0.8f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // 6 Tiles: LATITUDE, LONGITUDE, SPEED, ALTITUDE, BEARING, ACCURACY
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TelemetryTile(
                            modifier = Modifier.weight(1f),
                            label = "LATITUDE",
                            value = String.format(Locale.US, "%.6f°", measurement.gps.latitude),
                            unit = ""
                        )
                        TelemetryTile(
                            modifier = Modifier.weight(1f),
                            label = "LONGITUDE",
                            value = String.format(Locale.US, "%.6f°", measurement.gps.longitude),
                            unit = ""
                        )
                        TelemetryTile(
                            modifier = Modifier.weight(1f),
                            label = "SPEED",
                            value = String.format(Locale.US, "%.1f", measurement.gps.speedKmh),
                            unit = "km/h",
                            valueColor = accentCyan
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TelemetryTile(
                            modifier = Modifier.weight(1f),
                            label = "ALTITUDE",
                            value = String.format(Locale.US, "%.1f", measurement.gps.altitude),
                            unit = "m"
                        )
                        TelemetryTile(
                            modifier = Modifier.weight(1f),
                            label = "BEARING",
                            value = String.format(Locale.US, "%.0f°", measurement.gps.bearing),
                            unit = ""
                        )
                        TelemetryTile(
                            modifier = Modifier.weight(1f),
                            label = "ACCURACY",
                            value = String.format(Locale.US, "±%.1f", measurement.gps.accuracy),
                            unit = "m",
                            valueColor = Color(0xFF22C55E)
                        )
                    }
                }
            }
        }

        // 7. CARD 4: Active Throughput & Latency Test + Continuous Latency Probe
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = cardBg),
                border = BorderStroke(1.dp, tileBorder),
                shape = RoundedCornerShape(14.dp),
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
                                imageVector = Icons.Default.Bolt,
                                contentDescription = null,
                                tint = accentCyan,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = tr(language, "Active Throughput & Latency Test", "ทดสอบความเร็วและความหน่วงเครือข่าย"),
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }

                        Button(
                            onClick = { CampaignRepository.runActiveSpeedTest(measurement.radio) },
                            enabled = !speedTestProgress.isRunning,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (speedTestProgress.isRunning) {
                                    "${speedTestProgress.phase} ${speedTestProgress.progressPercent}%"
                                } else {
                                    tr(language, "Run Speed Test", "เริ่มทดสอบความเร็ว")
                                },
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // 2x2 Grid: DOWNLOAD, UPLOAD, UNLOADED PING / JITTER, LOADED LATENCY
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TelemetryTile(
                            modifier = Modifier.weight(1f),
                            label = "DOWNLOAD",
                            value = String.format(Locale.US, "%.1f", latestSpeedTest.downloadMbps),
                            unit = "Mbps",
                            valueColor = Color(0xFF22C55E)
                        )
                        TelemetryTile(
                            modifier = Modifier.weight(1f),
                            label = "UPLOAD",
                            value = String.format(Locale.US, "%.1f", latestSpeedTest.uploadMbps),
                            unit = "Mbps",
                            valueColor = accentCyan
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ServingDetailBox(
                            modifier = Modifier.weight(1f),
                            label = "UNLOADED PING / JITTER",
                            primary = "${latestSpeedTest.pingMs} ms",
                            secondary = "Jitter ${latestSpeedTest.jitterMs} ms"
                        )
                        ServingDetailBox(
                            modifier = Modifier.weight(1f),
                            label = "LOADED LATENCY",
                            primary = "${latestSpeedTest.loadedLatencyMs} ms",
                            secondary = latestSpeedTest.serverLocation,
                            primaryColor = Color(0xFFFACC15)
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Continuous Latency Probe Footer
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = tr(language, "Continuous Latency Probe", "ตรวจสอบ Ping ต่อเนื่อง"),
                                color = Color(0xFFCBD5E1),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Switch(
                                checked = lightProbeEnabled,
                                onCheckedChange = { DriveTestForegroundService.setLightProbeEnabled(it) },
                                modifier = Modifier.scale(0.8f)
                            )
                        }

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(tileBg)
                                .border(1.dp, tileBorder, RoundedCornerShape(6.dp))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "Ping ${latestProbe.pingMs} ms · Jitter ${latestProbe.jitterMs} ms",
                                color = Color(0xFF94A3B8),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }

        // 8. CARD 5: Neighboring Cells Table + CARD 6: Live RSRP Stream Chart
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = cardBg),
                border = BorderStroke(1.dp, tileBorder),
                shape = RoundedCornerShape(14.dp),
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
                                imageVector = Icons.Default.SignalCellularAlt,
                                contentDescription = null,
                                tint = accentCyan,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "${tr(language, "Neighboring Cells", "เซลล์ข้างเคียง")} (${measurement.radio.neighborCells.size})",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                        Text(
                            text = tr(language, "A3 Offset Alert >= +6 dB", "แจ้งเตือนเมื่อแรงกว่า >= +6 dB"),
                            color = Color(0xFF64748B),
                            fontSize = 10.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    if (measurement.radio.neighborCells.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 18.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = tr(language, "No neighboring cells reported by modem", "ไม่มีข้อมูลเซลล์ข้างเคียงจากโมเด็ม"),
                                color = Color(0xFF64748B),
                                fontSize = 12.sp
                            )
                        }
                    } else {
                        // Table Header
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(tileBg, RoundedCornerShape(6.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("PCI", color = Color(0xFF94A3B8), fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Text("TECH", color = Color(0xFF94A3B8), fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.1f))
                            Text("EARFCN", color = Color(0xFF94A3B8), fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.2f))
                            Text("RSRP", color = Color(0xFF94A3B8), fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.1f))
                            Text("RSRQ", color = Color(0xFF94A3B8), fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Text("DELTA", color = Color(0xFF94A3B8), fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        measurement.radio.neighborCells.forEach { nc ->
                            val delta = nc.rsrp - measurement.radio.rsrp
                            val isStrongerOpportunity = delta >= 6
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp)
                                    .background(
                                        if (isStrongerOpportunity) Color(0xFF3B2110) else Color.Transparent,
                                        RoundedCornerShape(6.dp)
                                    )
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "PCI ${nc.pci}",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = nc.technology.displayName,
                                    color = accentCyan,
                                    fontSize = 11.sp,
                                    modifier = Modifier.weight(1.1f)
                                )
                                Text(
                                    text = "${nc.arfcn}",
                                    color = Color(0xFFCBD5E1),
                                    fontSize = 11.sp,
                                    modifier = Modifier.weight(1.2f)
                                )
                                Text(
                                    text = "${nc.rsrp} dBm",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1.1f)
                                )
                                Text(
                                    text = "${nc.rsrq} dB",
                                    color = Color(0xFF94A3B8),
                                    fontSize = 11.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = if (delta > 0) "+$delta dB" else "$delta dB",
                                    color = when {
                                        delta >= 6 -> Color(0xFFFACC15)
                                        delta >= 0 -> Color(0xFF22C55E)
                                        else -> Color(0xFF94A3B8)
                                    },
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
        }

        // 9. CARD 6: Live RSRP Stream (Last N Samples)
        item {
            val recentSamples = history.takeLast(30).ifEmpty { listOf(measurement) }
            Card(
                colors = CardDefaults.cardColors(containerColor = cardBg),
                border = BorderStroke(1.dp, tileBorder),
                shape = RoundedCornerShape(14.dp),
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
                                imageVector = Icons.Default.ShowChart,
                                contentDescription = null,
                                tint = accentCyan,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "${tr(language, "Live RSRP Stream", "กราฟสัญญาณ RSRP สด")} (${recentSamples.size} Samples)",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                        Text(
                            text = "Range: -130 dBm to -60 dBm",
                            color = Color(0xFF64748B),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    LiveRsrpStreamChart(
                        samples = recentSamples,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(110.dp)
                    )
                }
            }
        }
    }

    // Start Campaign Modal (Name + 4 Presets + Weak Signal Threshold)
    if (showStartDialog) {
        StartCampaignDialog(
            language = language,
            onDismiss = { showStartDialog = false },
            onConfirm = { name, preset, threshold ->
                weakSignalThresholdDbm = threshold
                showStartDialog = false
                onStartTest(name, preset)
            }
        )
    }

    // Stop Confirmation Dialog
    if (showStopConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showStopConfirmDialog = false },
            containerColor = Color(0xFF0F172A),
            title = {
                Text(
                    text = tr(language, "Finish Drive Test Session?", "ยืนยันการจบการทดสอบ?"),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            },
            text = {
                Text(
                    text = tr(
                        language,
                        "This will finalize the current campaign (${history.size} samples) and generate the route summary & diagnostics.",
                        "ระบบจะบันทึกข้อมูลทั้งหมด (${history.size} จุดวัด) และสร้างสรุปผลการวิเคราะห์เส้นทาง"
                    ),
                    color = Color(0xFFCBD5E1),
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showStopConfirmDialog = false
                        onStopTest()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                ) {
                    Text(tr(language, "Finish & Save", "บันทึกและจบ"), color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showStopConfirmDialog = false }) {
                    Text(tr(language, "Continue Recording", "บันทึกต่อ"), color = Color(0xFF94A3B8))
                }
            }
        )
    }
}

@Composable
private fun StartCampaignDialog(
    language: AppLanguage,
    onDismiss: () -> Unit,
    onConfirm: (String, CampaignPreset, Int) -> Unit
) {
    var campaignName by remember {
        mutableStateOf("Drive_${System.currentTimeMillis() % 10000}")
    }
    var selectedPreset by remember { mutableStateOf(CampaignPreset.URBAN_ROUTE) }
    var thresholdDbm by remember { mutableIntStateOf(CampaignPreset.URBAN_ROUTE.defaultWeakThresholdDbm) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
            border = BorderStroke(1.dp, Color(0xFF1E293B)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = tr(language, "Start New Drive Test Campaign", "เริ่มแคมเปญทดสอบสัญญาณใหม่"),
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 16.sp
                )

                OutlinedTextField(
                    value = campaignName,
                    onValueChange = { campaignName = it },
                    label = { Text(tr(language, "Campaign Name", "ชื่อแคมเปญ")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = tr(language, "Select Test Preset:", "เลือกโหมดการทดสอบ:"),
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )

                CampaignPreset.values().forEach { preset ->
                    val selected = selectedPreset == preset
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) Color(0xFF1E3A5F) else Color(0xFF152036))
                            .border(
                                1.dp,
                                if (selected) Color(0xFF38BDF8) else Color(0xFF1E293B),
                                RoundedCornerShape(10.dp)
                            )
                            .clickable {
                                selectedPreset = preset
                                thresholdDbm = preset.defaultWeakThresholdDbm
                            }
                            .padding(10.dp)
                    ) {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = if (language == AppLanguage.TH) preset.labelTh else preset.labelEn,
                                    color = if (selected) Color(0xFF38BDF8) else Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                                Text(
                                    text = "Alert ${preset.defaultWeakThresholdDbm} dBm",
                                    color = Color(0xFF94A3B8),
                                    fontSize = 10.sp
                                )
                            }
                            Text(
                                text = if (language == AppLanguage.TH) preset.descTh else preset.descEn,
                                color = Color(0xFF94A3B8),
                                fontSize = 11.sp
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(tr(language, "Cancel", "ยกเลิก"), color = Color(0xFF94A3B8))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { onConfirm(campaignName, selectedPreset, thresholdDbm) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF38BDF8))
                    ) {
                        Text(
                            text = tr(language, "Start Recording", "เริ่มบันทึก"),
                            color = Color(0xFF090F1D),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CarHudFullScreen(
    measurement: Measurement,
    history: List<Measurement>,
    isTesting: Boolean,
    isPaused: Boolean,
    isMirrored: Boolean,
    onToggleMirror: () -> Unit,
    onExitHud: () -> Unit,
    onStartQuick: () -> Unit,
    onTogglePause: () -> Unit,
    onStop: () -> Unit,
    language: AppLanguage
) {
    val rsrpColor = when {
        measurement.radio.rsrp >= -85 -> Color(0xFF22C55E)
        measurement.radio.rsrp >= -105 -> Color(0xFFFACC15)
        else -> Color(0xFFEF4444)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .scale(scaleX = 1f, scaleY = if (isMirrored) -1f else 1f)
            .background(Color(0xFF050811))
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Top HUD Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "CAR HUD MODE · ${measurement.radio.technology.displayName}",
                    color = Color(0xFF38BDF8),
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 16.sp
                )
                Text(
                    text = if (isMirrored) {
                        tr(language, "Windshield Reflection Active", "เปิดการกลับด้านสะท้อนกระจกหน้ารถ")
                    } else {
                        tr(language, "High-Contrast Field Display", "โหมดแสดงผลขนาดใหญ่สำหรับขับรถ")
                    },
                    color = Color(0xFF94A3B8),
                    fontSize = 11.sp
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onToggleMirror,
                    border = BorderStroke(1.dp, Color(0xFF38BDF8))
                ) {
                    Text(
                        text = if (isMirrored) "Normal" else "Mirror HUD",
                        color = Color(0xFF38BDF8),
                        fontSize = 12.sp
                    )
                }
                Button(
                    onClick = onExitHud,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B))
                ) {
                    Text(tr(language, "Exit HUD", "ออกจาก HUD"), color = Color.White, fontSize = 12.sp)
                }
            }
        }

        // 3 Oversized Cards (RSRP, SERVING CELL, SPEED & SESSION)
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Card 1: Giant RSRP + SINR/RSRQ
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                border = BorderStroke(2.dp, rsrpColor),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("RSRP (dBm)", color = Color(0xFF94A3B8), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text(
                            text = "${measurement.radio.rsrp}",
                            color = rsrpColor,
                            fontSize = 54.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "SINR ${measurement.radio.sinr} dB",
                            color = Color(0xFF38BDF8),
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "RSRQ ${measurement.radio.rsrq} dB · CQI ${measurement.radio.cqi}",
                            color = Color(0xFFCBD5E1),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // Card 2: Serving Cell (PCI, Band, Cell ID)
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                border = BorderStroke(1.dp, Color(0xFF1E293B)),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("SERVING CELL", color = Color(0xFF94A3B8), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text(
                            text = "PCI ${measurement.radio.pci}",
                            color = Color.White,
                            fontSize = 36.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = "${measurement.radio.operatorName} · Cell ${measurement.radio.cellId}",
                            color = Color(0xFF94A3B8),
                            fontSize = 13.sp
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = measurement.radio.band,
                            color = Color(0xFF38BDF8),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "EARFCN ${measurement.radio.arfcn}",
                            color = Color(0xFFCBD5E1),
                            fontSize = 14.sp
                        )
                    }
                }
            }

            // Card 3: Speed & Session Counter
            val distKm = RfAnalyticsEngine.calculateTotalRouteDistanceKm(history)
            val hoCount = RfAnalyticsEngine.detectHandovers(history).size
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                border = BorderStroke(1.dp, Color(0xFF1E293B)),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("GROUND SPEED", color = Color(0xFF94A3B8), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text(
                            text = String.format(Locale.US, "%.1f km/h", measurement.gps.speedKmh),
                            color = Color(0xFF22C55E),
                            fontSize = 34.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = String.format(Locale.US, "%.2f km", distKm),
                            color = Color.White,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${history.size} pts · $hoCount Handovers",
                            color = Color(0xFF94A3B8),
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }

        // Bottom HUD Controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!isTesting) {
                Button(
                    onClick = onStartQuick,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF38BDF8)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        text = tr(language, "START DRIVE TEST", "เริ่มทดสอบสัญญาณ"),
                        color = Color(0xFF090F1D),
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 16.sp
                    )
                }
            } else {
                Button(
                    onClick = onTogglePause,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706)),
                    modifier = Modifier
                        .weight(1f)
                        .height(54.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        text = if (isPaused) "RESUME" else "PAUSE",
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 15.sp
                    )
                }
                Button(
                    onClick = onStop,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                    modifier = Modifier
                        .weight(1f)
                        .height(54.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        text = "STOP TEST",
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 15.sp
                    )
                }
            }
        }
    }
}

@Composable
fun RsrpGauge(rsrp: Int, isConnected: Boolean = true, language: AppLanguage = AppLanguage.EN) {
    val clampedRsrp = rsrp.coerceIn(-140, -40)
    val fraction = (clampedRsrp - (-140)) / 100f
    val animatedFraction by animateFloatAsState(targetValue = fraction, label = "gauge")

    val activeColor = when {
        rsrp >= -85 -> Color(0xFF22C55E)
        rsrp >= -105 -> Color(0xFFFACC15)
        else -> Color(0xFFEF4444)
    }

    val qualityLabel = when {
        !isConnected && rsrp <= -135 -> tr(language, "STANDBY / NO SIGNAL", "สแตนด์บาย / ไม่มีสัญญาณ")
        rsrp >= -85 -> tr(language, "EXCELLENT COVERAGE", "สัญญาณดีเยี่ยม")
        rsrp >= -98 -> tr(language, "GOOD SIGNAL", "สัญญาณปกติ")
        rsrp >= -108 -> tr(language, "FAIR / CELL EDGE", "สัญญาณปานกลาง / ขอบเซลล์")
        else -> tr(language, "POOR / COVERAGE HOLE", "สัญญาณอ่อน")
    }

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(210.dp, 112.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 20f
            val arcSize = Size(size.width - strokeWidth, (size.height * 2) - strokeWidth)

            drawArc(
                color = Color(0xFF1E293B),
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(strokeWidth / 2, strokeWidth / 2),
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )

            drawArc(
                color = activeColor,
                startAngle = 180f,
                sweepAngle = 180f * animatedFraction,
                useCenter = false,
                topLeft = Offset(strokeWidth / 2, strokeWidth / 2),
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = 28.dp)
        ) {
            Text(
                text = "$rsrp dBm",
                fontWeight = FontWeight.ExtraBold,
                fontSize = 26.sp,
                color = Color.White
            )
            Text(
                text = qualityLabel,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = activeColor
            )
        }
    }
}

@Composable
private fun LiveRsrpStreamChart(samples: List<Measurement>, modifier: Modifier = Modifier) {
    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF152036))
            .padding(horizontal = 10.dp, vertical = 12.dp)
    ) {
        val minDbm = -130f
        val maxDbm = -60f
        val range = maxDbm - minDbm

        // Horizontal guide lines (-85 dBm Good, -105 dBm Poor)
        val yGood = size.height * (1f - ((-85f - minDbm) / range))
        val yPoor = size.height * (1f - ((-105f - minDbm) / range))

        drawLine(
            color = Color(0xFF1E3A5F),
            start = Offset(0f, yGood),
            end = Offset(size.width, yGood),
            strokeWidth = 1.5f
        )
        drawLine(
            color = Color(0xFF3B1D2E),
            start = Offset(0f, yPoor),
            end = Offset(size.width, yPoor),
            strokeWidth = 1.5f
        )

        if (samples.isEmpty()) return@Canvas

        val path = Path()
        val count = samples.size.coerceAtLeast(2)
        samples.forEachIndexed { idx, m ->
            val x = if (samples.size == 1) size.width / 2f else (idx.toFloat() / (count - 1)) * size.width
            val clamped = m.radio.rsrp.toFloat().coerceIn(minDbm, maxDbm)
            val y = size.height * (1f - ((clamped - minDbm) / range))
            if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        drawPath(
            path = path,
            color = Color(0xFF38BDF8),
            style = Stroke(width = 3.5f, cap = StrokeCap.Round)
        )

        samples.forEachIndexed { idx, m ->
            val x = if (samples.size == 1) size.width / 2f else (idx.toFloat() / (count - 1)) * size.width
            val clamped = m.radio.rsrp.toFloat().coerceIn(minDbm, maxDbm)
            val y = size.height * (1f - ((clamped - minDbm) / range))
            val dotColor = when {
                m.radio.rsrp >= -85 -> Color(0xFF22C55E)
                m.radio.rsrp >= -105 -> Color(0xFF38BDF8)
                else -> Color(0xFFEF4444)
            }
            drawCircle(color = dotColor, radius = 4.5f, center = Offset(x, y))
        }
    }
}

@Composable
private fun TelemetryTile(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    unit: String,
    valueColor: Color = Color.White
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF152036))
            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 9.dp)
    ) {
        Text(
            text = label,
            color = Color(0xFF94A3B8),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                color = valueColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                softWrap = false
            )
            if (unit.isNotEmpty()) {
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = unit,
                    color = Color(0xFF64748B),
                    fontSize = 10.sp,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(bottom = 1.dp)
                )
            }
        }
    }
}

@Composable
private fun ServingDetailBox(
    modifier: Modifier = Modifier,
    label: String,
    primary: String,
    secondary: String,
    primaryColor: Color = Color.White
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF152036))
            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(10.dp))
            .padding(10.dp)
    ) {
        Text(
            text = label,
            color = Color(0xFF94A3B8),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = primary,
            color = primaryColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = secondary,
            color = Color(0xFF64748B),
            fontSize = 10.sp,
            maxLines = 1
        )
    }
}

@Composable
private fun StatusBadge(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(999.dp))
            .padding(horizontal = 9.dp, vertical = 3.dp)
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 10.sp,
            fontWeight = FontWeight.ExtraBold
        )
    }
}
