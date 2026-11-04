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
import android.os.Bundle
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.drivetest.lte5g.MainActivity
import com.drivetest.lte5g.model.CellMetrics
import com.drivetest.lte5g.model.LiveAlertStyle
import com.drivetest.lte5g.model.MeasurementCadence
import com.drivetest.lte5g.model.RatLockMode
import com.drivetest.lte5g.model.RatType
import java.util.Locale

/**
 * Helper object to build Live Alerts Notifications for Drive Test LTE/5G NR.
 * Supports two distinct presentation styles:
 * 1. CAPSULE (Default & Recommended): Activates the Dynamic Island / Fluid Cloud capsule
 *    around the punch-hole front camera on ColorOS / OxygenOS (OnePlus, OPPO, Realme) and
 *    Android 16/17 Promoted Ongoing Notifications.
 *    Optimized as a pure Drive Test Telemetry Instrument Panel:
 *    - Replaces music controls (pause/skip) with CustomActions (Stop Recording / New Session).
 *    - Suppresses audio scrubber and progress timelines (PLAYBACK_POSITION_UNKNOWN).
 *    - Generates a multi-metric Telemetry HUD Tile (RAT, Band, RSRP, RSRQ, SINR, Speed, Points).
 * 2. COMPACT: Standard notification drawer card with multi-line BigTextStyle.
 */
object LiveAlertNotificationHelper {

    const val CHANNEL_ID = "drive_test_live_capsule"
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

    private var mediaSession: MediaSessionCompat? = null

    fun getMediaSession(): MediaSessionCompat? = mediaSession

