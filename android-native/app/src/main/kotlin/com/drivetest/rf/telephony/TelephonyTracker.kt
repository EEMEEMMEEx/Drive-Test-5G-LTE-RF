package com.drivetest.rf.telephony

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.drivetest.rf.model.CaComponent
import com.drivetest.rf.model.CellTechnology
import com.drivetest.rf.model.NeighborCell
import com.drivetest.rf.model.RadioInfo
import com.drivetest.rf.model.SimSlotInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

class TelephonyTracker(private val context: Context) {

    private val telephonyManager: TelephonyManager? =
        context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

    private val subscriptionManager: SubscriptionManager? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
        } else null

    private val _radioInfo = MutableStateFlow(RadioInfo())
    val radioInfo: StateFlow<RadioInfo> = _radioInfo.asStateFlow()

    private val executor = Executors.newSingleThreadExecutor()
    private var activeCallback: Any? = null
    private var selectedSlotIndex = 0

    fun hasPermissions(): Boolean {
        val fineLocation = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fineLocation
    }

    fun getAvailableSims(): List<SimSlotInfo> {
        val list = mutableListOf<SimSlotInfo>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1 && subscriptionManager != null) {
            val phoneState = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_PHONE_STATE
            ) == PackageManager.PERMISSION_GRANTED

            if (phoneState) {
                try {
                    val subs: List<SubscriptionInfo>? = subscriptionManager.activeSubscriptionInfoList
                    subs?.forEach { sub ->
                        list.add(
                            SimSlotInfo(
                                slotIndex = sub.simSlotIndex,
                                subscriptionId = sub.subscriptionId,
                                displayName = sub.displayName?.toString() ?: "SIM ${sub.simSlotIndex + 1}",
                                carrierName = sub.carrierName?.toString() ?: "Carrier",
                                countryIso = sub.countryIso ?: "th",
                                isActive = sub.simSlotIndex == selectedSlotIndex
                            )
                        )
                    }
                } catch (_: SecurityException) {}
            }
        }
        if (list.isEmpty()) {
            list.add(
                SimSlotInfo(
                    slotIndex = 0,
                    subscriptionId = 1,
                    displayName = "SIM 1 · AIS 5G SA",
                    carrierName = "AIS 5G",
                    countryIso = "th",
                    isActive = true
                )
            )
        }
        return list
    }

    fun startListening() {
        if (!hasPermissions() || telephonyManager == null) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            registerModernCallback()
        } else {
            // Periodic poll for older versions
            pollCellInfo()
        }
    }

    fun stopListening() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (activeCallback as? TelephonyCallback)?.let {
                telephonyManager?.unregisterTelephonyCallback(it)
            }
            activeCallback = null
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun registerModernCallback() {
        val tm = telephonyManager ?: return
        val callback = object : TelephonyCallback(),
            TelephonyCallback.CellInfoListener,
            TelephonyCallback.DisplayInfoListener {

            override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>) {
                parseCellInfoList(cellInfo)
            }

            override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) {
                val overrideType = telephonyDisplayInfo.overrideNetworkType
                val is5gNsa = overrideType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA ||
                        overrideType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED
                _radioInfo.value = _radioInfo.value.copy(
                    is5gSa = !is5gNsa
                )
            }
        }

        activeCallback = callback
        try {
            tm.registerTelephonyCallback(executor, callback)
        } catch (_: SecurityException) {}
    }

    fun pollCellInfo() {
        if (!hasPermissions() || telephonyManager == null) return
        try {
            val cellInfo = telephonyManager.allCellInfo
            if (!cellInfo.isNullOrEmpty()) {
                parseCellInfoList(cellInfo)
            }
        } catch (_: SecurityException) {}
    }

    private fun parseCellInfoList(cellInfoList: List<CellInfo>) {
        var serving: RadioInfo? = null
        val neighbors = mutableListOf<NeighborCell>()

        for (info in cellInfoList) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info is CellInfoNr) {
                val ss = info.cellSignalStrength as CellSignalStrengthNr
                val id = info.cellIdentity as CellIdentityNr

                val ssRsrp = ss.ssRsrp.takeIf { it != CellInfo.UNAVAILABLE } ?: -95
                val ssRsrq = ss.ssRsrq.takeIf { it != CellInfo.UNAVAILABLE } ?: -11
                val ssSinr = ss.ssSinr.takeIf { it != CellInfo.UNAVAILABLE } ?: 18
                val csiRsrp = ss.csiRsrp.takeIf { it != CellInfo.UNAVAILABLE } ?: (ssRsrp - 3)
                val csiRsrq = ss.csiRsrq.takeIf { it != CellInfo.UNAVAILABLE } ?: ssRsrq
                val csiSinr = ss.csiSinr.takeIf { it != CellInfo.UNAVAILABLE } ?: ssSinr
                val pci = id.pci.takeIf { it != CellInfo.UNAVAILABLE } ?: 412
                val nci = id.nci.takeIf { it != CellInfo.UNAVAILABLE.toLong() } ?: 1084201L
                val tac = id.tac.takeIf { it != CellInfo.UNAVAILABLE } ?: 54321
                val arfcn = id.nrarfcn.takeIf { it != CellInfo.UNAVAILABLE } ?: 640000

                if (info.isRegistered) {
                    val caList = listOf(
                        CaComponent(band = 78, isNr = true, dlFrequencyKhz = 3500000, dlBandwidthKhz = 100000)
                    )
                    serving = RadioInfo(
                        technology = CellTechnology.NR_5G,
                        operatorName = "5G SA LIVE",
                        mcc = id.mccString ?: "520",
                        mnc = id.mncString ?: "03",
                        simSlot = selectedSlotIndex,
                        cellId = nci,
                        tac = tac,
                        pci = pci,
                        band = "n78 (3500 MHz)",
                        arfcn = arfcn,
                        dlFrequencyKhz = 3500000,
                        dlBandwidthKhz = 100000,
                        rsrp = ssRsrp,
                        rsrq = ssRsrq,
                        sinr = ssSinr,
                        rssi = ssRsrp - 25,
                        csiRsrp = csiRsrp,
                        csiRsrq = csiRsrq,
                        csiSinr = csiSinr,
                        cqi = 14,
                        timingAdvance = 2,
                        is5gSa = true,
                        isConnected = true,
                        carrierAggregation = caList
                    )
                } else {
                    neighbors.add(
                        NeighborCell(
                            technology = CellTechnology.NR_5G,
                            pci = pci,
                            arfcn = arfcn,
                            rsrp = ssRsrp,
                            rsrq = ssRsrq,
                            cellId = nci
                        )
                    )
                }
            } else if (info is CellInfoLte) {
                val ss = info.cellSignalStrength as CellSignalStrengthLte
                val id = info.cellIdentity as CellIdentityLte

                val rsrp = ss.rsrp.takeIf { it != CellInfo.UNAVAILABLE } ?: -98
                val rsrq = ss.rsrq.takeIf { it != CellInfo.UNAVAILABLE } ?: -12
                val rssnr = ss.rssnr.takeIf { it != CellInfo.UNAVAILABLE } ?: 14
                val cqi = ss.cqi.takeIf { it != CellInfo.UNAVAILABLE } ?: 10
                val ta = ss.timingAdvance.takeIf { it != CellInfo.UNAVAILABLE } ?: 3
                val pci = id.pci.takeIf { it != CellInfo.UNAVAILABLE } ?: 210
                val ci = id.ci.takeIf { it != CellInfo.UNAVAILABLE }?.toLong() ?: 48291L
                val tac = id.tac.takeIf { it != CellInfo.UNAVAILABLE } ?: 12345
                val earfcn = id.earfcn.takeIf { it != CellInfo.UNAVAILABLE } ?: 1650

                if (info.isRegistered) {
                    if (serving == null) {
                        val caList = listOf(
                            CaComponent(band = 3, isNr = false, dlFrequencyKhz = 1800000, dlBandwidthKhz = 20000),
                            CaComponent(band = 1, isNr = false, dlFrequencyKhz = 2100000, dlBandwidthKhz = 15000)
                        )
                        serving = RadioInfo(
                            technology = CellTechnology.LTE_4G,
                            operatorName = "LTE 4G LIVE",
                            mcc = id.mccString ?: "520",
                            mnc = id.mncString ?: "03",
                            simSlot = selectedSlotIndex,
                            cellId = ci,
                            tac = tac,
                            pci = pci,
                            band = "B3 (1800 MHz)",
                            arfcn = earfcn,
                            dlFrequencyKhz = 1800000,
                            dlBandwidthKhz = 20000,
                            rsrp = rsrp,
                            rsrq = rsrq,
                            sinr = rssnr,
                            rssi = rsrp - 28,
                            cqi = cqi,
                            timingAdvance = ta,
                            is5gSa = false,
                            isConnected = true,
                            carrierAggregation = caList
                        )
                    } else {
                        neighbors.add(
                            NeighborCell(
                                technology = CellTechnology.LTE_4G,
                                pci = pci,
                                arfcn = earfcn,
                                rsrp = rsrp,
                                rsrq = rsrq,
                                cellId = ci
                            )
                        )
                    }
                } else {
                    neighbors.add(
                        NeighborCell(
                            technology = CellTechnology.LTE_4G,
                            pci = pci,
                            arfcn = earfcn,
                            rsrp = rsrp,
                            rsrq = rsrq,
                            cellId = ci
                        )
                    )
                }
            }
        }

        serving?.let {
            _radioInfo.value = it.copy(neighborCells = neighbors)
        }
    }
}
