package com.rout.air.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rout.air.core.AutoConfigPlan
import com.rout.air.core.CaptureConfig
import com.rout.air.core.LiveConfig
import com.rout.air.core.VideoCodec
import com.rout.air.encode.ConfigProbe
import com.rout.air.encode.EncoderProbe
import com.rout.air.live.UploadProbe
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Setup otomatis ala OBS: pilih tujuan, uji perangkat, lalu terapkan
 * resolusi, fps, codec, dan bitrate yang direkomendasikan.
 *
 * Hanya diuji maksimal tiga kandidat seperti OBS; setiap kandidat 4 detik.
 */
@Composable
fun AutoConfigWizardDialog(
    config: CaptureConfig,
    live: LiveConfig,
    onApply: (CaptureConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var target by remember { mutableStateOf(AutoConfigPlan.Target.Streaming) }
    var codec by remember { mutableStateOf(VideoCodec.AVC) }
    var preference by remember { mutableStateOf(AutoConfigPlan.FpsPreference.PreferHighFps) }
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var log by remember { mutableStateOf(emptyList<String>()) }
    var result by remember { mutableStateOf<AutoConfigPlan.Recommendation?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }

    fun start() {
        running = true
        log = emptyList()
        progress = 0f
        result = null
        failure = null
        scope.launch {
            try {
                val candidates = AutoConfigPlan.ladder(
                    Runtime.getRuntime().availableProcessors(),
                    preference,
                ).take(3)
                val probes = mutableListOf<AutoConfigPlan.Probe>()
                for ((index, candidate) in candidates.withIndex()) {
                    log = log + "Menguji ${candidate.width}x${candidate.height}@${candidate.fps}…"
                    val probe = ConfigProbe.run(candidate, codec, seconds = 4)
                    probes += probe
                    val status = if (probe.passes()) "lulus" else "kurang"
                    log = log.dropLast(1) + buildString {
                        append(candidate.width)
                        append('x')
                        append(candidate.height)
                        append('@')
                        append(candidate.fps)
                        append(": ")
                        append(String.format(Locale.US, "%.1f", probe.achievedFps))
                        append(" fps • ")
                        append(status)
                        if (probe.passes()) append(" ✓")
                    }
                    progress = (index + 1).toFloat() / (candidates.size + 1)
                    if (probe.passes()) break
                }

                val hardware = EncoderProbe.select(codec)?.hardware == true
                val tentative = AutoConfigPlan.recommend(
                    probes = probes,
                    target = target,
                    codec = codec,
                    hardwareEncoder = hardware,
                )

                var uploadKbps: Int? = null
                if (target == AutoConfigPlan.Target.Streaming) {
                    if (live.isConfigured) {
                        log = log + "Tes upload ${tentative.bitrateKbps / 1000} Mbps…"
                        val upload = UploadProbe.measure(live, tentative.bitrateKbps)
                        uploadKbps = upload.recommendedKbps.takeIf { it > 0 }
                        log = log.dropLast(1) + buildString {
                            append("Upload terukur: ")
                            append(upload.measuredKbps / 1000)
                            append(" Mbps")
                            upload.note?.let { append(" • ").append(it) }
                        }
                    } else {
                        log = log + "Tes upload dilewati: stream key belum diisi"
                    }
                }

                result = AutoConfigPlan.recommend(
                    probes = probes,
                    target = target,
                    codec = codec,
                    hardwareEncoder = hardware,
                    uploadKbps = uploadKbps,
                )
                progress = 1f
            } catch (error: Throwable) {
                failure = error.message ?: "Diagnosa gagal"
            } finally {
                running = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text("Setup otomatis") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    "Pilih tujuan, lalu RoutAir menguji kemampuan HP dan " +
                        "menyarankan resolusi, fps, dan bitrate.",
                    style = MaterialTheme.typography.bodySmall,
                )

                Spacer(Modifier.height(8.dp))
                Text("Tujuan", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = target == AutoConfigPlan.Target.Streaming,
                        onClick = { target = AutoConfigPlan.Target.Streaming },
                        enabled = !running,
                        label = { Text("Streaming") },
                    )
                    FilterChip(
                        selected = target == AutoConfigPlan.Target.Recording,
                        onClick = { target = AutoConfigPlan.Target.Recording },
                        enabled = !running,
                        label = { Text("Rekaman") },
                    )
                }

                Spacer(Modifier.height(8.dp))
                Text("Codec", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(VideoCodec.AVC, VideoCodec.HEVC).forEach { item ->
                        FilterChip(
                            selected = codec == item,
                            onClick = { codec = item },
                            enabled = !running,
                            label = { Text(item.label) },
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text("Preferensi fps", style = MaterialTheme.typography.bodyMedium)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AutoConfigPlan.FpsPreference.entries.forEach { item ->
                        FilterChip(
                            selected = preference == item,
                            onClick = { preference = item },
                            enabled = !running,
                            label = { Text(item.label) },
                        )
                    }
                }

                if (running) {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (log.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    log.forEach { line ->
                        Text(line, style = MaterialTheme.typography.bodySmall)
                    }
                }

                failure?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error)
                }

                result?.let { data ->
                    Spacer(Modifier.height(12.dp))
                    Text("Rekomendasi", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${data.width}x${data.height} @ ${data.fps} fps • ${data.codec.label}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Bitrate ${data.bitrateKbps / 1000} Mbps • ${data.reason}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Preferensi: ${preference.label}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Angka ini dari uji encoder. Bila rekaman game tersendat, " +
                            "turunkan resolusi atau jalankan benchmark beban game.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            when {
                result != null -> TextButton(onClick = {
                    val data = result ?: return@TextButton
                    onApply(
                        config.copy(
                            fps = data.fps,
                            videoBitrate = data.bitrateKbps * 1_000,
                            outputWidth = data.width,
                            outputHeight = data.height,
                            videoCodec = data.codec,
                            keyframeIntervalSec = 2,
                        ),
                    )
                    onDismiss()
                }) { Text("Terapkan") }

                !running -> TextButton(onClick = { start() }) { Text("Mulai diagnosa") }
                else -> TextButton(onClick = {}, enabled = false) { Text("Menguji…") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !running) { Text("Batal") }
        },
    )
}
