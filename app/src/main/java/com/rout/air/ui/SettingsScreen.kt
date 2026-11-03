package com.rout.air.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.rout.air.core.AudioChannel
import com.rout.air.core.AudioChannelId
import com.rout.air.core.AudioMix
import com.rout.air.core.CanvasOrientation
import com.rout.air.core.CaptureConfig
import com.rout.air.core.ColorRangeMode
import com.rout.air.core.ColorSpaceMode
import com.rout.air.core.ContentMode
import com.rout.air.core.LiveConfig
import com.rout.air.core.LiveService
import com.rout.air.core.MeterDecay
import com.rout.air.core.PeakMeterType
import com.rout.air.core.SaveLocation
import com.rout.air.core.VideoBitrateMode
import com.rout.air.core.VideoCodec
import com.rout.air.core.VideoProfile
import com.rout.air.encode.EncoderBenchmark
import com.rout.air.encode.EncoderProbe
import com.rout.air.encode.GameLoadBenchmark
import com.rout.air.data.AudioMixer
import com.rout.air.data.DiagLog
import com.rout.air.data.SettingsStore
import com.rout.air.live.RtmpTester
import com.rout.air.live.RtmpTestResult
import com.rout.air.live.GoogleAuth
import com.rout.air.live.GoogleAuthStore
import com.rout.air.live.GoogleTokens
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import androidx.activity.result.IntentSenderRequest
import com.rout.air.live.YouTubeApi
import androidx.compose.ui.res.stringResource
import com.rout.air.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private enum class SettingsPage(val title: String) {
    Root("Setelan"),
    General("General"),
    Stream("Stream"),
    Output("Output"),
    Audio("Audio"),
    Video("Video"),
    Advanced("Advanced"),
}

/**
 * Setelan mengikuti struktur OBS: Video, Output, Audio, Hotkeys, Advanced.
 * Dipisah per halaman karena layar HP tidak cukup untuk satu dialog panjang.
 */
@Composable
fun SettingsScreen(
    config: CaptureConfig,
    canvasConfig: CaptureConfig,
    mix: AudioMix,
    liveConfig: LiveConfig,
    onConfigChange: (CaptureConfig) -> Unit,
    onMixChange: (AudioChannel) -> Unit,
    onMasterEnabledChange: (Boolean) -> Unit,
    onLiveConfigChange: (LiveConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    var page by remember { mutableStateOf(SettingsPage.Root) }
    // Satu posisi gulir untuk seluruh halaman; tab Output memakainya untuk
    // kembali ke atas saat berpindah tab.
    val scrollState = rememberScrollState()

    BackHandler(enabled = page != SettingsPage.Root) { page = SettingsPage.Root }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        if (page == SettingsPage.Root) {
            Text(stringResource(R.string.settings_setelan), style = MaterialTheme.typography.headlineSmall)
            Text(
                text = stringResource(R.string.settings_preset_rekaman_dan_perangkat),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            SettingsEntry("General", "Bahasa, tema, konfirmasi") {
                page = SettingsPage.General
            }
            SettingsEntry("Stream", "Service, server, stream key, tes koneksi") {
                page = SettingsPage.Stream
            }
            SettingsEntry("Output", "Streaming/Recording, encoder, bitrate, format") {
                page = SettingsPage.Output
            }
            SettingsEntry("Audio", "Sample rate, kanal, perangkat, meter") {
                page = SettingsPage.Audio
            }
            SettingsEntry("Video", "Kanvas, output resolution, fps, warna") {
                page = SettingsPage.Video
            }
            SettingsEntry("Advanced", "Performa, informasi teknis, diagnostik") {
                page = SettingsPage.Advanced
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { page = SettingsPage.Root }) { Text(stringResource(R.string.common_kembali_panah)) }
                Text(page.title, style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.height(8.dp))

            when (page) {
                SettingsPage.Video -> VideoSettings(config, canvasConfig, onConfigChange)
                SettingsPage.Output -> OutputSettings(
                    config = config,
                    mix = mix,
                    live = liveConfig,
                    scrollState = scrollState,
                    onConfigChange = onConfigChange,
                    onLiveConfigChange = onLiveConfigChange,
                )
                SettingsPage.General -> GeneralSettings()
                SettingsPage.Stream -> LiveSettings(liveConfig, onLiveConfigChange)
                SettingsPage.Audio -> AudioSettings(
                    config = config,
                    mix = mix,
                    onMixChange = onMixChange,
                    onMasterEnabledChange = onMasterEnabledChange,
                    onConfigChange = onConfigChange,
                )
                SettingsPage.Advanced -> AdvancedSettings(
                    config = config,
                    canvasConfig = canvasConfig,
                    live = liveConfig,
                    onConfigChange = onConfigChange,
                )
                SettingsPage.Root -> Unit
            }
        }
    }
}

@Composable
private fun SettingsEntry(title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(stringResource(R.string.settings_teks), style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun VideoSettings(
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
            CanvasOrientation.Auto to "Auto",
            CanvasOrientation.Portrait to "Vertikal",
            CanvasOrientation.Landscape to "Horizontal",
        ).forEach { (value, label) ->
            FilterChip(
                selected = config.canvasOrientation == value,
                onClick = { onConfigChange(config.copy(canvasOrientation = value)) },
                label = { Text(label) },
            )
        }
    }
    Hint(
        "Auto mengikuti orientasi HP saat rekaman dimulai. Vertikal/Horizontal " +
            "mengunci bentuk video; layar tetap bebas diputar.",
    )

    Spacer(Modifier.height(16.dp))
    SectionTitle(stringResource(R.string.settings_mode_isi))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            ContentMode.Fit to "Pas",
            ContentMode.Crop to "Penuh",
            ContentMode.Stretch to "Peregangan",
        ).forEach { (value, label) ->
            FilterChip(
                selected = config.contentMode == value,
                onClick = { onConfigChange(config.copy(contentMode = value)) },
                label = { Text(label) },
            )
        }
    }
    Hint(
        "Dipakai saat rasio layar beda dengan video: Pas = bar hitam, " +
            "Penuh = dipotong, Peregangan = dipaksa penuh.",
    )

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
        "Resolusi encoder",
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
                            ColorRangeMode.Limited -> "Limited (16-235)"
                            ColorRangeMode.Full -> "Full (0-255)"
                        },
                    )
                },
            )
        }
    }

    Spacer(Modifier.height(16.dp))
    SectionTitle(stringResource(R.string.settings_kanvas_aktif))
    InfoRow("Ukuran", "${canvasConfig.width}x${canvasConfig.height}")
    InfoRow("Rasio", String.format("%.3f", canvasConfig.aspectRatio))
    InfoRow("Mode isi", contentModeLabel(canvasConfig))
    Hint(stringResource(R.string.settings_output_scaled_resolution_saat_ini_sama_d))
}

