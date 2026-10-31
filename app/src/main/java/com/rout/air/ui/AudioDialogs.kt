package com.rout.air.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rout.air.core.AudioChannel
import com.rout.air.core.AudioChannelId
import com.rout.air.core.AudioFilter
import com.rout.air.core.AudioFilterType
import com.rout.air.core.AudioMix
import com.rout.air.core.FaderScale
import com.rout.air.core.MonitorMode
import com.rout.air.data.AudioMixer
import com.rout.air.ui.theme.AccentCyan
import com.rout.air.ui.theme.Divider
import com.rout.air.ui.theme.ErrorRed
import com.rout.air.ui.theme.OnAccent
import com.rout.air.ui.theme.SurfaceBorder
import com.rout.air.ui.theme.SurfaceLow
import com.rout.air.ui.theme.TextPrimary
import com.rout.air.ui.theme.TextSecondary
import com.rout.air.ui.theme.TextTertiary
import java.util.UUID
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

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
                        text = "Properti Audio Lanjutan",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text("Volume %", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
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
                                text = "Tidak ada sumber aktif.",
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
                    Text("Hanya sumber aktif", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Tutup") }
                }
            }
        }
    }
}

@Composable
private fun AdvancedHeader() {
    Row(Modifier.height(28.dp), verticalAlignment = Alignment.CenterVertically) {
        HeaderCell("Sumber", 140.dp)
        HeaderCell("Status", 72.dp)
        HeaderCell("Volume", 124.dp)
        HeaderCell("Mono", 52.dp)
        HeaderCell("Balance", 164.dp)
        HeaderCell("Sync", 104.dp)
        HeaderCell("Monitor", 64.dp)
        HeaderCell("Tracks", 164.dp)
    }
}

@Composable
private fun HeaderCell(text: String, width: androidx.compose.ui.unit.Dp) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = TextSecondary,
        modifier = Modifier.width(width).padding(horizontal = 4.dp),
    )
}

@Composable
private fun AdvancedRow(
    channel: AudioChannel,
    active: Boolean,
    usePercent: Boolean,
    onChannelChange: (AudioChannel) -> Unit,
) {
    Row(
        modifier = Modifier.height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = channel.name,
            style = MaterialTheme.typography.bodySmall,
            color = if (channel.enabled) TextPrimary else TextTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(140.dp).padding(horizontal = 4.dp),
        )
        Text(
            text = if (active) "Aktif" else "Nonaktif",
            style = MaterialTheme.typography.labelSmall,
            color = if (active) AccentCyan else TextTertiary,
            modifier = Modifier.width(72.dp).padding(horizontal = 4.dp),
        )

        val volumeText = if (usePercent) {
            "${(10f.pow(channel.gainDb / 20f) * 100f).roundToInt()}%"
        } else {
            FaderScale.formatDb(channel.gainDb)
        }
        Stepper(
            value = volumeText,
            width = 124.dp,
            onMinus = {
                onChannelChange(channel.copy(gainDb = FaderScale.snapDbWide(channel.gainDb - 0.5f)))
            },
            onPlus = {
                onChannelChange(channel.copy(gainDb = FaderScale.snapDbWide(channel.gainDb + 0.5f)))
            },
        )

        Box(Modifier.width(52.dp), contentAlignment = Alignment.Center) {
            TinyToggle(
                checked = channel.forceMono,
                onCheckedChange = { onChannelChange(channel.copy(forceMono = it)) },
            )
        }

        Row(
            modifier = Modifier.width(164.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("L", style = MaterialTheme.typography.labelSmall, color = TextTertiary)
            BalanceFader(
                value = channel.balance,
                onChange = { onChannelChange(channel.copy(balance = it)) },
                modifier = Modifier.weight(1f).height(26.dp),
            )
            Text("R", style = MaterialTheme.typography.labelSmall, color = TextTertiary)
        }

        Stepper(
            value = "${channel.syncOffsetMs} ms",
            width = 104.dp,
            onMinus = {
                onChannelChange(
                    channel.copy(syncOffsetMs = (channel.syncOffsetMs - 10).coerceAtLeast(-950)),
                )
            },
            onPlus = {
                onChannelChange(
                    channel.copy(syncOffsetMs = (channel.syncOffsetMs + 10).coerceAtMost(20_000)),
                )
            },
        )

        Box(Modifier.width(64.dp), contentAlignment = Alignment.Center) {
            TinyToggle(
                checked = channel.monitor.monitors,
                onCheckedChange = {
                    onChannelChange(
                        channel.copy(
                            monitor = if (it) MonitorMode.MonitorAndOutput else MonitorMode.Off,
                        ),
                    )
                },
            )
        }

        Row(
            modifier = Modifier.width(164.dp).padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            for (track in 1..6) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    TinyToggle(
                        checked = channel.tracks and (1 shl (track - 1)) != 0,
                        onCheckedChange = { checked ->
                            val bit = 1 shl (track - 1)
                            onChannelChange(
                                channel.copy(
                                    tracks = if (checked) channel.tracks or bit else channel.tracks and bit.inv(),
                                ),
                            )
                        },
                    )
                    Text(
                        text = "$track",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                        color = TextTertiary,
                    )
                }
            }
        }
    }
}

