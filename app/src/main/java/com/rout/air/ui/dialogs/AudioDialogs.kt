package com.rout.air.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rout.air.core.audio.AudioChannel
import com.rout.air.core.audio.AudioChannelId
import com.rout.air.core.audio.AudioFilter
import com.rout.air.core.audio.AudioFilterType
import com.rout.air.core.audio.AudioMix
import com.rout.air.core.audio.MonitorMode
import com.rout.air.data.audio.AudioMixer
import com.rout.air.ui.theme.Divider
import com.rout.air.ui.theme.TextSecondary
import com.rout.air.ui.theme.TextTertiary
import java.util.UUID
import androidx.compose.ui.res.stringResource
import com.rout.air.R
import com.rout.air.ui.components.audio.filterLabel
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp
import com.rout.air.ui.dialogs.components.AdvancedHeader
import com.rout.air.ui.dialogs.components.AdvancedRow
import com.rout.air.ui.dialogs.components.FilterListRow
import com.rout.air.ui.dialogs.components.FilterParam
import com.rout.air.ui.dialogs.components.PropertyToggle
import com.rout.air.ui.dialogs.components.TinyToggle
import com.rout.air.ui.dialogs.components.moveFilter

/** Tabel Advanced Audio Properties ala OBS. */
@Composable
internal fun AdvancedAudioDialog(
    mix: AudioMix,
    activeIds: Set<AudioChannelId>,
    onChannelChange: (AudioChannel) -> Unit,
    onDismiss: () -> Unit,
) {
    var activeOnly by remember { mutableStateOf(true) }
    val rows = mix.channels.filter { !activeOnly || it.id in activeIds }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.97f),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.audio_properti_audio_lanjutan),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(stringResource(R.string.audio_volume), style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                    Spacer(Modifier.width(4.dp))
                    TinyToggle(
                        checked = mix.volumePercent,
                        onCheckedChange = { AudioMixer.setVolumePercent(it) },
                    )
                }

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                        .horizontalScroll(rememberScrollState()),
                ) {
                    Column {
                        AdvancedHeader()
                        HorizontalDivider(color = Divider)
                        if (rows.isEmpty()) {
                            Text(
                                text = stringResource(R.string.audio_tidak_ada_sumber_aktif),
                                style = MaterialTheme.typography.bodySmall,
                                color = TextTertiary,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                        rows.forEach { channel ->
                            AdvancedRow(
                                channel = channel,
                                active = channel.id in activeIds,
                                usePercent = mix.volumePercent,
                                onChannelChange = onChannelChange,
                            )
                            HorizontalDivider(color = Divider)
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TinyToggle(
                        checked = activeOnly,
                        onCheckedChange = { activeOnly = it },
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.audio_hanya_sumber_aktif), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_tutup)) }
                }
            }
        }
    }
}


