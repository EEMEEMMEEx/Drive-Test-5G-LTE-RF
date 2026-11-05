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
import com.drivetest.lte5g.model.CellMetrics
import com.drivetest.lte5g.model.MeasurementCadence
import com.drivetest.lte5g.model.NeighborCell
import com.drivetest.lte5g.model.RatLockMode
import com.drivetest.lte5g.model.RatType
import com.drivetest.lte5g.model.SimSlotInfo
import com.drivetest.lte5g.service.LiveAlertNotificationHelper
import java.util.Locale
import com.drivetest.lte5g.service.DriveTestForegroundService
import com.drivetest.lte5g.telephony.CellCollector
import com.drivetest.lte5g.telephony.RatLockCapability
import com.drivetest.lte5g.telephony.RatLockManager
import com.drivetest.lte5g.telephony.RatLockStatus
import com.drivetest.lte5g.telephony.RatLockType
import com.drivetest.lte5g.ui.theme.*
import com.drivetest.lte5g.ui.map.isNrMetrics
import com.drivetest.lte5g.ui.map.sim2RsrpOf
import com.drivetest.lte5g.ui.map.sim2RsrqOf
import com.drivetest.lte5g.ui.map.sim2SinrOf

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
    val selectedSimSlot by DriveTestForegroundService.selectedSimSlot.collectAsStateWithLifecycle()
    val availableSims by DriveTestForegroundService.availableSims.collectAsStateWithLifecycle()
    val liveMetrics by DriveTestForegroundService.liveMetrics.collectAsStateWithLifecycle()
    val isDualSimMode by DriveTestForegroundService.isDualSimMode.collectAsStateWithLifecycle()
    val liveMetricsSim2 by DriveTestForegroundService.liveMetricsSim2.collectAsStateWithLifecycle()
    val ratLockType by DriveTestForegroundService.ratLockType.collectAsStateWithLifecycle()
    val ratLockCapability by DriveTestForegroundService.ratLockCapability.collectAsStateWithLifecycle()
    val ratLockStatus by DriveTestForegroundService.ratLockStatus.collectAsStateWithLifecycle()

    val metrics = latestSample?.metrics ?: liveMetrics
    val isNr = (ratLockMode == RatLockMode.NR_ONLY) || (metrics?.nr != null) || (metrics?.ratType == RatType.NR_NSA) || (metrics?.ratType == RatType.NR_SA)

    var showNewSessionDialog by remember { mutableStateOf(false) }
    var pendingRatLockMode by remember { mutableStateOf<RatLockMode?>(null) }
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

    LaunchedEffect(hasPermissions) {
        if (hasPermissions) {
            DriveTestForegroundService.refreshAvailableSims(context)
            DriveTestForegroundService.refreshRatLockCapability(context)
        }
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

        // RAT LOCK SELECTOR (WITH HARDWARE/SOFTWARE TRANSPARENCY)
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
                        text = "TARGET RAT LOCK",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF94A3B8)
                    )
                    Surface(
                        color = when (ratLockType) {
                            RatLockType.HARDWARE_BASEBAND -> Color(0xFF14532D)
                            RatLockType.SOFTWARE_FILTER -> Color(0xFF1E3A8A)
                            RatLockType.AUTO -> Color(0xFF1E293B)
                        },
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = when (ratLockType) {
                                RatLockType.HARDWARE_BASEBAND -> "MODEM LOCKED"
                                RatLockType.SOFTWARE_FILTER -> "SOFTWARE FILTER"
                                RatLockType.AUTO -> "AUTO SELECTION"
                            },
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = when (ratLockType) {
                                RatLockType.HARDWARE_BASEBAND -> Color(0xFF4ADE80)
                                RatLockType.SOFTWARE_FILTER -> TelemetryCyan
                                RatLockType.AUTO -> Color(0xFF94A3B8)
                            },
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
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
                        DriveTestForegroundService.setRatLock(context, RatLockMode.AUTO, RatLockType.AUTO)
                    }
                    RatLockButton(
                        label = "🔒 LTE Only",
                        isSelected = ratLockMode == RatLockMode.LTE_ONLY,
                        modifier = Modifier.weight(1.1f)
                    ) {
                        if (ratLockCapability == RatLockCapability.DIRECT_MODEM_LOCK_SUPPORTED) {
                            DriveTestForegroundService.setRatLock(context, RatLockMode.LTE_ONLY, RatLockType.HARDWARE_BASEBAND)
                        } else {
                            pendingRatLockMode = RatLockMode.LTE_ONLY
                        }
                    }
                    RatLockButton(
                        label = "🔒 5G Only",
                        isSelected = ratLockMode == RatLockMode.NR_ONLY,
                        modifier = Modifier.weight(1.1f)
                    ) {
                        if (ratLockCapability == RatLockCapability.DIRECT_MODEM_LOCK_SUPPORTED) {
                            DriveTestForegroundService.setRatLock(context, RatLockMode.NR_ONLY, RatLockType.HARDWARE_BASEBAND)
                        } else {
                            pendingRatLockMode = RatLockMode.NR_ONLY
                        }
                    }
                }

                // LIVE CONNECTED RAT CONFIRMATION BAR
                Spacer(modifier = Modifier.height(8.dp))
                val isMatched = ratLockStatus.isMatched
                val connectedRatLabel = when (metrics?.ratType) {
                    RatType.NR_SA -> "5G SA"
                    RatType.NR_NSA -> "5G NSA"
                    RatType.LTE -> "LTE 4G"
                    else -> "Scanning..."
                }

                Surface(
                    color = when {
                        ratLockMode == RatLockMode.AUTO -> Color(0xFF1E293B)
                        isMatched -> Color(0xFF064E3B).copy(alpha = 0.6f)
                        else -> Color(0xFF7C2D12).copy(alpha = 0.5f)
                    },
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(
                        0.5.dp,
                        when {
                            ratLockMode == RatLockMode.AUTO -> Color(0xFF334155)
                            isMatched -> Color(0xFF059669)
                            else -> Color(0xFFDC2626)
                        }
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 6.dp)) {
                            Text(
                                text = "CONNECTED: $connectedRatLabel",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = ratLockStatus.statusMessage,
                                fontSize = 10.sp,
                                color = if (isMatched || ratLockMode == RatLockMode.AUTO) Color(0xFF94A3B8) else Color(0xFFFCA5A5)
                            )
                        }

                        if (!isMatched && ratLockMode != RatLockMode.AUTO) {
                            Surface(
                                color = Color(0xFF991B1B),
                                shape = RoundedCornerShape(4.dp),
                                modifier = Modifier.clickable {
                                    RatLockManager.launchHardwareLock(context)
                                }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "RadioInfo ↗",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }
                            }
                        }
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
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
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
                Text(
                    text = "LAUNCH ↗",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = TelemetryCyan
                )
            }
        }

        // Serving Cell Card & SIM Selector (Compact Layout)
        Card(
            colors = CardDefaults.cardColors(containerColor = TelemetryCardBg),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, TelemetryCardStroke, RoundedCornerShape(14.dp))
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                // DUAL SIM SELECTOR (SIM 1 / SIM 2)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val sim1 = availableSims.firstOrNull { it.slotIndex == 0 }
                    val sim2 = availableSims.firstOrNull { it.slotIndex == 1 }

                    val sim1Label = sim1?.displayName?.takeIf { it.isNotBlank() } ?: "SIM 1"
                    val sim2Label = sim2?.displayName?.takeIf { it.isNotBlank() }
                        ?: if (availableSims.size > 1) "SIM 2" else "SIM 2 (No SIM)"

                    SimSlotButton(
                        label = "📶 $sim1Label",
                        isSelected = selectedSimSlot == 0,
                        isEnabled = true,
                        modifier = Modifier.weight(1f)
                    ) {
                        DriveTestForegroundService.setSimSlot(context, 0, sim1?.subscriptionId, sim1?.displayName)
                    }

                    SimSlotButton(
                        label = "📶 $sim2Label",
                        isSelected = selectedSimSlot == 1,
                        isEnabled = (sim2 != null || availableSims.size > 1),
                        modifier = Modifier.weight(1f)
                    ) {
                        if (sim2 != null || availableSims.size > 1) {
                            DriveTestForegroundService.setSimSlot(context, 1, sim2?.subscriptionId, sim2?.displayName)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // SERVING CELL HEADER
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val servingCellTitle = when {
                        metrics?.ratType == RatType.NR_NSA -> "SERVING CELL (5G NSA · EN-DC)"
                        metrics?.ratType == RatType.NR_SA -> "SERVING CELL (5G NR SA)"
                        isNr -> "SERVING CELL (5G NR)"
                        else -> "SERVING CELL (LTE PRIMARY)"
                    }
                    Text(
                        text = servingCellTitle,
                        fontSize = 11.sp,
                        color = Color(0xFF94A3B8),
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false
                    )
                    metrics?.let { m ->
                        val ratTag = when (m.ratType) {
                            RatType.NR_SA -> "5G SA"
                            RatType.NR_NSA -> "5G NSA"
                            RatType.LTE -> "LTE"
                            else -> null
                        }
                        ratTag?.let { tag ->
                            Surface(
                                color = Color(0xFF1E3A8A).copy(alpha = 0.5f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = tag,
                                    fontSize = 10.sp,
                                    color = TelemetryCyan,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // PLMN & BAND (Only show BAND if actually present, no fake defaults)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val plmnStr = metrics?.plmn?.takeIf { it.isNotBlank() } ?: "Searching..."
                    val alphaStr = metrics?.operatorAlpha?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
                    Text(
                        text = "PLMN: $plmnStr$alphaStr",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                    val bandStr = when {
                        metrics?.ratType == RatType.NR_NSA && metrics.nr?.band != null && metrics.lte?.band != null ->
                            "n${metrics.nr.band} + B${metrics.lte.band}"
                        metrics?.ratType == RatType.NR_NSA && metrics.nr?.band != null ->
                            "n${metrics.nr.band}"
                        isNr && metrics?.nr?.band != null ->
                            "n${metrics.nr.band}"
                        metrics?.lte?.band != null ->
                            "B${metrics.lte.band}"
                        else -> null
                    }
                    if (bandStr != null) {
                        Text(
                            text = "BAND: $bandStr",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = TelemetryCyan
                        )
                    }
                }

                // DYNAMIC PARAMETER CHIPS (FlowRow with no-wrap chips)
                val isNsa = metrics?.ratType == RatType.NR_NSA
                val paramList = buildList {
                    if (isNsa) {
                        // In 5G NSA: Clean concise telecom notation
                        val pci = metrics?.nr?.pci ?: metrics?.lte?.pci
                        val arfcn = metrics?.nr?.nrArfcn ?: metrics?.lte?.earfcn
                        val tac = metrics?.nr?.tac ?: metrics?.lte?.tac
                        val cellId = metrics?.nr?.nci ?: metrics?.lte?.eci
                        val isNrCell = metrics?.nr?.nci != null

                        pci?.let { add("PCI: $it") }
                        arfcn?.let { add("${if (metrics?.nr?.nrArfcn != null) "NR-ARFCN" else "EARFCN"}: $it") }
                        tac?.let { add("TAC: $it") }
                        cellId?.let { add("${if (isNrCell) "NCI" else "ECI"}: $it") }
                    } else if (isNr) {
                        metrics?.nr?.pci?.let { add("PCI: $it") }
                        metrics?.nr?.nrArfcn?.let { add("NR-ARFCN: $it") }
                        metrics?.nr?.tac?.let { add("TAC: $it") }
                        metrics?.nr?.nci?.let { add("NCI: $it") }
                    } else {
                        metrics?.lte?.pci?.let { add("PCI: $it") }
                        metrics?.lte?.earfcn?.let { add("EARFCN: $it") }
                        metrics?.lte?.tac?.let { add("TAC: $it") }
                        metrics?.lte?.eci?.let { add("ECI: $it") }
                    }
                }

                if (paramList.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        paramList.forEach { param ->
                            Surface(
                                color = Color(0xFF1E293B),
                                shape = RoundedCornerShape(6.dp),
                                border = BorderStroke(0.5.dp, Color(0xFF334155))
                            ) {
                                Text(
                                    text = param,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Color(0xFFE2E8F0),
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }
                }

                // 5G NSA ANCHOR LTE INFO
                if (isNr && metrics?.lte != null) {
                    val anchorLte = metrics.lte
                    val anchorItems = listOfNotNull(
                        anchorLte.band?.let { "Band B$it" },
                        anchorLte.pci?.let { "PCI $it" },
                        anchorLte.earfcn?.let { "EARFCN $it" },
                        anchorLte.tac?.let { "TAC $it" },
                        anchorLte.eci?.let { "ECI $it" }
                    )
                    if (anchorItems.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Surface(
                            color = Color(0xFF1E293B).copy(alpha = 0.6f),
                            shape = RoundedCornerShape(6.dp),
                            border = BorderStroke(0.5.dp, Color(0xFF334155))
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                                Text(
                                    text = "🔗 Anchor LTE: " + anchorItems.joinToString(" · "),
                                    fontSize = 10.5.sp,
                                    color = Color(0xFF93C5FD),
                                    fontWeight = FontWeight.Medium
                                )
                                if (metrics.ratType == RatType.NR_NSA) {
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "ℹ️ 5G NSA ควบคุมผ่าน LTE Anchor (Cell ID/TAC อยู่บน 4G, Throughput วิ่งบน 5G)",
                                        fontSize = 9.5.sp,
                                        color = Color(0xFF94A3B8),
                                        lineHeight = 13.sp
                                    )
                                }
                            }
                        }
                    }
                }
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

        // SIM 2 Live RF Gauges (dual-SIM sessions only)
        val sim2Metrics = liveMetricsSim2
        if (isDualSimMode && sim2Metrics != null) {
            val m2 = sim2Metrics
            val isNr2 = isNrMetrics(m2)
            val rsrp2 = sim2RsrpOf(m2, isNr2)
            val rsrq2 = sim2RsrqOf(m2, isNr2)
            val sinr2 = sim2SinrOf(m2, isNr2)
            val rat2Text = when {
                m2.nr != null -> if (m2.nr.isNsa) "5G NSA" else "5G SA"
                m2.lte != null -> "LTE 4G"
                else -> "--"
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = TelemetryCardBg),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Sim2Tier2.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "SIM 2 LIVE \u2022 ${m2.simDisplayName ?: "SIM 2"}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Sim2Tier1
                        )
                        Surface(
                            color = Color(0xFF1E3A8A),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = rat2Text,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Sim2Tier1,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        SignalGaugeCard(
                            title = if (isNr2) "SIM 2 SS-RSRP" else "SIM 2 LTE RSRP",
                            value = rsrp2?.toString() ?: "--",
                            unit = "dBm",
                            qualityColor = sim2RsrpColor(rsrp2),
                            qualityText = getRsrpTierLabel(rsrp2),
                            modifier = Modifier.weight(1f)
                        )
                        SignalGaugeCard(
                            title = if (isNr2) "SIM 2 SS-RSRQ" else "SIM 2 LTE RSRQ",
                            value = rsrq2?.toString() ?: "--",
                            unit = "dB",
                            qualityColor = sim2RsrqColor(rsrq2),
                            qualityText = getRsrqTierLabel(rsrq2),
                            modifier = Modifier.weight(1f)
                        )
                        SignalGaugeCard(
                            title = if (isNr2) "SIM 2 SS-SINR" else "SIM 2 LTE SINR",
                            value = sinr2?.let { String.format("%.1f", it) } ?: "--",
                            unit = "dB",
                            qualityColor = sim2SinrColor(sinr2),
                            qualityText = getSinrTierLabel(sinr2),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
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


        // RAT LOCK GUIDANCE DIALOG
        if (pendingRatLockMode != null) {
            val targetMode = pendingRatLockMode!!
            val targetLabel = if (targetMode == RatLockMode.NR_ONLY) "5G NR Only" else "LTE Only"
            AlertDialog(
                onDismissRequest = { pendingRatLockMode = null },
                title = {
                    Text(
                        text = "การล็อกโมเด็ม (Hardware Modem Lock)",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Surface(
                            color = Color(0xFF7C2D12).copy(alpha = 0.4f),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(0.5.dp, Color(0xFFEA580C)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    text = "ข้อจำกัดความปลอดภัยของ Android Sandbox:",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFFDBA74)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Android ไม่อนุญาตให้แอปทั่วไปสั่งล็อกชิปโมเด็มฮาร์ดแวร์โดยตรง (ต้องใช้สิทธิ์ MODIFY_PHONE_STATE หรือ Carrier Privileges) เพื่อป้องกันระบบโทรฉุกเฉินและสัญญาณขัดข้อง",
                                    fontSize = 11.sp,
                                    color = Color(0xFFFED7AA)
                                )
                            }
                        }

                        Text(
                            text = "เลือกรูปแบบการทำงานสำหรับ $targetLabel:",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )

                        // Option 1: Hardware Lock via RadioInfo
                        Surface(
                            color = Color(0xFF1E293B),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, TelemetryCyan.copy(alpha = 0.6f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    DriveTestForegroundService.setRatLock(context, targetMode, RatLockType.HARDWARE_BASEBAND)
                                    RatLockManager.launchHardwareLock(context)
                                    pendingRatLockMode = null
                                }
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    text = "1. เปิดเมนูวิศวกรรม RadioInfo (*#*#4636#*#*)",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TelemetryCyan
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "แนะนำสำหรับการขับทดสอบจริง: ไปที่ 'Set Preferred Network Type' และเลือก '${if (targetMode == RatLockMode.NR_ONLY) "NR only" else "LTE only"}' บนชิปโมเด็มโดยตรง",
                                    fontSize = 11.sp,
                                    color = Color(0xFFCBD5E1)
                                )
                            }
                        }

                        // Option 2: Software Filter Only
                        Surface(
                            color = Color(0xFF0F172A),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(0.5.dp, Color(0xFF334155)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    DriveTestForegroundService.setRatLock(context, targetMode, RatLockType.SOFTWARE_FILTER)
                                    pendingRatLockMode = null
                                }
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    text = "2. ใช้เป็น Software Filter เฉพาะในแอป",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "แอปจะบันทึกและพล็อตพิกัดเฉพาะเมื่อตรวจพบสัญญาณ $targetLabel (ชิปโมเด็มจะยังคงสลับสัญญาณอัตโนมัติตามเครือข่าย)",
                                    fontSize = 11.sp,
                                    color = Color(0xFF94A3B8)
                                )
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { pendingRatLockMode = null }) {
                        Text("ยกเลิก", color = Color(0xFF94A3B8))
                    }
                },
                containerColor = Color(0xFF0F172A),
                shape = RoundedCornerShape(16.dp)
            )
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

/** SIM 2 gauge colors, matching the SIM 2 map/KMZ palette. */
fun sim2RsrpColor(rsrp: Int?): Color = when {
    rsrp == null -> Color(0xFF64748B)
    rsrp >= -85 -> Sim2Tier1
    rsrp >= -95 -> Sim2Tier2
    rsrp >= -105 -> Sim2Tier3
    rsrp >= -115 -> Sim2Tier4
    else -> Sim2Tier5
}

fun sim2RsrqColor(rsrq: Int?): Color = when {
    rsrq == null -> Color(0xFF64748B)
    rsrq >= -10 -> Sim2Tier1
    rsrq >= -15 -> Sim2Tier2
    rsrq >= -19 -> Sim2Tier3
    else -> Sim2Tier5
}

fun sim2SinrColor(sinr: Double?): Color = when {
    sinr == null -> Color(0xFF64748B)
    sinr >= 20.0 -> Sim2Tier1
    sinr >= 13.0 -> Sim2Tier2
    sinr >= 5.0 -> Sim2Tier3
    sinr >= 0.0 -> Sim2Tier4
    else -> Sim2Tier5
}

@Composable
fun SimSlotButton(
    label: String,
    isSelected: Boolean,
    isEnabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        color = when {
            !isEnabled -> Color(0xFF1E293B).copy(alpha = 0.4f)
            isSelected -> Color(0xFF0369A1)
            else -> Color(0xFF1E293B)
        },
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(
            1.dp,
            when {
                !isEnabled -> Color(0xFF334155).copy(alpha = 0.3f)
                isSelected -> TelemetryCyan
                else -> Color(0xFF334155)
            }
        ),
        modifier = modifier
            .height(34.dp)
            .clickable(enabled = isEnabled, onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = when {
                    !isEnabled -> Color(0xFF64748B)
                    isSelected -> Color.White
                    else -> Color(0xFF94A3B8)
                },
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            if (isSelected) {
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "✓",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = TelemetryCyan
                )
            }
        }
    }
}
