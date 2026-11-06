package com.rout.air.ui.components.audio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rout.air.ui.theme.AccentCyan
import com.rout.air.ui.theme.TextSecondary
import com.rout.air.ui.theme.TextTertiary

/** Tombol ikon datar ala OBS. */
@Composable
internal fun MixerIconButton(
    onClick: () -> Unit,
    description: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    activeColor: Color = AccentCyan,
    activeBackground: Color = Color.Transparent,
    enabled: Boolean = true,
    content: @Composable (Color) -> Unit,
) {
    Box(
        modifier = modifier
            .size(width = 30.dp, height = 30.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (active) activeBackground else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        content(
            when {
                !enabled -> TextTertiary
                active -> activeColor
                else -> TextSecondary
            },
        )
    }
}

// --- Ikon vektor kecil -----------------------------------------------------

@Composable
internal fun SpeakerIcon(muted: Boolean, color: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val u = this.size.minDimension / 24f
        val body = Path().apply {
            moveTo(3.5f * u, 9.5f * u)
            lineTo(7.5f * u, 9.5f * u)
            lineTo(12f * u, 5.5f * u)
            lineTo(12f * u, 18.5f * u)
            lineTo(7.5f * u, 14.5f * u)
            lineTo(3.5f * u, 14.5f * u)
            close()
        }
        drawPath(body, color)
        val stroke = 1.8f * u
        if (muted) {
            drawLine(
                color,
                Offset(15f * u, 9f * u),
                Offset(20.5f * u, 15f * u),
                stroke,
                StrokeCap.Round,
            )
            drawLine(
                color,
                Offset(20.5f * u, 9f * u),
                Offset(15f * u, 15f * u),
                stroke,
                StrokeCap.Round,
            )
        } else {
            drawArc(
                color,
                -50f,
                100f,
                false,
                Offset(11f * u, 8f * u),
                Size(7f * u, 8f * u),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            drawArc(
                color,
                -50f,
                100f,
                false,
                Offset(12f * u, 5.5f * u),
                Size(10f * u, 13f * u),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
    }
}

@Composable
internal fun HeadphoneIcon(active: Boolean, color: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val u = this.size.minDimension / 24f
        val stroke = 1.9f * u
        drawArc(
            color,
            180f,
            180f,
            false,
            Offset(4f * u, 5f * u),
            Size(16f * u, 16f * u),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        drawRoundRect(
            color = if (active) color else Color.Transparent,
            topLeft = Offset(4f * u, 12.5f * u),
            size = Size(4.2f * u, 7f * u),
            cornerRadius = CornerRadius(1.8f * u),
        )
        drawRoundRect(
            color = if (active) color else Color.Transparent,
            topLeft = Offset(15.8f * u, 12.5f * u),
            size = Size(4.2f * u, 7f * u),
            cornerRadius = CornerRadius(1.8f * u),
        )
        drawRoundRect(
            color,
            Offset(4f * u, 12.5f * u),
            Size(4.2f * u, 7f * u),
            CornerRadius(1.8f * u),
            style = Stroke(width = 1.6f * u),
        )
        drawRoundRect(
            color,
            Offset(15.8f * u, 12.5f * u),
            Size(4.2f * u, 7f * u),
            CornerRadius(1.8f * u),
            style = Stroke(width = 1.6f * u),
        )
    }
}

/** Ikon power: menandai kanal aktif/nonaktif. */
@Composable
internal fun PowerIcon(color: Color, size: Dp = 18.dp) {
    Canvas(Modifier.size(size)) {
        val u = this.size.minDimension / 24f
        val stroke = 2.2f * u
        // Busur hampir penuh dengan celah di atas.
        drawArc(
            color = color,
            startAngle = 300f,
            sweepAngle = 300f,
            useCenter = false,
            topLeft = Offset(4f * u, 4f * u),
            size = Size(16f * u, 16f * u),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        // Garis vertikal di tengah atas.
        drawLine(
            color = color,
            start = Offset(12f * u, 2.8f * u),
            end = Offset(12f * u, 11.5f * u),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
internal fun LockIcon(locked: Boolean, color: Color, modifier: Modifier = Modifier) {    Canvas(modifier) {
        val u = this.size.minDimension / 24f
        val stroke = 2f * u
        if (locked) {
            drawArc(
                color,
                180f,
                180f,
                false,
                Offset(7f * u, 4.5f * u),
                Size(10f * u, 10f * u),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        } else {
            drawArc(
                color,
                200f,
                160f,
                false,
                Offset(9f * u, 3.5f * u),
                Size(10f * u, 10f * u),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        drawRoundRect(
            color,
            Offset(4.5f * u, 10.5f * u),
            Size(15f * u, 9.5f * u),
            CornerRadius(2.6f * u),
        )
    }
}

@Composable
internal fun GearIcon(color: Color, size: Dp = 18.dp) {
    Canvas(Modifier.size(size)) {
        val u = this.size.minDimension / 24f
        val stroke = 1.8f * u
        drawCircle(color, 4.2f * u, Offset(12f * u, 12f * u), style = Stroke(width = stroke))
        for (i in 0 until 8) {
            val angle = i * 45.0 * Math.PI / 180.0
            val start = Offset(
                12f * u + (6.2f * u * kotlin.math.cos(angle)).toFloat(),
                12f * u + (6.2f * u * kotlin.math.sin(angle)).toFloat(),
            )
            val end = Offset(
                12f * u + (9f * u * kotlin.math.cos(angle)).toFloat(),
                12f * u + (9f * u * kotlin.math.sin(angle)).toFloat(),
            )
            drawLine(color, start, end, stroke, StrokeCap.Round)
        }
    }
}

@Composable
internal fun LayoutIcon(vertical: Boolean, color: Color, size: Dp = 18.dp) {
    Canvas(Modifier.size(size)) {
        val u = this.size.minDimension / 24f
        val stroke = 1.7f * u
        drawRoundRect(
            color,
            Offset(3f * u, 4f * u),
            Size(18f * u, 16f * u),
            CornerRadius(2.5f * u),
            style = Stroke(width = stroke),
        )
        if (vertical) {
            drawLine(color, Offset(9f * u, 4f * u), Offset(9f * u, 20f * u), stroke)
            drawLine(color, Offset(15f * u, 4f * u), Offset(15f * u, 20f * u), stroke)
        } else {
            drawLine(color, Offset(3f * u, 9.3f * u), Offset(21f * u, 9.3f * u), stroke)
            drawLine(color, Offset(3f * u, 14.7f * u), Offset(21f * u, 14.7f * u), stroke)
        }
    }
}

/** Tombol tahan untuk push-to-talk / push-to-mute. */
@Composable
internal fun HoldButton(
    label: String,
    activeColor: Color,
    onHoldChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var held by remember { mutableStateOf(false) }
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = if (held) Color.White else TextSecondary,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (held) activeColor else Color.Transparent)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    held = true
                    onHoldChange(true)
                    waitForUpOrCancellation()
                    held = false
                    onHoldChange(false)
                }
            }
            .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}
