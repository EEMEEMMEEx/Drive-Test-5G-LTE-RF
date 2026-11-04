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
import com.drivetest.lte5g.model.RatType
import com.drivetest.lte5g.model.SimSlotInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

class CellCollector(private val context: Context) {

    private val baseTelephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private var activeTelephonyManager: TelephonyManager = baseTelephonyManager
    private var secondaryTelephonyManager: TelephonyManager? = null
    private val executor = Executors.newSingleThreadExecutor()

    private val _currentMetrics = MutableStateFlow(CellMetrics())
    val currentMetrics: StateFlow<CellMetrics> = _currentMetrics.asStateFlow()

    private val _currentMetricsSim2 = MutableStateFlow<CellMetrics?>(null)
    val currentMetricsSim2: StateFlow<CellMetrics?> = _currentMetricsSim2.asStateFlow()

    var currentSlotIndex: Int = 0
        private set
    var currentSubId: Int? = null
        private set
    var currentSimName: String? = null
        private set

    var secondarySlotIndex: Int = 1
        private set
    var secondarySubId: Int? = null
        private set
    var secondarySimName: String? = null
        private set

    var isDualSimLogging: Boolean = false
        private set

    private var isNsaOverrideSim1 = false
    private var isNsaOverrideSim2 = false

    private var callbackSim1: Any? = null
    private var legacyListenerSim1: PhoneStateListener? = null

    private var callbackSim2: Any? = null
    private var legacyListenerSim2: PhoneStateListener? = null

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
        registerTelephonyListenersSim1()
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

    fun setDualSimLogging(enabled: Boolean) {
        if (isDualSimLogging == enabled) return
        isDualSimLogging = enabled
        Log.d("CellCollector", "setDualSimLogging: $enabled")

        if (enabled) {
            setupSecondarySim()
        } else {
            unregisterTelephonyListenersSim2()
            secondaryTelephonyManager = null
            secondarySubId = null
            secondarySimName = null
            _currentMetricsSim2.value = null
        }
    }

    private fun setupSecondarySim() {
        val subs = getActiveSubscriptions(context)
        val secondarySub = subs.firstOrNull { it.slotIndex != currentSlotIndex }
        if (secondarySub != null && secondarySub.subscriptionId != -1) {
            secondarySlotIndex = secondarySub.slotIndex
            secondarySubId = secondarySub.subscriptionId
            secondarySimName = secondarySub.displayName
            secondaryTelephonyManager = baseTelephonyManager.createForSubscriptionId(secondarySub.subscriptionId)
            registerTelephonyListenersSim2()
            Log.d("CellCollector", "Secondary SIM configured: slot $secondarySlotIndex, subId $secondarySubId, name $secondarySimName")
        } else {
            Log.w("CellCollector", "Secondary SIM not available or inactive")
            secondaryTelephonyManager = null
            _currentMetricsSim2.value = null
        }
    }

    fun switchSim(slotIndex: Int, subId: Int?, simName: String? = null) {
        if (slotIndex == currentSlotIndex && subId == currentSubId && activeTelephonyManager != baseTelephonyManager) {
            return
        }
        Log.d("CellCollector", "Switching SIM slot to $slotIndex (subId=$subId, name=$simName)")
        unregisterTelephonyListenersSim1()

        currentSlotIndex = slotIndex
        currentSubId = subId
        currentSimName = simName

        activeTelephonyManager = if (subId != null && subId != -1) {
            baseTelephonyManager.createForSubscriptionId(subId)
        } else {
            baseTelephonyManager
        }

        registerTelephonyListenersSim1()

        if (isDualSimLogging) {
            unregisterTelephonyListenersSim2()
            setupSecondarySim()
        }

        pollCellInfo()
    }

    @SuppressLint("MissingPermission")
    private fun registerTelephonyListenersSim1() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val cb = object : TelephonyCallback(),
                TelephonyCallback.DisplayInfoListener,
                TelephonyCallback.CellInfoListener {

                override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) {
                    val override = telephonyDisplayInfo.overrideNetworkType
                    isNsaOverrideSim1 = (override == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA ||
                            override == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED)
                    Log.d("CellCollector", "SIM1 DisplayInfo changed: isNsaOverride=$isNsaOverrideSim1 (override=$override)")
                    pollCellInfoSim1()
                }

