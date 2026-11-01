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
) {
    val aspectRatio: Float get() = width.toFloat() / height.toFloat()
}
