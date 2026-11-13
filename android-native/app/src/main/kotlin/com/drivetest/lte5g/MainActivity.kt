package com.drivetest.lte5g

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drivetest.lte5g.ui.analytics.AnalyticsScreen
import com.drivetest.lte5g.ui.dashboard.DashboardScreen
import com.drivetest.lte5g.ui.map.DriveTestMapScreen
import com.drivetest.lte5g.ui.sessions.SessionsScreen
import com.drivetest.lte5g.ui.settings.PlayUpdateFallbackDialog
import com.drivetest.lte5g.ui.settings.SettingsScreen
import com.drivetest.lte5g.ui.theme.DriveTestLTE5GTheme
import com.drivetest.lte5g.ui.theme.TelemetryCyan
import com.drivetest.lte5g.ui.theme.TelemetryDarkBg
import com.drivetest.lte5g.updater.OtaState
import com.drivetest.lte5g.updater.OtaUpdateManager
import com.drivetest.lte5g.updater.PlayUpdateAction
import com.drivetest.lte5g.updater.PlayUpdateManager
import com.drivetest.lte5g.updater.PlayUpdateState
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DriveTestLTE5GTheme {
                MainDriveTestApp()
            }
        }
    }
}

@Composable
fun MainDriveTestApp() {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }
    val otaState by OtaUpdateManager.state.collectAsStateWithLifecycle()
    val playState by PlayUpdateManager.state.collectAsStateWithLifecycle()
    var showInstallDialog by remember { mutableStateOf(true) }
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
    val scope = rememberCoroutineScope()

    // Refreshes Play update state on resume (also completes a downloaded flexible update)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch { PlayUpdateManager.onResume(context) }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Checks release notes (GitHub) and asks Play Core whether an update is available
    LaunchedEffect(Unit) {
        PlayUpdateManager.check(context)
        OtaUpdateManager.checkForUpdates(currentVersionName)
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(TelemetryDarkBg),
        bottomBar = {
            NavigationBar(
                containerColor = Color(0xFF0E1626),
                contentColor = Color.White,
                tonalElevation = 8.dp
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Sensors, contentDescription = "Dashboard") },
                    label = { Text("Dashboard", fontSize = 10.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = TelemetryCyan,
                        selectedTextColor = TelemetryCyan,
                        unselectedIconColor = Color(0xFF64748B),
                        unselectedTextColor = Color(0xFF64748B),
                        indicatorColor = Color(0xFF1E293B)
                    )
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Map, contentDescription = "Map") },
                    label = { Text("Route Map", fontSize = 10.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = TelemetryCyan,
                        selectedTextColor = TelemetryCyan,
                        unselectedIconColor = Color(0xFF64748B),
                        unselectedTextColor = Color(0xFF64748B),
                        indicatorColor = Color(0xFF1E293B)
                    )
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Sessions") },
                    label = { Text("Sessions", fontSize = 10.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = TelemetryCyan,
                        selectedTextColor = TelemetryCyan,
                        unselectedIconColor = Color(0xFF64748B),
                        unselectedTextColor = Color(0xFF64748B),
                        indicatorColor = Color(0xFF1E293B)
                    )
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(Icons.Default.AutoAwesome, contentDescription = "AI Analytics") },
                    label = { Text("AI Analytics", fontSize = 10.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = TelemetryCyan,
                        selectedTextColor = TelemetryCyan,
                        unselectedIconColor = Color(0xFF64748B),
                        unselectedTextColor = Color(0xFF64748B),
                        indicatorColor = Color(0xFF1E293B)
                    )
                )
                NavigationBarItem(
                    selected = selectedTab == 4,
                    onClick = { selectedTab = 4 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                    label = { Text("Settings", fontSize = 10.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = TelemetryCyan,
                        selectedTextColor = TelemetryCyan,
                        unselectedIconColor = Color(0xFF64748B),
                        unselectedTextColor = Color(0xFF64748B),
                        indicatorColor = Color(0xFF1E293B)
                    )
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                0 -> DashboardScreen()
                1 -> DriveTestMapScreen()
                2 -> SessionsScreen(
                    onNavigateToMap = { selectedTab = 1 },
                    onNavigateToAnalytics = { selectedTab = 3 }
                )
                3 -> AnalyticsScreen(onNavigateToMap = { selectedTab = 1 })
                4 -> SettingsScreen()
            }
        }
    }

// Play In-App Updates: prompt when Play reports an update for this install
    val playUpdate = playState
    val release = (otaState as? OtaState.UpdateAvailable)?.releaseInfo
    val hasPlayUpdate = playUpdate is PlayUpdateState.Available && !playUpdate.updateInProgress
    val needsCompletion = playUpdate is PlayUpdateState.Downloaded ||
        (playUpdate is PlayUpdateState.Available && playUpdate.updateInProgress)

    if (showInstallDialog && (hasPlayUpdate || needsCompletion)) {
        val androidVersion = when {
            playUpdate is PlayUpdateState.Available -> playUpdate.versionCode.toString()
            else -> release?.versionName.orEmpty()
        }
        AlertDialog(
            onDismissRequest = { showInstallDialog = false },
            icon = {
                Icon(
                    Icons.Default.SystemUpdate,
                    contentDescription = null,
                    tint = TelemetryCyan,
                    modifier = Modifier.size(28.dp)
                )
            },
            title = {
                Text(
                    text = if (release != null) {
                        "พบอัปเดตใหม่ v${release.versionName}"
                    } else {
                        "พบอัปเดตใหม่ (Play build $androidVersion)"
                    },
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    color = Color.White
                )
            },
            text = {
                Column {
                    if (release != null && release.releaseTitle.isNotBlank()) {
                        Text(
                            text = release.releaseTitle,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            color = TelemetryCyan
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                    Text(
                        text = if (needsCompletion) {
                            "ดาวน์โหลดอัปเดตเสร็จแล้ว ปิดแอปหนึ่งครั้งเพื่อติดตั้งให้เสร็จ"
                        } else {
                            "อัปเดตจะติดตั้งผ่าน Google Play ไม่ต้องดาวน์โหลดหรือติดตั้ง APK เอง"
                        },
                        fontSize = 12.sp,
                        color = Color(0xFFCBD5E1),
                        lineHeight = 16.sp
                    )
                    if (release != null && release.releaseNotes.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            color = Color(0xFF1E293B),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = release.releaseNotes.take(240) + if (release.releaseNotes.length > 240) "..." else "",
                                fontSize = 11.sp,
                                color = Color(0xFF94A3B8),
                                modifier = Modifier.padding(8.dp),
                                lineHeight = 14.sp
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showInstallDialog = false
                        scope.launch {
                            val result = PlayUpdateManager.startUpdate(context)
                            val action = result.getOrNull()
                            if (action == null || action == PlayUpdateAction.NOT_FROM_PLAY) {
                                showPlayFallback = true
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = TelemetryCyan)
                ) {
                    Text(
                        text = if (needsCompletion) "ติดตั้งให้เสร็จ" else "อัปเดตเลย",
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showInstallDialog = false }
                ) {
                    Text("ไว้ภายหลัง", color = Color(0xFF94A3B8))
                }
            },
            containerColor = Color(0xFF0F172A),
            tonalElevation = 6.dp
        )
    }

    // Play cannot update a sideloaded install: point the user at the Play listing instead
    if (showPlayFallback) {
        val reason = (playState as? PlayUpdateState.Unavailable)?.reason
            ?: "แอปที่ติดตั้งจาก APK เองจะไม่ได้รับอัปเดตผ่าน Google Play"
        PlayUpdateFallbackDialog(reason = reason, onDismiss = { showPlayFallback = false })
    }
}
