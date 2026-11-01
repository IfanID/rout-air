package com.rout.air.service

import kotlin.math.abs
import kotlin.math.min

/** Hasil perhitungan ukuran panel overlay. */
data class OverlayPanelSize(
    val panelWidth: Int,
    val panelHeight: Int,
    val previewWidth: Int,
    val previewHeight: Int,
)

/**
 * Matematika tata letak panel overlay, dipisah dari Service agar bisa diuji.
 * Rasio pratinjau selalu 16:9.
 */
object OverlayLayout {
    const val ASPECT = 16f / 9f
    const val MIN_WIDTH_DP = 180

    /**
     * Hitung ukuran panel dari lebar yang diminta.
     * @param maxHeightFraction bagian tinggi layar yang boleh dipakai panel.
     */
    fun panelSize(
        requestedWidth: Int,
        screenWidth: Int,
        screenHeight: Int,
        sidePad: Int,
        header: Int,
        controls: Int,
        gapTop: Int,
        gapBottom: Int,
        minWidth: Int,
        maxWidthFraction: Float = 0.92f,
        maxHeightFraction: Float = 0.78f,
        availableWidth: Int = Int.MAX_VALUE,
        availableHeight: Int = Int.MAX_VALUE,
    ): OverlayPanelSize {
        // Batasi oleh ruang nyata dari posisi panel agar tidak perlu digeser.
        val heightBudget = min(
            (screenHeight * maxHeightFraction).toInt(),
            availableHeight,
        ) - header - controls - gapTop - gapBottom
        val maxByHeight = (heightBudget * ASPECT).toInt() + sidePad * 2
        val maxWidth = min(
            (screenWidth * maxWidthFraction).toInt(),
            min(min(maxByHeight, availableWidth), Int.MAX_VALUE),
        ).coerceAtLeast(minWidth)
        val width = requestedWidth.coerceIn(minWidth, maxWidth)
        val previewWidth = (width - sidePad * 2).coerceAtLeast(1)
        val previewHeight = (previewWidth / ASPECT).toInt().coerceAtLeast(1)
        val height = header + gapTop + previewHeight + gapBottom + controls
        return OverlayPanelSize(width, height, previewWidth, previewHeight)
    }

    /** Lebar baru saat pegangan digeser (dx, dy); pilih arah dominan. */
    fun widthFromDrag(startWidth: Int, dx: Float, dy: Float): Int {
        val horizontal = dx
        val vertical = dy * ASPECT
        val delta = if (abs(horizontal) >= abs(vertical)) horizontal else vertical
        return (startWidth + delta).toInt()
    }
}
