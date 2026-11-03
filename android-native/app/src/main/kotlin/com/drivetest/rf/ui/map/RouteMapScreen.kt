package com.drivetest.rf.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.drivetest.rf.data.CampaignRepository
import com.drivetest.rf.data.RfAnalyticsEngine
import com.drivetest.rf.model.*
import com.drivetest.rf.telephony.DriveTestForegroundService
import com.drivetest.rf.ui.i18n.AppLanguage
import com.drivetest.rf.ui.i18n.AppStrings.tr
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class MapTheme {
    DARK,
    STANDARD
}

@Composable
fun RouteMapScreen(
    measurements: List<Measurement>,
    language: AppLanguage = AppLanguage.EN
) {
    val context = LocalContext.current
    val campaigns by CampaignRepository.campaigns.collectAsState()
    val selectedCampaignId by CampaignRepository.selectedCampaignId.collectAsState()
    val measurementsByCampaign by CampaignRepository.measurementsByCampaign.collectAsState()
    val liveMeasurement by DriveTestForegroundService.liveMeasurement.collectAsState()

    val activeMeasurements = remember(selectedCampaignId, measurementsByCampaign, measurements) {
        val campaignList = measurementsByCampaign[selectedCampaignId]
        if (!campaignList.isNullOrEmpty()) campaignList else measurements
    }

    var colorMode by remember { mutableStateOf(MapColorMode.BY_RSRP) }
    var palette by remember { mutableStateOf(SignalPalette.CLASSIC) }
    var mapTheme by remember { mutableStateOf(MapTheme.DARK) }

    var showCurrentCellOverlay by remember { mutableStateOf(true) }
    var showNeighborCellsOverlay by remember { mutableStateOf(true) }
    var showHandoversOverlay by remember { mutableStateOf(true) }
    var showMissedHosOverlay by remember { mutableStateOf(true) }

    var showSettingsPanel by remember { mutableStateOf(false) }
    var strokeWidthPx by remember { mutableFloatStateOf(14f) }
    var goodRsrpThreshold by remember { mutableIntStateOf(-85) }
    var poorRsrpThreshold by remember { mutableIntStateOf(-105) }

    var inspectedMeasurement by remember { mutableStateOf<Measurement?>(null) }
    var shouldFitBounds by remember { mutableStateOf(false) }
    var shouldCenterPhonePin by remember { mutableStateOf(true) }
    var lastCampaignId by remember { mutableLongStateOf(selectedCampaignId) }

    LaunchedEffect(selectedCampaignId) {
        if (lastCampaignId != selectedCampaignId) {
            lastCampaignId = selectedCampaignId
            shouldFitBounds = true
            inspectedMeasurement = null
        }
    }

    // Dark Map ColorMatrix filter (inverts and tints OSM tiles to dark navy)
    val darkFilter = remember {
        val matrix = ColorMatrix(
            floatArrayOf(
                -0.85f, 0f, 0f, 0f, 215f,
                0f, -0.85f, 0f, 0f, 215f,
                0f, 0f, -0.75f, 0f, 230f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        ColorMatrixColorFilter(matrix)
    }

    remember {
        val osmConfig = Configuration.getInstance()
        osmConfig.userAgentValue = context.packageName
        osmConfig.osmdroidBasePath = File(context.cacheDir, "osmdroid")
        osmConfig.osmdroidTileCache = File(context.cacheDir, "osmdroid/tiles")
        osmConfig.load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
    }

    var mapViewRef by remember { mutableStateOf<MapView?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            mapViewRef?.onPause()
            mapViewRef?.onDetach()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0F172A))) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                MapView(ctx).apply {
                    setMultiTouchControls(true)
                    isTilesScaledToDpi = true
                    zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
                    controller.setZoom(15.5)
                    controller.setCenter(GeoPoint(13.7563, 100.5018))
                    setTileSource(TileSourceFactory.MAPNIK)
                    overlayManager.tilesOverlay.setColorFilter(if (mapTheme == MapTheme.DARK) darkFilter else null)
                    mapViewRef = this
                }
            },
            update = { mapView ->
                mapView.overlayManager.tilesOverlay.setColorFilter(if (mapTheme == MapTheme.DARK) darkFilter else null)
                mapView.overlays.removeAll { it is Polyline || it is Marker || it is Polygon }

                val phoneGps = liveMeasurement.gps
                if (phoneGps.latitude != 0.0 && phoneGps.longitude != 0.0) {
                    val phoneGeo = GeoPoint(phoneGps.latitude, phoneGps.longitude)
                    val phonePin = Marker(mapView).apply {
                        position = phoneGeo
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        icon = createCircleMarker(context, android.graphics.Color.parseColor("#38BDF8"), 42)
                        title = "Phone GPS: ${String.format(Locale.US, "%.5f, %.5f", phoneGps.latitude, phoneGps.longitude)}"
                        setOnMarkerClickListener { _, _ ->
                            inspectedMeasurement = liveMeasurement
                            true
                        }
                    }
                    mapView.overlays.add(phonePin)

                    if (shouldCenterPhonePin) {
                        mapView.controller.animateTo(phoneGeo)
                        mapView.controller.setZoom(16.5)
                        shouldCenterPhonePin = false
                    }
                }

                val validMeasurements = activeMeasurements.filter { it.gps.latitude != 0.0 && it.gps.longitude != 0.0 }

                if (validMeasurements.isNotEmpty()) {
                    // 1. Serving Cell & Neighbor Cell Vectors (optional overlay)
                    if (showCurrentCellOverlay) {
                        val sites = RfAnalyticsEngine.extractCellSites(validMeasurements)
                        sites.take(4).forEach { site ->
                            val siteMarker = Marker(mapView).apply {
                                position = GeoPoint(site.centroidLat, site.centroidLon)
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                                icon = createCircleMarker(context, android.graphics.Color.parseColor("#0284C7"), 30)
                                title = "Site PCI ${site.pci} (${site.band})"
                            }
                            mapView.overlays.add(siteMarker)
                        }
                    }

                    // 2. Segmented Colored Route Polylines
                    val segments = mutableListOf<Pair<Int, MutableList<GeoPoint>>>()
                    for (i in validMeasurements.indices) {
                        val m = validMeasurements[i]
                        val pt = GeoPoint(m.gps.latitude, m.gps.longitude)
                        val color = RfAnalyticsEngine.getMetricColor(
                            m = m,
                            mode = colorMode,
                            palette = palette,
                            goodRsrpThreshold = goodRsrpThreshold,
                            poorRsrpThreshold = poorRsrpThreshold
                        ).toArgb()

                        if (segments.isEmpty() || segments.last().first != color) {
                            val list = mutableListOf<GeoPoint>()
                            if (i > 0) {
                                val prev = validMeasurements[i - 1]
                                list.add(GeoPoint(prev.gps.latitude, prev.gps.longitude))
                            }
                            list.add(pt)
                            segments.add(Pair(color, list))
                        } else {
                            segments.last().second.add(pt)
                        }
                    }

                    for ((color, points) in segments) {
                        if (points.size >= 2) {
                            val polyline = Polyline(mapView).apply {
                                outlinePaint.color = color
                                outlinePaint.strokeWidth = strokeWidthPx
                                outlinePaint.strokeCap = Paint.Cap.ROUND
                                outlinePaint.strokeJoin = Paint.Join.ROUND
                                setPoints(points)
                            }
                            mapView.overlays.add(polyline)
                        }
                    }

                    // 3. Tappable Sample Nodes (every 3rd point + endpoints for fast inspection)
                    validMeasurements.forEachIndexed { idx, m ->
                        if (idx == 0 || idx == validMeasurements.lastIndex || idx % 3 == 0) {
                            val ptColor = RfAnalyticsEngine.getMetricColor(
                                m = m,
                                mode = colorMode,
                                palette = palette,
                                goodRsrpThreshold = goodRsrpThreshold,
                                poorRsrpThreshold = poorRsrpThreshold
                            ).toArgb()
                            val sampleMarker = Marker(mapView).apply {
                                position = GeoPoint(m.gps.latitude, m.gps.longitude)
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                                icon = createCircleMarker(
                                    context,
                                    ptColor,
                                    if (idx == validMeasurements.lastIndex) 36 else 22
                                )
                                title = "Sample #${idx + 1}: ${m.radio.rsrp} dBm"
                                setOnMarkerClickListener { _, _ ->
                                    inspectedMeasurement = m
                                    true
                                }
                            }
                            mapView.overlays.add(sampleMarker)
                        }
                    }

                    // 4. Neighbor Cell Line from Latest/Inspected Point
                    if (showNeighborCellsOverlay) {
                        val ref = inspectedMeasurement ?: validMeasurements.last()
                        ref.radio.neighborCells.take(2).forEachIndexed { nIdx, nc ->
                            val offsetLat = ref.gps.latitude + (if (nIdx == 0) 0.0022 else -0.0018)
                            val offsetLon = ref.gps.longitude + (if (nIdx == 0) -0.0020 else 0.0024)
                            val line = Polyline(mapView).apply {
                                outlinePaint.color = android.graphics.Color.parseColor("#94A3B8")
                                outlinePaint.strokeWidth = 4f
                                setPoints(
                                    listOf(
                                        GeoPoint(ref.gps.latitude, ref.gps.longitude),
                                        GeoPoint(offsetLat, offsetLon)
                                    )
                                )
                            }
                            mapView.overlays.add(line)
                        }
                    }

                    // 5. Handover Markers (Purple)
                    if (showHandoversOverlay) {
                        val hos = RfAnalyticsEngine.detectHandovers(validMeasurements)
                        hos.forEach { ho ->
                            val hoMarker = Marker(mapView).apply {
                                position = GeoPoint(ho.latitude, ho.longitude)
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                                icon = createCircleMarker(context, android.graphics.Color.parseColor("#A855F7"), 28)
                                title = "HO: PCI ${ho.fromPci} -> ${ho.toPci}"
                            }
                            mapView.overlays.add(hoMarker)
                        }
                    }

                    // 6. Missed Handover Markers (Amber/Red)
                    if (showMissedHosOverlay) {
                        val missed = RfAnalyticsEngine.detectMissedHandovers(validMeasurements)
                        missed.forEach { mh ->
                            val mhMarker = Marker(mapView).apply {
                                position = GeoPoint(mh.latitude, mh.longitude)
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                                icon = createCircleMarker(context, android.graphics.Color.parseColor("#F97316"), 26)
                                title = "Missed HO (+${mh.deltaDb} dB PCI ${mh.bestNeighborPci})"
                            }
                            mapView.overlays.add(mhMarker)
                        }
                    }

                    // Fit camera bounds
                    if (shouldFitBounds) {
                        val geoPoints = validMeasurements.map { GeoPoint(it.gps.latitude, it.gps.longitude) }
                        if (geoPoints.size == 1) {
                            mapView.controller.animateTo(geoPoints.first())
                            mapView.controller.setZoom(17.0)
                        } else if (geoPoints.size > 1) {
                            val bb = BoundingBox.fromGeoPoints(geoPoints)
                            val latSpan = (bb.latNorth - bb.latSouth).coerceAtLeast(0.002)
                            val lonSpan = (bb.lonEast - bb.lonWest).coerceAtLeast(0.002)
                            val center = GeoPoint((bb.latNorth + bb.latSouth) / 2.0, (bb.lonEast + bb.lonWest) / 2.0)
                            val paddedBox = BoundingBox(
                                center.latitude + (latSpan * 0.75),
                                center.longitude + (lonSpan * 0.75),
                                center.latitude - (latSpan * 0.75),
                                center.longitude - (lonSpan * 0.75)
                            )
                            mapView.post {
                                mapView.zoomToBoundingBox(paddedBox, true)
                            }
                        }
                        shouldFitBounds = false
                    }
                }

                mapView.invalidate()
            }
        )

        // Top Multi-Row Control Bar: Campaign Switcher + 5 Color Modes + 4 Palettes + 4 Overlays
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(Color(0xD9090F1D))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Row 1: Campaign Switcher + Settings Button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                campaigns.forEach { camp ->
                    val selected = camp.id == selectedCampaignId
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected) Color(0xFF0284C7) else Color(0xFF1E293B))
                            .clickable { CampaignRepository.selectCampaign(camp.id) }
                            .padding(horizontal = 9.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "${camp.name} (${camp.measurementCount})",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            // Row 2: 5 Color Modes + 4 Palettes
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MapColorMode.values().forEach { mode ->
                    val active = colorMode == mode
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (active) Color(0xFF38BDF8) else Color(0xFF152036))
                            .border(1.dp, if (active) Color(0xFF38BDF8) else Color(0xFF334155), RoundedCornerShape(6.dp))
                            .clickable { colorMode = mode }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (language == AppLanguage.TH) mode.labelTh else mode.labelEn,
                            color = if (active) Color(0xFF090F1D) else Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.width(4.dp))

                SignalPalette.values().forEach { pal ->
                    val active = palette == pal
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (active) Color(0xFF1E3A5F) else Color(0xFF152036))
                            .border(1.dp, if (active) Color(0xFF38BDF8) else Color(0xFF1E293B), RoundedCornerShape(6.dp))
                            .clickable { palette = pal }
                            .padding(horizontal = 7.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = pal.name,
                            color = if (active) Color(0xFF38BDF8) else Color(0xFF94A3B8),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Row 3: Overlay Toggles (Cells, Neighbors, Handovers, Missed HO)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OverlayToggleChip(
                    label = tr(language, "Serving Sites", "สถานีฐาน"),
                    active = showCurrentCellOverlay,
                    dotColor = Color(0xFF0284C7),
                    onClick = { showCurrentCellOverlay = !showCurrentCellOverlay }
                )
                OverlayToggleChip(
                    label = tr(language, "Neighbors", "เซลล์ข้างเคียง"),
                    active = showNeighborCellsOverlay,
                    dotColor = Color(0xFF94A3B8),
                    onClick = { showNeighborCellsOverlay = !showNeighborCellsOverlay }
                )
                OverlayToggleChip(
                    label = tr(language, "Handovers", "จุดเปลี่ยนเซลล์"),
                    active = showHandoversOverlay,
                    dotColor = Color(0xFFA855F7),
                    onClick = { showHandoversOverlay = !showHandoversOverlay }
                )
                OverlayToggleChip(
                    label = tr(language, "Missed HO", "พลาดเปลี่ยนเซลล์"),
                    active = showMissedHosOverlay,
                    dotColor = Color(0xFFF97316),
                    onClick = { showMissedHosOverlay = !showMissedHosOverlay }
                )
            }
        }

        // Settings Panel Popup (Dot/Line Width + RSRP Thresholds)
        if (showSettingsPanel) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xF20F172A)),
                border = BorderStroke(1.dp, Color(0xFF334155)),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 68.dp)
                    .width(240.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = tr(language, "Map Thresholds & Style", "ตั้งค่าเกณฑ์สีและขนาดเส้น"),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Line Width: ${strokeWidthPx.toInt()} px", color = Color(0xFF94A3B8), fontSize = 10.sp)
                    Slider(
                        value = strokeWidthPx,
                        onValueChange = { strokeWidthPx = it },
                        valueRange = 6f..24f
                    )
                    Text("Good RSRP >= $goodRsrpThreshold dBm", color = Color(0xFF22C55E), fontSize = 10.sp)
                    Slider(
                        value = goodRsrpThreshold.toFloat(),
                        onValueChange = { goodRsrpThreshold = it.toInt() },
                        valueRange = -95f..-70f
                    )
                    Text("Poor RSRP <= $poorRsrpThreshold dBm", color = Color(0xFFEF4444), fontSize = 10.sp)
                    Slider(
                        value = poorRsrpThreshold.toFloat(),
                        onValueChange = { poorRsrpThreshold = it.toInt() },
                        valueRange = -120f..-96f
                    )
                }
            }
        }

        // Point Inspector Card (when user taps any point on the route)
        inspectedMeasurement?.let { pt ->
            val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xF50F172A)),
                border = BorderStroke(1.dp, Color(0xFF38BDF8)),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 88.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${tr(language, "Point Inspector", "ข้อมูลจุดวัด")} · ${fmt.format(Date(pt.timestampMs))} · ${pt.radio.technology.displayName}",
                            color = Color(0xFF38BDF8),
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                        IconButton(
                            onClick = { inspectedMeasurement = null },
                            modifier = Modifier.size(22.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        StatItem("RSRP", "${pt.radio.rsrp} dBm")
                        StatItem("RSRQ", "${pt.radio.rsrq} dB")
                        StatItem("SINR", "${pt.radio.sinr} dB")
                        StatItem("PCI / Band", "PCI ${pt.radio.pci} · ${pt.radio.band.substringBefore(" ")}")
                        StatItem("Speed", String.format(Locale.US, "%.1f km/h", pt.gps.speedKmh))
                    }
                }
            }
        }

        // Floating Action Buttons (Settings, Style Toggle, Fit Bounds)
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 14.dp, bottom = 92.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SmallFloatingActionButton(
                onClick = { showSettingsPanel = !showSettingsPanel },
                containerColor = Color(0xFF1E293B),
                contentColor = Color(0xFF38BDF8)
            ) {
                Icon(Icons.Default.Tune, contentDescription = "Thresholds", modifier = Modifier.size(18.dp))
            }

            SmallFloatingActionButton(
                onClick = {
                    mapTheme = if (mapTheme == MapTheme.DARK) MapTheme.STANDARD else MapTheme.DARK
                },
                containerColor = Color(0xFF1E293B),
                contentColor = Color(0xFF38BDF8)
            ) {
                Icon(Icons.Default.Layers, contentDescription = "Map Style", modifier = Modifier.size(18.dp))
            }

            FloatingActionButton(
                onClick = { shouldCenterPhonePin = true },
                containerColor = Color(0xFF0284C7),
                contentColor = Color.White,
                shape = CircleShape,
                modifier = Modifier.size(44.dp)
            ) {
                Icon(Icons.Default.MyLocation, contentDescription = "Center Phone GPS", modifier = Modifier.size(20.dp))
            }
        }

        // Bottom Stats Overlay (Preserving exact 4 metrics: Samples, Avg RSRP, Speed, Altitude)
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xEE0F172A)),
            border = BorderStroke(1.dp, Color(0xFF1E293B)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                val last = activeMeasurements.lastOrNull()
                val avgRsrp = if (activeMeasurements.isNotEmpty()) activeMeasurements.map { it.radio.rsrp }.average().toInt() else 0

                StatItem(label = tr(language, "Samples", "จำนวนจุดวัด"), value = "${activeMeasurements.size}")
                StatItem(label = tr(language, "Avg RSRP", "RSRP เฉลี่ย"), value = "$avgRsrp dBm")
                StatItem(label = tr(language, "Speed", "ความเร็ว"), value = String.format(Locale.US, "%.1f km/h", last?.gps?.speedKmh ?: 0f))
                StatItem(label = tr(language, "Altitude", "ความสูง"), value = String.format(Locale.US, "%.0f m", last?.gps?.altitude ?: 0.0))
            }
        }
    }
}

@Composable
private fun OverlayToggleChip(
    label: String,
    active: Boolean,
    dotColor: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (active) Color(0xFF1E293B) else Color(0xFF0F172A))
            .border(1.dp, if (active) dotColor else Color(0xFF334155), RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(if (active) dotColor else Color(0xFF475569))
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = label,
            color = if (active) Color.White else Color(0xFF64748B),
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, color = Color(0xFF94A3B8), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        Text(text = value, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

private fun createCircleMarker(context: Context, color: Int, sizePx: Int): BitmapDrawable {
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = android.graphics.Color.WHITE
        style = Paint.Style.FILL
    }
    canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, paint)
    paint.color = color
    canvas.drawCircle(sizePx / 2f, sizePx / 2f, (sizePx / 2f) - 4f, paint)
    return BitmapDrawable(context.resources, bitmap)
}
