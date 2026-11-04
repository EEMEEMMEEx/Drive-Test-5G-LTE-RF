package com.drivetest.lte5g.overlay

import android.animation.LayoutTransition
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.drivetest.lte5g.MainActivity
import com.drivetest.lte5g.data.SessionRepository
import com.drivetest.lte5g.model.CellMetrics
import com.drivetest.lte5g.model.MeasurementCadence
import com.drivetest.lte5g.service.DriveTestForegroundService
import com.drivetest.lte5g.service.LiveAlertNotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Floating Dynamic Island Overlay Service for Drive Test LTE/5G NR.
 * Renders an interactive, floating Dynamic Island around the front camera punch-hole:
 * - Collapsed: Sleek black capsule around the front camera cutout displaying RAT, live RSRP, and tier dot.
 * - Expanded: 100% Custom Drive Test Telemetry Card with zero music player controls, zero headphones icon,
 *   zero audio scrubber line, and zero unwanted media buttons.
 */
class DynamicIslandOverlayService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var windowManager: WindowManager? = null
    private var overlayView: DynamicIslandView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Overlay permission not granted. Stopping service.")
            stopSelf()
            return
        }

        windowManager = getSystemService(WindowManager::class.java)
        setupOverlayView()
        observeDriveTestData()
        _isOverlayShowing.value = true
    }

    private fun setupOverlayView() {
        val wm = windowManager ?: return

        val statusBarHeight = getStatusBarHeight()
        val topOffset = maxOf(statusBarHeight - 28.dpToPx(), 6.dpToPx())

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = topOffset
        }
        layoutParams = params

        val view = DynamicIslandView(this).apply {
            onToggleExpand = { isExpanded ->
                if (isExpanded) {
                    params.width = 346.dpToPx()
                    params.height = WindowManager.LayoutParams.WRAP_CONTENT
                } else {
                    params.width = WindowManager.LayoutParams.WRAP_CONTENT
                    params.height = WindowManager.LayoutParams.WRAP_CONTENT
                }
                try {
                    wm.updateViewLayout(this, params)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed updating window layout params", e)
                }
            }
            onCardClick = {
                val intent = Intent(this@DynamicIslandOverlayService, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                startActivity(intent)
            }
        }
        overlayView = view

        try {
            wm.addView(view, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed adding overlay view to WindowManager", e)
            stopSelf()
        }
    }

    private fun observeDriveTestData() {
        // Automatically dismiss when drive test recording finishes
        serviceScope.launch {
            DriveTestForegroundService.isRecording.collect { isRec ->
                if (!isRec) {
                    stopSelf()
                }
            }
        }

        // Live RF metrics update
        serviceScope.launch {
            DriveTestForegroundService.liveMetrics.collect { metrics ->
                val sample = DriveTestForegroundService.latestSample.value
                val samples = DriveTestForegroundService.samplesList.value
                val session = SessionRepository.activeSession.value
                val cadence = DriveTestForegroundService.measurementCadence.value
                val slot = DriveTestForegroundService.selectedSimSlot.value
                val simName = DriveTestForegroundService.availableSims.value.firstOrNull { it.slotIndex == slot }?.displayName

                overlayView?.updateData(
                    metrics = metrics,
                    speedKmh = sample?.speedKmh,
                    sampleCount = samples.size,
                    sessionName = session?.name ?: "Drive Test",
                    startTimeMillis = session?.startTime ?: System.currentTimeMillis(),
                    cadence = cadence,
                    simDisplayName = simName
                )
            }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        overlayView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing overlay view", e)
            }
        }
        overlayView = null
        _isOverlayShowing.value = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun getStatusBarHeight(): Int {
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else 24.dpToPx()
    }

    private fun Int.dpToPx(): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            this.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    companion object {
        const val TAG = "DynamicIslandOverlay"
        const val PREF_NAME = "drive_test_overlay_prefs"
        const val PREF_KEY_OVERLAY_ENABLED = "pref_dynamic_island_overlay_enabled"

        private val _isOverlayShowing = MutableStateFlow(false)
        val isOverlayShowing = _isOverlayShowing.asStateFlow()

        fun isOverlayEnabled(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(PREF_KEY_OVERLAY_ENABLED, true)
        }

        fun setOverlayEnabled(context: Context, enabled: Boolean) {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(PREF_KEY_OVERLAY_ENABLED, enabled).apply()
        }

        fun start(context: Context) {
            if (!Settings.canDrawOverlays(context)) return
            try {
                val intent = Intent(context, DynamicIslandOverlayService::class.java)
                context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start DynamicIslandOverlayService", e)
            }
        }

        fun stop(context: Context) {
            try {
                val intent = Intent(context, DynamicIslandOverlayService::class.java)
                context.stopService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop DynamicIslandOverlayService", e)
            }
        }
    }
}

