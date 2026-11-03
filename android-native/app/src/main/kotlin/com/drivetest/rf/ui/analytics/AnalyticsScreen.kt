package com.drivetest.rf.ui.analytics

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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drivetest.rf.data.CampaignRepository
import com.drivetest.rf.data.RfAnalyticsEngine
import com.drivetest.rf.model.*
import com.drivetest.rf.ui.i18n.AppLanguage
import com.drivetest.rf.ui.i18n.AppStrings.tr
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

enum class AnalyticsSubTab(val labelEn: String, val labelTh: String) {
    SIGNAL("Signal", "กราฟสัญญาณ"),
    CDF("CDF", "การกระจายตัว"),
    NETWORK("Network", "ความเร็ว/Ping"),
    BANDS("Bands", "ความถี่"),
    TRACE("Trace (HO)", "การเปลี่ยนเซลล์"),
    SITES("Sites", "สถานีฐาน"),
    DIAGNOSTIC("Diagnostic", "วินิจฉัยปัญหา"),
    ADVANCED("Advanced", "ไทม์ไลน์เชิงลึก")
}

@Composable
fun AnalyticsScreen(
    measurements: List<Measurement>,
    language: AppLanguage = AppLanguage.EN
) {
    val campaigns by CampaignRepository.campaigns.collectAsState()
    val selectedCampaignId by CampaignRepository.selectedCampaignId.collectAsState()
    val compareCampaignId by CampaignRepository.compareCampaignId.collectAsState()
    val measurementsByCampaign by CampaignRepository.measurementsByCampaign.collectAsState()
    val speedTestsByCampaign by CampaignRepository.speedTestsByCampaign.collectAsState()
    val sessionEvents by CampaignRepository.sessionEvents.collectAsState()

    var activeSubTab by remember { mutableStateOf(AnalyticsSubTab.SIGNAL) }
    var showComparePanel by remember { mutableStateOf(false) }

    val activeMeasurements = remember(selectedCampaignId, measurementsByCampaign, measurements) {
        val list = measurementsByCampaign[selectedCampaignId]
        if (!list.isNullOrEmpty()) list else measurements
    }
    val activeSpeedTests = remember(selectedCampaignId, speedTestsByCampaign) {
        speedTestsByCampaign[selectedCampaignId] ?: emptyList()
    }

    val campaignA = campaigns.find { it.id == selectedCampaignId } ?: campaigns.firstOrNull()
    val campaignB = campaigns.find { it.id == compareCampaignId }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF090F1D))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. Campaign Selector + A vs B Comparison Toggle
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
            border = BorderStroke(1.dp, Color(0xFF1E293B)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = tr(language, "Campaign:", "แคมเปญ:"),
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    campaigns.forEach { camp ->
                        val selected = camp.id == selectedCampaignId
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(7.dp))
                                .background(if (selected) Color(0xFF0284C7) else Color(0xFF152036))
                                .border(1.dp, if (selected) Color(0xFF38BDF8) else Color(0xFF334155), RoundedCornerShape(7.dp))
                                .clickable { CampaignRepository.selectCampaign(camp.id) }
                                .padding(horizontal = 9.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = camp.name,
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(7.dp))
                            .background(if (showComparePanel) Color(0xFF1E3A5F) else Color(0xFF152036))
                            .border(1.dp, Color(0xFF38BDF8), RoundedCornerShape(7.dp))
                            .clickable { showComparePanel = !showComparePanel }
                            .padding(horizontal = 9.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = tr(language, "Compare A vs B", "เปรียบเทียบ A vs B"),
                            color = Color(0xFF38BDF8),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }

                // Optional A vs B Comparison Panel
                if (showComparePanel && campaignA != null && campaignB != null) {
                    val comp = RfAnalyticsEngine.compareCampaigns(
                        campaignA = campaignA,
                        measurementsA = activeMeasurements,
                        campaignB = campaignB,
                        measurementsB = measurementsByCampaign[campaignB.id] ?: emptyList()
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF152036))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "A: ${campaignA.name}  vs  B: ${campaignB.name}",
                                color = Color(0xFF38BDF8),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                campaigns.filter { it.id != campaignA.id }.forEach { other ->
                                    val isB = other.id == campaignB.id
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(5.dp))
                                            .background(if (isB) Color(0xFF38BDF8) else Color(0xFF0F172A))
                                            .clickable { CampaignRepository.setCompareCampaign(other.id) }
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = "B: ${other.name.take(10)}",
                                            color = if (isB) Color(0xFF090F1D) else Color(0xFF94A3B8),
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            CompareKpi(
                                label = "Avg RSRP",
                                valA = String.format(Locale.US, "%.1f dBm", comp.avgRsrpA),
                                valB = String.format(Locale.US, "%.1f dBm", comp.avgRsrpB),
                                delta = String.format(Locale.US, "%+.1f dB", comp.deltaRsrp),
                                positive = comp.deltaRsrp >= 0
                            )
                            CompareKpi(
                                label = "Avg SINR",
                                valA = String.format(Locale.US, "%.1f dB", comp.avgSinrA),
                                valB = String.format(Locale.US, "%.1f dB", comp.avgSinrB),
                                delta = String.format(Locale.US, "%+.1f dB", comp.deltaSinr),
                                positive = comp.deltaSinr >= 0
                            )
                            CompareKpi(
                                label = "Good Coverage",
                                valA = String.format(Locale.US, "%.0f%%", comp.coverageGoodPctA),
                                valB = String.format(Locale.US, "%.0f%%", comp.coverageGoodPctB),
                                delta = String.format(Locale.US, "%+.0f%%", comp.coverageGoodPctA - comp.coverageGoodPctB),
                                positive = comp.coverageGoodPctA >= comp.coverageGoodPctB
                            )
                        }
                    }
                }
            }
        }

        // 2. 8-Sub-Tab Selector Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            AnalyticsSubTab.values().forEach { tab ->
                val selected = activeSubTab == tab
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected) Color(0xFF38BDF8) else Color(0xFF0F172A))
                        .border(1.dp, if (selected) Color(0xFF38BDF8) else Color(0xFF1E293B), RoundedCornerShape(8.dp))
                        .clickable { activeSubTab = tab }
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                ) {
                    Text(
                        text = if (language == AppLanguage.TH) tab.labelTh else tab.labelEn,
                        color = if (selected) Color(0xFF090F1D) else Color(0xFFCBD5E1),
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 11.sp
                    )
                }
            }
        }

        // 3. Active Sub-Tab Content
        Box(modifier = Modifier.fillMaxSize()) {
            when (activeSubTab) {
                AnalyticsSubTab.SIGNAL -> SignalTabContent(activeMeasurements, language)
                AnalyticsSubTab.CDF -> CdfTabContent(activeMeasurements, language)
                AnalyticsSubTab.NETWORK -> NetworkTabContent(activeSpeedTests, sessionEvents, language)
                AnalyticsSubTab.BANDS -> BandsTabContent(activeMeasurements, language)
                AnalyticsSubTab.TRACE -> TraceTabContent(activeMeasurements, language)
                AnalyticsSubTab.SITES -> SitesTabContent(activeMeasurements, language)
                AnalyticsSubTab.DIAGNOSTIC -> DiagnosticTabContent(activeMeasurements, activeSpeedTests, language)
                AnalyticsSubTab.ADVANCED -> AdvancedTabContent(activeMeasurements, language)
            }
        }
    }
}

