package com.rout.air.ui.components.audio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rout.air.core.audio.FaderScale
import com.rout.air.ui.theme.AccentCyan
import com.rout.air.ui.theme.TextPrimary
import com.rout.air.ui.theme.TextTertiary
import androidx.compose.ui.res.stringResource
import com.rout.air.R

/** Label volume OBS: teks polos, bukan kotak. */
@Composable
internal fun VolumeReadout(gainDb: Float, usePercent: Boolean, modifier: Modifier = Modifier) {
    Text(
        text = if (usePercent) {
            "${percentOfDb(gainDb)}%"
        } else {
            FaderScale.formatDb(gainDb)
        },
        style = MaterialTheme.typography.labelMedium.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
        ),
        color = TextPrimary,
        maxLines = 1,
        textAlign = TextAlign.End,
        modifier = modifier.width(72.dp),
    )
}

/**
 * Fader log OBS: groove tipis, isi cyan, handle putih, tick tiap 10 dB.
 */
@Composable
internal fun VolumeFader(
    gainDb: Float,
    enabled: Boolean,
    vertical: Boolean,
    onGainChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val latest by rememberUpdatedState(onGainChange)
    val volumeDesc = stringResource(R.string.audio_volume_desc, FaderScale.formatDb(gainDb))
    val deflection = FaderScale.dbToDeflection(gainDb)
    val knobColor = if (enabled) TextPrimary else TextTertiary
    val fillColor = if (enabled) AccentCyan else TextTertiary

    Canvas(
        modifier = modifier
            .then(if (vertical) Modifier.width(34.dp) else Modifier.height(34.dp))
            .pointerInput(enabled, vertical) {
                if (!enabled) return@pointerInput
                fun emit(position: Offset) {
                    val raw = if (vertical) {
                        1f - position.y / size.height.toFloat()
                    } else {
                        position.x / size.width.toFloat()
                    }
                    latest(FaderScale.snapDb(FaderScale.deflectionToDb(raw)))
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
            }
            .semantics { contentDescription = volumeDesc },
    ) {
        val pad = 7.dp.toPx()
        val groove = 4.dp.toPx()
        val knobShort = 10.dp.toPx()
        val knobLong = 20.dp.toPx()
        val tickColor = Color(0xFF5B6273)
        val grooveColor = Color(0xFF1D1F26)

        if (vertical) {
            val centerX = size.width / 2f
            val top = pad
            val bottom = size.height - pad
            val length = bottom - top
            val knobY = top + (1f - deflection) * length

            drawRoundRect(
                color = grooveColor,
                topLeft = Offset(centerX - groove / 2f, top),
                size = Size(groove, length),
                cornerRadius = CornerRadius(groove / 2f),
            )
            drawRoundRect(
                color = fillColor,
                topLeft = Offset(centerX - groove / 2f, knobY),
                size = Size(groove, bottom - knobY),
                cornerRadius = CornerRadius(groove / 2f),
            )
            for (db in -10 downTo -96 step 10) {
                val y = top + (1f - FaderScale.dbToDeflection(db.toFloat())) * length
                drawLine(
                    color = tickColor,
                    start = Offset(centerX - 5.dp.toPx(), y),
                    end = Offset(centerX + 5.dp.toPx(), y),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            drawRoundRect(
                color = knobColor,
                topLeft = Offset(centerX - knobLong / 2f, knobY - knobShort / 2f),
                size = Size(knobLong, knobShort),
                cornerRadius = CornerRadius(4.dp.toPx()),
            )
        } else {
            val centerY = size.height / 2f
            val left = pad
            val right = size.width - pad
            val length = right - left
            val knobX = left + deflection * length

            drawRoundRect(
                color = grooveColor,
                topLeft = Offset(left, centerY - groove / 2f),
                size = Size(length, groove),
                cornerRadius = CornerRadius(groove / 2f),
            )
            drawRoundRect(
                color = fillColor,
                topLeft = Offset(left, centerY - groove / 2f),
                size = Size(knobX - left, groove),
                cornerRadius = CornerRadius(groove / 2f),
            )
            for (db in -10 downTo -90 step 10) {
                val x = left + FaderScale.dbToDeflection(db.toFloat()) * length
                drawLine(
                    color = tickColor,
                    start = Offset(x, centerY - 6.dp.toPx()),
                    end = Offset(x, centerY + 6.dp.toPx()),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            drawRoundRect(
                color = knobColor,
                topLeft = Offset(knobX - knobShort / 2f, centerY - knobLong / 2f),
                size = Size(knobShort, knobLong),
                cornerRadius = CornerRadius(4.dp.toPx()),
            )
        }
    }
}
