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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.rout.air.R
import com.rout.air.core.CaptureConfig
import com.rout.air.core.RecordingState
import com.rout.air.core.RecordingStats
import com.rout.air.output.CaptureEngine
import androidx.compose.ui.res.stringResource

/**
 * Halaman Siaran: pratinjau hasil komposisi + kontrol rekam.
 */
@Composable
fun HomeScreen(
    state: RecordingState,
    stats: RecordingStats,
    canvasConfig: CaptureConfig,
    fps: Int,
    elapsedMs: Long,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onEndSession: () -> Unit,
    onRequestPermission: () -> Unit,
    onClearError: () -> Unit,
    recordingOverlayEnabled: Boolean,
    liveOverlayEnabled: Boolean,
    onToggleRecordingOverlay: () -> Unit,
    onToggleLiveOverlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var previewReady by remember { mutableStateOf(false) }
    val isActive = state is RecordingState.Recording
    val paused = (state as? RecordingState.Recording)?.isPaused == true
    val sessionOnly = state is RecordingState.Previewing

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.home_siaran), style = MaterialTheme.typography.headlineSmall)
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
                TextButton(onClick = onClearError) { Text(stringResource(R.string.common_tutup)) }
            }
            // Cadangan izin rekam layar: tampil hanya saat sesi belum aktif.
            if (state is RecordingState.Idle || state is RecordingState.Error) {
                IconButton(onClick = onRequestPermission) {
                    Icon(
                        painter = painterResource(R.drawable.ic_screen_permission),
                        contentDescription = stringResource(R.string.home_izin_rekam_layar),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // Mengakhiri sesi sekaligus melepas izin MediaProjection. Setelah
            // ini izin harus diminta ulang saat sesi dibuka lagi.
            if (sessionOnly) {
                IconButton(onClick = onEndSession) {
                    Icon(
                        painter = painterResource(R.drawable.ic_end_session),
                        contentDescription = stringResource(R.string.home_akhiri_sesi),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
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
                if (isActive) {
                    Text(
                        text = stringResource(
                            R.string.home_statistik,
                            formatRecordSize(stats.bytes),
                            String.format("%.1f", stats.bitrateKbps / 1000.0),
                            stats.frames,
                            stats.droppedFrames,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isActive) {
                    IconButton(
                        onClick = if (paused) onResume else onPause,
                        modifier = Modifier.size(44.dp),
                    ) {
                        Icon(
                            painter = painterResource(
                                if (paused) R.drawable.ic_play else R.drawable.ic_pause,
                            ),
                            contentDescription = stringResource(
                                if (paused) R.string.home_lanjutkan_rekaman
                                else R.string.home_jeda_rekaman,
                            ),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                }
                // Tombol rekam cadangan (tanpa perlu tombol melayang).
                RecordButton(
                    isActive = isActive,
                    enabled = state !is RecordingState.Preparing,
                    preparing = state is RecordingState.Preparing,
                    paused = paused,
                    onClick = { if (isActive) onStop() else onStart() },
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // Aksi live dan overlay dipisah dari tombol rekam supaya jelas.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LiveButton(modifier = Modifier.weight(0.8f))
            OverlayToggleButton(
                label = if (recordingOverlayEnabled) {
                    stringResource(R.string.home_overlay_record_hide)
                } else {
                    stringResource(R.string.home_overlay_record)
                },
                active = recordingOverlayEnabled,
                modifier = Modifier.weight(1.1f),
                onClick = onToggleRecordingOverlay,
            )
            OverlayToggleButton(
                label = if (liveOverlayEnabled) {
                    stringResource(R.string.home_overlay_live_hide)
                } else {
                    stringResource(R.string.home_overlay_live)
                },
                active = liveOverlayEnabled,
                modifier = Modifier.weight(1.1f),
                onClick = onToggleLiveOverlay,
            )
        }
    }
}

@Composable
private fun statusText(state: RecordingState): String = when (state) {
    RecordingState.Idle -> stringResource(R.string.home_siap_merekam_layar)
    RecordingState.Previewing -> stringResource(R.string.home_pratinjau_aktif_tekan_rekam)
    RecordingState.Preparing -> stringResource(R.string.home_menyiapkan_tangkap_layar)
    is RecordingState.Recording -> when {
        state.isPaused -> stringResource(R.string.home_rekaman_dijeda)
        state.audioActive -> stringResource(R.string.home_merekam_layar_mixer_audio)
        else -> stringResource(R.string.home_merekam_tanpa_audio)
    }

    is RecordingState.Error -> state.message
}

/** Tombol overlay (rekam/live): aktif = tampil, nonaktif = disembunyikan. */
@Composable
private fun OverlayToggleButton(
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (active) scheme.primary else scheme.surfaceVariant,
        contentColor = if (active) scheme.onPrimary else scheme.onSurfaceVariant,
        modifier = modifier.height(44.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_logo_mark),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Tombol live: disiapkan untuk M5, belum bisa dipakai. */
@Composable
private fun LiveButton(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = scheme.surfaceVariant,
        contentColor = scheme.onSurfaceVariant.copy(alpha = 0.6f),
        modifier = modifier.height(44.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_signal),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = stringResource(R.string.home_live_menyusul),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
