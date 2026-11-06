package com.rout.air.encode.probe

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.util.Log
import com.rout.air.core.model.VideoCodec
import com.rout.air.data.logging.DiagLog
import com.rout.air.engine.gl.EglCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Mengukur fps maksimum encoder dengan frame sintetis.
 *
 * Frame dikirim lewat Surface (jalur yang sama dengan rekaman), lalu jumlah
 * frame yang benar-benar keluar dari encoder dihitung. Hasilnya batas atas
 * encoder: rekaman nyata tetap dibatasi pilihan fps, refresh layar, dan
 * throttle termal.
 */
object EncoderBenchmark {

    data class Result(
        val codec: VideoCodec,
        val encoder: String,
        val width: Int,
        val height: Int,
        val fps: Double,
        val frames: Int,
        val error: String? = null,
    )

    /** 5 detik per codec sudah cukup untuk mencapai laju stabil. */
    /**
     * [onProgress] dipanggil tiap ±100 ms dengan sisa detik codec yang sedang
     * diuji, [onResult] langsung setelah satu codec selesai supaya hasilnya
     * muncul bertahap, bukan menunggu keduanya.
     */
    suspend fun measure(
        width: Int,
        height: Int,
        fps: Int = 60,
        secondsPerCodec: Int = 5,
        codecs: List<VideoCodec> = listOf(VideoCodec.AVC, VideoCodec.HEVC),
        onProgress: (index: Int, codec: VideoCodec, secondsLeft: Int, fraction: Float) -> Unit =
            { _, _, _, _ -> },
        onResult: (Result) -> Unit = {},
    ): List<Result> = withContext(Dispatchers.Default) {
        val results = mutableListOf<Result>()
        codecs.forEachIndexed { index, codec ->
            // Tampilkan nama codec segera, sebelum EGL/encoder siap.
            onProgress(index, codec, secondsPerCodec, 0f)
            val result = runCatching {
                measureOne(codec, width, height, fps, secondsPerCodec) { secondsLeft, fraction ->
                    onProgress(index, codec, secondsLeft, fraction)
                }
            }.getOrElse { error ->
                Log.w(TAG, "Uji ${codec.label} gagal: ${error.message}", error)
                Result(
                    codec = codec,
                    encoder = "-",
                    width = width,
                    height = height,
                    fps = 0.0,
                    frames = 0,
                    error = error.message ?: "gagal",
                )
            }
            results += result
            onResult(result)
        }
        results
    }

