package com.rout.air.core

import java.util.Locale
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Identitas kanal audio. Dua kanal bawaan adalah perangkat global; kanal
 * lainnya berasal dari sumber audio yang ditambahkan di panel Sumber.
 */
sealed interface AudioChannelId {
    val key: String
    val defaultName: String

    data object Internal : AudioChannelId {
        override val key = "internal"
        override val defaultName = "Audio internal"
    }

    data object Microphone : AudioChannelId {
        override val key = "microphone"
        override val defaultName = "Mikrofon"
    }

    data class Source(val sourceId: String) : AudioChannelId {
        override val key: String = "source:$sourceId"
        override val defaultName: String = "Sumber audio"
    }

    /** Kanal Application Audio Capture untuk satu aplikasi (UID Android). */
    data class App(val uid: Int, val packageName: String) : AudioChannelId {
        override val key: String = "app:$uid:$packageName"
        override val defaultName: String = "Audio aplikasi"
    }

    companion object {
        fun forSource(sourceId: String): AudioChannelId = Source(sourceId)

        fun forApp(uid: Int, packageName: String): AudioChannelId = App(uid, packageName)

        fun fromKey(key: String): AudioChannelId = when {
            key == Internal.key -> Internal
            key == Microphone.key -> Microphone
            key.startsWith("source:") -> Source(key.removePrefix("source:"))
            key.startsWith("app:") -> {
                val rest = key.removePrefix("app:")
                val separator = rest.indexOf(':')
                if (separator <= 0) {
                    App(0, rest)
                } else {
                    App(
                        uid = rest.substring(0, separator).toIntOrNull() ?: 0,
                        packageName = rest.substring(separator + 1),
                    )
                }
            }
            else -> Source(key)
        }
    }
}

/** Cara mengambil audio untuk sebuah kanal. */
enum class AudioCaptureKind(val label: String) {
    Playback("Audio output"),
    Microphone("Audio input"),
}


/** Tipe filter audio yang tersedia di dialog Filters. */
enum class AudioFilterType(val label: String) {
    Gain("Gain"),
    NoiseGate("Noise Gate"),
    Compressor("Compressor"),
    Limiter("Limiter"),
    InvertPolarity("Invert Polarity"),
    NoiseSuppression("Noise Suppression"),
    Equalizer("Equalizer 3-band");

    fun defaults(): Map<String, Float> = when (this) {
        Gain -> mapOf("gainDb" to 0f)
        NoiseGate -> mapOf(
            "openThresholdDb" to -26f,
            "closeThresholdDb" to -32f,
            "attackMs" to 5f,
            "holdMs" to 50f,
            "releaseMs" to 100f,
        )

        Compressor -> mapOf(
            "thresholdDb" to -18f,
            "ratio" to 4f,
            "attackMs" to 6f,
            "releaseMs" to 60f,
            "outputGainDb" to 0f,
        )

        Limiter -> mapOf("thresholdDb" to -6f, "releaseMs" to 60f)
        InvertPolarity -> emptyMap()
        NoiseSuppression -> mapOf("amount" to 50f)
        Equalizer -> mapOf(
            "lowDb" to 0f,
            "midDb" to 0f,
            "highDb" to 0f,
        )
    }
}

/** Satu filter pada rantai audio sebuah kanal. */
data class AudioFilter(
    val id: String,
    val type: AudioFilterType,
    val enabled: Boolean = true,
    val params: Map<String, Float> = type.defaults(),
) {
    fun param(key: String): Float = params[key] ?: type.defaults()[key] ?: 0f
}

/** Mode monitoring seperti API OBS terbaru: hanya on/off. */
enum class MonitorMode(val label: String) {
    Off("Mati"),
    MonitorOnly("Hanya monitor"),
    MonitorAndOutput("Monitor + output");

    val monitors: Boolean get() = this != Off
    val outputs: Boolean get() = this != MonitorOnly
}

/** Kecepatan turunnya peak hold pada meter. */
enum class MeterDecay(val label: String, val dbPerSecond: Float) {
    Fast("Cepat", 26f),
    Medium("Sedang", 16f),
    Slow("Lambat", 8f),
}

enum class PeakMeterType(val label: String) {
    Sample("Sample Peak"),
    TruePeak("True Peak"),
}

/**
 * Pengaturan satu kanal audio. Gain disimpan dalam dB agar sama dengan label
 * OBS, sedangkan konversi linear dilakukan saat mixing.
 */
