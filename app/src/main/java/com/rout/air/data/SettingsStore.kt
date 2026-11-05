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
import com.rout.air.core.VideoCodec
import com.rout.air.core.VideoProfile

/**
 * Menyimpan preset rekaman terakhir agar tidak perlu diatur ulang.
 * Pengaturan mixer audio punya penyimpanan sendiri di [AudioMixStore].
 */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): CaptureConfig {
        migrateRateControl()
        val width = prefs.getInt(KEY_WIDTH, DEFAULT_WIDTH)
        val height = prefs.getInt(KEY_HEIGHT, DEFAULT_HEIGHT)
        // Setelan lama menyimpan skala persen; konversi sekali ke resolusi nyata.
        val savedWidth = prefs.getInt(KEY_OUTPUT_WIDTH, 0)
        val savedHeight = prefs.getInt(KEY_OUTPUT_HEIGHT, 0)
        val legacyPercent = prefs.getInt(KEY_OUTPUT_SCALE, 0)
        val outputWidth: Int
        val outputHeight: Int
        if (savedWidth > 0 && savedHeight > 0) {
            outputWidth = savedWidth
            outputHeight = savedHeight
        } else if (legacyPercent in 1..99) {
            outputWidth = evenSide(width.toLong() * legacyPercent / 100L)
            outputHeight = evenSide(height.toLong() * legacyPercent / 100L)
        } else {
            outputWidth = 0
            outputHeight = 0
        }

        return CaptureConfig(
            width = width,
            height = height,
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
            outputWidth = outputWidth,
            outputHeight = outputHeight,
            keyframeIntervalSec = prefs.getInt(KEY_KEYFRAME, 2),
            bitrateMode = enumValue(prefs.getString(KEY_RATE_CONTROL, null), VideoBitrateMode.CBR),
            cqLevel = prefs.getInt(KEY_CQ_LEVEL, 20).coerceIn(0, 51),
            recordVideoBitrate = prefs.getInt(KEY_RECORD_BITRATE, 0),
            videoCodec = enumValue(prefs.getString(KEY_VIDEO_CODEC, null), VideoCodec.AVC),
            preferHevcRecording = prefs.getBoolean(KEY_PREFER_HEVC, false),
            videoProfile = enumValue(prefs.getString(KEY_PROFILE, null), VideoProfile.Auto),
            colorSpace = enumValue(prefs.getString(KEY_COLOR_SPACE, null), ColorSpaceMode.BT709),
            colorRange = enumValue(prefs.getString(KEY_COLOR_RANGE, null), ColorRangeMode.Limited),
            fileNameTemplate = prefs.getString(KEY_FILE_NAME, "RoutAir_{date}_{time}")
                ?: "RoutAir_{date}_{time}",
            noSpaceFileName = prefs.getBoolean(KEY_NO_SPACE, false),
            staticThrottleEnabled = prefs.getBoolean(KEY_STATIC_THROTTLE, false),
            thermalThrottleEnabled = prefs.getBoolean(KEY_THERMAL_THROTTLE, false),
            splitMinutes = prefs.getInt(KEY_SPLIT_MINUTES, 0).coerceIn(0, 240),
            splitSizeMb = prefs.getInt(KEY_SPLIT_SIZE_MB, 0).coerceAtLeast(0),
            safeRecording = prefs.getBoolean(KEY_SAFE_RECORDING, true),
            safeSegmentMinutes = prefs.getInt(KEY_SAFE_SEGMENT_MINUTES, 5).coerceIn(1, 30),
            replayBufferEnabled = prefs.getBoolean(KEY_REPLAY_ENABLED, false),
            replaySeconds = prefs.getInt(KEY_REPLAY_SECONDS, 60).coerceIn(10, 300),
        )
    }

    /**
     * Rate control default kini CBR (sama seperti OBS). Pengguna lama yang
     * masih menyimpan VBR dari default sebelumnya ikut dipindahkan sekali;
     * pilihan CBR/CQ dan pilihan setelah migrasi tidak pernah disentuh lagi.
     */
    private fun migrateRateControl() {
        val version = prefs.getInt(KEY_SETTINGS_VERSION, 0)
        if (version >= SETTINGS_VERSION) return
        val stored = prefs.getString(KEY_RATE_CONTROL, null)
        val editor = prefs.edit().putInt(KEY_SETTINGS_VERSION, SETTINGS_VERSION)
        if (stored == VideoBitrateMode.VBR.name) {
            editor.putString(KEY_RATE_CONTROL, VideoBitrateMode.CBR.name)
            DiagLog.line("setelan: rate control VBR lama dipindah ke CBR")
        }
        editor.apply()
    }

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
            .putInt(KEY_OUTPUT_WIDTH, config.outputWidth)
            .putInt(KEY_OUTPUT_HEIGHT, config.outputHeight)
            .putInt(KEY_KEYFRAME, config.keyframeIntervalSec)
            .putString(KEY_RATE_CONTROL, config.bitrateMode.name)
            .putInt(KEY_CQ_LEVEL, config.cqLevel)
            .putInt(KEY_RECORD_BITRATE, config.recordVideoBitrate)
            .putString(KEY_VIDEO_CODEC, config.videoCodec.name)
            .putBoolean(KEY_PREFER_HEVC, config.preferHevcRecording)
            .putString(KEY_PROFILE, config.videoProfile.name)
            .putString(KEY_COLOR_SPACE, config.colorSpace.name)
            .putString(KEY_COLOR_RANGE, config.colorRange.name)
            .putString(KEY_FILE_NAME, config.fileNameTemplate)
            .putBoolean(KEY_NO_SPACE, config.noSpaceFileName)
            .putBoolean(KEY_STATIC_THROTTLE, config.staticThrottleEnabled)
            .putBoolean(KEY_THERMAL_THROTTLE, config.thermalThrottleEnabled)
            .putInt(KEY_SPLIT_MINUTES, config.splitMinutes)
            .putInt(KEY_SPLIT_SIZE_MB, config.splitSizeMb)
            .putBoolean(KEY_SAFE_RECORDING, config.safeRecording)
            .putInt(KEY_SAFE_SEGMENT_MINUTES, config.safeSegmentMinutes)
            .putBoolean(KEY_REPLAY_ENABLED, config.replayBufferEnabled)
            .putInt(KEY_REPLAY_SECONDS, config.replaySeconds)
            .putInt(KEY_SETTINGS_VERSION, SETTINGS_VERSION)
            .apply()
    }

    /** Ukuran kanvas terakhir yang dipakai menyusun scene. */
    fun loadSceneCanvas(): Pair<Int, Int>? {
        val width = prefs.getInt(KEY_SCENE_CANVAS_W, 0)
        val height = prefs.getInt(KEY_SCENE_CANVAS_H, 0)
        return if (width > 0 && height > 0) width to height else null
    }

    /** Setup otomatis hanya muncul sendiri sekali. */
    fun isWizardDone(): Boolean = prefs.getBoolean(KEY_WIZARD_DONE, false)

    fun setWizardDone() {
        prefs.edit().putBoolean(KEY_WIZARD_DONE, true).apply()
    }

    /** Id koleksi scene yang terakhir dipakai. */
    fun activeCollection(): String =
        prefs.getString(KEY_ACTIVE_COLLECTION, SceneStore.DEFAULT_ID)
            ?: SceneStore.DEFAULT_ID

    fun setActiveCollection(id: String) {
        prefs.edit().putString(KEY_ACTIVE_COLLECTION, id).apply()
    }

    /** Pengenalan singkat sebelum aplikasi dipakai pertama kali. */
    fun isOnboardingDone(): Boolean = prefs.getBoolean(KEY_ONBOARDING_DONE, false)

    fun setOnboardingDone() {
        prefs.edit().putBoolean(KEY_ONBOARDING_DONE, true).apply()
    }

    /** Hasil setup otomatis terakhir, mis. "1080p60 · H.264 · 8800 kbps". */
    fun lastAutoConfig(): String? = prefs.getString(KEY_WIZARD_RESULT, null)

    /** Kunci aksen tema, mis. "cyan". */
    fun accentKey(): String? = prefs.getString(KEY_ACCENT, null)

    fun setAccentKey(key: String) {
        prefs.edit().putString(KEY_ACCENT, key).apply()
    }

    /** Bahasa pilihan pengguna: "in" atau "en". */
    fun languageTag(): String = prefs.getString(KEY_LANGUAGE, null) ?: "in"

    fun setLanguageTag(tag: String) {
        prefs.edit().putString(KEY_LANGUAGE, tag).apply()
    }

    /** Latar benar-benar hitam untuk layar AMOLED. */
    fun amoledBlack(): Boolean = prefs.getBoolean(KEY_AMOLED, false)

    fun setAmoledBlack(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AMOLED, enabled).apply()
    }
    fun setLastAutoConfig(text: String) {
        prefs.edit().putString(KEY_WIZARD_RESULT, text).apply()
    }

    fun saveSceneCanvas(width: Int, height: Int) {
        prefs.edit()
            .putInt(KEY_SCENE_CANVAS_W, width)
            .putInt(KEY_SCENE_CANVAS_H, height)
            .apply()
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
        const val KEY_SCENE_CANVAS_W = "scene_canvas_width"
        const val KEY_SCENE_CANVAS_H = "scene_canvas_height"

        const val KEY_OUTPUT_SCALE = "output_scale_percent"
        const val KEY_OUTPUT_WIDTH = "output_width"
        const val KEY_OUTPUT_HEIGHT = "output_height"
        const val KEY_KEYFRAME = "keyframe_interval"
        const val KEY_RATE_CONTROL = "rate_control"
        const val KEY_CQ_LEVEL = "cq_level"
        const val KEY_RECORD_BITRATE = "record_video_bitrate"
        const val KEY_VIDEO_CODEC = "video_codec"
        const val KEY_PREFER_HEVC = "prefer_hevc_recording"
        const val KEY_SETTINGS_VERSION = "settings_version"
        const val KEY_WIZARD_DONE = "auto_config_done"
        const val KEY_WIZARD_RESULT = "auto_config_result"
        const val KEY_ONBOARDING_DONE = "onboarding_done"
        const val KEY_ACTIVE_COLLECTION = "active_scene_collection"
        const val KEY_ACCENT = "theme_accent"
        const val KEY_AMOLED = "theme_amoled"
        const val KEY_LANGUAGE = "app_language"
        const val SETTINGS_VERSION = 1
        const val KEY_PROFILE = "video_profile"
        const val KEY_COLOR_SPACE = "color_space"
        const val KEY_COLOR_RANGE = "color_range"
        const val KEY_FILE_NAME = "file_name_template"
        const val KEY_NO_SPACE = "no_space_filename"
        const val KEY_STATIC_THROTTLE = "static_throttle"
        const val KEY_THERMAL_THROTTLE = "thermal_throttle"
        const val KEY_SPLIT_MINUTES = "split_minutes"
        const val KEY_SPLIT_SIZE_MB = "split_size_mb"
        const val KEY_SAFE_RECORDING = "safe_recording"
        const val KEY_SAFE_SEGMENT_MINUTES = "safe_segment_minutes"
        const val KEY_REPLAY_ENABLED = "replay_enabled"
        const val KEY_REPLAY_SECONDS = "replay_seconds"

        inline fun <reified T : Enum<T>> enumValue(name: String?, fallback: T): T =
            runCatching { enumValueOf<T>(name ?: fallback.name) }.getOrDefault(fallback)

        fun evenSide(value: Long): Int {
            val scaled = value.toInt().coerceAtLeast(2)
            return if (scaled % 2 == 0) scaled else scaled + 1
        }

        const val DEFAULT_WIDTH = 1920
        const val DEFAULT_HEIGHT = 1080
        const val DEFAULT_FPS = 30
        const val DEFAULT_BITRATE = 6_000_000
        const val DEFAULT_AUDIO_BITRATE = 128_000
    }
}
