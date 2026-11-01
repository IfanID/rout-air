package com.rout.air.core

/**
 * Orientasi video hasil rekaman, terpisah dari rotasi layar.
 * Auto memakai orientasi HP saat rekaman dimulai, Portrait/Landscape
 * mengunci dimensi video tanpa memaksa layar ikut berputar.
 */
enum class CanvasOrientation {
    Auto,
    Portrait,
    Landscape,
}

/**
 * Cara menyesuaikan isi layar bila rasio layar berbeda dengan rasio video.
 * Padanan "Scale Content" di OBS.
 */
enum class ContentMode {
    /** Seluruh layar masuk bidang video, sisa bidang hitam (letterbox). */
    Fit,

    /** Isi bidang video penuh, bagian luar dipotong. */
    Crop,

    /** Dipaksa memenuhi bidang video, bisa gepeng. */
    Stretch,
}

/** Padanan "Rate Control" OBS untuk encoder MediaCodec. */
enum class VideoBitrateMode {
    VBR,
    CBR,
}

/** Profil H.264 yang bisa dipilih; Auto memakai High lalu fallback. */
enum class VideoProfile {
    Auto,
    High,
    Baseline,
}

enum class ColorSpaceMode {
    BT709,
    BT601,
}

enum class ColorRangeMode {
    Limited,
    Full,
}

/**
 * Pengaturan satu sesi rekam. Nilai default sengaja konservatif supaya
 * langsung jalan di sebagian besar perangkat.
 */
data class CaptureConfig(
    val width: Int = 1920,
    val height: Int = 1080,
    val fps: Int = 30,
    val videoBitrate: Int = 6_000_000,
    val audioBitrate: Int = 128_000,
    val sampleRate: Int = 48_000,
    val channelCount: Int = 2,
    val canvasOrientation: CanvasOrientation = CanvasOrientation.Auto,
    val contentMode: ContentMode = ContentMode.Fit,
    val saveLocation: SaveLocation = SaveLocation.MoviesRoutAir,
    /** 0 = mengikuti kanvas; 75/50/25 = Rescale Output ala OBS. */
    val outputScalePercent: Int = 0,
    val keyframeIntervalSec: Int = 2,
    val bitrateMode: VideoBitrateMode = VideoBitrateMode.VBR,
    val videoProfile: VideoProfile = VideoProfile.Auto,
    val colorSpace: ColorSpaceMode = ColorSpaceMode.BT709,
    val colorRange: ColorRangeMode = ColorRangeMode.Limited,
    val fileNameTemplate: String = "RoutAir_{date}_{time}",
    val noSpaceFileName: Boolean = false,
    /** Default mati: selalu fps penuh seperti OBS. */
    val staticThrottleEnabled: Boolean = false,
    val thermalThrottleEnabled: Boolean = false,
) {
    val aspectRatio: Float get() = width.toFloat() / height.toFloat()

    val encoderWidth: Int
        get() = if (outputScalePercent in 1..99) {
            evenSide(width.toLong() * outputScalePercent / 100L)
        } else {
            width
        }
    val encoderHeight: Int
        get() = if (outputScalePercent in 1..99) {
            evenSide(height.toLong() * outputScalePercent / 100L)
        } else {
            height
        }
    val rescaled: Boolean get() = encoderWidth != width || encoderHeight != height

    private fun evenSide(value: Long): Int {
        val scaled = value.toInt().coerceAtLeast(2)
        return if (scaled % 2 == 0) scaled else scaled + 1
    }
}