@Composable
private fun Stepper(
    value: String,
    width: androidx.compose.ui.unit.Dp,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
) {
    Row(
        modifier = Modifier.width(width).padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        TinyButton("−", onMinus)
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = TextPrimary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.width(64.dp),
        )
        TinyButton("+", onPlus)
    }
}

@Composable
private fun TinyButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(20.dp)
            .background(SurfaceLow, RoundedCornerShape(3.dp))
            .border(1.dp, SurfaceBorder, RoundedCornerShape(3.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
    }
}

@Composable
internal fun TinyToggle(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Box(
        modifier = Modifier
            .size(18.dp)
            .background(
                if (checked) AccentCyan else SurfaceLow,
                RoundedCornerShape(3.dp),
            )
            .border(
                1.dp,
                if (checked) AccentCyan else SurfaceBorder,
                RoundedCornerShape(3.dp),
            )
            .clickable { onCheckedChange(!checked) },
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Text("✓", style = MaterialTheme.typography.labelSmall, color = OnAccent)
        }
    }
}

/** Slider balance -1..1 dengan titik tengah. */
@Composable
private fun BalanceFader(
    value: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val latest by rememberUpdatedState(onChange)
    Canvas(
        modifier = modifier.pointerInput(Unit) {
            fun emit(position: Offset) {
                val normalized = ((position.x / size.width) * 2f - 1f).coerceIn(-1f, 1f)
                latest((normalized * 20f).roundToInt() / 20f)
            }
            awaitEachGesture {
                val down = awaitFirstDown()
                down.consume()
                emit(down.position)
                drag(down.id) { change ->
                    emit(change.position)
                    change.consume()
                }
            }
        },
    ) {
        val centerY = size.height / 2f
        val track = 3.dp.toPx()
        val centerX = size.width / 2f
        val knobX = centerX + value * (size.width / 2f - 5.dp.toPx())
        drawRoundRect(
            color = SurfaceLow,
            topLeft = Offset(2.dp.toPx(), centerY - track / 2f),
            size = Size(size.width - 4.dp.toPx(), track),
            cornerRadius = CornerRadius(track / 2f),
        )
        drawLine(
            color = TextTertiary,
            start = Offset(centerX, centerY - 5.dp.toPx()),
            end = Offset(centerX, centerY + 5.dp.toPx()),
            strokeWidth = 1.dp.toPx(),
        )
        drawLine(
            color = AccentCyan,
            start = Offset(minOf(centerX, knobX), centerY),
            end = Offset(maxOf(centerX, knobX), centerY),
            strokeWidth = track,
        )
        drawRoundRect(
            color = TextPrimary,
            topLeft = Offset(knobX - 4.dp.toPx(), centerY - 9.dp.toPx()),
            size = Size(8.dp.toPx(), 18.dp.toPx()),
            cornerRadius = CornerRadius(3.dp.toPx()),
        )
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
                        text = "Filter — ${channel.name}",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onCopy, enabled = channel.filters.isNotEmpty()) {
                        Text("Salin")
                    }
                    TextButton(onClick = onPaste, enabled = canPaste) { Text("Tempel") }
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
                                    "Belum ada filter.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextTertiary,
                                    modifier = Modifier.padding(8.dp),
                                )
                            }
                        }
                        Box {
                            TextButton(onClick = { addOpen = true }) { Text("+ Tambah filter") }
                            DropdownMenu(expanded = addOpen, onDismissRequest = { addOpen = false }) {
                                AudioFilterType.entries.forEach { type ->
                                    DropdownMenuItem(
                                        text = { Text(type.label) },
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
                                "Pilih filter untuk mengatur parameternya.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextTertiary,
                            )
                        } else {
                            Text(selected.type.label, style = MaterialTheme.typography.titleSmall)
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
                    TextButton(onClick = onDismiss) { Text("Tutup") }
                }
            }
        }
    }
}