    private fun measureOne(
        codec: VideoCodec,
        width: Int,
        height: Int,
        fps: Int,
        seconds: Int,
        onProgress: (secondsLeft: Int, fraction: Float) -> Unit,
    ): Result {
        val chosen = EncoderProbe.select(codec)
            ?: error("Tidak ada encoder ${codec.label}")
        val mediaCodec = runCatching { MediaCodec.createByCodecName(chosen.name) }
            .getOrElse { MediaCodec.createEncoderByType(codec.mime) }

        var inputSurface: android.view.Surface? = null
        var core: EglCore? = null
        var window: EGLSurface? = null

        try {
            val format = MediaFormat.createVideoFormat(codec.mime, width, height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
                )
                // Bitrate hanya formalitas; yang diukur jumlah frame keluar.
                setInteger(MediaFormat.KEY_BIT_RATE, maxOf(2_000_000, width * height * 4))
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(
                    MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
                )
            }
            configure(mediaCodec, format)

            inputSurface = runCatching { mediaCodec.createInputSurface() }
                .getOrElse { error("createInputSurface: ${it.message}") }
            runCatching { mediaCodec.start() }
                .onFailure { error("start: ${it.message}") }

            // EglCore memakai konfigurasi RECORDABLE yang wajib dipakai
            // sebagai input MediaCodec. Konfigurasi EGL biasa membuat
            // encoder berhenti sesaat setelah start.
            core = EglCore()
            window = core.createWindowSurface(inputSurface)
            core.makeCurrent(window)

            val frames = encodeLoop(mediaCodec, core, window, seconds, onProgress)
            val fpsResult = frames.toDouble() / seconds
            DiagLog.line(
                "uji: ${codec.label} ${chosen.name} ${width}x$height → " +
                    String.format(java.util.Locale.US, "%.1f", fpsResult) + " fps",
            )
            return Result(
                codec = codec,
                encoder = chosen.name,
                width = width,
                height = height,
                fps = fpsResult,
                frames = frames,
            )
        } finally {
            runCatching { core?.makeNothingCurrent() }
            runCatching { if (window != null) core?.releaseSurface(window) }
            runCatching { core?.release() }
            runCatching { mediaCodec.stop() }
            runCatching { mediaCodec.release() }
            runCatching { inputSurface?.release() }
        }
    }

    /**
     * Sebagian encoder menolak CBR; turunkan setelan sampai configure
     * diterima supaya pengukuran tetap jalan.
     */
    private fun configure(mediaCodec: MediaCodec, format: MediaFormat) {
        try {
            mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            return
        } catch (error: Throwable) {
            Log.w(TAG, "CBR ditolak, uji memakai VBR: ${error.message}")
        }
        format.setInteger(
            MediaFormat.KEY_BITRATE_MODE,
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
        )
        mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    }

    /** Mengirim frame secepat mungkin; swapBuffers ikut menahan bila antrean penuh. */
    private fun encodeLoop(
        mediaCodec: MediaCodec,
        core: EglCore,
        window: EGLSurface,
        seconds: Int,
        onProgress: (secondsLeft: Int, fraction: Float) -> Unit,
    ): Int {
        val info = MediaCodec.BufferInfo()
        val startNs = System.nanoTime()
        val durationNs = seconds * 1_000_000_000L
        // Hanya frame dengan PTS di dalam jendela uji yang dihitung, supaya
        // sisa antrean setelah 5 detik tidak menggelembungkan hasil.
        val windowEndUs = durationNs / 1_000L
        var frames = 0
        var tick = 0
        var lastReportNs = 0L
        GLES20.glDisable(GLES20.GL_BLEND)
        onProgress(seconds, 0f)

        while (true) {
            val nowNs = System.nanoTime()
            val elapsed = nowNs - startNs
            if (elapsed >= durationNs) break

            if (nowNs - lastReportNs >= REPORT_INTERVAL_NS) {
                lastReportNs = nowNs
                val fraction = (elapsed.toFloat() / durationNs).coerceIn(0f, 1f)
                val left = ((durationNs - elapsed + 999_999_999L) / 1_000_000_000L)
                    .toInt()
                    .coerceAtLeast(0)
                onProgress(left, fraction)
            }

            // Warna berganti tiap frame supaya encoder tidak menganggap
            // frame identik dan berhenti mengeluarkan output.
            val shade = (tick % 8) / 8f
            GLES20.glClearColor(shade, 1f - shade, 0.5f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            core.setPresentationTime(window, elapsed)
            if (!core.swapBuffers(window)) {
                error("eglSwapBuffers gagal")
            }
            tick++
            frames += drain(mediaCodec, info, timeoutUs = 0, maxPtsUs = windowEndUs)
        }

        // Buang sisa frame yang masih di antrean encoder. Berhenti lebih awal
        // bila 300 ms tidak ada frame baru (antrean sudah kosong).
        val drainDeadline = System.nanoTime() + 1_000_000_000L
        var idleSinceNs = 0L
        while (System.nanoTime() < drainDeadline) {
            val added = drain(mediaCodec, info, timeoutUs = 10_000, maxPtsUs = windowEndUs)
            frames += added
            if (added > 0) {
                idleSinceNs = 0L
            } else {
                if (idleSinceNs == 0L) idleSinceNs = System.nanoTime()
                if (System.nanoTime() - idleSinceNs > 300_000_000L) break
            }
        }
        onProgress(0, 1f)
        return frames
    }

    private fun drain(
        mediaCodec: MediaCodec,
        info: MediaCodec.BufferInfo,
        timeoutUs: Long,
        maxPtsUs: Long = Long.MAX_VALUE,
    ): Int {
        var count = 0
        while (true) {
            val index = mediaCodec.dequeueOutputBuffer(info, timeoutUs)
            when {
                index >= 0 -> {
                    mediaCodec.releaseOutputBuffer(index, false)
                    if (info.presentationTimeUs < maxPtsUs) count++
                }

                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                else -> return count
            }
        }
    }

    private const val TAG = "RoutAirBench"

    /** Interval pembaruan countdown di layar. */
    private const val REPORT_INTERVAL_NS = 100_000_000L
}
