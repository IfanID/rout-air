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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rout.air.R
import androidx.compose.ui.res.stringResource

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
                text = stringResource(R.string.studio_transisi),
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
    preparing: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme

    // Denyut hanya saat merekam supaya jelas bahwa sesi sedang berjalan.
    val pulse = rememberInfiniteTransition(label = stringResource(R.string.studio_denyut_rekam)).animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = stringResource(R.string.studio_indikator),
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
            // Ikon berubah sesuai kondisi: rekam, stop, atau memuat.
            when {
                preparing -> CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = content,
                )

                else -> Icon(
                    painter = painterResource(
                        if (isActive) R.drawable.ic_stop else R.drawable.ic_record,
                    ),
                    contentDescription = null,
                    tint = if (isActive) content.copy(alpha = indicatorAlpha) else content,
                    modifier = Modifier.size(16.dp),
                )
            }
            Text(
                text = when {
                    preparing -> "Menyiapkan…"
                    isActive -> "Berhenti"
                    else -> "Rekam"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
