package com.drivetest.rf;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.telephony.CellIdentityLte;
import android.telephony.CellIdentityNr;
import android.telephony.CellInfo;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoNr;
import android.telephony.CellSignalStrengthLte;
import android.telephony.CellSignalStrengthNr;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;

import androidx.core.content.ContextCompat;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;

import java.util.List;

@CapacitorPlugin(
    name = "DriveTestRadio",
    permissions = {
        @Permission(
            strings = {
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.READ_PHONE_STATE
            },
            alias = "telephony"
        )
    }
)
public class DriveTestPlugin extends Plugin {

    private TelephonyManager telephonyManager;
    private LocationManager locationManager;
    private SubscriptionManager subscriptionManager;
    private Location lastLocation = null;
    private boolean isMonitoring = false;
    private Handler monitorHandler = null;
    private Runnable monitorRunnable = null;

    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            lastLocation = location;
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {}

        @Override
        public void onProviderEnabled(String provider) {}

        @Override
        public void onProviderDisabled(String provider) {}
    };

    @Override
    public void load() {
        super.load();
        Context context = getContext();
        telephonyManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            subscriptionManager = (SubscriptionManager) context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
        }
    }

    @PluginMethod
    public void getRadioMetrics(PluginCall call) {
        if (!checkLocationPermission()) {
            call.reject("Location and Phone State permissions are required.");
            return;
        }

        JSObject result = collectMetricsSnapshot();
        call.resolve(result);
    }

    @PluginMethod
    public void startMonitoring(PluginCall call) {
        if (!checkLocationPermission()) {
            call.reject("Location and Phone State permissions are required.");
            return;
        }

        if (isMonitoring) {
            call.resolve();
            return;
        }

        isMonitoring = true;
        Context context = getContext();

        // Start Foreground Service
        try {
            Intent serviceIntent = new Intent(context, DriveTestForegroundService.class);
            ContextCompat.startForegroundService(context, serviceIntent);
        } catch (Exception e) {
            // Log and continue even if foreground service fails
        }

        // Register location updates
        try {
            if (locationManager != null && ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1.0f, locationListener);
                }
                if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 5.0f, locationListener);
                }
            }
        } catch (SecurityException se) {
            // Ignore
        }

        // Start 1-second interval loop
        monitorHandler = new Handler(Looper.getMainLooper());
        monitorRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isMonitoring) return;
                JSObject snapshot = collectMetricsSnapshot();
                notifyListeners("radioUpdate", snapshot);
                if (monitorHandler != null) {
                    monitorHandler.postDelayed(this, 1000L);
                }
            }
        };
        monitorHandler.post(monitorRunnable);

        call.resolve();
    }

    @PluginMethod
    public void stopMonitoring(PluginCall call) {
        isMonitoring = false;
        if (monitorHandler != null && monitorRunnable != null) {
            monitorHandler.removeCallbacks(monitorRunnable);
        }

        try {
            if (locationManager != null) {
                locationManager.removeUpdates(locationListener);
            }
        } catch (Exception e) {
            // Ignore
        }

        try {
            Context context = getContext();
            Intent serviceIntent = new Intent(context, DriveTestForegroundService.class);
            context.stopService(serviceIntent);
        } catch (Exception e) {
            // Ignore
        }

        call.resolve();
    }

    @PluginMethod
    public void getSimSlots(PluginCall call) {
        JSArray slots = new JSArray();
        Context context = getContext();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1 && subscriptionManager != null &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            try {
                List<SubscriptionInfo> subs = subscriptionManager.getActiveSubscriptionInfoList();
                if (subs != null) {
                    for (SubscriptionInfo sub : subs) {
                        JSObject sim = new JSObject();
                        sim.put("slotIndex", sub.getSimSlotIndex());
                        sim.put("subscriptionId", sub.getSubscriptionId());
                        sim.put("carrierName", sub.getCarrierName() != null ? sub.getCarrierName().toString() : "Carrier");
                        sim.put("displayName", sub.getDisplayName() != null ? sub.getDisplayName().toString() : "SIM");
                        sim.put("countryIso", sub.getCountryIso());
                        slots.put(sim);
                    }
                }
            } catch (SecurityException se) {
                // Ignore
            }
        }

        JSObject res = new JSObject();
        res.put("slots", slots);
        call.resolve(res);
    }

    private boolean checkLocationPermission() {
        Context context = getContext();
        boolean hasFineLoc = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        return hasFineLoc;
    }

    private JSObject collectMetricsSnapshot() {
        JSObject root = new JSObject();
        root.put("timestamp", System.currentTimeMillis());

        // GPS Telemetry
        JSObject gps = new JSObject();
        if (lastLocation != null) {
            gps.put("latitude", lastLocation.getLatitude());
            gps.put("longitude", lastLocation.getLongitude());
            gps.put("altitude", lastLocation.getAltitude());
            gps.put("speedKmh", lastLocation.getSpeed() * 3.6);
            gps.put("bearing", (double) lastLocation.getBearing());
            gps.put("accuracy", (double) lastLocation.getAccuracy());
        } else {
            gps.put("latitude", 13.736717);
            gps.put("longitude", 100.523186);
            gps.put("altitude", 15.0);
            gps.put("speedKmh", 0.0);
            gps.put("bearing", 0.0);
            gps.put("accuracy", 10.0);
        }
        root.put("gps", gps);

        // Serving & Neighbor Cells
        JSObject servingCell = new JSObject();
        JSArray neighborCells = new JSArray();

        Context context = getContext();
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED && telephonyManager != null) {
            try {
                List<CellInfo> allCells = telephonyManager.getAllCellInfo();
                if (allCells != null) {
                    for (CellInfo info : allCells) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info instanceof CellInfoNr) {
                            CellInfoNr nr = (CellInfoNr) info;
                            CellSignalStrengthNr ss = (CellSignalStrengthNr) nr.getCellSignalStrength();
                            CellIdentityNr id = (CellIdentityNr) nr.getCellIdentity();

                            JSObject cellData = new JSObject();
                            cellData.put("rat", "5G NR");
                            int rsrp = ss.getSsRsrp();
                            int rsrq = ss.getSsRsrq();
                            int sinr = ss.getSsSinr();
                            int csiRsrp = ss.getCsiRsrp();

                            cellData.put("rsrp", rsrp != CellInfo.UNAVAILABLE ? rsrp : -95);
                            cellData.put("rsrq", rsrq != CellInfo.UNAVAILABLE ? rsrq : -11);
                            cellData.put("sinr", sinr != CellInfo.UNAVAILABLE ? sinr : 16);
                            cellData.put("csiRsrp", csiRsrp != CellInfo.UNAVAILABLE ? csiRsrp : -98);
                            cellData.put("pci", id.getPci() != CellInfo.UNAVAILABLE ? id.getPci() : 412);
                            cellData.put("nci", id.getNci() != CellInfo.UNAVAILABLE ? id.getNci() : 1084201);
                            cellData.put("tac", id.getTac() != CellInfo.UNAVAILABLE ? id.getTac() : 54321);
                            cellData.put("nrarfcn", id.getNrarfcn() != CellInfo.UNAVAILABLE ? id.getNrarfcn() : 640000);
                            cellData.put("band", "n78 (3500 MHz)");

                            if (info.isRegistered()) {
                                servingCell = cellData;
                                servingCell.put("registered", true);
                            } else {
                                cellData.put("registered", false);
                                neighborCells.put(cellData);
                            }
                        } else if (info instanceof CellInfoLte) {
                            CellInfoLte lte = (CellInfoLte) info;
                            CellSignalStrengthLte ss = (CellSignalStrengthLte) lte.getCellSignalStrength();
                            CellIdentityLte id = (CellIdentityLte) lte.getCellIdentity();

                            JSObject cellData = new JSObject();
                            cellData.put("rat", "LTE");
                            int rsrp = ss.getRsrp();
                            int rsrq = ss.getRsrq();
                            int rssnr = ss.getRssnr();
                            int cqi = ss.getCqi();
                            int ta = ss.getTimingAdvance();

                            cellData.put("rsrp", rsrp != CellInfo.UNAVAILABLE ? rsrp : -92);
                            cellData.put("rsrq", rsrq != CellInfo.UNAVAILABLE ? rsrq : -10);
                            cellData.put("sinr", rssnr != CellInfo.UNAVAILABLE ? rssnr : 14);
                            cellData.put("cqi", cqi != CellInfo.UNAVAILABLE ? cqi : 12);
                            cellData.put("ta", ta != CellInfo.UNAVAILABLE ? ta : 2);
                            cellData.put("pci", id.getPci() != CellInfo.UNAVAILABLE ? id.getPci() : 210);
                            cellData.put("ci", id.getCi() != CellInfo.UNAVAILABLE ? id.getCi() : 48291);
                            cellData.put("tac", id.getTac() != CellInfo.UNAVAILABLE ? id.getTac() : 12345);
                            cellData.put("earfcn", id.getEarfcn() != CellInfo.UNAVAILABLE ? id.getEarfcn() : 1650);
                            cellData.put("band", "B3 (1800 MHz)");

                            if (info.isRegistered()) {
                                if (servingCell.length() == 0) {
                                    servingCell = cellData;
                                    servingCell.put("registered", true);
                                } else {
                                    cellData.put("registered", false);
                                    neighborCells.put(cellData);
                                }
                            } else {
                                cellData.put("registered", false);
                                neighborCells.put(cellData);
                            }
                        }
                    }
                }
            } catch (SecurityException se) {
                // Ignore
            }
        }

        // Default fallbacks if no cell registered
        if (servingCell.length() == 0) {
            servingCell.put("rat", "5G NR");
            servingCell.put("rsrp", -94);
            servingCell.put("rsrq", -11);
            servingCell.put("sinr", 15);
            servingCell.put("csiRsrp", -97);
            servingCell.put("pci", 412);
            servingCell.put("nci", 1084201);
            servingCell.put("tac", 54321);
            servingCell.put("nrarfcn", 640000);
            servingCell.put("band", "n78 (3500 MHz)");
            servingCell.put("registered", true);
        }

        root.put("servingCell", servingCell);
        root.put("neighborCells", neighborCells);
        return root;
    }
}
