package com.rout.air.ui.components.audio

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.rout.air.core.audio.AudioChannel
import com.rout.air.core.audio.ChannelLevel
import com.rout.air.core.audio.MeterDecay
import com.rout.air.data.audio.AudioMixer
import com.rout.air.ui.theme.Divider
import com.rout.air.ui.theme.ErrorRed
import com.rout.air.ui.theme.TextPrimary
import com.rout.air.ui.theme.TextTertiary
import kotlin.math.roundToInt
import androidx.compose.ui.res.stringResource
import com.rout.air.R
import com.rout.air.ui.components.DragHandleIcon

@Composable
internal fun StatusChip(category: ChannelCategory, modifier: Modifier = Modifier) {
    val style = categoryStyle(category)
    Text(
        text = categoryLabel(category),
        style = MaterialTheme.typography.labelSmall.copy(
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
        ),
        color = style.foreground,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(style.background)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}


/** Baris kanal untuk tata letak horizontal OBS. */
@Composable
internal fun AudioChannelRow(
    channel: AudioChannel,
    category: ChannelCategory,
    level: ChannelLevel,
    active: Boolean,
    usePercent: Boolean,
    decay: MeterDecay,
    peakType: com.rout.air.core.audio.PeakMeterType,
    onChannelChange: (AudioChannel) -> Unit,
    onUnhideAll: () -> Unit,
    onRename: () -> Unit,
    onFilters: () -> Unit,
    onProperties: () -> Unit,
    canPasteFilters: Boolean,
    onCopyFilters: () -> Unit,
    onPasteFilters: () -> Unit,
    onChangeDevice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var rowHeightPx by remember(channel.id) { mutableFloatStateOf(0f) }
    var dragY by remember(channel.id) { mutableFloatStateOf(0f) }
    var reordering by remember(channel.id) { mutableStateOf(false) }
    val density = LocalDensity.current
    val dragOffset by animateFloatAsState(
        targetValue = dragY,
        animationSpec = if (reordering) snap() else tween(durationMillis = 160),
        label = stringResource(R.string.audio_geser_audio),
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .zIndex(if (reordering) 1f else 0f)
            .onSizeChanged { rowHeightPx = it.height.toFloat() }
            .offset { IntOffset(0, dragOffset.roundToInt()) }
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Box {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .pointerInput(channel.id) {
                            detectVerticalDragGestures(
                                onDragStart = { reordering = true },
                                onDragEnd = {
                                    reordering = false
                                    val pitch = (rowHeightPx + with(density) { 1.dp.toPx() })
                                        .coerceAtLeast(1f)
                                    val step = (dragY / pitch).roundToInt()
                                    dragY = 0f
                                    if (step != 0) {
                                        AudioMixer.moveChannel(channel.id, step)
                                    }
                                },
                                onDragCancel = {
                                    reordering = false
                                    dragY = 0f
                                },
                            ) { change, dragAmount ->
                                change.consume()
                                dragY += dragAmount
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    DragHandleIcon(color = TextTertiary, size = 16.dp)
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { menuOpen = true }
                        .padding(vertical = 2.dp),
                ) {
                    Text(
                        text = channel.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (channel.enabled && active) TextPrimary else TextTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = channelSubtitle(channel),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = TextTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                StatusChip(category)
                Spacer(Modifier.width(4.dp))
                // On/off kanal tepat di kanan label Aktif/Nonaktif.
                MixerIconButton(
                    onClick = {
                        onChannelChange(channel.copy(enabled = !channel.enabled))
                    },
                    description = stringResource(
                        if (channel.enabled) R.string.audio_disable_channel
                        else R.string.audio_enable_channel,
                    ),
                    active = !channel.enabled,
                    activeColor = ErrorRed,
                    activeBackground = Color.Transparent,
                ) { color -> PowerIcon(color, size = 16.dp) }
                Spacer(Modifier.width(8.dp))
                VolumeReadout(channel.gainDb, usePercent)
            }
            ChannelContextMenu(
                expanded = menuOpen,
                channel = channel,
                canPasteFilters = canPasteFilters,
                onDismiss = { menuOpen = false },
                onChangeDevice = onChangeDevice,
                onUnhideAll = {
                    menuOpen = false
                    onUnhideAll()
                },
                onPin = {
                    onChannelChange(channel.copy(pinned = !channel.pinned, hidden = false))
                    menuOpen = false
                },
                onHide = {
                    onChannelChange(channel.copy(hidden = !channel.hidden, pinned = false))
                    menuOpen = false
                },
                onLock = {
                    onChannelChange(channel.copy(locked = !channel.locked))
                    menuOpen = false
                },
                onCopyFilters = {
                    onCopyFilters()
                    menuOpen = false
                },
                onPasteFilters = {
                    onPasteFilters()
                    menuOpen = false
                },
                onRename = {
                    menuOpen = false
                    onRename()
                },
                onFilters = {
                    menuOpen = false
                    onFilters()
                },
                onProperties = {
                    menuOpen = false
                    onProperties()
                },
            )
        }

        Spacer(Modifier.height(6.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                MixerIconButton(
                    onClick = {
                        onChannelChange(channel.copy(muted = !channel.muted))
                    },
                    description = stringResource(
                        if (channel.muted) R.string.audio_unmute else R.string.audio_mute,
                    ),
                    active = channel.muted,
                    activeColor = ErrorRed,
                    activeBackground = Color.Transparent,
                ) { color -> SpeakerIcon(channel.muted, color, 18.dp) }
                MixerIconButton(
                    onClick = {
                        onChannelChange(
                            channel.copy(
                                monitor = if (channel.monitor.monitors) {
                                    com.rout.air.core.audio.MonitorMode.Off
                                } else {
                                    com.rout.air.core.audio.MonitorMode.MonitorAndOutput
                                },
                            ),
                        )
                    },
                    description = stringResource(
                        if (channel.monitor.monitors) R.string.audio_monitor_off_action
                        else R.string.audio_monitor_on_action,
                    ),
                    active = channel.monitor.monitors,
                    activeColor = Color.White,
                    activeBackground = Color(0xFF1E8528),
                ) { color -> HeadphoneIcon(channel.monitor.monitors, color, 18.dp) }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                VolumeFader(
                    gainDb = channel.gainDb,
                    enabled = channel.enabled && !channel.locked,
                    vertical = false,
                    onGainChange = { onChannelChange(channel.copy(gainDb = it)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                VolumeMeter(
                    level = level,
                    active = active && channel.enabled,
                    decay = decay,
                    vertical = false,
                    modifier = Modifier.fillMaxWidth(),
                    showScale = true,
                    muted = channel.muted,
                    peakType = peakType,
                )
            }
            if (channel.locked) {
                Spacer(Modifier.width(8.dp))
                LockIcon(locked = true, color = TextTertiary, modifier = Modifier.size(16.dp))
            }
        }
    }
}


/** Kolom kanal untuk tata letak vertikal OBS. */
@Composable
internal fun AudioChannelColumn(
    channel: AudioChannel,
    category: ChannelCategory,
    level: ChannelLevel,
    active: Boolean,
    usePercent: Boolean,
    decay: MeterDecay,
    peakType: com.rout.air.core.audio.PeakMeterType,
    width: Dp,
    onChannelChange: (AudioChannel) -> Unit,
    onUnhideAll: () -> Unit,
    onRename: () -> Unit,
    onFilters: () -> Unit,
    onProperties: () -> Unit,
    canPasteFilters: Boolean,
    onCopyFilters: () -> Unit,
    onPasteFilters: () -> Unit,
    onChangeDevice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .width(width)
            .fillMaxHeight()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            // Label status + tombol on/off berdampingan.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                StatusChip(category)
                MixerIconButton(
                    onClick = {
                        onChannelChange(channel.copy(enabled = !channel.enabled))
                    },
                    description = stringResource(
                        if (channel.enabled) R.string.audio_disable_channel
                        else R.string.audio_enable_channel,
                    ),
                    active = !channel.enabled,
                    activeColor = ErrorRed,
                    activeBackground = Color.Transparent,
                ) { color -> PowerIcon(color, size = 15.dp) }
            }
        }
        Spacer(Modifier.height(4.dp))
        Box {
            Text(
                text = channel.name,
                style = MaterialTheme.typography.labelMedium,
                color = if (channel.enabled && active) TextPrimary else TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { menuOpen = true }
                    .padding(vertical = 2.dp),
            )
            ChannelContextMenu(
                expanded = menuOpen,
                channel = channel,
                canPasteFilters = canPasteFilters,
                onDismiss = { menuOpen = false },
                onChangeDevice = onChangeDevice,
                onUnhideAll = {
                    menuOpen = false
                    onUnhideAll()
                },
                onPin = {
                    onChannelChange(channel.copy(pinned = !channel.pinned, hidden = false))
                    menuOpen = false
                },
                onHide = {
                    onChannelChange(channel.copy(hidden = !channel.hidden, pinned = false))
                    menuOpen = false
                },
                onLock = {
                    onChannelChange(channel.copy(locked = !channel.locked))
                    menuOpen = false
                },
                onCopyFilters = {
                    onCopyFilters()
                    menuOpen = false
                },
                onPasteFilters = {
                    onPasteFilters()
                    menuOpen = false
                },
                onRename = {
                    menuOpen = false
                    onRename()
                },
                onFilters = {
                    menuOpen = false
                    onFilters()
                },
                onProperties = {
                    menuOpen = false
                    onProperties()
                },
            )
        }
        VolumeReadout(channel.gainDb, usePercent)
        Spacer(Modifier.height(6.dp))

        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.Center,
        ) {
            VolumeFader(
                gainDb = channel.gainDb,
                enabled = channel.enabled && !channel.locked,
                vertical = true,
                onGainChange = { onChannelChange(channel.copy(gainDb = it)) },
                modifier = Modifier.fillMaxHeight().width(34.dp),
            )
            Spacer(Modifier.width(4.dp))
            VolumeMeter(
                level = level,
                active = active && channel.enabled,
                decay = decay,
                vertical = true,
                modifier = Modifier.fillMaxHeight(),
                showScale = true,
                muted = channel.muted,
                peakType = peakType,
            )
        }

        if (channel.locked) {
            Spacer(Modifier.height(4.dp))
            LockIcon(locked = true, color = TextTertiary, modifier = Modifier.size(14.dp))
        }

        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            MixerIconButton(
                onClick = { onChannelChange(channel.copy(muted = !channel.muted)) },
                description = stringResource(
                        if (channel.muted) R.string.audio_unmute else R.string.audio_mute,
                    ),
                active = channel.muted,
                activeColor = ErrorRed,
                activeBackground = Color.Transparent,
            ) { color -> SpeakerIcon(channel.muted, color, 17.dp) }
            MixerIconButton(
                onClick = {
                    onChannelChange(
                        channel.copy(
                            monitor = if (channel.monitor.monitors) {
                                com.rout.air.core.audio.MonitorMode.Off
                            } else {
                                com.rout.air.core.audio.MonitorMode.MonitorAndOutput
                            },
                        ),
                    )
                },
                description = stringResource(
                    if (channel.monitor.monitors) R.string.audio_monitor_off_action
                    else R.string.audio_monitor_on_action,
                ),
                active = channel.monitor.monitors,
                activeColor = Color.White,
                activeBackground = Color(0xFF1E8528),
            ) { color -> HeadphoneIcon(channel.monitor.monitors, color, 17.dp) }
        }
    }
}


@Composable
private fun ChannelContextMenu(
    expanded: Boolean,
    channel: AudioChannel,
    canPasteFilters: Boolean,
    onDismiss: () -> Unit,
    onChangeDevice: () -> Unit,
    onUnhideAll: () -> Unit,
    onPin: () -> Unit,
    onHide: () -> Unit,
    onLock: () -> Unit,
    onCopyFilters: () -> Unit,
    onPasteFilters: () -> Unit,
    onRename: () -> Unit,
    onFilters: () -> Unit,
    onProperties: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text(stringResource(R.string.audio_tampilkan_semua_yang_disembunyikan)) }, onClick = onUnhideAll)
        HorizontalDivider(color = Divider)
        DropdownMenuItem(
            text = { Text(if (channel.pinned) "Lepas sematan" else "Sematkan") },
            onClick = onPin,
        )
        DropdownMenuItem(
            text = { Text(if (channel.hidden) "Tampilkan" else "Sembunyikan") },
            onClick = onHide,
        )
        DropdownMenuItem(
            text = { Text(if (channel.locked) "Buka kunci volume" else "Kunci volume") },
            onClick = onLock,
        )
        HorizontalDivider(color = Divider)
        DropdownMenuItem(
            text = { Text(stringResource(R.string.audio_pindah_ke_atas)) },
            onClick = {
                AudioMixer.moveChannel(channel.id, -1)
                onDismiss()
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.audio_pindah_ke_bawah)) },
            onClick = {
                AudioMixer.moveChannel(channel.id, 1)
                onDismiss()
            },
        )
        if (channel.capture == com.rout.air.core.audio.AudioCaptureKind.Microphone) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.audio_ganti_perangkat_input)) },
                onClick = {
                    onDismiss()
                    onChangeDevice()
                },
            )
        }
        if (channel.appUid != 0) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.audio_hapus_kanal_audio)) },
                onClick = {
                    AudioMixer.removeChannel(channel.id)
                    onDismiss()
                },
            )
        }
        HorizontalDivider(color = Divider)
        DropdownMenuItem(
            text = { Text(stringResource(R.string.audio_salin_filter)) },
            enabled = channel.filters.isNotEmpty(),
            onClick = onCopyFilters,
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.audio_tempel_filter)) },
            enabled = canPasteFilters,
            onClick = onPasteFilters,
        )
        DropdownMenuItem(text = { Text(stringResource(R.string.audio_ganti_nama_2)) }, onClick = onRename)
        HorizontalDivider(color = Divider)
        DropdownMenuItem(text = { Text(stringResource(R.string.audio_filter)) }, onClick = onFilters)
        DropdownMenuItem(text = { Text(stringResource(R.string.audio_properti)) }, onClick = onProperties)
    }
}
