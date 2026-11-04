package com.drivetest.lte5g.telephony

import com.drivetest.lte5g.model.RatLockMode
import com.drivetest.lte5g.model.RatType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RatLockManagerTest {

    @Test
    fun testEvaluateLockStatus_autoMode_alwaysMatched() {
        val status = RatLockManager.evaluateLockStatus(
            targetMode = RatLockMode.AUTO,
            lockType = RatLockType.AUTO,
            capability = RatLockCapability.RESTRICTED_RADIO_INFO_AVAILABLE,
            connectedRat = RatType.LTE
        )
        assertTrue(status.isMatched)
        assertEquals("Auto (All RATs active)", status.statusMessage)
    }

    @Test
    fun testEvaluateLockStatus_lteOnly_matchedWhenConnectedLte() {
        val statusHw = RatLockManager.evaluateLockStatus(
            targetMode = RatLockMode.LTE_ONLY,
            lockType = RatLockType.HARDWARE_BASEBAND,
            capability = RatLockCapability.DIRECT_MODEM_LOCK_SUPPORTED,
            connectedRat = RatType.LTE
        )
        assertTrue(statusHw.isMatched)
        assertTrue(statusHw.statusMessage.contains("Hardware Locked to LTE_ONLY"))

        val statusSw = RatLockManager.evaluateLockStatus(
            targetMode = RatLockMode.LTE_ONLY,
            lockType = RatLockType.SOFTWARE_FILTER,
            capability = RatLockCapability.RESTRICTED_RADIO_INFO_AVAILABLE,
            connectedRat = RatType.LTE
        )
        assertTrue(statusSw.isMatched)
        assertTrue(statusSw.statusMessage.contains("Filter Active"))
    }

    @Test
    fun testEvaluateLockStatus_lteOnly_mismatchWhenConnectedNrNsa() {
        val status = RatLockManager.evaluateLockStatus(
            targetMode = RatLockMode.LTE_ONLY,
            lockType = RatLockType.SOFTWARE_FILTER,
            capability = RatLockCapability.RESTRICTED_RADIO_INFO_AVAILABLE,
            connectedRat = RatType.NR_NSA
        )
        assertFalse(status.isMatched)
        assertTrue(status.statusMessage.contains("Modem on NR_NSA"))
    }

    @Test
    fun testEvaluateLockStatus_nrOnly_matchedWhenConnectedNrNsaOrSa() {
        val statusNsa = RatLockManager.evaluateLockStatus(
            targetMode = RatLockMode.NR_ONLY,
            lockType = RatLockType.HARDWARE_BASEBAND,
            capability = RatLockCapability.DIRECT_MODEM_LOCK_SUPPORTED,
            connectedRat = RatType.NR_NSA
        )
        assertTrue(statusNsa.isMatched)

        val statusSa = RatLockManager.evaluateLockStatus(
            targetMode = RatLockMode.NR_ONLY,
            lockType = RatLockType.HARDWARE_BASEBAND,
            capability = RatLockCapability.DIRECT_MODEM_LOCK_SUPPORTED,
            connectedRat = RatType.NR_SA
        )
        assertTrue(statusSa.isMatched)
    }

    @Test
    fun testEvaluateLockStatus_nrOnly_mismatchWhenConnectedLte() {
        val status = RatLockManager.evaluateLockStatus(
            targetMode = RatLockMode.NR_ONLY,
            lockType = RatLockType.SOFTWARE_FILTER,
            capability = RatLockCapability.RESTRICTED_RADIO_INFO_AVAILABLE,
            connectedRat = RatType.LTE
        )
        assertFalse(status.isMatched)
        assertTrue(status.statusMessage.contains("Modem on LTE (Not locked to NR_ONLY)"))
    }
}