data class AudioChannel(
    val id: AudioChannelId,
    val name: String = id.defaultName,
    val enabled: Boolean = id == AudioChannelId.Internal,
    val muted: Boolean = false,
    val gainDb: Float = 0f,
    val balance: Float = 0f,
    val monitor: MonitorMode = MonitorMode.Off,
    val pinned: Boolean = false,
    val hidden: Boolean = false,
    val locked: Boolean = false,
    val syncOffsetMs: Int = 0,
    val forceMono: Boolean = false,
    val tracks: Int = 0x01,
    val filters: List<AudioFilter> = emptyList(),
    val order: Int = when (id) {
        AudioChannelId.Internal -> 0
        AudioChannelId.Microphone -> 1
        else -> 0
    },
    val capture: AudioCaptureKind = if (id == AudioChannelId.Microphone) {
        AudioCaptureKind.Microphone
    } else {
        AudioCaptureKind.Playback
    },
    val deviceId: Int = 0,
    val deviceName: String? = null,
    val deviceType: Int = 0,
    val sco: Boolean = false,
    val appUid: Int = (id as? AudioChannelId.App)?.uid ?: 0,
    val appPackage: String? = (id as? AudioChannelId.App)?.packageName,
    val global: Boolean = id == AudioChannelId.Internal || id == AudioChannelId.Microphone,
    val sourceId: String? = (id as? AudioChannelId.Source)?.sourceId,
)

/** Keseluruhan mixer: sakelar induk, kanal, dan preferensi tampilan. */
data class AudioMix(
    val masterEnabled: Boolean = true,
    val channels: List<AudioChannel> = defaultChannels(),
    val verticalLayout: Boolean = false,
    val showHidden: Boolean = false,
    val showInactive: Boolean = true,
    val keepHiddenLast: Boolean = true,
    val keepInactiveLast: Boolean = false,
    val outputTracks: Int = 0x01,
    val meterDecay: MeterDecay = MeterDecay.Medium,
    val peakMeterType: PeakMeterType = PeakMeterType.Sample,
    val monitoringDeviceId: Int = 0,
    val volumePercent: Boolean = false,
    val pushToMuteEnabled: Boolean = false,
    val pushToTalkEnabled: Boolean = false,
) {
    fun channel(id: AudioChannelId): AudioChannel =
        channels.firstOrNull { it.id == id } ?: AudioChannel(id)

    fun update(channel: AudioChannel): AudioMix = copy(
        channels = channels.map { if (it.id == channel.id) channel else it },
    )

    fun withMasterEnabled(enabled: Boolean): AudioMix = copy(masterEnabled = enabled)

    fun withVerticalLayout(vertical: Boolean): AudioMix = copy(verticalLayout = vertical)

    fun enabledChannels(): List<AudioChannel> = channels.filter { it.enabled && !it.muted }

    /** Bitmask track output yang valid (hanya 6 track). */
    fun safeOutputTracks(): Int = (outputTracks and 0x3F).let {
        if (it == 0) 0x01 else it
    }

    /** Potret beku untuk thread audio; UI boleh berubah kapan saja. */
    fun snapshot(
        pushToMuteActive: Boolean = false,
        pushToTalkActive: Boolean = false,
    ): MixSnapshot = MixSnapshot(
        masterEnabled = masterEnabled,
        channels = channels.associate { it.id to it.toMix() },
        outputTracks = safeOutputTracks(),
        peakMeterType = peakMeterType,
        monitoringDeviceId = monitoringDeviceId,
        pushToMuteActive = pushToMuteActive,
        pushToTalkActive = pushToTalkActive,
    )

    companion object {
        fun defaultChannels(): List<AudioChannel> = listOf(
            AudioChannel(AudioChannelId.Internal, order = 0),
            AudioChannel(AudioChannelId.Microphone, order = 1),
        )
    }
}

private fun AudioChannel.toMix(): ChannelMix = ChannelMix(
    enabled = enabled,
    muted = muted,
    gainDb = gainDb,
    balance = balance,
    monitor = monitor,
    syncOffsetMs = syncOffsetMs,
    forceMono = forceMono,
    tracks = tracks,
    filters = filters,
    capture = capture,
    deviceId = deviceId,
    sco = sco,
    captureUid = appUid,
)

/** Pengaturan kanal yang dibaca thread audio setiap potongan PCM. */
data class ChannelMix(
    val enabled: Boolean,
    val muted: Boolean,
    val gainDb: Float,
    val balance: Float,
    val monitor: MonitorMode,
    val syncOffsetMs: Int,
    val forceMono: Boolean,
    val tracks: Int,
    val filters: List<AudioFilter>,
    val capture: AudioCaptureKind,
    val deviceId: Int,
    val sco: Boolean,
    val captureUid: Int,
) {
    val audible: Boolean get() = enabled && !muted && gainDb > FaderScale.MIN_DB + 0.05f

    val gainLinear: Float
        get() = if (gainDb <= FaderScale.MIN_DB + 0.05f) {
            0f
        } else {
            10f.pow(gainDb / 20f)
        }

    /** Pengali kanal kiri/kanan dari balance -1..+1. */
    val leftGain: Float get() = gainLinear * (1f - balance.coerceIn(0f, 1f))
    val rightGain: Float get() = gainLinear * (1f + balance.coerceIn(-1f, 0f))
}

