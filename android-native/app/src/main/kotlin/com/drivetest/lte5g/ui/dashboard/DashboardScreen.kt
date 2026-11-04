package com.drivetest.lte5g.ui.dashboard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drivetest.lte5g.R
import com.drivetest.lte5g.data.SessionRepository
import com.drivetest.lte5g.model.MeasurementCadence
import com.drivetest.lte5g.model.NeighborCell
import com.drivetest.lte5g.model.RatLockMode
import com.drivetest.lte5g.model.RatType
import com.drivetest.lte5g.service.DriveTestForegroundService
import com.drivetest.lte5g.telephony.CellCollector
import com.drivetest.lte5g.ui.theme.*

@Composable
fun DashboardScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isRecording by DriveTestForegroundService.isRecording.collectAsStateWithLifecycle()
    val ratLockMode by DriveTestForegroundService.ratLockMode.collectAsStateWithLifecycle()
    val measurementCadence by DriveTestForegroundService.measurementCadence.collectAsStateWithLifecycle()
    val latestSample by DriveTestForegroundService.latestSample.collectAsStateWithLifecycle()
    val samplesList by DriveTestForegroundService.samplesList.collectAsStateWithLifecycle()
    val activeSession by SessionRepository.activeSession.collectAsStateWithLifecycle()

    var showNewSessionDialog by remember { mutableStateOf(false) }
    var sessionNameInput by remember { mutableStateOf("") }

    // Permission Request Launcher
    val requiredPermissions = remember {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.POST_NOTIFICATIONS
        )
    }

    var hasPermissions by remember {
        mutableStateOf(
            requiredPermissions.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasPermissions = results.values.all { it }
    }

    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(TelemetryDarkBg)
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // App Title with Logo & RAT Badge
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    color = Color(0xFF0F172A),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, TelemetryCyan.copy(alpha = 0.5f)),
                    modifier = Modifier.size(38.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_launcher_foreground),
                            contentDescription = "App Logo",
                            tint = Color.Unspecified,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
                Column {
                    Text(
                        text = "Drive Test Monitor",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = if (isRecording) "RECORDING IN PROGRESS" else "STANDBY / MONITORING",
                        fontSize = 11.sp,
                        color = if (isRecording) SignalTier2 else Color(0xFF94A3B8)
                    )
                }
            }

            val ratText = when (latestSample?.metrics?.ratType) {
                RatType.NR_SA -> "5G SA"
                RatType.NR_NSA -> "5G NSA"
                RatType.LTE -> "LTE 4G"
                else -> "CONNECTING"
            }
            Surface(
                color = Color(0xFF1E3A8A),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = ratText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TelemetryCyan,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }

        // ACTIVE SESSION BANNER
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, if (isRecording) SignalTier2 else TelemetryCardStroke, RoundedCornerShape(12.dp))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "DRIVE TEST SESSION",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF94A3B8)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = activeSession?.name ?: SessionRepository.getDefaultSessionName(),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1
                    )
                    Text(
                        text = if (isRecording) "● Recording (${activeSession?.sampleCount ?: samplesList.size} pts)" else "Ready to start recording",
                        fontSize = 11.sp,
                        color = if (isRecording) SignalTier2 else Color(0xFF64748B)
                    )
                }
                OutlinedButton(
                    onClick = {
                        sessionNameInput = SessionRepository.getDefaultSessionName()
                        showNewSessionDialog = true
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TelemetryCyan),
                    border = BorderStroke(1.dp, TelemetryCyan.copy(alpha = 0.7f)),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text("+ NEW", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // RAT LOCK SELECTOR (CORE USER REQUIREMENT)
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    text = "TARGET RAT LOCK",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF94A3B8)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    RatLockButton(
                        label = "Auto (All)",
                        isSelected = ratLockMode == RatLockMode.AUTO,
                        modifier = Modifier.weight(1f)
                    ) {
                        DriveTestForegroundService.setRatLock(context, RatLockMode.AUTO)
                    }
                    RatLockButton(
                        label = "🔒 LTE Only",
                        isSelected = ratLockMode == RatLockMode.LTE_ONLY,
                        modifier = Modifier.weight(1.1f)
                    ) {
                        DriveTestForegroundService.setRatLock(context, RatLockMode.LTE_ONLY)
                    }
                    RatLockButton(
                        label = "🔒 5G Only",
                        isSelected = ratLockMode == RatLockMode.NR_ONLY,
                        modifier = Modifier.weight(1.1f)
                    ) {
                        DriveTestForegroundService.setRatLock(context, RatLockMode.NR_ONLY)
                    }
                }
            }
        }

        // MEASUREMENT CADENCE QUICK SELECTOR ROW (ECO / STD / PRECISE)
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "MEASUREMENT CADENCE",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF94A3B8)
                    )
                    Text(
                        text = "${measurementCadence.frequencyLabel} (${measurementCadence.intervalMs / 1000}s)",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = TelemetryCyan
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CadenceOptionButton(
                        label = "⚡ Precise 1s",
                        isSelected = measurementCadence == MeasurementCadence.PRECISE,
                        modifier = Modifier.weight(1f)
                    ) {
                        DriveTestForegroundService.setMeasurementCadence(context, MeasurementCadence.PRECISE)
                    }
                    CadenceOptionButton(
                        label = "⚖️ Standard 2s",
                        isSelected = measurementCadence == MeasurementCadence.STANDARD,
                        modifier = Modifier.weight(1.1f)
                    ) {
                        DriveTestForegroundService.setMeasurementCadence(context, MeasurementCadence.STANDARD)
                    }
                    CadenceOptionButton(
                        label = "🍃 Eco 5s",
                        isSelected = measurementCadence == MeasurementCadence.ECO,
                        modifier = Modifier.weight(1f)
                    ) {
                        DriveTestForegroundService.setMeasurementCadence(context, MeasurementCadence.ECO)
                    }
                }
            }
        }

        // Hardware Modem Lock Shortcut Pill (*#*#4636#*#*)
        Surface(
            color = Color(0xFF172554),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { CellCollector.launchRadioInfo(context) }
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "⚡ Hardware Modem Lock (*#*#4636#*#*)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF93C5FD)
                    )
                    Text(
                        text = "Force Baseband lock to LTE-only or NR-only",
                        fontSize = 10.sp,
                        color = Color(0xFFBFDBFE)
                    )
                }
                Text(text = "LAUNCH ↗", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TelemetryCyan)
            }
        }

        // Serving Cell Card
        val metrics = latestSample?.metrics
        val isNr = (ratLockMode == RatLockMode.NR_ONLY) || (metrics?.nr != null)
        Card(
            colors = CardDefaults.cardColors(containerColor = TelemetryCardBg),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, TelemetryCardStroke, RoundedCornerShape(14.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = if (isNr) "SERVING CELL (5G NR)" else "SERVING CELL (LTE PRIMARY)",
                    fontSize = 11.sp,
                    color = Color(0xFF94A3B8),
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        text = "PLMN: ${metrics?.plmn ?: "Searching..."} (${metrics?.operatorAlpha ?: ""})",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                    Text(
                        text = "BAND: ${if (isNr) "n${metrics?.nr?.band ?: "78"}" else "B${metrics?.lte?.band ?: "3"}"}",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = TelemetryCyan
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        text = "PCI: ${if (isNr) metrics?.nr?.pci ?: "--" else metrics?.lte?.pci ?: "--"}",
                        fontSize = 12.sp,
                        color = Color(0xFFCBD5E1)
                    )
                    Text(
                        text = "ARFCN: ${if (isNr) metrics?.nr?.nrArfcn ?: "--" else metrics?.lte?.earfcn ?: "--"}",
                        fontSize = 12.sp,
                        color = Color(0xFFCBD5E1)
                    )
                    Text(
                        text = "TAC: ${if (isNr) metrics?.nr?.tac ?: "--" else metrics?.lte?.tac ?: "--"}",
                        fontSize = 12.sp,
                        color = Color(0xFFCBD5E1)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (isNr) "NCI: ${metrics?.nr?.nci ?: "--"}" else "ECI: ${metrics?.lte?.eci ?: "--"}",
                    fontSize = 11.sp,
                    color = Color(0xFF64748B)
                )
            }
        }

        // RF Key Metric Gauges (RSRP, RSRQ, SINR)
        val rsrpVal = if (isNr) metrics?.nr?.ssRsrp ?: metrics?.lte?.rsrp else metrics?.lte?.rsrp
        val rsrqVal = if (isNr) metrics?.nr?.ssRsrq ?: metrics?.lte?.rsrq else metrics?.lte?.rsrq
        val sinrVal = if (isNr) metrics?.nr?.ssSinr ?: metrics?.lte?.rssnr else metrics?.lte?.rssnr

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SignalGaugeCard(
                title = if (isNr) "SS-RSRP" else "LTE RSRP",
                value = rsrpVal?.toString() ?: "--",
                unit = "dBm",
                qualityColor = getRsrpColor(rsrpVal),
                qualityText = getRsrpTierLabel(rsrpVal),
                modifier = Modifier.weight(1f)
            )
            SignalGaugeCard(
                title = if (isNr) "SS-RSRQ" else "LTE RSRQ",
                value = rsrqVal?.toString() ?: "--",
                unit = "dB",
                qualityColor = getRsrqColor(rsrqVal),
                qualityText = getRsrqTierLabel(rsrqVal),
                modifier = Modifier.weight(1f)
            )
            SignalGaugeCard(
                title = if (isNr) "SS-SINR" else "LTE SINR",
                value = sinrVal?.let { String.format("%.1f", it) } ?: "--",
                unit = "dB",
                qualityColor = getSinrColor(sinrVal),
                qualityText = getSinrTierLabel(sinrVal),
                modifier = Modifier.weight(1f)
            )
        }

        // 60-Second Real-Time Signal Trend Chart
        Card(
            colors = CardDefaults.cardColors(containerColor = TelemetryCardBg),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, TelemetryCardStroke, RoundedCornerShape(14.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "RSRP Real-Time Trend (${measurementCadence.label})",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "${measurementCadence.frequencyLabel} Live",
                        fontSize = 10.sp,
                        color = TelemetryCyan,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))

                // Canvas line graph of recent samples
                val recentRsrpList = remember(samplesList) {
                    samplesList.takeLast(60).mapNotNull {
                        it.metrics.nr?.ssRsrp ?: it.metrics.lte?.rsrp
                    }
                }

                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(110.dp)
                ) {
                    val w = size.width
                    val h = size.height

                    // Grid lines: -70 (top), -95 (mid), -120 (bottom)
                    val y70 = h * 0.1f
                    val y95 = h * 0.5f
                    val y120 = h * 0.9f

                    drawLine(Color(0xFF1E2D4A), Offset(0f, y70), Offset(w, y70), 1f)
                    drawLine(Color(0xFF1E2D4A), Offset(0f, y95), Offset(w, y95), 1f)
                    drawLine(Color(0xFF1E2D4A), Offset(0f, y120), Offset(w, y120), 1f)

                    if (recentRsrpList.size >= 2) {
                        val path = Path()
                        val stepX = w / (recentRsrpList.size - 1).coerceAtLeast(1)

                        recentRsrpList.forEachIndexed { index, rsrp ->
                            // Map -130 to -60 to height
                            val clamped = rsrp.coerceIn(-130, -60)
                            val normY = 1f - ((clamped - (-130f)) / 70f)
                            val px = index * stepX
                            val py = normY * h

                            if (index == 0) path.moveTo(px, py) else path.lineTo(px, py)
                        }

                        drawPath(
                            path = path,
                            color = SignalTier1,
                            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }
                }
            }
        }

        // Neighbor Cells Table
        val neighbors = metrics?.neighbors ?: emptyList()
        Card(
            colors = CardDefaults.cardColors(containerColor = TelemetryCardBg),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, TelemetryCardStroke, RoundedCornerShape(14.dp))
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "NEIGHBOR CELLS (${neighbors.size})",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF94A3B8)
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (neighbors.isEmpty()) {
                    Text(
                        text = "No neighbor cells detected in current scan cycle.",
                        fontSize = 12.sp,
                        color = Color(0xFF64748B)
                    )
                } else {
                    neighbors.take(4).forEach { cell ->
                        NeighborRow(cell)
                        HorizontalDivider(color = Color(0xFF1E293B), thickness = 0.5.dp)
                    }
                }
            }
        }

        // START / STOP SERVICE BUTTON
        if (!hasPermissions) {
            Button(
                onClick = { permissionLauncher.launch(requiredPermissions) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Text("GRANT PERMISSIONS (GPS & TELEPHONY)", fontWeight = FontWeight.Bold)
            }
        } else {
            Button(
                onClick = {
                    if (isRecording) {
                        DriveTestForegroundService.stop(context)
                    } else {
                        sessionNameInput = SessionRepository.getDefaultSessionName()
                        showNewSessionDialog = true
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRecording) SignalTier5 else Color(0xFF16A34A)
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Icon(
                    imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isRecording) "STOP DRIVE TEST" else "START DRIVE TEST",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }
        }

        // NEW SESSION DIALOG
        if (showNewSessionDialog) {
            AlertDialog(
                onDismissRequest = { showNewSessionDialog = false },
                title = {
                    Text(
                        text = "New Drive Test Session",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "ตั้งชื่อหรือเลือกชื่ออัตโนมัติสำหรับเซสชันใหม่:",
                            fontSize = 13.sp,
                            color = Color(0xFFCBD5E1)
                        )
                        OutlinedTextField(
                            value = sessionNameInput,
                            onValueChange = { sessionNameInput = it },
                            singleLine = true,
                            placeholder = { Text(SessionRepository.getDefaultSessionName(), color = Color(0xFF64748B)) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = TelemetryCyan,
                                unfocusedBorderColor = Color(0xFF334155),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedContainerColor = Color(0xFF0F172A),
                                unfocusedContainerColor = Color(0xFF0F172A)
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = "• บันทึกแยก log และพิกัดสัญญาณเฉพาะเซสชันนี้\n• สามารถดูประวัติและ Export KMZ ย้อนหลังได้ตลอดเวลา",
                            fontSize = 11.sp,
                            color = Color(0xFF94A3B8)
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val finalName = sessionNameInput.ifBlank { SessionRepository.getDefaultSessionName() }
                            DriveTestForegroundService.newSession(
                                context = context,
                                sessionName = finalName,
                                lockMode = ratLockMode,
                                cadence = measurementCadence
                            )
                            showNewSessionDialog = false
                            sessionNameInput = ""
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("START TEST", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showNewSessionDialog = false }) {
                        Text("CANCEL", color = Color(0xFF94A3B8))
                    }
                },
                containerColor = Color(0xFF1E293B),
                shape = RoundedCornerShape(16.dp)
            )
        }
    }
}

@Composable
fun RatLockButton(
    label: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        color = if (isSelected) TelemetryCyan else Color(0xFF1E293B),
        shape = RoundedCornerShape(8.dp),
        modifier = modifier.clickable { onClick() }
    ) {
        Box(
            modifier = Modifier.padding(vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = if (isSelected) Color(0xFF0B101B) else Color(0xFF94A3B8)
            )
        }
    }
}

