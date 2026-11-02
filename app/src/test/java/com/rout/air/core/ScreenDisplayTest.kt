package com.rout.air.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Aturan putar otomatis untuk template Vertikal/Horizontal sumber Layar. */
class ScreenDisplayTest {

    @Test
    fun `horizontal pada tangkapan portrait diputar 90 derajat`() {
        assertTrue(screenNeedsRotation(ScreenDisplay.Landscape, 1080, 2400))
    }

    @Test
    fun `horizontal pada tangkapan landscape tidak diputar`() {
        assertFalse(screenNeedsRotation(ScreenDisplay.Landscape, 2400, 1080))
    }

    @Test
    fun `vertikal pada tangkapan landscape diputar 90 derajat`() {
        assertTrue(screenNeedsRotation(ScreenDisplay.Portrait, 2400, 1080))
    }

    @Test
    fun `vertikal pada tangkapan portrait tidak diputar`() {
        assertFalse(screenNeedsRotation(ScreenDisplay.Portrait, 1080, 2400))
    }

    @Test
    fun `otomatis tidak pernah memutar`() {
        assertFalse(screenNeedsRotation(ScreenDisplay.Auto, 2400, 1080))
        assertFalse(screenNeedsRotation(null, 2400, 1080))
    }

    @Test
    fun `ukuran belum diketahui tidak diputar`() {
        assertFalse(screenNeedsRotation(ScreenDisplay.Portrait, 0, 0))
    }
}
