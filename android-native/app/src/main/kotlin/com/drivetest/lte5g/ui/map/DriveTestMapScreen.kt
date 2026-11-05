package com.drivetest.lte5g.ui.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drivetest.lte5g.data.SessionRepository
import com.drivetest.lte5g.service.DriveTestForegroundService
import com.drivetest.lte5g.ui.theme.*
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*

/** Resolves the quality tier (1..5) to a color, shared by RSRP / RSRQ / SINR modes. */
private fun qualityColorFor(key: Int): Color = when (key) {
    1 -> SignalTier1
    2 -> SignalTier2
    3 -> SignalTier3
    4 -> SignalTier4
    else -> SignalTier5
}

/** Maps a legend key to a map color under the selected [RouteColorMode]. */
@Composable
private fun routeColorFor(mode: RouteColorMode, key: Int): Color = when (mode) {
    RouteColorMode.RSRP, RouteColorMode.RSRQ, RouteColorMode.SINR -> qualityColorFor(key)
    RouteColorMode.TECHNOLOGY -> when (key) {
        1 -> TechNrSa
        2 -> TechNrNsa
        3 -> TechLte
        else -> TechUnknown
    }
    RouteColorMode.SPEED -> when (key) {
        1 -> SpeedTier1
        2 -> SpeedTier2
        3 -> SpeedTier3
        4 -> SpeedTier4
        else -> SpeedTier5
    }
}