private enum class OutputTab(val label: String) {
    Streaming("Streaming"),
    Recording("Recording"),
    Audio("Audio Output"),
}

/** Bitrate yang diatur blok setelan video pada tab yang sedang dibuka. */
private enum class BitrateTarget { Streaming, Recording }

/**
 * Halaman Output dipisah per tab. Tiap tab dibuka dengan ringkasan dan
 * bagian khususnya sendiri, baru blok setelan video bersama, sehingga
 * perbedaan antartab langsung terlihat tanpa perlu menggulir.
 */
@Composable
private fun OutputSettings(
    config: CaptureConfig,
    mix: AudioMix,
    live: LiveConfig,
    scrollState: ScrollState,
    onConfigChange: (CaptureConfig) -> Unit,
    onLiveConfigChange: (LiveConfig) -> Unit,
) {
    var tab by remember { mutableStateOf(OutputTab.Streaming) }
    // Kembali ke atas saat tab diganti supaya isi tab baru tidak tertutup
    // posisi gulir dari tab sebelumnya.
    LaunchedEffect(tab) { scrollState.scrollTo(0) }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutputTab.entries.forEach { item ->
            FilterChip(
                selected = tab == item,
                onClick = { tab = item },
                label = { Text(item.label) },
            )
        }
    }

    when (tab) {
        OutputTab.Streaming -> StreamingTab(
            config = config,
            live = live,
            onConfigChange = onConfigChange,
            onLiveConfigChange = onLiveConfigChange,
        )

        OutputTab.Recording -> RecordingTab(
            config = config,
            mix = mix,
            onConfigChange = onConfigChange,
        )

        OutputTab.Audio -> AudioOutputTab(
            config = config,
            mix = mix,
            onConfigChange = onConfigChange,
        )
    }
}

@Composable
private fun StreamingTab(
    config: CaptureConfig,
    live: LiveConfig,
    onConfigChange: (CaptureConfig) -> Unit,
    onLiveConfigChange: (LiveConfig) -> Unit,
) {
    Spacer(Modifier.height(12.dp))
    OutputSummary(
        "Streaming • ${config.videoCodec.label} • ${bitrateText(config.videoBitrate)} • " +
            "${config.bitrateMode.name} • track ${live.audioTrack}",
    )

    Spacer(Modifier.height(16.dp))
    SectionTitle(stringResource(R.string.settings_stream_audio_track))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (track in 1..6) {
            FilterChip(
                selected = live.audioTrack == track,
                onClick = { onLiveConfigChange(live.copy(audioTrack = track)) },
                label = { Text("$track") },
            )
        }
    }
    Hint(stringResource(R.string.settings_stream_audio_track_hint))

    VideoEncodeSection(config = config, onConfigChange = onConfigChange)
}

@Composable
private fun RecordingTab(
    config: CaptureConfig,
    mix: AudioMix,
    onConfigChange: (CaptureConfig) -> Unit,
) {
    val bitrate = config.recordVideoBitrate.takeIf { it > 0 } ?: config.videoBitrate
    Spacer(Modifier.height(12.dp))
    OutputSummary(
        "Rekaman • MP4 • ${bitrateText(bitrate)} • ${saveLocationLabel(config.saveLocation)}",
    )

    Spacer(Modifier.height(16.dp))
    SectionTitle(stringResource(R.string.settings_format))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = true, onClick = {}, label = { Text("MP4") })
    }
    Hint(stringResource(R.string.settings_format_hint))

    Spacer(Modifier.height(12.dp))
    SectionTitle(stringResource(R.string.settings_file_name))
    OutlinedTextField(
        value = config.fileNameTemplate,
        onValueChange = { onConfigChange(config.copy(fileNameTemplate = it)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Hint(stringResource(R.string.settings_file_name_hint))
    SettingSwitchRow(
        title = stringResource(R.string.settings_no_space_filename),
        subtitle = stringResource(R.string.settings_no_space_filename_desc),
        checked = config.noSpaceFileName,
    ) { onConfigChange(config.copy(noSpaceFileName = it)) }

    Spacer(Modifier.height(16.dp))
    SectionTitle(stringResource(R.string.settings_lokasi_penyimpanan))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SaveLocation.entries.forEach { location ->
            FilterChip(
                selected = config.saveLocation == location,
                onClick = { onConfigChange(config.copy(saveLocation = location)) },
                label = { Text(saveLocationLabel(location)) },
            )
        }
    }
    Hint(stringResource(R.string.settings_lokasi_hint))

    Spacer(Modifier.height(16.dp))
    SectionTitle(stringResource(R.string.settings_recording_audio_track))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (track in 1..6) {
            val bit = 1 shl (track - 1)
            val selected = mix.outputTracks and bit != 0
            FilterChip(
                selected = selected,
                onClick = {
                    val next = if (selected) {
                        mix.outputTracks and bit.inv()
                    } else {
                        mix.outputTracks or bit
                    }
                    AudioMixer.setOutputTracks(
                        if (next and 0x3F == 0) mix.outputTracks else next,
                    )
                },
                label = { Text("$track") },
            )
        }
    }
    Hint(
        "Track 1 adalah campuran utama. Track tambahan menulis trek AAC terpisah " +
            "di MP4; tidak semua player memutar track selain track pertama.",
    )

    Spacer(Modifier.height(16.dp))
    SectionTitle(stringResource(R.string.settings_qs_tile))
    Hint(stringResource(R.string.settings_qs_tile_desc))

    Spacer(Modifier.height(12.dp))
    Hint(stringResource(R.string.settings_split_recording_dan_replay_buffer_menyus))

    VideoEncodeSection(
        config = config,
        onConfigChange = onConfigChange,
        target = BitrateTarget.Recording,
    )
}

