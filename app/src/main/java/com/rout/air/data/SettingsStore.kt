package com.rout.air.data

import android.content.Context
import com.rout.air.core.CanvasOrientation
import com.rout.air.core.CaptureConfig
import com.rout.air.core.ColorRangeMode
import com.rout.air.core.ColorSpaceMode
import com.rout.air.core.ContentMode
import com.rout.air.core.LiveConfig
import com.rout.air.core.SaveLocation
import com.rout.air.core.VideoBitrateMode
import com.rout.air.core.VideoProfile

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
        saveLocation = SaveLocation.fromName(prefs.getString(KEY_SAVE_LOCATION, null)),
        outputScalePercent = prefs.getInt(KEY_OUTPUT_SCALE, 0),
        keyframeIntervalSec = prefs.getInt(KEY_KEYFRAME, 2),
        bitrateMode = enumValue(prefs.getString(KEY_RATE_CONTROL, null), VideoBitrateMode.VBR),
        videoProfile = enumValue(prefs.getString(KEY_PROFILE, null), VideoProfile.Auto),
        colorSpace = enumValue(prefs.getString(KEY_COLOR_SPACE, null), ColorSpaceMode.BT709),
        colorRange = enumValue(prefs.getString(KEY_COLOR_RANGE, null), ColorRangeMode.Limited),
        fileNameTemplate = prefs.getString(KEY_FILE_NAME, "RoutAir_{date}_{time}")
            ?: "RoutAir_{date}_{time}",
        noSpaceFileName = prefs.getBoolean(KEY_NO_SPACE, false),
        staticThrottleEnabled = prefs.getBoolean(KEY_STATIC_THROTTLE, false),
        thermalThrottleEnabled = prefs.getBoolean(KEY_THERMAL_THROTTLE, false),
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
            .putString(KEY_SAVE_LOCATION, config.saveLocation.name)
            .putInt(KEY_OUTPUT_SCALE, config.outputScalePercent)
            .putInt(KEY_KEYFRAME, config.keyframeIntervalSec)
            .putString(KEY_RATE_CONTROL, config.bitrateMode.name)
            .putString(KEY_PROFILE, config.videoProfile.name)
            .putString(KEY_COLOR_SPACE, config.colorSpace.name)
            .putString(KEY_COLOR_RANGE, config.colorRange.name)
            .putString(KEY_FILE_NAME, config.fileNameTemplate)
            .putBoolean(KEY_NO_SPACE, config.noSpaceFileName)
            .putBoolean(KEY_STATIC_THROTTLE, config.staticThrottleEnabled)
            .putBoolean(KEY_THERMAL_THROTTLE, config.thermalThrottleEnabled)
            .apply()
    }

    /** Ukuran kanvas terakhir yang dipakai menyusun scene. */
    fun loadSceneCanvas(): Pair<Int, Int>? {
        val width = prefs.getInt(KEY_SCENE_CANVAS_W, 0)
        val height = prefs.getInt(KEY_SCENE_CANVAS_H, 0)
        return if (width > 0 && height > 0) width to height else null
    }

    fun saveSceneCanvas(width: Int, height: Int) {
        prefs.edit()
            .putInt(KEY_SCENE_CANVAS_W, width)
            .putInt(KEY_SCENE_CANVAS_H, height)
            .apply()
    }

    /** Id scene layar jeda yang dipakai tombol overlay. */
    fun loadScreensaverSceneId(): String =
        prefs.getString(KEY_JEDA_SCENE, "").orEmpty()

    fun saveScreensaverSceneId(id: String) {
        prefs.edit().putString(KEY_JEDA_SCENE, id).apply()
    }

    fun loadLive(): LiveConfig = LiveConfig(
        service = com.rout.air.core.LiveService.fromName(prefs.getString(KEY_LIVE_SERVICE, null)),
        serverUrl = prefs.getString(KEY_LIVE_URL, "").orEmpty(),
        streamKey = prefs.getString(KEY_LIVE_KEY, "").orEmpty(),
        useAuth = prefs.getBoolean(KEY_LIVE_USE_AUTH, false),
        username = prefs.getString(KEY_LIVE_USER, "").orEmpty(),
        password = prefs.getString(KEY_LIVE_PASSWORD, "").orEmpty(),
        autoReconnect = prefs.getBoolean(KEY_LIVE_RECONNECT, true),
        audioTrack = prefs.getInt(KEY_LIVE_TRACK, 1),
        accountLinked = prefs.getBoolean(KEY_LIVE_ACCOUNT, false),
    )

    fun saveLive(config: LiveConfig) {
        prefs.edit()
            .putString(KEY_LIVE_SERVICE, config.service.name)
            .putString(KEY_LIVE_URL, config.serverUrl)
            .putString(KEY_LIVE_KEY, config.streamKey)
            .putBoolean(KEY_LIVE_USE_AUTH, config.useAuth)
            .putString(KEY_LIVE_USER, config.username)
            .putString(KEY_LIVE_PASSWORD, config.password)
            .putBoolean(KEY_LIVE_RECONNECT, config.autoReconnect)
            .putInt(KEY_LIVE_TRACK, config.audioTrack)
            .putBoolean(KEY_LIVE_ACCOUNT, config.accountLinked)
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
        const val KEY_SAVE_LOCATION = "save_location"
        const val KEY_LIVE_URL = "live_url"
        const val KEY_LIVE_KEY = "live_key"
        const val KEY_LIVE_RECONNECT = "live_reconnect"
        const val KEY_LIVE_SERVICE = "live_service"
        const val KEY_LIVE_USE_AUTH = "live_use_auth"
        const val KEY_LIVE_USER = "live_user"
        const val KEY_LIVE_PASSWORD = "live_password"
        const val KEY_LIVE_TRACK = "live_track"
        const val KEY_LIVE_ACCOUNT = "live_account_linked"
        const val KEY_JEDA_SCENE = "screensaver_scene_id"
        const val KEY_SCENE_CANVAS_W = "scene_canvas_width"
        const val KEY_SCENE_CANVAS_H = "scene_canvas_height"

        const val KEY_OUTPUT_SCALE = "output_scale_percent"
        const val KEY_KEYFRAME = "keyframe_interval"
        const val KEY_RATE_CONTROL = "rate_control"
        const val KEY_PROFILE = "video_profile"
        const val KEY_COLOR_SPACE = "color_space"
        const val KEY_COLOR_RANGE = "color_range"
        const val KEY_FILE_NAME = "file_name_template"
        const val KEY_NO_SPACE = "no_space_filename"
        const val KEY_STATIC_THROTTLE = "static_throttle"
        const val KEY_THERMAL_THROTTLE = "thermal_throttle"

        inline fun <reified T : Enum<T>> enumValue(name: String?, fallback: T): T =
            runCatching { enumValueOf<T>(name ?: fallback.name) }.getOrDefault(fallback)

        const val DEFAULT_WIDTH = 1920
        const val DEFAULT_HEIGHT = 1080
        const val DEFAULT_FPS = 30
        const val DEFAULT_BITRATE = 6_000_000
        const val DEFAULT_AUDIO_BITRATE = 128_000
    }
}