/**
 * Custom Android View for the Dynamic Island Overlay:
 * - Collapsed Pill: Pure black `#000000` pill sitting around the camera hole.
 * - Expanded Card: Pure Drive Test Telemetry Card with zero music player controls,
 *   zero headphones icon, zero timeline scrubber bar, and zero action buttons.
 */
class DynamicIslandView(context: Context) : FrameLayout(context) {

    var onToggleExpand: ((Boolean) -> Unit)? = null
    var onCardClick: (() -> Unit)? = null

    private var isExpanded = false

    // Collapsed Pill Views
    private val collapsedPill: LinearLayout
    private val ratTextPill: TextView
    private val spacerPunchHole: View
    private val rsrpTextPill: TextView
    private val statusDotPill: View

    // Expanded Card Views
    private val expandedCard: LinearLayout
    private val headerTitle: TextView
    private val headerSubtitle: TextView
    private val collapseButton: TextView
    private val pointsVal: TextView
    private val speedVal: TextView
    private val elapsedVal: TextView
    private val rsrqVal: TextView
    private val sinrVal: TextView
    private val cadenceVal: TextView
    private val simBandVal: TextView

    private val cardBackground = GradientDrawable()
    private val pillBackground = GradientDrawable()
    private val dotBackground = GradientDrawable()

    init {
        layoutTransition = LayoutTransition().apply {
            enableTransitionType(LayoutTransition.CHANGING)
            setDuration(220)
        }

        // ==========================================
        // 1. COLLAPSED PILL (Around Camera Cutout)
        // ==========================================
        pillBackground.apply {
            setColor(Color.BLACK)
            cornerRadius = 18.dpToPx().toFloat()
        }

        collapsedPill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pillBackground
            setPadding(12.dpToPx(), 4.dpToPx(), 12.dpToPx(), 4.dpToPx())
            elevation = 12.dpToPx().toFloat()
            setOnClickListener { toggleExpand() }
        }

        ratTextPill = TextView(context).apply {
            text = "5G"
            textSize = 12f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(Color.parseColor("#38BDF8")) // Cyan / Sky Blue
        }

