package com.rout.air.data

import android.content.Context
import com.rout.air.core.CanvasOrientation
import com.rout.air.core.CaptureConfig
import com.rout.air.core.ContentMode

/**
 * Menyimpan preset rekaman terakhir agar tidak perlu diatur ulang.
 * Pengaturan mixer audio punya penyimpanan sendiri di [AudioMixStore].
 */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): CaptureConfig = CaptureConfig(
        width = prefs.getInt(KEY_WIDTH, DEFAULT_WIDTH),
        height = prefs.getInt(KEY_HEIGHT, DEFAULT_HEIGHT),
        fps = prefs.getInt(KEY_FPS, DEFAULT_FPS),
        videoBitrate = prefs.getInt(KEY_BITRATE, DEFAULT_BITRATE),
        audioBitrate = prefs.getInt(KEY_AUDIO_BITRATE, DEFAULT_AUDIO_BITRATE),
        canvasOrientation = runCatching {
            CanvasOrientation.valueOf(
                prefs.getString(KEY_ORIENTATION, CanvasOrientation.Auto.name)
                    ?: CanvasOrientation.Auto.name,
            )
        }.getOrDefault(CanvasOrientation.Auto),
        contentMode = runCatching {
            ContentMode.valueOf(
                prefs.getString(KEY_CONTENT_MODE, ContentMode.Fit.name) ?: ContentMode.Fit.name,
            )
        }.getOrDefault(ContentMode.Fit),
    )

    fun save(config: CaptureConfig) {
        prefs.edit()
            .putInt(KEY_WIDTH, config.width)
            .putInt(KEY_HEIGHT, config.height)
            .putInt(KEY_FPS, config.fps)
            .putInt(KEY_BITRATE, config.videoBitrate)
            .putInt(KEY_AUDIO_BITRATE, config.audioBitrate)
            .putString(KEY_ORIENTATION, config.canvasOrientation.name)
            .putString(KEY_CONTENT_MODE, config.contentMode.name)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "rout_air_settings"
        const val KEY_WIDTH = "width"
        const val KEY_HEIGHT = "height"
        const val KEY_FPS = "fps"
        const val KEY_BITRATE = "bitrate"
        const val KEY_AUDIO_BITRATE = "audio_bitrate"
        const val KEY_ORIENTATION = "canvas_orientation"
        const val KEY_CONTENT_MODE = "content_mode"

        const val DEFAULT_WIDTH = 1920
        const val DEFAULT_HEIGHT = 1080
        const val DEFAULT_FPS = 30
        const val DEFAULT_BITRATE = 6_000_000
        const val DEFAULT_AUDIO_BITRATE = 128_000
    }
}