@Composable
fun CadenceOptionButton(
    label: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        color = if (isSelected) Color(0xFF0284C7) else Color(0xFF1E293B),
        shape = RoundedCornerShape(8.dp),
        modifier = modifier.clickable { onClick() }
    ) {
        Box(
            modifier = Modifier.padding(vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = if (isSelected) Color.White else Color(0xFF94A3B8)
            )
        }
    }
}

@Composable
fun SignalGaugeCard(
    title: String,
    value: String,
    unit: String,
    qualityColor: Color,
    qualityText: String,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = TelemetryCardBg),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.border(1.dp, TelemetryCardStroke, RoundedCornerShape(12.dp))
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(text = title, fontSize = 10.sp, color = TelemetryCyan, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = value,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = qualityColor,
                    fontFamily = FontFamily.Default
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(text = unit, fontSize = 10.sp, color = Color(0xFF94A3B8))
            }
            Spacer(modifier = Modifier.height(6.dp))
            Surface(
                color = qualityColor.copy(alpha = 0.2f),
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    text = qualityText,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = qualityColor,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
fun NeighborRow(cell: NeighborCell) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = cell.rat.name,
            fontSize = 11.sp,
            color = TelemetryCyan,
            fontWeight = FontWeight.Bold
        )
        Text(text = "PCI: ${cell.pci ?: "--"}", fontSize = 11.sp, color = Color.White)
        Text(text = "ARFCN: ${cell.arfcn ?: "--"}", fontSize = 11.sp, color = Color(0xFF94A3B8))
        Text(
            text = "${cell.rsrp ?: "--"} dBm",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = getRsrpColor(cell.rsrp)
        )
    }
}

