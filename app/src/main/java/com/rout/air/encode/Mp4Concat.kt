package com.rout.air.encode

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * Menggabungkan beberapa berkas MP4 menjadi satu keluaran.
 *
 * Dipakai replay buffer (segmen 5 detik) dan rekaman aman (segmen yang sudah
 * difinalisasi). Setiap segmen mulai dari PTS 0, jadi diberi offset dan celah
 * kecil supaya timestamp tetap naik monoton di muxer tujuan.
 */
object Mp4Concat {
    private const val TAG = "RoutAirConcat"
    private const val BUFFER_BYTES = 1 shl 20
    private const val SAMPLE_FLAG_MASK =
        MediaCodec.BUFFER_FLAG_KEY_FRAME or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME

    /**
     * Menyalin semua track video/audio dari [sources] ke [muxer].
     * Mengembalikan true bila sedikitnya satu sampel berhasil ditulis.
     */
    fun concat(
        sources: List<File>,
        muxer: MediaMuxer,
        gapUs: Long = 10_000L,
    ): Boolean {
        val muxerTrackBySource = HashMap<Int, Int>()
        var started = false
        var trackCount = 0
        var timeOffsetUs = 0L
        val buffer = ByteBuffer.allocateDirect(BUFFER_BYTES)
        val info = MediaCodec.BufferInfo()

        for (source in sources) {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(source.absolutePath)
                val trackIndexes = (0 until extractor.trackCount).filter { index ->
                    val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                    mime?.startsWith("video/") == true || mime?.startsWith("audio/") == true
                }
                if (trackIndexes.isEmpty()) continue

                if (!started) {
                    trackIndexes.forEach { index ->
                        muxerTrackBySource[index] = muxer.addTrack(extractor.getTrackFormat(index))
                        trackCount++
                    }
                    if (trackCount == 0) return false
                    muxer.start()
                    started = true
                }

                var maxEndUs = 0L
                trackIndexes.forEach { index ->
                    val muxerTrack = muxerTrackBySource[index] ?: return@forEach
                    extractor.selectTrack(index)
                    while (true) {
                        buffer.clear()
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) break
                        info.set(
                            0,
                            size,
                            extractor.sampleTime.coerceAtLeast(0L) + timeOffsetUs,
                            extractor.sampleFlags and SAMPLE_FLAG_MASK,
                        )
                        runCatching { muxer.writeSampleData(muxerTrack, buffer, info) }
                        maxEndUs = maxOf(maxEndUs, info.presentationTimeUs)
                        extractor.advance()
                    }
                    extractor.unselectTrack(index)
                }
                timeOffsetUs = maxEndUs + gapUs
            } catch (error: Throwable) {
                Log.w(TAG, "Segmen ${source.name} dilewati: ${error.message}")
            } finally {
                runCatching { extractor.release() }
            }
        }
        return started
    }
}
