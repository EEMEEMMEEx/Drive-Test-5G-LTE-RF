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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Warning
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
    val currentVersionName = packageInfo?.versionName ?: "1.8.10"
    val currentVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        packageInfo?.longVersionCode?.toInt() ?: 23
    } else {
        @Suppress("DEPRECATION")
        packageInfo?.versionCode ?: 23
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

        // OTA Software Update Card
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFF0284C7), RoundedCornerShape(14.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SystemUpdate,
                            contentDescription = null,
                            tint = TelemetryCyan,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "OVER-THE-AIR (OTA) UPDATES",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = TelemetryCyan
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            color = Color(0xFF065F46),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = "AUTO OTA: ON",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFA7F3D0),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            color = Color(0xFF0369A1),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = "v$currentVersionName",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Auto-checks GitHub on startup, downloads updates in background, and prompts to install. You can also manually trigger check and install here.",
                    fontSize = 12.sp,
                    color = Color(0xFF94A3B8)
                )

                Spacer(modifier = Modifier.height(14.dp))

                // OTA State Display
                when (val state = otaState) {
                    is OtaState.Idle -> {
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
                            Text("CHECK FOR UPDATES", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }

                    is OtaState.Checking -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = TelemetryCyan,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Checking GitHub Releases...",
                                fontSize = 12.sp,
                                color = Color(0xFFA5F3FC)
                            )
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
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = SignalTier2, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
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
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    OtaUpdateManager.checkForUpdates(currentVersionName)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Check Again", fontSize = 11.sp, color = Color(0xFF94A3B8))
                        }
                    }

                    is OtaState.UpdateAvailable -> {
                        val release = state.releaseInfo
                        Surface(
                            color = Color(0xFF1E293B),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, SignalTier1, RoundedCornerShape(10.dp))
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "NEW VERSION AVAILABLE",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = SignalTier1
                                    )
                                    Text(
                                        text = "v${release.versionName}",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color.White
                                    )
                                }

                                if (release.releaseTitle.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = release.releaseTitle,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color(0xFFE2E8F0)
                                    )
                                }

                                if (release.apkSize > 0) {
                                    Text(
                                        text = "Download Size: ${String.format("%.1f", release.apkSize / (1024f * 1024f))} MB",
                                        fontSize = 10.sp,
                                        color = Color(0xFF94A3B8)
                                    )
                                }

                                if (release.releaseNotes.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = release.releaseNotes.take(240) + if (release.releaseNotes.length > 240) "..." else "",
                                        fontSize = 10.sp,
                                        color = Color(0xFFCBD5E1),
                                        lineHeight = 14.sp
                                    )
                                }

                                Spacer(modifier = Modifier.height(12.dp))
                                Button(
                                    onClick = {
                                        if (!OtaUpdateManager.canRequestPackageInstalls(context)) {
                                            OtaUpdateManager.openInstallPermissionSettings(context)
                                        } else {
                                            scope.launch {
                                                val res = OtaUpdateManager.downloadApk(context, release)
                                                res.onSuccess { apkFile ->
                                                    OtaUpdateManager.installApk(context, apkFile)
                                                }
                                            }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = SignalTier1),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = if (!OtaUpdateManager.canRequestPackageInstalls(context))
                                            "ALLOW PERMISSION & DOWNLOAD"
                                        else
                                            "DOWNLOAD & INSTALL UPDATE",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = Color.Black
                                    )
                                }
                            }
                        }
                    }

                    is OtaState.Downloading -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Downloading APK update...",
                                    fontSize = 11.sp,
                                    color = Color(0xFFA5F3FC),
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "${(state.progress * 100).toInt()}%",
                                    fontSize = 11.sp,
                                    color = TelemetryCyan,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { state.progress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp),
                                color = TelemetryCyan,
                                trackColor = Color(0xFF1E293B)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${String.format("%.1f", state.downloadedBytes / (1024f * 1024f))} / ${String.format("%.1f", state.totalBytes / (1024f * 1024f))} MB",
                                fontSize = 10.sp,
                                color = Color(0xFF94A3B8)
                            )
                        }
                    }

                    is OtaState.ReadyToInstall -> {
                        if (!state.validation.isValid) {
                            Surface(
                                color = Color(0xFF450A0A),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, Color(0xFFDC2626)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFEF4444), modifier = Modifier.size(20.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = state.validation.errorMessage ?: "ตรวจสอบการติดตั้งไม่ผ่าน",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.5.sp,
                                            color = Color.White
                                        )
                                    }
                                    if (!state.validation.errorDetails.isNullOrBlank()) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = state.validation.errorDetails,
                                            fontSize = 11.sp,
                                            color = Color(0xFFFCA5A5),
                                            lineHeight = 15.sp
                                        )
                                    }
                                    if (state.validation.isSignatureMismatch) {
                                        Spacer(modifier = Modifier.height(10.dp))
                                        Surface(
                                            color = Color(0xFF1E293B),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Column(modifier = Modifier.padding(10.dp)) {
                                                Text(
                                                    text = "💡 คำแนะนำในการอัปเดต:",
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 11.5.sp,
                                                    color = TelemetryCyan
                                                )
                                                Spacer(modifier = Modifier.height(4.dp))
                                                Text(
                                                    text = "เนื่องจากแอปเดิมและไฟล์ใหม่ลงนามด้วย Keystore ต่างกัน เพื่อความปลอดภัยของระบบ Android ให้ถอนการติดตั้ง (Uninstall) แอปเดิมออกก่อน 1 ครั้ง จากนั้นกดเปิดไฟล์ APK นี้เพื่อติดตั้งใหม่ทันที",
                                                    fontSize = 11.sp,
                                                    color = Color(0xFFCBD5E1),
                                                    lineHeight = 15.sp
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(10.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Button(
                                                onClick = {
                                                    OtaUpdateManager.shareApkFile(context, state.apkFile)
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = TelemetryCyan),
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Text("เปิด / ส่งต่อไฟล์ APK", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                                            }
                                            OutlinedButton(
                                                onClick = {
                                                    OtaUpdateManager.resetState()
                                                },
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Text("ยกเลิก", fontSize = 11.sp, color = Color(0xFF94A3B8))
                                            }
                                        }
                                    } else {
                                        Spacer(modifier = Modifier.height(10.dp))
                                        OutlinedButton(
                                            onClick = {
                                                OtaUpdateManager.resetState()
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text("ลบไฟล์และดาวน์โหลดใหม่", fontSize = 11.sp, color = Color.White)
                                        }
                                    }
                                }
                            }
                        } else if (!state.validation.canInstallPackages) {
                            Surface(
                                color = Color(0xFF451A03),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, Color(0xFFD97706)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFF59E0B), modifier = Modifier.size(20.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "ต้องเปิดสิทธิ์ 'ติดตั้งแอปที่ไม่รู้จัก'",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.5.sp,
                                            color = Color.White
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "Android กำหนดให้แอปต้องได้รับอนุญาตสิทธิ์ก่อนเรียก Package Installer เพื่อติดตั้งไฟล์อัปเดต",
                                        fontSize = 11.sp,
                                        color = Color(0xFFFDE68A),
                                        lineHeight = 15.sp
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Button(
                                        onClick = {
                                            OtaUpdateManager.openInstallPermissionSettings(context)
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF59E0B)),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("เปิดการตั้งค่าเพื่ออนุญาตสิทธิ์ ↗", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color.Black)
                                    }
                                }
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        OtaUpdateManager.installApk(context, state.apkFile)
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = SignalTier1),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("INSTALL DOWNLOADED UPDATE", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color.Black)
                                }
                                Surface(
                                    color = Color(0xFF064E3B).copy(alpha = 0.5f),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "✓ ตรวจสอบไฟล์สมบูรณ์ & Keystore ตรงกัน พร้อมติดตั้งทันที",
                                            fontSize = 10.sp,
                                            color = Color(0xFF6EE7B7)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    is OtaState.Error -> {
                        Surface(
                            color = Color(0xFF450A0A),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
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
                                        fontSize = 10.sp,
                                        color = SignalTier4
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                OtaUpdateManager.resetState()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Dismiss", fontSize = 11.sp, color = Color(0xFF94A3B8))
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
