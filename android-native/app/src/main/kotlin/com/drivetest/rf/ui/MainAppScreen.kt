package com.drivetest.rf.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import com.drivetest.rf.data.CampaignRepository
import com.drivetest.rf.location.GpsTracker
import com.drivetest.rf.model.Measurement
import com.drivetest.rf.telephony.DriveTestForegroundService
import com.drivetest.rf.telephony.TelephonyTracker
import com.drivetest.rf.ui.analytics.AnalyticsScreen
import com.drivetest.rf.ui.campaigns.CampaignsScreen
import com.drivetest.rf.ui.dashboard.DashboardScreen
import com.drivetest.rf.ui.i18n.AppLanguage
import com.drivetest.rf.ui.i18n.AppStrings.tr
import com.drivetest.rf.ui.map.RouteMapScreen

enum class AppTab(val labelEn: String, val labelTh: String) {
    DASHBOARD("Live Monitor", "มอนิเตอร์สด"),
    MAP("Route Map", "แผนที่เส้นทาง"),
    CAMPAIGNS("Campaigns", "แคมเปญ"),
    ANALYTICS("Analytics", "วิเคราะห์ RF")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScreen() {
    val context = LocalContext.current
    var currentTab by remember { mutableStateOf(AppTab.DASHBOARD) }
    var isTesting by remember { mutableStateOf(false) }
    var selectedSimSlot by remember { mutableIntStateOf(0) }

    var language by remember { mutableStateOf(AppLanguage.EN) }
    var isDarkMode by remember { mutableStateOf(true) }
    var showWidgetsModal by remember { mutableStateOf(false) }
    var showTutorialModal by remember { mutableStateOf(false) }

    val liveMeasurement by DriveTestForegroundService.liveMeasurement.collectAsState()
    val measurementHistory by DriveTestForegroundService.measurementHistory.collectAsState()

    val telephonyTracker = remember { TelephonyTracker(context) }
    val gpsTracker = remember { GpsTracker(context) }
    val liveGps by gpsTracker.gpsInfo.collectAsState()
    val simSlots = remember { telephonyTracker.getAvailableSims() }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        telephonyTracker.startListening()
        telephonyTracker.pollCellInfo()
        gpsTracker.startTracking()
    }

    DisposableEffect(Unit) {
        telephonyTracker.startListening()
        telephonyTracker.pollCellInfo()
        gpsTracker.startTracking()
        onDispose {
            telephonyTracker.stopListening()
            gpsTracker.stopTracking()
        }
    }

    LaunchedEffect(liveGps) {
        if (!isTesting && liveGps.isValid) {
            DriveTestForegroundService.updateLiveSnapshot(
                liveMeasurement.copy(
                    gps = liveGps,
                    radio = telephonyTracker.radioInfo.value.takeIf { it.isConnected } ?: liveMeasurement.radio
                )
            )
        }
    }

