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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drivetest.lte5g.BuildConfig
import com.drivetest.lte5g.ai.GeminiRfAdvisor
import com.drivetest.lte5g.cloud.CloudConfigRepository
import com.drivetest.lte5g.cloud.CloudTelemetrySyncManager
import com.drivetest.lte5g.cloud.GoogleDriveAutoUploader
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

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val measurementCadence by DriveTestForegroundService.measurementCadence.collectAsStateWithLifecycle()

    val isDualSimMode by DriveTestForegroundService.isDualSimMode.collectAsStateWithLifecycle()

    val availableSims by DriveTestForegroundService.availableSims.collectAsStateWithLifecycle()
    val scrollState = rememberScrollState()
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                OtaUpdateManager.revalidate(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val scope = rememberCoroutineScope()
    val otaState by OtaUpdateManager.state.collectAsStateWithLifecycle()
    val lastCheckedTime by OtaUpdateManager.lastCheckedTimestamp.collectAsStateWithLifecycle()

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
    var vehicleIdInput by remember(cloudConfig.vehicleId) { mutableStateOf(cloudConfig.vehicleId) }
    var vehicleNameInput by remember(cloudConfig.vehicleName) { mutableStateOf(cloudConfig.vehicleName) }
    var fbUrlInput by remember(cloudConfig.firebaseDbUrl) { mutableStateOf(cloudConfig.firebaseDbUrl) }
    var fbTokenInput by remember(cloudConfig.firebaseAuthToken) { mutableStateOf(cloudConfig.firebaseAuthToken) }
    var liveTelemetryEnabled by remember(cloudConfig.liveTelemetryEnabled) { mutableStateOf(cloudConfig.liveTelemetryEnabled) }
    var driveTokenInput by remember(cloudConfig.googleDriveAccessToken) { mutableStateOf(cloudConfig.googleDriveAccessToken) }
    var driveFolderInput by remember(cloudConfig.googleDriveFolderId) { mutableStateOf(cloudConfig.googleDriveFolderId) }
    var autoUploadOnWifi by remember(cloudConfig.autoUploadOnWifi) { mutableStateOf(cloudConfig.autoUploadOnWifi) }

    var fbTestResult by remember { mutableStateOf<String?>(null) }
    var fbTesting by remember { mutableStateOf(false) }
    var driveTestResult by remember { mutableStateOf<String?>(null) }
    var driveTesting by remember { mutableStateOf(false) }

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

                    is OtaState.Downloading -> {
                        Surface(
                            color = Color(0xFF1E293B),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, TelemetryCyan.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(14.dp),
                                            color = TelemetryCyan,
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Downloading in Background...",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color(0xFFA5F3FC)
                                        )
                                    }
                                    Text(
                                        text = "${(state.progress * 100).toInt()}%",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TelemetryCyan
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                LinearProgressIndicator(
                                    progress = { state.progress },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp),
                                    color = TelemetryCyan,
                                    trackColor = Color(0xFF0F172A)
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "${String.format(Locale.US, "%.1f", state.downloadedBytes / (1024f * 1024f))} / ${String.format(Locale.US, "%.1f", state.totalBytes / (1024f * 1024f))} MB",
                                    fontSize = 10.5.sp,
                                    color = Color(0xFF94A3B8)
                                )
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

                    is OtaState.ReadyToInstall -> {
                        Surface(
                            color = if (state.validation.isValid) Color(0xFF064E3B).copy(alpha = 0.7f) else Color(0xFF450A0A),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, if (state.validation.isValid) SignalTier1 else Color(0xFFDC2626)),
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
                                            if (state.validation.isValid) Icons.Default.CheckCircle else Icons.Default.Warning,
                                            contentDescription = null,
                                            tint = if (state.validation.isValid) SignalTier1 else Color(0xFFEF4444),
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = if (state.validation.isValid) "Update Ready to Install" else "Validation Failed",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                    }
                                    Surface(
                                        color = Color(0xFF0284C7),
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            text = "v${state.releaseInfo.versionName}",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                if (!state.validation.isValid) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = state.validation.errorMessage ?: "Verification failed",
                                        fontSize = 11.sp,
                                        color = Color(0xFFFCA5A5)
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

                    is OtaState.Downloading -> {
                        OutlinedButton(
                            onClick = {},
                            enabled = false,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("DOWNLOADING IN BACKGROUND...", fontSize = 11.sp, color = Color(0xFF94A3B8))
                        }
                    }

                    is OtaState.ReadyToInstall -> {
                        if (state.validation.isValid) {
                            Button(
                                onClick = {
                                    OtaUpdateManager.installApk(context, state.apkFile)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = SignalTier1),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = if (!state.validation.canInstallPackages) "ALLOW PERMISSION & INSTALL" else "INSTALL UPDATE NOW",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = Color.Black
                                )
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { OtaUpdateManager.shareApkFile(context, state.apkFile) },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("SHARE APK", fontSize = 11.sp, fontWeight = FontWeight.Bold)
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
                    }

                    is OtaState.UpdateAvailable -> {
                        Button(
                            onClick = {
                                if (!OtaUpdateManager.canRequestPackageInstalls(context)) {
                                    OtaUpdateManager.openInstallPermissionSettings(context)
                                } else {
                                    scope.launch {
                                        val res = OtaUpdateManager.downloadApk(context, state.releaseInfo)
                                        res.onSuccess { apkFile ->
                                            OtaUpdateManager.installApk(context, apkFile)
                                        }
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = SignalTier1),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "DOWNLOAD & INSTALL UPDATE",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = Color.Black
                            )
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
            }
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
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CloudSync, contentDescription = null, tint = TelemetryCyan, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "LIVE CLOUD FLEET TELEMETRY & NOC",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF94A3B8)
                        )
                    }
                    val isLiveActive = cloudConfig.liveTelemetryEnabled && cloudConfig.firebaseDbUrl.isNotBlank()
                    Surface(
                        color = if (isLiveActive) Color(0xFF065F46) else Color(0xFF334155),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = if (isLiveActive) "LIVE STREAM ON" else "STANDALONE",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isLiveActive) Color(0xFFA7F3D0) else Color(0xFF94A3B8),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Vehicle Identification
                Text(
                    text = "ข้อมูลประจำรถทดสอบ (Vehicle Identity)",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = vehicleIdInput,
                        onValueChange = {
                            vehicleIdInput = it
                            CloudConfigRepository.updateVehicleInfo(it, vehicleNameInput)
                        },
                        label = { Text("Vehicle ID (เช่น DT-01)", fontSize = 10.sp) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = TelemetryCyan,
                            unfocusedBorderColor = Color(0xFF334155),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedContainerColor = Color(0xFF0F172A),
                            unfocusedContainerColor = Color(0xFF0F172A)
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = vehicleNameInput,
                        onValueChange = {
                            vehicleNameInput = it
                            CloudConfigRepository.updateVehicleInfo(vehicleIdInput, it)
                        },
                        label = { Text("ชื่อรถ / ทีมงาน", fontSize = 10.sp) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = TelemetryCyan,
                            unfocusedBorderColor = Color(0xFF334155),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedContainerColor = Color(0xFF0F172A),
                            unfocusedContainerColor = Color(0xFF0F172A)
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1.5f)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = Color(0xFF334155), thickness = 0.8.dp)
                Spacer(modifier = Modifier.height(14.dp))

                // Firebase Realtime DB Section (Pre-configured & Auto-active)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "สตรีม Telemetry สดเข้า Firebase",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                        Text(
                            text = "ส่งพิกัดและค่า RF RSRP/SINR สดเข้า NOC Dashboard อัตโนมัติ",
                            fontSize = 11.sp,
                            color = Color(0xFF94A3B8)
                        )
                    }
                    Switch(
                        checked = liveTelemetryEnabled,
                        onCheckedChange = { checked ->
                            liveTelemetryEnabled = checked
                            CloudConfigRepository.updateFirebaseConfig(fbUrlInput, fbTokenInput, checked)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = TelemetryCyan,
                            uncheckedThumbColor = Color(0xFF94A3B8),
                            uncheckedTrackColor = Color(0xFF334155)
                        )
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    color = Color(0xFF0F172A),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, Color(0xFF334155)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Realtime Database", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Surface(color = Color(0xFF065F46), shape = RoundedCornerShape(4.dp)) {
                                Text("BUILT-IN", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color(0xFFA7F3D0), modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = fbUrlInput.ifBlank { "https://drivetest-telemetry-default-rtdb.asia-southeast1.firebasedatabase.app" },
                            fontSize = 10.sp,
                            color = TelemetryCyan,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("ภูมิภาค: Singapore (asia-southeast1) • ตั้งค่าผ่าน .env / local.properties", fontSize = 10.sp, color = Color(0xFF64748B))
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        scope.launch {
                            fbTesting = true
                            fbTestResult = null
                            val res = CloudTelemetrySyncManager.testFirebaseConnection(fbUrlInput, fbTokenInput)
                            fbTesting = false
                            fbTestResult = if (res.isSuccess) res.getOrNull() else res.exceptionOrNull()?.message
                        }
                    },
                    enabled = !fbTesting && fbUrlInput.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (fbTesting) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("กำลังทดสอบการเชื่อมต่อ...", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    } else {
                        Text("⚡ ตรวจสอบการเชื่อมต่อ Firebase", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                fbTestResult?.let { msg ->
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = msg,
                        fontSize = 11.sp,
                        color = if (msg.contains("สำเร็จ")) SignalTier1 else SignalTier5,
                        lineHeight = 14.sp
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = Color(0xFF334155), thickness = 0.8.dp)
                Spacer(modifier = Modifier.height(14.dp))

                // Google Drive Auto-Upload Section
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "อัปโหลดอัตโนมัติขึ้น Google Drive เมื่อต่อ Wi-Fi",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                        Text(
                            text = "ส่งออกไฟล์ KMZ, CSV, GeoJSON, MapInfo และ PDF ทันทีที่ต่อ Wi-Fi",
                            fontSize = 11.sp,
                            color = Color(0xFF94A3B8)
                        )
                    }
                    Switch(
                        checked = autoUploadOnWifi,
                        onCheckedChange = { checked ->
                            autoUploadOnWifi = checked
                            CloudConfigRepository.updateGoogleDriveConfig(driveTokenInput, driveFolderInput, checked)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = TelemetryCyan,
                            uncheckedThumbColor = Color(0xFF94A3B8),
                            uncheckedTrackColor = Color(0xFF334155)
                        )
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = driveTokenInput,
                    onValueChange = {
                        driveTokenInput = it
                        CloudConfigRepository.updateGoogleDriveConfig(it, driveFolderInput, autoUploadOnWifi)
                    },
                    label = { Text("Google Drive OAuth Access Token (Bearer)", fontSize = 10.sp) },
                    placeholder = { Text("ya29.a0AfH6SM...", fontSize = 10.sp, color = Color(0xFF64748B)) },
                    singleLine = true,
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

                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = driveFolderInput,
                    onValueChange = {
                        driveFolderInput = it
                        CloudConfigRepository.updateGoogleDriveConfig(driveTokenInput, it, autoUploadOnWifi)
                    },
                    label = { Text("Google Drive Folder ID (ไม่บังคับ, ปล่อยว่างจะบันทึกใน My Drive)", fontSize = 10.sp) },
                    singleLine = true,
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

                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        scope.launch {
                            driveTesting = true
                            driveTestResult = null
                            val res = GoogleDriveAutoUploader.testDriveConnection(driveTokenInput)
                            driveTesting = false
                            driveTestResult = if (res.isSuccess) res.getOrNull() else res.exceptionOrNull()?.message
                        }
                    },
                    enabled = !driveTesting && driveTokenInput.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (driveTesting) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("กำลังทดสอบ Google Drive...", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    } else {
                        Text("☁️ ทดสอบเชื่อมต่อ Google Drive", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                driveTestResult?.let { msg ->
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = msg,
                        fontSize = 11.sp,
                        color = if (msg.contains("สำเร็จ")) SignalTier1 else SignalTier5,
                        lineHeight = 14.sp
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
