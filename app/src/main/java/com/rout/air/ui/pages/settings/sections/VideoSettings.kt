package com.rout.air.ui.pages.settings.sections

import android.os.Build
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rout.air.core.model.CanvasOrientation
import com.rout.air.core.model.CaptureConfig
import com.rout.air.core.model.ColorRangeMode
import com.rout.air.core.model.ColorSpaceMode
import com.rout.air.core.model.ContentMode
import com.rout.air.core.model.VideoBitrateMode
import com.rout.air.core.model.VideoCodec
import com.rout.air.core.model.VideoProfile
import com.rout.air.encode.probe.EncoderProbe
import androidx.compose.ui.res.stringResource
import com.rout.air.R
import kotlin.math.roundToInt
import com.rout.air.core.model.Crop
import com.rout.air.ui.pages.settings.BitrateTarget
import com.rout.air.ui.components.Hint
import com.rout.air.ui.pages.settings.components.InfoRow
import com.rout.air.ui.pages.settings.components.SectionTitle
import com.rout.air.ui.pages.settings.components.SettingSwitchRow
import com.rout.air.ui.pages.settings.components.bitrateText
import com.rout.air.ui.util.contentModeLabel

@Composable
internal fun VideoSettings(
    config: CaptureConfig,
    canvasConfig: CaptureConfig,
    onConfigChange: (CaptureConfig) -> Unit,
) {
    SectionTitle(stringResource(R.string.settings_base_canvas_resolution))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(1920 to 1080, 1280 to 720).forEach { (width, height) ->
            FilterChip(
                selected = config.width == width && config.height == height,
                onClick = {
                    onConfigChange(
                        config.copy(
                            width = width,
                            height = height,
                            videoBitrate = if (width == 1280) 4_000_000 else 6_000_000,
                        ),
                    )
                },
                label = { Text("${height}p") },
            )
        }
    }

    Spacer(Modifier.height(16.dp))
    SectionTitle(stringResource(R.string.settings_orientasi_video))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            CanvasOrientation.Auto to stringResource(R.string.settings_otomatis),
            CanvasOrientation.Portrait to stringResource(R.string.common_vertikal),
            CanvasOrientation.Landscape to stringResource(R.string.common_horizontal),
        ).forEach { (value, label) ->
            FilterChip(
                selected = config.canvasOrientation == value,
                onClick = { onConfigChange(config.copy(canvasOrientation = value)) },
                label = { Text(label) },
            )
        }
    }
    Hint(stringResource(R.string.settings_orientation_hint))

    Spacer(Modifier.height(16.dp))
    SectionTitle(stringResource(R.string.settings_mode_isi))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            ContentMode.Fit to stringResource(R.string.common_pas_letterbox),
            ContentMode.Crop to stringResource(R.string.common_penuh_crop),
            ContentMode.Stretch to stringResource(R.string.common_peregangan),
        ).forEach { (value, label) ->
            FilterChip(
                selected = config.contentMode == value,
                onClick = { onConfigChange(config.copy(contentMode = value)) },
                label = { Text(label) },
            )
        }
    }
    Hint(stringResource(R.string.settings_content_mode_hint))

    Spacer(Modifier.height(16.dp))
    SectionTitle(stringResource(R.string.settings_fps))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(30, 60).forEach { fps ->
            FilterChip(
                selected = config.fps == fps,
                onClick = { onConfigChange(config.copy(fps = fps)) },
                label = { Text("$fps") },
            )
        }
    }

    Spacer(Modifier.height(16.dp))
    SectionTitle(stringResource(R.string.settings_output_resolution))
    InfoRow(
        stringResource(R.string.settings_encoder_resolution),
        if (config.rescaled) {
            "${config.encoderWidth}x${config.encoderHeight}"
        } else {
            stringResource(R.string.settings_same_as_canvas)
        },
    )
    Hint(stringResource(R.string.settings_output_resolution_hint))

    Spacer(Modifier.height(16.dp))
    SectionTitle(stringResource(R.string.settings_color_space))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ColorSpaceMode.entries.forEach { mode ->
            FilterChip(
                selected = config.colorSpace == mode,
                onClick = { onConfigChange(config.copy(colorSpace = mode)) },
                label = { Text(mode.name) },
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    SectionTitle(stringResource(R.string.settings_color_range))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ColorRangeMode.entries.forEach { mode ->
            FilterChip(
                selected = config.colorRange == mode,
                onClick = { onConfigChange(config.copy(colorRange = mode)) },
                label = {
                    Text(
                        when (mode) {
                            ColorRangeMode.Limited ->
                                stringResource(R.string.settings_color_range_limited)
                            ColorRangeMode.Full ->
                                stringResource(R.string.settings_color_range_full)
                        },
                    )
                },
            )
        }
    }

    Spacer(Modifier.height(16.dp))
    SectionTitle(stringResource(R.string.settings_kanvas_aktif))
    InfoRow(
        stringResource(R.string.settings_size),
        "${canvasConfig.width}x${canvasConfig.height}",
    )
    InfoRow(
        stringResource(R.string.settings_ratio),
        String.format("%.3f", canvasConfig.aspectRatio),
    )
    InfoRow(stringResource(R.string.settings_mode_isi), contentModeLabel(canvasConfig))
    Hint(stringResource(R.string.settings_output_scaled_resolution_saat_ini_sama_d))
}

