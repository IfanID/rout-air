package com.rout.air.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Tombol Transisi ala OBS Studio Mode.
 *
 * Menonjol saat ada perubahan pratinjau yang belum masuk ke Program, dan
 * meredup saat tidak ada yang perlu diterapkan.
 */
@Composable
internal fun TransitionButton(
    pending: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        enabled = pending,
        shape = RoundedCornerShape(16.dp),
        color = if (pending) {
            scheme.primary.copy(alpha = 0.14f)
        } else {
            scheme.surfaceVariant.copy(alpha = 0.45f)
        },
        contentColor = if (pending) scheme.primary else scheme.onSurfaceVariant.copy(alpha = 0.7f),
        border = BorderStroke(
            width = 1.5.dp,
            color = if (pending) scheme.primary else scheme.onSurfaceVariant.copy(alpha = 0.25f),
        ),
        modifier = modifier.height(52.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Transisi",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (pending) {
                // Titik penanda: masih ada perubahan yang belum mengudara.
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(scheme.primary),
                )
            }
        }
    }
}

/**
 * Tombol rekam berbentuk pil, sejajar tinggi dengan tombol Transisi.
 *
 * Warna mengikuti status: siap (cyan), merekam (merah), belum siap (abu).
 * Saat merekam, indikator berdenyut halus sebagai penanda sesi berjalan.
 */
@Composable
internal fun RecordButton(
    isActive: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme

    // Denyut hanya saat merekam supaya jelas bahwa sesi sedang berjalan.
    val pulse = rememberInfiniteTransition(label = "denyut-rekam").animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "indikator",
    )
    val indicatorAlpha = if (isActive) pulse.value else 1f

    val container = when {
        !enabled -> scheme.surfaceVariant
        isActive -> Color(0xFFE53935)
        else -> scheme.primary
    }
    val content = when {
        !enabled -> scheme.onSurfaceVariant.copy(alpha = 0.6f)
        isActive -> Color.White
        else -> scheme.onPrimary
    }

    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        color = container,
        contentColor = content,
        modifier = Modifier
            .height(52.dp)
            .semantics {
                contentDescription = if (isActive) "Berhenti merekam" else "Mulai merekam"
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Ikon status: lingkaran saat siap, kotak saat merekam.
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(if (isActive) RoundedCornerShape(3.dp) else CircleShape)
                    .background(content.copy(alpha = indicatorAlpha)),
            )
            Text(
                text = if (isActive) "Berhenti" else "Rekam",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
