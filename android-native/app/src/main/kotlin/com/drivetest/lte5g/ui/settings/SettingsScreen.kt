package com.drivetest.lte5g.ui.settings

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drivetest.lte5g.BuildConfig
import com.drivetest.lte5g.ai.GeminiRfAdvisor
import com.drivetest.lte5g.cloud.CloudConfigRepository
import com.drivetest.lte5g.cloud.CloudTelemetrySyncManager
import com.drivetest.lte5g.cloud.GoogleDriveAutoUploader
import com.drivetest.lte5g.cloud.VehicleIdAvailability
import com.drivetest.lte5g.cloud.VehicleIdClaimOutcome
import com.drivetest.lte5g.cloud.VehicleIdGenerator
import com.drivetest.lte5g.cloud.VehicleIdSyncIssue
import com.drivetest.lte5g.cloud.VehicleIdSyncState
import com.drivetest.lte5g.cloud.VehicleIdValidationResult
import com.drivetest.lte5g.model.MeasurementCadence
import com.drivetest.lte5g.service.DriveTestForegroundService
import com.drivetest.lte5g.telephony.CellCollector
import com.drivetest.lte5g.ui.theme.SignalTier1
import com.drivetest.lte5g.ui.theme.SignalTier2
import com.drivetest.lte5g.ui.theme.SignalTier4
import com.drivetest.lte5g.ui.theme.SignalTier5
import com.drivetest.lte5g.ui.theme.TelemetryCardBg
import com.drivetest.lte5g.ui.theme.TelemetryCardStroke
import com.drivetest.lte5g.ui.theme.TelemetryCyan
import com.drivetest.lte5g.ui.theme.TelemetryDarkBg
import com.drivetest.lte5g.updater.OtaErrorKind
import com.drivetest.lte5g.updater.OtaState
import com.drivetest.lte5g.updater.ReleaseInfo
import com.drivetest.lte5g.updater.OtaUpdateManager
import com.drivetest.lte5g.updater.PlayUpdateAction
import com.drivetest.lte5g.updater.PlayUpdateManager
import com.drivetest.lte5g.updater.PlayUpdateState
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun formatLastChecked(timestamp: Long?, nowMs: Long = System.currentTimeMillis()): String {
    if (timestamp == null || timestamp <= 0L) return "Not checked yet"
    val diffMs = maxOf(0L, nowMs - timestamp)
    return when {
        diffMs < 60_000L -> "Checked\u00A0just\u00A0now"
        diffMs < 3_600_000L -> {
            val mins = diffMs / 60_000L
            "Checked\u00A0${mins}m\u00A0ago"
        }
        diffMs < 86_400_000L -> {
            val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
            "Today\u00A0at\u00A0${sdf.format(Date(timestamp))}"
        }
        else -> {
            val days = diffMs / 86_400_000L
            "Checked\u00A0${days}d\u00A0ago"
        }
    }
}

/**
 * Fallback shown when Google Play cannot update this install (app was installed by sideloading
 * an APK). No APK is installed by the app itself anymore.
 */