/**
 * Tab audio hanya memuat setelan audio output. Perangkat dan mixer ada di
 * Setelan → Audio supaya tidak ada dua tempat untuk hal yang sama.
 */
@Composable
private fun AudioOutputTab(
    config: CaptureConfig,
    mix: AudioMix,
    onConfigChange: (CaptureConfig) -> Unit,
) {
    Spacer(Modifier.height(12.dp))
    OutputSummary("Audio Output • AAC-LC • ${config.audioBitrate / 1_000} kbps")

    Spacer(Modifier.height(16.dp))
    SectionTitle(stringResource(R.string.settings_audio_encoder))
    InfoRow(stringResource(R.string.settings_encoder), "AAC-LC")

    Spacer(Modifier.height(12.dp))
    SectionTitle(stringResource(R.string.settings_bitrate_audio))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(64, 128, 192).forEach { kbps ->
            FilterChip(
                selected = config.audioBitrate == kbps * 1_000,
                onClick = { onConfigChange(config.copy(audioBitrate = kbps * 1_000)) },
                label = { Text("$kbps kbps") },
            )
        }
    }
    Hint(stringResource(R.string.settings_audio_bitrate_hint))

    Spacer(Modifier.height(12.dp))
    SectionTitle(stringResource(R.string.settings_audio_track))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (track in 1..6) {
            val bit = 1 shl (track - 1)
            val selected = mix.outputTracks and bit != 0
            FilterChip(
                selected = selected,
                onClick = {
                    val next = if (selected) {
                        mix.outputTracks and bit.inv()
                    } else {
                        mix.outputTracks or bit
                    }
                    AudioMixer.setOutputTracks(
                        if (next and 0x3F == 0) mix.outputTracks else next,
                    )
                },
                label = { Text("$track") },
            )
        }
    }
    Hint(stringResource(R.string.settings_audio_tab_hint))
    Hint(stringResource(R.string.settings_audio_output_hint))

    Spacer(Modifier.height(16.dp))
    SectionTitle(stringResource(R.string.settings_audio_track_bitrate))
    Hint(stringResource(R.string.settings_audio_track_bitrate_hint))
    Spacer(Modifier.height(8.dp))
    for (track in 1..6) {
        if (mix.outputTracks and (1 shl (track - 1)) == 0) continue
        Text(
            stringResource(R.string.settings_audio_track_n, track),
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf(64, 96, 128, 160, 192, 256, 320).forEach { kbps ->
                val selected = mix.bitrateFor(track) == kbps * 1_000
                FilterChip(
                    selected = selected,
                    onClick = {
                        AudioMixer.setTrackBitrate(track, kbps * 1_000)
                    },
                    label = { Text("$kbps") },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

/**
 * Setelan video dipakai bersama live dan rekam karena hanya ada satu
 * encoder. Ditampilkan di tab Streaming dan Recording, tidak di tab audio.
 *
 * [target] menentukan bitrate mana yang diatur di blok ini, supaya tiap tab
 * hanya punya satu kontrol bitrate dan tidak ada dua slider kembar.
 */
@Composable
private fun VideoEncodeSection(
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
                InfoRow("Bitrate", bitrateText(config.videoBitrate))
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
        InfoRow("Kualitas", "${config.cqLevel}")
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
    SectionTitle(stringResource(R.string.settings_rescale_output))
    ResolutionPicker(config = config, onConfigChange = onConfigChange)
    Hint(stringResource(R.string.settings_rescale_output_hint))

    Spacer(Modifier.height(12.dp))
    SectionTitle(stringResource(R.string.settings_media_codec_limit))
    Hint(stringResource(R.string.settings_media_codec_limit_desc))
}

/** Penanda tab sekaligus nilai yang paling sering dicek. */
@Composable
private fun OutputSummary(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun bitrateText(bitrate: Int): String =
    String.format("%.1f Mbps", bitrate / 1_000_000f)

/**
 * Pilihan codec dengan status nyata dari MediaCodec. Codec yang tidak
 * tersedia tetap tampil (nonaktif) supaya alasannya bisa dibaca, bukan
 * disembunyikan.
 */
@Composable
private fun CodecPicker(
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
                label = { Text(codec.label) },
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

/** Slider bitrate 1–50 Mbps dengan langkah 0,5 Mbps seperti spinbox OBS. */
@Composable
private fun BitrateSlider(bitrate: Int, onChange: (Int) -> Unit) {
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
private fun ResolutionPicker(
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

private fun applyCustomSize(
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

@Composable
private fun saveLocationLabel(location: SaveLocation): String = when (location) {    SaveLocation.MoviesRoutAir -> stringResource(R.string.settings_lokasi_movies_routair)
    SaveLocation.Movies -> stringResource(R.string.settings_lokasi_movies)
    SaveLocation.DcimRoutAir -> stringResource(R.string.settings_lokasi_dcim)
    SaveLocation.DcimCamera -> stringResource(R.string.settings_lokasi_dcim_camera)
}

@Composable
private fun GeneralSettings() {
    SectionTitle(stringResource(R.string.settings_general))
    InfoRow(stringResource(R.string.settings_language), "Indonesia")
    InfoRow(stringResource(R.string.settings_theme), "Gelap (cyan)")
    InfoRow(stringResource(R.string.settings_confirm_exit), stringResource(R.string.settings_unsupported_android))
    Hint(stringResource(R.string.settings_general_hint))
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LiveSettings(
    live: LiveConfig,
    onLiveConfigChange: (LiveConfig) -> Unit,
) {
    var serviceExpanded by remember { mutableStateOf(false) }
    var showingKey by remember { mutableStateOf(false) }
    var showPassword by remember { mutableStateOf(false) }
    // Default seperti OBS: hubungkan akun, bukan isi kunci manual.
    var manualKey by remember {
        mutableStateOf(!live.accountLinked && live.streamKey.isNotBlank())
    }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<RtmpTestResult?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    SectionTitle(stringResource(R.string.settings_live_destination))
    ExposedDropdownMenuBox(
        expanded = serviceExpanded,
        onExpandedChange = { serviceExpanded = it },
    ) {
        OutlinedTextField(
            value = live.service.label,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.settings_live_service)) },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = serviceExpanded)
            },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = serviceExpanded,
            onDismissRequest = { serviceExpanded = false },
        ) {
            LiveService.entries.forEach { service ->
                DropdownMenuItem(
                    text = { Text(service.label) },
                    onClick = {
                        serviceExpanded = false
                        onLiveConfigChange(
                            live.copy(
                                service = service,
                                serverUrl = if (service.custom) {
                                    live.serverUrl
                                } else {
                                    service.defaultServer
                                },
                            ),
                        )
                    },
                )
            }
        }
    }

    Spacer(Modifier.height(10.dp))
    if (live.service == LiveService.YouTube) {
        // Sama seperti OBS: pilih hubungkan akun atau isi stream key manual.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !manualKey,
                onClick = { manualKey = false },
                label = { Text(stringResource(R.string.settings_live_connect_account)) },
            )
            FilterChip(
                selected = manualKey,
                onClick = { manualKey = true },
                label = { Text(stringResource(R.string.settings_live_use_key)) },
            )
        }
        Spacer(Modifier.height(10.dp))
    }

    if (live.service == LiveService.YouTube && !manualKey) {
        YoutubeAccountSection(live, onLiveConfigChange)
    } else {
        OutlinedTextField(
            value = live.serverUrl,
            onValueChange = { onLiveConfigChange(live.copy(serverUrl = it)) },
            label = { Text(stringResource(R.string.settings_live_server_url)) },
            placeholder = { Text("rtmp://server/aplikasi") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = live.streamKey,
            onValueChange = {
                // Mengetik kunci manual berarti keluar dari mode akun.
                onLiveConfigChange(live.copy(streamKey = it, accountLinked = false))
            },
            label = { Text(stringResource(R.string.settings_live_key)) },
            visualTransformation = if (showingKey) {
                androidx.compose.ui.text.input.VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                TextButton(onClick = { showingKey = !showingKey }) {
                    Text(
                        stringResource(
                            if (showingKey) R.string.settings_live_hide
                            else R.string.settings_live_show,
                        ),
                    )
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    Spacer(Modifier.height(12.dp))
    if (live.service.custom) {
        SettingSwitchRow(
            title = stringResource(R.string.settings_live_use_auth),
            subtitle = stringResource(R.string.settings_live_use_auth_desc),
            checked = live.useAuth,
        ) { onLiveConfigChange(live.copy(useAuth = it)) }

        if (live.useAuth) {
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = live.username,
                onValueChange = { onLiveConfigChange(live.copy(username = it)) },
                label = { Text(stringResource(R.string.settings_live_username)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = live.password,
                onValueChange = { onLiveConfigChange(live.copy(password = it)) },
                label = { Text(stringResource(R.string.settings_live_password)) },
                visualTransformation = if (showPassword) {
                    androidx.compose.ui.text.input.VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    TextButton(onClick = { showPassword = !showPassword }) {
                        Text(
                            stringResource(
                                if (showPassword) R.string.settings_live_hide
                                else R.string.settings_live_show,
                            ),
                        )
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    Spacer(Modifier.height(12.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = {
                testing = true
                testResult = null
                scope.launch {
                    val result = withContext(Dispatchers.IO) { RtmpTester.test(live) }
                    testResult = result
                    testing = false
                }
            },
            enabled = !testing && live.isConfigured,
        ) {
            if (testing) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .height(14.dp),
                    strokeWidth = 2.dp,
                )
                Text(stringResource(R.string.settings_live_testing))
            } else {
                Text(stringResource(R.string.settings_live_test))
            }
        }
        testResult?.let { result ->
            Text(
                text = if (result.ok) {
                    stringResource(R.string.settings_live_test_ok, result.latencyMs)
                } else {
                    stringResource(R.string.settings_live_test_fail, result.message)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (result.ok) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }

    Spacer(Modifier.height(16.dp))
    SettingSwitchRow(
        title = stringResource(R.string.settings_live_reconnect),
        subtitle = stringResource(R.string.settings_live_reconnect_desc),
        checked = live.autoReconnect,
    ) { onLiveConfigChange(live.copy(autoReconnect = it)) }

    Spacer(Modifier.height(16.dp))
    SectionTitle(stringResource(R.string.settings_live_audio_track))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (track in 1..6) {
            FilterChip(
                selected = live.audioTrack == track,
                onClick = { onLiveConfigChange(live.copy(audioTrack = track)) },
                label = { Text("$track") },
            )
        }
    }

    if (live.service.dashboardUrl.isNotBlank()) {
        Spacer(Modifier.height(8.dp))
        TextButton(
            onClick = {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(live.service.dashboardUrl)),
                    )
                }
            },
        ) {
            Text(stringResource(R.string.settings_live_open_dashboard))
        }
    }

    Spacer(Modifier.height(12.dp))
    Hint(stringResource(R.string.settings_live_url_hint))
    Hint(stringResource(R.string.settings_live_notes))

}

/**
 * Bagian "Hubungkan Akun" YouTube, meniru OBS: login sekali lewat browser,
 * lalu server dan stream key diisi otomatis dan chat memakai akun yang sama.
 */
@Composable
private fun YoutubeAccountSection(
    live: LiveConfig,
    onLiveConfigChange: (LiveConfig) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { GoogleAuthStore(context) }
    var email by remember { mutableStateOf(if (store.loggedIn) store.accountEmail else "") }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    // Setelah token didapat: simpan, ambil email, lalu isi server + stream key
    // seperti tombol Connect Account di OBS.
    suspend fun finishLogin(accessToken: String) {
        store.saveTokens(GoogleTokens(accessToken, GoogleAuth.expiryFrom()))
        val mail = withContext(Dispatchers.IO) { GoogleAuth.accountEmail(accessToken) }
        store.accountEmail = mail
        email = mail
        val target = withContext(Dispatchers.IO) {
            runCatching { YouTubeApi(accessToken).streamTarget() }.getOrNull()
        }
        if (target == null) {
            status = context.getString(R.string.settings_google_no_key)
        } else {
            onLiveConfigChange(
                live.copy(
                    serverUrl = target.server,
                    streamKey = target.key,
                    accountLinked = true,
                ),
            )
        }
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode != android.app.Activity.RESULT_OK || data == null) {
            status = "Login dibatalkan"
            busy = false
            return@rememberLauncherForActivityResult
        }
        val authorization = try {
            Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data)
        } catch (error: ApiException) {
            status = error.message ?: "Login gagal"
            busy = false
            return@rememberLauncherForActivityResult
        }
        val token = authorization.accessToken
        if (token.isNullOrBlank()) {
            status = "Google tidak mengembalikan token"
            busy = false
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            finishLogin(token)
            busy = false
        }
    }

    fun authorize() {
        status = ""
        busy = true
        Identity.getAuthorizationClient(context)
            .authorize(GoogleAuth.authorizationRequest())
            .addOnSuccessListener { authorization ->
                val pending = authorization.pendingIntent
                if (authorization.hasResolution() && pending != null) {
                    launcher.launch(
                        IntentSenderRequest.Builder(pending.intentSender).build(),
                    )
                    return@addOnSuccessListener
                }
                val token = authorization.accessToken
                if (token.isNullOrBlank()) {
                    status = "Google tidak mengembalikan token"
                    busy = false
                } else {
                    scope.launch {
                        finishLogin(token)
                        busy = false
                    }
                }
            }
            .addOnFailureListener { error ->
                status = error.message ?: "Login gagal"
                busy = false
            }
    }

    if (email.isNotBlank()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.settings_google_connected_as),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(email, style = MaterialTheme.typography.bodyMedium)
                if (live.streamKey.isNotBlank()) {
                    Text(
                        stringResource(R.string.settings_google_target_ready),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            TextButton(
                onClick = {
                    store.clear()
                    email = ""
                    status = ""
                    onLiveConfigChange(
                        live.copy(serverUrl = "", streamKey = "", accountLinked = false),
                    )
                },
            ) {
                Text(stringResource(R.string.settings_google_disconnect))
            }
        }
    } else if (!GoogleAuth.available(context)) {
        // Tanpa Google Play Services tombol tidak akan pernah berhasil.
        Hint(stringResource(R.string.settings_google_not_configured))
    } else {
        Button(
            enabled = !busy,
            onClick = { authorize() },
        ) {
            Text(
                if (busy) {
                    stringResource(R.string.settings_google_waiting_browser)
                } else {
                    stringResource(R.string.settings_google_connect)
                },
            )
        }
    }

    if (status.isNotBlank()) {
        Spacer(Modifier.height(6.dp))
        Text(
            text = status,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }

    if (email.isBlank()) Hint(stringResource(R.string.settings_google_hint))
}

@Composable
private fun AudioSettings(
    config: CaptureConfig,
    mix: AudioMix,
    onMixChange: (AudioChannel) -> Unit,
    onMasterEnabledChange: (Boolean) -> Unit,
    onConfigChange: (CaptureConfig) -> Unit,
) {
    var deviceTarget by remember { mutableStateOf<AudioChannel?>(null) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_rekam_audio), style = MaterialTheme.typography.titleSmall)
            Text(
                text = stringResource(R.string.settings_sakelar_induk_trek_audio_di_dalam_rekama),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = mix.masterEnabled,
            onCheckedChange = onMasterEnabledChange,
        )
    }

    Spacer(Modifier.height(16.dp))
    SectionTitle(stringResource(R.string.settings_perangkat_audio_global))
    mix.channels.filter { it.global }.forEach { channel ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when (channel.id) {
                        AudioChannelId.Internal -> "Desktop Audio"
                        AudioChannelId.Microphone -> "Mic/Auxiliary Audio"
                        else -> channel.name
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = when (channel.capture) {
                        com.rout.air.core.AudioCaptureKind.Playback ->
                            "AudioPlaybackCapture dari aplikasi lain"

                        com.rout.air.core.AudioCaptureKind.Microphone ->
                            "AudioRecord dari mikrofon perangkat"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (channel.capture == com.rout.air.core.AudioCaptureKind.Microphone) {
                    Text(
                        text = stringResource(R.string.settings_perangkat_n, channel.deviceName ?: androidx.compose.ui.platform.LocalContext.current.getString(R.string.common_default)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { deviceTarget = channel }) {
                        Text(stringResource(R.string.scenes_ganti_perangkat_input))
                    }
                }
            }
            Switch(
                checked = channel.enabled,
                onCheckedChange = { onMixChange(channel.copy(enabled = it)) },
            )
        }
    }

    Spacer(Modifier.height(16.dp))
    SectionTitle(stringResource(R.string.settings_sample_rate))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(44_100, 48_000).forEach { rate ->
            FilterChip(
                selected = config.sampleRate == rate,
                onClick = { onConfigChange(config.copy(sampleRate = rate)) },
                label = { Text(if (rate == 44_100) "44.1 kHz" else "48 kHz") },
            )
        }
    }
    Hint(stringResource(R.string.settings_perubahan_sample_rate_berlaku_pada_sesi))

    Spacer(Modifier.height(12.dp))
    SectionTitle(stringResource(R.string.settings_channels))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(1, 2).forEach { count ->
            FilterChip(
                selected = config.channelCount == count,
                onClick = { onConfigChange(config.copy(channelCount = count)) },
                label = { Text(if (count == 1) "Mono" else "Stereo") },
            )
        }
    }

    Spacer(Modifier.height(16.dp))
    SectionTitle(stringResource(R.string.settings_meters))
    Text(
        "Decay Rate",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        MeterDecay.entries.forEach { decay ->
            FilterChip(
                selected = mix.meterDecay == decay,
                onClick = { AudioMixer.setMeterDecay(decay) },
                label = { Text(decayLabel(decay)) },
            )
        }
    }
    Spacer(Modifier.height(6.dp))
    Text(
        "Peak Meter Type",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PeakMeterType.entries.forEach { type ->
            FilterChip(
                selected = mix.peakMeterType == type,
                onClick = { AudioMixer.setPeakMeterType(type) },
                label = { Text(peakMeterLabel(type)) },
            )
        }
    }

    Spacer(Modifier.height(16.dp))
    SectionTitle(stringResource(R.string.settings_monitoring_device))
    val context = androidx.compose.ui.platform.LocalContext.current
    val devices = remember(context) {
        context.getSystemService(android.media.AudioManager::class.java)
            ?.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
            ?.toList()
            .orEmpty()
            // Nama kosong tidak berguna di daftar.
            .filter { !it.productName.isNullOrBlank() }
            // Sistem kadang mengembalikan perangkat yang sama beberapa kali
            // dengan tipe/rute berbeda; cukup satu entri per nama.
            .distinctBy { it.productName.toString() }
            .sortedBy { it.productName.toString().lowercase() }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FilterChip(
            selected = mix.monitoringDeviceId == 0,
            onClick = { AudioMixer.setMonitoringDevice(0) },
            label = { Text(stringResource(R.string.settings_otomatis)) },
        )
        devices.forEach { device ->
            FilterChip(
                selected = mix.monitoringDeviceId == device.id,
                onClick = { AudioMixer.setMonitoringDevice(device.id) },
                label = { Text(device.productName.toString()) },
            )
        }
    }

    Spacer(Modifier.height(16.dp))
    SectionTitle(stringResource(R.string.settings_push_to_talk_push_to_mute))
    SettingSwitchRow(
        title = stringResource(R.string.settings_aktifkan_push_to_mute),
        subtitle = stringResource(R.string.settings_tahan_tombol_untuk_membisukan_mikrofon),
        checked = mix.pushToMuteEnabled,
    ) { AudioMixer.setPushToMuteEnabled(it) }
    SettingSwitchRow(
        title = stringResource(R.string.settings_aktifkan_push_to_talk),
        subtitle = stringResource(R.string.settings_tahan_tombol_agar_mikrofon_berbunyi),
        checked = mix.pushToTalkEnabled,
    ) { AudioMixer.setPushToTalkEnabled(it) }

    Spacer(Modifier.height(12.dp))
    Hint(stringResource(R.string.settings_volume_mute_filter_balance_sync_offset_d))


    deviceTarget?.let { channel ->
        AudioDevicePickerDialog(
            currentId = channel.deviceId,
            onSelect = { option ->
                AudioMixer.changeInputDevice(
                    id = channel.id,
                    deviceId = option?.id ?: 0,
                    deviceName = option?.name,
                    deviceType = option?.type ?: 0,
                )
                deviceTarget = null
            },
            onDismiss = { deviceTarget = null },
        )
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun AdvancedSettings(
    config: CaptureConfig,
    canvasConfig: CaptureConfig,
    live: LiveConfig,
    onConfigChange: (CaptureConfig) -> Unit,
) {
    SectionTitle(stringResource(R.string.settings_informasi))
    val caps = remember { EncoderProbe.all() }
    val encoder = EncoderProbe.select(caps, canvasConfig.videoCodec)
    InfoRow("Versi", "1.0 (M4)")
    InfoRow("Kanvas", "${canvasConfig.width}x${canvasConfig.height}")
    InfoRow("Renderer", "OpenGL ES 3.0")
    InfoRow("Codec", canvasConfig.videoCodec.label)
    InfoRow(
        "Encoder",
        encoder?.let {
            "${it.name} • ${if (it.hardware) "hardware" else "software"}"
        } ?: "tidak tersedia",
    )
    InfoRow("Audio", "AAC-LC + mixer OBS")
    InfoRow("Rate control", canvasConfig.bitrateMode.name)

    Text(
        "Encoder MediaCodec terdeteksi:",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (caps.isEmpty()) {
        Text(
            "• pemindaian gagal atau tidak ada encoder",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        caps.forEach { cap ->
            Text(
                "• ${cap.codec.label}: ${cap.describe()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    InfoRow("Profile", canvasConfig.videoProfile.name)
    InfoRow("Color", "${canvasConfig.colorSpace.name} • ${canvasConfig.colorRange.name}")
    InfoRow("Network", "RTMP/RTMPS • reconnect maks 8×")

    Spacer(Modifier.height(16.dp))
    SectionTitle(stringResource(R.string.settings_performance))
    SettingSwitchRow(
        title = stringResource(R.string.settings_static_throttle),
        subtitle = stringResource(R.string.settings_static_throttle_desc),
        checked = config.staticThrottleEnabled,
    ) { onConfigChange(config.copy(staticThrottleEnabled = it)) }
    SettingSwitchRow(
        title = stringResource(R.string.settings_thermal_throttle),
        subtitle = stringResource(R.string.settings_thermal_throttle_desc),
        checked = config.thermalThrottleEnabled,
    ) { onConfigChange(config.copy(thermalThrottleEnabled = it)) }
    Hint(stringResource(R.string.settings_performance_hint))

    Spacer(Modifier.height(16.dp))
    SectionTitle(stringResource(R.string.settings_encoder_test))
    Hint(stringResource(R.string.settings_encoder_test_hint))

    val scope = rememberCoroutineScope()
    var testing by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf(emptyList<EncoderBenchmark.Result>()) }
    var runningCodec by remember { mutableStateOf<VideoCodec?>(null) }
    var secondsLeft by remember { mutableStateOf(5) }
    var codecFraction by remember { mutableStateOf(0f) }
    var codecIndex by remember { mutableStateOf(0) }
    var benchError by remember { mutableStateOf<String?>(null) }
    val testCodecs = listOf(VideoCodec.AVC, VideoCodec.HEVC)

    Spacer(Modifier.height(8.dp))
    Button(
        onClick = {
            testing = true
            results = emptyList()
            benchError = null
            runningCodec = null
            secondsLeft = 5
            codecFraction = 0f
            codecIndex = 0
            // Jalan di thread lain; layar tetap bisa digulir selama uji.
            scope.launch {
                try {
                    EncoderBenchmark.measure(
                        width = canvasConfig.encoderWidth,
                        height = canvasConfig.encoderHeight,
                        fps = canvasConfig.fps,
                        codecs = testCodecs,
                        onProgress = { index, codec, left, fraction ->
                            codecIndex = index
                            runningCodec = codec
                            secondsLeft = left
                            codecFraction = fraction
                        },
                        // Hasil satu codec langsung tampil tanpa menunggu yang lain.
                        onResult = { results = results + it },
                    )
                } catch (error: Throwable) {
                    benchError = error.message ?: "Uji gagal"
                } finally {
                    // Tombol harus selalu aktif lagi, termasuk setelah gagal.
                    testing = false
                }
            }
        },
        enabled = !testing,
    ) {
        Text(
            stringResource(
                if (testing) {
                    R.string.settings_encoder_test_running
                } else {
                    R.string.settings_encoder_test_start
                },
            ),
        )
    }

    if (testing) {
        val overall = ((codecIndex + codecFraction) / testCodecs.size).coerceIn(0f, 1f)
        // Animasi membuat bar bergerak halus meski progres dikirim tiap 100 ms.
        val animated by animateFloatAsState(targetValue = overall, label = "uji-encoder")
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { animated },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "${runningCodec?.label ?: "menyiapkan"} • sisa $secondsLeft detik",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    benchError?.let { message ->
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Uji gagal: $message",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }

    results.forEach { result ->
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (result.error != null) {
                "${result.codec.label}: gagal — ${result.error}"
            } else {
                String.format(
                    java.util.Locale.US,
                    "%s: %.1f fps • %d frame • %s",
                    result.codec.label,
                    result.fps,
                    result.frames,
                    result.encoder,
                )
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (result.error != null) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }

    Spacer(Modifier.height(20.dp))
    AutoConfigSection(
        config = config,
        live = live,
        onConfigChange = onConfigChange,
    )

    Spacer(Modifier.height(20.dp))
    GameLoadBenchmarkSection()

    Spacer(Modifier.height(16.dp))
    SectionTitle(stringResource(R.string.settings_diagnostik))
    Hint(stringResource(R.string.settings_setiap_rekaman_menulis_log_ke_download_r))

    val context = LocalContext.current
    var exportMessage by remember { mutableStateOf<String?>(null) }
    Spacer(Modifier.height(8.dp))
    Button(
        onClick = {
            exportMessage = DiagLog.exportToDownloads(context)
                ?.let { "Log diekspor ke $it" }
                ?: "Gagal mengekspor log"
        },
    ) {
        Text("Ekspor log ke Download")
    }
    exportMessage?.let { Hint(it) }
}

/**
 * Kartu setup otomatis: menampilkan hasil terakhir dan membuka wizard.
 * Penempatannya di atas benchmark sesuai permintaan.
 */
@Composable
private fun AutoConfigSection(
    config: CaptureConfig,
    live: LiveConfig,
    onConfigChange: (CaptureConfig) -> Unit,
) {
    val context = LocalContext.current
    val store = remember { SettingsStore(context) }
    var open by remember { mutableStateOf(false) }
    var last by remember { mutableStateOf(store.lastAutoConfig()) }

    SectionTitle(stringResource(R.string.settings_autoconfig_title))
    Hint(stringResource(R.string.settings_autoconfig_hint))
    last?.let { Hint("Terakhir: $it") }
    Spacer(Modifier.height(8.dp))
    Button(onClick = { open = true }) {
        Text(stringResource(R.string.settings_autoconfig_start))
    }

    if (open) {
        AutoConfigWizardDialog(
            config = config,
            live = live,
            onApply = { updated ->
                onConfigChange(updated)
                val text = "${updated.outputWidth}x${updated.outputHeight} " +
                    "@${updated.fps} fps • ${updated.videoCodec.label} • " +
                    "${updated.videoBitrate / 1_000_000} Mbps"
                store.setLastAutoConfig(text)
                store.setWizardDone()
                last = text
            },
            onDismiss = {
                open = false
                store.setWizardDone()
            },
        )
    }
}

/**
 * Benchmark beban game: adegan GL berat + thread pengisi CPU, direkam lewat
 * encoder yang sama dengan rekaman asli. Tidak butuh game maupun izin
 * tangkap layar.
 */
@Composable
private fun GameLoadBenchmarkSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var preset by remember { mutableStateOf(GameLoadBenchmark.Preset.High) }
    var codec by remember { mutableStateOf(VideoCodec.AVC) }
    var resolution by remember { mutableStateOf(BenchResolution.P1080) }
    var targetFps by remember { mutableStateOf(60) }
    var manualBitrate by remember { mutableStateOf(false) }
    var manualMbps by remember { mutableStateOf(12f) }
    var minutes by remember { mutableStateOf(3) }
    var traffic by remember { mutableStateOf(70f) }
    var saveVideo by remember { mutableStateOf(true) }
    var keepAwake by remember { mutableStateOf(true) }
    var running by remember { mutableStateOf(false) }
    var secondsLeft by remember { mutableStateOf(0) }
    var fraction by remember { mutableStateOf(0f) }
    var fpsNow by remember { mutableStateOf(0) }
    var result by remember { mutableStateOf<GameLoadBenchmark.Result?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }

    // Layar tidak boleh mati saat benchmark; kalau mati, render berhenti
    // dan angka fps jadi turun tanpa alasan yang jelas.
    val view = LocalView.current
    DisposableEffect(running, keepAwake) {
        view.keepScreenOn = running && keepAwake
        onDispose { view.keepScreenOn = false }
    }

    SectionTitle(stringResource(R.string.settings_bench_title))
    Hint(stringResource(R.string.settings_bench_hint))
    Hint(stringResource(R.string.settings_bench_overlay_hint))

    Spacer(Modifier.height(8.dp))
    Text("Beban grafis", style = MaterialTheme.typography.bodyMedium)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GameLoadBenchmark.Preset.entries.forEach { item ->
            FilterChip(
                selected = preset == item,
                onClick = { preset = item },
                label = { Text(item.label) },
            )
        }
    }

    Spacer(Modifier.height(8.dp))
    Text("Codec", style = MaterialTheme.typography.bodyMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(VideoCodec.AVC, VideoCodec.HEVC).forEach { item ->
            FilterChip(
                selected = codec == item,
                onClick = { codec = item },
                label = { Text(item.label) },
            )
        }
    }

    Spacer(Modifier.height(8.dp))
    Text("Resolusi", style = MaterialTheme.typography.bodyMedium)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BenchResolution.entries.forEach { item ->
            FilterChip(
                selected = resolution == item,
                onClick = { resolution = item },
                label = { Text(item.label) },
            )
        }
    }

    Spacer(Modifier.height(8.dp))
    Text("Frame rate", style = MaterialTheme.typography.bodyMedium)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(30, 60, 90, 120).forEach { item ->
            FilterChip(
                selected = targetFps == item,
                onClick = { targetFps = item },
                label = { Text("$item fps") },
            )
        }
    }

    Spacer(Modifier.height(8.dp))
    Text("Bitrate", style = MaterialTheme.typography.bodyMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = !manualBitrate,
            onClick = { manualBitrate = false },
            label = { Text("Otomatis") },
        )
        FilterChip(
            selected = manualBitrate,
            onClick = { manualBitrate = true },
            label = { Text("Manual") },
        )
    }
    val autoBitrate = GameLoadBenchmark.bitrateFor(
        resolution.width,
        resolution.height,
        targetFps,
    )
    val effectiveBitrate = if (manualBitrate) {
        GameLoadBenchmark.manualBitrate(manualMbps)
    } else {
        autoBitrate
    }
    if (manualBitrate) {
        Text(String.format(java.util.Locale.US, "%.1f Mbps", manualMbps))
        // Tanpa steps: tanda tick rapat membuat track terlihat seperti balok.
        Slider(
            value = manualMbps,
            onValueChange = { manualMbps = (it * 2f).roundToInt() / 2f },
            valueRange = 2f..100f,
        )
    } else {
        Hint(
            "Otomatis: " + String.format(
                java.util.Locale.US,
                "%.1f Mbps",
                effectiveBitrate / 1_000_000f,
            ) + " untuk resolusi dan fps ini.",
        )
    }

    Spacer(Modifier.height(8.dp))
    Text("Durasi", style = MaterialTheme.typography.bodyMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(1, 3, 5).forEach { item ->
            FilterChip(
                selected = minutes == item,
                onClick = { minutes = item },
                label = { Text("$item menit") },
            )
        }
    }

    Spacer(Modifier.height(8.dp))
    Text(
        "Traffic ${traffic.toInt()}% (kepadatan objek seperti setelan NTE)",
        style = MaterialTheme.typography.bodyMedium,
    )
    Slider(
        value = traffic,
        // Dibulatkan 5% agar angka mudah dibaca, tanpa tick yang menutupi track.
        onValueChange = { traffic = (it / 5f).roundToInt() * 5f },
        valueRange = 0f..100f,
    )

    SettingSwitchRow(
        title = stringResource(R.string.settings_bench_keep_awake),
        subtitle = stringResource(R.string.settings_bench_keep_awake_desc),
        checked = keepAwake,
    ) { keepAwake = it }

    SettingSwitchRow(
        title = "Simpan rekaman MP4",
        subtitle = "Ikut membebani penyimpanan; bisa ditonton untuk cek kualitas.",
        checked = saveVideo,
    ) { saveVideo = it }
    if (saveVideo) {
        val estimateMb = effectiveBitrate / 8L * (minutes * 60) / 1_000_000.0
        Hint(
            "Perkiraan file: " +
                String.format(java.util.Locale.US, "%.0f MB", estimateMb) +
                " • " +
                String.format(java.util.Locale.US, "%.1f Mbps", effectiveBitrate / 1_000_000f),
        )
    }

    Spacer(Modifier.height(8.dp))
    Button(
        onClick = {
            running = true
            result = null
            failure = null
            secondsLeft = minutes * 60
            fraction = 0f
            fpsNow = 0
            scope.launch {
                try {
                    result = GameLoadBenchmark.run(
                        context = context,
                        width = resolution.width,
                        height = resolution.height,
                        targetFps = targetFps,
                        bitrate = effectiveBitrate,
                        codec = codec,
                        preset = preset,
                        durationSec = minutes * 60,
                        trafficPercent = traffic.toInt(),
                        saveVideo = saveVideo,
                        keepAwake = keepAwake,
                        onProgress = { left, done, fps ->
                            secondsLeft = left
                            fraction = done
                            fpsNow = fps
                        },
                    )
                } catch (error: Throwable) {
                    failure = error.message ?: "Benchmark gagal"
                } finally {
                    running = false
                }
            }
        },
        enabled = !running,
    ) {
        Text(
            if (running) {
                "Merekam… ${secondsLeft / 60}:${(secondsLeft % 60).toString().padStart(2, '0')}"
            } else {
                "Mulai benchmark (${minutes} menit)"
            },
        )
    }

    if (running) {
        val animated by animateFloatAsState(targetValue = fraction, label = "beban-game")
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { animated },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        Hint(
            "fps detik terakhir: $fpsNow • ${resolution.width}x${resolution.height} @ $targetFps • " +
                "${GameLoadBenchmark.effectiveObjects(preset, traffic.toInt())} objek",
        )
    }

    failure?.let {
        Spacer(Modifier.height(4.dp))
        Text("Benchmark gagal: $it", color = MaterialTheme.colorScheme.error)
    }

    result?.let { data ->
        Spacer(Modifier.height(8.dp))
        Text(
            "${data.codec.label} • ${data.preset.label} • ${data.width}x${data.height}@${data.targetFps}",
            style = MaterialTheme.typography.bodyMedium,
        )
        InfoRow("fps rata-rata", String.format(java.util.Locale.US, "%.1f", data.avgFps))
        InfoRow("fps terburuk", String.format(java.util.Locale.US, "%.1f", data.worstFps))
        InfoRow("frame / telat", "${data.frames} / ${data.lateFrames}")
        InfoRow("bitrate", "${data.bitrateKbps} kbps")
        InfoRow("termal tertinggi", data.thermalMax)
        data.uri?.let { InfoRow("video", it) }
        Text(
            verdict(data),
            style = MaterialTheme.typography.bodyMedium,
            color = if (data.avgFps >= data.targetFps * 0.95) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            },
        )
    }
}

/** Kesimpulan singkat dari angka benchmark. */
private fun verdict(result: GameLoadBenchmark.Result): String = when {
    result.error != null -> "Gagal: ${result.error}"
    result.avgFps >= result.targetFps * 0.95 -> "60 fps tercapai"
    result.worstFps < result.targetFps * 0.6 -> "Tidak stabil; turunkan preset atau traffic"
    else -> "Mendekati target; coba preset lebih rendah"
}

/** Template resolusi benchmark; semua sisi genap. */
private enum class BenchResolution(val label: String, val width: Int, val height: Int) {
    P720("720p", 1280, 720),
    P1080("1080p", 1920, 1080),
    P1440("1440p", 2560, 1440),
    P4K("4K", 3840, 2160),
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}


@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}