@Composable
private fun FilterListRow(
    filter: AudioFilter,
    selected: Boolean,
    onSelect: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) AccentCyan.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(onClick = onSelect)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TinyToggle(checked = filter.enabled, onCheckedChange = onToggle)
        Spacer(Modifier.width(6.dp))
        Text(
            text = filter.type.label,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        TinyButton("↑", onUp)
        TinyButton("↓", onDown)
        TinyButton("✕", onDelete)
    }
}

private fun moveFilter(
    channel: AudioChannel,
    filterId: String,
    delta: Int,
    onChannelChange: (AudioChannel) -> Unit,
) {
    val index = channel.filters.indexOfFirst { it.id == filterId }
    if (index < 0) return
    val target = (index + delta).coerceIn(0, channel.filters.lastIndex)
    if (target == index) return
    val list = channel.filters.toMutableList()
    val item = list.removeAt(index)
    list.add(target, item)
    onChannelChange(channel.copy(filters = list))
}

@Composable
private fun FilterParam(
    filter: AudioFilter,
    paramKey: String,
    channel: AudioChannel,
    onChannelChange: (AudioChannel) -> Unit,
) {
    val current = filter.param(paramKey)
    val range = paramRange(paramKey)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = paramLabel(paramKey),
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = paramValue(paramKey, current),
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = TextPrimary,
        )
    }
    Slider(
        value = current.coerceIn(range.start, range.endInclusive),
        onValueChange = { value ->
            val next = if (paramKey.endsWith("Db")) {
                (value * 10f).roundToInt() / 10f
            } else {
                value.roundToInt().toFloat()
            }
            onChannelChange(
                channel.copy(
                    filters = channel.filters.map {
                        if (it.id == filter.id) {
                            it.copy(params = it.params + (paramKey to next))
                        } else {
                            it
                        }
                    },
                ),
            )
        },
        valueRange = range,
    )
}

private fun paramRange(key: String): ClosedFloatingPointRange<Float> = when {
    key.endsWith("ThresholdDb") -> -60f..0f
    key == "gainDb" -> -30f..30f
    key == "attackMs" -> 1f..100f
    key == "holdMs" -> 0f..500f
    key == "releaseMs" -> 10f..1000f
    key == "ratio" -> 1f..20f
    key == "outputGainDb" -> 0f..30f
    key == "amount" -> 0f..100f
    key.endsWith("Db") -> -20f..20f
    else -> 0f..100f
}

private fun paramLabel(key: String): String = when (key) {
    "gainDb" -> "Gain"
    "openThresholdDb" -> "Open Threshold"
    "closeThresholdDb" -> "Close Threshold"
    "attackMs" -> "Attack"
    "holdMs" -> "Hold"
    "releaseMs" -> "Release"
    "thresholdDb" -> "Threshold"
    "ratio" -> "Ratio"
    "outputGainDb" -> "Output Gain"
    "amount" -> "Jumlah"
    "lowDb" -> "Low"
    "midDb" -> "Mid"
    "highDb" -> "High"
    else -> key
}

private fun paramValue(key: String, value: Float): String = when {
    key.endsWith("Db") -> FaderScale.formatDb(value)
    key.endsWith("Ms") -> "${value.roundToInt()} ms"
    key == "ratio" -> "${(value * 10).roundToInt() / 10f}:1"
    key == "amount" -> "${value.roundToInt()}%"
    else -> value.toString()
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
                Text("Properti — ${channel.name}", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nama") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (channel.capture == com.rout.air.core.AudioCaptureKind.Microphone) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Perangkat input", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = channel.deviceName ?: "Default",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextTertiary,
                            )
                        }
                        TextButton(onClick = onOpenDevicePicker) { Text("Ganti…") }
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
                        channel.appUid != 0 -> {
                            "Application Audio Capture: ${channel.appPackage ?: "aplikasi"}."
                        }

                        channel.capture == com.rout.air.core.AudioCaptureKind.Playback -> {
                            "Sumber: AudioPlaybackCapture. Hanya aplikasi yang mengizinkan " +
                                "tangkapan audio yang akan terdengar."
                        }

                        else -> {
                            "Sumber: AudioRecord (UNPROCESSED, fallback MIC). " +
                                "Izin mikrofon diberikan saat sesi dimulai."
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onOpenFilters) { Text("Filter…") }
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = {
                            onChannelChange(channel.copy(name = name.trim().ifEmpty { channel.name }))
                            onDismiss()
                        },
                    ) { Text("Simpan") }
                    TextButton(onClick = onDismiss) { Text("Tutup") }
                }
            }
        }
    }
}

@Composable
private fun PropertyToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
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
        title = { Text("Ganti Nama Sumber Audio") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Nama") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val trimmed = name.trim()
                    if (trimmed.isNotEmpty()) onRename(trimmed)
                },
            ) { Text("Ganti nama") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Batal") }
        },
    )
}
