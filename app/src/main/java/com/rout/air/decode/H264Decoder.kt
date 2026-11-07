package com.rout.air.decode

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Dekoder H.264 (`MediaCodec`) yang menulis frame hasil decode ke [surface].
 *
 * Input berupa NAL unit Annex B (dengan start code) dari [queue]. Satu thread
 * internal mengurus input dan output agar MediaCodec tidak diakses dari banyak
 * thread sekaligus.
 */
class H264Decoder(
    private val surface: Surface,
    private val onSize: (width: Int, height: Int) -> Unit,
    private val onError: (message: String) -> Unit = {},
    private val onLog: (String) -> Unit = {},
) {
    private val codec: MediaCodec = MediaCodec.createDecoderByType(MIME)
    private val queue = LinkedBlockingQueue<ByteArray>(8)
    private val running = AtomicBoolean(true)
    private var thread: Thread? = null

    init {
        val format = MediaFormat.createVideoFormat(MIME, 1280, 720).apply {
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 shl 20)
        }
        codec.configure(format, surface, null, 0)
        codec.start()
        onLog("decoder ${codec.name} siap")
        thread = Thread({ loop() }, "rout-h264-decode").apply {
            isDaemon = true
            start()
        }
    }

    /** Antrekan satu NAL unit (Annex B). Frame lama dibuang bila antrean penuh. */
    fun queue(nal: ByteArray) {
        if (!running.get()) return
        queue.offer(nal)
    }

    private fun loop() {
        val info = MediaCodec.BufferInfo()
        var queued = 0L
        var decoded = 0L
        var lastStatsNs = System.nanoTime()
        while (running.get()) {
            try {
                val nal = queue.poll()
                if (nal != null) {
                    val index = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index)
                        if (buffer != null && buffer.capacity() >= nal.size) {
                            buffer.clear()
                            buffer.put(nal)
                            codec.queueInputBuffer(
                                index,
                                0,
                                nal.size,
                                System.nanoTime() / 1_000,
                                0,
                            )
                            queued++
                            if (queued <= 3 || queued % 60L == 0L) {
                                onLog("input #$queued size=${nal.size}")
                            }
                        } else {
                            codec.queueInputBuffer(
                                index,
                                0,
                                0,
                                System.nanoTime() / 1_000,
                                0,
                            )
                        }
                    }
                }

                while (true) {
                    val out = codec.dequeueOutputBuffer(info, 0)
                    when {
                        out >= 0 -> {
                            codec.releaseOutputBuffer(out, true)
                            decoded++
                            if (decoded <= 3 || decoded % 60L == 0L) {
                                onLog("output #$decoded pts=${info.presentationTimeUs}")
                            }
                        }
                        out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val format = codec.outputFormat
                            val width = format.getInteger(MediaFormat.KEY_WIDTH)
                            val height = format.getInteger(MediaFormat.KEY_HEIGHT)
                            onLog("format ${width}x$height")
                            onSize(width, height)
                        }

                        else -> break
                    }
                }

                if (nal == null) Thread.sleep(2)
                val now = System.nanoTime()
                if (now - lastStatsNs >= 2_000_000_000L) {
                    lastStatsNs = now
                    onLog("decoded=$decoded antre=${queue.size}")
                }
            } catch (error: Throwable) {
                if (running.get()) {
                    onLog("error: ${error.javaClass.simpleName}: ${error.message}")
                    onError(error.message ?: "decode gagal")
                }
                break
            }
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        thread?.interrupt()
        runCatching { thread?.join(500) }
        thread = null
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }

    private companion object {
        const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        const val DEQUEUE_TIMEOUT_US = 10_000L
    }
}
