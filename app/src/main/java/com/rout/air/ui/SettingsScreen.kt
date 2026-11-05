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
import androidx.annotation.StringRes
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.rout.air.data.AppLanguage
import com.rout.air.data.SceneManager
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
import com.rout.air.ui.theme.AccentChoice
import com.rout.air.ui.theme.Appearance
import kotlin.math.roundToInt

private enum class SettingsPage(@StringRes val titleRes: Int) {
    Root(R.string.tab_setelan),
    General(R.string.settings_entry_umum),
    Stream(R.string.settings_entry_siaran),
    Output(R.string.settings_entry_output),
    Audio(R.string.tab_audio),
    Video(R.string.settings_entry_video),
    Collections(R.string.settings_entry_adegan),
    Advanced(R.string.settings_entry_lanjutan),
}

/**
 * Setelan dipisah per halaman supaya tidak menumpuk dalam satu dialog:
 * Umum, Siaran, Output, Audio, Video, dan Lanjutan.
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

            SettingsEntry(
                stringResource(R.string.settings_entry_umum),
                stringResource(R.string.settings_entry_umum_desc),
            ) { page = SettingsPage.General }
            SettingsEntry(
                stringResource(R.string.settings_entry_siaran),
                stringResource(R.string.settings_entry_siaran_desc),
            ) { page = SettingsPage.Stream }
            SettingsEntry(
                stringResource(R.string.settings_entry_output),
                stringResource(R.string.settings_entry_output_desc),
            ) { page = SettingsPage.Output }
            SettingsEntry(
                stringResource(R.string.settings_entry_audio),
                stringResource(R.string.settings_entry_audio_desc),
            ) { page = SettingsPage.Audio }
            SettingsEntry(
                stringResource(R.string.settings_entry_video),
                stringResource(R.string.settings_entry_video_desc),
            ) { page = SettingsPage.Video }
            SettingsEntry(
                stringResource(R.string.settings_entry_adegan),
                stringResource(R.string.settings_entry_adegan_desc),
            ) { page = SettingsPage.Collections }
            SettingsEntry(
                stringResource(R.string.settings_entry_lanjutan),
                stringResource(R.string.settings_entry_lanjutan_desc),
            ) { page = SettingsPage.Advanced }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { page = SettingsPage.Root }) { Text(stringResource(R.string.common_kembali_panah)) }
                Text(stringResource(page.titleRes), style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.height(8.dp))

            when (page) {
                SettingsPage.Video -> VideoSettings(config, canvasConfig, onConfigChange)
                SettingsPage.Collections -> SceneCollectionsSettings()
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
            Text(stringResource(R.string.settings_chevron), style = MaterialTheme.typography.titleLarge)
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

private enum class OutputTab(@StringRes val labelRes: Int) {
    Streaming(R.string.settings_entry_siaran),
    Recording(R.string.tab_rekaman),
    Audio(R.string.tab_audio),
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
                label = { Text(stringResource(item.labelRes)) },
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
        stringResource(
            R.string.settings_summary_stream,
            config.videoCodec.label,
            bitrateText(config.videoBitrate),
            config.bitrateMode.name,
            live.audioTrack,
        ),
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
        stringResource(
            R.string.settings_summary_recording,
            bitrateText(bitrate),
            saveLocationLabel(config.saveLocation),
        ),
    )

    Spacer(Modifier.height(16.dp))
    SectionTitle(stringResource(R.string.settings_format))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = true,
            onClick = {},
            label = { Text(stringResource(R.string.settings_format_mp4)) },
        )
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
        stringResource(R.string.settings_track_hint),
    )

    Spacer(Modifier.height(16.dp))
    SectionTitle(stringResource(R.string.settings_qs_tile))
    Hint(stringResource(R.string.settings_qs_tile_desc))

    Spacer(Modifier.height(16.dp))
    SectionTitle(stringResource(R.string.settings_split_recording))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        // Satu pilihan saja: split karena durasi atau karena ukuran berkas.
        listOf(0 to 0, 5 to 0, 10 to 0, 30 to 0, 0 to 1024, 0 to 2048).forEach { (minutes, sizeMb) ->
            val selected = config.splitMinutes == minutes && config.splitSizeMb == sizeMb
            FilterChip(
                selected = selected,
                onClick = {
                    onConfigChange(config.copy(splitMinutes = minutes, splitSizeMb = sizeMb))
                },
                label = {
                    Text(
                        when {
                            minutes > 0 -> stringResource(R.string.settings_split_menit_n, minutes)
                            sizeMb > 0 -> stringResource(
                                R.string.settings_split_ukuran_n,
                                sizeMb / 1024,
                            )

                            else -> stringResource(R.string.settings_split_off)
                        },
                    )
                },
            )
        }
    }
    Hint(stringResource(R.string.settings_split_hint))

    Spacer(Modifier.height(16.dp))
    SectionTitle(stringResource(R.string.settings_safe_recording))
    SettingSwitchRow(
        title = stringResource(R.string.settings_safe_recording),
        subtitle = stringResource(R.string.settings_safe_recording_desc),
        checked = config.safeRecording,
    ) { onConfigChange(config.copy(safeRecording = it)) }
    if (config.safeRecording) {
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(2, 5, 10).forEach { minutes ->
                FilterChip(
                    selected = config.safeSegmentMinutes == minutes,
                    onClick = { onConfigChange(config.copy(safeSegmentMinutes = minutes)) },
                    label = {
                        Text(stringResource(R.string.settings_safe_segment_n, minutes))
                    },
                )
            }
        }
        Hint(stringResource(R.string.settings_safe_recording_hint))
    }

    Spacer(Modifier.height(16.dp))
    SectionTitle(stringResource(R.string.settings_replay_buffer))
    SettingSwitchRow(
        title = stringResource(R.string.settings_replay_buffer),
        subtitle = stringResource(R.string.settings_replay_desc),
        checked = config.replayBufferEnabled,
    ) { onConfigChange(config.copy(replayBufferEnabled = it)) }
    if (config.replayBufferEnabled) {
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(30, 60, 120).forEach { seconds ->
                FilterChip(
                    selected = config.replaySeconds == seconds,
                    onClick = { onConfigChange(config.copy(replaySeconds = seconds)) },
                    label = { Text(stringResource(R.string.settings_replay_detik_n, seconds)) },
                )
            }
        }
    }

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
    OutputSummary(
        stringResource(R.string.settings_summary_audio, config.audioBitrate / 1_000),
    )

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
private fun codecChipLabel(codec: VideoCodec): String = stringResource(
    when (codec) {
        VideoCodec.AVC -> R.string.settings_codec_avc_label
        VideoCodec.HEVC -> R.string.settings_codec_hevc_label
        VideoCodec.AV1 -> R.string.settings_codec_av1_label
    },
)

/** Keterangan singkat codec yang sedang dipilih. */
@Composable
private fun codecDescription(codec: VideoCodec): String = stringResource(
    when (codec) {
        VideoCodec.AVC -> R.string.settings_codec_desc_avc
        VideoCodec.HEVC -> R.string.settings_codec_desc_hevc
        VideoCodec.AV1 -> R.string.settings_codec_desc_av1
    },
)

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
    val accent by Appearance.accent.collectAsState()
    val amoled by Appearance.amoled.collectAsState()

    SectionTitle(stringResource(R.string.settings_general))
    // Bahasa aplikasi hanya bisa diganti mulai Android 13 (LocaleManager).
    val context = LocalContext.current
    var language by remember { mutableStateOf(AppLanguage.current(context)) }
    if (AppLanguage.supported(context)) {
        Text(stringResource(R.string.settings_language))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppLanguage.tags.forEach { tag ->
                FilterChip(
                    selected = language == tag,
                    onClick = {
                        AppLanguage.set(context, tag)
                        language = tag
                    },
                    label = {
                        Text(
                            stringResource(
                                if (tag == "en") R.string.settings_language_en
                                else R.string.settings_language_id,
                            ),
                        )
                    },
                )
            }
        }
    } else {
        InfoRow(stringResource(R.string.settings_language), stringResource(R.string.settings_language_id))
        Hint(stringResource(R.string.settings_language_android13))
    }

    Spacer(Modifier.height(12.dp))
    SectionTitle(stringResource(R.string.settings_theme))
    Hint(stringResource(R.string.settings_accent_hint))
    Spacer(Modifier.height(8.dp))
    // Enam pilihan aksen; dipecah dua baris supaya tetap muat di layar sempit.
    val choices = AccentChoice.entries
    choices.chunked(3).forEach { row ->
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 8.dp),
        ) {
            row.forEach { choice ->
                FilterChip(
                    selected = accent == choice,
                    onClick = { Appearance.setAccent(choice) },
                    label = { Text(accentLabel(choice)) },
                )
            }
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_amoled))
            Hint(stringResource(R.string.settings_amoled_hint))
        }
        Switch(
            checked = amoled,
            onCheckedChange = { Appearance.setAmoled(it) },
        )
    }

    Spacer(Modifier.height(12.dp))
    InfoRow(stringResource(R.string.settings_confirm_exit), stringResource(R.string.settings_unsupported_android))
    Hint(stringResource(R.string.settings_general_hint))
}

