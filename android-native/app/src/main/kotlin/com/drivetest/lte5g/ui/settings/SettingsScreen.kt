package com.drivetest.lte5g.ui.settings

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import com.drivetest.lte5g.updater.OtaState
import com.drivetest.lte5g.updater.OtaUpdateManager
import com.drivetest.lte5g.updater.PlayUpdateAction
import com.drivetest.lte5g.updater.PlayUpdateManager
import com.drivetest.lte5g.updater.PlayUpdateState
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun formatLastChecked(timestamp: Long?): String {
    if (timestamp == null || timestamp <= 0) return "Not checked in this session"
    val diffMs = System.currentTimeMillis() - timestamp
    if (diffMs < 60_000) return "Just now"
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    return "Today at ${sdf.format(Date(timestamp))}"
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
    val isFromPlay = remember(context) {
        PlayUpdateManager.isInstalledFromPlay(PlayUpdateManager.installerPackage(context))
    }
    var showPlayFallback by remember { mutableStateOf(false) }

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
        Card(
            colors = CardDefaults.cardColors(containerColor = TelemetryCardBg),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, TelemetryCardStroke, RoundedCornerShape(14.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Header: Spacing fixed, badge overflow eliminated, matches other cards
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "APP UPDATE",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF94A3B8)
                    )
                    Surface(
                        color = Color(0xFF065F46),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "AUTO: ON",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFA7F3D0),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                // Shortened description
                Text(
                    text = "Auto-checks GitHub on startup and downloads updates in background.",
                    fontSize = 12.sp,
                    color = Color(0xFF94A3B8)
                )

                Spacer(modifier = Modifier.height(14.dp))

                // 1. STATUS
                when (val state = otaState) {
                    is OtaState.Checking -> {
                        Surface(
                            color = Color(0xFF1E293B),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, TelemetryCyan.copy(alpha = 0.4f)),
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
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
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
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, SignalTier1),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Default.SystemUpdate,
                                            contentDescription = null,
                                            tint = SignalTier1,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "New Update Available",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = SignalTier1
                                        )
                                    }
                                    Surface(
                                        color = SignalTier1.copy(alpha = 0.2f),
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            text = "v${release.versionName}",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = SignalTier1,
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

                    is OtaState.Error -> {
                        Surface(
                            color = Color(0xFF450A0A),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, Color(0xFFDC2626).copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = SignalTier5, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = "Update Check Failed",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = Color.White
                                    )
                                    Text(
                                        text = state.message,
                                        fontSize = 10.5.sp,
                                        color = SignalTier4
                                    )
                                }
                            }
                        }
                    }

                    is OtaState.Idle -> {
                        Surface(
                            color = Color(0xFF1E293B),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, tint = Color(0xFF94A3B8), modifier = Modifier.size(18.dp))
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

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = Color(0xFF1E293B), thickness = 1.dp)
                Spacer(modifier = Modifier.height(10.dp))

                // 2. VERSION
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Current Version",
                        fontSize = 12.sp,
                        color = Color(0xFF94A3B8)
                    )
                    Surface(
                        color = Color(0xFF0369A1),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "v$currentVersionName (Build $currentVersionCode)",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 3. LAST CHECKED
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Last Checked",
                        fontSize = 12.sp,
                        color = Color(0xFF94A3B8)
                    )
                    Text(
                        text = formatLastChecked(lastCheckedTime),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFFE2E8F0)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 4. BUTTON
                when (val state = otaState) {
                    is OtaState.Checking -> {
                        OutlinedButton(
                            onClick = {},
                            enabled = false,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = TelemetryCyan)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("CHECKING...", fontSize = 12.sp, color = TelemetryCyan)
                        }
                    }

                    is OtaState.UpdateAvailable -> {
                        if (isFromPlay) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        val result = PlayUpdateManager.startUpdate(context)
                                        val action = result.getOrNull()
                                        if (action == null || action == PlayUpdateAction.NOT_FROM_PLAY) {
                                            showPlayFallback = true
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = SignalTier1),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "อัปเดตผ่าน Play Store",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = Color.Black
                                )
                            }
                        } else {
                            Button(
                                onClick = {
                                    scope.launch {
                                        OtaUpdateManager.checkForUpdates(currentVersionName)
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "CHECK AGAIN",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }

                    is OtaState.Error -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        OtaUpdateManager.checkForUpdates(currentVersionName)
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("RETRY", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                            OutlinedButton(
                                onClick = { OtaUpdateManager.resetState() },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("DISMISS", fontSize = 11.sp, color = Color(0xFF94A3B8))
                            }
                        }
                    }

                    is OtaState.UpToDate, is OtaState.Idle -> {
                        Button(
                            onClick = {
                                scope.launch {
                                    OtaUpdateManager.checkForUpdates(currentVersionName)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (state is OtaState.UpToDate) "CHECK AGAIN" else "CHECK FOR UPDATES",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                // 5. Play Store update status & fallback (shown only when installed from Google Play)
                if (isFromPlay) {
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = Color(0xFF1E293B), thickness = 1.dp)
                    Spacer(modifier = Modifier.height(10.dp))

                    val playDownload = playState
                    if (playDownload is PlayUpdateState.Downloading) {
                        Spacer(modifier = Modifier.height(10.dp))
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
                                text = "${playDownload.progressPercent}%",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = TelemetryCyan
                            )
                        }
                    }
                    val playUnavailable = playState
                    if (playUnavailable is PlayUpdateState.Unavailable) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = playUnavailable.reason,
                            fontSize = 11.sp,
                            color = Color(0xFF94A3B8)
                        )
                    }
                    TextButton(
                        onClick = { showPlayFallback = true },
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Color(0xFF60A5FA), modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "GEMINI AI OPTIMIZATION ENGINE",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF94A3B8)
                        )
                    }
                    val hasKey = BuildConfig.GEMINI_API_KEY.isNotBlank()
                    Surface(
                        color = if (hasKey) Color(0xFF065F46) else Color(0xFF7F1D1D),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = if (hasKey) "ACTIVE" else "KEY MISSING",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (hasKey) Color(0xFFA7F3D0) else Color(0xFFFCA5A5),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                SettingItem(title = "AI Model", value = GeminiRfAdvisor.MODEL_NAME)
                SettingItem(title = "API Key Source", value = "local.properties (Compile-time Injected)")
                SettingItem(title = "RF Heuristic Engine", value = "CDF, Problem Detection & Handover Analysis")
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "ระบบจะส่งข้อมูลสรุปสถิติ Telemetry ไปยัง ${GeminiRfAdvisor.MODEL_NAME} เพื่อวิเคราะห์ Root Cause และสร้างคำแนะนำปรับแต่งโครงข่าย (Tilt, Power, HO Margin)",
                    fontSize = 11.sp,
                    color = Color(0xFF94A3B8),
                    lineHeight = 15.sp
                )
            }
        }

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
