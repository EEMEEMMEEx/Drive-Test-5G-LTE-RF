package com.drivetest.lte5g.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.drivetest.lte5g.MainActivity
import com.drivetest.lte5g.model.CellMetrics
import com.drivetest.lte5g.model.MeasurementCadence
import com.drivetest.lte5g.model.RatLockMode
import com.drivetest.lte5g.model.RatType
import java.util.Locale

/**
 * Helper object to build and update Live Alerts Notifications for Drive Test LTE/5G NR.
 * Supports Android 16 (API 36) / Android 17 (API 37) Promoted Ongoing Notifications (Live Updates)
 * with real-time status bar chip, lock screen prominence, interactive actions, signal-tier colorization,
 * and seamless fallback on older Android versions down to minSdk 29.
 */
object LiveAlertNotificationHelper {

    const val CHANNEL_ID = "drive_test_channel"
    const val NOTIFICATION_ID = 1001

    // Key introduced in Android 16 / 17 for Promoted Ongoing Notifications (Live Updates)
    const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"

    // Signal Tier Colors (matching UI Theme SignalTier1..5)
    val COLOR_EXCELLENT = 0xFF10B981.toInt() // >= -85 dBm (Emerald Green)
    val COLOR_GOOD = 0xFF22C55E.toInt()      // -86 to -95 dBm (Light Green)
    val COLOR_FAIR = 0xFFEAB308.toInt()      // -96 to -105 dBm (Amber Yellow)
    val COLOR_POOR = 0xFFF97316.toInt()      // -106 to -115 dBm (Orange)
    val COLOR_BAD = 0xFFEF4444.toInt()       // < -115 dBm (Red)
    val COLOR_DEFAULT = 0xFF06B6D4.toInt()   // Scanning / No Signal (Cyan)

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Drive Test Live Alerts",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Real-time Drive Test telemetry, live RF signal status, and ongoing recording controls"
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val manager = context.getSystemService(NotificationManager::class.java)
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

    fun buildLiveNotification(
        context: Context,
        sessionName: String,
        startTimeMillis: Long,
        sampleCount: Int,
        speedKmh: Float?,
        metrics: CellMetrics?,
        cadence: MeasurementCadence,
        simDisplayName: String? = null,
        lockMode: RatLockMode = RatLockMode.AUTO
    ): Notification {
        val rsrp = getRsrp(metrics)
        val rsrpStr = if (rsrp != null) "$rsrp dBm" else "N/A"
        val tierColor = getRsrpTierColor(rsrp)
        val ratLabel = formatRatLabel(metrics)
        val speedStr = formatSpeed(speedKmh)
        val elapsedStr = formatElapsedTime(startTimeMillis)

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

        // Collapsed status line (glanceable)
        val contentText = "$ratLabel · $rsrpStr · $sampleCount pts · $speedStr"

        // Expanded multi-metric telemetry (BigTextStyle)
        val bigText = buildString {
            append("📶 RAT: ").append(ratLabel).append(" | RSRP: ").append(rsrpStr)
            if (rsrp != null) {
                append(" (").append(getRsrpQualityLabel(rsrp)).append(")")
            }
            append("\n")
            append("🚗 Speed: ").append(speedStr).append(" | 📍 Points: ").append(sampleCount).append(" pts")
            append("\n")
            append("⏱ Elapsed: ").append(elapsedStr).append(" | ⚙ Cadence: ").append(cadence.label)
            if (lockMode != RatLockMode.AUTO) {
                append(" | 🔒 Lock: ").append(lockMode.name)
            }
            if (!simDisplayName.isNullOrBlank()) {
                append("\n📱 SIM: ").append(simDisplayName)
            }
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("RF Drive Test — $sessionName")
            .setContentText(contentText)
            .setSubText("Live Alert · ${cadence.label}")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setColor(tierColor)
            .setColorized(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setWhen(startTimeMillis)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setContentIntent(pendingIntent)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopIntent)
            .addAction(android.R.drawable.ic_menu_rotate, "New Session", newSessionIntent)

        // Android 16 / 17 Live Updates (Promoted Ongoing Notification)
        // Elevates the ongoing notification into a status bar chip, lock screen prominence, and uncollapsible priority
        if (Build.VERSION.SDK_INT >= 36) {
            builder.extras.putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true)
        }

        return builder.build()
    }
}
