package com.rout.air.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mengunci masa berlaku token tanpa perlu perangkat. */
class GoogleAuthTest {

    @Test
    fun tokenExpiresAfterFiftyFiveMinutes() {
        val now = 1_000_000L
        assertEquals(now + 55 * 60 * 1000L, GoogleAuth.expiryFrom(now))
    }

    @Test
    fun freshRequiresMarginBeforeExpiry() {
        val now = 1_000_000L
        val token = GoogleTokens("abc", now + 55 * 60 * 1000L)
        assertTrue(token.fresh(now))
        // Satu menit sebelum kedaluwarsa sudah dianggap perlu diperbarui.
        assertFalse(token.fresh(now + 55 * 60 * 1000L - 30_000L))
    }

    @Test
    fun blankTokenIsNeverFresh() {
        val token = GoogleTokens("", Long.MAX_VALUE)
        assertFalse(token.fresh())
    }
}