/**
 * Setelan video dipakai bersama live dan rekam karena hanya ada satu
 * encoder. Ditampilkan di tab Streaming dan Recording, tidak di tab audio.
 *
 * [target] menentukan bitrate mana yang diatur di blok ini, supaya tiap tab
 * hanya punya satu kontrol bitrate dan tidak ada dua slider kembar.
 */
@Composable
internal fun VideoEncodeSection(
    config: CaptureConfig,
    onConfigChange: (CaptureConfig) -> Unit,
    target: BitrateTarget = BitrateTarget.Streaming,
) {
    Spacer(Modifier.height(20.dp))
    SectionTitle(stringResource(R.string.settings_video_encoder))
    Hint(stringResource(R.string.settings_output_shared_hint))
    CodecPicker(config = config, onConfigChange = onConfigChange)

    Spacer(Modifier.height(16.dp))
    when (target) {
        BitrateTarget.Streaming -> {
            SectionTitle(stringResource(R.string.settings_bitrate_video))
            BitrateSlider(
                bitrate = config.videoBitrate,
                onChange = { onConfigChange(config.copy(videoBitrate = it)) },
            )
        }

        BitrateTarget.Recording -> {
            SectionTitle(stringResource(R.string.settings_bitrate_rekaman))
            SettingSwitchRow(
                title = stringResource(R.string.settings_same_as_streaming),
                subtitle = stringResource(R.string.settings_same_as_streaming_desc),
                checked = config.recordVideoBitrate == 0,
            ) { same ->
                onConfigChange(
                    config.copy(recordVideoBitrate = if (same) 0 else config.videoBitrate),
                )
            }
            if (config.recordVideoBitrate > 0) {
                BitrateSlider(
                    bitrate = config.recordVideoBitrate,
                    onChange = { onConfigChange(config.copy(recordVideoBitrate = it)) },
                )
            } else {
                // Nilai hanya ditampilkan karena mengikuti setelan streaming.
                InfoRow(stringResource(R.string.settings_bitrate), bitrateText(config.videoBitrate))
            }
            Hint(stringResource(R.string.settings_bitrate_rekaman_hint))
        }
    }

    Spacer(Modifier.height(12.dp))
    SectionTitle(stringResource(R.string.settings_rate_control))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        VideoBitrateMode.entries.forEach { mode ->
            FilterChip(
                selected = config.bitrateMode == mode,
                onClick = { onConfigChange(config.copy(bitrateMode = mode)) },
                label = { Text(mode.name) },
            )
        }
    }
    if (config.bitrateMode == VideoBitrateMode.CQ) {
        Spacer(Modifier.height(8.dp))
        SectionTitle(stringResource(R.string.settings_cq_level))
        // Tanpa steps: tanda tick rapat akan menutupi track.
        Slider(
            value = config.cqLevel.toFloat(),
            onValueChange = {
                onConfigChange(config.copy(cqLevel = it.roundToInt().coerceIn(0, 51)))
            },
            valueRange = 0f..51f,
        )
        InfoRow(stringResource(R.string.settings_quality), "${config.cqLevel}")
    }
    Hint(stringResource(R.string.settings_rate_control_hint))
    Hint(stringResource(R.string.settings_rate_control_cq_hint))

    Spacer(Modifier.height(12.dp))
    SectionTitle(stringResource(R.string.settings_keyframe_interval))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(0, 1, 2, 4).forEach { seconds ->
            FilterChip(
                selected = config.keyframeIntervalSec == seconds,
                onClick = { onConfigChange(config.copy(keyframeIntervalSec = seconds)) },
                label = {
                    Text(
                        if (seconds == 0) {
                            stringResource(R.string.settings_auto)
                        } else {
                            "$seconds s"
                        },
                    )
                },
            )
        }
    }

    Spacer(Modifier.height(12.dp))
    SectionTitle(stringResource(R.string.settings_profile))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        VideoProfile.entries.forEach { profile ->
            FilterChip(
                selected = config.videoProfile == profile,
                onClick = { onConfigChange(config.copy(videoProfile = profile)) },
                label = { Text(profile.name) },
            )
        }
    }
    Hint(stringResource(R.string.settings_profile_hint))

    Spacer(Modifier.height(12.dp))
    SectionTitle(stringResource(R.string.settings_output_resolution))
    ResolutionPicker(config = config, onConfigChange = onConfigChange)
    Hint(stringResource(R.string.settings_output_resolution_hint))
}