@Composable
private fun CompareKpi(label: String, valA: String, valB: String, delta: String, positive: Boolean) {
    Column {
        Text(label, color = Color(0xFF94A3B8), fontSize = 9.sp, fontWeight = FontWeight.Bold)
        Text("A: $valA · B: $valB", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        Text(
            text = "Δ $delta",
            color = if (positive) Color(0xFF22C55E) else Color(0xFFEF4444),
            fontSize = 10.sp,
            fontWeight = FontWeight.ExtraBold
        )
    }
}

@Composable
private fun SignalTabContent(measurements: List<Measurement>, language: AppLanguage) {
    val total = measurements.size.coerceAtLeast(1)
    val exc = measurements.count { it.radio.rsrp >= -85 }
    val good = measurements.count { it.radio.rsrp in -95..-86 }
    val fair = measurements.count { it.radio.rsrp in -105..-96 }
    val poor = measurements.count { it.radio.rsrp < -105 }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            AnalyticsCard(title = tr(language, "RSRP / RSRQ / SINR Timeline", "กราฟแนวโน้ม RSRP / RSRQ / SINR ตามเวลา")) {
                MultiMetricTimelineCanvas(
                    measurements = measurements,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    LegendDot("RSRP (dBm)", Color(0xFF38BDF8))
                    LegendDot("SINR (dB)", Color(0xFF22C55E))
                    LegendDot("RSRQ (dB)", Color(0xFFFACC15))
                }
            }
        }

        item {
            AnalyticsCard(title = tr(language, "Coverage Quality Distribution", "สัดส่วนคุณภาพสัญญาณ")) {
                DistributionBar("Excellent (>= -85 dBm)", exc, total, Color(0xFF22C55E))
                Spacer(modifier = Modifier.height(6.dp))
                DistributionBar("Good (-86 to -95 dBm)", good, total, Color(0xFF38BDF8))
                Spacer(modifier = Modifier.height(6.dp))
                DistributionBar("Fair (-96 to -105 dBm)", fair, total, Color(0xFFFACC15))
                Spacer(modifier = Modifier.height(6.dp))
                DistributionBar("Poor (< -105 dBm)", poor, total, Color(0xFFEF4444))
            }
        }
    }
}