        // Center spacer for front camera punch hole
        spacerPunchHole = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(34.dpToPx(), 28.dpToPx())
        }

        rsrpTextPill = TextView(context).apply {
            text = "-- dBm"
            textSize = 12f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }

        dotBackground.apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#10B981"))
        }

        statusDotPill = View(context).apply {
            val size = 8.dpToPx()
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginStart = 6.dpToPx()
            }
            background = dotBackground
        }

        collapsedPill.addView(ratTextPill)
        collapsedPill.addView(spacerPunchHole)
        collapsedPill.addView(rsrpTextPill)
        collapsedPill.addView(statusDotPill)
        addView(collapsedPill)

        // ==========================================
        // 2. EXPANDED HUD CARD (Pure Drive Test Telemetry)
        // ==========================================
        cardBackground.apply {
            setColor(Color.parseColor("#0B1120"))
            cornerRadius = 22.dpToPx().toFloat()
            setStroke(2.dpToPx(), Color.parseColor("#10B981"))
        }

        expandedCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBackground
            setPadding(16.dpToPx(), 14.dpToPx(), 16.dpToPx(), 14.dpToPx())
            elevation = 20.dpToPx().toFloat()
            visibility = View.GONE
            setOnClickListener { onCardClick?.invoke() }
        }

        // Row 1: Header (Title, Subtitle & Collapse Button)
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val appIconBadge = TextView(context).apply {
            text = "📶"
            textSize = 16f
            setPadding(0, 0, 8.dpToPx(), 0)
        }

        val titleCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        headerTitle = TextView(context).apply {
            text = "5G NSA · -- dBm"
            textSize = 14f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(Color.parseColor("#10B981"))
        }

        headerSubtitle = TextView(context).apply {
            text = "Drive Test Telemetry"
            textSize = 11f
            setTextColor(Color.parseColor("#94A3B8"))
        }

        titleCol.addView(headerTitle)
        titleCol.addView(headerSubtitle)

        collapseButton = TextView(context).apply {
            text = "▲"
            textSize = 14f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(Color.parseColor("#64748B"))
            setPadding(8.dpToPx(), 4.dpToPx(), 8.dpToPx(), 4.dpToPx())
            setOnClickListener { toggleExpand() }
        }

        headerRow.addView(appIconBadge)
        headerRow.addView(titleCol)
        headerRow.addView(collapseButton)
        expandedCard.addView(headerRow)

        // Thin divider
        val divider = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1.dpToPx()).apply {
                setMargins(0, 10.dpToPx(), 0, 10.dpToPx())
            }
            setBackgroundColor(Color.parseColor("#1E293B"))
        }
        expandedCard.addView(divider)

        // Row 2: Grid 1 (Points, Speed, Elapsed)
        val grid1 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 3f
        }
        pointsVal = createGridCell(grid1, "📍 POINTS", "0 pts")
        speedVal = createGridCell(grid1, "🚗 SPEED", "0.0 km/h")
        elapsedVal = createGridCell(grid1, "⏱️ ELAPSED", "00:00")
        expandedCard.addView(grid1)

        // Row 3: Grid 2 (RSRQ, SINR, Cadence)
        val grid2 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 3f
            setPadding(0, 8.dpToPx(), 0, 0)
        }
        rsrqVal = createGridCell(grid2, "RSRQ", "-- dB")
        sinrVal = createGridCell(grid2, "SINR", "-- dB")
        cadenceVal = createGridCell(grid2, "CADENCE", "Precise")
        expandedCard.addView(grid2)

        // Row 4: SIM & Band Info Strip
        simBandVal = TextView(context).apply {
            text = "📱 SIM: Standby  |  Band: Scanning..."
            textSize = 11f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, 10.dpToPx(), 0, 2.dpToPx())
            gravity = Gravity.CENTER_HORIZONTAL
        }
        expandedCard.addView(simBandVal)

        addView(expandedCard)
    }

    private fun createGridCell(parent: LinearLayout, label: String, initialVal: String): TextView {
        val cell = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val labelView = TextView(context).apply {
            text = label
            textSize = 10f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(Color.parseColor("#64748B"))
        }

        val valView = TextView(context).apply {
            text = initialVal
            textSize = 13f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(Color.parseColor("#E2E8F0"))
        }

        cell.addView(labelView)
        cell.addView(valView)
        parent.addView(cell)
        return valView
    }

    fun toggleExpand() {
        isExpanded = !isExpanded
        if (isExpanded) {
            collapsedPill.visibility = View.GONE
            expandedCard.visibility = View.VISIBLE
        } else {
            expandedCard.visibility = View.GONE
            collapsedPill.visibility = View.VISIBLE
        }
        onToggleExpand?.invoke(isExpanded)
    }

    fun updateData(
        metrics: CellMetrics?,
        speedKmh: Float?,
        sampleCount: Int,
        sessionName: String,
        startTimeMillis: Long,
        cadence: MeasurementCadence,
        simDisplayName: String?
    ) {
        val rsrp = LiveAlertNotificationHelper.getRsrp(metrics)
        val tierColor = LiveAlertNotificationHelper.getRsrpTierColor(rsrp)
        val ratLabel = LiveAlertNotificationHelper.formatRatLabel(metrics)
        val speedStr = LiveAlertNotificationHelper.formatSpeed(speedKmh)
        val elapsedStr = LiveAlertNotificationHelper.formatElapsedTime(startTimeMillis)
        val quality = LiveAlertNotificationHelper.getRsrpQualityLabel(rsrp)

        // 1. Update Collapsed Pill
        val shortRat = when {
            metrics?.nr != null -> "5G"
            metrics?.lte != null -> "LTE"
            else -> "RF"
        }
        ratTextPill.text = shortRat
        rsrpTextPill.text = if (rsrp != null) "$rsrp dBm" else "-- dBm"
        rsrpTextPill.setTextColor(Color.WHITE)
        dotBackground.setColor(tierColor)

        // 2. Update Expanded Card
        cardBackground.setStroke(2.dpToPx(), tierColor)
        headerTitle.text = "$ratLabel · ${rsrp?.let { "$it dBm" } ?: "N/A"} ($quality)"
        headerTitle.setTextColor(tierColor)
        headerSubtitle.text = sessionName

        pointsVal.text = "$sampleCount pts"
        speedVal.text = speedStr
        elapsedVal.text = elapsedStr

        val rsrq = metrics?.nr?.ssRsrq ?: metrics?.lte?.rsrq
        val sinr = metrics?.nr?.ssSinr ?: metrics?.lte?.rssnr
        rsrqVal.text = if (rsrq != null) "$rsrq dB" else "--"
        sinrVal.text = if (sinr != null) String.format(Locale.US, "%.1f dB", sinr) else "--"
        cadenceVal.text = cadence.label

        val simText = if (!simDisplayName.isNullOrBlank()) "📱 $simDisplayName" else "📱 SIM 1"
        val bandText = when {
            metrics?.nr?.band != null -> "Band n${metrics.nr.band}"
            metrics?.lte?.band != null -> "Band B${metrics.lte.band}"
            else -> "Band: Scanning..."
        }
        simBandVal.text = "$simText  |  $bandText"
    }

    private fun Int.dpToPx(): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            this.toFloat(),
            resources.displayMetrics
        ).toInt()
    }
}
