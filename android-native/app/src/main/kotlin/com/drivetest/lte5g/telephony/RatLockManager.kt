package com.drivetest.lte5g.telephony

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log
import com.drivetest.lte5g.model.RatLockMode
import com.drivetest.lte5g.model.RatType

enum class RatLockCapability {
    DIRECT_MODEM_LOCK_SUPPORTED,
    RESTRICTED_RADIO_INFO_AVAILABLE,
    RESTRICTED_SETTINGS_FALLBACK
}

enum class RatLockType {
    AUTO,
    HARDWARE_BASEBAND,
    SOFTWARE_FILTER
}

data class RatLockStatus(
    val mode: RatLockMode = RatLockMode.AUTO,
    val lockType: RatLockType = RatLockType.AUTO,
    val capability: RatLockCapability = RatLockCapability.RESTRICTED_RADIO_INFO_AVAILABLE,
    val isMatched: Boolean = true,
    val connectedRat: RatType = RatType.UNKNOWN,
    val statusMessage: String = "Auto (All RATs active)"
)

object RatLockManager {
    private const val TAG = "RatLockManager"

    fun checkCapability(context: Context): RatLockCapability {
        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val hasCarrierPrivileges = try {
            telephonyManager?.hasCarrierPrivileges() == true
        } catch (_: Exception) {
            false
        }
        val hasModifyPhoneState = context.checkSelfPermission("android.permission.MODIFY_PHONE_STATE") == PackageManager.PERMISSION_GRANTED

        if (hasCarrierPrivileges || hasModifyPhoneState) {
            return RatLockCapability.DIRECT_MODEM_LOCK_SUPPORTED
        }

        val radioInfoIntent = Intent(Intent.ACTION_MAIN).apply {
            setClassName("com.android.settings", "com.android.settings.RadioInfo")
        }
        val canLaunchRadioInfo = context.packageManager.resolveActivity(radioInfoIntent, 0) != null

        return if (canLaunchRadioInfo) {
            RatLockCapability.RESTRICTED_RADIO_INFO_AVAILABLE
        } else {
            RatLockCapability.RESTRICTED_SETTINGS_FALLBACK
        }
    }

    @SuppressLint("MissingPermission")
    fun tryDirectModemLock(context: Context, mode: RatLockMode, subId: Int? = null): Result<Boolean> {
        val baseTm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return Result.failure(IllegalStateException("TelephonyManager unavailable"))

        val tm = if (subId != null && subId != -1) {
            baseTm.createForSubscriptionId(subId)
        } else {
            baseTm
        }

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val bitmask = when (mode) {
                    RatLockMode.AUTO -> TelephonyManager.NETWORK_TYPE_BITMASK_NR or
                            TelephonyManager.NETWORK_TYPE_BITMASK_LTE or
                            TelephonyManager.NETWORK_TYPE_BITMASK_UMTS or
                            TelephonyManager.NETWORK_TYPE_BITMASK_GSM
                    RatLockMode.LTE_ONLY -> TelephonyManager.NETWORK_TYPE_BITMASK_LTE
                    RatLockMode.NR_ONLY -> TelephonyManager.NETWORK_TYPE_BITMASK_NR
                }
                tm.setAllowedNetworkTypesForReason(TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER, bitmask)
                Result.success(true)
            } else {
                try {
                    val method = tm.javaClass.getMethod("setPreferredNetworkTypeBitmask", Long::class.javaPrimitiveType)
                    val bitmask = when (mode) {
                        RatLockMode.AUTO -> 0L
                        RatLockMode.LTE_ONLY -> TelephonyManager.NETWORK_TYPE_BITMASK_LTE
                        RatLockMode.NR_ONLY -> TelephonyManager.NETWORK_TYPE_BITMASK_NR
                    }
                    method.invoke(tm, bitmask)
                    Result.success(true)
                } catch (e: NoSuchMethodException) {
                    Result.failure(UnsupportedOperationException("Modem lock API requires Android 13+ on this device"))
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Direct modem lock denied by Android OS Security Sandbox", e)
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "Error applying direct modem lock", e)
            Result.failure(e)
        }
    }

    fun launchHardwareLock(context: Context): Boolean {
        return CellCollector.launchRadioInfo(context)
    }

    fun evaluateLockStatus(
        targetMode: RatLockMode,
        lockType: RatLockType,
        capability: RatLockCapability,
        connectedRat: RatType
    ): RatLockStatus {
        val isMatched = when (targetMode) {
            RatLockMode.AUTO -> true
            RatLockMode.LTE_ONLY -> connectedRat == RatType.LTE
            RatLockMode.NR_ONLY -> connectedRat == RatType.NR_NSA || connectedRat == RatType.NR_SA
        }

        val message = when {
            targetMode == RatLockMode.AUTO -> "Auto (All RATs active)"
            isMatched && lockType == RatLockType.HARDWARE_BASEBAND -> "✓ Hardware Locked to ${targetMode.name}"
            isMatched && lockType == RatLockType.SOFTWARE_FILTER -> "✓ Connected to ${connectedRat.name} (Filter Active)"
            !isMatched && lockType == RatLockType.HARDWARE_BASEBAND -> "⚠️ Mismatch: Modem on ${connectedRat.name}"
            !isMatched -> "⚠️ Modem on ${connectedRat.name} (Not locked to ${targetMode.name})"
            else -> "Monitoring ${connectedRat.name}"
        }

        return RatLockStatus(
            mode = targetMode,
            lockType = lockType,
            capability = capability,
            isMatched = isMatched,
            connectedRat = connectedRat,
            statusMessage = message
        )
    }
}
