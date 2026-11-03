package com.drivetest.rf.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.drivetest.rf.model.GpsInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class GpsTracker(private val context: Context) {

    private val locationManager: LocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private val _gpsInfo = MutableStateFlow(GpsInfo())
    val gpsInfo: StateFlow<GpsInfo> = _gpsInfo.asStateFlow()

    private var satelliteCount = 0

    private val locationListener = LocationListener { location ->
        updateLocation(location)
    }

    private val gnssCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                var usedInFix = 0
                for (i in 0 until status.satelliteCount) {
                    if (status.usedInFix(i)) {
                        usedInFix++
                    }
                }
                satelliteCount = usedInFix
                _gpsInfo.value = _gpsInfo.value.copy(satellites = usedInFix)
            }
        }
    } else null

    fun hasPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun startTracking() {
        if (!hasPermissions() || locationManager == null) return

        try {
            val lastGps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val lastNet = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            val initialLoc = when {
                lastGps != null && lastNet != null -> if (lastGps.time >= lastNet.time) lastGps else lastNet
                lastGps != null -> lastGps
                else -> lastNet
            }
            if (initialLoc != null) {
                updateLocation(initialLoc)
            }

            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000L,
                    0f,
                    locationListener
                )
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    2000L,
                    0f,
                    locationListener
                )
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && gnssCallback != null) {
                locationManager.registerGnssStatusCallback(context.mainExecutor, gnssCallback)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && gnssCallback != null) {
                @Suppress("DEPRECATION")
                locationManager.registerGnssStatusCallback(gnssCallback)
            }
        } catch (_: SecurityException) {}
    }

    fun stopTracking() {
        try {
            locationManager?.removeUpdates(locationListener)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && gnssCallback != null) {
                locationManager?.unregisterGnssStatusCallback(gnssCallback)
            }
        } catch (_: Exception) {}
    }

    private fun updateLocation(loc: Location) {
        _gpsInfo.value = GpsInfo(
            latitude = loc.latitude,
            longitude = loc.longitude,
            altitude = loc.altitude,
            speedKmh = loc.speed * 3.6f,
            bearing = loc.bearing,
            accuracy = loc.accuracy,
            satellites = satelliteCount,
            timestampMs = loc.time,
            isValid = true
        )
    }
}
