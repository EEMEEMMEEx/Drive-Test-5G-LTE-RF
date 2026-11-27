package com.drivetest.lte5g.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
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
    }

    private val fusedClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private val _currentLocation = MutableStateFlow<Location?>(null)
    val currentLocation: StateFlow<Location?> = _currentLocation.asStateFlow()

    @Volatile
    private var isTracking = false
    @Volatile
    private var lastFixElapsedRealtimeMs: Long? = null

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            if (location.hasAccuracy() && location.accuracy > maxAccuracyThresholdMeters) {
                SessionDiagnostics.incDroppedInaccurateGps()
                Log.d(TAG, "Discarded inaccurate GPS point: ${location.accuracy}m > ${maxAccuracyThresholdMeters}m")
                return
            }
            lastFixElapsedRealtimeMs = SystemClock.elapsedRealtime()
            _currentLocation.value = location
        }
    }

    fun lastFixAgeMs(): Long? {
        val last = lastFixElapsedRealtimeMs ?: return null
        return maxOf(0L, SystemClock.elapsedRealtime() - last)
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
                    isTracking = false
                    Log.e(TAG, "Async requestLocationUpdates failed", err)
                }
            Log.d(TAG, "Started location tracking with interval ${intervalMs}ms")
        } catch (e: Exception) {
            isTracking = false
            Log.e(TAG, "Failed to start location updates", e)
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
                    isTracking = false
                    Log.e(TAG, "Async updateInterval requestLocationUpdates failed", err)
                }
            Log.d(TAG, "Updated location tracking interval to ${intervalMs}ms")
        } catch (e: Exception) {
            isTracking = false
            Log.e(TAG, "Failed to update location interval", e)
        }
    }

    fun stopTracking() {
        if (!isTracking) {
            _currentLocation.value = null
            lastFixElapsedRealtimeMs = null
            return
        }
        try {
            fusedClient.removeLocationUpdates(locationCallback)
            Log.d(TAG, "Stopped location tracking")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop location updates", e)
        } finally {
            isTracking = false
            _currentLocation.value = null
            lastFixElapsedRealtimeMs = null
        }
    }
}
