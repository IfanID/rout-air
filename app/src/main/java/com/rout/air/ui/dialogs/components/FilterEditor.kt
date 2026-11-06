package com.rout.air.ui.dialogs.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import com.rout.air.core.audio.AudioChannel
import com.rout.air.core.audio.AudioFilter
import com.rout.air.core.audio.FaderScale
import com.rout.air.ui.theme.AccentCyan
import com.rout.air.ui.theme.TextPrimary
import com.rout.air.ui.theme.TextSecondary
import com.rout.air.ui.components.audio.filterLabel
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
internal fun FilterListRow(
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
            text = filterLabel(filter.type),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        TinyButton("↑", onUp)
        TinyButton("↓", onDown)
        TinyButton("✕", onDelete)
    }
}

internal fun moveFilter(
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
internal fun FilterParam(
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

internal fun paramRange(key: String): ClosedFloatingPointRange<Float> = when {
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

internal fun paramLabel(key: String): String = when (key) {
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

internal fun paramValue(key: String, value: Float): String = when {
    key.endsWith("Db") -> FaderScale.formatDb(value)
    key.endsWith("Ms") -> "${value.roundToInt()} ms"
    key == "ratio" -> "${(value * 10).roundToInt() / 10f}:1"
    key == "amount" -> "${value.roundToInt()}%"
    else -> value.toString()
}
