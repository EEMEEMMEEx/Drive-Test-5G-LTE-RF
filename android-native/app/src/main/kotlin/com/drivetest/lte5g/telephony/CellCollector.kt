package com.drivetest.lte5g.telephony

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.PhoneStateListener
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import android.util.Log
import com.drivetest.lte5g.model.CellMetrics
import com.drivetest.lte5g.model.LteMetrics
import com.drivetest.lte5g.model.NeighborCell
import com.drivetest.lte5g.model.NrMetrics
import com.drivetest.lte5g.model.RatLockMode
import com.drivetest.lte5g.model.RatType
import com.drivetest.lte5g.model.SimSlotInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

class CellCollector(private val context: Context) {

    private val baseTelephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private var activeTelephonyManager: TelephonyManager = baseTelephonyManager
    private val executor = Executors.newSingleThreadExecutor()

    private val _currentMetrics = MutableStateFlow(CellMetrics())
    val currentMetrics: StateFlow<CellMetrics> = _currentMetrics.asStateFlow()

    var currentSlotIndex: Int = 0
        private set
    var currentSubId: Int? = null
        private set
    var currentSimName: String? = null
        private set

    private var isNsaOverride = false
    private var callback31: Any? = null
    private var legacyListener: PhoneStateListener? = null

    init {
        // Initialize with default subscription if available
        val subs = getActiveSubscriptions(context)
        val defaultSub = subs.firstOrNull { it.isDefault } ?: subs.firstOrNull()
        if (defaultSub != null) {
            currentSlotIndex = defaultSub.slotIndex
            currentSubId = defaultSub.subscriptionId
            currentSimName = defaultSub.displayName
            if (defaultSub.subscriptionId != -1) {
                activeTelephonyManager = baseTelephonyManager.createForSubscriptionId(defaultSub.subscriptionId)
            }
        }
        registerTelephonyListeners()
    }

    private fun sanitizeInt(value: Int): Int? {
        return if (value == CellInfo.UNAVAILABLE || value == Int.MAX_VALUE || value < -150) null else value
    }

    private fun sanitizeLong(value: Long): Long? {
        return if (value == CellInfo.UNAVAILABLE_LONG || value == Long.MAX_VALUE || value < 0) null else value
    }

    private fun sanitizeDouble(value: Int): Double? {
        val sanitized = sanitizeInt(value) ?: return null
        return sanitized.toDouble()
    }

    fun switchSim(slotIndex: Int, subId: Int?, simName: String? = null) {
        if (slotIndex == currentSlotIndex && subId == currentSubId && activeTelephonyManager != baseTelephonyManager) {
            return
        }
        Log.d("CellCollector", "Switching SIM slot to $slotIndex (subId=$subId, name=$simName)")
        unregisterTelephonyListeners()

        currentSlotIndex = slotIndex
        currentSubId = subId
        currentSimName = simName

        activeTelephonyManager = if (subId != null && subId != -1) {
            baseTelephonyManager.createForSubscriptionId(subId)
        } else {
            baseTelephonyManager
        }

        registerTelephonyListeners()
        pollCellInfo()
    }

    @SuppressLint("MissingPermission")
    private fun registerTelephonyListeners() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val cb = object : TelephonyCallback(),
                TelephonyCallback.DisplayInfoListener,
                TelephonyCallback.CellInfoListener {

                override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) {
                    val override = telephonyDisplayInfo.overrideNetworkType
                    isNsaOverride = (override == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA ||
                            override == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED)
                    Log.d("CellCollector", "DisplayInfo changed: isNsaOverride=$isNsaOverride (override=$override)")
                    pollCellInfo()
                }

