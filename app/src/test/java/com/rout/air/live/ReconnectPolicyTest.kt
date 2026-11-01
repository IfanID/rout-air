package com.rout.air.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReconnectPolicyTest {

    @Test
    fun backoff_growsAndIsCapped() {
        assertEquals(1_000L, ReconnectPolicy.delayMs(1))
        assertEquals(2_000L, ReconnectPolicy.delayMs(2))
        assertEquals(4_000L, ReconnectPolicy.delayMs(3))
        assertEquals(8_000L, ReconnectPolicy.delayMs(4))
        assertEquals(10_000L, ReconnectPolicy.delayMs(5))
        assertEquals(10_000L, ReconnectPolicy.delayMs(ReconnectPolicy.MAX_ATTEMPTS))
    }

    @Test
    fun attemptsOutsideRange_stopReconnecting() {
        assertNull(ReconnectPolicy.delayMs(0))
        assertNull(ReconnectPolicy.delayMs(-1))
        assertNull(ReconnectPolicy.delayMs(ReconnectPolicy.MAX_ATTEMPTS + 1))
    }
}
