package com.drivetest.rf.telephony

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.drivetest.rf.data.CampaignRepository
import com.drivetest.rf.location.GpsTracker
import com.drivetest.rf.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

class DriveTestForegroundService : Service() {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    private var loggingJob: Job? = null

    private lateinit var telephonyTracker: TelephonyTracker
    private lateinit var gpsTracker: GpsTracker
    private var wakeLock: PowerManager.WakeLock? = null
    private var simTick: Int = 0

    inner class LocalBinder : Binder() {
        fun getService(): DriveTestForegroundService = this@DriveTestForegroundService
    }

    override fun onCreate() {
        super.onCreate()
        telephonyTracker = TelephonyTracker(this)
        gpsTracker = GpsTracker(this)

        createNotificationChannel()

        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DriveTestNative::WakeLock")?.apply {
            setReferenceCounted(false)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification("Starting 5G/LTE Drive Test...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        wakeLock?.acquire(12 * 60 * 60 * 1000L /* 12 hours */)
        startTelemetryCollection()

        return START_STICKY
    }

    private fun startTelemetryCollection() {
        telephonyTracker.startListening()
        gpsTracker.startTracking()

        loggingJob?.cancel()
        loggingJob = serviceScope.launch {
            while (isActive) {
                if (!_isPaused.value) {
                    telephonyTracker.pollCellInfo()
                    val rawRadio = telephonyTracker.radioInfo.value
                    val rawGps = gpsTracker.gpsInfo.value

                    val effectiveRadio = applyRatModeAndFallback(rawRadio, simTick)
                    val effectiveGps = if (_useSimulatedGps.value) {
                        generateSimulatedGps(simTick)
                    } else {
                        rawGps
                    }
                    simTick++

                    val measurement = Measurement(
                        id = System.currentTimeMillis(),
                        campaignId = CampaignRepository.activeCampaignId.value ?: activeCampaignId,
                        timestampMs = System.currentTimeMillis(),
                        radio = effectiveRadio,
                        gps = effectiveGps
                    )

                    _liveMeasurement.value = measurement
                    _measurementHistory.value = (_measurementHistory.value + measurement).takeLast(500)
                    CampaignRepository.appendMeasurement(measurement)

                    if (_lightProbeEnabled.value) {
                        val probe = CampaignRepository.triggerLightProbe(effectiveRadio)
                        CampaignRepository.logSessionEvent("LIGHT_PROBE", "Ping ${probe.pingMs} ms · Jitter ${probe.jitterMs} ms")
                    }

                    updateNotification(
                        "${effectiveRadio.technology.displayName} RSRP: ${effectiveRadio.rsrp} dBm | SINR: ${effectiveRadio.sinr} dB | PCI: ${effectiveRadio.pci}"
                    )
                }

                delay(_cadence.value.intervalMs)
            }
        }
    }

    private fun applyRatModeAndFallback(raw: RadioInfo, tick: Int): RadioInfo {
        val base = if (!raw.isConnected && _useSimulatedGps.value) {
            // When simulated GPS is active and modem is standby, generate realistic live RF telemetry
            val wave = (sin(tick * 0.35) * 11.0).roundToInt()
            val rsrp = (-84 + wave).coerceIn(-118, -68)
            val rsrq = (-10 + wave / 3).coerceIn(-18, -6)
            val sinr = (18 + wave).coerceIn(-2, 28)
            val sector = (tick / 10) % 3
            val pciList = listOf(104, 218, 312)
            raw.copy(
                technology = CellTechnology.NR_5G,
                operatorName = "AIS 5G SA (SIMULATED)",
                mcc = "520",
                mnc = "03",
                cellId = 52003101L + sector,
                tac = 10240 + sector,
                pci = pciList[sector],
                band = "n78 (3500 MHz)",
                arfcn = 633334,
                dlFrequencyKhz = 3500000,
                dlBandwidthKhz = 100000,
                rsrp = rsrp,
                rsrq = rsrq,
                sinr = sinr,
                rssi = rsrp + 22,
                csiRsrp = rsrp - 1,
                cqi = ((sinr + 6) / 2).coerceIn(4, 15),
                timingAdvance = 3 + (tick % 4),
                is5gSa = true,
                isConnected = true,
                carrierAggregation = listOf(
                    CaComponent(78, true, 3500000, 100000),
                    CaComponent(41, true, 2600000, 60000)
                ),
                neighborCells = listOf(
                    NeighborCell(CellTechnology.NR_5G, pciList[(sector + 1) % 3], 633334, rsrp - 5, rsrq - 1, 52003105L),
                    NeighborCell(CellTechnology.LTE_4G, 409, 1650, rsrp - 8, rsrq - 2, 52003201L)
                )
            )
        } else {
            raw
        }

        return when (_ratMode.value) {
            RatSelectionMode.AUTO -> base
            RatSelectionMode.NR_5G_ONLY -> base.copy(
                technology = CellTechnology.NR_5G,
                band = if (base.band.startsWith("n")) base.band else "n78 (3500 MHz)",
                is5gSa = true
            )
            RatSelectionMode.LTE_4G_ONLY -> base.copy(
                technology = CellTechnology.LTE_4G,
                band = if (base.band.startsWith("B")) base.band else "B3 (1800 MHz)",
                is5gSa = false
            )
        }
    }

    private fun generateSimulatedGps(tick: Int): GpsInfo {
        val lat = 13.756300 + (tick * 0.00048) + sin(tick * 0.25) * 0.00018
        val lon = 100.501800 + (tick * 0.00055) + cos(tick * 0.25) * 0.00015
        val speed = (38.0 + sin(tick * 0.4) * 14.0).toFloat().coerceAtLeast(5f)
        val bearing = ((45f + tick * 4.5f) % 360f)
        return GpsInfo(
            latitude = lat,
            longitude = lon,
            altitude = 14.5 + sin(tick * 0.2) * 1.8,
            speedKmh = speed,
            bearing = bearing,
            accuracy = 3.6f,
            satellites = 15,
            timestampMs = System.currentTimeMillis(),
            isValid = true
        )
    }

    override fun onDestroy() {
        loggingJob?.cancel()
        telephonyTracker.stopListening()
        gpsTracker.stopTracking()
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "5G/LTE Drive Test Logging",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Continuous background drive test recording"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(content: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("RF Field Suite v1.9.3 Active")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(content: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, createNotification(content))
    }

    companion object {
        private const val CHANNEL_ID = "drive_test_native_channel"
        private const val NOTIFICATION_ID = 7001

        var activeCampaignId: Long = 1001L

        private val _liveMeasurement = MutableStateFlow(
            CampaignRepository.measurementsByCampaign.value[1001L]?.lastOrNull() ?: Measurement()
        )
        val liveMeasurement: StateFlow<Measurement> = _liveMeasurement.asStateFlow()

        private val _measurementHistory = MutableStateFlow<List<Measurement>>(
            CampaignRepository.measurementsByCampaign.value[1001L] ?: emptyList()
        )
        val measurementHistory: StateFlow<List<Measurement>> = _measurementHistory.asStateFlow()

        private val _cadence = MutableStateFlow(MeasurementCadence.STANDARD)
        val cadence: StateFlow<MeasurementCadence> = _cadence.asStateFlow()

        private val _ratMode = MutableStateFlow(RatSelectionMode.AUTO)
        val ratMode: StateFlow<RatSelectionMode> = _ratMode.asStateFlow()

        private val _isPaused = MutableStateFlow(false)
        val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

        private val _useSimulatedGps = MutableStateFlow(false)
        val useSimulatedGps: StateFlow<Boolean> = _useSimulatedGps.asStateFlow()

        private val _lightProbeEnabled = MutableStateFlow(true)
        val lightProbeEnabled: StateFlow<Boolean> = _lightProbeEnabled.asStateFlow()

        fun setCadence(mode: MeasurementCadence) {
            _cadence.value = mode
        }

        fun setRatMode(mode: RatSelectionMode) {
            _ratMode.value = mode
        }

        fun setPaused(paused: Boolean) {
            _isPaused.value = paused
            CampaignRepository.logSessionEvent(
                if (paused) "PAUSE" else "RESUME",
                if (paused) "Session recording paused" else "Session recording resumed"
            )
        }

        fun setUseSimulatedGps(enabled: Boolean) {
            _useSimulatedGps.value = enabled
        }

        fun setLightProbeEnabled(enabled: Boolean) {
            _lightProbeEnabled.value = enabled
        }

        fun updateLiveSnapshot(m: Measurement) {
            _liveMeasurement.value = m
        }
    }
}
