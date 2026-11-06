package com.rout.air.live.probe

import com.rout.air.core.model.LiveConfig
import com.rout.air.core.model.VideoCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import com.rout.air.live.format.FlvCodec
import com.rout.air.live.rtmp.RtmpConnection
import com.rout.air.live.rtmp.RtmpEndpoint

/**
 * Tes kecepatan upload seperti OBS: benar-benar publish ke server live
 * selama beberapa detik, lalu ukur bitrate yang berhasil terkirim.
 *
 * Mirip `AutoConfigTestPage.cpp`: 2,5 detik pertama diabaikan karena
 * buffering, dan bila bitrate terukur di bawah 75% target, hasilnya
 * diturunkan 30% supaya tidak memaksa koneksi.
 */
object UploadProbe {

    data class Result(
        /** Bitrate yang disarankan untuk dipakai (kbps). */
        val recommendedKbps: Int,
        val measuredKbps: Int,
        val stable: Boolean,
        val note: String? = null,
    )

    private const val FPS = 30
    private const val WARMUP_SECONDS = 2.5

    suspend fun measure(
        config: LiveConfig,
        targetKbps: Int,
        seconds: Int = 10,
    ): Result = withContext(Dispatchers.IO) {
        if (!config.isConfigured) {
            return@withContext Result(0, 0, stable = false, note = com.rout.air.data.common.Msg.get(com.rout.air.R.string.error_stream_key_empty))
        }
        val endpoint = runCatching { RtmpEndpoint.parse(config.serverUrl, config.streamKey) }
            .getOrElse { return@withContext Result(0, 0, false, it.message ?: com.rout.air.data.common.Msg.get(com.rout.air.R.string.error_url_invalid)) }

        val connection = RtmpConnection(endpoint, config.username, config.password) { }
        try {
            if (!connection.connect(12_000L)) {
                return@withContext Result(
                    0,
                    0,
                    false,
                    com.rout.air.data.common.Msg.get(com.rout.air.R.string.error_server_connect),
                )
            }

            // Sequence header minimal agar server mengenali stream video.
            val sps = byteArrayOf(0x67, 0x42, 0xC0.toByte(), 0x1F, 0x11, 0x22)
            val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x33, 0x44)
            connection.sendVideo(
                0,
                FlvCodec.videoSequenceHeader(
                    VideoCodec.AVC,
                    FlvCodec.avcDecoderConfigurationRecord(sps, pps),
                ),
            )

            val frameBytes = (targetKbps * 1_000L / 8 / FPS).toInt().coerceAtLeast(512)
            val payload = FlvCodec.videoFrame(
                VideoCodec.AVC,
                ByteArray(frameBytes) { 0x41 },
                keyframe = false,
            )
            val frameIntervalNs = 1_000_000_000L / FPS

            val cancelled = AtomicBoolean(false)
            val startNs = System.nanoTime()
            var nextFrameNs = startNs
            var measuredBytes = 0L
            var measuredStartNs = 0L
            var framesSent = 0

            while (!cancelled.get()) {
                val now = System.nanoTime()
                val elapsed = now - startNs
                if (elapsed >= (seconds + WARMUP_SECONDS.toInt()) * 1_000_000_000L) break
                if (now < nextFrameNs) sleepUntil(nextFrameNs)

                val timestampMs = (elapsed / 1_000_000L).toInt()
                connection.sendVideo(timestampMs, payload)
                framesSent++

                if (elapsed >= (WARMUP_SECONDS * 1_000_000_000L).toLong()) {
                    if (measuredStartNs == 0L) measuredStartNs = System.nanoTime()
                    measuredBytes += payload.size
                }
                nextFrameNs += frameIntervalNs
            }

            val measuredNs = (System.nanoTime() - measuredStartNs).coerceAtLeast(1L)
            val kbps = (measuredBytes * 8L * 1_000_000L / measuredNs).toInt()
            val stable = framesSent > 0 && kbps >= targetKbps * 75 / 100
            val recommended = when {
                framesSent == 0 -> 0
                stable -> targetKbps
                else -> (kbps * 70 / 100).coerceAtLeast(500)
            }
            Result(
                recommendedKbps = recommended,
                measuredKbps = kbps,
                stable = stable,
                note = when {
                    framesSent == 0 -> "Tidak ada data terkirim"
                    !stable -> com.rout.air.data.common.Msg.get(com.rout.air.R.string.error_upload_unstable)
                    else -> null
                },
            )
        } catch (error: Throwable) {
            Result(0, 0, false, error.message ?: "Tes upload gagal")
        } finally {
            runCatching { connection.close() }
        }
    }

    private fun sleepUntil(deadlineNs: Long) {
        while (true) {
            val remaining = deadlineNs - System.nanoTime()
            if (remaining <= 0) return
            if (remaining > 2_000_000L) {
                Thread.sleep((remaining / 1_000_000L) - 1)
            } else {
                Thread.yield()
            }
        }
    }
}