@Composable
fun PlayUpdateFallbackDialog(reason: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "อัปเดตผ่าน Google Play",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = Color.White
            )
        },
        text = {
            Column {
                Text(
                    text = "ติดตั้งจาก Play ก่อนหนึ่งครั้งเพื่อรับอัปเดตอัตโนมัติ",
                    fontSize = 12.sp,
                    color = Color(0xFFCBD5E1),
                    lineHeight = 16.sp
                )
                if (reason.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = reason,
                        fontSize = 11.sp,
                        color = Color(0xFF94A3B8),
                        lineHeight = 15.sp
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    PlayUpdateManager.openPlayStore(context)
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
            ) {
                Text("เปิด Play Store", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("ปิด", color = Color(0xFF94A3B8))
            }
        },
        containerColor = Color(0xFF0F172A),
        tonalElevation = 6.dp
    )
}

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val measurementCadence by DriveTestForegroundService.measurementCadence.collectAsStateWithLifecycle()

    val isDualSimMode by DriveTestForegroundService.isDualSimMode.collectAsStateWithLifecycle()

    val availableSims by DriveTestForegroundService.availableSims.collectAsStateWithLifecycle()
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                // Finishes a flexible update Play downloaded while the app was in background
                scope.launch { PlayUpdateManager.onResume(context) }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val otaState by OtaUpdateManager.state.collectAsStateWithLifecycle()
    val playState by PlayUpdateManager.state.collectAsStateWithLifecycle()
    val lastCheckedTime by OtaUpdateManager.lastCheckedTimestamp.collectAsStateWithLifecycle()
    val autoUpdateEnabled by OtaUpdateManager.autoUpdateEnabled.collectAsStateWithLifecycle()
    val isFromPlay = remember(context) {
        PlayUpdateManager.isInstalledFromPlay(PlayUpdateManager.installerPackage(context))
    }
    var showPlayFallback by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        OtaUpdateManager.init(context)
    }

    // Read authoritative package version info dynamically (Rule 10)
    val packageInfo = remember {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
        } catch (_: Exception) {
            null
        }
    }
    val currentVersionName = packageInfo?.versionName ?: "1.11.2"
    val currentVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        packageInfo?.longVersionCode?.toInt() ?: 39
    } else {
        @Suppress("DEPRECATION")
        packageInfo?.versionCode ?: 39
    }

    val cloudConfig by CloudConfigRepository.config.collectAsStateWithLifecycle()
    val deviceSuggestedVehicleId = remember { CloudConfigRepository.getDeviceSuggestedVehicleId() }
    var vehicleIdInput by remember { mutableStateOf(cloudConfig.vehicleId) }
    var lastObservedConfigId by remember { mutableStateOf(cloudConfig.vehicleId) }
    var skipAvailabilityCheckFor by remember { mutableStateOf<String?>(null) }
    var vehicleIdAvailability by remember { mutableStateOf<VehicleIdAvailability>(VehicleIdAvailability.Idle) }
    var saveConflictMessage by remember { mutableStateOf<String?>(null) }
    var saveConflictSuggestion by remember { mutableStateOf<String?>(null) }
    var saveLocalNotice by remember { mutableStateOf<String?>(null) }
    var isSavingId by remember { mutableStateOf(false) }

    LaunchedEffect(cloudConfig.vehicleId) {
        if (cloudConfig.vehicleId != lastObservedConfigId) {
            val inputMatchedPrevious = vehicleIdInput.trim().uppercase(Locale.US) == lastObservedConfigId
            if (inputMatchedPrevious || vehicleIdInput.isBlank()) {
                vehicleIdInput = cloudConfig.vehicleId
            }
            lastObservedConfigId = cloudConfig.vehicleId
        }
    }

    LaunchedEffect(Unit) {
        if (
            cloudConfig.vehicleIdSyncState == VehicleIdSyncState.PENDING_SYNC ||
            cloudConfig.vehicleIdSyncState == VehicleIdSyncState.UNCLAIMED
        ) {
            CloudConfigRepository.syncPendingVehicleIdClaim()
        }
    }

    LaunchedEffect(vehicleIdInput) {
        if (isSavingId) return@LaunchedEffect
        val validation = VehicleIdGenerator.validate(vehicleIdInput)
        if (validation is VehicleIdValidationResult.Invalid) {
            vehicleIdAvailability = VehicleIdAvailability.Invalid(validation.reason)
            return@LaunchedEffect
        }
        val normalized = (validation as VehicleIdValidationResult.Valid).normalized
        if (skipAvailabilityCheckFor == normalized) {
            skipAvailabilityCheckFor = null
            return@LaunchedEffect
        }
        vehicleIdAvailability = VehicleIdAvailability.Checking
        kotlinx.coroutines.delay(350)
        if (isSavingId) return@LaunchedEffect
        val result = CloudConfigRepository.checkVehicleIdAvailability(normalized)
        if (vehicleIdInput.trim().uppercase(Locale.US) == normalized && !isSavingId) {
            vehicleIdAvailability = result
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(TelemetryDarkBg)
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Settings & Diagnostics",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )

        // App Update Card
        AppUpdateCard(
            otaState = otaState,
            playState = playState,
            isFromPlay = isFromPlay,
            autoUpdateEnabled = autoUpdateEnabled,
            currentVersionName = currentVersionName,
            currentVersionCode = currentVersionCode,
            lastCheckedTimestamp = lastCheckedTime,
            onToggleAutoUpdate = { enabled ->
                OtaUpdateManager.setAutoUpdateEnabled(enabled, context)
            },
            onCheckForUpdates = {
                scope.launch { OtaUpdateManager.checkForUpdates(currentVersionName) }
            },
            onDownloadUpdate = { release ->
                OtaUpdateManager.startDownload(context, release)
            },
            onCancelDownload = {
                OtaUpdateManager.cancelDownload()
            },
            onInstallUpdate = { release, apkPath ->
                OtaUpdateManager.installDownloadedApk(context, release, apkPath)
            },
            onPlayUpdateClick = {
                scope.launch {
                    val result = PlayUpdateManager.startUpdate(context)
                    val action = result.getOrNull()
                    if (action == null || action == PlayUpdateAction.NOT_FROM_PLAY) {
                        showPlayFallback = true
                    }
                }
            },
            onOpenPlayFallback = { showPlayFallback = true },
            onOpenInstallSettings = { OtaUpdateManager.openInstallPermissionSettings(context) },
            onOpenNetworkSettings = {
                try {
                    context.startActivity(
                        Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (_: Exception) {
                }
            },
            onDismissError = { OtaUpdateManager.resetState() }
        )

        if (showPlayFallback) {
            val playReason = (playState as? PlayUpdateState.Unavailable)?.reason
                ?: "แอปที่ติดตั้งจาก APK เองจะไม่ได้รับอัปเดตผ่าน Google Play"
            PlayUpdateFallbackDialog(
                reason = playReason,
                onDismiss = { showPlayFallback = false }
            )
        }

        // Measurement Cadence Configuration Card
        Card(
            colors = CardDefaults.cardColors(containerColor = TelemetryCardBg),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, TelemetryCardStroke, RoundedCornerShape(14.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "MEASUREMENT CADENCE (SAMPLING FREQUENCY)",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF94A3B8)
                )
                Spacer(modifier = Modifier.height(10.dp))

                MeasurementCadence.values().forEach { cadence ->
                    val isSelected = cadence == measurementCadence
                    Surface(
                        color = if (isSelected) Color(0xFF0C4A6E) else Color(0xFF1E293B),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .border(
                                width = if (isSelected) 1.5.dp else 1.dp,
                                color = if (isSelected) Color(0xFF06B6D4) else Color(0xFF334155),
                                shape = RoundedCornerShape(10.dp)
                            )
                            .clickable {
                                DriveTestForegroundService.setMeasurementCadence(context, cadence)
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = cadence.label,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) Color.White else Color(0xFFE2E8F0)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Surface(
                                        color = if (isSelected) Color(0xFF0284C7) else Color(0xFF0F172A),
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            text = cadence.frequencyLabel,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isSelected) Color.White else Color(0xFF94A3B8),
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = cadence.description,
                                    fontSize = 11.sp,
                                    color = if (isSelected) Color(0xFFA5F3FC) else Color(0xFF94A3B8)
                                )
                            }
                            if (isSelected) {
                                Text(
                                    text = "✓",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF06B6D4)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Dual-SIM Simultaneous Benchmark Logging Card

        Card(

            colors = CardDefaults.cardColors(containerColor = TelemetryCardBg),

            shape = RoundedCornerShape(14.dp),

            modifier = Modifier

                .fillMaxWidth()

                .border(1.dp, if (isDualSimMode) TelemetryCyan.copy(alpha = 0.7f) else TelemetryCardStroke, RoundedCornerShape(14.dp))

        ) {

            Column(modifier = Modifier.padding(16.dp)) {

                Row(

                    modifier = Modifier.fillMaxWidth(),

                    horizontalArrangement = Arrangement.SpaceBetween,

                    verticalAlignment = Alignment.CenterVertically

                ) {

                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {

                        Text(

                            text = "DUAL-SIM BENCHMARK LOGGING",

                            fontSize = 11.sp,

                            fontWeight = FontWeight.Bold,

                            color = if (isDualSimMode) TelemetryCyan else Color(0xFF94A3B8)

                        )

                        Text(

                            text = "บันทึก 2 ซิมพร้อมกันแบบ Operator Benchmark",

                            fontSize = 13.sp,

                            fontWeight = FontWeight.Bold,

                            color = Color.White

                        )

                    }

                    Switch(

                        checked = isDualSimMode,

                        onCheckedChange = { checked ->

                            DriveTestForegroundService.setDualSimMode(context, checked)

                        },

                        colors = SwitchDefaults.colors(

                            checkedThumbColor = Color.White,

                            checkedTrackColor = TelemetryCyan,

                            uncheckedThumbColor = Color(0xFF94A3B8),

                            uncheckedTrackColor = Color(0xFF1E293B)

                        )

                    )

                }

                Spacer(modifier = Modifier.height(6.dp))

                Text(

                    text = "บันทึกข้อมูล RF ของทั้ง SIM 1 (เช่น AIS) และ SIM 2 (เช่น TRUE) พร้อมกันในเซสชันเดียว เหมาะสำหรับการขับทดสอบเปรียบเทียบคุณภาพโครงข่ายบนเส้นทางเดียวกัน",

                    fontSize = 11.sp,

                    color = Color(0xFFCBD5E1),

                    lineHeight = 15.sp

                )

                if (availableSims.isNotEmpty()) {

                    Spacer(modifier = Modifier.height(8.dp))

                    availableSims.forEach { sim ->

                        Text(

                            text = "• ${sim.displayName}${if (sim.carrierName.isNotBlank() && !sim.displayName.contains(sim.carrierName)) " (${sim.carrierName})" else ""}",

                            fontSize = 11.sp,

                            fontWeight = FontWeight.Medium,

                            color = TelemetryCyan

                        )

                    }

                }

            }

        }



        // Modem Radio Lock Card
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF172554)),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFF2563EB), RoundedCornerShape(14.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "HARDWARE MODEM LOCK",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF93C5FD)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Opens Android RadioInfo (*#*#4636#*#*) engineering mode to force Qualcomm / MediaTek baseband to 'NR only' or 'LTE only'.",
                    fontSize = 12.sp,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(14.dp))
                Button(
                    onClick = { CellCollector.launchRadioInfo(context) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("⚡ LAUNCH ANDROID RADIO INFO", fontWeight = FontWeight.Bold)
                }
            }
        }

        // OEM Battery Saver Notice Card
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF451A03)),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFFD97706), RoundedCornerShape(14.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "OEM BATTERY OPTIMIZATION",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFFDE68A)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Xiaomi (MIUI/HyperOS), Samsung, and Oppo aggressively kill background drive test services. Please set Battery to 'No Restrictions' and enable 'Autostart'.",
                    fontSize = 12.sp,
                    color = Color(0xFFFEF3C7)
                )
                Spacer(modifier = Modifier.height(14.dp))
                Button(
                    onClick = {
                        try {
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", context.packageName, null)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        } catch (_: Exception) {
                            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            context.startActivity(intent)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("⚙ OPEN APP BATTERY SETTINGS", fontWeight = FontWeight.Bold)
                }
            }
        }

        // Gemini AI Optimization Engine Card
        GeminiAiOptimizationCard(
            modelName = GeminiRfAdvisor.MODEL_NAME,
            hasApiKey = BuildConfig.GEMINI_API_KEY.isNotBlank()
        )

        // Cloud Fleet Telemetry & Google Drive Auto-Upload Card
        Card(
            colors = CardDefaults.cardColors(containerColor = TelemetryCardBg),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, TelemetryCardStroke, RoundedCornerShape(14.dp))
        ) {
            val localValidation = VehicleIdGenerator.validate(vehicleIdInput)
            val normalizedInput = (localValidation as? VehicleIdValidationResult.Valid)?.normalized
            val inputMatchesSaved = normalizedInput != null && normalizedInput == cloudConfig.vehicleId

            val (badgeText, badgeBg, badgeFg) = when (cloudConfig.vehicleIdSyncState) {
                VehicleIdSyncState.SYNCED -> Triple(
                    "ACTIVE",
                    Color(0xFF065F46),
                    Color(0xFFA7F3D0)
                )
                VehicleIdSyncState.PENDING_SYNC -> {
                    if (cloudConfig.vehicleIdSyncIssue == VehicleIdSyncIssue.PERMISSION_DENIED) {
                        Triple("LOCAL ONLY", Color(0xFF78350F), Color(0xFFFDE68A))
                    } else {
                        Triple("PENDING SYNC", Color(0xFF78350F), Color(0xFFFDE68A))
                    }
                }
                VehicleIdSyncState.CONFLICT -> Triple(
                    "ID CONFLICT",
                    Color(0xFF7F1D1D),
                    Color(0xFFFCA5A5)
                )
                VehicleIdSyncState.UNCLAIMED -> Triple(
                    "NOT REGISTERED",
                    Color(0xFF334155),
                    Color(0xFFE2E8F0)
                )
            }

            val isConflictState = saveConflictMessage != null ||
                vehicleIdAvailability is VehicleIdAvailability.Taken ||
                (inputMatchesSaved && cloudConfig.vehicleIdSyncState == VehicleIdSyncState.CONFLICT)

            val conflictMessage = saveConflictMessage
                ?: (vehicleIdAvailability as? VehicleIdAvailability.Taken)?.message
                ?: CloudConfigRepository.DUPLICATE_ERROR_MESSAGE

            val conflictSuggestion = saveConflictSuggestion
                ?: (vehicleIdAvailability as? VehicleIdAvailability.Taken)?.suggestedAlternative
                ?: cloudConfig.suggestedAlternativeId.takeIf {
                    inputMatchesSaved && cloudConfig.vehicleIdSyncState == VehicleIdSyncState.CONFLICT
                }

            val hasErrorBorder = localValidation is VehicleIdValidationResult.Invalid || isConflictState

            Column(modifier = Modifier.padding(16.dp)) {
                // Header with wrapping/ellipsized title so it never collides with status badge
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.CloudSync,
                            contentDescription = null,
                            tint = TelemetryCyan,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "LIVE CLOUD FLEET TELEMETRY & NOC",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFCBD5E1),
                            lineHeight = 14.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            softWrap = true
                        )
                    }
                    Surface(
                        color = badgeBg,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = badgeText,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = badgeFg,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Vehicle Identification Title (high-contrast Thai + English, full width)
                Text(
                    text = "ข้อมูลประจำรถทดสอบ (Vehicle Identity)",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    lineHeight = 20.sp
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Input field + SAVE button (>= 48dp touch targets)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = vehicleIdInput,
                        onValueChange = { raw ->
                            vehicleIdInput = raw
                            skipAvailabilityCheckFor = null
                            saveConflictMessage = null
                            saveConflictSuggestion = null
                            saveLocalNotice = null
                        },
                        label = {
                            Text(
                                text = "Vehicle ID",
                                fontSize = 12.sp
                            )
                        },
                        placeholder = {
                            Text(
                                text = "เช่น ${VehicleIdGenerator.PLACEHOLDER_EXAMPLE_ID}",
                                fontSize = 13.sp,
                                color = Color(0xFF94A3B8)
                            )
                        },
                        singleLine = true,
                        isError = hasErrorBorder,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = if (hasErrorBorder) Color(0xFFF87171) else TelemetryCyan,
                            unfocusedBorderColor = if (hasErrorBorder) Color(0xFFF87171) else Color(0xFF475569),
                            errorBorderColor = Color(0xFFF87171),
                            focusedLabelColor = if (hasErrorBorder) Color(0xFFF87171) else TelemetryCyan,
                            unfocusedLabelColor = if (hasErrorBorder) Color(0xFFF87171) else Color(0xFFCBD5E1),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedPlaceholderColor = Color(0xFF94A3B8),
                            unfocusedPlaceholderColor = Color(0xFF94A3B8),
                            focusedContainerColor = Color(0xFF0F172A),
                            unfocusedContainerColor = Color(0xFF0F172A)
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 56.dp)
                    )

                    Button(
                        onClick = {
                            val candidate = vehicleIdInput
                            scope.launch {
                                isSavingId = true
                                saveConflictMessage = null
                                saveConflictSuggestion = null
                                saveLocalNotice = null
                                val outcome = CloudConfigRepository.saveVehicleId(candidate, isUserEdit = true)
                                isSavingId = false
                                when (outcome) {
                                    is VehicleIdClaimOutcome.Claimed -> {
                                        skipAvailabilityCheckFor = outcome.vehicleId
                                        vehicleIdInput = outcome.vehicleId
                                        lastObservedConfigId = outcome.vehicleId
                                        vehicleIdAvailability = VehicleIdAvailability.Available(
                                            normalizedId = outcome.vehicleId,
                                            ownedBySelf = true
                                        )
                                    }
                                    is VehicleIdClaimOutcome.TakenByOtherDevice -> {
                                        skipAvailabilityCheckFor = outcome.requestedId
                                        saveConflictMessage = outcome.message
                                        saveConflictSuggestion = outcome.suggestedAlternative
                                        vehicleIdAvailability = VehicleIdAvailability.Taken(
                                            normalizedId = outcome.requestedId,
                                            suggestedAlternative = outcome.suggestedAlternative,
                                            message = outcome.message
                                        )
                                    }
                                    is VehicleIdClaimOutcome.SavedLocallyPendingSync -> {
                                        skipAvailabilityCheckFor = outcome.vehicleId
                                        vehicleIdInput = outcome.vehicleId
                                        lastObservedConfigId = outcome.vehicleId
                                        saveLocalNotice = outcome.message
                                        vehicleIdAvailability = when (outcome.issue) {
                                            VehicleIdSyncIssue.PERMISSION_DENIED ->
                                                VehicleIdAvailability.PermissionDenied(outcome.vehicleId)
                                            else ->
                                                VehicleIdAvailability.Offline(outcome.vehicleId)
                                        }
                                    }
                                    is VehicleIdClaimOutcome.InvalidInput -> {
                                        vehicleIdAvailability = VehicleIdAvailability.Invalid(outcome.reason)
                                    }
                                }
                            }
                        },
                        enabled = !isSavingId && localValidation is VehicleIdValidationResult.Valid,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = TelemetryCyan,
                            contentColor = Color(0xFF0F172A),
                            disabledContainerColor = Color(0xFF1E293B),
                            disabledContentColor = Color(0xFF94A3B8)
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .defaultMinSize(minWidth = 76.dp, minHeight = 52.dp)
                    ) {
                        if (isSavingId) {
                            CircularProgressIndicator(
                                color = Color(0xFF0F172A),
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("SAVE", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // "Use device name" suggestion chip/button with aligned sparkle icon & >= 48dp touch target
                OutlinedButton(
                    enabled = !isSavingId,
                    onClick = {
                        skipAvailabilityCheckFor = null
                        vehicleIdInput = deviceSuggestedVehicleId
                        saveConflictMessage = null
                        saveConflictSuggestion = null
                        saveLocalNotice = null
                    },
                    border = BorderStroke(1.dp, TelemetryCyan.copy(alpha = 0.55f)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color(0xFF0F172A),
                        contentColor = Color(0xFFE0F2FE)
                    ),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = TelemetryCyan,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Use device name ($deviceSuggestedVehicleId)",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFFE0F2FE),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Debounced real-time availability & registration status under the input
                Spacer(modifier = Modifier.height(8.dp))
                when {
                    isSavingId -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(
                                color = Color(0xFF38BDF8),
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp
                            )
                            Text(
                                text = "Registering Vehicle ID in Firebase...",
                                fontSize = 12.sp,
                                color = Color(0xFF38BDF8),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    localValidation is VehicleIdValidationResult.Invalid -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = Color(0xFFF87171),
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = localValidation.reason,
                                fontSize = 12.sp,
                                color = Color(0xFFF87171),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    isConflictState -> {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = Color(0xFFF87171),
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = conflictMessage,
                                    fontSize = 12.sp,
                                    color = Color(0xFFF87171),
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            if (!conflictSuggestion.isNullOrBlank()) {
                                OutlinedButton(
                                    enabled = !isSavingId,
                                    onClick = {
                                        skipAvailabilityCheckFor = null
                                        vehicleIdInput = conflictSuggestion
                                        saveConflictMessage = null
                                        saveConflictSuggestion = null
                                        saveLocalNotice = null
                                    },
                                    border = BorderStroke(1.dp, TelemetryCyan),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        containerColor = Color(0xFF0F172A),
                                        contentColor = Color(0xFFE0F2FE)
                                    ),
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                                    modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.AutoAwesome,
                                            contentDescription = null,
                                            tint = TelemetryCyan,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Text(
                                            text = "Use suggested free ID: $conflictSuggestion",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color(0xFFE0F2FE),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                    inputMatchesSaved && cloudConfig.vehicleIdSyncState == VehicleIdSyncState.SYNCED -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = Color(0xFF34D399),
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Vehicle ID registered & active for this device",
                                fontSize = 12.sp,
                                color = Color(0xFF34D399),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    vehicleIdAvailability is VehicleIdAvailability.Checking -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(
                                color = Color(0xFF38BDF8),
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp
                            )
                            Text(
                                text = "Checking availability...",
                                fontSize = 12.sp,
                                color = Color(0xFF38BDF8),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    inputMatchesSaved && cloudConfig.vehicleIdSyncState == VehicleIdSyncState.PENDING_SYNC -> {
                        val pendingText = saveLocalNotice ?: when (cloudConfig.vehicleIdSyncIssue) {
                            VehicleIdSyncIssue.PERMISSION_DENIED ->
                                "Saved locally — Firebase permission denied, will retry sync"
                            VehicleIdSyncIssue.OFFLINE ->
                                "Saved locally (offline) — will verify & sync when reconnected"
                            else ->
                                "Saved locally — pending Firebase registration"
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = Color(0xFFFBBF24),
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = pendingText,
                                fontSize = 12.sp,
                                color = Color(0xFFFBBF24),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    vehicleIdAvailability is VehicleIdAvailability.Available -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = Color(0xFF34D399),
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Available — tap SAVE to register",
                                fontSize = 12.sp,
                                color = Color(0xFF34D399),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    vehicleIdAvailability is VehicleIdAvailability.PermissionDenied -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = Color(0xFFFBBF24),
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Firebase permission denied — tap SAVE to store locally and sync later",
                                fontSize = 12.sp,
                                color = Color(0xFFFBBF24),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    vehicleIdAvailability is VehicleIdAvailability.Offline -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = Color(0xFFFBBF24),
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Offline — tap SAVE to store locally and verify on reconnect",
                                fontSize = 12.sp,
                                color = Color(0xFFFBBF24),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = Color(0xFF1E293B), thickness = 0.8.dp)
                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.CloudDone,
                        contentDescription = null,
                        tint = Color(0xFF94A3B8),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Telemetry streams live to Firebase • Auto-uploading KMZ, CSV, GeoJSON, MapInfo, PDF to Google Drive on Wi-Fi",
                        fontSize = 11.sp,
                        color = Color(0xFF94A3B8),
                        lineHeight = 15.sp
                    )
                }
            }
        }

        // Configuration Card
        Card(
            colors = CardDefaults.cardColors(containerColor = TelemetryCardBg),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, TelemetryCardStroke, RoundedCornerShape(14.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "DATA COLLECTION PARAMETERS",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF94A3B8)
                )
                Spacer(modifier = Modifier.height(10.dp))
                SettingItem(title = "Sampling Rate", value = "${measurementCadence.label} (${measurementCadence.frequencyLabel})")
                SettingItem(title = "GPS Accuracy Filter", value = "≤ 30 meters")
                SettingItem(title = "Maps Engine", value = "Google Maps SDK Active")
                SettingItem(title = "Target Android SDK", value = "Android 10 - 15 (API 29-37)")
            }
        }

        // Dynamic App Info (Rule 10)
        Text(
            text = "Drive Test LTE/5G v$currentVersionName (Build $currentVersionCode) • Native Android Kotlin",
            fontSize = 11.sp,
            color = Color(0xFF64748B),
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GeminiAiOptimizationCard(
    modelName: String,
    hasApiKey: Boolean,
    modifier: Modifier = Modifier
) {
    val fontScale = LocalDensity.current.fontScale
    Card(
        colors = CardDefaults.cardColors(containerColor = TelemetryCardBg),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, TelemetryCardStroke, RoundedCornerShape(14.dp))
    ) {
        BoxWithConstraints(modifier = Modifier.padding(16.dp)) {
            val useStackedRows = maxWidth < 320.dp || fontScale >= 1.3f

            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = Color(0xFF60A5FA),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "GEMINI AI OPTIMIZATION ENGINE",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFCBD5E1),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Surface(
                        color = if (hasApiKey) Color(0xFF065F46) else Color(0xFF7F1D1D),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = if (hasApiKey) "ACTIVE" else "KEY MISSING",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (hasApiKey) Color(0xFFA7F3D0) else Color(0xFFFCA5A5),
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Row 1: AI Model
                GeminiKeyValueRow(
                    label = "AI Model",
                    stacked = useStackedRows
                ) {
                    Text(
                        text = modelName,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = SignalTier1,
                        lineHeight = 17.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Row 2: API Key Source
                GeminiKeyValueRow(
                    label = "API Key Source",
                    stacked = useStackedRows
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "local.properties",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = SignalTier1,
                            lineHeight = 17.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Compile-time Injected",
                            fontSize = 11.sp,
                            color = Color(0xFF94A3B8),
                            lineHeight = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Row 3: RF Heuristic Engine chips
                GeminiKeyValueRow(
                    label = "RF Heuristic Engine",
                    stacked = useStackedRows
                ) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("CDF", "Problem Detection", "Handover Analysis").forEach { item ->
                            Surface(
                                color = SignalTier1.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(6.dp),
                                border = BorderStroke(1.dp, SignalTier1.copy(alpha = 0.3f))
                            ) {
                                Text(
                                    text = item,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = SignalTier1,
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(
                    color = Color(0xFF1E293B),
                    modifier = Modifier.padding(vertical = 12.dp)
                )

                Text(
                    text = "ระบบจะส่งข้อมูลสรุปสถิติ Telemetry ไปยัง $modelName เพื่อวิเคราะห์ Root\u00A0Cause และสร้างคำแนะนำปรับแต่งโครงข่าย (Tilt, Power, HO\u00A0Margin)",
                    fontSize = 12.sp,
                    color = Color(0xFFCBD5E1),
                    lineHeight = 18.sp,
                    style = TextStyle(
                        lineBreak = LineBreak.Paragraph,
                        hyphens = Hyphens.None
                    )
                )
            }
        }
    }
}

@Composable
private fun GeminiKeyValueRow(
    label: String,
    stacked: Boolean,
    valueContent: @Composable () -> Unit
) {
    if (stacked) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF94A3B8),
                lineHeight = 16.sp
            )
            valueContent()
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF94A3B8),
                lineHeight = 17.sp,
                modifier = Modifier
                    .weight(0.4f)
                    .widthIn(min = 120.dp)
            )
            Box(modifier = Modifier.weight(0.6f)) {
                valueContent()
            }
        }
    }
}

@Composable
fun SettingItem(title: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = title, fontSize = 12.sp, color = Color.White)
        Text(text = value, fontSize = 12.sp, color = SignalTier1, fontWeight = FontWeight.SemiBold)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppUpdateCard(
    otaState: OtaState,
    playState: PlayUpdateState = PlayUpdateState.Idle,
    isFromPlay: Boolean = false,
    autoUpdateEnabled: Boolean = true,
    currentVersionName: String,
    currentVersionCode: Int,
    lastCheckedTimestamp: Long?,
    onToggleAutoUpdate: (Boolean) -> Unit = {},
    onCheckForUpdates: () -> Unit = {},
    onDownloadUpdate: (ReleaseInfo) -> Unit = {},
    onCancelDownload: () -> Unit = {},
    onInstallUpdate: (ReleaseInfo, String) -> Unit = { _, _ -> },
    onPlayUpdateClick: () -> Unit = {},
    onOpenPlayFallback: () -> Unit = {},
    onOpenInstallSettings: () -> Unit = {},
    onOpenNetworkSettings: () -> Unit = {},
    onDismissError: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var nowMs by remember(lastCheckedTimestamp) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(lastCheckedTimestamp) {
        if (lastCheckedTimestamp != null && lastCheckedTimestamp > 0L) {
            while (true) {
                kotlinx.coroutines.delay(15_000L)
                nowMs = System.currentTimeMillis()
            }
        }
    }

    var releaseNotesToShow by remember { mutableStateOf<ReleaseInfo?>(null) }
    val activeRelease: ReleaseInfo? = when (otaState) {
        is OtaState.UpdateAvailable -> otaState.releaseInfo
        is OtaState.Downloading -> otaState.releaseInfo
        is OtaState.ReadyToInstall -> otaState.releaseInfo
        is OtaState.Installing -> otaState.releaseInfo
        else -> null
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = TelemetryCardBg),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, TelemetryCardStroke, RoundedCornerShape(14.dp))
            .animateContentSize()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header row: APP UPDATE + tappable AUTO: ON/OFF badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "APP UPDATE",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFCBD5E1),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                val autoBg = if (autoUpdateEnabled) Color(0xFF065F46) else Color(0xFF1E293B)
                val autoFg = if (autoUpdateEnabled) Color(0xFFA7F3D0) else Color(0xFF94A3B8)
                val autoLabel = if (autoUpdateEnabled) "AUTO: ON" else "AUTO: OFF"
                Surface(
                    color = autoBg,
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(
                        1.dp,
                        if (autoUpdateEnabled) Color(0xFF10B981).copy(alpha = 0.4f) else Color(0xFF334155)
                    ),
                    modifier = Modifier
                        .clickable(role = Role.Switch) { onToggleAutoUpdate(!autoUpdateEnabled) }
                        .semantics {
                            contentDescription = "Auto-check updates: ${if (autoUpdateEnabled) "enabled" else "disabled"}. Tap to toggle."
                        }
                ) {
                    Text(
                        text = autoLabel,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = autoFg,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (autoUpdateEnabled) {
                    "Auto-checks GitHub on startup and notifies when a new build is ready."
                } else {
                    "Auto-check is paused. Tap AUTO: OFF to enable or check manually below."
                },
                fontSize = 11.5.sp,
                color = Color(0xFF94A3B8),
                lineHeight = 16.sp
            )

            Spacer(modifier = Modifier.height(14.dp))

            // 1. Prominent Status Block (animated across states)
            AnimatedContent(
                targetState = otaState,
                label = "AppUpdateStatus"
            ) { state ->
                when (state) {
                    is OtaState.Checking -> {
                        Surface(
                            color = Color(0xFF1E293B),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, TelemetryCyan.copy(alpha = 0.45f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = TelemetryCyan,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "Checking for Updates...",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = Color.White
                                    )
                                    Text(
                                        text = "Connecting to GitHub Releases API",
                                        fontSize = 11.sp,
                                        color = Color(0xFFA5F3FC)
                                    )
                                }
                            }
                        }
                    }

                    is OtaState.UpToDate -> {
                        Surface(
                            color = Color(0xFF064E3B),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = "Up to date",
                                    tint = SignalTier2,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "App is Up to Date",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = Color.White
                                    )
                                    Text(
                                        text = "You are on the latest release (v${state.version}).",
                                        fontSize = 11.sp,
                                        color = Color(0xFFA7F3D0)
                                    )
                                }
                            }
                        }
                    }

                    is OtaState.UpdateAvailable -> {
                        val release = state.releaseInfo
                        Surface(
                            color = Color(0xFF1E293B),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, SignalTier1),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.SystemUpdate,
                                        contentDescription = "Update available",
                                        tint = SignalTier1,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = "New Update Available",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = SignalTier1,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Surface(
                                        color = SignalTier1.copy(alpha = 0.2f),
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            text = "v${release.versionName}",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = SignalTier1,
                                            maxLines = 1,
                                            softWrap = false,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                if (release.releaseTitle.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = release.releaseTitle,
                                        fontSize = 11.5.sp,
                                        color = Color(0xFFE2E8F0)
                                    )
                                }
                            }
                        }
                    }

                    is OtaState.Downloading -> {
                        val mbDone = String.format(Locale.US, "%.1f", state.downloadedBytes / (1024.0 * 1024.0))
                        val mbTotal = if (state.totalBytes > 0L) {
                            String.format(Locale.US, "%.1f", state.totalBytes / (1024.0 * 1024.0))
                        } else "?"
                        Surface(
                            color = Color(0xFF1E293B),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, TelemetryCyan.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Downloading v${state.releaseInfo.versionName}",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = "${state.progressPercent}%",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TelemetryCyan,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                LinearProgressIndicator(
                                    progress = { (state.progressPercent / 100f).coerceIn(0f, 1f) },
                                    color = TelemetryCyan,
                                    trackColor = Color(0xFF0F172A),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "$mbDone MB of $mbTotal MB",
                                    fontSize = 11.sp,
                                    color = Color(0xFF94A3B8),
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }

                    is OtaState.ReadyToInstall -> {
                        Surface(
                            color = Color(0xFF064E3B),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, SignalTier1),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.InstallMobile,
                                    contentDescription = "Ready to install",
                                    tint = SignalTier2,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "Ready to Install",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = Color.White
                                    )
                                    Text(
                                        text = "v${state.releaseInfo.versionName} package downloaded and verified.",
                                        fontSize = 11.sp,
                                        color = Color(0xFFA7F3D0)
                                    )
                                }
                            }
                        }
                    }

                    is OtaState.Installing -> {
                        Surface(
                            color = Color(0xFF1E293B),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, TelemetryCyan.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = TelemetryCyan,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "Installing Update...",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = Color.White
                                    )
                                    Text(
                                        text = "Follow the Android system prompt to finish installing v${state.releaseInfo.versionName}.",
                                        fontSize = 11.sp,
                                        color = Color(0xFFA5F3FC)
                                    )
                                }
                            }
                        }
                    }

                    is OtaState.Error -> {
                        Surface(
                            color = Color(0xFF450A0A),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, Color(0xFFDC2626).copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Icon(
                                    Icons.Default.Warning,
                                    contentDescription = "Update error",
                                    tint = Color(0xFFFCA5A5),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = when (state.kind) {
                                            OtaErrorKind.INSTALL_PERMISSION -> "Install Permission Required"
                                            OtaErrorKind.NETWORK -> "Network Unavailable"
                                            OtaErrorKind.GENERAL -> "Update Check Failed"
                                        },
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = Color.White
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = state.message,
                                        fontSize = 11.sp,
                                        color = Color(0xFFFCA5A5),
                                        lineHeight = 15.sp
                                    )
                                }
                            }
                        }
                    }

                    is OtaState.Idle -> {
                        Surface(
                            color = Color(0xFF1E293B),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = null,
                                    tint = Color(0xFF94A3B8),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Ready to check for latest releases",
                                    fontSize = 12.sp,
                                    color = Color(0xFFCBD5E1)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = Color(0xFF1E293B), thickness = 1.dp)
            Spacer(modifier = Modifier.height(10.dp))

            // 2. Compact Info Section (Current Version & Live Last Checked)
            AppUpdateInfoRow(label = "Current Version") {
                Surface(
                    color = Color(0xFF0369A1),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = "v$currentVersionName\u00A0(Build\u00A0$currentVersionCode)",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            AppUpdateInfoRow(label = "Last Checked") {
                Text(
                    text = formatLastChecked(lastCheckedTimestamp, nowMs),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFFE2E8F0),
                    maxLines = 1,
                    softWrap = false
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 3. Primary Action Button (>= 48dp, WCAG AA white text on #0284C7)
            val primaryColors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF0284C7),
                contentColor = Color.White,
                disabledContainerColor = Color(0xFF1E293B),
                disabledContentColor = Color(0xFF94A3B8)
            )
            when (otaState) {
                is OtaState.Checking -> {
                    Button(
                        onClick = {},
                        enabled = false,
                        colors = primaryColors,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = TelemetryCyan
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "CHECKING...",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }

                is OtaState.UpdateAvailable -> {
                    if (isFromPlay) {
                        Button(
                            onClick = onPlayUpdateClick,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SignalTier1,
                                contentColor = Color(0xFF052E16)
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 48.dp)
                        ) {
                            Icon(Icons.Default.SystemUpdate, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "อัปเดตผ่าน Play Store",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    } else {
                        Button(
                            onClick = { onDownloadUpdate(otaState.releaseInfo) },
                            colors = primaryColors,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 48.dp)
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "DOWNLOAD UPDATE",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }

                is OtaState.Downloading -> {
                    OutlinedButton(
                        onClick = onCancelDownload,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFCA5A5)),
                        border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.6f)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "CANCEL DOWNLOAD",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }

                is OtaState.ReadyToInstall -> {
                    Button(
                        onClick = { onInstallUpdate(otaState.releaseInfo, otaState.apkFilePath) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF059669),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                    ) {
                        Icon(Icons.Default.InstallMobile, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "INSTALL",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }

                is OtaState.Installing -> {
                    Button(
                        onClick = {},
                        enabled = false,
                        colors = primaryColors,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = TelemetryCyan
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "INSTALLING...",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }

                is OtaState.Error -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (otaState.kind == OtaErrorKind.INSTALL_PERMISSION || otaState.kind == OtaErrorKind.NETWORK) {
                            OutlinedButton(
                                onClick = {
                                    if (otaState.kind == OtaErrorKind.INSTALL_PERMISSION) {
                                        onOpenInstallSettings()
                                    } else {
                                        onOpenNetworkSettings()
                                    }
                                },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFDE68A)),
                                border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.6f)),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .defaultMinSize(minHeight = 48.dp)
                            ) {
                                Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (otaState.kind == OtaErrorKind.INSTALL_PERMISSION) {
                                        "OPEN INSTALL SETTINGS"
                                    } else {
                                        "OPEN WI-FI SETTINGS"
                                    },
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = onCheckForUpdates,
                                colors = primaryColors,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .defaultMinSize(minHeight = 48.dp)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("RETRY", fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1, softWrap = false)
                            }
                            OutlinedButton(
                                onClick = onDismissError,
                                border = BorderStroke(1.dp, Color(0xFF334155)),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .defaultMinSize(minHeight = 48.dp)
                            ) {
                                Text("DISMISS", fontSize = 11.sp, color = Color(0xFFCBD5E1), maxLines = 1, softWrap = false)
                            }
                        }
                    }
                }

                is OtaState.UpToDate, is OtaState.Idle -> {
                    Button(
                        onClick = onCheckForUpdates,
                        colors = primaryColors,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (otaState is OtaState.UpToDate) "CHECK AGAIN" else "CHECK FOR UPDATES",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }

            // Secondary Action: View Release Notes chevron row (when release metadata is present)
            if (activeRelease != null && activeRelease.releaseNotes.isNotBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    onClick = { releaseNotesToShow = activeRelease },
                    color = Color(0xFF0B1220),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, Color(0xFF1E293B)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "View release notes (v${activeRelease.versionName})",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFFCBD5E1),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = "Open release notes",
                            tint = Color(0xFF94A3B8),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // Play Store update status & fallback (strictly gated for Play installs)
            if (isFromPlay) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = Color(0xFF1E293B), thickness = 1.dp)
                Spacer(modifier = Modifier.height(10.dp))

                if (playState is PlayUpdateState.Downloading) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "กำลังดาวน์โหลดอัปเดตผ่าน Play",
                            fontSize = 11.5.sp,
                            color = Color(0xFFA5F3FC)
                        )
                        Text(
                            text = "${playState.progressPercent}%",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = TelemetryCyan
                        )
                    }
                }
                if (playState is PlayUpdateState.Unavailable) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = playState.reason,
                        fontSize = 11.sp,
                        color = Color(0xFF94A3B8)
                    )
                }
                TextButton(
                    onClick = onOpenPlayFallback,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "ติดตั้งจาก Play ก่อนหนึ่งครั้งเพื่อรับอัปเดตอัตโนมัติ",
                        fontSize = 11.5.sp,
                        color = Color(0xFF94A3B8)
                    )
                }
            }
        }
    }

    releaseNotesToShow?.let { release ->
        AlertDialog(
            onDismissRequest = { releaseNotesToShow = null },
            containerColor = Color(0xFF0F172A),
            title = {
                Text(
                    text = "Release Notes · v${release.versionName}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = Color.White
                )
            },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        text = release.releaseNotes,
                        fontSize = 12.sp,
                        color = Color(0xFFCBD5E1),
                        lineHeight = 18.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { releaseNotesToShow = null }) {
                    Text("CLOSE", color = TelemetryCyan, fontWeight = FontWeight.Bold)
                }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppUpdateInfoRow(
    label: String,
    valueContent: @Composable () -> Unit
) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = Color(0xFF94A3B8),
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(end = 12.dp)
        )
        valueContent()
    }
}