    LaunchedEffect(Unit) {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_PHONE_STATE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    val headerBg = if (isDarkMode) Color(0xFF0B1222) else Color(0xFF1E293B)
    val accentCyan = Color(0xFF38BDF8)

    Scaffold(
        topBar = {
            Surface(
                color = headerBg,
                shadowElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Brand Identity + Version Badge
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF152036))
                                .border(1.dp, accentCyan.copy(alpha = 0.6f), RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Sensors,
                                contentDescription = null,
                                tint = accentCyan,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "RF Field Suite",
                                    color = Color.White,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 14.sp,
                                    maxLines = 1
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(Color(0xFF1E3A5F))
                                        .padding(horizontal = 5.dp, vertical = 1.dp)
                                ) {
                                    Text(
                                        text = "v1.9.3",
                                        color = accentCyan,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 9.sp,
                                        maxLines = 1
                                    )
                                }
                            }
                            Text(
                                text = tr(language, "5G NR / LTE Drive Test", "ทดสอบสัญญาณ 5G/LTE"),
                                color = Color(0xFF94A3B8),
                                fontSize = 9.sp,
                                maxLines = 1
                            )
                        }
                    }

                    // Header Action Pills: Widgets HUD, Interactive Guide, EN/TH, Theme
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        HeaderActionPill(
                            text = tr(language, "Widgets", "วิดเจ็ต"),
                            onClick = { showWidgetsModal = true }
                        )
                        HeaderActionPill(
                            text = tr(language, "Guide", "คู่มือ"),
                            onClick = { showTutorialModal = true }
                        )
                        HeaderActionPill(
                            text = language.code,
                            active = true,
                            onClick = {
                                language = if (language == AppLanguage.EN) AppLanguage.TH else AppLanguage.EN
                            }
                        )
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .background(Color(0xFF152036))
                                .border(1.dp, Color(0xFF334155), RoundedCornerShape(7.dp))
                                .clickable { isDarkMode = !isDarkMode },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isDarkMode) Icons.Default.WbSunny else Icons.Default.DarkMode,
                                contentDescription = "Theme",
                                tint = Color(0xFFFACC15),
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar(containerColor = headerBg) {
                AppTab.values().forEach { tab ->
                    NavigationBarItem(
                        selected = currentTab == tab,
                        onClick = { currentTab = tab },
                        icon = {
                            Icon(
                                imageVector = when (tab) {
                                    AppTab.DASHBOARD -> Icons.Default.Speed
                                    AppTab.MAP -> Icons.Default.Place
                                    AppTab.CAMPAIGNS -> Icons.Default.Folder
                                    AppTab.ANALYTICS -> Icons.Default.Analytics
                                },
                                contentDescription = null
                            )
                        },
                        label = {
                            Text(
                                text = if (language == AppLanguage.TH) tab.labelTh else tab.labelEn,
                                fontSize = 10.sp,
                                fontWeight = if (currentTab == tab) FontWeight.ExtraBold else FontWeight.Normal
                            )
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (currentTab) {
                AppTab.DASHBOARD -> {
                    DashboardScreen(
                        measurement = liveMeasurement,
                        history = measurementHistory,
                        isTesting = isTesting,
                        simSlots = simSlots,
                        selectedSimSlot = selectedSimSlot,
                        onSelectSimSlot = { selectedSimSlot = it },
                        onStartTest = { name, preset ->
                            val created = CampaignRepository.startNewCampaign(name, preset)
                            DriveTestForegroundService.activeCampaignId = created.id
                            DriveTestForegroundService.setPaused(false)
                            isTesting = true
                            val intent = Intent(context, DriveTestForegroundService::class.java)
                            ContextCompat.startForegroundService(context, intent)
                        },
                        onStopTest = {
                            isTesting = false
                            CampaignRepository.stopActiveCampaign()
                            val intent = Intent(context, DriveTestForegroundService::class.java)
                            context.stopService(intent)
                        },
                        onOpenAnalytics = { currentTab = AppTab.ANALYTICS },
                        language = language
                    )
                }
                AppTab.MAP -> {
                    RouteMapScreen(
                        measurements = measurementHistory,
                        language = language
                    )
                }
                AppTab.CAMPAIGNS -> {
                    CampaignsScreen(
                        measurements = measurementHistory,
                        onOpenMap = { currentTab = AppTab.MAP },
                        onOpenAnalytics = { currentTab = AppTab.ANALYTICS },
                        language = language
                    )
                }
                AppTab.ANALYTICS -> {
                    AnalyticsScreen(
                        measurements = measurementHistory,
                        language = language
                    )
                }
            }
        }
    }

    if (showWidgetsModal) {
        WidgetsHudModal(
            measurement = liveMeasurement,
            isTesting = isTesting,
            language = language,
            onDismiss = { showWidgetsModal = false }
        )
    }

    if (showTutorialModal) {
        SpotlightGuideModal(
            language = language,
            onDismiss = { showTutorialModal = false }
        )
    }
}

@Composable
private fun HeaderActionPill(
    text: String,
    active: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(if (active) Color(0xFF1E3A5F) else Color(0xFF152036))
            .border(
                1.dp,
                if (active) Color(0xFF38BDF8) else Color(0xFF334155),
                RoundedCornerShape(7.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 7.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            color = if (active) Color(0xFF38BDF8) else Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
private fun WidgetsHudModal(
    measurement: Measurement,
    isTesting: Boolean,
    language: AppLanguage,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
            border = BorderStroke(1.dp, Color(0xFF38BDF8)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = tr(language, "Android Home Screen & Lock-Screen Widgets", "ตัวอย่างวิดเจ็ตหน้าจอหลักและหน้าจอล็อก"),
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 14.sp
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, contentDescription = null, tint = Color.White)
                    }
                }

                // Widget 1: Compact 2x2 Signal & Cell Pill
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF152036)),
                    border = BorderStroke(1.dp, Color(0xFF1E293B)),
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
                        Column {
                            Text("2x2 COMPACT SIGNAL WIDGET", color = Color(0xFF38BDF8), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Text("${measurement.radio.rsrp} dBm · SINR ${measurement.radio.sinr} dB", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                            Text("${measurement.radio.technology.displayName} · PCI ${measurement.radio.pci} · ${measurement.radio.band}", color = Color(0xFF94A3B8), fontSize = 11.sp)
                        }
                        Icon(Icons.Default.SignalCellularAlt, contentDescription = null, tint = Color(0xFF22C55E), modifier = Modifier.size(28.dp))
                    }
                }

                // Widget 2: 4x2 Drive Test Session Controller Widget
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF152036)),
                    border = BorderStroke(1.dp, Color(0xFF1E293B)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("4x2 FIELD TELEMETRY & QUICK RECORDER", color = Color(0xFF38BDF8), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Status: ${if (isTesting) "RECORDING ACTIVE" else "STANDBY READY"} · Cell ${measurement.radio.cellId}",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        Text(
                            text = "RSRQ ${measurement.radio.rsrq} dB · CQI ${measurement.radio.cqi}/15 · TA ${measurement.radio.timingAdvance}",
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp
                        )
                    }
                }

                // Widget 3: Persistent Notification Bar Preview
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF152036)),
                    border = BorderStroke(1.dp, Color(0xFF1E293B)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("LOCK-SCREEN FOREGROUND SERVICE BANNER", color = Color(0xFFFACC15), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${measurement.radio.technology.displayName} RSRP: ${measurement.radio.rsrp} dBm | SINR: ${measurement.radio.sinr} dB | PCI: ${measurement.radio.pci}",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SpotlightGuideModal(
    language: AppLanguage,
    onDismiss: () -> Unit
) {
    var step by remember { mutableIntStateOf(0) }
    val stepsEn = listOf(
        "1. Live Monitor & Session Controls" to "Select your active SIM slot, sampling interval (Eco 5s / Standard 2s / Precise 1s), and RAT mode. Use Quick Start or Start Drive Test to choose from 4 engineering presets.",
        "2. RF, Serving Cell & Carrier Aggregation" to "Monitor live RSRP, RSRQ, SINR, RSSI, CSI-RSRP, CQI, and Timing Advance. Inspect PCell/SCell Carrier Aggregation bandwidth and Weak Signal Alerts.",
        "3. Active Speed Test & Continuous Ping Probe" to "Run on-demand DL/UL/Ping/Jitter/Loaded Latency tests or keep Continuous Latency Probe enabled during mobility routes.",
        "4. Interactive Multi-Layer Route Map" to "Switch between 5 color metrics (RSRP, RSRQ, SINR, Tech, Speed), 4 scientific palettes, and toggle Serving Sites, Neighbors, Handovers, and Missed HO overlays.",
        "5. Multi-Campaign Exports & 8-Tab Diagnostics" to "Export 24-column CSV, OpenGIS KML 2.2, GeoJSON, or A4 PDF reports, and inspect CDF, Handover Ping-Pong traces, and Automated Route Health Scores."
    )
    val stepsTh = listOf(
        "1. มอนิเตอร์สดและการตั้งค่าเซสชัน" to "เลือกซิมการ์ด ความถี่การเก็บข้อมูล (5 วิ / 2 วิ / 1 วิ) และโหมดเครือข่าย กดเริ่มด่วนหรือเลือกโปรไฟล์การทดสอบ 4 รูปแบบ",
        "2. พารามิเตอร์คลื่นวิทยุและสถานีฐาน" to "ติดตามค่า RSRP, RSRQ, SINR, RSSI, CSI-RSRP, CQI, Timing Advance และการรวมคลื่น Carrier Aggregation พร้อมระบบแจ้งเตือนสัญญาณอ่อน",
        "3. ทดสอบความเร็วและตรวจวัดความหน่วงต่อเนื่อง" to "กดทดสอบความเร็ว ดาวน์โหลด/อัปโหลด/Ping หรือเปิดโหมดตรวจสอบ Ping ต่อเนื่องขณะขับรถ",
        "4. แผนที่เส้นทางแบบหลายเลเยอร์" to "เปลี่ยนโหมดสีตามค่า RSRP, RSRQ, SINR, เทคโนโลยี หรือความเร็ว พร้อมชุดสี 4 รูปแบบ และแสดงจุด Handover บนแผนที่",
        "5. จัดการแคมเปญและวิเคราะห์ผล 8 มิติ" to "ส่งออกไฟล์ CSV 24 คอลัมน์, KML 2.2, GeoJSON และรายงาน PDF พร้อมกราฟ CDF และระบบวินิจฉัยปัญหาอัตโนมัติ"
    )
    val list = if (language == AppLanguage.TH) stepsTh else stepsEn
    val current = list[step]

    Dialog(onDismissRequest = onDismiss) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
            border = BorderStroke(1.dp, Color(0xFF38BDF8)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = tr(language, "RF Field Suite Interactive Guide", "คู่มือการใช้งาน RF Field Suite"),
                    color = Color(0xFF38BDF8),
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 13.sp
                )
                Text(
                    text = current.first,
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 16.sp
                )
                Text(
                    text = current.second,
                    color = Color(0xFFCBD5E1),
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${step + 1} / ${list.size}",
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (step > 0) {
                            OutlinedButton(onClick = { step-- }) {
                                Text(tr(language, "Prev", "ก่อนหน้า"), color = Color.White)
                            }
                        }
                        Button(
                            onClick = {
                                if (step < list.lastIndex) step++ else onDismiss()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF38BDF8))
                        ) {
                            Text(
                                text = if (step < list.lastIndex) tr(language, "Next", "ถัดไป") else tr(language, "Done", "เสร็จสิ้น"),
                                color = Color(0xFF090F1D),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}
