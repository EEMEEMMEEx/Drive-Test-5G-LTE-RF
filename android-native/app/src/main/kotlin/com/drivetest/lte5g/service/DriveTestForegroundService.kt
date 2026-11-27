package com.drivetest.lte5g.service

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.drivetest.lte5g.cloud.CloudTelemetrySyncManager
import com.drivetest.lte5g.cloud.GoogleDriveAutoUploader
import com.drivetest.lte5g.data.SessionDiagnostics
import com.drivetest.lte5g.data.SessionRepository
import com.drivetest.lte5g.location.LocationCollector
import com.drivetest.lte5g.model.CellMetrics
import com.drivetest.lte5g.model.DriveTestSample
import com.drivetest.lte5g.model.DriveTestSession
import com.drivetest.lte5g.model.MeasurementCadence
import com.drivetest.lte5g.model.RatLockMode
import com.drivetest.lte5g.model.RatType
import com.drivetest.lte5g.model.SimSlotInfo
import com.drivetest.lte5g.telephony.CellCollector
import com.drivetest.lte5g.telephony.RatLockCapability
import com.drivetest.lte5g.telephony.RatLockManager
import com.drivetest.lte5g.telephony.RatLockStatus
import com.drivetest.lte5g.telephony.RatLockType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class DriveTestForegroundService : Service() {

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Uncaught exception in serviceScope", throwable)
        SessionDiagnostics.incLoopExceptions()
        SessionRepository.activeSession.value?.id?.let { sid ->
            SessionDiagnostics.recordEvent(
                sessionId = sid,
                event = "SERVICE_SCOPE_EXCEPTION",
                details = mapOf("error" to (throwable.message ?: throwable.javaClass.simpleName))
            )
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + exceptionHandler)
    private var collectionJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private lateinit var cellCollector: CellCollector
    private lateinit var locationCollector: LocationCollector

    private var lastNotificationUpdateElapsedMs: Long = 0L
    private var lastNotifiedRsrp: Int? = null
    private var lastNotifiedRat: RatType? = null

    override fun onCreate() {
        super.onCreate()
        SessionRepository.initStorage(applicationContext)
        CloudTelemetrySyncManager.init(applicationContext)
        GoogleDriveAutoUploader.init(applicationContext)
        createNotificationChannel()

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

        if (_isDualSimMode.value) {
            cellCollector.setDualSimLogging(true)
        }

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

        serviceScope.launch {
            cellCollector.currentMetricsSim2.collect { m2 ->
                _liveMetricsSim2.value = m2
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Handle START_STICKY process recreation where Android delivers a null intent
        if (intent == null) {
            SessionRepository.ensureLoadedSync(applicationContext)
            val pointer = SessionRepository.readActiveSessionPointer()
            if (pointer != null) {
                Log.w(TAG, "START_STICKY null intent received — resuming interrupted session ${pointer.sessionId}")
                _ratLockMode.value = pointer.ratLockMode
                _measurementCadence.value = pointer.cadence
                _selectedSimSlot.value = pointer.simSlot
                _isDualSimMode.value = pointer.isDualSimLogging
                if (::cellCollector.isInitialized) {
                    cellCollector.setDualSimLogging(pointer.isDualSimLogging)
                }
                val resumed = SessionRepository.resumeSessionForRecovery(pointer.sessionId)
                if (resumed != null) {
                    startRecording(sessionName = resumed.name, preloadedSession = resumed)
                    return START_STICKY
                }
                SessionRepository.clearActiveSessionPointer()
            }
            Log.i(TAG, "START_STICKY null intent with no active session pointer; stopping service cleanly.")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        when (intent.action) {
            ACTION_START -> {
                val lockModeStr = intent.getStringExtra(EXTRA_RAT_LOCK)
                val lockMode = if (lockModeStr != null) {
                    try { RatLockMode.valueOf(lockModeStr) } catch (_: Exception) { RatLockMode.AUTO }
                } else {
                    _ratLockMode.value
                }
                _ratLockMode.value = lockMode

                val cadenceStr = intent.getStringExtra(EXTRA_CADENCE)
                if (cadenceStr != null) {
                    try { _measurementCadence.value = MeasurementCadence.valueOf(cadenceStr) } catch (_: Exception) {}
                }

                val dualSimExtra = intent.getBooleanExtra(EXTRA_DUAL_SIM_ENABLED, _isDualSimMode.value)
                _isDualSimMode.value = dualSimExtra
                if (::cellCollector.isInitialized) {
                    cellCollector.setDualSimLogging(dualSimExtra)
                }

                val sessionName = intent.getStringExtra(EXTRA_SESSION_NAME)
                startRecording(sessionName)
            }
            ACTION_NEW_SESSION -> {
                val lockModeStr = intent.getStringExtra(EXTRA_RAT_LOCK)
                val lockMode = if (lockModeStr != null) {
                    try { RatLockMode.valueOf(lockModeStr) } catch (_: Exception) { RatLockMode.AUTO }
                } else {
                    _ratLockMode.value
                }
                _ratLockMode.value = lockMode

                val cadenceStr = intent.getStringExtra(EXTRA_CADENCE)
                if (cadenceStr != null) {
                    try { _measurementCadence.value = MeasurementCadence.valueOf(cadenceStr) } catch (_: Exception) {}
                }

                val dualSimExtra = intent.getBooleanExtra(EXTRA_DUAL_SIM_ENABLED, _isDualSimMode.value)
                _isDualSimMode.value = dualSimExtra
                if (::cellCollector.isInitialized) {
                    cellCollector.setDualSimLogging(dualSimExtra)
                }

                val sessionName = intent.getStringExtra(EXTRA_SESSION_NAME)
                if (_isRecording.value) {
                    stopRecording(reason = "NEW_SESSION_REQUESTED")
                }
                startRecording(sessionName)
            }
            ACTION_STOP -> {
                stopRecording(reason = "USER_STOP")
                stopSelf()
                return START_NOT_STICKY
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
                        updateNotification(force = true)
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
                        if (_isRecording.value) {
                            locationCollector.updateInterval(newCadence.intervalMs)
                            updateNotification(force = true)
                        }
                        Log.d(TAG, "Measurement cadence updated to ${newCadence.label}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Invalid cadence string: $cadenceStr", e)
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
                    if (_isDualSimMode.value) {
                        _liveMetricsSim2.value = cellCollector.currentMetricsSim2.value
                    }
                    updateNotification(force = true)
                }
            }
            ACTION_SET_DUAL_SIM_MODE -> {
                val enabled = intent.getBooleanExtra(EXTRA_DUAL_SIM_ENABLED, false)
                _isDualSimMode.value = enabled
                if (::cellCollector.isInitialized) {
                    cellCollector.setDualSimLogging(enabled)
                    _liveMetricsSim2.value = cellCollector.currentMetricsSim2.value
                }
                updateNotification(force = true)
                Log.d(TAG, "Dual SIM Benchmark mode updated: $enabled")
            }
        }
        return if (_isRecording.value) START_STICKY else START_NOT_STICKY
    }

    private fun acquireWakeLock(sessionId: Long) {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            if (wakeLock == null) {
                wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "DriveTestLTE5G::RecordingWakeLock"
                ).apply {
                    setReferenceCounted(false)
                }
            }
            // Acquire with 60-minute rolling timeout; renewed every 30 min during active recording
            wakeLock?.acquire(WAKELOCK_TIMEOUT_MS)
            SessionDiagnostics.recordEvent(
                sessionId = sessionId,
                event = "WAKELOCK_ACQUIRED",
                details = mapOf(
                    "timeoutMs" to WAKELOCK_TIMEOUT_MS,
                    "ignoringBatteryOpt" to isIgnoringBatteryOptimizations(this)
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire partial WakeLock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { wl ->
                if (wl.isHeld) {
                    wl.release()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release WakeLock: ${e.message}")
        }
    }

    private fun getBatteryPercentage(): Int? {
        return try {
            val bm = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return null
            val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (pct in 0..100) pct else null
        } catch (_: Exception) {
            null
        }
    }

    private fun startRecording(sessionName: String? = null, preloadedSession: DriveTestSession? = null) {
        if (_isRecording.value) return

        val currentSlot = _selectedSimSlot.value
        val currentSimName = _availableSims.value.firstOrNull { it.slotIndex == currentSlot }?.displayName
        val isDualSim = _isDualSimMode.value && _availableSims.value.size >= 2
        val secondarySim = if (isDualSim) _availableSims.value.firstOrNull { it.slotIndex != currentSlot } else null

        if (::cellCollector.isInitialized) {
            cellCollector.ensureListenersRegistered()
            cellCollector.setDualSimLogging(isDualSim)
        }

        val session = preloadedSession ?: run {
            val currentActive = SessionRepository.activeSession.value
            if (currentActive == null || !currentActive.isRecording) {
                SessionRepository.createNewSession(
                    name = sessionName,
                    lockMode = _ratLockMode.value,
                    cadence = _measurementCadence.value,
                    simSlot = currentSlot,
                    simDisplayName = currentSimName,
                    isDualSimLogging = isDualSim,
                    sim2DisplayName = secondarySim?.displayName
                )
            } else {
                currentActive
            }
        }

        activeSessionId = session.id
        _isRecording.value = true
        _samplesList.value = session.samples
        CloudTelemetrySyncManager.markSessionStarted(session)

        try {
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
            SessionDiagnostics.recordEvent(
                sessionId = session.id,
                event = "FGS_STARTED",
                details = mapOf("cadence" to _measurementCadence.value.name, "batteryPct" to getBatteryPercentage())
            )
        } catch (e: Exception) {
            Log.e(TAG, "startForeground with live notification failed, trying safe fallback", e)
            try {
                val fallbackNotif = NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_upload)
                    .setContentTitle("Drive Test Recording")
                    .setContentText("Recording in progress...")
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .build()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        fallbackNotif,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                    )
                } else {
                    startForeground(NOTIFICATION_ID, fallbackNotif)
                }
            } catch (fatal: Exception) {
                Log.e(TAG, "Critical failure posting fallback foreground notification", fatal)
                SessionDiagnostics.recordEvent(
                    sessionId = session.id,
                    event = "FGS_START_FAILED",
                    details = mapOf("error" to (fatal.message ?: fatal.javaClass.simpleName))
                )
            }
        }

        acquireWakeLock(session.id)
        locationCollector.startTracking(intervalMs = _measurementCadence.value.intervalMs)

        lastNotificationUpdateElapsedMs = 0L
        lastNotifiedRsrp = null
        lastNotifiedRat = null

        collectionJob?.cancel()
        collectionJob = serviceScope.launch {
            var sampleId = (session.samples.maxOfOrNull { it.id } ?: 0L) + 1L
            var lastHeartbeatElapsedMs = SystemClock.elapsedRealtime()
            var lastWakeLockRenewElapsedMs = SystemClock.elapsedRealtime()

            while (isActive) {
                try {
                    SessionDiagnostics.incTicks()
                    try {
                        cellCollector.pollCellInfo()
                    } catch (e: Exception) {
                        SessionDiagnostics.incPollErrors()
                        Log.w(TAG, "pollCellInfo failed: ${e.message}")
                    }

                    val currentCadence = _measurementCadence.value
                    delay(currentCadence.intervalMs)

                    val loc = locationCollector.currentLocation.value
                    val metrics = cellCollector.currentMetrics.value
                    val metricsSim2 = if (_isDualSimMode.value) cellCollector.currentMetricsSim2.value else null

                    _liveMetrics.value = metrics
                    _liveMetricsSim2.value = metricsSim2
                    val lockMode = _ratLockMode.value

                    val passesFilter = when (lockMode) {
                        RatLockMode.AUTO -> true
                        RatLockMode.LTE_ONLY -> metrics.lte != null
                        RatLockMode.NR_ONLY -> (metrics.nr != null || metrics.ratType == RatType.NR_NSA)
                    }

                    if (loc == null) {
                        SessionDiagnostics.incDroppedNoLocation()
                    } else if (!passesFilter) {
                        SessionDiagnostics.incDroppedRatFilter()
                    } else {
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
                            metricsSim2 = metricsSim2,
                            ratLockMode = lockMode,
                            cadence = currentCadence,
                            simSlot = simSlot,
                            simDisplayName = simName
                        )
                        _latestSample.value = sample
                        SessionRepository.addSample(sample)
                        val currentSession = SessionRepository.activeSession.value
                        val currentSamples = currentSession?.samples ?: emptyList()
                        _samplesList.value = currentSamples

                        if (currentSession != null) {
                            CloudTelemetrySyncManager.enqueueSample(currentSession, sample)
                        }
                    }

                    updateNotification(force = false)

                    val nowElapsed = SystemClock.elapsedRealtime()
                    if (nowElapsed - lastHeartbeatElapsedMs >= DIAGNOSTIC_HEARTBEAT_INTERVAL_MS) {
                        lastHeartbeatElapsedMs = nowElapsed
                        val rt = Runtime.getRuntime()
                        val heapUsedMb = (rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L)
                        SessionDiagnostics.recordHeartbeat(
                            sessionId = activeSessionId,
                            heapUsedMb = heapUsedMb,
                            batteryPct = getBatteryPercentage(),
                            ignoringBatteryOpt = isIgnoringBatteryOptimizations(this@DriveTestForegroundService),
                            lastGpsFixAgeMs = locationCollector.lastFixAgeMs()
                        )
                    }
                    if (nowElapsed - lastWakeLockRenewElapsedMs >= WAKELOCK_RENEW_INTERVAL_MS) {
                        lastWakeLockRenewElapsedMs = nowElapsed
                        wakeLock?.acquire(WAKELOCK_TIMEOUT_MS)
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    SessionDiagnostics.incLoopExceptions()
                    Log.e(TAG, "Error in collection loop iteration; continuing", t)
                    SessionDiagnostics.recordEvent(
                        sessionId = activeSessionId,
                        event = "LOOP_EXCEPTION",
                        details = mapOf("error" to (t.message ?: t.javaClass.simpleName))
                    )
                    delay(1000L)
                }
            }
        }
    }

    private fun stopRecording(reason: String = "STOP") {
        val activeId = SessionRepository.activeSession.value?.id
        if (activeId != null) {
            SessionDiagnostics.recordEvent(
                sessionId = activeId,
                event = "STOP_REQUESTED",
                details = mapOf("reason" to reason, "batteryPct" to getBatteryPercentage())
            )
        }
        collectionJob?.cancel()
        collectionJob = null
        locationCollector.stopTracking()
        val sessionToFinish = SessionRepository.activeSession.value
        SessionRepository.finishCurrentSession()
        if (sessionToFinish != null) {
            val finalized = SessionRepository.sessions.value.firstOrNull { it.id == sessionToFinish.id } ?: sessionToFinish
            CloudTelemetrySyncManager.markSessionStopped(finalized)
            GoogleDriveAutoUploader.enqueueSessionForWifiUpload(finalized)
        } else {
            CloudTelemetrySyncManager.markSessionStopped()
        }
        releaseWakeLock()
        _isRecording.value = false
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (_isRecording.value) {
            val sid = SessionRepository.activeSession.value?.id
            if (sid != null) {
                SessionDiagnostics.recordEvent(
                    sessionId = sid,
                    event = "ON_TRIM_MEMORY",
                    details = mapOf("level" to level)
                )
            }
            serviceScope.launch(Dispatchers.IO) {
                SessionRepository.flushActiveSessionSync()
            }
        }
    }

    override fun onDestroy() {
        if (_isRecording.value) {
            stopRecording(reason = "SERVICE_DESTROY")
        } else {
            releaseWakeLock()
        }
        cellCollector.stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        LiveAlertNotificationHelper.createNotificationChannel(this)
    }

    private fun buildNotification(): Notification {
        val session = SessionRepository.activeSession.value
        val metrics = _liveMetrics.value
        val metricsSim2 = if (_isDualSimMode.value) _liveMetricsSim2.value else null
        val loc = locationCollector.currentLocation.value
        val speedKmh = loc?.let { it.speed * 3.6f }
        val currentSamples = _samplesList.value
        val currentSlot = _selectedSimSlot.value
        val simName = _availableSims.value.firstOrNull { it.slotIndex == currentSlot }?.displayName
        val secondarySim = if (_isDualSimMode.value) _availableSims.value.firstOrNull { it.slotIndex != currentSlot } else null

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
            metricsSim2 = metricsSim2,
            sim2DisplayName = secondarySim?.displayName
        )
    }

    private fun updateNotification(force: Boolean = false) {
        if (!_isRecording.value) return
        val nowElapsed = SystemClock.elapsedRealtime()
        val currentMetrics = _liveMetrics.value
        val currentRsrp = LiveAlertNotificationHelper.getRsrp(currentMetrics)
        val currentRat = currentMetrics?.ratType
        val elapsedSinceLast = nowElapsed - lastNotificationUpdateElapsedMs
        val signalChanged = currentRsrp != lastNotifiedRsrp || currentRat != lastNotifiedRat

        // Throttle notification Bitmap + Binder IPC updates in 1s Precise mode to prevent OEM kill pressure
        if (!force && elapsedSinceLast < MIN_NOTIFICATION_UPDATE_INTERVAL_MS && !(signalChanged && elapsedSinceLast >= 1500L)) {
            return
        }

        lastNotificationUpdateElapsedMs = nowElapsed
        lastNotifiedRsrp = currentRsrp
        lastNotifiedRat = currentRat

        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager?.notify(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            Log.w(TAG, "Failed updating live notification: ${e.message}")
        }
    }

    companion object {
        const val TAG = "DriveTestService"
        const val CHANNEL_ID = LiveAlertNotificationHelper.CHANNEL_ID
        const val NOTIFICATION_ID = 1001

        private const val WAKELOCK_TIMEOUT_MS = 60L * 60L * 1000L // 60 minutes rolling
        private const val WAKELOCK_RENEW_INTERVAL_MS = 30L * 60L * 1000L // Renew every 30 minutes
        private const val DIAGNOSTIC_HEARTBEAT_INTERVAL_MS = 60_000L // 60s health snapshot
        private const val MIN_NOTIFICATION_UPDATE_INTERVAL_MS = 3000L // Max 1 Bitmap IPC / 3s unless RF changes

        const val ACTION_START = "com.drivetest.lte5g.action.START"
        const val ACTION_STOP = "com.drivetest.lte5g.action.STOP"
        const val ACTION_NEW_SESSION = "com.drivetest.lte5g.action.NEW_SESSION"
        const val ACTION_SET_RAT_LOCK = "com.drivetest.lte5g.action.SET_RAT_LOCK"
        const val ACTION_SET_CADENCE = "com.drivetest.lte5g.action.SET_CADENCE"
        const val ACTION_SET_SIM_SLOT = "com.drivetest.lte5g.action.SET_SIM_SLOT"
        const val ACTION_SET_DUAL_SIM_MODE = "com.drivetest.lte5g.action.SET_DUAL_SIM_MODE"

        const val EXTRA_RAT_LOCK = "EXTRA_RAT_LOCK"
        const val EXTRA_LOCK_TYPE = "EXTRA_LOCK_TYPE"
        const val EXTRA_CADENCE = "EXTRA_CADENCE"
        const val EXTRA_SESSION_NAME = "EXTRA_SESSION_NAME"
        const val EXTRA_SIM_SLOT = "EXTRA_SIM_SLOT"
        const val EXTRA_SUB_ID = "EXTRA_SUB_ID"
        const val EXTRA_SIM_NAME = "EXTRA_SIM_NAME"
        const val EXTRA_DUAL_SIM_ENABLED = "EXTRA_DUAL_SIM_ENABLED"

        var activeSessionId: Long = System.currentTimeMillis()

        private val _isRecording = MutableStateFlow(false)
        val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

        private val _isDualSimMode = MutableStateFlow(false)
        val isDualSimMode: StateFlow<Boolean> = _isDualSimMode.asStateFlow()

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

        private val _liveMetricsSim2 = MutableStateFlow<CellMetrics?>(null)
        val liveMetricsSim2: StateFlow<CellMetrics?> = _liveMetricsSim2.asStateFlow()

        fun isIgnoringBatteryOptimizations(context: Context): Boolean {
            return try {
                val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
                pm.isIgnoringBatteryOptimizations(context.packageName)
            } catch (_: Exception) {
                true
            }
        }

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
                putExtra(EXTRA_DUAL_SIM_ENABLED, _isDualSimMode.value)
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
                putExtra(EXTRA_DUAL_SIM_ENABLED, _isDualSimMode.value)
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
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed sending ACTION_STOP", e)
            }
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
            if (!_isRecording.value) return
            val intent = Intent(context, DriveTestForegroundService::class.java).apply {
                action = ACTION_SET_CADENCE
                putExtra(EXTRA_CADENCE, cadence.name)
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update measurement cadence in service", e)
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

        fun setDualSimMode(context: Context, enabled: Boolean) {
            _isDualSimMode.value = enabled
            val intent = Intent(context, DriveTestForegroundService::class.java).apply {
                action = ACTION_SET_DUAL_SIM_MODE
                putExtra(EXTRA_DUAL_SIM_ENABLED, enabled)
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed sending setDualSimMode", e)
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
