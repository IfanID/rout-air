package com.rout.air.ui

import android.content.res.Configuration
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.rout.air.core.CaptureConfig
import com.rout.air.core.RecordingState
import com.rout.air.output.CaptureEngine

/**
 * Halaman Studio Mode: dua panel terpisah.
 *
 * - Pratinjau (mentah): komposisi yang sedang disunting.
 * - Program (siaran): keluaran yang benar-benar masuk file rekaman.
 *
 * Keduanya digambar dari pipeline yang sama; selama baru ada satu adegan,
 * isinya identik. Pemisahan bermakna saat transisi adegan tersedia.
 */
@Composable
fun StudioScreen(
    state: RecordingState,
    canvasConfig: CaptureConfig,
    fps: Int,
    elapsedMs: Long,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onClearError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isActive = state is RecordingState.Recording || state is RecordingState.Preparing
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val canvasAspect = canvasConfig.aspectRatio

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Studio Mode", style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = statusText(state),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state is RecordingState.Error) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (state is RecordingState.Error) {
                TextButton(onClick = onClearError) { Text("Tutup") }
            }
        }

        Spacer(Modifier.height(10.dp))

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            if (landscape) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PreviewPane(
                        label = "Pratinjau (mentah)",
                        canvasAspect = canvasAspect,
                        modifier = Modifier.weight(1f).fillMaxSize(),
                        attach = { surface, width, height ->
                            CaptureEngine.attachPreview(surface, width, height)
                        },
                    )
                    PreviewPane(
                        label = "Program (siaran)",
                        canvasAspect = canvasAspect,
                        live = isActive,
                        modifier = Modifier.weight(1f).fillMaxSize(),
                        attach = { surface, width, height ->
                            CaptureEngine.attachProgram(surface, width, height)
                        },
                    )
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PreviewPane(
                        label = "Pratinjau (mentah)",
                        canvasAspect = canvasAspect,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        attach = { surface, width, height ->
                            CaptureEngine.attachPreview(surface, width, height)
                        },
                    )
                    PreviewPane(
                        label = "Program (siaran)",
                        canvasAspect = canvasAspect,
                        live = isActive,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        attach = { surface, width, height ->
                            CaptureEngine.attachProgram(surface, width, height)
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(formatDuration(elapsedMs), style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "${canvasConfig.width}x${canvasConfig.height} • $fps fps • " +
                        "${orientationLabel(canvasConfig)} • ${contentModeLabel(canvasConfig)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            RecordButton(
                isActive = isActive,
                enabled = state !is RecordingState.Preparing,
                onClick = { if (isActive) onStop() else onStart() },
            )
        }
    }
}

/**
 * Satu panel pratinjau dengan SurfaceView sendiri.
 *
 * Ukuran kotak panel mengikuti rasio kanvas (mis. 2400x1080 = 2,22:1), bukan
 * memenuhi ruang yang tersedia, supaya bentuk panel sama dengan bentuk video.
 */
@Composable
private fun PreviewPane(
    label: String,
    canvasAspect: Float,
    modifier: Modifier = Modifier,
    live: Boolean = false,
    attach: (Surface?, Int, Int) -> Unit,
) {
    val holderCallback = remember(attach) {
        object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = attachHolder(holder, attach)

            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int,
            ) = attachHolder(holder, attach)

            override fun surfaceDestroyed(holder: SurfaceHolder) = attach(null, 0, 0)
        }
    }

    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        var frameWidth = maxWidth
        var frameHeight = frameWidth / canvasAspect
        if (frameHeight > maxHeight) {
            frameHeight = maxHeight
            frameWidth = frameHeight * canvasAspect
        }

        Box(
            modifier = Modifier
                .size(width = frameWidth, height = frameHeight)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.Black),
        ) {
            AndroidView(
                factory = { ctx ->
                    SurfaceView(ctx).apply { holder.addCallback(holderCallback) }
                },
                modifier = Modifier.fillMaxSize(),
            )

            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xCC000000))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (live) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFE53935)),
                    )
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFF2F2F2),
                    textAlign = TextAlign.Start,
                )
            }
        }
    }
}

private fun attachHolder(holder: SurfaceHolder, attach: (Surface?, Int, Int) -> Unit) {
    val frame = holder.surfaceFrame
    if (frame.width() <= 0 || frame.height() <= 0) {
        attach(null, 0, 0)
        return
    }
    attach(holder.surface, frame.width(), frame.height())
}

private fun statusText(state: RecordingState): String = when (state) {
    RecordingState.Idle -> "Pratinjau dan program siap"
    RecordingState.Preparing -> "Menyiapkan tangkap layar…"
    is RecordingState.Recording -> if (state.audioActive) {
        "Merekam • program = file, audio internal aktif"
    } else {
        "Merekam • program = file, tanpa audio internal"
    }

    is RecordingState.Error -> state.message
}
