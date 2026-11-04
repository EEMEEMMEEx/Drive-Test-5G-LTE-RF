package com.drivetest.lte5g.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.drivetest.lte5g.ui.theme.TelemetryCardBg
import com.drivetest.lte5g.ui.theme.TelemetryCardStroke
import com.drivetest.lte5g.ui.theme.TelemetryDarkBg

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val measurementCadence by DriveTestForegroundService.measurementCadence.collectAsStateWithLifecycle()
    val scrollState = rememberScrollState()

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

        // Measurement Cadence Configuration Card (feat)
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
                        } catch (e: Exception) {
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

        // App Info
        Text(
            text = "Drive Test LTE/5G v1.0.0 (Build 1) • Native Android Kotlin",
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
