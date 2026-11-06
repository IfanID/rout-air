package com.rout.air.ui.pages.settings.sections

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import com.rout.air.core.audio.AudioChannel
import com.rout.air.core.audio.AudioChannelId
import com.rout.air.core.audio.AudioMix
import com.rout.air.core.model.CaptureConfig
import com.rout.air.core.audio.MeterDecay
import com.rout.air.core.audio.PeakMeterType
import com.rout.air.data.audio.AudioMixer
import androidx.compose.ui.res.stringResource
import com.rout.air.R
import com.rout.air.ui.components.audio.decayLabel
import com.rout.air.ui.components.audio.peakMeterLabel
import com.rout.air.ui.dialogs.AudioDevicePickerDialog
import com.rout.air.ui.components.Hint
import com.rout.air.ui.pages.settings.components.SectionTitle
import com.rout.air.ui.pages.settings.components.SettingSwitchRow

@Composable
internal fun AudioSettings(
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
                        com.rout.air.core.audio.AudioCaptureKind.Playback ->
                            stringResource(R.string.settings_channel_internal_desc)

                        com.rout.air.core.audio.AudioCaptureKind.Microphone ->
                            stringResource(R.string.settings_channel_mic_desc)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (channel.capture == com.rout.air.core.audio.AudioCaptureKind.Microphone) {
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