    fun initMediaSession(
        context: Context,
        onStop: () -> Unit,
        onNewSession: () -> Unit
    ): MediaSessionCompat {
        val existing = mediaSession
        if (existing != null) return existing

        val session = MediaSessionCompat(context, "DriveTestLiveAlert").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS or
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS
            )
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onStop() {
                    onStop()
                }
                override fun onPause() {
                    onStop()
                }
                override fun onCustomAction(action: String?, extras: Bundle?) {
                    when (action) {
                        CUSTOM_ACTION_STOP, "ACTION_STOP" -> onStop()
                        CUSTOM_ACTION_NEW_SESSION, "ACTION_NEW_SESSION" -> onNewSession()
                    }
                }
            })
            isActive = true
        }
        mediaSession = session
        return session
    }

    fun releaseMediaSession() {
        try {
            mediaSession?.apply {
                isActive = false
                release()
            }
        } catch (_: Exception) {}
        mediaSession = null
    }

    fun updatePlaybackState(isRecording: Boolean) {
        val session = mediaSession ?: return
        try {
            val state = if (isRecording) {
                PlaybackStateCompat.STATE_PLAYING
            } else {
                PlaybackStateCompat.STATE_STOPPED
            }

            val stopCustomAction = PlaybackStateCompat.CustomAction.Builder(
                CUSTOM_ACTION_STOP,
                "Stop Recording",
                android.R.drawable.ic_menu_close_clear_cancel
            ).build()

            val newSessionCustomAction = PlaybackStateCompat.CustomAction.Builder(
                CUSTOM_ACTION_NEW_SESSION,
                "New Session",
                android.R.drawable.ic_popup_sync
            ).build()

            // OMIT ACTION_PAUSE and ACTION_SKIP_TO_NEXT so SystemUI doesn't render music player buttons
            val playbackState = PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_STOP)
                .addCustomAction(stopCustomAction)
                .addCustomAction(newSessionCustomAction)
                // PLAYBACK_POSITION_UNKNOWN (-1L) suppresses the audio seek bar!
                .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, if (isRecording) 1.0f else 0.0f)
                .build()
            session.setPlaybackState(playbackState)
        } catch (_: Exception) {}
    }

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)

            try {
                manager?.deleteNotificationChannel("drive_test_channel")
            } catch (_: Exception) {}

            val channel = NotificationChannel(
                CHANNEL_ID,
                "Drive Test Live Alerts (Capsule)",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Live dynamic capsule around camera cutout and real-time recording controls"
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setSound(null, null)
                enableVibration(false)
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

    /**
     * Dynamically generates a high-density 256x256 square "Drive Test Telemetry HUD Tile" bitmap,
     * rendering the full RF telemetry matrix:
     * - Top Header: Cellular RAT and Carrier Band
     * - Center: Prominent RSRP value in neon tier color with dBm unit and quality label
     * - Middle Grid: Secondary RF metrics (RSRQ & SINR) in 2-column layout
     * - Bottom Row: Speed km/h, points count, and illuminated 5-tier signal bars.
     */
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

            // 1. Dark sleek background with rounded corners
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF0B1120.toInt()
                style = Paint.Style.FILL
            }
            val rect = RectF(0f, 0f, size.toFloat(), size.toFloat())
            canvas.drawRoundRect(rect, 28f, 28f, bgPaint)

            // 2. Subtle glowing border in signal tier color
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = tierColor
                alpha = 110
                style = Paint.Style.STROKE
                strokeWidth = 3f
            }
            canvas.drawRoundRect(rect, 28f, 28f, borderPaint)

            // 3. Top Header Strip (Pill)
            val headerBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF1E293B.toInt()
                style = Paint.Style.FILL
            }
            val headerRect = RectF(14f, 12f, (size - 14).toFloat(), 44f)
            canvas.drawRoundRect(headerRect, 8f, 8f, headerBgPaint)

            val ratText = formatRatLabel(metrics)
            val ratPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF38BDF8.toInt() // Sky Blue
                textSize = 19f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(ratText, size / 2f, 35f, ratPaint)

            // 4. Center RSRP Metrics
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

            // 5. Secondary 2-Column Telemetry Grid (RSRQ & SINR)
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

            // Left Column: RSRQ
            val leftColX = 70f
            canvas.drawText("RSRQ", leftColX, 148f, subLabelPaint)
            val rsrqStr = if (rsrq != null) "$rsrq dB" else "--"
            canvas.drawText(rsrqStr, leftColX, 170f, subValPaint)

            // Right Column: SINR
            val rightColX = 186f
            canvas.drawText("SINR", rightColX, 148f, subLabelPaint)
            val sinrStr = if (sinr != null) String.format(Locale.US, "%.1f dB", sinr) else "--"
            canvas.drawText(sinrStr, rightColX, 170f, subValPaint)

            // Vertical Column Divider
            val divPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF334155.toInt()
                strokeWidth = 1.5f
            }
            canvas.drawLine(128f, 138f, 128f, 176f, divPaint)

            // 6. Bottom Row: Stats & 5-Tier Signal Bars
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

        // Pending Intents for Navigation & Action Controls
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

        // Branch 1: CAPSULE Mode (Default & Recommended)
        // Activates the Dynamic Island / Fluid Cloud capsule around the camera cutout on ColorOS/OxygenOS.
        // Uses clean labels, no audio scrubber, and dedicated utility buttons (Stop / New Session).
        if (style == LiveAlertStyle.CAPSULE) {
            val session = mediaSession ?: initMediaSession(context, {
                DriveTestForegroundService.stop(context)
            }, {
                DriveTestForegroundService.newSession(context)
            })

            val stopCustomAction = PlaybackStateCompat.CustomAction.Builder(
                CUSTOM_ACTION_STOP,
                "Stop Recording",
                android.R.drawable.ic_menu_close_clear_cancel
            ).build()

            val newSessionCustomAction = PlaybackStateCompat.CustomAction.Builder(
                CUSTOM_ACTION_NEW_SESSION,
                "New Session",
                android.R.drawable.ic_popup_sync
            ).build()

            // OMIT ACTION_PAUSE and ACTION_SKIP_TO_NEXT so SystemUI doesn't render music player buttons
            val playbackState = PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_STOP)
                .addCustomAction(stopCustomAction)
                .addCustomAction(newSessionCustomAction)
                .setState(PlaybackStateCompat.STATE_PLAYING, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                .build()
            session.setPlaybackState(playbackState)

            // Update MediaMetadata with telemetry data
            val titleText = "$ratLabel · $rsrpStr ($qualityLabel)"
            val artistText = "📍 $sampleCount pts  |  🚗 $speedStr"
            val albumText = "⏱️ $elapsedStr  |  ⚙️ ${cadence.label}${if (!simDisplayName.isNullOrBlank()) "  |  📱 $simDisplayName" else ""}"

            val metadataBuilder = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, titleText)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artistText)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, albumText)
                // NOTE: We intentionally OMIT METADATA_KEY_DURATION so SystemUI does not render a duration bar

            if (artworkBitmap != null) {
                metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, artworkBitmap)
                metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, artworkBitmap)
            }
            session.setMetadata(metadataBuilder.build())

            val mediaStyle = MediaStyle()
                .setMediaSession(session.sessionToken)
                .setShowActionsInCompactView(0, 1)
                .setShowCancelButton(true)
                .setCancelButtonIntent(stopIntent)

            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setStyle(mediaStyle)
                .setContentTitle(titleText)
                .setContentText(artistText)
                .setSubText("Live Alert · ${cadence.label}")
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setColor(tierColor)
                .setColorized(true)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .setContentIntent(pendingIntent)
                // Utility action buttons (Stop Recording / New Session)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Recording", stopIntent)
                .addAction(android.R.drawable.ic_popup_sync, "New Session", newSessionIntent)

            if (artworkBitmap != null) {
                builder.setLargeIcon(artworkBitmap)
            }

            // Android 16/17 Promoted Ongoing Notifications (Live Updates)
            builder.extras.putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true)

            // ColorOS / OxygenOS Fluid Cloud capsule hooks (around punch-hole camera)
            builder.extras.putBoolean("oplus.notification.capsule", true)
            builder.extras.putBoolean("coloros.notification.capsule", true)
            builder.extras.putBoolean("com.oplus.notification.capsule", true)
            builder.extras.putString("oplus.notification.capsule.type", "media")
            builder.extras.putString("coloros.notification.capsule.type", "media")
            builder.extras.putString("oplus.notification.capsule.status", "active")
            builder.extras.putString("oplus.notification.capsule.title", "$ratLabel · $rsrpStr")
            builder.extras.putString("oplus.notification.capsule.content", "$sampleCount pts · $speedStr")
            builder.extras.putString("coloros.notification.capsule.title", "$ratLabel · $rsrpStr")
            builder.extras.putString("coloros.notification.capsule.content", "$sampleCount pts · $speedStr")
            builder.extras.putString("android.substName", "Drive Test 5G")

            return builder.build()
        }

        // Branch 2: COMPACT Mode (Standard notification drawer card)
        releaseMediaSession()

        val titleText = "$ratLabel · $rsrpStr ($qualityLabel)"
        val contentText = "$sessionName · $sampleCount pts · $speedStr"
        val subText = "Live Alert · ${cadence.label}"

        val detailLines = buildString {
            append("🚗 Speed: $speedStr | 📍 Points: $sampleCount pts\n")
            append("⏱️ Elapsed: $elapsedStr | ⚙️ Cadence: ${cadence.label} (${cadence.frequencyLabel})\n")
            if (!simDisplayName.isNullOrBlank()) {
                append("📱 SIM: $simDisplayName | ")
            }
            append("🔒 Mode: ${lockMode.name}")
        }

        val bigTextStyle = NotificationCompat.BigTextStyle()
            .setBigContentTitle(titleText)
            .setSummaryText(subText)
            .bigText(detailLines)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setStyle(bigTextStyle)
            .setContentTitle(titleText)
            .setContentText(contentText)
            .setSubText(subText)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setColor(tierColor)
            .setColorized(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setUsesChronometer(true)
            .setWhen(startTimeMillis)
            .setShowWhen(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Recording", stopIntent)
            .addAction(android.R.drawable.ic_popup_sync, "New Session", newSessionIntent)

        if (artworkBitmap != null) {
            builder.setLargeIcon(artworkBitmap)
        }

        builder.extras.putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true)
        builder.extras.putString("android.substName", "Drive Test 5G")

        return builder.build()
    }
}
