package com.rout.air.ui.pages.studio

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import com.rout.air.core.model.CaptureConfig
import com.rout.air.core.model.LiveStats
import com.rout.air.core.model.RecordingState
import com.rout.air.core.model.RecordingStats
import com.rout.air.core.model.SceneCollection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.rout.air.R
import com.rout.air.ui.components.LiveButton
import com.rout.air.ui.components.RecordButton
import com.rout.air.ui.components.TransitionButton
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import com.rout.air.ui.util.contentModeLabel
import com.rout.air.ui.util.formatDuration
import com.rout.air.ui.util.formatRecordSize
import com.rout.air.ui.util.orientationLabel
import com.rout.air.ui.pages.studio.components.PreviewFullscreenButton
import com.rout.air.ui.pages.studio.components.ProgramPane
import com.rout.air.ui.pages.studio.components.RawPreview

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
    stats: RecordingStats,
    liveStats: LiveStats,
    liveConfigured: Boolean,
    canvasConfig: CaptureConfig,
    fps: Int,
    elapsedMs: Long,
    collection: SceneCollection,
    programCollection: SceneCollection,
    selectedItemId: String?,
    onSelectItem: (String?) -> Unit,
    onTransform: (String?, Float, Float, Float, Float, Float) -> Unit,
    onCollectionChange: (SceneCollection) -> Unit,
    onCommitProgram: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onToggleLive: () -> Unit,
    onClearError: () -> Unit,
    onRequestPermission: () -> Unit,
    fullscreen: Boolean,
    onFullscreenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isActive = state is RecordingState.Recording
    val paused = (state as? RecordingState.Recording)?.isPaused == true
    val sessionLive = state is RecordingState.Recording || state is RecordingState.Previewing
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    // Tombol kembali menutup mode pratinjau penuh, tanpa keluar aplikasi.
    if (fullscreen) {
        BackHandler { onFullscreenChange(false) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.studio_studio_mode), style = MaterialTheme.typography.headlineSmall)
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
            // Cadangan izin tangkap layar: satu-satunya jalan manual, sama
            // seperti ikon di pojok kanan atas halaman Siaran.
            if (state is RecordingState.Idle || state is RecordingState.Error) {
                IconButton(onClick = onRequestPermission) {
                    Icon(
                        painter = painterResource(R.drawable.ic_screen_permission),
                        contentDescription = stringResource(R.string.home_izin_rekam_layar),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // Satu tombol di header: ikon full screen saat normal, ikon ✕
            // saat pratinjau penuh. Posisinya sama supaya konsisten.
            PreviewFullscreenButton(
                fullscreen = fullscreen,
                onClick = { onFullscreenChange(!fullscreen) },
            )
        }

        Spacer(Modifier.height(10.dp))

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            if (fullscreen) {
                // Pratinjau penuh: panel Program disembunyikan supaya area
                // edit jadi luas, tetapi header dan tombol bawah tetap ada.
                Box(modifier = Modifier.fillMaxSize()) {
                    RawPreview(
                        isActive = sessionLive,
                        canvasConfig = canvasConfig,
                        collection = collection,
                        selectedItemId = selectedItemId,
                        onSelectItem = onSelectItem,
                        onTransform = onTransform,
                        onCollectionChange = onCollectionChange,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            } else if (landscape) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                        RawPreview(
                            isActive = sessionLive,
                            canvasConfig = canvasConfig,
                            collection = collection,
                            selectedItemId = selectedItemId,
                            onSelectItem = onSelectItem,
                            onTransform = onTransform,
                            onCollectionChange = onCollectionChange,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    ProgramPane(
                        isLive = sessionLive,
                        canvasConfig = canvasConfig,
                        program = programCollection,
                        modifier = Modifier.weight(1f).fillMaxSize(),
                    )
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        RawPreview(
                            isActive = sessionLive,
                            canvasConfig = canvasConfig,
                            collection = collection,
                            selectedItemId = selectedItemId,
                            onSelectItem = onSelectItem,
                            onTransform = onTransform,
                            onCollectionChange = onCollectionChange,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    ProgramPane(
                        isLive = sessionLive,
                        canvasConfig = canvasConfig,
                        program = programCollection,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        Column {
            Text(formatDuration(elapsedMs), style = MaterialTheme.typography.titleMedium)
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

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Tombol Transisi ala OBS: menerapkan hasil edit pratinjau ke
            // Program (dan ke rekaman). Sebelum ditekan, Program tetap.
            val pending = collection != programCollection
            TransitionButton(
                pending = pending,
                onClick = onCommitProgram,
            )
            RecordButton(
                isActive = isActive,
                enabled = state !is RecordingState.Preparing,
                preparing = state is RecordingState.Preparing,
                paused = paused,
                modifier = Modifier.weight(1f),
                onClick = { if (isActive) onStop() else onStart() },
            )
            LiveButton(
                stats = liveStats,
                configured = liveConfigured,
                modifier = Modifier.weight(1f),
                onClick = onToggleLive,
            )
            if (isActive && !liveStats.active) {
                IconButton(
                    onClick = if (paused) onResume else onPause,
                    modifier = Modifier.size(52.dp),
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
            }
        }
    }
}


@Composable
private fun statusText(state: RecordingState): String = when (state) {
    RecordingState.Idle -> stringResource(R.string.studio_pratinjau_dan_program_siap)
    RecordingState.Previewing -> stringResource(R.string.studio_pratinjau_aktif_tekan_rekam)
    RecordingState.Preparing -> stringResource(R.string.studio_menyiapkan_tangkap_layar)
    is RecordingState.Recording -> when {
        state.isPaused -> stringResource(R.string.home_rekaman_dijeda)
        state.audioActive -> stringResource(R.string.studio_merekam_program_file_mixer)
        else -> stringResource(R.string.studio_merekam_program_file_tanpa_audio)
    }

    is RecordingState.Error -> state.message
}