                override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>) {
                    _currentMetrics.value = parseCellMetrics(
                        tm = activeTelephonyManager,
                        slotIndex = currentSlotIndex,
                        simName = currentSimName,
                        cells = cellInfo,
                        isNsaOverride = isNsaOverrideSim1
                    )
                }
            }
            callbackSim1 = cb
            try {
                activeTelephonyManager.registerTelephonyCallback(executor, cb)
            } catch (e: Exception) {
                Log.e("CellCollector", "Error registering TelephonyCallback on SIM1 slot $currentSlotIndex", e)
            }
        } else {
            val listener = object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>?) {
                    _currentMetrics.value = parseCellMetrics(
                        tm = activeTelephonyManager,
                        slotIndex = currentSlotIndex,
                        simName = currentSimName,
                        cells = cellInfo ?: emptyList(),
                        isNsaOverride = isNsaOverrideSim1
                    )
                }

                @Deprecated("Deprecated in Java")
                override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val override = telephonyDisplayInfo.overrideNetworkType
                        isNsaOverrideSim1 = (override == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA ||
                                override == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED)
                        pollCellInfoSim1()
                    }
                }
            }
            legacyListenerSim1 = listener
            @Suppress("DEPRECATION")
            try {
                activeTelephonyManager.listen(listener, PhoneStateListener.LISTEN_CELL_INFO)
            } catch (e: Exception) {
                Log.e("CellCollector", "Error registering PhoneStateListener on SIM1 slot $currentSlotIndex", e)
            }
        }
    }

    private fun unregisterTelephonyListenersSim1() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && callbackSim1 != null) {
                activeTelephonyManager.unregisterTelephonyCallback(callbackSim1 as TelephonyCallback)
                callbackSim1 = null
            } else if (legacyListenerSim1 != null) {
                @Suppress("DEPRECATION")
                activeTelephonyManager.listen(legacyListenerSim1, PhoneStateListener.LISTEN_NONE)
                legacyListenerSim1 = null
            }
        } catch (e: Exception) {
            Log.e("CellCollector", "Error unregistering telephony listeners for SIM1", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerTelephonyListenersSim2() {
        val tm = secondaryTelephonyManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val cb = object : TelephonyCallback(),
                TelephonyCallback.DisplayInfoListener,
                TelephonyCallback.CellInfoListener {

                override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) {
                    val override = telephonyDisplayInfo.overrideNetworkType
                    isNsaOverrideSim2 = (override == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA ||
                            override == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED)
                    Log.d("CellCollector", "SIM2 DisplayInfo changed: isNsaOverride=$isNsaOverrideSim2 (override=$override)")
                    pollCellInfoSim2()
                }

                override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>) {
                    _currentMetricsSim2.value = parseCellMetrics(
                        tm = tm,
                        slotIndex = secondarySlotIndex,
                        simName = secondarySimName,
                        cells = cellInfo,
                        isNsaOverride = isNsaOverrideSim2
                    )
                }
            }
            callbackSim2 = cb
            try {
                tm.registerTelephonyCallback(executor, cb)
            } catch (e: Exception) {
                Log.e("CellCollector", "Error registering TelephonyCallback on SIM2 slot $secondarySlotIndex", e)
            }
        } else {
            val listener = object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>?) {
                    _currentMetricsSim2.value = parseCellMetrics(
                        tm = tm,
                        slotIndex = secondarySlotIndex,
                        simName = secondarySimName,
                        cells = cellInfo ?: emptyList(),
                        isNsaOverride = isNsaOverrideSim2
                    )
                }

                @Deprecated("Deprecated in Java")
                override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val override = telephonyDisplayInfo.overrideNetworkType
                        isNsaOverrideSim2 = (override == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA ||
                                override == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED)
                        pollCellInfoSim2()
                    }
                }
            }
            legacyListenerSim2 = listener
            @Suppress("DEPRECATION")
            try {
                tm.listen(listener, PhoneStateListener.LISTEN_CELL_INFO)
            } catch (e: Exception) {
                Log.e("CellCollector", "Error registering PhoneStateListener on SIM2 slot $secondarySlotIndex", e)
            }
        }
    }

    private fun unregisterTelephonyListenersSim2() {
        try {
            val tm = secondaryTelephonyManager
            if (tm != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && callbackSim2 != null) {
                    tm.unregisterTelephonyCallback(callbackSim2 as TelephonyCallback)
                    callbackSim2 = null
                } else if (legacyListenerSim2 != null) {
                    @Suppress("DEPRECATION")
                    tm.listen(legacyListenerSim2, PhoneStateListener.LISTEN_NONE)
                    legacyListenerSim2 = null
                }
            }
        } catch (e: Exception) {
            Log.e("CellCollector", "Error unregistering telephony listeners for SIM2", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun pollCellInfo() {
        pollCellInfoSim1()
        if (isDualSimLogging && secondaryTelephonyManager != null) {
            pollCellInfoSim2()
        }
    }

    @SuppressLint("MissingPermission")
    private fun pollCellInfoSim1() {
        try {
            activeTelephonyManager.requestCellInfoUpdate(executor, object : TelephonyManager.CellInfoCallback() {
                override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                    _currentMetrics.value = parseCellMetrics(
                        tm = activeTelephonyManager,
                        slotIndex = currentSlotIndex,
                        simName = currentSimName,
                        cells = cellInfo,
                        isNsaOverride = isNsaOverrideSim1
                    )
                }

                override fun onError(errorCode: Int, detail: Throwable?) {
                    Log.w("CellCollector", "SIM1 requestCellInfoUpdate error code: $errorCode", detail)
                    try {
                        val cached = activeTelephonyManager.allCellInfo
                        if (!cached.isNullOrEmpty()) {
                            _currentMetrics.value = parseCellMetrics(
                                tm = activeTelephonyManager,
                                slotIndex = currentSlotIndex,
                                simName = currentSimName,
                                cells = cached,
                                isNsaOverride = isNsaOverrideSim1
                            )
                        }
                    } catch (e: Exception) {
                        Log.e("CellCollector", "Failed getting cached allCellInfo for SIM1", e)
                    }
                }
            })
        } catch (e: Exception) {
            Log.e("CellCollector", "Failed requestCellInfoUpdate for SIM1", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun pollCellInfoSim2() {
        val tm = secondaryTelephonyManager ?: return
        try {
            tm.requestCellInfoUpdate(executor, object : TelephonyManager.CellInfoCallback() {
                override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                    _currentMetricsSim2.value = parseCellMetrics(
                        tm = tm,
                        slotIndex = secondarySlotIndex,
                        simName = secondarySimName,
                        cells = cellInfo,
                        isNsaOverride = isNsaOverrideSim2
                    )
                }

                override fun onError(errorCode: Int, detail: Throwable?) {
                    Log.w("CellCollector", "SIM2 requestCellInfoUpdate error code: $errorCode", detail)
                    try {
                        val cached = tm.allCellInfo
                        if (!cached.isNullOrEmpty()) {
                            _currentMetricsSim2.value = parseCellMetrics(
                                tm = tm,
                                slotIndex = secondarySlotIndex,
                                simName = secondarySimName,
                                cells = cached,
                                isNsaOverride = isNsaOverrideSim2
                            )
                        }
                    } catch (e: Exception) {
                        Log.e("CellCollector", "Failed getting cached allCellInfo for SIM2", e)
                    }
                }
            })
        } catch (e: Exception) {
            Log.e("CellCollector", "Failed requestCellInfoUpdate for SIM2", e)
        }
    }

    private fun parseCellMetrics(
        tm: TelephonyManager,
        slotIndex: Int,
        simName: String?,
        cells: List<CellInfo>,
        isNsaOverride: Boolean
    ): CellMetrics {
        if (cells.isEmpty()) {
            return CellMetrics(selectedSimSlot = slotIndex, simDisplayName = simName)
        }

        var servingLte: LteMetrics? = null
        var servingNr: NrMetrics? = null
        val neighbors = mutableListOf<NeighborCell>()
        var plmn: String? = tm.networkOperator.takeIf { it.isNotBlank() }
        val operatorAlpha = tm.networkOperatorName.takeIf { it.isNotBlank() }

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

        return CellMetrics(
            ratType = determinedRat,
            plmn = plmn,
            operatorAlpha = operatorAlpha,
            lte = servingLte,
            nr = servingNr,
            neighbors = neighbors,
            selectedSimSlot = slotIndex,
            simDisplayName = simName
        )
    }

    fun stop() {
        unregisterTelephonyListenersSim1()
        unregisterTelephonyListenersSim2()
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