/** Dialog Filters per kanal. */
@Composable
internal fun FiltersDialog(
    channel: AudioChannel,
    canPaste: Boolean,
    onChannelChange: (AudioChannel) -> Unit,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedId by remember { mutableStateOf(channel.filters.firstOrNull()?.id) }
    var addOpen by remember { mutableStateOf(false) }
    val selected = channel.filters.firstOrNull { it.id == selectedId }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.97f).fillMaxHeight(0.88f),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.audio_filter_channel, channel.name),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onCopy, enabled = channel.filters.isNotEmpty()) {
                        Text(stringResource(R.string.audio_salin))
                    }
                    TextButton(onClick = onPaste, enabled = canPaste) { Text(stringResource(R.string.audio_tempel)) }
                }

                Row(Modifier.weight(1f)) {
                    Column(Modifier.width(190.dp).fillMaxHeight()) {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                            channel.filters.forEach { filter ->
                                FilterListRow(
                                    filter = filter,
                                    selected = filter.id == selectedId,
                                    onSelect = { selectedId = filter.id },
                                    onToggle = { enabled ->
                                        onChannelChange(
                                            channel.copy(
                                                filters = channel.filters.map {
                                                    if (it.id == filter.id) it.copy(enabled = enabled) else it
                                                },
                                            ),
                                        )
                                    },
                                    onUp = {
                                        moveFilter(channel, filter.id, -1, onChannelChange)
                                    },
                                    onDown = {
                                        moveFilter(channel, filter.id, 1, onChannelChange)
                                    },
                                    onDelete = {
                                        selectedId = null
                                        onChannelChange(
                                            channel.copy(
                                                filters = channel.filters.filterNot { it.id == filter.id },
                                            ),
                                        )
                                    },
                                )
                            }
                            if (channel.filters.isEmpty()) {
                                Text(
                                    stringResource(R.string.audio_no_filter),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextTertiary,
                                    modifier = Modifier.padding(8.dp),
                                )
                            }
                        }
                        Box {
                            TextButton(onClick = { addOpen = true }) { Text(stringResource(R.string.audio_tambah_filter)) }
                            DropdownMenu(expanded = addOpen, onDismissRequest = { addOpen = false }) {
                                AudioFilterType.entries.forEach { type ->
                                    DropdownMenuItem(
                                        text = { Text(filterLabel(type)) },
                                        onClick = {
                                            val filter = AudioFilter(
                                                id = UUID.randomUUID().toString(),
                                                type = type,
                                            )
                                            onChannelChange(
                                                channel.copy(filters = channel.filters + filter),
                                            )
                                            selectedId = filter.id
                                            addOpen = false
                                        },
                                    )
                                }
                            }
                        }
                    }

                    Box(Modifier.width(1.dp).fillMaxHeight().background(Divider))
                    Spacer(Modifier.width(10.dp))

                    Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                        if (selected == null) {
                            Text(
                                stringResource(R.string.audio_select_filter_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = TextTertiary,
                            )
                        } else {
                            Text(filterLabel(selected.type), style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(4.dp))
                            selected.type.defaults().forEach { (key, _) ->
                                FilterParam(
                                    filter = selected,
                                    paramKey = key,
                                    onChannelChange = onChannelChange,
                                    channel = channel,
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_tutup)) }
                }
            }
        }
    }
}


/** Properti sumber: nama, aktif, pin/hidden/lock, monitor. */
@Composable
internal fun SourcePropertiesDialog(
    channel: AudioChannel,
    onChannelChange: (AudioChannel) -> Unit,
    onOpenRename: () -> Unit,
    onOpenFilters: () -> Unit,
    onOpenDevicePicker: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(channel.name) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.audio_properti_channel, channel.name), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.audio_nama)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (channel.capture == com.rout.air.core.audio.AudioCaptureKind.Microphone) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.scenes_perangkat_input), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = channel.deviceName ?: "Default",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextTertiary,
                            )
                        }
                        TextButton(onClick = onOpenDevicePicker) { Text(stringResource(R.string.audio_ganti)) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                PropertyToggle("Aktif", channel.enabled) {
                    onChannelChange(channel.copy(enabled = it))
                }
                PropertyToggle("Sematkan", channel.pinned) {
                    onChannelChange(channel.copy(pinned = it, hidden = false))
                }
                PropertyToggle("Sembunyikan", channel.hidden) {
                    onChannelChange(channel.copy(hidden = it, pinned = false))
                }
                PropertyToggle("Kunci volume", channel.locked) {
                    onChannelChange(channel.copy(locked = it))
                }
                PropertyToggle("Monitoring", channel.monitor.monitors) {
                    onChannelChange(
                        channel.copy(
                            monitor = if (it) MonitorMode.MonitorAndOutput else MonitorMode.Off,
                        ),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = when {
                        channel.appUid != 0 -> stringResource(
                            R.string.audio_source_app,
                            channel.appPackage
                                ?: stringResource(R.string.audio_source_app_generic),
                        )

                        channel.capture == com.rout.air.core.audio.AudioCaptureKind.Playback ->
                            stringResource(R.string.audio_source_playback)

                        else -> stringResource(R.string.audio_source_record)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onOpenFilters) { Text(stringResource(R.string.audio_filter)) }
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = {
                            onChannelChange(channel.copy(name = name.trim().ifEmpty { channel.name }))
                            onDismiss()
                        },
                    ) { Text(stringResource(R.string.common_simpan)) }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_tutup)) }
                }
            }
        }
    }
}


@Composable
internal fun RenameChannelDialog(
    channel: AudioChannel,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(channel.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.audio_ganti_nama_sumber_audio)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.audio_nama)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val trimmed = name.trim()
                    if (trimmed.isNotEmpty()) onRename(trimmed)
                },
            ) { Text(stringResource(R.string.audio_ganti_nama)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_batal)) }
        },
    )
}
