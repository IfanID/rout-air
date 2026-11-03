package com.rout.air.live

import org.junit.Assert.assertEquals
import org.junit.Test

/** Mengunci pembentukan skema redirect AppAuth tanpa perlu perangkat. */
class GoogleAuthTest {

    @Test
    fun redirectSchemeReversesClientId() {
        assertEquals(
            "com.googleusercontent.apps.123456-abc",
            GoogleAuth.redirectScheme("123456-abc.apps.googleusercontent.com"),
        )
    }

    @Test
    fun blankClientFallsBackToPackageScheme() {
        assertEquals("com.rout.air", GoogleAuth.redirectScheme(""))
    }
}
