package com.rout.air.engine.filter

import com.rout.air.core.model.VideoFilter
import com.rout.air.core.model.chromaKey
import com.rout.air.core.model.colorCorrection

/**
 * Nilai filter yang siap dikirim ke shader. Dibentuk sekali per layer saat
 * scene dibangun, bukan setiap frame.
 */
data class FilterState(
    val chroma: Boolean = false,
    val keyR: Float = 0f,
    val keyG: Float = 1f,
    val keyB: Float = 0f,
    val similarity: Float = 0.35f,
    val smoothness: Float = 0.08f,
    val spill: Float = 0.10f,
    val adjust: Boolean = false,
    val brightness: Float = 1f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    val gamma: Float = 1f,
    val hueRadians: Float = 0f,
) {
    companion object {
        val NONE = FilterState()

        fun from(filters: List<VideoFilter>): FilterState {
            if (filters.isEmpty()) return NONE
            val key = filters.chromaKey()
            val color = filters.colorCorrection()
            val colorValue = key?.color ?: 0L
            return FilterState(
                chroma = key != null,
                keyR = ((colorValue shr 16) and 0xFF) / 255f,
                keyG = ((colorValue shr 8) and 0xFF) / 255f,
                keyB = (colorValue and 0xFF) / 255f,
                similarity = key?.similarity ?: 0.35f,
                smoothness = key?.smoothness ?: 0.08f,
                spill = key?.spill ?: 0.10f,
                adjust = color != null,
                brightness = color?.brightness ?: 1f,
                contrast = color?.contrast ?: 1f,
                saturation = color?.saturation ?: 1f,
                gamma = color?.gamma ?: 1f,
                hueRadians = Math.toRadians((color?.hueDegrees ?: 0f).toDouble()).toFloat(),
            )
        }
    }
}
