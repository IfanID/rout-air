package com.rout.air.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class OverlayLayoutTest {

    private val sidePad = 32
    private val header = 100
    private val controls = 138
    private val gapTop = 12
    private val gapBottom = 18
    private val minW = 540

    private fun size(width: Int, screenW: Int = 1080, screenH: Int = 2400): OverlayPanelSize =
        OverlayLayout.panelSize(
            requestedWidth = width,
            screenWidth = screenW,
            screenHeight = screenH,
            sidePad = sidePad,
            header = header,
            controls = controls,
            gapTop = gapTop,
            gapBottom = gapBottom,
            minWidth = minW,
        )

    @Test
    fun preview_keeps16by9_aspect() {
        val result = size(900)
        val ratio = result.previewWidth.toFloat() / result.previewHeight
        assertTrue(abs(ratio - OverlayLayout.ASPECT) < 0.02f)
    }

    @Test
    fun panel_height_matches_header_preview_controls() {
        val result = size(900)
        val expected = header + gapTop + result.previewHeight + gapBottom + controls
        assertEquals(expected, result.panelHeight)
    }

    @Test
    fun width_is_clamped_to_minimum() {
        assertEquals(minW, size(10).panelWidth)
    }

    @Test
    fun width_never_exceeds_screen_fraction() {
        assertTrue(size(10_000).panelWidth <= (1080 * 0.92f).toInt() + 1)
    }

    @Test
    fun panel_never_taller_than_budget() {
        assertTrue(size(10_000).panelHeight <= (2400 * 0.78f).toInt() + 2)
    }

    @Test
    fun drag_right_or_down_grows_width() {
        val start = 800
        assertEquals(start + 100, OverlayLayout.widthFromDrag(start, 100f, 0f))
        assertTrue(OverlayLayout.widthFromDrag(start, 0f, 100f) > start)
    }

    @Test
    fun drag_left_or_up_shrinks_width() {
        val start = 800
        assertEquals(start - 100, OverlayLayout.widthFromDrag(start, -100f, 0f))
        assertTrue(OverlayLayout.widthFromDrag(start, 0f, -100f) < start)
    }

    @Test
    fun simulated_drag_sequence_stays_within_bounds() {
        var start = 800
        val deltas = List(120) { i -> if (i < 60) 25f else -35f }
        repeat(deltas.size) { index ->
            val width = OverlayLayout.widthFromDrag(start, deltas[index], deltas[index])
            val result = size(width)
            assertTrue(result.panelWidth in minW..1080)
            assertTrue(result.panelHeight <= (2400 * 0.78f).toInt() + 2)
            assertTrue(result.previewWidth > 0 && result.previewHeight > 0)
            val ratio = result.previewWidth.toFloat() / result.previewHeight
            assertTrue("rasio berubah di langkah $index", abs(ratio - OverlayLayout.ASPECT) < 0.03f)
            start = result.panelWidth
        }
    }

    @Test
    fun landscape_screen_also_respects_budget() {
        val result = size(2000, screenW = 2400, screenH = 1080)
        assertTrue(result.panelWidth <= (2400 * 0.92f).toInt())
        assertTrue(result.panelHeight <= (1080 * 0.78f).toInt() + 2)
        val ratio = result.previewWidth.toFloat() / result.previewHeight
        assertTrue(abs(ratio - OverlayLayout.ASPECT) < 0.03f)
    }

    @Test
    fun available_width_limits_panel() {
        val result = OverlayLayout.panelSize(
            requestedWidth = 10_000,
            screenWidth = 1080,
            screenHeight = 2400,
            sidePad = sidePad,
            header = header,
            controls = controls,
            gapTop = gapTop,
            gapBottom = gapBottom,
            minWidth = minW,
            availableWidth = 700,
        )
        assertTrue(result.panelWidth <= 700)
    }

    @Test
    fun available_height_limits_panel() {
        val result = OverlayLayout.panelSize(
            requestedWidth = 10_000,
            screenWidth = 1080,
            screenHeight = 2400,
            sidePad = sidePad,
            header = header,
            controls = controls,
            gapTop = gapTop,
            gapBottom = gapBottom,
            minWidth = minW,
            availableHeight = 900,
        )
        assertTrue(result.panelHeight <= 900)
    }

    @Test
    fun drag_past_edge_does_not_jump() {
        // Panel di dekat tepi kanan: ruang tersisa hanya 600 px.
        var width = 500
        var prev = OverlayLayout.panelSize(
            requestedWidth = width,
            screenWidth = 1080,
            screenHeight = 2400,
            sidePad = sidePad,
            header = header,
            controls = controls,
            gapTop = gapTop,
            gapBottom = gapBottom,
            minWidth = minW,
            availableWidth = 600,
        ).panelWidth
        val step = 30f
        repeat(40) {
            width = OverlayLayout.widthFromDrag(width, step, step)
            val result = OverlayLayout.panelSize(
                requestedWidth = width,
                screenWidth = 1080,
                screenHeight = 2400,
                sidePad = sidePad,
                header = header,
                controls = controls,
                gapTop = gapTop,
                gapBottom = gapBottom,
                minWidth = minW,
                availableWidth = 600,
            )
            // Tidak boleh melonjak lebih dari satu langkah + toleransi.
            val jump = result.panelWidth - prev
            assertTrue("lonjakan $jump px di langkah $it", jump <= 60)
            prev = result.panelWidth
            width = result.panelWidth
        }
    }

    @Test
    fun minimum_width_keeps_close_button_visible() {
        // Perkiraan kebutuhan header: dot, timer, orang+0, sinyal+kbps, ✕, padding.
        val headerNeed = 40 + 60 + 24 + 90 + 48 + 48
        val result = size(10)
        assertTrue("min width ${result.panelWidth} < $headerNeed", result.panelWidth >= headerNeed)
    }

    @Test
    fun minimum_width_fits_nine_toolbar_icons() {
        // 9 ikon dengan area sentuh ~48px + padding kiri/kanan.
        val toolbarNeed = 9 * 48 + 52
        assertTrue(size(10).panelWidth >= toolbarNeed)
    }

    @Test
    fun shrink_below_min_is_clamped() {
        val result = size(1)
        assertEquals(minW, result.panelWidth)
        assertTrue(result.previewWidth > 0)
    }
}
