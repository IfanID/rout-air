package com.rout.air.ui.dialogs.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.rout.air.core.audio.AudioChannel
import com.rout.air.core.audio.FaderScale
import com.rout.air.core.audio.MonitorMode
import com.rout.air.ui.theme.AccentCyan
import com.rout.air.ui.theme.OnAccent
import com.rout.air.ui.theme.SurfaceBorder
import com.rout.air.ui.theme.SurfaceLow
import com.rout.air.ui.theme.TextPrimary
import com.rout.air.ui.theme.TextSecondary
import com.rout.air.ui.theme.TextTertiary
import androidx.compose.ui.res.stringResource
import com.rout.air.R
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.pow
import kotlin.math.roundToInt

@Composable
internal fun AdvancedHeader() {
    Row(Modifier.height(28.dp), verticalAlignment = Alignment.CenterVertically) {
        HeaderCell("Sumber", 140.dp)
        HeaderCell("Status", 72.dp)
        HeaderCell("Volume", 124.dp)
        HeaderCell("Mono", 52.dp)
        HeaderCell("Panorama", 164.dp)
        HeaderCell("Sinkron", 104.dp)
        HeaderCell("Monitor", 64.dp)
        HeaderCell("Track", 164.dp)
    }
}

@Composable
internal fun HeaderCell(text: String, width: androidx.compose.ui.unit.Dp) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = TextSecondary,
        modifier = Modifier.width(width).padding(horizontal = 4.dp),
    )
}

@Composable
internal fun AdvancedRow(
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
            Text(stringResource(R.string.audio_l), style = MaterialTheme.typography.labelSmall, color = TextTertiary)
            BalanceFader(
                value = channel.balance,
                onChange = { onChannelChange(channel.copy(balance = it)) },
                modifier = Modifier.weight(1f).height(26.dp),
            )
            Text(stringResource(R.string.audio_r), style = MaterialTheme.typography.labelSmall, color = TextTertiary)
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
internal fun Stepper(
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
internal fun TinyButton(label: String, onClick: () -> Unit) {
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
            Text(stringResource(R.string.audio_teks), style = MaterialTheme.typography.labelSmall, color = OnAccent)
        }
    }
}

/** Slider balance -1..1 dengan titik tengah. */
@Composable
internal fun BalanceFader(
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
