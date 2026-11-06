package com.rout.air.ui.pages.scenes.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.rout.air.R
import com.rout.air.core.model.Align
import androidx.compose.ui.res.stringResource
import com.rout.air.ui.components.ResetIcon
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

@Composable
internal fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: (Float) -> String,
    onReset: (() -> Unit)? = null,
    onChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(format(value), style = MaterialTheme.typography.bodySmall)
            if (onReset != null) {
                // Reset khusus properti ini, mis. hanya Scale X.
                Box(
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onReset),
                    contentAlignment = Alignment.Center,
                ) {
                    ResetIcon(
                        color = MaterialTheme.colorScheme.primary,
                        size = 18.dp,
                        description = stringResource(R.string.scenes_reset_properti, label),
                    )
                }
            }
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
        )
    }
}

@Composable
internal fun CropSlider(
    label: String,
    value: Float,
    onChange: (Float) -> Unit,
) {
    LabeledSlider(
        label = stringResource(R.string.scenes_crop_n, label),
        value = value,
        range = 0f..0.49f,
        format = { "%.0f%%".format(it * 100f) },
        onReset = { onChange(0f) },
        onChange = onChange,
    )
}

@Composable
internal fun AlignGrid(current: Align, onChange: (Align) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(stringResource(R.string.scenes_titik_jangkar), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(6.dp))
        val rows = listOf(
            listOf(Align.TopLeft, Align.Top, Align.TopRight),
            listOf(Align.Left, Align.Center, Align.Right),
            listOf(Align.BottomLeft, Align.Bottom, Align.BottomRight),
        )
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { align ->
                    Surface(
                        color = if (align == current) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier
                            .size(40.dp)
                            .clickable { onChange(align) },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = alignSymbol(align),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
internal fun ColorRow(title: String, selected: Long, onPick: (Long) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PRESET_COLORS.forEach { color ->
                val argb = color.toInt()
                val ring = if (selected == color) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                } else {
                    Modifier
                }
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(argb))
                        .then(ring)
                        .clickable { onPick(color) },
                )
            }
        }
    }
}
