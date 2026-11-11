package com.drivetest.lte5g.ai

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class GeminiRfAdvisorTest {

    @Before
    fun setUp() {
        GeminiRfAdvisor.reset()
    }

    @Test
    fun initialState_isIdle() {
        assertEquals(GeminiAdvisorState.Idle, GeminiRfAdvisor.state.value)
    }

    @Test
    fun reset_returnsStateToIdle() {
        GeminiRfAdvisor.reset()
        assertTrue(GeminiRfAdvisor.state.value is GeminiAdvisorState.Idle)
    }
}
