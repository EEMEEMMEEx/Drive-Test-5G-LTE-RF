package com.drivetest.lte5g.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.drivetest.lte5g.data.SessionDiagnostics
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class LocationCollector(
    private val context: Context,
    private val maxAccuracyThresholdMeters: Float = 30f
) {
    companion object {
        private const val TAG = "LocationCollector"
        private const val LAST_LOCATION_MAX_AGE_MS = 60_000L
        const val STALE_GPS_THRESHOLD_MS = 30_000L
    }

    private val fusedClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)
    private val locationManager: LocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private val _currentLocation = MutableStateFlow<Location?>(null)
    val currentLocation: StateFlow<Location?> = _currentLocation.asStateFlow()

    private val _isGpsStale = MutableStateFlow(false)
    val isGpsStale: StateFlow<Boolean> = _isGpsStale.asStateFlow()

    @Volatile
    private var isTracking = false
    @Volatile
    private var isGpsFallbackRegistered = false
    @Volatile
    private var lastFixElapsedRealtimeMs: Long? = null

    private fun acceptLocation(location: Location) {
        if (location.hasAccuracy() && location.accuracy > maxAccuracyThresholdMeters) {
            SessionDiagnostics.incDroppedInaccurateGps()
            Log.d(TAG, "Discarded inaccurate GPS point: ${location.accuracy}m > ${maxAccuracyThresholdMeters}m")
            return
        }
        lastFixElapsedRealtimeMs = SystemClock.elapsedRealtime()
        _isGpsStale.value = false
        _currentLocation.value = location
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            acceptLocation(location)
        }
    }

    // Direct GNSS fallback for SIM-only / offline devices where FusedLocation NLP/AGPS may stall
    private val directGpsListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            acceptLocation(location)
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    fun lastFixAgeMs(): Long? {
        val last = lastFixElapsedRealtimeMs ?: return null
        return maxOf(0L, SystemClock.elapsedRealtime() - last)
    }

    /**
     * Returns true if a previous GPS fix exists and no new fix arrived within [thresholdMs] (default 30s).
     * Updates [isGpsStale] state flow so UI/diagnostics can surface the stale GPS flag.
     */
    fun isLocationStale(thresholdMs: Long = STALE_GPS_THRESHOLD_MS): Boolean {
        val age = lastFixAgeMs() ?: run {
            _isGpsStale.value = false
            return false
        }
        val stale = age > thresholdMs
        _isGpsStale.value = stale
        return stale
    }

    @SuppressLint("MissingPermission")
    fun startTracking(intervalMs: Long = 1000L) {
        if (isTracking) return

        // Seed with recent lastLocation so initial ticks don't drop while GNSS warms up
        try {
            fusedClient.lastLocation.addOnSuccessListener { lastLoc ->
                if (lastLoc != null && _currentLocation.value == null) {
                    val ageMs = System.currentTimeMillis() - lastLoc.time
                    val accurate = !lastLoc.hasAccuracy() || lastLoc.accuracy <= maxAccuracyThresholdMeters
                    if (accurate && ageMs in 0..LAST_LOCATION_MAX_AGE_MS) {
                        lastFixElapsedRealtimeMs = SystemClock.elapsedRealtime()
                        _isGpsStale.value = false
                        _currentLocation.value = lastLoc
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Unable to seed lastLocation: ${e.message}")
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .setMinUpdateDistanceMeters(0f)
            .build()

        try {
            isTracking = true
            fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
                .addOnFailureListener { err ->
                    Log.e(TAG, "Async requestLocationUpdates failed", err)
                }
            registerDirectGpsFallback(intervalMs)
            Log.d(TAG, "Started location tracking with interval ${intervalMs}ms")
        } catch (e: Exception) {
            isTracking = false
            Log.e(TAG, "Failed to start location updates", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerDirectGpsFallback(intervalMs: Long) {
        val lm = locationManager ?: return
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                if (isGpsFallbackRegistered) {
                    lm.removeUpdates(directGpsListener)
                }
                lm.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    intervalMs,
                    0f,
                    directGpsListener,
                    Looper.getMainLooper()
                )
                isGpsFallbackRegistered = true
            }
        } catch (e: Exception) {
            Log.d(TAG, "Direct GPS_PROVIDER fallback unavailable: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun updateInterval(intervalMs: Long) {
        if (!isTracking) {
            startTracking(intervalMs)
            return
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .setMinUpdateDistanceMeters(0f)
            .build()

        try {
            fusedClient.removeLocationUpdates(locationCallback)
            fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
                .addOnFailureListener { err ->
                    Log.e(TAG, "Async updateInterval requestLocationUpdates failed", err)
                }
            registerDirectGpsFallback(intervalMs)
            Log.d(TAG, "Updated location tracking interval to ${intervalMs}ms")
        } catch (e: Exception) {
            isTracking = false
            Log.e(TAG, "Failed to update location interval", e)
        }
    }

    fun stopTracking() {
        if (!isTracking) {
            _currentLocation.value = null
            _isGpsStale.value = false
            lastFixElapsedRealtimeMs = null
            return
        }
        try {
            fusedClient.removeLocationUpdates(locationCallback)
            if (isGpsFallbackRegistered) {
                locationManager?.removeUpdates(directGpsListener)
                isGpsFallbackRegistered = false
            }
            Log.d(TAG, "Stopped location tracking")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop location updates", e)
        } finally {
            isTracking = false
            _currentLocation.value = null
            _isGpsStale.value = false
            lastFixElapsedRealtimeMs = null
        }
    }
}
