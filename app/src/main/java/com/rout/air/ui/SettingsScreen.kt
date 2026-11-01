package com.rout.air.ui

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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rout.air.core.AudioChannel
import com.rout.air.core.AudioChannelId
import com.rout.air.core.AudioMix
import com.rout.air.core.CanvasOrientation
import com.rout.air.core.CaptureConfig
import com.rout.air.core.ContentMode
import com.rout.air.core.MeterDecay
import com.rout.air.core.PeakMeterType
import com.rout.air.core.SaveLocation
import com.rout.air.data.AudioMixer
import androidx.compose.ui.res.stringResource
import com.rout.air.R

private enum class SettingsPage(val title: String) {
    Root("Setelan"),
    Video("Video"),
    Output("Output"),
    Audio("Audio"),
    Hotkeys("Hotkeys"),
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
    onConfigChange: (CaptureConfig) -> Unit,
    onMixChange: (AudioChannel) -> Unit,
    onMasterEnabledChange: (Boolean) -> Unit,
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

            SettingsEntry("Video", "Kanvas, orientasi, resolusi, fps") {
                page = SettingsPage.Video
            }
            SettingsEntry("Output", "Encoder, bitrate, format, lokasi") {
                page = SettingsPage.Output
            }
            SettingsEntry("Audio", "Sumber audio, sample rate, kanal") {
                page = SettingsPage.Audio
            }
            SettingsEntry("Hotkeys", "Pintasan mulai/stop rekaman") {
                page = SettingsPage.Hotkeys
            }
            SettingsEntry("Advanced", "Informasi teknis dan diagnostik") {
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
                SettingsPage.Output -> OutputSettings(config, mix, onConfigChange)
                SettingsPage.Audio -> AudioSettings(
                    config = config,
                    mix = mix,
                    onMixChange = onMixChange,
                    onMasterEnabledChange = onMasterEnabledChange,
                    onConfigChange = onConfigChange,
                )
                SettingsPage.Hotkeys -> HotkeysSettings()
                SettingsPage.Advanced -> AdvancedSettings(canvasConfig)
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
    SectionTitle(stringResource(R.string.settings_kanvas_aktif))
    InfoRow("Ukuran", "${canvasConfig.width}x${canvasConfig.height}")
    InfoRow("Rasio", String.format("%.3f", canvasConfig.aspectRatio))
    InfoRow("Mode isi", contentModeLabel(canvasConfig))
    Hint(stringResource(R.string.settings_output_scaled_resolution_saat_ini_sama_d))
}

@Composable
private fun OutputSettings(
    config: CaptureConfig,
    mix: AudioMix,
    onConfigChange: (CaptureConfig) -> Unit,
) {
    SectionTitle(stringResource(R.string.settings_encoder))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = true, onClick = {}, label = { Text(stringResource(R.string.settings_h_264_mediacodec)) })
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

    Spacer(Modifier.height(16.dp))
    SectionTitle(stringResource(R.string.settings_track_audio))
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
                    AudioMixer.setOutputTracks(if (next and 0x3F == 0) mix.outputTracks else next)
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
    SectionTitle(stringResource(R.string.settings_format))
    InfoRow("Kontainer", "MP4")
    InfoRow("Video", "H.264")
    InfoRow("Audio", "AAC-LC")

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

@Composable
private fun saveLocationLabel(location: SaveLocation): String = when (location) {
    SaveLocation.MoviesRoutAir -> stringResource(R.string.settings_lokasi_movies_routair)
    SaveLocation.Movies -> stringResource(R.string.settings_lokasi_movies)
    SaveLocation.DcimRoutAir -> stringResource(R.string.settings_lokasi_dcim)
    SaveLocation.DcimCamera -> stringResource(R.string.settings_lokasi_dcim_camera)
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
private fun HotkeysSettings() {
    SectionTitle(stringResource(R.string.recordings_rekaman))
    HotkeyRow("Mulai merekam", "Belum diatur")
    HotkeyRow("Stop merekam", "Belum diatur")
    HotkeyRow("Pause rekaman", "Menyusul")
    HotkeyRow("Replay buffer", "Menyusul")
    Hint(stringResource(R.string.settings_pintasan_tombol_volume_dan_quick_setting))
}

@Composable
private fun AdvancedSettings(canvasConfig: CaptureConfig) {
    SectionTitle(stringResource(R.string.settings_informasi))
    InfoRow("Versi", "1.0 (M4)")
    InfoRow("Kanvas", "${canvasConfig.width}x${canvasConfig.height}")
    InfoRow("Renderer", "OpenGL ES 3.0")
    InfoRow("Encoder", "MediaCodec H.264 + AAC")
    InfoRow("Audio", "Mixer OBS: internal + mikrofon")

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
private fun HotkeyRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
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
