package com.rout.air.encode.benchmark

import android.content.Context
import androidx.annotation.StringRes
import com.rout.air.R
import android.opengl.GLES20
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import com.rout.air.core.model.ColorRangeMode
import com.rout.air.core.model.ColorSpaceMode
import com.rout.air.core.model.SaveLocation
import com.rout.air.core.model.VideoBitrateMode
import com.rout.air.core.model.VideoCodec
import com.rout.air.core.model.VideoProfile
import com.rout.air.data.logging.DiagLog
import com.rout.air.engine.gl.EglCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import com.rout.air.encode.mux.Mp4Writer
import com.rout.air.encode.probe.EncoderProbe
import com.rout.air.encode.video.OutputTarget
import com.rout.air.encode.video.VideoEncoder

/**
 * Benchmark "beban game" tanpa membuka game.
 *
 * Adegan GL berat (shader per piksel, overdraw berlapis, banyak sprite) dan
 * thread pengisi CPU dibuat sebesar preset, lalu hasilnya direkam lewat
 * [VideoEncoder] yang sama dengan rekaman asli. Yang diukur adalah fps
 * encoder yang benar-benar keluar, bukan fps render.
 *
 * Ini hanya meniru beban game. Game sungguhan tetap bisa lebih berat karena
 * tekstur, bayangan dinamis, dan AI.
 */
object GameLoadBenchmark {

    /** Setelan beban, disamakan dengan menu grafis mobile NTE. */
    enum class Preset(
        @StringRes val labelRes: Int,
        val objects: Int,
        val layers: Int,
        val shadows: Boolean,
        /** 0 = jumlah inti dikurangi 2 supaya UI tetap responsif. */
        val cpuThreads: Int,
    ) {
        Low(R.string.bench_preset_low, 150, 2, shadows = false, cpuThreads = 2),
        Medium(R.string.bench_preset_medium, 300, 3, shadows = false, cpuThreads = 4),
        High(R.string.bench_preset_high, 600, 5, shadows = true, cpuThreads = 6),
        Ultra(R.string.bench_preset_ultra, 1000, 8, shadows = true, cpuThreads = 0),
    }

    /** Tingkat panas perangkat; label dibaca dari resource. */
    enum class ThermalLevel(@StringRes val labelRes: Int) {
        Normal(R.string.thermal_normal),
        Warm(R.string.thermal_warm),
        Hot(R.string.thermal_hot),
        VeryHot(R.string.thermal_very_hot),
        Critical(R.string.thermal_critical),
        Emergency(R.string.thermal_emergency),
        Shutdown(R.string.thermal_shutdown),
        Unknown(R.string.thermal_unknown),
    }

    data class Result(
        val codec: VideoCodec,
        val preset: Preset,
        val width: Int,
        val height: Int,
        val targetFps: Int,
        val durationSec: Int,
        val avgFps: Double,
        val worstFps: Double,
        val frames: Int,
        val lateFrames: Int,
        val bitrateKbps: Int,
        val thermalMax: ThermalLevel,
        val uri: String?,
        val error: String? = null,
    )

    suspend fun run(
        context: Context,
        width: Int,
        height: Int,
        targetFps: Int,
        bitrate: Int,
        codec: VideoCodec,
        preset: Preset,
        durationSec: Int,
        trafficPercent: Int,
        saveVideo: Boolean,
        keepAwake: Boolean = true,
        onProgress: (secondsLeft: Int, fraction: Float, fpsNow: Int) -> Unit = { _, _, _ -> },
    ): Result = withContext(Dispatchers.Default) {
        measure(
            context = context,
            width = width,
            height = height,
            targetFps = targetFps,
            bitrate = bitrate,
            codec = codec,
            preset = preset,
            durationSec = durationSec,
            trafficPercent = trafficPercent,
            saveVideo = saveVideo,
            keepAwake = keepAwake,
            onProgress = onProgress,
        )
    }

    /** Fps detik terburuk; dipakai juga oleh tes tanpa perangkat. */
    fun worstSecond(perSecond: List<Int>): Double =
        perSecond.minOrNull()?.toDouble() ?: 0.0

