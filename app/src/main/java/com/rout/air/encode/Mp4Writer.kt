package com.rout.air.encode

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.nio.ByteBuffer

/**
 * Penulis MP4 yang menerima sampel dari encoder video dan audio.
 *
 * MediaMuxer mensyaratkan semua track sudah ditambahkan sebelum start(),
 * sedangkan format keluar dari encoder datang asinkron dan bisa berbeda waktu.
 * Karena itu sampel yang datang terlalu awal disimpan sementara, lalu
 * ditulis berurutan setelah muxer mulai.
 */
class Mp4Writer(private val target: OutputTarget) {
    private val muxer = MediaMuxer(
        target.fileDescriptor,
        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
    )

    private val lock = Any()
    private val pending = ArrayDeque<PendingSample>()

    private var videoTrack = -1
    private val audioTracks = HashMap<Int, Int>()
    private var expectedAudioTracks = 0
    private var started = false
    private var released = false

    /** Statistik live untuk halaman Siaran/Studio. */
    @Volatile
    var bytesWritten: Long = 0L
        private set

    @Volatile
    var writeFailures: Int = 0
        private set

    /** MediaMuxer menolak PTS yang tidak naik, jadi dijaga di sini. */
    private val lastPtsByTrack = HashMap<Int, Long>()

    private data class PendingSample(
        val track: Int,
        val data: ByteArray,
        val presentationTimeUs: Long,
        val flags: Int,
    )

    /** Harus dipanggil sebelum encoder mana pun mulai mengirim format. */
    fun expectTracks(videoTracks: Int = 1, audioTrackCount: Int = 0) {
        synchronized(lock) {
            expectedAudioTracks = audioTrackCount
            maybeStartLocked()
        }
    }

    fun addVideoTrack(format: MediaFormat): Int = synchronized(lock) {
        if (videoTrack < 0) {
            videoTrack = muxer.addTrack(format)
            maybeStartLocked()
        }
        videoTrack
    }

    /**
     * Menambahkan trek audio. [key] adalah nomor logis track (1..6) supaya
     * encoder bisa menulis ke trek yang benar saat multitrack aktif.
     */
    fun addAudioTrack(key: Int, format: MediaFormat): Int = synchronized(lock) {
        audioTracks[key]?.let { return it }
        val index = muxer.addTrack(format)
        audioTracks[key] = index
        maybeStartLocked()
        index
    }

    fun write(track: Int, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (track < 0 || info.size <= 0) return
        // Data csd sudah ikut di MediaFormat, jadi tidak perlu ditulis ulang.
        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) return

        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        val copy = ByteArray(info.size)
        buffer.get(copy)

        val sample = PendingSample(
            track = track,
            data = copy,
            presentationTimeUs = info.presentationTimeUs,
            flags = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME,
        )

        synchronized(lock) {
            if (released) return
            if (!started) {
                if (pending.size < MAX_PENDING_SAMPLES) {
                    pending.addLast(sample)
                } else {
                    Log.w(TAG, "Sampel dibuang: muxer belum siap")
                }
                return
            }
            writeLocked(sample)
        }
    }

    fun finish(): Boolean = synchronized(lock) {
        if (released) return false
        var wroteData = started
        if (started) {
            try {
                muxer.stop()
            } catch (error: IllegalStateException) {
                Log.w(TAG, "muxer.stop gagal: ${error.message}")
                wroteData = false
            }
        }
        muxer.release()
        released = true
        pending.clear()
        wroteData
    }

    private fun maybeStartLocked() {
        if (started || released) return
        val videoReady = videoTrack >= 0
        val audioReady = audioTracks.size >= expectedAudioTracks
        val ready = videoReady && audioReady
        if (!ready) return

        try {
            muxer.start()
            started = true
        } catch (error: IllegalStateException) {
            Log.e(TAG, "muxer.start gagal: ${error.message}")
            return
        }

        while (pending.isNotEmpty()) {
            writeLocked(pending.removeFirst())
        }
    }

    private fun writeLocked(sample: PendingSample) {
        val previous = lastPtsByTrack[sample.track]
        if (previous != null && sample.presentationTimeUs <= previous) {
            Log.w(TAG, "Sampel dilewati: PTS ${sample.presentationTimeUs} <= $previous")
            return
        }

        val info = MediaCodec.BufferInfo().apply {
            set(0, sample.data.size, sample.presentationTimeUs, sample.flags)
        }
        try {
            muxer.writeSampleData(sample.track, ByteBuffer.wrap(sample.data), info)
            lastPtsByTrack[sample.track] = sample.presentationTimeUs
            bytesWritten += sample.data.size
        } catch (error: IllegalStateException) {
            writeFailures++
            Log.w(TAG, "writeSampleData gagal: ${error.message}")
        }
    }

    private companion object {
        const val TAG = "RoutAirMp4"
        const val MAX_PENDING_SAMPLES = 120
    }
}
