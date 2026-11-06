package com.rout.air.ui.components.audio

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.rout.air.core.audio.AudioChannel
import com.rout.air.ui.theme.AccentCyan
import com.rout.air.ui.theme.TextPrimary
import com.rout.air.ui.theme.TextTertiary
import kotlin.math.pow
import kotlin.math.roundToInt
import androidx.compose.ui.res.stringResource
import com.rout.air.R

/** Kategori chip yang mengikuti prioritas OBS. */
internal enum class ChannelCategory {
    Unassigned,
    Pinned,
    Hidden,
    Active,
    Inactive,
}

@Composable
internal fun filterLabel(type: com.rout.air.core.audio.AudioFilterType): String = when (type) {
    com.rout.air.core.audio.AudioFilterType.Gain -> stringResource(R.string.audio_filter_gain)
    com.rout.air.core.audio.AudioFilterType.NoiseGate -> stringResource(R.string.audio_filter_noise_gate)
    com.rout.air.core.audio.AudioFilterType.Compressor -> stringResource(R.string.audio_filter_compressor)
    com.rout.air.core.audio.AudioFilterType.Limiter -> stringResource(R.string.audio_filter_limiter)
    com.rout.air.core.audio.AudioFilterType.InvertPolarity -> stringResource(R.string.audio_filter_invert)
    com.rout.air.core.audio.AudioFilterType.NoiseSuppression -> stringResource(R.string.audio_filter_suppression)
    com.rout.air.core.audio.AudioFilterType.Equalizer -> stringResource(R.string.audio_filter_equalizer)
}

@Composable
internal fun decayLabel(decay: com.rout.air.core.audio.MeterDecay): String = when (decay) {
    com.rout.air.core.audio.MeterDecay.Fast -> stringResource(R.string.audio_decay_fast)
    com.rout.air.core.audio.MeterDecay.Medium -> stringResource(R.string.audio_decay_medium)
    com.rout.air.core.audio.MeterDecay.Slow -> stringResource(R.string.audio_decay_slow)
}

@Composable
internal fun peakMeterLabel(type: com.rout.air.core.audio.PeakMeterType): String = when (type) {
    com.rout.air.core.audio.PeakMeterType.Sample -> stringResource(R.string.audio_peak_sample)
    com.rout.air.core.audio.PeakMeterType.TruePeak -> stringResource(R.string.audio_peak_true)
}

@Composable
internal fun categoryLabel(category: ChannelCategory): String = when (category) {
    ChannelCategory.Unassigned -> stringResource(R.string.audio_tanpa_track)
    ChannelCategory.Pinned -> stringResource(R.string.audio_disematkan)
    ChannelCategory.Hidden -> stringResource(R.string.audio_disembunyikan)
    ChannelCategory.Active -> stringResource(R.string.common_aktif)
    ChannelCategory.Inactive -> stringResource(R.string.common_nonaktif)
}

internal data class CategoryStyle(
    val background: Color,
    val foreground: Color,
)

internal fun categoryFor(channel: AudioChannel, active: Boolean): ChannelCategory = when {
    channel.tracks and 0x3F == 0 -> ChannelCategory.Unassigned
    channel.pinned -> ChannelCategory.Pinned
    channel.hidden -> ChannelCategory.Hidden
    active -> ChannelCategory.Active
    else -> ChannelCategory.Inactive
}

internal fun channelSubtitle(channel: AudioChannel): String = when {
    channel.appUid != 0 -> channel.appPackage ?: "Tangkap audio aplikasi"
    channel.capture == com.rout.air.core.audio.AudioCaptureKind.Microphone ->
        channel.deviceName ?: "Mikrofon default"
    channel.sourceId != null -> "Sumber audio"
    else -> "Semua aplikasi (playback)"
}

internal fun categoryStyle(category: ChannelCategory): CategoryStyle = when (category) {
    ChannelCategory.Active -> CategoryStyle(AccentCyan.copy(alpha = 0.18f), AccentCyan)
    ChannelCategory.Pinned -> CategoryStyle(AccentCyan.copy(alpha = 0.3f), TextPrimary)
    ChannelCategory.Hidden -> CategoryStyle(Color(0xFF2E1A60), Color(0xFF997FDC))
    ChannelCategory.Unassigned -> CategoryStyle(Color(0xFF493709), Color(0xFFEABC48))
    ChannelCategory.Inactive -> CategoryStyle(Color(0xFF13141A), TextTertiary)
}

internal fun percentOfDb(gainDb: Float): Int =
    (10f.pow(gainDb / 20f) * 100f).roundToInt()
