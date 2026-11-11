package com.drivetest.lte5g.ui.sessions

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.TableChart
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
import com.drivetest.lte5g.ai.GeminiAdvisorState
import com.drivetest.lte5g.ai.GeminiRfAdvisor
import com.drivetest.lte5g.cloud.CloudConfigRepository
import com.drivetest.lte5g.cloud.CloudUploadStatus
import com.drivetest.lte5g.cloud.GoogleDriveAutoUploader
import com.drivetest.lte5g.data.SessionRepository
import com.drivetest.lte5g.data.analytics.RfAnalyticsEngine
import com.drivetest.lte5g.export.GisExporter
import com.drivetest.lte5g.export.KmlExporter
import com.drivetest.lte5g.export.PdfReportExporter
import com.drivetest.lte5g.model.DriveTestSession
import com.drivetest.lte5g.model.RatLockMode
import com.drivetest.lte5g.service.DriveTestForegroundService
import com.drivetest.lte5g.ui.dashboard.getRsrpColor
import com.drivetest.lte5g.ui.dashboard.sim2RsrpColor
import com.drivetest.lte5g.ui.map.dualSimBenchmarkComparison
import com.drivetest.lte5g.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SessionsScreen(
    modifier: Modifier = Modifier,
    onNavigateToMap: (() -> Unit)? = null,
    onNavigateToAnalytics: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val isRecording by DriveTestForegroundService.isRecording.collectAsStateWithLifecycle()
    val ratLockMode by DriveTestForegroundService.ratLockMode.collectAsStateWithLifecycle()
    val measurementCadence by DriveTestForegroundService.measurementCadence.collectAsStateWithLifecycle()
    val activeSession by SessionRepository.activeSession.collectAsStateWithLifecycle()
    val allSessions by SessionRepository.sessions.collectAsStateWithLifecycle()
    val sessionStatuses by CloudConfigRepository.sessionStatuses.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var sessionToExportKmz by remember { mutableStateOf<DriveTestSession?>(null) }
    var sessionToExportCsv by remember { mutableStateOf<DriveTestSession?>(null) }
    var sessionToExportPdf by remember { mutableStateOf<DriveTestSession?>(null) }
    var sessionToExportGeoJson by remember { mutableStateOf<DriveTestSession?>(null) }
    var sessionToExportMapInfo by remember { mutableStateOf<DriveTestSession?>(null) }
    var sessionForExportMenu by remember { mutableStateOf<DriveTestSession?>(null) }

    var showNewSessionDialog by remember { mutableStateOf(false) }
    var sessionNameInput by remember { mutableStateOf("") }

    var sessionToRename by remember { mutableStateOf<DriveTestSession?>(null) }
    var renameInput by remember { mutableStateOf("") }

    var sessionToDelete by remember { mutableStateOf<DriveTestSession?>(null) }

    val kmzLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.google-earth.kmz")
    ) { uri ->
        if (uri != null) {
            val session = sessionToExportKmz ?: activeSession
            val samples = session?.samples ?: emptyList()
            if (samples.isEmpty()) {
                Toast.makeText(context, "No samples in this session to export!", Toast.LENGTH_SHORT).show()
                return@rememberLauncherForActivityResult
            }
            try {
                context.contentResolver.openOutputStream(uri)?.use { os ->
                    KmlExporter.exportKmz(samples, os)
                }
                Toast.makeText(context, "KMZ exported successfully! (${samples.size} points)", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Failed to export KMZ: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    val csvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) {
            val session = sessionToExportCsv ?: activeSession
            val samples = session?.samples ?: emptyList()
            if (samples.isEmpty()) {
                Toast.makeText(context, "No samples in this session to export!", Toast.LENGTH_SHORT).show()
                return@rememberLauncherForActivityResult
            }
            try {
                context.contentResolver.openOutputStream(uri)?.use { os ->
                    KmlExporter.exportCsv(samples, os)
                }
                Toast.makeText(context, "CSV exported successfully! (${samples.size} points)", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Failed to export CSV: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    val pdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        if (uri != null) {
            val session = sessionToExportPdf ?: activeSession
            if (session == null || session.samples.isEmpty()) {
                Toast.makeText(context, "No samples to generate PDF report!", Toast.LENGTH_SHORT).show()
                return@rememberLauncherForActivityResult
            }
            try {
                val analytics = RfAnalyticsEngine.analyze(session)
                val aiState = GeminiRfAdvisor.state.value
                val aiReport = if (aiState is GeminiAdvisorState.Success) aiState.report else null
                context.contentResolver.openOutputStream(uri)?.use { os ->
                    PdfReportExporter.exportPdfReport(session, analytics, aiReport, os)
                }
                Toast.makeText(context, "PDF Report exported successfully!", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Failed to export PDF: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    val geoJsonLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/geo+json")
    ) { uri ->
        if (uri != null) {
            val session = sessionToExportGeoJson ?: activeSession
            if (session == null || session.samples.isEmpty()) {
                Toast.makeText(context, "No samples to export GeoJSON!", Toast.LENGTH_SHORT).show()
                return@rememberLauncherForActivityResult
            }
            try {
                context.contentResolver.openOutputStream(uri)?.use { os ->
                    GisExporter.exportGeoJson(session, os)
                }
                Toast.makeText(context, "GeoJSON exported successfully!", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Failed to export GeoJSON: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    val mapInfoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            val session = sessionToExportMapInfo ?: activeSession
            if (session == null || session.samples.isEmpty()) {
                Toast.makeText(context, "No samples to export MapInfo!", Toast.LENGTH_SHORT).show()
                return@rememberLauncherForActivityResult
            }
            try {
                context.contentResolver.openOutputStream(uri)?.use { os ->
                    GisExporter.exportMapInfoMifMid(session, os)
                }
                Toast.makeText(context, "MapInfo MIF/MID ZIP exported successfully!", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Failed to export MapInfo: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(TelemetryDarkBg)
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Screen Header & Action Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Test Sessions & Export",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = "Per-Session Log Archive & KMZ / CSV Export",
                    fontSize = 11.sp,
                    color = Color(0xFF94A3B8)
                )
            }
        }

        // Primary NEW SESSION Button
        Button(
            onClick = {
                sessionNameInput = SessionRepository.getDefaultSessionName()
                showNewSessionDialog = true
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("NEW DRIVE TEST SESSION", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }

        // Active Session Card
        Card(
            colors = CardDefaults.cardColors(containerColor = TelemetryCardBg),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, if (isRecording) SignalTier2 else TelemetryCardStroke, RoundedCornerShape(14.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "ACTIVE SESSION",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF94A3B8)
                    )
                    Surface(
                        color = if (isRecording) Color(0xFF991B1B) else Color(0xFF1E293B),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = if (isRecording) "● RECORDING LIVE" else "READY / IDLE",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isRecording) Color.White else Color(0xFF94A3B8),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = activeSession?.name ?: "No session started",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.weight(1f)
                    )
                    if (activeSession != null) {
                        IconButton(
                            onClick = {
                                sessionToRename = activeSession
                                renameInput = activeSession?.name ?: ""
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = "Rename",
                                tint = Color(0xFF94A3B8),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                val sampleCount = activeSession?.sampleCount ?: 0
                val avgRsrp = activeSession?.avgRsrp

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "Points: $sampleCount",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFCBD5E1)
                    )
                    Text(
                        text = "Avg: ${avgRsrp?.let { "$it dBm" } ?: "N/A"}",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TelemetryCyan
                    )
                    Text(
                        text = "Mode: ${activeSession?.ratLockMode?.name ?: ratLockMode.name}",
                        fontSize = 13.sp,
                        color = Color(0xFF94A3B8)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            val session = activeSession
                            if (session == null || session.samples.isEmpty()) {
                                Toast.makeText(context, "No samples to export! Start drive test first.", Toast.LENGTH_SHORT).show()
                            } else {
                                sessionForExportMenu = session
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0D9488)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1.2f)
                    ) {
                        Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("EXPORT DATA...", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }

                    Button(
                        onClick = {
                            val session = activeSession
                            if (session == null || session.samples.isEmpty()) {
                                Toast.makeText(context, "No samples to analyze! Start drive test first.", Toast.LENGTH_SHORT).show()
                            } else {
                                SessionRepository.selectSessionForMap(session)
                                onNavigateToAnalytics?.invoke()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4F46E5)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("AI", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        }

        // Completed Sessions Header
        val completedSessions = allSessions.filter { it.id != activeSession?.id || !it.isRecording }
        Text(
            text = "SAVED SESSIONS (${completedSessions.size})",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF94A3B8)
        )

        if (completedSessions.isEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, TelemetryCardStroke, RoundedCornerShape(12.dp))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "No saved drive test sessions yet.",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF94A3B8)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Tap 'NEW DRIVE TEST SESSION' or 'START' on Dashboard to begin recording.",
                        fontSize = 12.sp,
                        color = Color(0xFF64748B)
                    )
                }
            }
        } else {
            completedSessions.forEach { session ->
                SessionItemCard(
                    session = session,
                    cloudStatus = sessionStatuses[session.id] ?: CloudUploadStatus.NONE,
                    onExport = {
                        sessionForExportMenu = session
                    },
                    onViewMap = {
                        SessionRepository.selectSessionForMap(session)
                        onNavigateToMap?.invoke()
                        Toast.makeText(context, "Selected ${session.name} on Route Map", Toast.LENGTH_SHORT).show()
                    },
                    onAnalyze = {
                        SessionRepository.selectSessionForMap(session)
                        onNavigateToAnalytics?.invoke()
                    },
                    onRename = {
                        sessionToRename = session
                        renameInput = session.name
                    },
                    onDelete = {
                        sessionToDelete = session
                    }
                )
            }
        }

        // NEW SESSION DIALOG
        if (showNewSessionDialog) {
            AlertDialog(
                onDismissRequest = { showNewSessionDialog = false },
                title = { Text("New Drive Test Session", color = Color.White, fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "ระบุชื่อเซสชันใหม่:",
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
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A))
                    ) {
                        Text("START TEST", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showNewSessionDialog = false }) {
                        Text("CANCEL", color = Color(0xFF94A3B8))
                    }
                },
                containerColor = Color(0xFF1E293B)
            )
        }

        // RENAME DIALOG
        sessionToRename?.let { target ->
            AlertDialog(
                onDismissRequest = { sessionToRename = null },
                title = { Text("Rename Session", color = Color.White, fontWeight = FontWeight.Bold) },
                text = {
                    OutlinedTextField(
                        value = renameInput,
                        onValueChange = { renameInput = it },
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
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (renameInput.isNotBlank()) {
                                SessionRepository.renameSession(target.id, renameInput.trim())
                            }
                            sessionToRename = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
                    ) {
                        Text("SAVE", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { sessionToRename = null }) {
                        Text("CANCEL", color = Color(0xFF94A3B8))
                    }
                },
                containerColor = Color(0xFF1E293B)
            )
        }

        // DELETE DIALOG
        sessionToDelete?.let { target ->
            AlertDialog(
                onDismissRequest = { sessionToDelete = null },
                title = { Text("Delete Session?", color = Color.White, fontWeight = FontWeight.Bold) },
                text = {
                    Text(
                        "ต้องการลบเซสชัน \"${target.name}\" และข้อมูลพิกัด ${target.sampleCount} จุด ออกอย่างถาวรใช่หรือไม่?",
                        color = Color(0xFFCBD5E1)
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            SessionRepository.deleteSession(target.id)
                            sessionToDelete = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                    ) {
                        Text("DELETE", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { sessionToDelete = null }) {
                        Text("CANCEL", color = Color(0xFF94A3B8))
                    }
                },
                containerColor = Color(0xFF1E293B)
            )
        }

        // EXPORT OPTIONS DIALOG
        sessionForExportMenu?.let { session ->
            val cleanName = session.name.replace(Regex("[^a-zA-Z0-9_-]"), "_").ifBlank { "drivetest_${session.id}" }
            AlertDialog(
                onDismissRequest = { sessionForExportMenu = null },
                title = {
                    Column {
                        Text("Export Drive Test Data", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "${session.name} (${session.sampleCount} samples)",
                            color = TelemetryCyan,
                            fontSize = 12.sp
                        )
                    }
                },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ExportOptionItem(
                            icon = Icons.Default.PictureAsPdf,
                            iconTint = Color(0xFF38BDF8),
                            title = "Site Acceptance PDF Report",
                            description = "Standard A4 Vector, Scorecard, KPI, Map Snapshot, AI & Signatures",
                            onClick = {
                                sessionToExportPdf = session
                                sessionForExportMenu = null
                                pdfLauncher.launch("${cleanName}_${session.id}_acceptance.pdf")
                            }
                        )
                        ExportOptionItem(
                            icon = Icons.Default.Public,
                            iconTint = Color(0xFF14B8A6),
                            title = "Google Earth KMZ",
                            description = "3D Point Towers & RSRP Color-Coded Route Track",
                            onClick = {
                                sessionToExportKmz = session
                                sessionForExportMenu = null
                                kmzLauncher.launch("${cleanName}_${session.id}.kmz")
                            }
                        )
                        ExportOptionItem(
                            icon = Icons.Default.TableChart,
                            iconTint = Color(0xFFF59E0B),
                            title = "Engineering CSV",
                            description = "Full Raw Drive Test Metrics (RSRP, SINR, PCI, GPS) for Excel",
                            onClick = {
                                sessionToExportCsv = session
                                sessionForExportMenu = null
                                csvLauncher.launch("${cleanName}_${session.id}.csv")
                            }
                        )
                        ExportOptionItem(
                            icon = Icons.Default.Map,
                            iconTint = Color(0xFF10B981),
                            title = "GIS GeoJSON (RFC 7946)",
                            description = "Survey Points & Route LineString for QGIS, ArcGIS & Web GIS",
                            onClick = {
                                sessionToExportGeoJson = session
                                sessionForExportMenu = null
                                geoJsonLauncher.launch("${cleanName}_${session.id}.geojson")
                            }
                        )
                        ExportOptionItem(
                            icon = Icons.Default.FolderZip,
                            iconTint = Color(0xFFA855F7),
                            title = "MapInfo Interchange (MIF/MID ZIP)",
                            description = "Packaged ZIP with .mif/.mid for Forsk Atoll & InfoVista Planet",
                            onClick = {
                                sessionToExportMapInfo = session
                                sessionForExportMenu = null
                                mapInfoLauncher.launch("${cleanName}_${session.id}_mapinfo.zip")
                            }
                        )
                        HorizontalDivider(color = Color(0xFF334155), thickness = 0.8.dp)
                        ExportOptionItem(
                            icon = Icons.Default.CloudUpload,
                            iconTint = Color(0xFF38BDF8),
                            title = "Upload All 5 Formats to Google Drive",
                            description = "Bundle KMZ, CSV, GeoJSON, MapInfo & PDF and upload to Cloud now",
                            onClick = {
                                sessionForExportMenu = null
                                Toast.makeText(context, "กำลังเตรียมชุดไฟล์และอัปโหลดขึ้น Google Drive...", Toast.LENGTH_SHORT).show()
                                GoogleDriveAutoUploader.uploadSessionAsync(session) { success, msg ->
                                    scope.launch(Dispatchers.Main) {
                                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        )
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { sessionForExportMenu = null }) {
                        Text("CANCEL", color = Color(0xFF94A3B8))
                    }
                },
                containerColor = Color(0xFF1E293B)
            )
        }
    }
}

@Composable
fun SessionItemCard(
    session: DriveTestSession,
    cloudStatus: CloudUploadStatus = CloudUploadStatus.NONE,
    onExport: () -> Unit,
    onViewMap: () -> Unit,
    onAnalyze: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    val dateStr = remember(session.startTime) {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(session.startTime))
    }

    val durationStr = remember(session.startTime, session.endTime) {
        val durationMs = (session.endTime ?: System.currentTimeMillis()) - session.startTime
        val seconds = (durationMs / 1000) % 60
        val minutes = (durationMs / (1000 * 60)) % 60
        val hours = durationMs / (1000 * 60 * 60)
        if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m ${seconds}s"
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, TelemetryCardStroke, RoundedCornerShape(12.dp))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header Row: Name & Action Icons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = session.name,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "$dateStr • Duration: $durationStr",
                        fontSize = 11.sp,
                        color = Color(0xFF94A3B8)
                    )
                }

                Row {
                    IconButton(onClick = onRename, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Default.Edit, contentDescription = "Rename", tint = Color(0xFF94A3B8), modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFEF4444), modifier = Modifier.size(16.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Badges Row
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = Color(0xFF1E293B),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = session.ratLockMode.name.replace("_", " "),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TelemetryCyan,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                Surface(
                    color = Color(0xFF1E293B),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = session.cadence.label,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFFBBF24),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                Text(
                    text = "${session.sampleCount} samples",
                    fontSize = 11.sp,
                    color = Color(0xFFCBD5E1)
                )

                if (cloudStatus != CloudUploadStatus.NONE) {
                    Surface(
                        color = when (cloudStatus) {
                            CloudUploadStatus.UPLOADED -> Color(0xFF065F46)
                            CloudUploadStatus.UPLOADING -> Color(0xFF0369A1)
                            CloudUploadStatus.PENDING_WIFI -> Color(0xFF78350F)
                            CloudUploadStatus.FAILED -> Color(0xFF7F1D1D)
                            else -> Color.Transparent
                        },
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = when (cloudStatus) {
                                CloudUploadStatus.UPLOADED -> "☁️ UPLOADED"
                                CloudUploadStatus.UPLOADING -> "🔄 UPLOADING..."
                                CloudUploadStatus.PENDING_WIFI -> "⏳ WAITING WI-FI"
                                CloudUploadStatus.FAILED -> "⚠️ FAILED"
                                else -> ""
                            },
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = when (cloudStatus) {
                                CloudUploadStatus.UPLOADED -> Color(0xFFA7F3D0)
                                CloudUploadStatus.UPLOADING -> Color(0xFFBAE6FD)
                                CloudUploadStatus.PENDING_WIFI -> Color(0xFFFDE68A)
                                CloudUploadStatus.FAILED -> Color(0xFFFCA5A5)
                                else -> Color.White
                            },
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                }

                session.avgRsrp?.let { rsrp ->
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = "Avg: $rsrp dBm",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = getRsrpColor(rsrp)
                    )
                }
            }

            // Dual-SIM Benchmark Summary (SIM 1 vs SIM 2)
            if (session.isDualSimLogging) {
                val comparison = dualSimBenchmarkComparison(session)
                if (comparison.hasData) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, Sim2Tier2.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "DUAL-SIM BENCHMARK",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Sim2Tier1
                            )
                            when (comparison.winner) {
                                1 -> Text(
                                    text = "WINNER: SIM 1",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = SignalTier1
                                )
                                2 -> Text(
                                    text = "WINNER: SIM 2",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Sim2Tier1
                                )
                                else -> Text(
                                    text = "TIE / N/A",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF94A3B8)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = comparison.sim1Label,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFFCBD5E1)
                                )
                                Text(
                                    text = "RSRP ${comparison.sim1Rsrp?.let { "$it dBm" } ?: "N/A"}",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = getRsrpColor(comparison.sim1Rsrp)
                                )
                                Text(
                                    text = "SINR ${comparison.sim1Sinr?.let { String.format("%.1f dB", it) } ?: "N/A"}",
                                    fontSize = 11.sp,
                                    color = Color(0xFFCBD5E1)
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = comparison.sim2Label,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Sim2Tier1
                                )
                                Text(
                                    text = "RSRP ${comparison.sim2Rsrp?.let { "$it dBm" } ?: "N/A"}",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = sim2RsrpColor(comparison.sim2Rsrp)
                                )
                                Text(
                                    text = "SINR ${comparison.sim2Sinr?.let { String.format("%.1f dB", it) } ?: "N/A"}",
                                    fontSize = 11.sp,
                                    color = Color(0xFFCBD5E1)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Surface(
                                color = Color(0xFF1E293B),
                                shape = RoundedCornerShape(4.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = "RSRP Δ ${comparison.rsrpDeltaDb?.let { "%+${it} dB" } ?: "N/A"} (SIM1-SIM2)",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFFCBD5E1),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                )
                            }
                            Surface(
                                color = Color(0xFF1E293B),
                                shape = RoundedCornerShape(4.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = "SINR Δ ${comparison.sinrDeltaDb?.let { String.format("%+.1f dB", it) } ?: "N/A"} (SIM1-SIM2)",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFFCBD5E1),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onExport,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F766E)),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.weight(1.3f)
                ) {
                    Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("EXPORT...", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = onViewMap,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF60A5FA)),
                    border = BorderStroke(1.dp, Color(0xFF60A5FA).copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.weight(0.9f)
                ) {
                    Icon(Icons.Default.Map, contentDescription = null, modifier = Modifier.size(13.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("MAP", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = onAnalyze,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF818CF8)),
                    border = BorderStroke(1.dp, Color(0xFF818CF8).copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.weight(0.8f)
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(13.dp))
                    Spacer(modifier = Modifier.width(3.dp))
                    Text("AI", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun ExportOptionItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    title: String,
    description: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = Color(0xFF0F172A),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, Color(0xFF334155)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                color = iconTint.copy(alpha = 0.15f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.size(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(description, color = Color(0xFF94A3B8), fontSize = 10.sp, lineHeight = 13.sp)
            }
        }
    }
}
