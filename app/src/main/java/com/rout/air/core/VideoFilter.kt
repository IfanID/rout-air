package com.rout.air.core

/**
 * Filter GPU sederhana per sumber, meniru sebagian filter OBS.
 *
 * Disimpan di [SceneSource] supaya berlaku di semua scene yang memakai
 * sumber tersebut, sama seperti filter OBS.
 */
sealed interface VideoFilter {

    /** Menghapus latar berwarna (green/blue screen). */
    data class ChromaKey(
        val color: Long = 0xFF00FF00,
        val similarity: Float = 0.35f,
        val smoothness: Float = 0.08f,
        val spill: Float = 0.10f,
    ) : VideoFilter

    /** Koreksi warna dasar: kecerahan, kontras, saturasi, gamma, hue. */
    data class ColorCorrection(
        val brightness: Float = 1f,
        val contrast: Float = 1f,
        val saturation: Float = 1f,
        val gamma: Float = 1f,
        val hueDegrees: Float = 0f,
    ) : VideoFilter
}

/** True bila sumber punya setidaknya satu filter. */
fun List<VideoFilter>.hasFilters(): Boolean = isNotEmpty()

/** Filter chroma pertama, bila ada. */
fun List<VideoFilter>.chromaKey(): VideoFilter.ChromaKey? =
    filterIsInstance<VideoFilter.ChromaKey>().firstOrNull()

/** Filter koreksi warna pertama, bila ada. */
fun List<VideoFilter>.colorCorrection(): VideoFilter.ColorCorrection? =
    filterIsInstance<VideoFilter.ColorCorrection>().firstOrNull()
