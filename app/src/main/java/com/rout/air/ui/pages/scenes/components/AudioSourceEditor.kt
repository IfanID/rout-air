package com.rout.air.ui.pages.scenes.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import com.rout.air.R
import com.rout.air.core.audio.AudioChannelId
import com.rout.air.core.model.CaptureConfig
import com.rout.air.core.model.SceneCollection
import com.rout.air.core.model.SceneItem
import com.rout.air.core.model.SceneSource
import com.rout.air.core.model.SourceType
import com.rout.air.data.scene.updateItem
import com.rout.air.data.scene.updateSource
import com.rout.air.data.audio.AudioMixer
import androidx.compose.ui.res.stringResource
import com.rout.air.ui.components.audio.VolumeMeter
import com.rout.air.ui.dialogs.AudioDevicePickerDialog
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.unit.dp

@Composable
internal fun AudioSourceEditor(
    canvas: CaptureConfig,
    collection: SceneCollection,
    item: SceneItem,
    source: SceneSource,
    onCollectionChange: (SceneCollection) -> Unit,
    onBack: () -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember(source.id) { mutableStateOf(source.name) }
    var showDevicePicker by remember(source.id) { mutableStateOf(false) }

    val mix by AudioMixer.mix.collectAsState()
    val levels by AudioMixer.levels.collectAsState()
    val runtime by AudioMixer.runtime.collectAsState()

    val channelId = AudioChannelId.forSource(source.id)
    val channel = mix.channel(channelId)
    val level = levels.forChannel(channelId)
    val ready = runtime.running && runtime.ready(channelId)
    val isBluetooth = source.settings.audioDeviceType == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO

    fun updateSource(next: SceneSource) = onCollectionChange(collection.updateSource(next))
    fun updateItem(next: SceneItem) = onCollectionChange(collection.updateItem(next))

    val statusText = when {
        !channel.enabled -> stringResource(R.string.audio_status_off)
        !runtime.running -> stringResource(R.string.audio_status_no_session)
        !ready -> stringResource(R.string.audio_status_failed)
        channel.muted -> stringResource(R.string.audio_status_muted)
        else -> stringResource(R.string.audio_status_on)
    }
    val statusColor = when {
        !channel.enabled || !runtime.running -> MaterialTheme.colorScheme.onSurfaceVariant
        !ready -> MaterialTheme.colorScheme.error
        channel.muted -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.common_kembali_panah)) }
            Text(
                text = source.name,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // Kunci/unlock item langsung dari halaman sumber audio.
            RowIconAction(
                onClick = { updateItem(item.copy(locked = !item.locked)) },
                background = if (item.locked) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                } else {
                    null
                },
            ) {
                Icon(
                    painter = painterResource(
                        if (item.locked) R.drawable.ic_lock else R.drawable.ic_lock_open,
                    ),
                    contentDescription = stringResource(
                        if (item.locked) R.string.icon_unlock else R.string.icon_lock,
                    ),
                    tint = if (item.locked) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = if (source.type == SourceType.AudioInput) {
                        stringResource(R.string.scenes_tipe_audio_input)
                    } else {
                        stringResource(R.string.scenes_tipe_audio_output)
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = if (source.type == SourceType.AudioInput) {
                        stringResource(R.string.scenes_audio_input_desc)
                    } else {
                        stringResource(R.string.scenes_audio_output_desc)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))

                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        updateSource(source.copy(name = it))
                    },
                    label = { Text(stringResource(R.string.scenes_nama_sumber)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (source.type == SourceType.AudioInput) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.scenes_perangkat_n, source.settings.audioDeviceName ?: androidx.compose.ui.platform.LocalContext.current.getString(R.string.common_default)),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = when (source.settings.audioDeviceType) {
                            android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC ->
                                stringResource(R.string.audio_device_builtin)
                            android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO ->
                                stringResource(R.string.audio_device_bt)
                            android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ->
                                stringResource(R.string.audio_device_wired)
                            android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
                            android.media.AudioDeviceInfo.TYPE_USB_DEVICE,
                            android.media.AudioDeviceInfo.TYPE_USB_ACCESSORY ->
                                stringResource(R.string.audio_device_usb)
                            android.media.AudioDeviceInfo.TYPE_LINE_ANALOG,
                            android.media.AudioDeviceInfo.TYPE_LINE_DIGITAL ->
                                stringResource(R.string.audio_device_line)
                            0 -> stringResource(R.string.audio_device_follow)
                            else -> stringResource(R.string.scenes_tipe_audio_input)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { showDevicePicker = true }) {
                        Text(stringResource(R.string.scenes_ganti_perangkat_input))
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.scenes_level_input), style = MaterialTheme.typography.bodyMedium)
                VolumeMeter(
                    level = level,
                    active = ready && channel.enabled && !channel.muted,
                    decay = mix.meterDecay,
                    vertical = false,
                    modifier = Modifier.fillMaxWidth(),
                    showScale = true,
                    muted = channel.muted,
                    peakType = mix.peakMeterType,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColor,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.audio_volume_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onDelete) { Text(stringResource(R.string.scenes_hapus_sumber)) }
    }

    if (showDevicePicker) {
        AudioDevicePickerDialog(
            currentId = source.settings.audioDeviceId,
            onSelect = { option ->
                updateSource(
                    source.copy(
                        settings = source.settings.copy(
                            audioDeviceId = option?.id ?: 0,
                            audioDeviceName = option?.name,
                            audioDeviceType = option?.type ?: 0,
                            audioScoEnabled = if (option?.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                                source.settings.audioScoEnabled
                            } else {
                                false
                            },
                        ),
                    ),
                )
                showDevicePicker = false
            },
            onDismiss = { showDevicePicker = false },
        )
    }
}