private val PreviewRelease = ReleaseInfo(
    tagName = "v1.13.14",
    versionName = "1.13.14",
    releaseTitle = "Offline recovery & UI polish",
    releaseNotes = "• Fixed offline session recovery\n• Improved Dual-SIM & CDF charts",
    downloadUrl = "https://example.com/app.apk",
    apkSize = 18_500_000L
)

@Preview(showBackground = true, backgroundColor = 0xFF090E17)
@Composable
private fun AppUpdateCardUpToDatePreview() {
    AppUpdateCard(
        otaState = OtaState.UpToDate("1.13.13"),
        currentVersionName = "1.13.13",
        currentVersionCode = 57,
        lastCheckedTimestamp = System.currentTimeMillis()
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF090E17)
@Composable
private fun AppUpdateCardCheckingPreview() {
    AppUpdateCard(
        otaState = OtaState.Checking,
        currentVersionName = "1.13.13",
        currentVersionCode = 57,
        lastCheckedTimestamp = null
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF090E17)
@Composable
private fun AppUpdateCardUpdateAvailablePreview() {
    AppUpdateCard(
        otaState = OtaState.UpdateAvailable(PreviewRelease),
        currentVersionName = "1.13.13",
        currentVersionCode = 57,
        lastCheckedTimestamp = System.currentTimeMillis()
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF090E17)
@Composable
private fun AppUpdateCardDownloadingPreview() {
    AppUpdateCard(
        otaState = OtaState.Downloading(PreviewRelease, progressPercent = 64, downloadedBytes = 11_840_000L, totalBytes = 18_500_000L),
        currentVersionName = "1.13.13",
        currentVersionCode = 57,
        lastCheckedTimestamp = System.currentTimeMillis()
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF090E17)
@Composable
private fun AppUpdateCardReadyToInstallPreview() {
    AppUpdateCard(
        otaState = OtaState.ReadyToInstall(PreviewRelease, "/cache/updates/update_1.13.14.apk"),
        currentVersionName = "1.13.13",
        currentVersionCode = 57,
        lastCheckedTimestamp = System.currentTimeMillis()
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF090E17)
@Composable
private fun AppUpdateCardErrorPreview() {
    AppUpdateCard(
        otaState = OtaState.Error("Allow 'Install unknown apps' permission to install v1.13.14.", OtaErrorKind.INSTALL_PERMISSION),
        autoUpdateEnabled = false,
        currentVersionName = "1.13.13",
        currentVersionCode = 57,
        lastCheckedTimestamp = System.currentTimeMillis()
    )
}
