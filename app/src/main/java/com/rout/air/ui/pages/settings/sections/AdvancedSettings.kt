package com.rout.air.ui.pages.settings.sections

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.rout.air.core.model.CaptureConfig
import com.rout.air.core.model.LiveConfig
import com.rout.air.core.model.VideoCodec
import com.rout.air.encode.probe.EncoderBenchmark
import com.rout.air.encode.probe.EncoderProbe
import com.rout.air.encode.benchmark.GameLoadBenchmark
import com.rout.air.data.logging.DiagLog
import com.rout.air.data.settings.SettingsStore
import androidx.compose.ui.res.stringResource
import com.rout.air.R
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import com.rout.air.ui.dialogs.AutoConfigWizardDialog
import com.rout.air.ui.pages.settings.BenchResolution
import com.rout.air.ui.components.Hint
import com.rout.air.ui.pages.settings.components.InfoRow
import com.rout.air.ui.pages.settings.components.SectionTitle
import com.rout.air.ui.pages.settings.components.SettingSwitchRow

@Composable
internal fun AdvancedSettings(
    config: CaptureConfig,
    canvasConfig: CaptureConfig,
    live: LiveConfig,
    onConfigChange: (CaptureConfig) -> Unit,
) {
    SectionTitle(stringResource(R.string.settings_informasi))
    val caps = remember { EncoderProbe.all() }
    val encoder = EncoderProbe.select(caps, canvasConfig.videoCodec)
    InfoRow(stringResource(R.string.settings_version), "1.0 (M4)")
    InfoRow(
        stringResource(R.string.settings_info_canvas),
        "${canvasConfig.width}x${canvasConfig.height}",
    )
    InfoRow(stringResource(R.string.settings_renderer), "OpenGL ES 3.0")
    InfoRow(stringResource(R.string.settings_codec), canvasConfig.videoCodec.label)
    InfoRow(
        stringResource(R.string.settings_encoder),
        encoder?.let {
            "${it.name} • ${if (it.hardware) "hardware" else "software"}"
        } ?: stringResource(R.string.settings_encoder_missing),
    )
    InfoRow(
        stringResource(R.string.settings_info_audio),
        stringResource(R.string.settings_info_audio_value),
    )
    InfoRow(stringResource(R.string.settings_rate_control), canvasConfig.bitrateMode.name)

    Text(
        stringResource(R.string.settings_encoder_scan),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (caps.isEmpty()) {
        Text(
            stringResource(R.string.settings_encoder_scan_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        caps.forEach { cap ->
            Text(
                "• ${cap.codec.label}: ${cap.describe()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    InfoRow(stringResource(R.string.settings_profile), canvasConfig.videoProfile.name)
    InfoRow(
        stringResource(R.string.settings_color),
        "${canvasConfig.colorSpace.name} • ${canvasConfig.colorRange.name}",
    )
    InfoRow(stringResource(R.string.settings_network), stringResource(R.string.settings_network_value))

    Spacer(Modifier.height(16.dp))
    SectionTitle(stringResource(R.string.settings_performance))
    SettingSwitchRow(
        title = stringResource(R.string.settings_static_throttle),
        subtitle = stringResource(R.string.settings_static_throttle_desc),
        checked = config.staticThrottleEnabled,
    ) { onConfigChange(config.copy(staticThrottleEnabled = it)) }
    SettingSwitchRow(
        title = stringResource(R.string.settings_thermal_throttle),
        subtitle = stringResource(R.string.settings_thermal_throttle_desc),
        checked = config.thermalThrottleEnabled,
    ) { onConfigChange(config.copy(thermalThrottleEnabled = it)) }
    Hint(stringResource(R.string.settings_performance_hint))

    Spacer(Modifier.height(16.dp))
    SectionTitle(stringResource(R.string.settings_encoder_test))
    Hint(stringResource(R.string.settings_encoder_test_hint))

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var testing by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf(emptyList<EncoderBenchmark.Result>()) }
    var runningCodec by remember { mutableStateOf<VideoCodec?>(null) }
    var secondsLeft by remember { mutableStateOf(5) }
    var codecFraction by remember { mutableStateOf(0f) }
    var codecIndex by remember { mutableStateOf(0) }
    var benchError by remember { mutableStateOf<String?>(null) }
    val testCodecs = listOf(VideoCodec.AVC, VideoCodec.HEVC)

    Spacer(Modifier.height(8.dp))
    Button(
        onClick = {
            testing = true
            results = emptyList()
            benchError = null
            runningCodec = null
            secondsLeft = 5
            codecFraction = 0f
            codecIndex = 0
            // Jalan di thread lain; layar tetap bisa digulir selama uji.
            scope.launch {
                try {
                    EncoderBenchmark.measure(
                        width = canvasConfig.encoderWidth,
                        height = canvasConfig.encoderHeight,
                        fps = canvasConfig.fps,
                        codecs = testCodecs,
                        onProgress = { index, codec, left, fraction ->
                            codecIndex = index
                            runningCodec = codec
                            secondsLeft = left
                            codecFraction = fraction
                        },
                        // Hasil satu codec langsung tampil tanpa menunggu yang lain.
                        onResult = { results = results + it },
                    )
                } catch (error: Throwable) {
                    benchError = error.message ?: context.getString(R.string.settings_test_failed)
                } finally {
                    // Tombol harus selalu aktif lagi, termasuk setelah gagal.
                    testing = false
                }
            }
        },
        enabled = !testing,
    ) {
        Text(
            stringResource(
                if (testing) {
                    R.string.settings_encoder_test_running
                } else {
                    R.string.settings_encoder_test_start
                },
            ),
        )
    }

    if (testing) {
        val overall = ((codecIndex + codecFraction) / testCodecs.size).coerceIn(0f, 1f)
        // Animasi membuat bar bergerak halus meski progres dikirim tiap 100 ms.
        val animated by animateFloatAsState(targetValue = overall, label = "uji-encoder")
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { animated },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(
                R.string.settings_test_progress,
                runningCodec?.label ?: stringResource(R.string.settings_test_preparing),
                secondsLeft,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    benchError?.let { message ->
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.settings_test_failed_detail, message),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }

    results.forEach { result ->
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (result.error != null) {
                stringResource(
                    R.string.settings_test_codec_failed,
                    result.codec.label,
                    result.error ?: "",
                )
            } else {
                String.format(
                    java.util.Locale.US,
                    "%s: %.1f fps • %d frame • %s",
                    result.codec.label,
                    result.fps,
                    result.frames,
                    result.encoder,
                )
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (result.error != null) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }

    Spacer(Modifier.height(20.dp))
    AutoConfigSection(
        config = config,
        live = live,
        onConfigChange = onConfigChange,
    )

    Spacer(Modifier.height(20.dp))
    GameLoadBenchmarkSection()

    Spacer(Modifier.height(16.dp))
    SectionTitle(stringResource(R.string.settings_diagnostik))
    Hint(stringResource(R.string.settings_setiap_rekaman_menulis_log_ke_download_r))

    var exportMessage by remember { mutableStateOf<String?>(null) }
    Spacer(Modifier.height(8.dp))
    Button(
        onClick = {
            exportMessage = DiagLog.exportToDownloads(context)
                ?.let { exported -> context.getString(R.string.settings_log_exported, exported) }
                ?: context.getString(R.string.settings_log_export_failed)
        },
    ) {
        Text(stringResource(R.string.settings_export_log))
    }
    exportMessage?.let { Hint(it) }
}

/**
 * Kartu setup otomatis: menampilkan hasil terakhir dan membuka wizard.
 * Penempatannya di atas benchmark sesuai permintaan.
 */
@Composable
internal fun AutoConfigSection(
    config: CaptureConfig,
    live: LiveConfig,
    onConfigChange: (CaptureConfig) -> Unit,
) {
    val context = LocalContext.current
    val store = remember { SettingsStore(context) }
    var open by remember { mutableStateOf(false) }
    var last by remember { mutableStateOf(store.lastAutoConfig()) }

    SectionTitle(stringResource(R.string.settings_autoconfig_title))
    Hint(stringResource(R.string.settings_autoconfig_hint))
    last?.let { Hint(stringResource(R.string.settings_last_result, it)) }
    Spacer(Modifier.height(8.dp))
    Button(onClick = { open = true }) {
        Text(stringResource(R.string.settings_autoconfig_start))
    }

    if (open) {
        AutoConfigWizardDialog(
            config = config,
            live = live,
            onApply = { updated ->
                onConfigChange(updated)
                val text = "${updated.outputWidth}x${updated.outputHeight} " +
                    "@${updated.fps} fps • ${updated.videoCodec.label} • " +
                    "${updated.videoBitrate / 1_000_000} Mbps"
                store.setLastAutoConfig(text)
                store.setWizardDone()
                last = text
            },
            onDismiss = {
                open = false
                store.setWizardDone()
            },
        )
    }
}

/**
 * Benchmark beban game: adegan GL berat + thread pengisi CPU, direkam lewat
 * encoder yang sama dengan rekaman asli. Tidak butuh game maupun izin
 * tangkap layar.
 */
@Composable
internal fun GameLoadBenchmarkSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var preset by remember { mutableStateOf(GameLoadBenchmark.Preset.High) }
    var codec by remember { mutableStateOf(VideoCodec.AVC) }
    var resolution by remember { mutableStateOf(BenchResolution.P1080) }
    var targetFps by remember { mutableStateOf(60) }
    var manualBitrate by remember { mutableStateOf(false) }
    var manualMbps by remember { mutableStateOf(12f) }
    var minutes by remember { mutableStateOf(3) }
    var traffic by remember { mutableStateOf(70f) }
    var saveVideo by remember { mutableStateOf(true) }
    var keepAwake by remember { mutableStateOf(true) }
    var running by remember { mutableStateOf(false) }
    var secondsLeft by remember { mutableStateOf(0) }
    var fraction by remember { mutableStateOf(0f) }
    var fpsNow by remember { mutableStateOf(0) }
    var result by remember { mutableStateOf<GameLoadBenchmark.Result?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }

    // Layar tidak boleh mati saat benchmark; kalau mati, render berhenti
    // dan angka fps jadi turun tanpa alasan yang jelas.
    val view = LocalView.current
    DisposableEffect(running, keepAwake) {
        view.keepScreenOn = running && keepAwake
        onDispose { view.keepScreenOn = false }
    }

    SectionTitle(stringResource(R.string.settings_bench_title))
    Hint(stringResource(R.string.settings_bench_hint))
    Hint(stringResource(R.string.settings_bench_overlay_hint))

    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.settings_bench_graphic_load), style = MaterialTheme.typography.bodyMedium)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GameLoadBenchmark.Preset.entries.forEach { item ->
            FilterChip(
                selected = preset == item,
                onClick = { preset = item },
                label = { Text(stringResource(item.labelRes)) },
            )
        }
    }

    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.settings_codec), style = MaterialTheme.typography.bodyMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(VideoCodec.AVC, VideoCodec.HEVC).forEach { item ->
            FilterChip(
                selected = codec == item,
                onClick = { codec = item },
                label = { Text(item.label) },
            )
        }
    }

    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.settings_resolution), style = MaterialTheme.typography.bodyMedium)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BenchResolution.entries.forEach { item ->
            FilterChip(
                selected = resolution == item,
                onClick = { resolution = item },
                label = { Text(item.label) },
            )
        }
    }

    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.settings_fps), style = MaterialTheme.typography.bodyMedium)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(30, 60, 90, 120).forEach { item ->
            FilterChip(
                selected = targetFps == item,
                onClick = { targetFps = item },
                label = { Text(stringResource(R.string.settings_bench_fps_option, item)) },
            )
        }
    }

    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.settings_bitrate), style = MaterialTheme.typography.bodyMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = !manualBitrate,
            onClick = { manualBitrate = false },
            label = { Text(stringResource(R.string.settings_otomatis)) },
        )
        FilterChip(
            selected = manualBitrate,
            onClick = { manualBitrate = true },
            label = { Text(stringResource(R.string.settings_bench_manual)) },
        )
    }
    val autoBitrate = GameLoadBenchmark.bitrateFor(
        resolution.width,
        resolution.height,
        targetFps,
    )
    val effectiveBitrate = if (manualBitrate) {
        GameLoadBenchmark.manualBitrate(manualMbps)
    } else {
        autoBitrate
    }
    if (manualBitrate) {
        Text(String.format(java.util.Locale.US, "%.1f Mbps", manualMbps))
        // Tanpa steps: tanda tick rapat membuat track terlihat seperti balok.
        Slider(
            value = manualMbps,
            onValueChange = { manualMbps = (it * 2f).roundToInt() / 2f },
            valueRange = 2f..100f,
        )
    } else {
        Hint(
            stringResource(
                R.string.settings_bench_auto_bitrate,
                String.format(java.util.Locale.US, "%.1f Mbps", effectiveBitrate / 1_000_000f),
            ),
        )
    }

    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.settings_bench_duration), style = MaterialTheme.typography.bodyMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(1, 3, 5).forEach { item ->
            FilterChip(
                selected = minutes == item,
                onClick = { minutes = item },
                label = { Text(stringResource(R.string.settings_bench_minutes, item)) },
            )
        }
    }

    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(R.string.settings_bench_traffic, traffic.toInt()),
        style = MaterialTheme.typography.bodyMedium,
    )
    Slider(
        value = traffic,
        // Dibulatkan 5% agar angka mudah dibaca, tanpa tick yang menutupi track.
        onValueChange = { traffic = (it / 5f).roundToInt() * 5f },
        valueRange = 0f..100f,
    )

    SettingSwitchRow(
        title = stringResource(R.string.settings_bench_keep_awake),
        subtitle = stringResource(R.string.settings_bench_keep_awake_desc),
        checked = keepAwake,
    ) { keepAwake = it }

    SettingSwitchRow(
        title = stringResource(R.string.settings_bench_save_title),
        subtitle = stringResource(R.string.settings_bench_save_desc),
        checked = saveVideo,
    ) { saveVideo = it }
    if (saveVideo) {
        val estimateMb = effectiveBitrate / 8L * (minutes * 60) / 1_000_000.0
        Hint(
            stringResource(
                R.string.settings_bench_estimate,
                String.format(java.util.Locale.US, "%.0f MB", estimateMb),
                String.format(java.util.Locale.US, "%.1f Mbps", effectiveBitrate / 1_000_000f),
            ),
        )
    }

    Spacer(Modifier.height(8.dp))
    Button(
        onClick = {
            running = true
            result = null
            failure = null
            secondsLeft = minutes * 60
            fraction = 0f
            fpsNow = 0
            scope.launch {
                try {
                    result = GameLoadBenchmark.run(
                        context = context,
                        width = resolution.width,
                        height = resolution.height,
                        targetFps = targetFps,
                        bitrate = effectiveBitrate,
                        codec = codec,
                        preset = preset,
                        durationSec = minutes * 60,
                        trafficPercent = traffic.toInt(),
                        saveVideo = saveVideo,
                        keepAwake = keepAwake,
                        onProgress = { left, done, fps ->
                            secondsLeft = left
                            fraction = done
                            fpsNow = fps
                        },
                    )
                } catch (error: Throwable) {
                    failure = error.message ?: "Benchmark gagal"
                } finally {
                    running = false
                }
            }
        },
        enabled = !running,
    ) {
        Text(
            if (running) {
                "Merekam… ${secondsLeft / 60}:${(secondsLeft % 60).toString().padStart(2, '0')}"
            } else {
                "Mulai benchmark (${minutes} menit)"
            },
        )
    }

    if (running) {
        val animated by animateFloatAsState(targetValue = fraction, label = "beban-game")
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { animated },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        Hint(
            stringResource(
                R.string.settings_bench_live_line,
                fpsNow,
                resolution.width,
                resolution.height,
                targetFps,
                GameLoadBenchmark.effectiveObjects(preset, traffic.toInt()),
            ),
        )
    }

    failure?.let {
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.settings_bench_failed, it),
            color = MaterialTheme.colorScheme.error,
        )
    }

    result?.let { data ->
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(
                R.string.settings_bench_result_line,
                data.codec.label,
                stringResource(data.preset.labelRes),
                data.width,
                data.height,
                data.targetFps,
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        InfoRow(
            stringResource(R.string.settings_bench_avg_fps),
            String.format(java.util.Locale.US, "%.1f", data.avgFps),
        )
        InfoRow(
            stringResource(R.string.settings_bench_worst_fps),
            String.format(java.util.Locale.US, "%.1f", data.worstFps),
        )
        InfoRow(
            stringResource(R.string.settings_bench_frames_late),
            "${data.frames} / ${data.lateFrames}",
        )
        InfoRow(stringResource(R.string.settings_bench_bitrate), "${data.bitrateKbps} kbps")
        InfoRow(
            stringResource(R.string.settings_bench_thermal),
            stringResource(data.thermalMax.labelRes),
        )
        data.uri?.let { InfoRow(stringResource(R.string.settings_bench_video), it) }
        Text(
            verdict(data),
            style = MaterialTheme.typography.bodyMedium,
            color = if (data.avgFps >= data.targetFps * 0.95) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            },
        )
    }
}

/** Kesimpulan singkat dari angka benchmark. */
@Composable
internal fun verdict(result: GameLoadBenchmark.Result): String = when {
    result.error != null -> stringResource(R.string.settings_bench_verdict_failed, result.error)
    result.avgFps >= result.targetFps * 0.95 -> stringResource(R.string.settings_bench_verdict_ok)
    result.worstFps < result.targetFps * 0.6 ->
        stringResource(R.string.settings_bench_verdict_unstable)

    else -> stringResource(R.string.settings_bench_verdict_close)
}