@SuppressLint("MissingPermission")
@Composable
fun DriveTestMapScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isRecording by DriveTestForegroundService.isRecording.collectAsStateWithLifecycle()
    val latestSample by DriveTestForegroundService.latestSample.collectAsStateWithLifecycle()
    val isDualSimMode by DriveTestForegroundService.isDualSimMode.collectAsStateWithLifecycle()
    val activeSession by SessionRepository.activeSession.collectAsStateWithLifecycle()
    val selectedSession by SessionRepository.selectedSessionForMap.collectAsStateWithLifecycle()

    // Map UI state
    var colorMode by remember { mutableStateOf(RouteColorMode.RSRP) }
    var showSim1 by remember { mutableStateOf(true) }
    var showSim2 by remember { mutableStateOf(true) }

    val displaySession = if (isRecording) activeSession else (selectedSession ?: activeSession)
    val displaySamples = displaySession?.samples ?: emptyList()
    val hasSim2Data = displaySamples.any { it.metricsSim2 != null } || isDualSimMode

    val fusedClient = remember { LocationServices.getFusedLocationProviderClient(context) }
    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasLocationPermission = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }

    LaunchedEffect(Unit) {
        if (!hasLocationPermission) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    val bangkokDefault = LatLng(13.7563, 100.5018)
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(bangkokDefault, 14f)
    }

    // Auto-center on current GPS location on first load if not recording and no previous session loaded
    LaunchedEffect(hasLocationPermission) {
        if (hasLocationPermission && !isRecording && displaySamples.isEmpty()) {
            try {
                fusedClient.lastLocation.addOnSuccessListener { loc ->
                    if (loc != null) {
                        cameraPositionState.position = CameraPosition.fromLatLngZoom(
                            LatLng(loc.latitude, loc.longitude),
                            16f
                        )
                    }
                }
            } catch (_: Exception) {}
        }
    }

    // Auto-center map on new sample or when a session is selected
    LaunchedEffect(latestSample, selectedSession) {
        val target = if (isRecording) {
            latestSample?.let { LatLng(it.latitude, it.longitude) }
        } else {
            displaySamples.lastOrNull()?.let { LatLng(it.latitude, it.longitude) }
                ?: displaySamples.firstOrNull()?.let { LatLng(it.latitude, it.longitude) }
        }

        if (target != null) {
            cameraPositionState.position = CameraPosition.fromLatLngZoom(target, 16f)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            properties = MapProperties(isMyLocationEnabled = hasLocationPermission),
            uiSettings = MapUiSettings(
                myLocationButtonEnabled = true,
                compassEnabled = true,
                zoomControlsEnabled = false
            )
        ) {
            // Draw route segments colored by the selected mode.
            // SIM 2 is drawn first (under SIM 1) with a wider stroke, mirroring the KMZ export.
            if (displaySamples.size >= 2) {
                if (showSim2 && hasSim2Data) {
                    for (i in 0 until displaySamples.size - 1) {
                        val p1 = displaySamples[i]
                        val p2 = displaySamples[i + 1]
                        val key = legendKeyFor(p1, colorMode, 2) ?: continue
                        Polyline(
                            points = listOf(
                                LatLng(p1.latitude, p1.longitude),
                                LatLng(p2.latitude, p2.longitude)
                            ),
                            color = routeColorFor(colorMode, key),
                            width = 16f
                        )
                    }
                }
                if (showSim1) {
                    for (i in 0 until displaySamples.size - 1) {
                        val p1 = displaySamples[i]
                        val p2 = displaySamples[i + 1]
                        val key = legendKeyFor(p1, colorMode, 1) ?: continue
                        Polyline(
                            points = listOf(
                                LatLng(p1.latitude, p1.longitude),
                                LatLng(p2.latitude, p2.longitude)
                            ),
                            color = routeColorFor(colorMode, key),
                            width = 12f
                        )
                    }
                }
            }

            // Current Vehicle Marker (if recording) or Last Point Marker
            val markerSample = if (isRecording) latestSample else displaySamples.lastOrNull()
            markerSample?.let { s ->
                Marker(
                    state = MarkerState(position = LatLng(s.latitude, s.longitude)),
                    title = if (isRecording) "Vehicle Position" else "End Position",
                    snippet = "Speed: ${String.format("%.1f", s.speedKmh)} km/h | RSRP: ${s.metrics.nr?.ssRsrp ?: s.metrics.lte?.rsrp ?: "--"} dBm"
                )
            }
        }

        // Floating Signal Legend (Bottom-Left), driven by the selected color mode
        Card(
            colors = CardDefaults.cardColors(containerColor = TelemetryCardBg.copy(alpha = 0.92f)),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp)
                .border(1.dp, TelemetryCardStroke, RoundedCornerShape(10.dp))
        ) {
            Column(modifier = Modifier.padding(10.dp).widthIn(max = 220.dp)) {
                Text(
                    text = colorMode.legendTitle,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF94A3B8)
                )
                Spacer(modifier = Modifier.height(6.dp))
                legendEntries(colorMode).forEach { (key, label) ->
                    LegendRow(color = routeColorFor(colorMode, key), label = label)
                }
            }
        }

        // Quick Start / Stop Floating Button (Bottom-Right)
        FloatingActionButton(
            onClick = {
                if (isRecording) {
                    DriveTestForegroundService.stop(context)
                } else {
                    DriveTestForegroundService.start(context)
                }
            },
            containerColor = if (isRecording) SignalTier5 else Color(0xFF16A34A),
            contentColor = Color.White,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = null
                )
                Text(
                    text = if (isRecording) "STOP" else "START TEST",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
        }

        // Top Floating Stats & Session Banner with color mode / layer controls
        Surface(
            color = TelemetryDarkBg.copy(alpha = 0.92f),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 16.dp, start = 12.dp, end = 12.dp)
                .border(1.dp, TelemetryCardStroke, RoundedCornerShape(10.dp))
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        color = if (displaySession?.isRecording == true) Color(0xFF991B1B) else Color(0xFF1E293B),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = if (displaySession?.isRecording == true) "● LIVE" else "SAVED",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (displaySession?.isRecording == true) Color.White else Color(0xFF94A3B8),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    Text(
                        text = displaySession?.name ?: "No Route Loaded",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1
                    )

                    Text(
                        text = "${displaySamples.size} pts",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TelemetryCyan
                    )

                    if (isRecording) {
                        Text(
                            text = "${latestSample?.let { String.format("%.1f", it.speedKmh) } ?: "0.0"} km/h",
                            fontSize = 12.sp,
                            color = Color(0xFFA5F3FC)
                        )
                    }
                }

                // Color mode selector + SIM layer toggles (SIM 2 only for dual-SIM sessions)
                if (displaySamples.isNotEmpty() || isRecording) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        RouteColorMode.entries.forEach { mode ->
                            FilterChip(
                                selected = colorMode == mode,
                                onClick = { colorMode = mode },
                                label = {
                                    Text(
                                        text = mode.label,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                },
                                modifier = Modifier.height(30.dp)
                            )
                        }
                        if (hasSim2Data) {
                            Spacer(modifier = Modifier.width(4.dp))
                            FilterChip(
                                selected = showSim1,
                                onClick = { showSim1 = !showSim1 },
                                label = { Text("SIM 1", fontSize = 10.sp) },
                                modifier = Modifier.height(30.dp)
                            )
                            FilterChip(
                                selected = showSim2,
                                onClick = { showSim2 = !showSim2 },
                                label = { Text("SIM 2", fontSize = 10.sp) },
                                modifier = Modifier.height(30.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LegendRow(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 2.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color, RoundedCornerShape(2.dp))
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = label, fontSize = 9.sp, color = Color.White)
    }
}
