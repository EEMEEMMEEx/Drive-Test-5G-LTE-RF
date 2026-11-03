package com.drivetest.rf.model

enum class CellTechnology(val displayName: String) {
    NR_5G("5G NR"),
    LTE_4G("LTE"),
    WCDMA_3G("3G"),
    GSM_2G("2G"),
    UNKNOWN("Unknown")
}

enum class CampaignPreset(
    val labelEn: String,
    val labelTh: String,
    val descEn: String,
    val descTh: String,
    val defaultWeakThresholdDbm: Int
) {
    URBAN_ROUTE(
        "Urban Route",
        "เส้นทางในเมือง",
        "Dense cells · Handover & interference focus",
        "เซลล์หนาแน่น · เน้นตรวจ Handover และสัญญาณรบกวน",
        -108
    ),
    HIGHWAY(
        "Highway / Mobility",
        "ทางหลวง / ความเร็วสูง",
        "High-speed Doppler · Fast handover tracking",
        "ความเร็วสูง · ติดตาม Handover ต่อเนื่อง",
        -112
    ),
    SITE_AUDIT(
        "Single Site Audit",
        "ตรวจสอบเสาสัญญาณ",
        "Sector verification · PCI / Azimuth / CA check",
        "ตรวจสอบรายเซกเตอร์ · PCI / Azimuth / CA",
        -105
    ),
    INDOOR(
        "Indoor Walk Test",
        "เดินทดสอบในอาคาร",
        "Building penetration · Deep indoor coverage",
        "ความเข้มสัญญาณภายในอาคารและจุดอับสัญญาณ",
        -115
    )
}

enum class MeasurementCadence(val intervalMs: Long, val labelEn: String, val labelTh: String) {
    ECO(5000L, "Eco · 5 s", "ประหยัด · 5 วิ"),
    STANDARD(2000L, "Standard · 2 s", "มาตรฐาน · 2 วิ"),
    PRECISE(1000L, "Precise · 1 s", "ละเอียด · 1 วิ")
}

enum class RatSelectionMode(val labelEn: String, val labelTh: String) {
    AUTO("Auto (5G/4G)", "อัตโนมัติ (5G/4G)"),
    NR_5G_ONLY("5G NR Only", "เฉพาะ 5G NR"),
    LTE_4G_ONLY("4G LTE Only", "เฉพาะ 4G LTE")
}

enum class SignalPalette(val labelEn: String, val labelTh: String) {
    CLASSIC("Classic RF", "มาตรฐาน RF"),
    THERMIQUE("Thermique", "ความร้อน"),
    VIRIDIS("Viridis", "ไวริดิส"),
    OCEAN("Ocean", "โอเชียน")
}

enum class MapColorMode(val labelEn: String, val labelTh: String) {
    BY_RSRP("RSRP", "RSRP"),
    BY_RSRQ("RSRQ", "RSRQ"),
    BY_SINR("SINR", "SINR"),
    BY_TECH("Tech", "เทคโนโลยี"),
    BY_SPEED("Speed", "ความเร็ว")
}

data class CaComponent(
    val band: Int,
    val isNr: Boolean,
    val dlFrequencyKhz: Int,
    val dlBandwidthKhz: Int
)

data class NeighborCell(
    val technology: CellTechnology,
    val pci: Int,
    val arfcn: Int,
    val rsrp: Int,
    val rsrq: Int,
    val cellId: Long = 0L
)

data class RadioInfo(
    val technology: CellTechnology = CellTechnology.NR_5G,
    val operatorName: String = "STANDBY",
    val mcc: String = "520",
    val mnc: String = "03",
    val simSlot: Int = 0,
    val cellId: Long = 0L,
    val tac: Int = 0,
    val pci: Int = 0,
    val band: String = "n78 (3500 MHz)",
    val arfcn: Int = 0,
    val dlFrequencyKhz: Int = 3500000,
    val dlBandwidthKhz: Int = 100000,
    val rsrp: Int = -140,
    val rsrq: Int = -20,
    val sinr: Int = -20,
    val rssi: Int = -120,
    val csiRsrp: Int = -140,
    val csiRsrq: Int = -20,
    val csiSinr: Int = -20,
    val cqi: Int = 0,
    val timingAdvance: Int = 0,
    val is5gSa: Boolean = true,
    val isConnected: Boolean = false,
    val carrierAggregation: List<CaComponent> = emptyList(),
    val neighborCells: List<NeighborCell> = emptyList()
)

data class GpsInfo(
    val latitude: Double = 13.756300,
    val longitude: Double = 100.501800,
    val altitude: Double = 14.5,
    val speedKmh: Float = 0f,
    val bearing: Float = 0f,
    val accuracy: Float = 3.8f,
    val satellites: Int = 12,
    val timestampMs: Long = System.currentTimeMillis(),
    val isValid: Boolean = true
)

data class Measurement(
    val id: Long = System.currentTimeMillis(),
    val campaignId: Long = 0L,
    val timestampMs: Long = System.currentTimeMillis(),
    val radio: RadioInfo = RadioInfo(),
    val gps: GpsInfo = GpsInfo()
)

data class SimSlotInfo(
    val slotIndex: Int,
    val subscriptionId: Int,
    val displayName: String,
    val carrierName: String,
    val countryIso: String,
    val isActive: Boolean,
    val mcc: String = "520",
    val mnc: String = "03"
)

