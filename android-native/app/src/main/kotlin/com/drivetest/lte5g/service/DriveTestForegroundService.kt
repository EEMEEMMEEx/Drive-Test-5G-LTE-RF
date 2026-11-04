package com.drivetest.lte5g.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.drivetest.lte5g.MainActivity
import com.drivetest.lte5g.data.SessionRepository
import com.drivetest.lte5g.location.LocationCollector
import com.drivetest.lte5g.model.CellMetrics
import com.drivetest.lte5g.model.DriveTestSample
import com.drivetest.lte5g.model.LiveAlertStyle
import com.drivetest.lte5g.model.MeasurementCadence
import com.drivetest.lte5g.model.RatLockMode
import com.drivetest.lte5g.model.RatType
import com.drivetest.lte5g.model.SimSlotInfo
import com.drivetest.lte5g.telephony.CellCollector
import com.drivetest.lte5g.telephony.RatLockCapability
import com.drivetest.lte5g.telephony.RatLockManager
import com.drivetest.lte5g.telephony.RatLockStatus
import com.drivetest.lte5g.telephony.RatLockType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class DriveTestForegroundService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    private var collectionJob: Job? = null

    private lateinit var cellCollector: CellCollector
    private lateinit var locationCollector: LocationCollector

    override fun onCreate() {
        super.onCreate()
        SessionRepository.initStorage(applicationContext)
        createNotificationChannel()

        // Initialize MediaSession so Dynamic Island / Fluid Cloud capsule is warm and ready
        LiveAlertNotificationHelper.initMediaSession(
            context = this,
            onStop = { stop(this) },
            onNewSession = { newSession(this) }
        )

        cellCollector = CellCollector(this)
        locationCollector = LocationCollector(this)
        refreshAvailableSims(this)
        _ratLockCapability.value = RatLockManager.checkCapability(this)
        _ratLockStatus.value = RatLockManager.evaluateLockStatus(
            targetMode = _ratLockMode.value,
            lockType = _ratLockType.value,
            capability = _ratLockCapability.value,
            connectedRat = RatType.UNKNOWN
        )

        serviceScope.launch {
            cellCollector.currentMetrics.collect { m ->
                _liveMetrics.value = m
                _ratLockStatus.value = RatLockManager.evaluateLockStatus(
                    targetMode = _ratLockMode.value,
                    lockType = _ratLockType.value,
                    capability = _ratLockCapability.value,
                    connectedRat = m.ratType
                )
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val lockModeStr = intent.getStringExtra(EXTRA_RAT_LOCK)
                val lockMode = if (lockModeStr != null) {
                    try { RatLockMode.valueOf(lockModeStr) } catch (e: Exception) { RatLockMode.AUTO }
                } else {
                    _ratLockMode.value
                }
                _ratLockMode.value = lockMode

                val cadenceStr = intent.getStringExtra(EXTRA_CADENCE)
                if (cadenceStr != null) {
                    try { _measurementCadence.value = MeasurementCadence.valueOf(cadenceStr) } catch (_: Exception) {}
                }

                val sessionName = intent.getStringExtra(EXTRA_SESSION_NAME)
                startRecording(sessionName)
            }
            ACTION_NEW_SESSION -> {
                val lockModeStr = intent.getStringExtra(EXTRA_RAT_LOCK)
                val lockMode = if (lockModeStr != null) {
                    try { RatLockMode.valueOf(lockModeStr) } catch (e: Exception) { RatLockMode.AUTO }
                } else {
                    _ratLockMode.value
                }
                _ratLockMode.value = lockMode

                val cadenceStr = intent.getStringExtra(EXTRA_CADENCE)
                if (cadenceStr != null) {
                    try { _measurementCadence.value = MeasurementCadence.valueOf(cadenceStr) } catch (_: Exception) {}
                }

                val sessionName = intent.getStringExtra(EXTRA_SESSION_NAME)
                if (_isRecording.value) {
                    stopRecording()
                }
                startRecording(sessionName)
            }
            ACTION_STOP -> {
                stopRecording()
                stopSelf()
            }
            ACTION_SET_RAT_LOCK -> {
                val lockModeStr = intent.getStringExtra(EXTRA_RAT_LOCK)
                val lockTypeStr = intent.getStringExtra(EXTRA_LOCK_TYPE)
                if (lockModeStr != null) {
                    try {
                        val newMode = RatLockMode.valueOf(lockModeStr)
                        val newType = if (lockTypeStr != null) {
                            try { RatLockType.valueOf(lockTypeStr) } catch (_: Exception) { RatLockType.SOFTWARE_FILTER }
                        } else {
                            if (newMode == RatLockMode.AUTO) RatLockType.AUTO else _ratLockType.value
                        }
                        _ratLockMode.value = newMode
                        _ratLockType.value = newType

                        if (newType == RatLockType.HARDWARE_BASEBAND) {
                            val res = RatLockManager.tryDirectModemLock(this, newMode, cellCollector.currentSubId)
                            if (res.isFailure) {
                                Log.w(TAG, "Hardware modem lock direct call rejected: ${res.exceptionOrNull()?.message}")
                                _ratLockType.value = RatLockType.SOFTWARE_FILTER
                            }
                        }

                        val curRat = cellCollector.currentMetrics.value.ratType
                        _ratLockStatus.value = RatLockManager.evaluateLockStatus(
                            targetMode = newMode,
                            lockType = _ratLockType.value,
                            capability = _ratLockCapability.value,
                            connectedRat = curRat
                        )
                        updateNotification()
                    } catch (e: Exception) {
                        Log.e(TAG, "Invalid lock mode string: $lockModeStr", e)
                    }
                }
            }
            ACTION_SET_CADENCE -> {
                val cadenceStr = intent.getStringExtra(EXTRA_CADENCE)
                if (cadenceStr != null) {
                    try {
                        val newCadence = MeasurementCadence.valueOf(cadenceStr)
                        _measurementCadence.value = newCadence
                        locationCollector.updateInterval(newCadence.intervalMs)
                        updateNotification()
                        Log.d(TAG, "Measurement cadence updated to ${newCadence.label}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Invalid cadence string: $cadenceStr", e)
                    }
                }
            }
            ACTION_SET_LIVE_ALERT_STYLE -> {
                val styleStr = intent.getStringExtra(EXTRA_LIVE_ALERT_STYLE)
                if (styleStr != null) {
                    try {
                        val newStyle = LiveAlertStyle.valueOf(styleStr)
                        _liveAlertStyle.value = newStyle
                        if (newStyle == LiveAlertStyle.COMPACT) {
                            LiveAlertNotificationHelper.releaseMediaSession()
                        } else {
                            LiveAlertNotificationHelper.initMediaSession(
                                context = this,
                                onStop = { stop(this) },
                                onNewSession = { newSession(this) }
                            )
                        }
                        updateNotification()
                        Log.d(TAG, "Live alert style updated to ${newStyle.label}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Invalid live alert style: $styleStr", e)
                    }
                }
            }
            ACTION_SET_SIM_SLOT -> {
                val slot = intent.getIntExtra(EXTRA_SIM_SLOT, 0)
                val subId = intent.getIntExtra(EXTRA_SUB_ID, -1)
                val simName = intent.getStringExtra(EXTRA_SIM_NAME)
                _selectedSimSlot.value = slot
                if (::cellCollector.isInitialized) {
                    cellCollector.switchSim(slot, if (subId == -1) null else subId, simName)
                    _liveMetrics.value = cellCollector.currentMetrics.value
                    updateNotification()
                }
            }
        }
        return START_STICKY
    }

    private fun startRecording(sessionName: String? = null) {
        if (_isRecording.value) return

        val currentSlot = _selectedSimSlot.value
        val currentSimName = _availableSims.value.firstOrNull { it.slotIndex == currentSlot }?.displayName

        val currentActive = SessionRepository.activeSession.value
        val session = if (currentActive == null || !currentActive.isRecording) {
            SessionRepository.createNewSession(
                name = sessionName,
                lockMode = _ratLockMode.value,
                cadence = _measurementCadence.value,
                simSlot = currentSlot,
                simDisplayName = currentSimName
            )
        } else {
            currentActive
        }

        activeSessionId = session.id
        _isRecording.value = true
        _samplesList.value = session.samples

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        locationCollector.startTracking(intervalMs = _measurementCadence.value.intervalMs)

        collectionJob = serviceScope.launch {
            var sampleId = (session.samples.maxOfOrNull { it.id } ?: 0L) + 1L
            while (isActive) {
                cellCollector.pollCellInfo()
                val currentCadence = _measurementCadence.value
                delay(currentCadence.intervalMs)

                val loc = locationCollector.currentLocation.value
                val metrics = cellCollector.currentMetrics.value
                _liveMetrics.value = metrics
                val lockMode = _ratLockMode.value

                // Filter or evaluate based on lock mode
                val passesFilter = when (lockMode) {
                    RatLockMode.AUTO -> true
                    RatLockMode.LTE_ONLY -> metrics.lte != null
                    RatLockMode.NR_ONLY -> (metrics.nr != null || metrics.ratType == RatType.NR_NSA)
                }

                if (loc != null && passesFilter) {
                    val simSlot = _selectedSimSlot.value
                    val simName = _availableSims.value.firstOrNull { it.slotIndex == simSlot }?.displayName

                    val sample = DriveTestSample(
                        id = sampleId++,
                        sessionId = activeSessionId,
                        timestamp = System.currentTimeMillis(),
                        latitude = loc.latitude,
                        longitude = loc.longitude,
                        altitude = loc.altitude,
                        speedKmh = loc.speed * 3.6f,
                        bearing = loc.bearing,
                        accuracyMeters = loc.accuracy,
                        metrics = metrics,
                        ratLockMode = lockMode,
                        cadence = currentCadence,
                        simSlot = simSlot,
                        simDisplayName = simName
                    )
                    _latestSample.value = sample
                    SessionRepository.addSample(sample)
                    val currentSamples = SessionRepository.activeSession.value?.samples ?: emptyList()
                    _samplesList.value = currentSamples

                }
                updateNotification()
            }
        }
    }

    private fun stopRecording() {
        collectionJob?.cancel()
        collectionJob = null
        locationCollector.stopTracking()
        SessionRepository.finishCurrentSession()
        _isRecording.value = false
        if (_liveAlertStyle.value == LiveAlertStyle.CAPSULE) {
            LiveAlertNotificationHelper.updatePlaybackState(isRecording = false)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        stopRecording()
        LiveAlertNotificationHelper.releaseMediaSession()
        cellCollector.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        LiveAlertNotificationHelper.createNotificationChannel(this)
    }

    private fun buildNotification(): Notification {
        val session = SessionRepository.activeSession.value
        val metrics = _liveMetrics.value
        val loc = locationCollector.currentLocation.value
        val speedKmh = loc?.let { it.speed * 3.6f }
        val currentSamples = _samplesList.value
        val currentSlot = _selectedSimSlot.value
        val simName = _availableSims.value.firstOrNull { it.slotIndex == currentSlot }?.displayName

        return LiveAlertNotificationHelper.buildLiveNotification(
            context = this,
            sessionName = session?.name ?: "Drive Test",
            startTimeMillis = session?.startTime ?: System.currentTimeMillis(),
            sampleCount = currentSamples.size,
            speedKmh = speedKmh,
            metrics = metrics,
            cadence = _measurementCadence.value,
            simDisplayName = simName,
            lockMode = _ratLockMode.value,
            style = _liveAlertStyle.value
        )
    }

    private fun updateNotification() {
        if (!_isRecording.value) return
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification())
    }

    companion object {
        const val TAG = "DriveTestService"
        const val CHANNEL_ID = LiveAlertNotificationHelper.CHANNEL_ID
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.drivetest.lte5g.action.START"
        const val ACTION_STOP = "com.drivetest.lte5g.action.STOP"
        const val ACTION_NEW_SESSION = "com.drivetest.lte5g.action.NEW_SESSION"
        const val ACTION_SET_RAT_LOCK = "com.drivetest.lte5g.action.SET_RAT_LOCK"
        const val ACTION_SET_CADENCE = "com.drivetest.lte5g.action.SET_CADENCE"
        const val ACTION_SET_LIVE_ALERT_STYLE = "com.drivetest.lte5g.action.SET_LIVE_ALERT_STYLE"
        const val ACTION_SET_SIM_SLOT = "com.drivetest.lte5g.action.SET_SIM_SLOT"

        const val EXTRA_RAT_LOCK = "EXTRA_RAT_LOCK"
        const val EXTRA_LOCK_TYPE = "EXTRA_LOCK_TYPE"
        const val EXTRA_CADENCE = "EXTRA_CADENCE"
        const val EXTRA_LIVE_ALERT_STYLE = "EXTRA_LIVE_ALERT_STYLE"
        const val EXTRA_SESSION_NAME = "EXTRA_SESSION_NAME"
        const val EXTRA_SIM_SLOT = "EXTRA_SIM_SLOT"
        const val EXTRA_SUB_ID = "EXTRA_SUB_ID"
        const val EXTRA_SIM_NAME = "EXTRA_SIM_NAME"

        var activeSessionId: Long = System.currentTimeMillis()

        private val _isRecording = MutableStateFlow(false)
        val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

        private val _ratLockMode = MutableStateFlow(RatLockMode.AUTO)
        val ratLockMode: StateFlow<RatLockMode> = _ratLockMode.asStateFlow()

        private val _ratLockType = MutableStateFlow(RatLockType.AUTO)
        val ratLockType: StateFlow<RatLockType> = _ratLockType.asStateFlow()

        private val _ratLockCapability = MutableStateFlow(RatLockCapability.RESTRICTED_RADIO_INFO_AVAILABLE)
        val ratLockCapability: StateFlow<RatLockCapability> = _ratLockCapability.asStateFlow()

        private val _ratLockStatus = MutableStateFlow(RatLockStatus())
        val ratLockStatus: StateFlow<RatLockStatus> = _ratLockStatus.asStateFlow()

        private val _measurementCadence = MutableStateFlow(MeasurementCadence.PRECISE)
        val measurementCadence: StateFlow<MeasurementCadence> = _measurementCadence.asStateFlow()

        // Default to CAPSULE mode (Dynamic Island / Fluid Cloud capsule around camera active)
        private val _liveAlertStyle = MutableStateFlow(LiveAlertStyle.CAPSULE)
        val liveAlertStyle: StateFlow<LiveAlertStyle> = _liveAlertStyle.asStateFlow()

        private val _latestSample = MutableStateFlow<DriveTestSample?>(null)
        val latestSample: StateFlow<DriveTestSample?> = _latestSample.asStateFlow()

        private val _samplesList = MutableStateFlow<List<DriveTestSample>>(emptyList())
        val samplesList: StateFlow<List<DriveTestSample>> = _samplesList.asStateFlow()

        private val _selectedSimSlot = MutableStateFlow(0)
        val selectedSimSlot: StateFlow<Int> = _selectedSimSlot.asStateFlow()

        private val _availableSims = MutableStateFlow<List<SimSlotInfo>>(emptyList())
        val availableSims: StateFlow<List<SimSlotInfo>> = _availableSims.asStateFlow()

        private val _liveMetrics = MutableStateFlow<CellMetrics?>(null)
        val liveMetrics: StateFlow<CellMetrics?> = _liveMetrics.asStateFlow()

        fun start(
            context: Context,
            lockMode: RatLockMode = _ratLockMode.value,
            cadence: MeasurementCadence = _measurementCadence.value,
            sessionName: String? = null
        ) {
            _measurementCadence.value = cadence
            val intent = Intent(context, DriveTestForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RAT_LOCK, lockMode.name)
                putExtra(EXTRA_CADENCE, cadence.name)
                if (sessionName != null) {
                    putExtra(EXTRA_SESSION_NAME, sessionName)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun newSession(
            context: Context,
            sessionName: String? = null,
            lockMode: RatLockMode = _ratLockMode.value,
            cadence: MeasurementCadence = _measurementCadence.value
        ) {
            _measurementCadence.value = cadence
            val intent = Intent(context, DriveTestForegroundService::class.java).apply {
                action = ACTION_NEW_SESSION
                putExtra(EXTRA_RAT_LOCK, lockMode.name)
                putExtra(EXTRA_CADENCE, cadence.name)
                if (sessionName != null) {
                    putExtra(EXTRA_SESSION_NAME, sessionName)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, DriveTestForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun setRatLock(
            context: Context,
            lockMode: RatLockMode,
            lockType: RatLockType = if (lockMode == RatLockMode.AUTO) RatLockType.AUTO else RatLockType.SOFTWARE_FILTER
        ) {
            _ratLockMode.value = lockMode
            _ratLockType.value = lockType
            val intent = Intent(context, DriveTestForegroundService::class.java).apply {
                action = ACTION_SET_RAT_LOCK
                putExtra(EXTRA_RAT_LOCK, lockMode.name)
                putExtra(EXTRA_LOCK_TYPE, lockType.name)
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start service for setRatLock", e)
            }
            val curMetrics = _liveMetrics.value
            _ratLockStatus.value = RatLockManager.evaluateLockStatus(
                targetMode = lockMode,
                lockType = lockType,
                capability = _ratLockCapability.value,
                connectedRat = curMetrics?.ratType ?: RatType.UNKNOWN
            )
        }

        fun refreshRatLockCapability(context: Context) {
            _ratLockCapability.value = RatLockManager.checkCapability(context)
            val curMetrics = _liveMetrics.value
            _ratLockStatus.value = RatLockManager.evaluateLockStatus(
                targetMode = _ratLockMode.value,
                lockType = _ratLockType.value,
                capability = _ratLockCapability.value,
                connectedRat = curMetrics?.ratType ?: RatType.UNKNOWN
            )
        }

        fun setMeasurementCadence(context: Context, cadence: MeasurementCadence) {
            _measurementCadence.value = cadence
            val intent = Intent(context, DriveTestForegroundService::class.java).apply {
                action = ACTION_SET_CADENCE
                putExtra(EXTRA_CADENCE, cadence.name)
            }
            context.startService(intent)
        }

        fun setLiveAlertStyle(context: Context, style: LiveAlertStyle) {
            _liveAlertStyle.value = style
            val intent = Intent(context, DriveTestForegroundService::class.java).apply {
                action = ACTION_SET_LIVE_ALERT_STYLE
                putExtra(EXTRA_LIVE_ALERT_STYLE, style.name)
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed sending setLiveAlertStyle", e)
            }
        }

        fun setSimSlot(context: Context, slot: Int, subId: Int? = null, simName: String? = null) {
            _selectedSimSlot.value = slot
            val intent = Intent(context, DriveTestForegroundService::class.java).apply {
                action = ACTION_SET_SIM_SLOT
                putExtra(EXTRA_SIM_SLOT, slot)
                if (subId != null) putExtra(EXTRA_SUB_ID, subId)
                if (simName != null) putExtra(EXTRA_SIM_NAME, simName)
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed sending setSimSlot", e)
            }
        }

        fun refreshAvailableSims(context: Context) {
            val list = CellCollector.getActiveSubscriptions(context)
            _availableSims.value = list
            if (list.isNotEmpty() && list.none { it.slotIndex == _selectedSimSlot.value }) {
                _selectedSimSlot.value = list.first().slotIndex
            }
        }
    }
}