fun getRsrpColor(rsrp: Int?): Color {
    if (rsrp == null) return Color.Gray
    return when {
        rsrp >= -85 -> SignalTier1
        rsrp >= -95 -> SignalTier2
        rsrp >= -105 -> SignalTier3
        rsrp >= -115 -> SignalTier4
        else -> SignalTier5
    }
}

fun getRsrpTierLabel(rsrp: Int?): String {
    if (rsrp == null) return "NO SIGNAL"
    return when {
        rsrp >= -85 -> "EXCELLENT"
        rsrp >= -95 -> "GOOD"
        rsrp >= -105 -> "FAIR"
        rsrp >= -115 -> "POOR"
        else -> "BAD"
    }
}

fun getRsrqColor(rsrq: Int?): Color {
    if (rsrq == null) return Color.Gray
    return when {
        rsrq >= -10 -> SignalTier1
        rsrq >= -15 -> SignalTier2
        rsrq >= -19 -> SignalTier3
        else -> SignalTier5
    }
}

fun getRsrqTierLabel(rsrq: Int?): String {
    if (rsrq == null) return "--"
    return when {
        rsrq >= -10 -> "EXCELLENT"
        rsrq >= -15 -> "GOOD"
        rsrq >= -19 -> "FAIR"
        else -> "POOR"
    }
}

fun getSinrColor(sinr: Double?): Color {
    if (sinr == null) return Color.Gray
    return when {
        sinr >= 20.0 -> SignalTier1
        sinr >= 13.0 -> SignalTier2
        sinr >= 5.0 -> SignalTier3
        sinr >= 0.0 -> SignalTier4
        else -> SignalTier5
    }
}

fun getSinrTierLabel(sinr: Double?): String {
    if (sinr == null) return "--"
    return when {
        sinr >= 20.0 -> "EXCELLENT"
        sinr >= 13.0 -> "GOOD"
        sinr >= 5.0 -> "FAIR"
        sinr >= 0.0 -> "POOR"
        else -> "BAD"
    }
}
