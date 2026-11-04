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
import androidx.compose.ui.res.stringResource
import com.rout.air.R
import com.rout.air.data.DiagLog
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
    val context = androidx.compose.ui.platform.LocalContext.current
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
                    config.width,
                    config.height,
                ).take(3)
                val probes = mutableListOf<AutoConfigPlan.Probe>()
                for ((index, candidate) in candidates.withIndex()) {
                    log = log + context.getString(
                        R.string.wizard_testing,
                        candidate.width,
                        candidate.height,
                        candidate.fps,
                    )
                    val probe = ConfigProbe.run(candidate, codec, seconds = 4)
                    probes += probe
                    val status = context.getString(
                        if (probe.passes()) R.string.wizard_pass else R.string.wizard_fail,
                    )
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
                        log = log + context.getString(
                            R.string.wizard_upload_testing,
                            tentative.bitrateKbps / 1000,
                        )
                        val upload = UploadProbe.measure(live, tentative.bitrateKbps)
                        uploadKbps = upload.recommendedKbps.takeIf { it > 0 }
                        log = log.dropLast(1) + buildString {
                            append("Upload terukur: ")
                            append(upload.measuredKbps / 1000)
                            append(" Mbps")
                            upload.note?.let { append(" • ").append(it) }
                        }
                    } else {
                        log = log + context.getString(R.string.wizard_upload_skipped)
                    }
                }

                result = AutoConfigPlan.recommend(
                    probes = probes,
                    target = target,
                    codec = codec,
                    hardwareEncoder = hardware,
                    uploadKbps = uploadKbps,
                ).also { data ->
                    DiagLog.line(
                        "setup: ${data.width}x${data.height}@${data.fps} " +
                            "${data.codec.label} ${data.bitrateKbps}kbps • " +
                                reasonText(context, data),
                    )
                }
                progress = 1f
            } catch (error: Throwable) {
                failure = error.message ?: context.getString(R.string.wizard_failed)
            } finally {
                running = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text(stringResource(R.string.settings_autoconfig_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    stringResource(R.string.wizard_hint),
                    style = MaterialTheme.typography.bodySmall,
                )

                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.wizard_target), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = target == AutoConfigPlan.Target.Streaming,
                        onClick = { target = AutoConfigPlan.Target.Streaming },
                        enabled = !running,
                        label = { Text(stringResource(R.string.wizard_streaming)) },
                    )
                    FilterChip(
                        selected = target == AutoConfigPlan.Target.Recording,
                        onClick = { target = AutoConfigPlan.Target.Recording },
                        enabled = !running,
                        label = { Text(stringResource(R.string.wizard_recording)) },
                    )
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.settings_codec),
                    style = MaterialTheme.typography.bodyMedium,
                )
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
                Text(
                    stringResource(R.string.wizard_fps_preference),
                    style = MaterialTheme.typography.bodyMedium,
                )
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
                            label = { Text(fpsPreferenceLabel(item)) },
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
                    Text(
                        stringResource(R.string.wizard_recommendation),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        stringResource(
                            R.string.wizard_result_line,
                            data.width,
                            data.height,
                            data.fps,
                            data.codec.label,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        stringResource(
                            R.string.wizard_result_bitrate,
                            data.bitrateKbps / 1000,
                            reasonText(context, data),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        stringResource(
                            R.string.wizard_preference_line,
                            fpsPreferenceLabel(preference),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.wizard_footer_hint),
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
                    // Samakan rasio output dengan kanvas; 0 berarti ikut kanvas.
                    val sameAsCanvas = data.width == config.width &&
                        data.height == config.height
                    onApply(
                        config.copy(
                            fps = data.fps,
                            videoBitrate = data.bitrateKbps * 1_000,
                            outputWidth = if (sameAsCanvas) 0 else data.width,
                            outputHeight = if (sameAsCanvas) 0 else data.height,
                            videoCodec = data.codec,
                            keyframeIntervalSec = 2,
                        ),
                    )
                    onDismiss()
                }) { Text(stringResource(R.string.wizard_apply)) }

                !running -> TextButton(onClick = { start() }) {
                    Text(stringResource(R.string.wizard_start))
                }

                else -> TextButton(onClick = {}, enabled = false) {
                    Text(stringResource(R.string.wizard_testing_button))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !running) {
                Text(stringResource(R.string.common_batal))
            }
        },
    )
}

/** Label preferensi fps mengikuti bahasa aplikasi. */
@Composable
private fun fpsPreferenceLabel(preference: AutoConfigPlan.FpsPreference): String =
    stringResource(
        when (preference) {
            AutoConfigPlan.FpsPreference.PreferHighFps -> R.string.wizard_prefer_fps
            AutoConfigPlan.FpsPreference.PreferHighRes -> R.string.wizard_prefer_res
            AutoConfigPlan.FpsPreference.Force30 -> R.string.wizard_force_30
            AutoConfigPlan.FpsPreference.Force60 -> R.string.wizard_force_60
        },
    )

/** Alasan rekomendasi disusun di UI supaya bisa diterjemahkan. */
private fun reasonText(
    context: android.content.Context,
    data: AutoConfigPlan.Recommendation,
): String {
    if (data.noCandidate) return context.getString(R.string.wizard_reason_no_candidate)
    val label = "${data.height}p${data.fps}"
    val fps = String.format(Locale.US, "%.1f", data.achievedFps)
    val base = context.getString(
        if (data.passed) R.string.wizard_reason_passed else R.string.wizard_reason_fallback,
        label,
        fps,
    )
    return base + if (data.uploadLimited) {
        context.getString(R.string.wizard_reason_upload)
    } else {
        ""
    }
}