/** Nama aksen mengikuti bahasa aplikasi. */
@Composable
private fun accentLabel(choice: AccentChoice): String = stringResource(
    when (choice) {
        AccentChoice.Cyan -> R.string.settings_accent_cyan
        AccentChoice.Green -> R.string.settings_accent_green
        AccentChoice.Blue -> R.string.settings_accent_blue
        AccentChoice.Purple -> R.string.settings_accent_purple
        AccentChoice.Amber -> R.string.settings_accent_amber
        AccentChoice.Pink -> R.string.settings_accent_pink
    },
)


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
            placeholder = { Text(stringResource(R.string.settings_live_placeholder)) },
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
            status = context.getString(R.string.settings_login_cancelled)
            busy = false
            return@rememberLauncherForActivityResult
        }
        val authorization = try {
            Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data)
        } catch (error: ApiException) {
            status = error.message ?: context.getString(R.string.settings_login_failed)
            busy = false
            return@rememberLauncherForActivityResult
        }
        val token = authorization.accessToken
        if (token.isNullOrBlank()) {
            status = context.getString(R.string.settings_login_no_token)
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
                    status = context.getString(R.string.settings_login_no_token)
                    busy = false
                } else {
                    scope.launch {
                        finishLogin(token)
                        busy = false
                    }
                }
            }
            .addOnFailureListener { error ->
                status = error.message ?: context.getString(R.string.settings_login_failed)
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
            }
            Column(horizontalAlignment = Alignment.End) {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            // Reset akun default saja; izin lama tetap ada.
                            GoogleAuth.switchAccount(context)
                            store.clear()
                            email = ""
                            status = ""
                            onLiveConfigChange(
                                live.copy(
                                    serverUrl = "",
                                    streamKey = "",
                                    accountLinked = false,
                                ),
                            )
                            busy = false
                        }
                    },
                ) {
                    Text(stringResource(R.string.settings_google_switch))
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
                        AudioChannelId.Internal ->
                            stringResource(R.string.settings_channel_internal)
                        AudioChannelId.Microphone ->
                            stringResource(R.string.settings_channel_mic)
                        else -> channel.name
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = when (channel.capture) {
                        com.rout.air.core.AudioCaptureKind.Playback ->
                            stringResource(R.string.settings_channel_internal_desc)

                        com.rout.air.core.AudioCaptureKind.Microphone ->
                            stringResource(R.string.settings_channel_mic_desc)
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
        stringResource(R.string.settings_meter_decay),
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
        stringResource(R.string.settings_peak_type),
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
    InfoRow(stringResource(R.string.settings_version), "1.0 (M4)")
    InfoRow(
        stringResource(R.string.settings_info_canvas),
        "${canvasConfig.width}x${canvasConfig.height}",
    )
    InfoRow(stringResource(R.string.settings_renderer), "OpenGL ES 3.0")
    InfoRow(stringResource(R.string.settings_codec), canvasConfig.videoCodec.label)
    InfoRow(
        stringResource(R.string.settings_encoder),
        encoder?.let {
            "${it.name} • ${if (it.hardware) "hardware" else "software"}"
        } ?: stringResource(R.string.settings_encoder_missing),
    )
    InfoRow(
        stringResource(R.string.settings_info_audio),
        stringResource(R.string.settings_info_audio_value),
    )
    InfoRow(stringResource(R.string.settings_rate_control), canvasConfig.bitrateMode.name)

    Text(
        stringResource(R.string.settings_encoder_scan),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (caps.isEmpty()) {
        Text(
            stringResource(R.string.settings_encoder_scan_empty),
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
    InfoRow(stringResource(R.string.settings_profile), canvasConfig.videoProfile.name)
    InfoRow(
        stringResource(R.string.settings_color),
        "${canvasConfig.colorSpace.name} • ${canvasConfig.colorRange.name}",
    )
    InfoRow(stringResource(R.string.settings_network), stringResource(R.string.settings_network_value))

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
    val context = LocalContext.current
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
                    benchError = error.message ?: context.getString(R.string.settings_test_failed)
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
            text = stringResource(
                R.string.settings_test_progress,
                runningCodec?.label ?: stringResource(R.string.settings_test_preparing),
                secondsLeft,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    benchError?.let { message ->
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.settings_test_failed_detail, message),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }

    results.forEach { result ->
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (result.error != null) {
                stringResource(
                    R.string.settings_test_codec_failed,
                    result.codec.label,
                    result.error ?: "",
                )
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

    var exportMessage by remember { mutableStateOf<String?>(null) }
    Spacer(Modifier.height(8.dp))
    Button(
        onClick = {
            exportMessage = DiagLog.exportToDownloads(context)
                ?.let { exported -> context.getString(R.string.settings_log_exported, exported) }
                ?: context.getString(R.string.settings_log_export_failed)
        },
    ) {
        Text(stringResource(R.string.settings_export_log))
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
    last?.let { Hint(stringResource(R.string.settings_last_result, it)) }
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
    Text(stringResource(R.string.settings_bench_graphic_load), style = MaterialTheme.typography.bodyMedium)
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
                label = { Text(stringResource(item.labelRes)) },
            )
        }
    }

    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.settings_codec), style = MaterialTheme.typography.bodyMedium)
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
    Text(stringResource(R.string.settings_resolution), style = MaterialTheme.typography.bodyMedium)
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
    Text(stringResource(R.string.settings_fps), style = MaterialTheme.typography.bodyMedium)
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
                label = { Text(stringResource(R.string.settings_bench_fps_option, item)) },
            )
        }
    }

    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.settings_bitrate), style = MaterialTheme.typography.bodyMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = !manualBitrate,
            onClick = { manualBitrate = false },
            label = { Text(stringResource(R.string.settings_otomatis)) },
        )
        FilterChip(
            selected = manualBitrate,
            onClick = { manualBitrate = true },
            label = { Text(stringResource(R.string.settings_bench_manual)) },
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
            stringResource(
                R.string.settings_bench_auto_bitrate,
                String.format(java.util.Locale.US, "%.1f Mbps", effectiveBitrate / 1_000_000f),
            ),
        )
    }

    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.settings_bench_duration), style = MaterialTheme.typography.bodyMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(1, 3, 5).forEach { item ->
            FilterChip(
                selected = minutes == item,
                onClick = { minutes = item },
                label = { Text(stringResource(R.string.settings_bench_minutes, item)) },
            )
        }
    }

    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(R.string.settings_bench_traffic, traffic.toInt()),
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
        title = stringResource(R.string.settings_bench_save_title),
        subtitle = stringResource(R.string.settings_bench_save_desc),
        checked = saveVideo,
    ) { saveVideo = it }
    if (saveVideo) {
        val estimateMb = effectiveBitrate / 8L * (minutes * 60) / 1_000_000.0
        Hint(
            stringResource(
                R.string.settings_bench_estimate,
                String.format(java.util.Locale.US, "%.0f MB", estimateMb),
                String.format(java.util.Locale.US, "%.1f Mbps", effectiveBitrate / 1_000_000f),
            ),
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
            stringResource(
                R.string.settings_bench_live_line,
                fpsNow,
                resolution.width,
                resolution.height,
                targetFps,
                GameLoadBenchmark.effectiveObjects(preset, traffic.toInt()),
            ),
        )
    }

    failure?.let {
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.settings_bench_failed, it),
            color = MaterialTheme.colorScheme.error,
        )
    }

    result?.let { data ->
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(
                R.string.settings_bench_result_line,
                data.codec.label,
                stringResource(data.preset.labelRes),
                data.width,
                data.height,
                data.targetFps,
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        InfoRow(
            stringResource(R.string.settings_bench_avg_fps),
            String.format(java.util.Locale.US, "%.1f", data.avgFps),
        )
        InfoRow(
            stringResource(R.string.settings_bench_worst_fps),
            String.format(java.util.Locale.US, "%.1f", data.worstFps),
        )
        InfoRow(
            stringResource(R.string.settings_bench_frames_late),
            "${data.frames} / ${data.lateFrames}",
        )
        InfoRow(stringResource(R.string.settings_bench_bitrate), "${data.bitrateKbps} kbps")
        InfoRow(
            stringResource(R.string.settings_bench_thermal),
            stringResource(data.thermalMax.labelRes),
        )
        data.uri?.let { InfoRow(stringResource(R.string.settings_bench_video), it) }
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
@Composable
private fun verdict(result: GameLoadBenchmark.Result): String = when {
    result.error != null -> stringResource(R.string.settings_bench_verdict_failed, result.error)
    result.avgFps >= result.targetFps * 0.95 -> stringResource(R.string.settings_bench_verdict_ok)
    result.worstFps < result.targetFps * 0.6 ->
        stringResource(R.string.settings_bench_verdict_unstable)

    else -> stringResource(R.string.settings_bench_verdict_close)
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

/**
 * Kelola beberapa koleksi scene (proyek) beserta ekspor/impor JSON.
 * Kanvas video tetap global; yang berpindah hanya susunan adegan dan sumber.
 */
@Composable
private fun SceneCollectionsSettings() {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    val collections = remember(version) { SceneManager.collections() }
    val active = collections.firstOrNull { it.active } ?: collections.firstOrNull()
    var message by remember { mutableStateOf<String?>(null) }
    var renameId by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteId by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            val ok = SceneManager.exportActive(uri)
            message = context.getString(
                if (ok) R.string.collections_export_done else R.string.collections_export_failed,
            )
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val imported = SceneManager.importCollection(
                uri,
                context.getString(R.string.collections_imported_name),
            )
            message = context.getString(
                if (imported != null) R.string.collections_import_done
                else R.string.collections_import_failed,
            )
            if (imported != null) version++
        }
    }

    SectionTitle(stringResource(R.string.settings_entry_adegan))
    Hint(stringResource(R.string.collections_hint))

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        collections.forEach { info ->
            FilterChip(
                selected = info.active,
                onClick = {
                    if (!info.active && SceneManager.switchCollection(info.id)) version++
                },
                label = {
                    Text(
                        info.title.ifBlank { stringResource(R.string.collections_untitled) },
                    )
                },
            )
        }
    }

    val activeTitle = active?.title?.ifBlank {
        context.getString(R.string.collections_untitled)
    }.orEmpty()
    val copyName = stringResource(R.string.collections_copy_name, activeTitle)

    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            onClick = {
                SceneManager.duplicateCollection(newTitle = copyName)
                version++
            },
        ) { Text(stringResource(R.string.collections_duplicate)) }
        OutlinedButton(
            onClick = {
                active?.let {
                    renameId = it.id
                    renameText = it.title
                }
            },
            enabled = active != null,
        ) { Text(stringResource(R.string.collections_rename)) }
        OutlinedButton(
            onClick = { deleteId = active?.id },
            enabled = collections.size > 1 && active != null,
        ) { Text(stringResource(R.string.common_hapus)) }
    }

    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(onClick = { exportLauncher.launch("RoutAir_scenes.json") }) {
            Text(stringResource(R.string.collections_export))
        }
        OutlinedButton(
            onClick = { importLauncher.launch(arrayOf("application/json", "text/plain")) },
        ) {
            Text(stringResource(R.string.collections_import))
        }
    }

    message?.let {
        Spacer(Modifier.height(6.dp))
        Text(
            text = it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    renameId?.let { id ->
        AlertDialog(
            onDismissRequest = { renameId = null },
            title = { Text(stringResource(R.string.collections_rename)) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.collections_name)) },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (renameText.isNotBlank()) {
                            SceneManager.renameCollection(id, renameText.trim())
                            version++
                        }
                        renameId = null
                    },
                ) { Text(stringResource(R.string.common_simpan)) }
            },
            dismissButton = {
                TextButton(onClick = { renameId = null }) {
                    Text(stringResource(R.string.common_batal))
                }
            },
        )
    }

    deleteId?.let { id ->
        AlertDialog(
            onDismissRequest = { deleteId = null },
            title = { Text(stringResource(R.string.collections_delete_title)) },
            text = { Text(stringResource(R.string.collections_delete_desc)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        SceneManager.deleteCollection(id)
                        version++
                        deleteId = null
                    },
                ) { Text(stringResource(R.string.common_hapus)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteId = null }) {
                    Text(stringResource(R.string.common_batal))
                }
            },
        )
    }
}
