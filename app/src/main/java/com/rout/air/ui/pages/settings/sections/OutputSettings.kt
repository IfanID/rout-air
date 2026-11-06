package com.rout.air.ui.pages.settings.sections

import androidx.compose.foundation.ScrollState
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rout.air.core.audio.AudioMix
import com.rout.air.core.model.CaptureConfig
import com.rout.air.core.model.LiveConfig
import com.rout.air.core.model.SaveLocation
import com.rout.air.data.audio.AudioMixer
import androidx.compose.ui.res.stringResource
import com.rout.air.R
import com.rout.air.ui.pages.settings.BitrateTarget
import com.rout.air.ui.pages.settings.OutputTab
import com.rout.air.ui.components.Hint
import com.rout.air.ui.pages.settings.components.InfoRow
import com.rout.air.ui.pages.settings.components.OutputSummary
import com.rout.air.ui.pages.settings.components.SectionTitle
import com.rout.air.ui.pages.settings.components.SettingSwitchRow
import com.rout.air.ui.pages.settings.components.bitrateText

/**
 * Halaman Output dipisah per tab. Tiap tab dibuka dengan ringkasan dan
 * bagian khususnya sendiri, baru blok setelan video bersama, sehingga
 * perbedaan antartab langsung terlihat tanpa perlu menggulir.
 */
@Composable
internal fun OutputSettings(
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
internal fun StreamingTab(
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
internal fun RecordingTab(
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
internal fun AudioOutputTab(
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

@Composable
internal fun saveLocationLabel(location: SaveLocation): String = when (location) {    SaveLocation.MoviesRoutAir -> stringResource(R.string.settings_lokasi_movies_routair)
    SaveLocation.Movies -> stringResource(R.string.settings_lokasi_movies)
    SaveLocation.DcimRoutAir -> stringResource(R.string.settings_lokasi_dcim)
    SaveLocation.DcimCamera -> stringResource(R.string.settings_lokasi_dcim_camera)
}
