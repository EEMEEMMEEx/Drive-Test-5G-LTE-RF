package com.drivetest.lte5g.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.util.Log
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import com.drivetest.lte5g.MainActivity
import com.drivetest.lte5g.R
import com.drivetest.lte5g.model.CellMetrics
import com.drivetest.lte5g.model.LiveAlertStyle
import com.drivetest.lte5g.model.MeasurementCadence
import com.drivetest.lte5g.model.RatLockMode
import com.drivetest.lte5g.model.RatType
import java.util.Locale

/**
 * Helper object to build Live Alerts Notifications for Drive Test LTE/5G NR.
 * Matches pixel-for-pixel the UI designs in `designs/dashboard.figcraft`:
 * - Collapsed: Sleek dark capsule bar with 5G badge, hero dBm, quality pill, and live speed.
 * - Expanded: High-density RF Telemetry card with hero -78 dBm, serving sector, route progress track,
 *   2x3 telemetry matrix (RSRQ, SINR, PCI, Speed, Points, SIM), and custom action buttons.
 * - 0% Music Player artifacts (no headphones icon, no scrubber line, no pause circles).
 * - NOT colorized green: maintains dark navy/charcoal styling consistent with FigCraft design.
 */
object LiveAlertNotificationHelper {

    const val CHANNEL_ID = "drive_test_live_channel_v3"
    const val NOTIFICATION_ID = 1001

    // Custom Action identifiers
    const val CUSTOM_ACTION_STOP = "STOP_RECORDING"
    const val CUSTOM_ACTION_NEW_SESSION = "NEW_SESSION"

    // Android 16 (API 36) / Android 17 (API 37) Promoted Ongoing extra key
    const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"

    // Signal Tier Colors (matching UI Theme SignalTier1..5)
    val COLOR_EXCELLENT = 0xFF10B981.toInt() // >= -85 dBm (Emerald Green)
    val COLOR_GOOD = 0xFF22C55E.toInt()      // -86 to -95 dBm (Light Green)
    val COLOR_FAIR = 0xFFEAB308.toInt()      // -96 to -105 dBm (Amber Yellow)
    val COLOR_POOR = 0xFFF97316.toInt()      // -106 to -115 dBm (Orange)
    val COLOR_BAD = 0xFFEF4444.toInt()       // < -115 dBm (Red)
    val COLOR_DEFAULT = 0xFF06B6D4.toInt()   // Scanning / No Signal (Cyan)

