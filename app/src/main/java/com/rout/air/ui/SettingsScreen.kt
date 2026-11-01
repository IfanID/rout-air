package com.rout.air.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import com.rout.air.core.VideoProfile
import com.rout.air.data.AudioMixer
import com.rout.air.live.RtmpTester
import com.rout.air.live.RtmpTestResult
import com.rout.air.live.DeviceLogin
import com.rout.air.live.GoogleAuth
import com.rout.air.live.GoogleAuthStore
import com.rout.air.live.TokenPoll
import com.rout.air.live.YouTubeApi
import androidx.compose.ui.res.stringResource
import com.rout.air.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    BackHandler(enabled = page != SettingsPage.Root) { page = SettingsPage.Root }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
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
                SettingsPage.Advanced -> AdvancedSettings(config, canvasConfig, onConfigChange)
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
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(0, 75, 50, 25).forEach { percent ->
            val selected = config.outputScalePercent == percent
            FilterChip(
                selected = selected,
                onClick = {
                    onConfigChange(config.copy(outputScalePercent = percent))
                },
                label = {
                    Text(if (percent == 0) stringResource(R.string.settings_same_as_canvas) else "$percent%")
                },
            )
        }
    }
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
    Audio("Audio"),
}

@Composable
private fun OutputSettings(
    config: CaptureConfig,
    mix: AudioMix,
    live: LiveConfig,
    onConfigChange: (CaptureConfig) -> Unit,
    onLiveConfigChange: (LiveConfig) -> Unit,
) {
    var tab by remember { mutableStateOf(OutputTab.Streaming) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutputTab.entries.forEach { item ->
            FilterChip(
                selected = tab == item,
                onClick = { tab = item },
                label = { Text(item.label) },
            )
        }
    }
    Hint(stringResource(R.string.settings_output_shared_hint))

    Spacer(Modifier.height(16.dp))
    SectionTitle(stringResource(R.string.settings_encoder))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = true,
            onClick = {},
            label = { Text(stringResource(R.string.settings_h_264_mediacodec)) },
        )
    }
    Hint(stringResource(R.string.settings_hevc_av1_menyusul_saat_ini_encoder_peran))

    Spacer(Modifier.height(16.dp))
    SectionTitle(stringResource(R.string.settings_bitrate_video))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(4, 6, 8, 10, 12).forEach { mbps ->
            FilterChip(
                selected = config.videoBitrate == mbps * 1_000_000,
                onClick = { onConfigChange(config.copy(videoBitrate = mbps * 1_000_000)) },
                label = { Text("$mbps Mbps") },
            )
        }
    }

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
    Hint("Bitrate audio berlaku pada sesi tangkap berikutnya.")

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
    Hint(stringResource(R.string.settings_rate_control_hint))

    Spacer(Modifier.height(12.dp))
    SectionTitle(stringResource(R.string.settings_keyframe_interval))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(1, 2, 4).forEach { seconds ->
            FilterChip(
                selected = config.keyframeIntervalSec == seconds,
                onClick = { onConfigChange(config.copy(keyframeIntervalSec = seconds)) },
                label = { Text("$seconds s") },
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
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(0, 75, 50, 25).forEach { percent ->
            val selected = config.outputScalePercent == percent
            FilterChip(
                selected = selected,
                onClick = {
                    onConfigChange(config.copy(outputScalePercent = percent))
                },
                label = {
                    Text(if (percent == 0) stringResource(R.string.settings_same_as_canvas) else "$percent%")
                },
            )
        }
    }

    when (tab) {
    OutputTab.Streaming -> {
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
    }

    OutputTab.Recording -> {
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
        SectionTitle(stringResource(R.string.settings_recording_quality))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                stringResource(R.string.settings_quality_same) to 6_000_000,
                stringResource(R.string.settings_quality_high) to 8_000_000,
                stringResource(R.string.settings_quality_indistinguishable) to 12_000_000,
            ).forEach { (label, bitrate) ->
                FilterChip(
                    selected = config.videoBitrate == bitrate,
                    onClick = { onConfigChange(config.copy(videoBitrate = bitrate)) },
                    label = { Text(label) },
                )
            }
        }

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
        SectionTitle(stringResource(R.string.settings_qs_tile))
        Hint(stringResource(R.string.settings_qs_tile_desc))

        Spacer(Modifier.height(12.dp))
        Hint(stringResource(R.string.settings_split_recording_dan_replay_buffer_menyus))
    }

    OutputTab.Audio -> {
        Spacer(Modifier.height(16.dp))
        SectionTitle(stringResource(R.string.settings_audio_encoder))
        InfoRow(stringResource(R.string.settings_encoder), "AAC-LC")
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
    }
    }

}

