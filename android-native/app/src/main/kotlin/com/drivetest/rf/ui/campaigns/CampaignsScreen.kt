package com.drivetest.rf.ui.campaigns

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drivetest.rf.data.CampaignRepository
import com.drivetest.rf.data.ExportEngine
import com.drivetest.rf.model.Campaign
import com.drivetest.rf.model.Measurement
import com.drivetest.rf.ui.i18n.AppLanguage
import com.drivetest.rf.ui.i18n.AppStrings.tr
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CampaignsScreen(
    measurements: List<Measurement>,
    onOpenMap: (Long) -> Unit = {},
    onOpenAnalytics: (Long) -> Unit = {},
    language: AppLanguage = AppLanguage.EN
) {
    val context = LocalContext.current
    val campaigns by CampaignRepository.campaigns.collectAsState()
    val measurementsByCampaign by CampaignRepository.measurementsByCampaign.collectAsState()
    val selectedCampaignId by CampaignRepository.selectedCampaignId.collectAsState()
    val canUndoDelete by CampaignRepository.canUndoDelete.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }

    val filteredCampaigns = remember(campaigns, searchQuery) {
        if (searchQuery.isBlank()) campaigns
        else campaigns.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
                    it.preset.labelEn.contains(searchQuery, ignoreCase = true) ||
                    it.tags.any { tag -> tag.contains(searchQuery, ignoreCase = true) }
        }
    }

    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy · HH:mm", Locale.US) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF090F1D))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. Top Toolbar: Search + Import CSV + Reset Demo Data
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
            border = BorderStroke(1.dp, Color(0xFF1E293B)),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${tr(language, "Drive Test Campaigns", "แคมเปญการทดสอบ")} (${filteredCampaigns.size})",
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 15.sp
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            onClick = {
                                val importedSamples = ExportEngine.parseSampleCsvImport()
                                val created = CampaignRepository.importCsvCampaign(
                                    name = "Imported_Field_Log_${System.currentTimeMillis() % 1000}",
                                    measurements = importedSamples
                                )
                                if (created != null) {
                                    Toast.makeText(
                                        context,
                                        "Imported ${created.measurementCount} RF samples into ${created.name}",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            },
                            border = BorderStroke(1.dp, Color(0xFF38BDF8)),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Icon(Icons.Default.FileUpload, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(tr(language, "Import CSV", "นำเข้า CSV"), color = Color(0xFF38BDF8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = {
                                CampaignRepository.resetDemoData()
                                selectedIds = emptySet()
                                Toast.makeText(context, "Demo campaigns restored", Toast.LENGTH_SHORT).show()
                            },
                            border = BorderStroke(1.dp, Color(0xFF334155)),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Icon(Icons.Default.Restore, contentDescription = null, tint = Color(0xFFCBD5E1), modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(tr(language, "Reset Demo", "รีเซ็ตข้อมูล"), color = Color(0xFFCBD5E1), fontSize = 11.sp)
                        }
                    }
                }

                // Search Input + Multi-Select Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = {
                            Text(
                                tr(language, "Search campaign name, preset, or band...", "ค้นหาชื่อแคมเปญ หรือความถี่..."),
                                fontSize = 11.sp,
                                color = Color(0xFF64748B)
                            )
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = null, tint = Color(0xFF94A3B8), modifier = Modifier.size(16.dp))
                        },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                    )

                    if (selectedIds.isNotEmpty()) {
                        Button(
                            onClick = {
                                CampaignRepository.deleteCampaigns(selectedIds)
                                selectedIds = emptySet()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(42.dp)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("${selectedIds.size}", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // 2. Undo Delete Banner
        if (canUndoDelete) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                border = BorderStroke(1.dp, Color(0xFFFACC15)),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = tr(language, "Campaign(s) deleted from workspace.", "ลบแคมเปญออกจากรายการแล้ว"),
                        color = Color.White,
                        fontSize = 12.sp
                    )
                    TextButton(onClick = { CampaignRepository.undoLastDelete() }) {
                        Text(
                            text = tr(language, "UNDO DELETE", "เลิกทำ (กู้คืน)"),
                            color = Color(0xFFFACC15),
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }

        // 3. Campaign Cards List
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(filteredCampaigns, key = { it.id }) { camp ->
                val campMeasurements = measurementsByCampaign[camp.id] ?: emptyList()
                val isSelectedActive = camp.id == selectedCampaignId
                val isChecked = camp.id in selectedIds

                CampaignItemCard(
                    campaign = camp,
                    measurements = campMeasurements,
                    isActiveCampaign = isSelectedActive,
                    isChecked = isChecked,
                    dateFormat = dateFormat,
                    language = language,
                    onToggleCheck = {
                        selectedIds = if (isChecked) selectedIds - camp.id else selectedIds + camp.id
                    },
                    onSelect = { CampaignRepository.selectCampaign(camp.id) },
                    onOpenMap = {
                        CampaignRepository.selectCampaign(camp.id)
                        onOpenMap(camp.id)
                    },
                    onOpenAnalytics = {
                        CampaignRepository.selectCampaign(camp.id)
                        onOpenAnalytics(camp.id)
                    },
                    onExportCsv = {
                        val file = File(context.cacheDir, "${camp.name}.csv")
                        ExportEngine.exportToCsv(campMeasurements, file)
                        Toast.makeText(context, "Exported 24-col CSV: ${file.name}", Toast.LENGTH_LONG).show()
                    },
                    onExportKml = {
                        val file = File(context.cacheDir, "${camp.name}.kml")
                        ExportEngine.exportToKml(camp.name, campMeasurements, file)
                        Toast.makeText(context, "Exported OpenGIS KML 2.2: ${file.name}", Toast.LENGTH_LONG).show()
                    },
                    onExportGeoJson = {
                        val file = File(context.cacheDir, "${camp.name}.geojson")
                        ExportEngine.exportToGeoJson(campMeasurements, file)
                        Toast.makeText(context, "Exported GeoJSON: ${file.name}", Toast.LENGTH_LONG).show()
                    },
                    onExportPdf = {
                        val file = File(context.cacheDir, "${camp.name}_Report.pdf")
                        ExportEngine.exportToPdfReport(context, campMeasurements, file)
                        Toast.makeText(context, "Generated PDF Report: ${file.name}", Toast.LENGTH_LONG).show()
                    },
                    onDelete = {
                        CampaignRepository.deleteCampaigns(setOf(camp.id))
                    }
                )
            }
        }
    }
}

@Composable
private fun CampaignItemCard(
    campaign: Campaign,
    measurements: List<Measurement>,
    isActiveCampaign: Boolean,
    isChecked: Boolean,
    dateFormat: SimpleDateFormat,
    language: AppLanguage,
    onToggleCheck: () -> Unit,
    onSelect: () -> Unit,
    onOpenMap: () -> Unit,
    onOpenAnalytics: () -> Unit,
    onExportCsv: () -> Unit,
    onExportKml: () -> Unit,
    onExportGeoJson: () -> Unit,
    onExportPdf: () -> Unit,
    onDelete: () -> Unit
) {
    val rsrpBadgeColor = when {
        campaign.avgRsrp >= -85 -> Color(0xFF22C55E)
        campaign.avgRsrp >= -102 -> Color(0xFF38BDF8)
        else -> Color(0xFFEF4444)
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isActiveCampaign) Color(0xFF132038) else Color(0xFF0F172A)
        ),
        border = BorderStroke(
            1.dp,
            if (isActiveCampaign) Color(0xFF38BDF8) else Color(0xFF1E293B)
        ),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header Row: Checkbox + Name + Preset Badge + Avg RSRP Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Checkbox(
                        checked = isChecked,
                        onCheckedChange = { onToggleCheck() },
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = campaign.name,
                            color = Color.White,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "${dateFormat.format(Date(campaign.startTimeMs))} · ${if (language == AppLanguage.TH) campaign.preset.labelTh else campaign.preset.labelEn}",
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(rsrpBadgeColor.copy(alpha = 0.16f))
                        .border(1.dp, rsrpBadgeColor.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 9.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "Avg ${campaign.avgRsrp} dBm",
                        color = rsrpBadgeColor,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 11.sp
                    )
                }
            }

            // Middle Row: KPIs + RSRP Sparkline Canvas
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    CampaignKpi(label = tr(language, "SAMPLES", "จุดวัด"), value = "${campaign.measurementCount}")
                    CampaignKpi(label = tr(language, "DISTANCE", "ระยะทาง"), value = String.format(Locale.US, "%.2f km", campaign.totalDistanceKm))
                    CampaignKpi(label = "MIN / MAX", value = "${campaign.minRsrp} / ${campaign.maxRsrp}")
                    CampaignKpi(label = "HANDOVERS", value = "${campaign.handoverCount}")
                }

                RsrpSparkline(
                    measurements = measurements,
                    lineColor = rsrpBadgeColor,
                    modifier = Modifier
                        .width(105.dp)
                        .height(32.dp)
                )
            }

            // Action Buttons Bar: Map, Analytics, CSV, KML, GeoJSON, PDF, Delete
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ActionChipButton(
                    label = tr(language, "Route Map", "แผนที่"),
                    color = Color(0xFF38BDF8),
                    onClick = onOpenMap
                )
                ActionChipButton(
                    label = tr(language, "Analytics", "วิเคราะห์"),
                    color = Color(0xFF22C55E),
                    onClick = onOpenAnalytics
                )
                ActionChipButton(label = "CSV", color = Color(0xFFCBD5E1), onClick = onExportCsv)
                ActionChipButton(label = "KML 2.2", color = Color(0xFFCBD5E1), onClick = onExportKml)
                ActionChipButton(label = "GeoJSON", color = Color(0xFFCBD5E1), onClick = onExportGeoJson)
                ActionChipButton(label = "PDF Report", color = Color(0xFFFACC15), onClick = onExportPdf)
                ActionChipButton(
                    label = tr(language, "Delete", "ลบ"),
                    color = Color(0xFFF87171),
                    onClick = onDelete
                )
            }
        }
    }
}

@Composable
private fun CampaignKpi(label: String, value: String) {
    Column {
        Text(text = label, color = Color(0xFF64748B), fontSize = 9.sp, fontWeight = FontWeight.Bold)
        Text(text = value, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ActionChipButton(label: String, color: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(Color(0xFF152036))
            .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(7.dp))
            .clickable { onClick() }
            .padding(horizontal = 9.dp, vertical = 5.dp)
    ) {
        Text(
            text = label,
            color = color,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun RsrpSparkline(
    measurements: List<Measurement>,
    lineColor: Color,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF152036))
            .padding(4.dp)
    ) {
        if (measurements.size < 2) return@Canvas
        val minR = -125f
        val maxR = -65f
        val path = Path()
        measurements.forEachIndexed { idx, m ->
            val x = (idx.toFloat() / (measurements.size - 1)) * size.width
            val v = m.radio.rsrp.toFloat().coerceIn(minR, maxR)
            val y = size.height * (1f - ((v - minR) / (maxR - minR)))
            if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path = path, color = lineColor, style = Stroke(width = 2.5f, cap = StrokeCap.Round))
    }
}