    /** Bitrate manual dibatasi 2–100 Mbps agar slider tidak menghasilkan nilai aneh. */
    fun manualBitrate(mbps: Float): Int =
        (mbps.coerceIn(2f, 100f) * 1_000_000).toInt()

    /** Bitrate wajar untuk ukuran dan fps uji; acuan 12 Mbps di 1080p60. */
    fun bitrateFor(width: Int, height: Int, fps: Int): Int {
        val reference = 1920.0 * 1080.0 * 60.0
        val scale = width.toDouble() * height.toDouble() * fps / reference
        return (12_000_000 * scale).toInt().coerceIn(4_000_000, 60_000_000)
    }

    /** Jumlah objek setelah dikurangi persentase Traffic; minimal 8. */
    fun effectiveObjects(preset: Preset, trafficPercent: Int): Int =
        (preset.objects * trafficPercent.coerceIn(0, 100) / 100).coerceAtLeast(8)

    private fun measure(
        context: Context,
        width: Int,
        height: Int,
        targetFps: Int,
        bitrate: Int,
        codec: VideoCodec,
        preset: Preset,
        durationSec: Int,
        trafficPercent: Int,
        saveVideo: Boolean,
        keepAwake: Boolean,
        onProgress: (secondsLeft: Int, fraction: Float, fpsNow: Int) -> Unit,
    ): Result {
        val stamp = System.currentTimeMillis()
        val target = if (saveVideo) {
            OutputTarget.create(
                context = context,
                location = SaveLocation.MoviesRoutAir,
                timestampMs = stamp,
                nameTemplate = "RoutAir_bench_${preset.name}_${codec.name}_{date}_{time}",
                noSpaceFileName = true,
            )
        } else {
            null
        }
        val writer = target?.let { Mp4Writer(it).apply { expectTracks(videoTracks = 1) } }

        val frameCount = AtomicInteger()
        val lateFrames = AtomicInteger()
        var core: EglCore? = null
        var scene: BenchmarkScene? = null
        var encoder: VideoEncoder? = null
        val load = CpuLoad(threads = if (preset.cpuThreads > 0) {
            preset.cpuThreads
        } else {
            (Runtime.getRuntime().availableProcessors() - 2).coerceAtLeast(2)
        })

        // Wake lock hanya dipakai bila pengguna menyalakan "Layar tetap menyala".
        val wakeLock = if (keepAwake) {
            context.getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RoutAir:bench")
                ?.apply {
                    setReferenceCounted(false)
                    runCatching { acquire((durationSec + 60) * 1_000L) }
                }
        } else {
            null
        }

        var success = false
        var encoderClosed = false
        var encoderError: Throwable? = null
        try {
            encoder = VideoEncoder(
                width = width,
                height = height,
                fps = targetFps,
                bitrate = bitrate,
                keyframeIntervalSec = 1,
                bitrateMode = VideoBitrateMode.CBR,
                cqLevel = 20,
                profile = VideoProfile.Auto,
                colorSpace = ColorSpaceMode.BT709,
                colorRange = ColorRangeMode.Limited,
                requestedCodec = codec,
                encoderName = EncoderProbe.select(codec)?.name,
                sink = writer ?: NullSink,
                onEncoderError = { encoderError = it },
                onFallback = { actual, message ->
                    DiagLog.line("bench: $message (dipakai ${actual.label})")
                },
                onFrameEncoded = { frameCount.incrementAndGet() },
            )
            val usedCodec = encoder.activeCodec

            core = EglCore()
            val window = core.createWindowSurface(encoder.inputSurface)
            core.makeCurrent(window)
            GLES20.glViewport(0, 0, width, height)

            val activeScene = BenchmarkScene(
                frameWidth = width,
                frameHeight = height,
                layers = preset.layers,
                objects = effectiveObjects(preset, trafficPercent),
                shadows = preset.shadows,
            )
            scene = activeScene
            activeScene.setHud(
                listOf(
                    com.rout.air.data.common.Msg.get(R.string.bench_hud_title),
                    com.rout.air.data.common.Msg.get(
                        R.string.bench_hud_config,
                        usedCodec.label,
                        com.rout.air.data.common.Msg.get(preset.labelRes),
                        width,
                        height,
                        targetFps,
                    ),
                    com.rout.air.data.common.Msg.get(R.string.bench_measuring),
                ),
            )
            load.start()

            val thermal = context.getSystemService(PowerManager::class.java)
            var thermalMax = thermal?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE
            val perSecond = mutableListOf<Int>()
            val seconds = durationSec.coerceAtLeast(1)
            val durationNs = seconds * 1_000_000_000L
            val frameIntervalNs = 1_000_000_000L / targetFps
            val startNs = System.nanoTime()
            var nextFrameNs = startNs
            var nextSampleNs = startNs + 1_000_000_000L
            var lastSampleFrames = 0
            var secondIndex = 0
            var renderSumNs = 0L
            var renderPeakNs = 0L
            var cpuPercent = 0
            var cpuBaseMs = Process.getElapsedCpuTime()
            var cpuBaseWallNs = startNs
            var pssMb = 0
            val memoryInfo = Debug.MemoryInfo()
            val cores = Runtime.getRuntime().availableProcessors()

            onProgress(seconds, 0f, 0)

            while (true) {
                val now = System.nanoTime()
                if (now - startNs >= durationNs) break

                if (now < nextFrameNs) {
                    sleepUntil(nextFrameNs)
                } else if (now - nextFrameNs > frameIntervalNs) {
                    // Deadline terlewat: hitung sekali lalu sinkronkan ulang.
                    lateFrames.incrementAndGet()
                    nextFrameNs = now
                }

                val elapsedNs = System.nanoTime() - startNs
                val renderStartNs = System.nanoTime()
                activeScene.draw(timeSec = elapsedNs / 1_000_000_000f)
                core.setPresentationTime(window, elapsedNs)
                if (!core.swapBuffers(window)) error("eglSwapBuffers gagal")
                val renderNs = System.nanoTime() - renderStartNs
                renderSumNs += renderNs
                if (renderNs > renderPeakNs) renderPeakNs = renderNs
                nextFrameNs += frameIntervalNs

                val after = System.nanoTime()
                if (after - nextSampleNs >= 0) {
                    val total = frameCount.get()
                    perSecond += total - lastSampleFrames
                    lastSampleFrames = total
                    thermal?.let { status ->
                        if (status.currentThermalStatus > thermalMax) {
                            thermalMax = status.currentThermalStatus
                        }
                    }

                    // CPU proses dihitung dari waktu CPU seluruh thread.
                    val wallNs = (after - cpuBaseWallNs).coerceAtLeast(1L)
                    val cpuMs = (Process.getElapsedCpuTime() - cpuBaseMs).coerceAtLeast(0L)
                    cpuPercent = (cpuMs * 1_000_000.0 / wallNs / cores * 100)
                        .toInt()
                        .coerceIn(0, 999)
                    cpuBaseMs = Process.getElapsedCpuTime()
                    cpuBaseWallNs = after
                    if (secondIndex % 5 == 0) {
                        runCatching {
                            Debug.getMemoryInfo(memoryInfo)
                            pssMb = memoryInfo.totalPss / 1024
                        }
                    }

                    val done = (after - startNs) / 1_000_000_000L
                    val fpsLast = perSecond.lastOrNull() ?: 0
                    val avgNow = if (perSecond.isEmpty()) 0.0 else perSecond.average()
                    val minNow = perSecond.minOrNull() ?: 0
                    activeScene.setHud(
                        listOf(
                            com.rout.air.data.common.Msg.get(R.string.bench_hud_title),
                            com.rout.air.data.common.Msg.get(
                                R.string.bench_hud_config,
                                usedCodec.label,
                                com.rout.air.data.common.Msg.get(preset.labelRes),
                                width,
                                height,
                                targetFps,
                            ),
                            com.rout.air.data.common.Msg.get(
                                R.string.bench_hud_fps,
                                fpsLast,
                                oneDecimal(avgNow),
                                minNow,
                            ),
                            com.rout.air.data.common.Msg.get(
                                R.string.bench_hud_render,
                                oneDecimal(renderPeakNs / 1e6),
                                oneDecimal(renderSumNs / 1e6 / fpsLast.coerceAtLeast(1)),
                            ),
                            com.rout.air.data.common.Msg.get(
                                R.string.bench_hud_cpu,
                                cpuPercent,
                                pssMb,
                            ),
                            com.rout.air.data.common.Msg.get(
                                R.string.bench_hud_thermal,
                                com.rout.air.data.common.Msg.get(thermalLevel(thermalMax).labelRes),
                                seconds - done,
                            ),
                        ),
                    )
                    renderSumNs = 0L
                    renderPeakNs = 0L
                    secondIndex++
                    onProgress(
                        (seconds - done).coerceAtLeast(0L).toInt(),
                        (after - startNs).toFloat() / durationNs,
                        fpsLast,
                    )
                    nextSampleNs += 1_000_000_000L
                }
            }

            encoderError?.let { throw it }
            load.stop()
            encoder.finish()
            encoderClosed = true
            val writerOk = writer?.finish() ?: true
            val bytes = writer?.bytesWritten ?: 0L
            target?.finish(context, success = writerOk)
            success = true

            val frames = frameCount.get()
            val avgFps = frames.toDouble() / seconds
            val worst = worstSecond(perSecond)
            val kbps = if (bytes > 0) {
                (bytes * 8 / seconds / 1_000L).toInt()
            } else {
                0
            }
            DiagLog.line(
                "bench: ${usedCodec.label} " +
                    com.rout.air.data.common.Msg.get(preset.labelRes) +
                    " ${width}x$height@$targetFps " +
                    String.format(Locale.US, "avg=%.1f worst=%.1f", avgFps, worst) +
                    com.rout.air.data.common.Msg.get(
                        R.string.bench_log_suffix,
                        frames,
                        lateFrames.get(),
                        com.rout.air.data.common.Msg.get(thermalLevel(thermalMax).labelRes),
                    ),
            )
            return Result(
                codec = usedCodec,
                preset = preset,
                width = width,
                height = height,
                targetFps = targetFps,
                durationSec = seconds,
                avgFps = avgFps,
                worstFps = worst,
                frames = frames,
                lateFrames = lateFrames.get(),
                bitrateKbps = kbps,
                thermalMax = thermalLevel(thermalMax),
                uri = target?.uri?.toString(),
            )
        } finally {
            runCatching { if (wakeLock?.isHeld == true) wakeLock.release() }
            load.stop()
            runCatching { scene?.release() }
            runCatching { core?.makeNothingCurrent() }
            runCatching { core?.release() }
            if (!encoderClosed) {
                runCatching { encoder?.finish(timeoutMs = 2_000L) }
            }
            if (!success) {
                runCatching { writer?.finish() }
                runCatching { target?.finish(context, success = false) }
            }
        }
    }