@Composable
private fun CdfTabContent(measurements: List<Measurement>, language: AppLanguage) {
    var metricIndex by remember { mutableIntStateOf(0) } // 0 = RSRP, 1 = SINR, 2 = RSRQ
    val values = remember(measurements, metricIndex) {
        when (metricIndex) {
            0 -> measurements.map { it.radio.rsrp.toDouble() }
            1 -> measurements.map { it.radio.sinr.toDouble() }
            else -> measurements.map { it.radio.rsrq.toDouble() }
        }
    }
    val cdf = remember(values) { RfAnalyticsEngine.calculateCdf(values) }
    val unit = if (metricIndex == 0) "dBm" else "dB"

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            AnalyticsCard(title = tr(language, "Empirical Cumulative Distribution (CDF)", "กราฟการกระจายตัวสะสม (CDF)")) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("RSRP (dBm)", "SINR (dB)", "RSRQ (dB)").forEachIndexed { idx, label ->
                        val active = metricIndex == idx
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (active) Color(0xFF38BDF8) else Color(0xFF152036))
                                .clickable { metricIndex = idx }
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = label,
                                color = if (active) Color(0xFF090F1D) else Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    StatBox("P10 (Worst 10%)", String.format(Locale.US, "%.1f %s", cdf.p10, unit), Color(0xFFEF4444))
                    StatBox("P50 (Median)", String.format(Locale.US, "%.1f %s", cdf.p50, unit), Color(0xFF38BDF8))
                    StatBox("P90 (Top 10%)", String.format(Locale.US, "%.1f %s", cdf.p90, unit), Color(0xFF22C55E))
                }

                Spacer(modifier = Modifier.height(12.dp))

                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(155.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF152036))
                        .padding(12.dp)
                ) {
                    if (cdf.points.size < 2) return@Canvas
                    val minV = cdf.min.toFloat()
                    val maxV = (cdf.max.toFloat() - minV).coerceAtLeast(1f)
                    val path = Path()
                    cdf.points.forEachIndexed { idx, pt ->
                        val x = ((pt.value.toFloat() - minV) / maxV) * size.width
                        val y = size.height * (1f - (pt.percentile.toFloat() / 100f))
                        if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    // P50 horizontal guide
                    drawLine(Color(0xFF334155), Offset(0f, size.height * 0.5f), Offset(size.width, size.height * 0.5f), 1.5f)
                    drawPath(path, Color(0xFF38BDF8), style = Stroke(width = 4f, cap = StrokeCap.Round))
                }
            }
        }
    }
}

