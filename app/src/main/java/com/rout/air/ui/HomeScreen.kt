package com.rout.air.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.rout.air.core.CaptureConfig
import com.rout.air.core.RecordingState
import com.rout.air.output.CaptureEngine

/**
 * Halaman Siaran: pratinjau hasil komposisi + kontrol rekam.
 */
@Composable
fun HomeScreen(
    state: RecordingState,
    canvasConfig: CaptureConfig,
    fps: Int,
    elapsedMs: Long,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onEndSession: () -> Unit,
    onClearError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var previewReady by remember { mutableStateOf(false) }
    val isActive = state is RecordingState.Recording
    val sessionOnly = state is RecordingState.Previewing

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Siaran", style = MaterialTheme.typography.headlineSmall)
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
            // Mengakhiri sesi tangkap layar secara sengaja. Setelah ini izin
            // harus diminta ulang karena token MediaProjection sekali pakai.
            if (sessionOnly) {
                TextButton(onClick = onEndSession) { Text("Akhiri sesi") }
            }
        }

        Spacer(Modifier.height(12.dp))

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            val canvasAspect = canvasConfig.aspectRatio
            var frameWidth = maxWidth
            var frameHeight = frameWidth / canvasAspect
            if (frameHeight > maxHeight) {
                frameHeight = maxHeight
                frameWidth = frameHeight * canvasAspect
            }

            Box(
                modifier = Modifier
                    .size(width = frameWidth, height = frameHeight)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                AndroidView(
                    factory = { ctx ->
                        SurfaceView(ctx).apply {
                            holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) {
                                    previewReady = true
                                    attachPreview(holder)
                                }

                                override fun surfaceChanged(
                                    holder: SurfaceHolder,
                                    format: Int,
                                    width: Int,
                                    height: Int,
                                ) {
                                    previewReady = true
                                    attachPreview(holder)
                                }

                                override fun surfaceDestroyed(holder: SurfaceHolder) {
                                    previewReady = false
                                    // Lepas hanya surface ini, bukan surface lain
                                    // yang mungkin sudah terpasang dari tab lain.
                                    CaptureEngine.attachProgram(holder.surface, 0, 0)
                                }

                                private fun attachPreview(holder: SurfaceHolder) {
                                    val frame = holder.surfaceFrame
                                    // Halaman Siaran menampilkan keluaran Program,
                                    // bukan hasil edit pratinjau.
                                    CaptureEngine.attachProgram(
                                        holder.surface,
                                        frame.width(),
                                        frame.height(),
                                    )
                                }
                            })
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )

                if (!previewReady) {
                    Text(
                        text = if (isActive) "Program disiapkan" else "Program",
                        color = Color.White,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    text = formatDuration(elapsedMs),
                    style = MaterialTheme.typography.titleMedium,
                )
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

private fun statusText(state: RecordingState): String = when (state) {
    RecordingState.Idle -> "Siap merekam layar"
    RecordingState.Previewing -> "Pratinjau aktif • tekan rekam"
    RecordingState.Preparing -> "Menyiapkan tangkap layar…"
    is RecordingState.Recording -> if (state.audioActive) {
        "Merekam layar + audio internal"
    } else {
        "Merekam tanpa audio (audio internal tidak tersedia)"
    }

    is RecordingState.Error -> state.message
}