/**
 * Pilihan codec dengan status nyata dari MediaCodec. Codec yang tidak
 * tersedia tetap tampil (nonaktif) supaya alasannya bisa dibaca, bukan
 * disembunyikan.
 */
@Composable
internal fun CodecPicker(
    config: CaptureConfig,
    onConfigChange: (CaptureConfig) -> Unit,
) {
    val caps = remember { EncoderProbe.all() }
    // Pemindaian gagal bukan alasan menonaktifkan semua codec.
    val known = caps.isNotEmpty()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        VideoCodec.entries.forEach { codec ->
            val available = EncoderProbe.select(caps, codec)
            FilterChip(
                selected = config.videoCodec == codec,
                enabled = !known || available != null,
                onClick = { onConfigChange(config.copy(videoCodec = codec)) },
                label = { Text(codecChipLabel(codec)) },
            )
        }
    }

    Spacer(Modifier.height(4.dp))
    if (known) {
        VideoCodec.entries.forEach { codec ->
            val available = EncoderProbe.select(caps, codec)
            val status = when {
                available != null ->
                    "${available.name} • ${if (available.hardware) "hardware" else "software"}"

                codec == VideoCodec.AV1 && caps.any { it.codec == VideoCodec.AV1 && !it.hardware } ->
                    stringResource(R.string.settings_codec_av1_software_only)

                codec == VideoCodec.AV1 ->
                    stringResource(R.string.settings_codec_av1_hardware_only)

                else -> stringResource(R.string.settings_codec_not_available)
            }
            Text(
                "${codec.label}: $status",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Hint(stringResource(R.string.settings_codec_probe_hint))
        Text(
            codecDescription(config.videoCodec),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (config.videoCodec != VideoCodec.AVC) {
            Hint(stringResource(R.string.settings_codec_live_h264_only))
        }
        val hevcHardware = EncoderProbe.select(caps, VideoCodec.HEVC)?.hardware == true
        if (hevcHardware) {
            SettingSwitchRow(
                title = stringResource(R.string.settings_prefer_hevc),
                subtitle = stringResource(R.string.settings_prefer_hevc_desc),
                checked = config.preferHevcRecording,
            ) { onConfigChange(config.copy(preferHevcRecording = it)) }
        } else {
            Hint(stringResource(R.string.settings_prefer_hevc_unavailable))
        }
    }

    if (config.videoCodec == VideoCodec.AV1 && Build.VERSION.SDK_INT < 34) {
        Hint(stringResource(R.string.settings_codec_av1_mp4_android14))
    }
    if (config.videoCodec.enhanced) {
        Hint(stringResource(R.string.settings_codec_enhanced_rtmp_hint))
    }
    if (config.videoCodec == VideoCodec.AV1) {
        Hint(stringResource(R.string.settings_codec_av1_hint))
    }
}

/** Nama codec yang mudah dibedakan di pemilih. */
@Composable
internal fun codecChipLabel(codec: VideoCodec): String = stringResource(
    when (codec) {
        VideoCodec.AVC -> R.string.settings_codec_avc_label
        VideoCodec.HEVC -> R.string.settings_codec_hevc_label
        VideoCodec.AV1 -> R.string.settings_codec_av1_label
    },
)

/** Keterangan singkat codec yang sedang dipilih. */
@Composable
internal fun codecDescription(codec: VideoCodec): String = stringResource(
    when (codec) {
        VideoCodec.AVC -> R.string.settings_codec_desc_avc
        VideoCodec.HEVC -> R.string.settings_codec_desc_hevc
        VideoCodec.AV1 -> R.string.settings_codec_desc_av1
    },
)

/** Slider bitrate 1–50 Mbps dengan langkah 0,5 Mbps seperti spinbox OBS. */
@Composable
internal fun BitrateSlider(bitrate: Int, onChange: (Int) -> Unit) {
    Text(
        String.format("%.1f Mbps", bitrate / 1_000_000f),
        style = MaterialTheme.typography.bodyMedium,
    )
    Slider(
        value = (bitrate / 1_000_000f).coerceIn(1f, 50f),
        // Dibulatkan 0,5 Mbps tanpa steps agar track tidak penuh titik.
        onValueChange = {
            onChange(((it * 2f).roundToInt() / 2f * 1_000_000).toInt())
        },
        valueRange = 1f..50f,
    )
}

/**
 * Rescale Output: preset resolusi atau ukuran bebas. Nilai 0/0 berarti
 * encoder mengikuti ukuran kanvas.
 */
@Composable
internal fun ResolutionPicker(
    config: CaptureConfig,
    onConfigChange: (CaptureConfig) -> Unit,
) {
    val presets = listOf(0 to 0, 1920 to 1080, 1280 to 720, 854 to 480)
    val isCustom = config.outputWidth > 0 &&
        presets.none { it.first == config.outputWidth && it.second == config.outputHeight }

    var widthText by remember(config.outputWidth) {
        mutableStateOf(config.outputWidth.takeIf { it > 0 }?.toString().orEmpty())
    }
    var heightText by remember(config.outputHeight) {
        mutableStateOf(config.outputHeight.takeIf { it > 0 }?.toString().orEmpty())
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        presets.forEach { (width, height) ->
            FilterChip(
                selected = config.outputWidth == width && config.outputHeight == height,
                onClick = {
                    onConfigChange(config.copy(outputWidth = width, outputHeight = height))
                },
                label = {
                    Text(
                        if (width == 0) {
                            stringResource(R.string.settings_same_as_canvas)
                        } else {
                            "${height}p"
                        },
                    )
                },
            )
        }
        FilterChip(
            selected = isCustom,
            onClick = {
                if (!isCustom) {
                    widthText = config.encoderWidth.toString()
                    heightText = config.encoderHeight.toString()
                    onConfigChange(
                        config.copy(
                            outputWidth = config.encoderWidth,
                            outputHeight = config.encoderHeight,
                        ),
                    )
                }
            },
            label = { Text(stringResource(R.string.settings_custom)) },
        )
    }

    if (isCustom) {
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = widthText,
                onValueChange = {
                    widthText = it.filter { ch -> ch.isDigit() }.take(5)
                    applyCustomSize(config, widthText, heightText, onConfigChange)
                },
                label = { Text(stringResource(R.string.settings_lebar)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = heightText,
                onValueChange = {
                    heightText = it.filter { ch -> ch.isDigit() }.take(5)
                    applyCustomSize(config, widthText, heightText, onConfigChange)
                },
                label = { Text(stringResource(R.string.settings_tinggi)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

internal fun applyCustomSize(
    config: CaptureConfig,
    widthText: String,
    heightText: String,
    onConfigChange: (CaptureConfig) -> Unit,
) {
    val width = widthText.toIntOrNull() ?: return
    val height = heightText.toIntOrNull() ?: return
    if (width < 16 || height < 16) return
    onConfigChange(config.copy(outputWidth = width, outputHeight = height))
}