@Composable
private fun saveLocationLabel(location: SaveLocation): String = when (location) {
    SaveLocation.MoviesRoutAir -> stringResource(R.string.settings_lokasi_movies_routair)
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
    var clientId by remember { mutableStateOf(store.clientId) }
    var clientSecret by remember { mutableStateOf(store.clientSecret) }
    // Token lama tanpa scope tulis dianggap belum login.
    var email by remember { mutableStateOf(if (store.loggedIn) store.accountEmail else "") }
    var waiting by remember { mutableStateOf<DeviceLogin?>(null) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }

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
    } else {
        TextButton(
            enabled = !busy && waiting == null,
            onClick = {
                busy = true
                status = ""
                scope.launch {
                    var failure: String? = null
                    val device = withContext(Dispatchers.IO) {
                        runCatching {
                            GoogleAuth.requestDeviceCode(store.clientId, store.clientSecret)
                        }.onFailure { failure = it.message }.getOrNull()
                    }
                    if (device == null) {
                        status = context.getString(
                            R.string.settings_google_failed,
                            failure ?: "-",
                        )
                        busy = false
                        return@launch
                    }
                    waiting = device
                    // Langsung buka browser supaya terasa seperti login OBS.
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(device.verificationUrl))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                    var intervalMs = device.intervalSec * 1000L
                    var done = false
                    var token: String? = null
                    while (!done && System.currentTimeMillis() < device.expiresAtMs) {
                        delay(intervalMs)
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                GoogleAuth.pollToken(
                                    store.clientId,
                                    store.clientSecret,
                                    device.deviceCode,
                                )
                            }.getOrElse {
                                TokenPoll.Failure(it.message ?: "Gagal menghubungi Google")
                            }
                        }
                        when (result) {
                            is TokenPoll.Success -> {
                                store.saveTokens(result.tokens)
                                token = result.tokens.accessToken
                                done = true
                            }
                            TokenPoll.SlowDown -> intervalMs += 5_000L
                            is TokenPoll.Failure -> {
                                status = result.message
                                done = true
                            }
                            TokenPoll.Pending -> Unit
                        }
                    }
                    waiting = null
                    val access = token
                    if (access == null) {
                        if (status.isBlank()) {
                            status = context.getString(R.string.settings_google_expired)
                        }
                        busy = false
                        return@launch
                    }
                    val mail = withContext(Dispatchers.IO) {
                        GoogleAuth.accountEmail(access)
                    }
                    store.accountEmail = mail
                    email = mail
                    // Ambil server + kunci stream, sama seperti Connect Account OBS.
                    val target = withContext(Dispatchers.IO) {
                        runCatching { YouTubeApi(access).streamTarget() }.getOrNull()
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
                    busy = false
                }
            },
        ) {
            Text(stringResource(R.string.settings_google_connect))
        }
    }

    waiting?.let { device ->
        Spacer(Modifier.height(10.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    stringResource(R.string.settings_google_code_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = device.userCode,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Row {
                    TextButton(
                        onClick = {
                            val manager = context.getSystemService(
                                android.content.Context.CLIPBOARD_SERVICE,
                            ) as android.content.ClipboardManager
                            manager.setPrimaryClip(
                                android.content.ClipData.newPlainText(
                                    "kode",
                                    device.userCode,
                                ),
                            )
                            android.widget.Toast.makeText(
                                context,
                                R.string.settings_google_copied,
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        },
                    ) {
                        Text(stringResource(R.string.settings_google_copy))
                    }
                    TextButton(
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(device.verificationUrl))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        },
                    ) {
                        Text(stringResource(R.string.settings_google_open))
                    }
                }
                Text(
                    stringResource(R.string.settings_google_waiting),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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

    Spacer(Modifier.height(4.dp))
    TextButton(onClick = { editing = !editing }) {
        Text(
            stringResource(
                if (editing) R.string.settings_google_edit_done
                else R.string.settings_google_edit,
            ),
        )
    }
    if (editing) {
        OutlinedTextField(
            value = clientId,
            onValueChange = {
                clientId = it
                store.clientId = it
            },
            label = { Text(stringResource(R.string.settings_google_client_id)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = clientSecret,
            onValueChange = {
                clientSecret = it
                store.clientSecret = it
            },
            label = { Text(stringResource(R.string.settings_google_client_secret)) },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
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
    onConfigChange: (CaptureConfig) -> Unit,
) {
    SectionTitle(stringResource(R.string.settings_informasi))
    InfoRow("Versi", "1.0 (M4)")
    InfoRow("Kanvas", "${canvasConfig.width}x${canvasConfig.height}")
    InfoRow("Renderer", "OpenGL ES 3.0")
    InfoRow("Encoder", "MediaCodec H.264 + AAC")
    InfoRow("Audio", "Mixer OBS: internal + mikrofon")
    InfoRow("Rate control", canvasConfig.bitrateMode.name)
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
    SectionTitle(stringResource(R.string.settings_diagnostik))
    Hint(stringResource(R.string.settings_setiap_rekaman_menulis_log_ke_download_r))
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