    fun initMediaSession(context: Context, onStop: () -> Unit, onNewSession: () -> Unit) {}
    fun releaseMediaSession() {}
    fun updatePlaybackState(isRecording: Boolean) {}

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)

            // Remove legacy channels to force ColorOS to pick up fresh banner/capsule permissions
            try {
                manager?.deleteNotificationChannel("drive_test_channel")
                manager?.deleteNotificationChannel("drive_test_live_capsule")
            } catch (_: Exception) {}

            val channel = NotificationChannel(
                CHANNEL_ID,
                "Drive Test Live Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Live dynamic capsule around camera cutout and real-time recording controls"
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                enableLights(true)
                lightColor = COLOR_EXCELLENT
            }
            manager?.createNotificationChannel(channel)
        }
    }

    fun getRsrp(metrics: CellMetrics?): Int? {
        return metrics?.nr?.ssRsrp ?: metrics?.lte?.rsrp
    }

    fun getRsrpTierColor(rsrp: Int?): Int {
        return when {
            rsrp == null -> COLOR_DEFAULT
            rsrp >= -85 -> COLOR_EXCELLENT
            rsrp >= -95 -> COLOR_GOOD
            rsrp >= -105 -> COLOR_FAIR
            rsrp >= -115 -> COLOR_POOR
            else -> COLOR_BAD
        }
    }

    fun getRsrpQualityLabel(rsrp: Int?): String {
        return when {
            rsrp == null -> "Unknown"
            rsrp >= -85 -> "Excellent"
            rsrp >= -95 -> "Good"
            rsrp >= -105 -> "Fair"
            rsrp >= -115 -> "Poor"
            else -> "Bad"
        }
    }

    fun formatRatLabel(metrics: CellMetrics?): String {
        if (metrics == null) return "Scanning..."
        return when (metrics.ratType) {
            RatType.NR_NSA -> {
                val nrBand = metrics.nr?.band?.let { "n$it" }
                val lteBand = metrics.lte?.band?.let { "+B$it" } ?: ""
                if (nrBand != null) "5G NSA ($nrBand$lteBand)" else "5G NSA"
            }
            RatType.NR_SA -> {
                val nrBand = metrics.nr?.band?.let { "n$it" }
                if (nrBand != null) "5G SA ($nrBand)" else "5G SA"
            }
            RatType.LTE -> {
                val lteBand = metrics.lte?.band?.let { "B$it" }
                if (lteBand != null) "LTE ($lteBand)" else "LTE"
            }
            RatType.UNKNOWN -> {
                if (metrics.nr != null) "5G NR"
                else if (metrics.lte != null) "LTE"
                else "Scanning..."
            }
        }
    }

    fun formatSpeed(speedKmh: Float?): String {
        return if (speedKmh == null || speedKmh < 0f) {
            "GPS Acquiring..."
        } else {
            String.format(Locale.US, "%.1f km/h", speedKmh)
        }
    }

    fun formatElapsedTime(startTimeMillis: Long, currentTimeMillis: Long = System.currentTimeMillis()): String {
        val diffMs = maxOf(0L, currentTimeMillis - startTimeMillis)
        val totalSec = diffMs / 1000
        val sec = totalSec % 60
        val min = (totalSec / 60) % 60
        val hr = totalSec / 3600
        return if (hr > 0) {
            String.format(Locale.US, "%02d:%02d:%02d", hr, min, sec)
        } else {
            String.format(Locale.US, "%02d:%02d", min, sec)
        }
    }

    fun generateSignalArtwork(
        metrics: CellMetrics?,
        tierColor: Int,
        speedKmh: Float? = null,
        sampleCount: Int = 0
    ): Bitmap? {
        return try {
            val size = 256
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF0B1120.toInt()
                style = Paint.Style.FILL
            }
            val rect = RectF(0f, 0f, size.toFloat(), size.toFloat())
            canvas.drawRoundRect(rect, 28f, 28f, bgPaint)

            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = tierColor
                alpha = 110
                style = Paint.Style.STROKE
                strokeWidth = 3f
            }
            canvas.drawRoundRect(rect, 28f, 28f, borderPaint)

            val headerBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF1E293B.toInt()
                style = Paint.Style.FILL
            }
            val headerRect = RectF(14f, 12f, (size - 14).toFloat(), 44f)
            canvas.drawRoundRect(headerRect, 8f, 8f, headerBgPaint)

            val ratText = formatRatLabel(metrics)
            val ratPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF38BDF8.toInt()
                textSize = 19f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(ratText, size / 2f, 35f, ratPaint)

            val rsrp = getRsrp(metrics)
            val rsrpText = if (rsrp != null) "$rsrp" else "--"
            val rsrpPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = tierColor
                textSize = 52f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(rsrpText, (size / 2f) - 18f, 96f, rsrpPaint)

            val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF94A3B8.toInt()
                textSize = 20f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.LEFT
            }
            canvas.drawText("dBm", (size / 2f) + 38f, 96f, unitPaint)

            val qualityLabel = getRsrpQualityLabel(rsrp).uppercase(Locale.US)
            val qualityPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = tierColor
                textSize = 15f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(qualityLabel, size / 2f, 120f, qualityPaint)

            val rsrq = metrics?.nr?.ssRsrq ?: metrics?.lte?.rsrq
            val sinr = metrics?.nr?.ssSinr ?: metrics?.lte?.rssnr

            val subLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF64748B.toInt()
                textSize = 13f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            val subValPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFE2E8F0.toInt()
                textSize = 16f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }

            val leftColX = 70f
            canvas.drawText("RSRQ", leftColX, 148f, subLabelPaint)
            val rsrqStr = if (rsrq != null) "$rsrq dB" else "--"
            canvas.drawText(rsrqStr, leftColX, 170f, subValPaint)

            val rightColX = 186f
            canvas.drawText("SINR", rightColX, 148f, subLabelPaint)
            val sinrStr = if (sinr != null) String.format(Locale.US, "%.1f dB", sinr) else "--"
            canvas.drawText(sinrStr, rightColX, 170f, subValPaint)

            val divPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF334155.toInt()
                strokeWidth = 1.5f
            }
            canvas.drawLine(128f, 138f, 128f, 176f, divPaint)

            val speedFormatted = formatSpeed(speedKmh)
            val statsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF94A3B8.toInt()
                textSize = 13.5f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("$speedFormatted • $sampleCount pts", size / 2f, 204f, statsPaint)

            val barCount = 5
            val activeBars = when {
                rsrp == null -> 0
                rsrp >= -85 -> 5
                rsrp >= -95 -> 4
                rsrp >= -105 -> 3
                rsrp >= -115 -> 2
                else -> 1
            }
            val barWidth = 14f
            val barSpacing = 10f
            val totalWidth = barCount * barWidth + (barCount - 1) * barSpacing
            var startX = (size - totalWidth) / 2f
            val baseY = 240f

            for (i in 1..barCount) {
                val barHeight = 10f + (i * 4.5f)
                val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (i <= activeBars) tierColor else 0xFF243046.toInt()
                    style = Paint.Style.FILL
                }
                val barRect = RectF(startX, baseY - barHeight, startX + barWidth, baseY)
                canvas.drawRoundRect(barRect, 4f, 4f, barPaint)
                startX += barWidth + barSpacing
            }

            bitmap
        } catch (_: Throwable) {
            null
        }
    }

    fun buildLiveNotification(
        context: Context,
        sessionName: String,
        startTimeMillis: Long,
        sampleCount: Int,
        speedKmh: Float?,
        metrics: CellMetrics?,
        cadence: MeasurementCadence,
        simDisplayName: String? = null,
        lockMode: RatLockMode = RatLockMode.AUTO,
        style: LiveAlertStyle = LiveAlertStyle.CAPSULE
    ): Notification {
        val rsrp = getRsrp(metrics)
        val rsrpStr = if (rsrp != null) "$rsrp dBm" else "N/A"
        val tierColor = getRsrpTierColor(rsrp)
        val qualityLabel = getRsrpQualityLabel(rsrp)
        val ratLabel = formatRatLabel(metrics)
        val speedStr = formatSpeed(speedKmh)
        val elapsedStr = formatElapsedTime(startTimeMillis)
        val artworkBitmap = generateSignalArtwork(metrics, tierColor, speedKmh, sampleCount)

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            context,
            1,
            Intent(context, DriveTestForegroundService::class.java).apply {
                action = DriveTestForegroundService.ACTION_STOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val newSessionIntent = PendingIntent.getService(
            context,
            2,
            Intent(context, DriveTestForegroundService::class.java).apply {
                action = DriveTestForegroundService.ACTION_NEW_SESSION
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val rsrq = metrics?.nr?.ssRsrq ?: metrics?.lte?.rsrq
        val sinr = metrics?.nr?.ssSinr ?: metrics?.lte?.rssnr
        val pci = metrics?.nr?.pci ?: metrics?.lte?.pci
        val sector = metrics?.nr?.let { "Cell #${it.pci} (n${it.band})" }
            ?: metrics?.lte?.let { "Cell #${it.pci} (B${it.band})" }
            ?: "Serving Sector"

        // =========================================================================
        // 1. Build Base Notification (Category: NAVIGATION)
        // =========================================================================
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("$ratLabel · $rsrpStr ($qualityLabel)")
            .setContentText("📍 $sampleCount pts  |  🚗 $speedStr")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setColorized(false) // CRITICAL: NEVER set colorized to prevent solid green background!
            .setColor(tierColor)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Recording", stopIntent)
            .addAction(android.R.drawable.ic_popup_sync, "New Session", newSessionIntent)

        if (artworkBitmap != null) {
            builder.setLargeIcon(artworkBitmap)
        }

        // =========================================================================
        // 2. Safely Inflate RemoteViews matching `designs/dashboard.figcraft`
        // =========================================================================
        var customViewsApplied = false
        try {
            val rvCollapsed = RemoteViews(context.packageName, R.layout.notification_live_collapsed).apply {
                setTextViewText(R.id.tv_collapsed_badge, ratLabel)
                setTextViewText(R.id.tv_collapsed_rsrp, rsrpStr)
                setTextColor(R.id.tv_collapsed_rsrp, tierColor)
                setTextViewText(R.id.tv_collapsed_quality, qualityLabel.uppercase(Locale.US))
                setTextColor(R.id.tv_collapsed_quality, tierColor)
                setTextViewText(R.id.tv_collapsed_speed, "🚗 $speedStr")
                setTextViewText(R.id.tv_collapsed_points, "📍 $sampleCount pts")
            }

            val rvExpanded = RemoteViews(context.packageName, R.layout.notification_live_expanded).apply {
                val ratShort = if (metrics?.ratType == RatType.NR_NSA || metrics?.ratType == RatType.NR_SA) "5G" else "LTE"
                setTextViewText(R.id.tv_badge_rat, ratShort)
                setTextViewText(R.id.tv_title, "Drive Test • RF Live Tracking")
                setTextViewText(R.id.tv_subtitle, "$sessionName ($elapsedStr)")
                setTextViewText(R.id.tv_cadence_chip, cadence.label)
                setTextViewText(R.id.tv_timer_chip, elapsedStr)

                setTextViewText(R.id.tv_hero_rsrp, rsrpStr)
                setTextColor(R.id.tv_hero_rsrp, tierColor)
                setTextViewText(R.id.tv_quality_pill, qualityLabel.uppercase(Locale.US))
                setTextColor(R.id.tv_quality_pill, tierColor)
                setTextViewText(R.id.tv_serving_sector, "Serving: $sector${if (!simDisplayName.isNullOrBlank()) " • $simDisplayName" else ""}")

                setProgressBar(R.id.pb_route_progress, 100, (sampleCount % 100), false)
                setTextViewText(R.id.tv_route_info, "🚗 $speedStr • $sampleCount pts")

                setTextViewText(R.id.tv_grid_rsrq, if (rsrq != null) "$rsrq dB" else "--")
                setTextViewText(R.id.tv_grid_sinr, if (sinr != null) String.format(Locale.US, "%.1f dB", sinr) else "--")
                setTextViewText(R.id.tv_grid_pci, if (pci != null) "$pci" else "--")
                setTextViewText(R.id.tv_grid_speed, speedStr)
                setTextViewText(R.id.tv_grid_points, "$sampleCount")
                setTextViewText(R.id.tv_grid_sim, simDisplayName ?: "SIM 1")

                setOnClickPendingIntent(R.id.btn_stop, stopIntent)
                setOnClickPendingIntent(R.id.btn_new_session, newSessionIntent)
            }

            builder.setCustomContentView(rvCollapsed)
            builder.setCustomBigContentView(rvExpanded)
            builder.setStyle(NotificationCompat.DecoratedCustomViewStyle())
            customViewsApplied = true
        } catch (t: Throwable) {
            Log.e("LiveAlertNotif", "Failed to apply custom RemoteViews, falling back to BigTextStyle", t)
        }

        if (!customViewsApplied) {
            val detailLines = buildString {
                append("🚗 Speed: $speedStr | 📍 Points: $sampleCount pts\n")
                if (rsrq != null || sinr != null) {
                    append("📶 RSRQ: ${if (rsrq != null) "$rsrq dB" else "--"} | SINR: ${if (sinr != null) String.format(Locale.US, "%.1f dB", sinr) else "--"}\n")
                }
                append("📡 Sector: $sector\n")
                append("⏱️ Elapsed: $elapsedStr | ⚙️ Cadence: ${cadence.label}")
                if (!simDisplayName.isNullOrBlank()) {
                    append(" | 📱 SIM: $simDisplayName")
                }
            }
            builder.setStyle(
                NotificationCompat.BigTextStyle()
                    .setBigContentTitle("$ratLabel · $rsrpStr ($qualityLabel)")
                    .setSummaryText(sessionName)
                    .bigText(detailLines)
            )
        }

        if (artworkBitmap != null) {
            builder.setLargeIcon(artworkBitmap)
        }

        // Android 16 (API 36+) Promoted Ongoing Live Updates
        builder.extras.putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true)

        // ColorOS / OxygenOS Fluid Cloud Navigation Capsule hooks (around camera cutout)
        builder.extras.putBoolean("oplus.notification.capsule", true)
        builder.extras.putBoolean("coloros.notification.capsule", true)
        builder.extras.putBoolean("com.oplus.notification.capsule", true)
        builder.extras.putString("oplus.notification.capsule.type", "navigation")
        builder.extras.putString("coloros.notification.capsule.type", "navigation")
        builder.extras.putString("oplus.notification.capsule.status", "active")
        builder.extras.putString("oplus.notification.capsule.title", "$ratLabel · $rsrpStr")
        builder.extras.putString("oplus.notification.capsule.content", "$sampleCount pts · $speedStr")
        builder.extras.putString("coloros.notification.capsule.title", "$ratLabel · $rsrpStr")
        builder.extras.putString("coloros.notification.capsule.content", "$sampleCount pts · $speedStr")
        builder.extras.putString("android.substName", "Drive Test 5G")

        return builder.build()
    }
}