data class Campaign(
    val id: Long,
    val name: String,
    val preset: CampaignPreset = CampaignPreset.URBAN_ROUTE,
    val startTimeMs: Long,
    val endTimeMs: Long? = null,
    val isCompleted: Boolean = true,
    val measurementCount: Int = 0,
    val totalDistanceKm: Double = 0.0,
    val avgRsrp: Int = -90,
    val minRsrp: Int = -115,
    val maxRsrp: Int = -70,
    val handoverCount: Int = 0,
    val tags: List<String> = emptyList()
)

data class CampaignFinishedSummary(
    val campaignId: Long,
    val name: String,
    val preset: CampaignPreset,
    val measurementCount: Int,
    val distanceKm: Double,
    val durationSec: Long,
    val avgRsrp: Int,
    val minRsrp: Int,
    val maxRsrp: Int,
    val handoverCount: Int
)

data class SpeedTestResult(
    val id: Long = System.currentTimeMillis(),
    val campaignId: Long = 0L,
    val timestampMs: Long = System.currentTimeMillis(),
    val downloadMbps: Double = 0.0,
    val uploadMbps: Double = 0.0,
    val pingMs: Double = 0.0,
    val jitterMs: Double = 0.0,
    val loadedLatencyMs: Double = 0.0,
    val serverLocation: String = "Bangkok Edge"
)

data class SpeedTestProgress(
    val isRunning: Boolean = false,
    val phase: String = "IDLE", // IDLE, PING, DOWNLOAD, UPLOAD, COMPLETE
    val progressPercent: Int = 0
)

data class LightNetworkProbe(
    val timestampMs: Long = System.currentTimeMillis(),
    val pingMs: Int = 21,
    val jitterMs: Double = 2.4,
    val success: Boolean = true
)

data class SessionEvent(
    val id: Long = System.currentTimeMillis(),
    val campaignId: Long,
    val timestampMs: Long,
    val type: String, // PAUSE, RESUME, LIGHT_PROBE, HANDOVER, SPEED_TEST
    val detail: String
)

data class HandoverEvent(
    val id: Long,
    val timestampMs: Long,
    val latitude: Double,
    val longitude: Double,
    val fromPci: Int,
    val toPci: Int,
    val fromCellId: Long,
    val toCellId: Long,
    val fromTech: CellTechnology,
    val toTech: CellTechnology,
    val fromBand: String,
    val toBand: String,
    val rsrpBefore: Int,
    val rsrpAfter: Int,
    val deltaDb: Int,
    val isPingPong: Boolean = false
)

data class MissedHandoverEvent(
    val id: Long,
    val timestampMs: Long,
    val latitude: Double,
    val longitude: Double,
    val servingPci: Int,
    val servingRsrp: Int,
    val bestNeighborPci: Int,
    val bestNeighborRsrp: Int,
    val deltaDb: Int
)

data class CellSiteSummary(
    val cellId: Long,
    val pci: Int,
    val tac: Int,
    val technology: CellTechnology,
    val band: String,
    val arfcn: Int,
    val operatorName: String,
    val sampleCount: Int,
    val avgRsrp: Int,
    val maxRsrp: Int,
    val avgSinr: Int,
    val centroidLat: Double,
    val centroidLon: Double,
    val firstSeenMs: Long,
    val lastSeenMs: Long
)

data class BandUsageStat(
    val band: String,
    val technology: CellTechnology,
    val sampleCount: Int,
    val percentage: Double,
    val avgRsrp: Int,
    val avgSinr: Int
)

data class CdfPoint(
    val value: Double,
    val percentile: Double // 0.0 to 100.0
)

data class MetricCdfSummary(
    val points: List<CdfPoint>,
    val p10: Double,
    val p50: Double,
    val p90: Double,
    val min: Double,
    val max: Double,
    val mean: Double
)

enum class DiagnosticIssueType {
    WEAK_SIGNAL,
    POOR_QUALITY,
    FREQUENT_HANDOVERS,
    LOW_THROUGHPUT,
    HIGH_LOADED_LATENCY
}

data class DiagnosticIssue(
    val type: DiagnosticIssueType,
    val severity: String, // HIGH, MEDIUM, LOW
    val titleEn: String,
    val titleTh: String,
    val summaryEn: String,
    val summaryTh: String,
    val recommendationEn: String,
    val recommendationTh: String,
    val affectedSamples: Int
)

data class RouteDiagnosticReport(
    val overallScore: Int, // 0..100
    val grade: String, // EXCELLENT, GOOD, FAIR, POOR
    val issues: List<DiagnosticIssue>
)

data class CampaignComparisonResult(
    val campaignA: Campaign,
    val campaignB: Campaign,
    val avgRsrpA: Double,
    val avgRsrpB: Double,
    val deltaRsrp: Double,
    val avgSinrA: Double,
    val avgSinrB: Double,
    val deltaSinr: Double,
    val avgSpeedA: Double,
    val avgSpeedB: Double,
    val coverageGoodPctA: Double,
    val coverageGoodPctB: Double,
    val handoverRatePerKmA: Double,
    val handoverRatePerKmB: Double
)
