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
 *
 * Bila [segment] diisi, penulis memutar berkas baru setiap kali batas durasi
 * atau ukuran tercapai. Dipakai split recording (part 2, 3, …) dan replay
 * buffer (segmen pendek yang dibuang setelah kedaluwarsa).
 */
class Mp4Writer(
    target: MuxTarget,
    private val segment: SegmentSpec? = null,
) : MediaSink {
    /**
     * Aturan pemutaran segmen. [next] dipanggil untuk membuat berkas segmen
     * berikutnya; mengembalikan null berarti berhenti memutar (tulis ke
     * berkas terakhir saja).
     */
    data class SegmentSpec(
        val maxDurationUs: Long,
        val maxBytes: Long,
        val next: (part: Int) -> MuxTarget?,
        /** Dipanggil saat satu segmen selesai, dengan durasi sebenarnya. */
        val onSegmentFinished: ((target: MuxTarget, durationUs: Long) -> Unit)? = null,
    )

    private var muxer = MediaMuxer(
        target.fileDescriptor,
        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
    )

    private val lock = Any()
    private val pending = ArrayDeque<PendingSample>()
    private val targets = mutableListOf(target)

    /** Format track disimpan supaya muxer baru bisa langsung memakai track sama. */
    private var videoFormat: MediaFormat? = null
    private val audioFormats = LinkedHashMap<Int, MediaFormat>()

    private var videoTrack = -1
    private val audioTracks = HashMap<Int, Int>()
    private var expectedAudioTracks = 0
    private var started = false
    private var released = false

    private var part = 1
    private var segmentStartPtsUs = 0L
    private var segmentStartBytes = 0L
    private var segmentSpec = segment

    /** Saat output dimulai setelah live berjalan, MP4 tetap dimulai dari 0. */
    private var ptsOffsetUs: Long? = null

    /** Statistik live untuk halaman Siaran/Studio. */
    @Volatile
    var bytesWritten: Long = 0L
        private set

    @Volatile
    var writeFailures: Int = 0
        private set

    /** MediaMuxer menolak PTS yang tidak naik, jadi dijaga di sini. */
    private var lastPtsByTrack = HashMap<Int, Long>()

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

    override fun addVideoTrack(format: MediaFormat): Int = synchronized(lock) {
        if (videoTrack < 0) {
            videoFormat = format
            videoTrack = muxer.addTrack(format)
            maybeStartLocked()
        }
        videoTrack
    }

    /**
     * Menambahkan trek audio. [key] adalah nomor logis track (1..6) supaya
     * encoder bisa menulis ke trek yang benar saat multitrack aktif.
     */
    override fun addAudioTrack(key: Int, format: MediaFormat): Int = synchronized(lock) {
        audioTracks[key]?.let { return it }
        audioFormats[key] = format
        val index = muxer.addTrack(format)
        audioTracks[key] = index
        maybeStartLocked()
        index
    }

    override fun write(
        trackId: Int,
        data: ByteArray,
        presentationTimeUs: Long,
        flags: Int,
    ) {
        if (trackId < 0 || data.isEmpty()) return
        // Data csd sudah ikut di MediaFormat, jadi tidak perlu ditulis ulang.
        if (flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) return

        val sample = PendingSample(
            track = trackId,
            data = data,
            presentationTimeUs = presentationTimeUs,
            flags = flags and MediaCodec.BUFFER_FLAG_KEY_FRAME,
        )

        synchronized(lock) {
            if (released) return
            val offset = ptsOffsetUs ?: presentationTimeUs.also {
                ptsOffsetUs = it
                segmentStartPtsUs = it
            }
            val adjusted = sample.copy(
                presentationTimeUs = (sample.presentationTimeUs - offset).coerceAtLeast(0L),
            )
            if (!started) {
                if (pending.size < MAX_PENDING_SAMPLES) {
                    pending.addLast(adjusted)
                } else {
                    Log.w(TAG, "Sampel dibuang: muxer belum siap")
                }
                return
            }
            // Segmen baru hanya dimulai di keyframe supaya setiap berkas bisa
            // diputar sendiri tanpa menunggu frame sebelumnya.
            if (shouldRotateLocked(sample)) {
                rotateLocked(sample.presentationTimeUs)
            }
            writeLocked(adjusted)
        }
    }

    /** Menutup semua segmen sebagai berhasil. */
    override fun finish(): Boolean = synchronized(lock) {
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
        // Segmen sebelumnya sudah ditutup saat rotasi; hanya segmen terakhir
        // yang masih terbuka di sini.
        targets.lastOrNull()?.let { runCatching { it.finish(wroteData) } }
        wroteData
    }

    /** Membatalkan penulisan: semua part dihapus dari galeri. */
    fun abort() = synchronized(lock) {
        if (released) return@synchronized
        runCatching { muxer.release() }
        released = true
        pending.clear()
        targets.forEach { runCatching { it.finish(false) } }
    }

    private fun shouldRotateLocked(sample: PendingSample): Boolean {
        val spec = segmentSpec ?: return false
        if (!started || videoTrack < 0) return false
        if (sample.track != videoTrack) return false
        if (sample.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME == 0) return false
        val elapsed = sample.presentationTimeUs - segmentStartPtsUs
        val bytes = bytesWritten - segmentStartBytes
        val durationHit = spec.maxDurationUs > 0 && elapsed >= spec.maxDurationUs
        val sizeHit = spec.maxBytes > 0 && bytes >= spec.maxBytes
        return durationHit || sizeHit
    }

    /** Menutup muxer sekarang lalu membuka part berikutnya pada PTS tertentu. */
    private fun rotateLocked(nextPtsUs: Long) {
        val spec = segmentSpec ?: return
        // Siapkan berkas berikutnya dulu; kalau gagal, lanjutkan berkas ini.
        val nextTarget = runCatching { spec.next(part + 1) }.getOrNull() ?: run {
            segmentSpec = null
            return
        }
        val current = targets.last()
        val durationUs = (nextPtsUs - segmentStartPtsUs).coerceAtLeast(0L)
        val stopped = runCatching { muxer.stop() }.isSuccess
        runCatching { muxer.release() }

        if (stopped) {
            // Tutup dulu supaya segmen replay langsung utuh saat dibaca.
            current.finish(true)
            spec.onSegmentFinished?.invoke(current, durationUs)
        } else {
            Log.w(TAG, "Segmen ${current.displayName} gagal ditutup")
            current.finish(false)
        }

        part++
        val newMuxer = runCatching {
            MediaMuxer(
                nextTarget.fileDescriptor,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
            )
        }.getOrNull() ?: run {
            Log.e(TAG, "Muxer segmen baru gagal dibuat")
            nextTarget.finish(false)
            segmentSpec = null
            return
        }
        muxer = newMuxer
        targets += nextTarget
        videoTrack = videoFormat?.let { muxer.addTrack(it) } ?: -1
        audioTracks.clear()
        audioFormats.forEach { (key, format) ->
            audioTracks[key] = muxer.addTrack(format)
        }
        lastPtsByTrack = HashMap()
        ptsOffsetUs = nextPtsUs
        segmentStartPtsUs = nextPtsUs
        segmentStartBytes = bytesWritten
        started = false
        runCatching { muxer.start() }
            .onSuccess { started = true }
            .onFailure {
                Log.e(TAG, "Muxer segmen gagal start: ${it.message}")
                segmentSpec = null
            }
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
        const val MAX_PENDING_SAMPLES = 60
    }
}