                override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>) {
                    processCellInfoList(cellInfo)
                }
            }
            callback31 = cb
            try {
                activeTelephonyManager.registerTelephonyCallback(executor, cb)
            } catch (e: Exception) {
                Log.e("CellCollector", "Error registering TelephonyCallback on slot $currentSlotIndex", e)
            }
        } else {
            val listener = object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>?) {
                    processCellInfoList(cellInfo ?: emptyList())
                }

                @Deprecated("Deprecated in Java")
                override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val override = telephonyDisplayInfo.overrideNetworkType
                        isNsaOverride = (override == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA ||
                                override == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED)
                        pollCellInfo()
                    }
                }
            }
            legacyListener = listener
            @Suppress("DEPRECATION")
            try {
                activeTelephonyManager.listen(listener, PhoneStateListener.LISTEN_CELL_INFO)
            } catch (e: Exception) {
                Log.e("CellCollector", "Error registering PhoneStateListener on slot $currentSlotIndex", e)
            }
        }
    }

    private fun unregisterTelephonyListeners() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && callback31 != null) {
                activeTelephonyManager.unregisterTelephonyCallback(callback31 as TelephonyCallback)
                callback31 = null
            } else if (legacyListener != null) {
                @Suppress("DEPRECATION")
                activeTelephonyManager.listen(legacyListener, PhoneStateListener.LISTEN_NONE)
                legacyListener = null
            }
        } catch (e: Exception) {
            Log.e("CellCollector", "Error unregistering telephony listeners", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun pollCellInfo() {
        try {
            activeTelephonyManager.requestCellInfoUpdate(executor, object : TelephonyManager.CellInfoCallback() {
                override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                    processCellInfoList(cellInfo)
                }

                override fun onError(errorCode: Int, detail: Throwable?) {
                    Log.w("CellCollector", "requestCellInfoUpdate error code: $errorCode", detail)
                    // Fallback to cached cell info if update is throttled
                    try {
                        val cached = activeTelephonyManager.allCellInfo
                        if (!cached.isNullOrEmpty()) {
                            processCellInfoList(cached)
                        }
                    } catch (e: Exception) {
                        Log.e("CellCollector", "Failed getting cached allCellInfo", e)
                    }
                }
            })
        } catch (e: Exception) {
            Log.e("CellCollector", "Failed requestCellInfoUpdate", e)
        }
    }

    private fun processCellInfoList(cells: List<CellInfo>) {
        if (cells.isEmpty()) return

        var servingLte: LteMetrics? = null
        var servingNr: NrMetrics? = null
        val neighbors = mutableListOf<NeighborCell>()
        var plmn: String? = activeTelephonyManager.networkOperator.takeIf { it.isNotBlank() }
        val operatorAlpha = activeTelephonyManager.networkOperatorName.takeIf { it.isNotBlank() }

        for (info in cells) {
            if (info is CellInfoLte) {
                val id = info.cellIdentity
                val ss = info.cellSignalStrength
                val pci = sanitizeInt(id.pci)
                val eci = sanitizeLong(id.ci.toLong())
                val tac = sanitizeInt(id.tac)
                val earfcn = sanitizeInt(id.earfcn)
                val band = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && id.bands.isNotEmpty()) id.bands[0] else null
                val rsrp = sanitizeInt(ss.rsrp)
                val rsrq = sanitizeInt(ss.rsrq)
                val rssnr = sanitizeDouble(ss.rssnr)
                val cqi = sanitizeInt(ss.cqi)

                if (plmn.isNullOrBlank()) {
                    val mcc = id.mccString ?: ""
                    val mnc = id.mncString ?: ""
                    if (mcc.isNotBlank() || mnc.isNotBlank()) {
                        plmn = "$mcc-$mnc"
                    }
                }

                if (info.isRegistered) {
                    servingLte = LteMetrics(
                        pci = pci,
                        eci = eci,
                        tac = tac,
                        earfcn = earfcn,
                        band = band,
                        rsrp = rsrp,
                        rsrq = rsrq,
                        rssnr = rssnr,
                        cqi = cqi
                    )
                } else {
                    neighbors.add(
                        NeighborCell(
                            rat = RatType.LTE,
                            pci = pci,
                            arfcn = earfcn,
                            rsrp = rsrp,
                            rsrq = rsrq,
                            sinr = rssnr
                        )
                    )
                }
            } else if (info is CellInfoNr) {
                val id = info.cellIdentity as? CellIdentityNr
                val ss = info.cellSignalStrength as? CellSignalStrengthNr
                if (id != null && ss != null) {
                    val pci = sanitizeInt(id.pci)
                    val nci = sanitizeLong(id.nci)
                    val tac = sanitizeInt(id.tac)
                    val nrArfcn = sanitizeInt(id.nrarfcn)
                    val band = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && id.bands.isNotEmpty()) id.bands[0] else null
                    val ssRsrp = sanitizeInt(ss.ssRsrp)
                    val ssRsrq = sanitizeInt(ss.ssRsrq)
                    val ssSinr = sanitizeDouble(ss.ssSinr)
                    val csiRsrp = sanitizeInt(ss.csiRsrp)
                    val csiRsrq = sanitizeInt(ss.csiRsrq)
                    val csiSinr = sanitizeDouble(ss.csiSinr)

                    if (plmn.isNullOrBlank()) {
                        val mcc = id.mccString ?: ""
                        val mnc = id.mncString ?: ""
                        if (mcc.isNotBlank() || mnc.isNotBlank()) {
                            plmn = "$mcc-$mnc"
                        }
                    }

                    if (info.isRegistered) {
                        servingNr = NrMetrics(
                            pci = pci,
                            nci = nci,
                            tac = tac,
                            nrArfcn = nrArfcn,
                            band = band,
                            ssRsrp = ssRsrp,
                            ssRsrq = ssRsrq,
                            ssSinr = ssSinr,
                            csiRsrp = csiRsrp,
                            csiRsrq = csiRsrq,
                            csiSinr = csiSinr,
                            isNsa = isNsaOverride
                        )
                    } else {
                        neighbors.add(
                            NeighborCell(
                                rat = RatType.NR_SA,
                                pci = pci,
                                arfcn = nrArfcn,
                                rsrp = ssRsrp,
                                rsrq = ssRsrq,
                                sinr = ssSinr
                            )
                        )
                    }
                }
            }
        }

        val determinedRat = when {
            servingNr != null && !servingNr.isNsa -> RatType.NR_SA
            servingNr != null && servingNr.isNsa -> RatType.NR_NSA
            isNsaOverride && servingLte != null -> RatType.NR_NSA
            servingLte != null -> RatType.LTE
            else -> RatType.UNKNOWN
        }

        _currentMetrics.value = CellMetrics(
            ratType = determinedRat,
            plmn = plmn,
            operatorAlpha = operatorAlpha,
            lte = servingLte,
            nr = servingNr,
            neighbors = neighbors,
            selectedSimSlot = currentSlotIndex,
            simDisplayName = currentSimName
        )
    }

    fun stop() {
        unregisterTelephonyListeners()
        executor.shutdown()
    }

    companion object {
        @SuppressLint("MissingPermission")
        fun getActiveSubscriptions(context: Context): List<SimSlotInfo> {
            val subManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
                ?: return listOf(SimSlotInfo(0, -1, "SIM 1", "", isDefault = true))
            return try {
                val list = subManager.activeSubscriptionInfoList
                if (list.isNullOrEmpty()) {
                    listOf(SimSlotInfo(0, -1, "SIM 1", "", isDefault = true))
                } else {
                    val defaultSubId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        SubscriptionManager.getDefaultDataSubscriptionId()
                    } else {
                        -1
                    }
                    list.map { subInfo ->
                        val carrier = subInfo.carrierName?.toString()?.trim() ?: ""
                        val display = subInfo.displayName?.toString()?.trim() ?: ""
                        val name = if (carrier.isNotBlank() && display.isNotBlank() && carrier != display) {
                            "SIM ${subInfo.simSlotIndex + 1}: $carrier ($display)"
                        } else if (carrier.isNotBlank()) {
                            "SIM ${subInfo.simSlotIndex + 1}: $carrier"
                        } else if (display.isNotBlank()) {
                            "SIM ${subInfo.simSlotIndex + 1}: $display"
                        } else {
                            "SIM ${subInfo.simSlotIndex + 1}"
                        }
                        SimSlotInfo(
                            slotIndex = subInfo.simSlotIndex,
                            subscriptionId = subInfo.subscriptionId,
                            displayName = name,
                            carrierName = carrier,
                            isDefault = (subInfo.subscriptionId == defaultSubId)
                        )
                    }.sortedBy { it.slotIndex }
                }
            } catch (e: Exception) {
                Log.e("CellCollector", "Failed getting subscriptions", e)
                listOf(SimSlotInfo(0, -1, "SIM 1", "", isDefault = true))
            }
        }

        /**
         * Launch Android RadioInfo engineering mode (*#*#4636#*#*)
         * Allows selecting "LTE only" or "NR only" directly in the modem baseband
         */
        fun launchRadioInfo(context: Context): Boolean {
            return try {
                val intent = Intent(Intent.ACTION_MAIN).apply {
                    setClassName("com.android.settings", "com.android.settings.RadioInfo")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                true
            } catch (e: Exception) {
                Log.w("CellCollector", "Failed to launch RadioInfo activity directly", e)
                try {
                    val settingsIntent = Intent(android.provider.Settings.ACTION_DATA_ROAMING_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(settingsIntent)
                    true
                } catch (e2: Exception) {
                    Log.e("CellCollector", "Failed opening network settings", e2)
                    false
                }
            }
        }
    }
}