@Composable
private fun NetworkTabContent(
    speedTests: List<SpeedTestResult>,
    sessionEvents: List<SessionEvent>,
    language: AppLanguage
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            AnalyticsCard(title = "${tr(language, "Active Speed Test History", "ประวัติการทดสอบความเร็ว")} (${speedTests.size})") {
                if (speedTests.isEmpty()) {
                    Text(
                        text = tr(language, "No speed tests recorded in this campaign.", "ยังไม่มีผลการทดสอบความเร็วในแคมเปญนี้"),
                        color = Color(0xFF64748B),
                        fontSize = 12.sp
                    )
                } else {
                    speedTests.forEach { st ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF152036))
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "DL ${st.downloadMbps} Mbps · UL ${st.uploadMbps} Mbps",
                                    color = Color(0xFF22C55E),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                                Text(
                                    text = st.serverLocation,
                                    color = Color(0xFF94A3B8),
                                    fontSize = 10.sp
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "Ping ${st.pingMs} ms (Jitter ${st.jitterMs} ms)",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Loaded Latency ${st.loadedLatencyMs} ms",
                                    color = Color(0xFFFACC15),
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            AnalyticsCard(title = "${tr(language, "Session Events & Light Probes", "บันทึกเหตุการณ์และผลตรวจสอบ Ping")} (${sessionEvents.size})") {
                sessionEvents.take(10).forEach { ev ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(ev.type, color = Color(0xFF38BDF8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text(ev.detail, color = Color(0xFFCBD5E1), fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun BandsTabContent(measurements: List<Measurement>, language: AppLanguage) {
    val bands = remember(measurements) { RfAnalyticsEngine.calculateBandUsage(measurements) }
    val nrCount = measurements.count { it.radio.technology == CellTechnology.NR_5G }
    val total = measurements.size.coerceAtLeast(1)
    val nrPct = (nrCount * 100) / total

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            AnalyticsCard(title = tr(language, "5G NR vs 4G LTE RAT Distribution", "สัดส่วนการใช้งาน 5G NR เทียบกับ 4G LTE")) {
                DistributionBar("5G NR SA/NSA", nrCount, total, Color(0xFF38BDF8))
                Spacer(modifier = Modifier.height(6.dp))
                DistributionBar("4G LTE", total - nrCount, total, Color(0xFF22C55E))
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "5G NR Dwell Ratio: $nrPct% (${bands.size} unique frequency band(s))",
                    color = Color(0xFF94A3B8),
                    fontSize = 11.sp
                )
            }
        }

        items(bands) { b ->
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                border = BorderStroke(1.dp, Color(0xFF1E293B)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(b.band, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(
                            "${b.technology.displayName} · ${b.sampleCount} samples (${String.format(Locale.US, "%.1f%%", b.percentage)})",
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Avg RSRP ${b.avgRsrp} dBm", color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text("Avg SINR ${b.avgSinr} dB", color = Color(0xFF22C55E), fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun TraceTabContent(measurements: List<Measurement>, language: AppLanguage) {
    val handovers = remember(measurements) { RfAnalyticsEngine.detectHandovers(measurements) }
    val missed = remember(measurements) { RfAnalyticsEngine.detectMissedHandovers(measurements) }
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            AnalyticsCard(title = "${tr(language, "Handover Events", "เหตุการณ์เปลี่ยนเซลล์ (Handover)")} (${handovers.size})") {
                if (handovers.isEmpty()) {
                    Text(tr(language, "No cell handovers occurred during this route.", "ไม่พบการเปลี่ยนเซลล์ในเส้นทางนี้"), color = Color(0xFF64748B), fontSize = 12.sp)
                } else {
                    handovers.forEach { ho ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (ho.isPingPong) Color(0xFF3B1219) else Color(0xFF152036))
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "PCI ${ho.fromPci} → PCI ${ho.toPci} ${if (ho.isPingPong) "(PING-PONG)" else ""}",
                                    color = if (ho.isPingPong) Color(0xFFF87171) else Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                                Text(
                                    text = "${timeFormat.format(Date(ho.timestampMs))} · ${ho.fromBand} → ${ho.toBand}",
                                    color = Color(0xFF94A3B8),
                                    fontSize = 10.sp
                                )
                            }
                            Text(
                                text = "${ho.rsrpBefore} → ${ho.rsrpAfter} dBm (${if (ho.deltaDb >= 0) "+${ho.deltaDb}" else "${ho.deltaDb}"} dB)",
                                color = if (ho.deltaDb >= 0) Color(0xFF22C55E) else Color(0xFFFACC15),
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }

        item {
            AnalyticsCard(title = "${tr(language, "Missed Handover Opportunities (+6 dB Neighbor)", "จุดที่ควรเปลี่ยนเซลล์แต่ไม่เปลี่ยน")} (${missed.size})") {
                missed.take(10).forEach { mh ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Serving PCI ${mh.servingPci} (${mh.servingRsrp} dBm) vs Neighbor PCI ${mh.bestNeighborPci} (${mh.bestNeighborRsrp} dBm)",
                            color = Color(0xFFCBD5E1),
                            fontSize = 11.sp
                        )
                        Text(
                            text = "+${mh.deltaDb} dB",
                            color = Color(0xFFF97316),
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SitesTabContent(measurements: List<Measurement>, language: AppLanguage) {
    val sites = remember(measurements) { RfAnalyticsEngine.extractCellSites(measurements) }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(
                text = "${tr(language, "Observed Serving Cell Sites", "สถานีฐานที่ตรวจพบทั้งหมด")} (${sites.size})",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            )
        }
        items(sites) { site ->
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
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
                        Text(
                            text = "PCI ${site.pci} · Cell ID ${site.cellId}",
                            color = Color(0xFF38BDF8),
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 13.sp
                        )
                        Text(
                            text = "${site.operatorName} · ${site.band} · TAC ${site.tac} · ARFCN ${site.arfcn}",
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "Avg ${site.avgRsrp} dBm (Max ${site.maxRsrp})",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                        Text(
                            text = "${site.sampleCount} samples · SINR ${site.avgSinr} dB",
                            color = Color(0xFF22C55E),
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticTabContent(
    measurements: List<Measurement>,
    speedTests: List<SpeedTestResult>,
    language: AppLanguage
) {
    val report = remember(measurements, speedTests) {
        RfAnalyticsEngine.calculateRouteDiagnostic(measurements, speedTests)
    }

    val scoreColor = when {
        report.overallScore >= 85 -> Color(0xFF22C55E)
        report.overallScore >= 70 -> Color(0xFF38BDF8)
        report.overallScore >= 55 -> Color(0xFFFACC15)
        else -> Color(0xFFEF4444)
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                border = BorderStroke(1.dp, scoreColor),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = tr(language, "AUTOMATED RF ROUTE HEALTH SCORE", "คะแนนสุขภาพโครงข่ายอัตโนมัติ"),
                            color = Color(0xFF94A3B8),
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                        Text(
                            text = "Grade: ${report.grade} (${report.issues.size} Findings)",
                            color = Color.White,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 16.sp
                        )
                    }
                    Text(
                        text = "${report.overallScore} / 100",
                        color = scoreColor,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 30.sp
                    )
                }
            }
        }

        items(report.issues) { issue ->
            val sevColor = when (issue.severity) {
                "HIGH" -> Color(0xFFEF4444)
                "MEDIUM" -> Color(0xFFFACC15)
                else -> Color(0xFF38BDF8)
            }
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                border = BorderStroke(1.dp, sevColor.copy(alpha = 0.6f)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (language == AppLanguage.TH) issue.titleTh else issue.titleEn,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        Text(
                            text = issue.severity,
                            color = sevColor,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 11.sp
                        )
                    }
                    Text(
                        text = if (language == AppLanguage.TH) issue.summaryTh else issue.summaryEn,
                        color = Color(0xFFCBD5E1),
                        fontSize = 12.sp
                    )
                    Text(
                        text = "Recommendation: ${if (language == AppLanguage.TH) issue.recommendationTh else issue.recommendationEn}",
                        color = Color(0xFF38BDF8),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun AdvancedTabContent(measurements: List<Measurement>, language: AppLanguage) {
    var cursorIdx by remember { mutableIntStateOf(0) }
    var anomaliesOnly by remember { mutableStateOf(false) }

    val filtered = remember(measurements, anomaliesOnly) {
        if (!anomaliesOnly) measurements
        else measurements.filter { it.radio.rsrp <= -98 || it.radio.sinr < 5 }
    }
    val safeIdx = cursorIdx.coerceIn(0, (filtered.size - 1).coerceAtLeast(0))
    val current = filtered.getOrNull(safeIdx)

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            AnalyticsCard(title = tr(language, "Synchronized RF Timeline Scrubber", "ตัวเลื่อนตรวจสอบข้อมูลรายจุดวัดเชิงลึก")) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Sample #${safeIdx + 1} / ${filtered.size}",
                        color = Color(0xFF38BDF8),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(tr(language, "Anomalies Only", "เฉพาะจุดผิดปกติ"), color = Color(0xFFCBD5E1), fontSize = 11.sp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Switch(checked = anomaliesOnly, onCheckedChange = {
                            anomaliesOnly = it
                            cursorIdx = 0
                        })
                    }
                }

                if (filtered.size > 1) {
                    Slider(
                        value = safeIdx.toFloat(),
                        onValueChange = { cursorIdx = it.roundToInt() },
                        valueRange = 0f..(filtered.size - 1).toFloat()
                    )
                }

                current?.let { m ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF152036))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "${m.radio.technology.displayName} · ${m.radio.operatorName} · PCI ${m.radio.pci} · Cell ${m.radio.cellId}",
                            color = Color.White,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 13.sp
                        )
                        Text(
                            text = "RSRP ${m.radio.rsrp} dBm  |  RSRQ ${m.radio.rsrq} dB  |  SINR ${m.radio.sinr} dB  |  CQI ${m.radio.cqi}  |  TA ${m.radio.timingAdvance}",
                            color = Color(0xFF38BDF8),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = String.format(
                                Locale.US,
                                "GPS: %.6f, %.6f  |  Speed: %.1f km/h  |  Neighbors: %d",
                                m.gps.latitude,
                                m.gps.longitude,
                                m.gps.speedKmh,
                                m.radio.neighborCells.size
                            ),
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AnalyticsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
        border = BorderStroke(1.dp, Color(0xFF1E293B)),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(title, color = Color(0xFF38BDF8), fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
            Spacer(modifier = Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun MultiMetricTimelineCanvas(measurements: List<Measurement>, modifier: Modifier = Modifier) {
    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF152036))
            .padding(10.dp)
    ) {
        if (measurements.size < 2) return@Canvas
        val n = measurements.size
        val rsrpPath = Path()
        val sinrPath = Path()

        measurements.forEachIndexed { idx, m ->
            val x = (idx.toFloat() / (n - 1)) * size.width
            val rsrpNorm = ((m.radio.rsrp.toFloat() - (-130f)) / 70f).coerceIn(0f, 1f)
            val sinrNorm = ((m.radio.sinr.toFloat() - (-10f)) / 40f).coerceIn(0f, 1f)
            val yRsrp = size.height * (1f - rsrpNorm)
            val ySinr = size.height * (1f - sinrNorm)
            if (idx == 0) {
                rsrpPath.moveTo(x, yRsrp)
                sinrPath.moveTo(x, ySinr)
            } else {
                rsrpPath.lineTo(x, yRsrp)
                sinrPath.lineTo(x, ySinr)
            }
        }

        drawPath(rsrpPath, Color(0xFF38BDF8), style = Stroke(width = 3.5f, cap = StrokeCap.Round))
        drawPath(sinrPath, Color(0xFF22C55E), style = Stroke(width = 2.5f, cap = StrokeCap.Round))
    }
}

@Composable
private fun LegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(8.dp).background(color, RoundedCornerShape(4.dp)))
        Spacer(modifier = Modifier.width(5.dp))
        Text(label, color = Color(0xFFCBD5E1), fontSize = 10.sp)
    }
}

@Composable
private fun StatBox(label: String, value: String, color: Color) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF152036))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(label, color = Color(0xFF94A3B8), fontSize = 9.sp)
        Text(value, color = color, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
    }
}

@Composable
fun DistributionBar(label: String, count: Int, total: Int, color: Color) {
    val pct = if (total > 0) (count * 100) / total else 0
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = label, fontSize = 11.sp, color = Color(0xFFCBD5E1))
            Text(text = "$pct% ($count)", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(7.dp)
                .clip(RoundedCornerShape(4.dp))
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(Color(0xFF1E293B))
                drawRect(color, size = Size(size.width * (pct / 100f), size.height))
            }
        }
    }
}
