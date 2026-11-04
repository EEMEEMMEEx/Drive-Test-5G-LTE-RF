package com.drivetest.lte5g.model

enum class RatType {
    LTE,
    NR_NSA,
    NR_SA,
    UNKNOWN
}

enum class RatLockMode {
    AUTO,
    LTE_ONLY,
    NR_ONLY
}

enum class MeasurementCadence(
    val intervalMs: Long,
    val label: String,
    val frequencyLabel: String,
    val description: String
) {
    ECO(5000L, "Eco (5 s)", "0.2 Hz", "ประหยัดแบตเตอรี่ เหมาะกับการขับทางไกล / High-speed highway"),
    STANDARD(2000L, "Standard (2 s)", "0.5 Hz", "สมดุลพลังงานและพิกัด สำหรับพื้นที่เมืองทั่วไป"),
    PRECISE(1000L, "Precise (1 s)", "1.0 Hz", "ความละเอียดสูงสุด สำหรับ Fast Fading & Handover")
}

enum class LiveAlertStyle(
    val label: String,
    val shortDesc: String,
    val fullDesc: String
) {
    CAPSULE(
        label = "Dynamic Island / Fluid Cloud",
        shortDesc = "เปิดแคปซูลรอบกล้องหน้า (แนะนำ)",
        fullDesc = "แสดงแคปซูลสดรอบรูกล้องหน้าแบบเรียลไทม์ พร้อมข้อมูลระดับสัญญาณและปุ่มควบคุม Drive Test"
    ),
    COMPACT(
        label = "Standard Notification",
        shortDesc = "การแจ้งเตือนปกติ",
        fullDesc = "แสดงการแจ้งเตือนมาตรฐานในแถบดึงลง โดยไม่ขึ้นแคปซูลรอบกล้องหน้า"
    )
}

data class SimSlotInfo(
    val slotIndex: Int,
    val subscriptionId: Int,
    val displayName: String,
    val carrierName: String,
    val isDefault: Boolean = false
)

data class LteMetrics(
    val pci: Int?,
    val eci: Long?,
    val tac: Int?,
    val earfcn: Int?,
    val band: Int?,
    val rsrp: Int?,
    val rsrq: Int?,
    val rssnr: Double?,
    val cqi: Int?
)

data class NrMetrics(
    val pci: Int?,
    val nci: Long?,
    val tac: Int?,
    val nrArfcn: Int?,
    val band: Int?,
    val ssRsrp: Int?,
    val ssRsrq: Int?,
    val ssSinr: Double?,
    val csiRsrp: Int?,
    val csiRsrq: Int?,
    val csiSinr: Double?,
    val isNsa: Boolean
)

data class NeighborCell(
    val rat: RatType,
    val pci: Int?,
    val arfcn: Int?,
    val rsrp: Int?,
    val rsrq: Int?,
    val sinr: Double?
)

data class CellMetrics(
    val ratType: RatType = RatType.UNKNOWN,
    val plmn: String? = null,
    val operatorAlpha: String? = null,
    val lte: LteMetrics? = null,
    val nr: NrMetrics? = null,
    val neighbors: List<NeighborCell> = emptyList(),
    val selectedSimSlot: Int = 0,
    val simDisplayName: String? = null
)

data class DriveTestSample(
    val id: Long = 0,
    val sessionId: Long = 1,
    val timestamp: Long = System.currentTimeMillis(),
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val speedKmh: Float,
    val bearing: Float,
    val accuracyMeters: Float,
    val metrics: CellMetrics,
    val ratLockMode: RatLockMode = RatLockMode.AUTO,
    val cadence: MeasurementCadence = MeasurementCadence.PRECISE,
    val simSlot: Int = 0,
    val simDisplayName: String? = null
)

data class DriveTestSession(
    val id: Long = System.currentTimeMillis(),
    val sessionNumber: Int = 1,
    val name: String = "Session #1",
    val startTime: Long = System.currentTimeMillis(),
    val endTime: Long? = null,
    val ratLockMode: RatLockMode = RatLockMode.AUTO,
    val cadence: MeasurementCadence = MeasurementCadence.PRECISE,
    val sampleCount: Int = 0,
    val avgRsrp: Int? = null,
    val avgRsrq: Int? = null,
    val avgSinr: Double? = null,
    val isRecording: Boolean = false,
    val samples: List<DriveTestSample> = emptyList(),
    val simSlot: Int = 0,
    val simDisplayName: String? = null
)