    /** Status termal Android dalam teks yang bisa dibaca. */
    fun thermalLevel(status: Int): ThermalLevel = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> ThermalLevel.Normal
        PowerManager.THERMAL_STATUS_LIGHT -> ThermalLevel.Warm
        PowerManager.THERMAL_STATUS_MODERATE -> ThermalLevel.Hot
        PowerManager.THERMAL_STATUS_SEVERE -> ThermalLevel.VeryHot
        PowerManager.THERMAL_STATUS_CRITICAL -> ThermalLevel.Critical
        PowerManager.THERMAL_STATUS_EMERGENCY -> ThermalLevel.Emergency
        PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalLevel.Shutdown
        else -> ThermalLevel.Unknown
    }

    /** Satu angka desimal dengan titik, tidak mengikuti locale perangkat. */
    private fun oneDecimal(value: Double): String =
        String.format(Locale.US, "%.1f", value)

    private fun sleepUntil(deadlineNs: Long) {
        while (true) {
            val remaining = deadlineNs - System.nanoTime()
            if (remaining <= 0) return
            if (remaining > 2_000_000L) {
                // Tidur kasar dulu, lalu putar sisa waktu agar pacing presisi.
                Thread.sleep((remaining / 1_000_000L) - 1)
            } else {
                // Putar sisa waktu; lebih presisi dari sleep 1 ms.
                Thread.yield()
            }
        }
    }

}
