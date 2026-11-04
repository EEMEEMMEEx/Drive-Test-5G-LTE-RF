package com.drivetest.lte5g.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import android.util.Log
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
    private val fusedClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private val _currentLocation = MutableStateFlow<Location?>(null)
    val currentLocation: StateFlow<Location?> = _currentLocation.asStateFlow()

    private var isTracking = false

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            if (location.hasAccuracy() && location.accuracy > maxAccuracyThresholdMeters) {
                Log.d("LocationCollector", "Discarded inaccurate GPS point: ${location.accuracy}m > ${maxAccuracyThresholdMeters}m")
                return
            }
            _currentLocation.value = location
        }
    }

    @SuppressLint("MissingPermission")
    fun startTracking(intervalMs: Long = 1000L) {
        if (isTracking) return
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .setMinUpdateDistanceMeters(0f)
            .build()

        try {
            fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
            isTracking = true
            Log.d("LocationCollector", "Started location tracking with interval ${intervalMs}ms")
        } catch (e: Exception) {
            Log.e("LocationCollector", "Failed to start location updates", e)
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
            Log.d("LocationCollector", "Updated location tracking interval to ${intervalMs}ms")
        } catch (e: Exception) {
            Log.e("LocationCollector", "Failed to update location interval", e)
        }
    }

    fun stopTracking() {
        if (!isTracking) return
        try {
            fusedClient.removeLocationUpdates(locationCallback)
            isTracking = false
            Log.d("LocationCollector", "Stopped location tracking")
        } catch (e: Exception) {
            Log.e("LocationCollector", "Failed to stop location updates", e)
        }
    }
}