data class MixSnapshot(
    val masterEnabled: Boolean,
    val channels: Map<AudioChannelId, ChannelMix>,
    val outputTracks: Int,
    val peakMeterType: PeakMeterType,
    val monitoringDeviceId: Int,
    val pushToMuteActive: Boolean,
    val pushToTalkActive: Boolean,
) {
    fun forChannel(id: AudioChannelId): ChannelMix = channels[id] ?: ChannelMix(
        enabled = false,
        muted = true,
        gainDb = FaderScale.MIN_DB,
        balance = 0f,
        monitor = MonitorMode.Off,
        syncOffsetMs = 0,
        forceMono = false,
        tracks = 0,
        filters = emptyList(),
        capture = AudioCaptureKind.Playback,
        deviceId = 0,
        sco = false,
        captureUid = 0,
    )
}

/**
 * Skala fader logaritmik yang dipakai OBS (`OBS_FADER_LOG`).
 *
 * Deflection 0..1 memetakan -96 dB sampai 0 dB dengan kurva log, sehingga
 * bagian atas fader punya resolusi lebih halus untuk penyetelan halus.
 */
object FaderScale {
    const val MIN_DB = -96f
    const val MAX_DB = 0f

    /** Batas volume di Advanced Audio Properties, seperti OBS (+26 dB). */
    const val MAX_ADV_DB = 26f

    private const val LOG_OFFSET_DB = 6.0
    private const val LOG_RANGE_DB = 96.0
    private const val LOG_OFFSET_VAL = -0.77815125038364363
    private const val LOG_RANGE_VAL = -2.00860017176191756

    fun deflectionToDb(deflection: Float): Float {
        val def = deflection.coerceIn(0f, 1f)
        if (def >= 1f) return MAX_DB
        if (def <= 0f) return MIN_DB
        val span = LOG_RANGE_DB + LOG_OFFSET_DB
        return (-span * (span / LOG_OFFSET_DB).pow(-def.toDouble()) + LOG_OFFSET_DB).toFloat()
            .coerceIn(MIN_DB, MAX_DB)
    }

    fun dbToDeflection(db: Float): Float {
        if (db >= MAX_DB) return 1f
        if (db <= MIN_DB) return 0f
        val value = (-log10(-db + LOG_OFFSET_DB) - LOG_RANGE_VAL) /
            (LOG_OFFSET_VAL - LOG_RANGE_VAL)
        return value.toFloat().coerceIn(0f, 1f)
    }

    /** Membulatkan ke 0,1 dB seperti label OBS. */
    fun snapDb(db: Float): Float {
        if (db <= MIN_DB) return MIN_DB
        if (db >= MAX_DB) return MAX_DB
        return ((db * 10f).roundToInt() / 10f).coerceIn(MIN_DB, MAX_DB)
    }

    /** Pembulatan untuk spinbox Advanced yang boleh melewati 0 dB. */
    fun snapDbWide(db: Float): Float =
        ((db * 10f).roundToInt() / 10f).coerceIn(MIN_DB, MAX_ADV_DB)

    fun formatDb(db: Float): String =
        if (db <= MIN_DB + 0.05f) {
            "-∞ dB"
        } else {
            String.format(Locale.US, "%.1f dB", db)
        }
}

/** Level satu kanal, L/R terpisah seperti meter OBS. */
data class ChannelLevel(
    val leftDb: Float = FaderScale.MIN_DB,
    val rightDb: Float = FaderScale.MIN_DB,
    val peakLeftDb: Float = FaderScale.MIN_DB,
    val peakRightDb: Float = FaderScale.MIN_DB,
) {
    val maxDb: Float get() = max(leftDb, rightDb)
    val maxPeakDb: Float get() = max(peakLeftDb, peakRightDb)
}

/** Level meter terakhir per kanal, dalam dB (-96..0). */
data class AudioLevels(
    val channels: Map<AudioChannelId, ChannelLevel> = emptyMap(),
) {
    fun forChannel(id: AudioChannelId): ChannelLevel = channels[id] ?: ChannelLevel()

    fun db(id: AudioChannelId): Float = forChannel(id).maxDb
}

/** Status mesin audio saat sesi berjalan. */
data class AudioRuntime(
    val running: Boolean = false,
    val ready: Set<AudioChannelId> = emptySet(),
) {
    fun ready(id: AudioChannelId): Boolean = ready.contains(id)
}
