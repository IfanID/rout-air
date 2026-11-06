package com.rout.air.output.replay

import android.content.Context
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import com.rout.air.core.model.SaveLocation
import com.rout.air.encode.mux.CacheFileTarget
import com.rout.air.encode.mux.MediaSink
import com.rout.air.encode.mux.Mp4Writer
import com.rout.air.encode.video.OutputTarget
import java.io.File
import java.util.ArrayDeque
import com.rout.air.encode.mux.TeeSink

/**
 * Replay buffer: menulis segmen MP4 pendek ke cache selama merekam, membuang
 * yang paling lama, lalu menggabungkan segmen terakhir menjadi satu berkas
 * saat pengguna menekan Simpan replay.
 *
 * Segmentasi memakai [Mp4Writer.SegmentSpec] yang sama dengan split
 * recording, jadi tiap segmen selalu dimulai dari keyframe.
 */
class ReplayBuffer(
    private val context: Context,
    private val directory: File,
    targetDurationUs: Long,
    segmentDurationUs: Long = SEGMENT_US,
) {
    private data class Segment(val file: File, val durationUs: Long)

    private val lock = Any()
    private val segments = ArrayDeque<Segment>()
    /** Berkas yang sedang dibaca proses simpan; jangan dihapus dulu. */
    private val inUse = HashSet<File>()
    private val targetDurationUs = targetDurationUs.coerceAtLeast(segmentDurationUs)

    private var counter = 0
    private val writer: Mp4Writer

    init {
        directory.mkdirs()
        // Sisa sesi sebelumnya tidak berguna dan bisa memakan penyimpanan.
        directory.listFiles()?.forEach { runCatching { it.delete() } }
        writer = Mp4Writer(
            newTarget(),
            Mp4Writer.SegmentSpec(
                maxDurationUs = segmentDurationUs,
                maxBytes = 0L,
                next = { newTarget() },
                onSegmentFinished = { _, durationUs ->
                    // Target yang selesai adalah berkas terakhir yang dibuat
                    // sebelum rotasi; rotasi selalu memanggil next() dulu.
                    addSegment(previousFile(), durationUs)
                },
            ),
        )
    }

    /** Sink yang dipasang ke TeeSink bersama keluaran rekaman utama. */
    fun sink(): MediaSink = writer

    /** Harus dipanggil sebelum format track pertama dikirim. */
    fun expectTracks(audioTrackCount: Int) {
        writer.expectTracks(videoTracks = 1, audioTrackCount = audioTrackCount)
    }

    /** Durasi segmen yang sudah tertahan; dipakai tombol untuk status. */
    fun availableMs(): Long = synchronized(lock) {
        segments.sumOf { it.durationUs } / 1_000L
    }

    /**
     * Menggabungkan semua segmen menjadi berkas MP4 di galeri. Dipanggil di
     * thread terpisah karena membaca dan menulis ulang seluruh sampel.
     */
    fun save(
        location: SaveLocation,
        nameTemplate: String,
        noSpaceFileName: Boolean,
    ): Uri? {
        // Snapshot diambil cepat supaya penulisan segmen baru tidak tertahan
        // selama proses gabung berjalan.
        val snapshot = synchronized(lock) {
            val copy = segments.toList()
            inUse += copy.map { it.file }
            copy
        }
        if (snapshot.isEmpty()) return null
        try {
            return saveSnapshot(snapshot, location, nameTemplate, noSpaceFileName)
        } finally {
            synchronized(lock) { inUse -= snapshot.map { it.file }.toSet() }
        }
    }

    private fun saveSnapshot(
        snapshot: List<Segment>,
        location: SaveLocation,
        nameTemplate: String,
        noSpaceFileName: Boolean,
    ): Uri? {
        val target = runCatching {
            OutputTarget.create(
                context = context,
                location = location,
                nameTemplate = "${nameTemplate}_replay",
                noSpaceFileName = noSpaceFileName,
            )
        }.getOrNull() ?: return null

        val muxer = runCatching {
            MediaMuxer(target.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        }.getOrNull() ?: run {
            target.finish(false)
            return null
        }

        var success = false
        try {
            success = com.rout.air.encode.mux.Mp4Concat.concat(snapshot.map { it.file }, muxer)
        } catch (error: Throwable) {
            Log.w(TAG, "Gagal menggabungkan replay: ${error.message}")
        } finally {
            runCatching { if (success) muxer.stop() }
            runCatching { muxer.release() }
        }
        target.finish(success)
        return if (success) target.uri else null
    }

    private fun addSegment(file: File, durationUs: Long) {
        synchronized(lock) {
            segments.addLast(Segment(file, durationUs))
            // Buang segmen tertua sampai total muat dalam durasi target.
            while (segments.size > 1 &&
                segments.sumOf { it.durationUs } > targetDurationUs
            ) {
                val oldest = segments.first()
                if (oldest.file in inUse) break
                segments.removeFirst()
                runCatching { oldest.file.delete() }
            }
        }
    }

    /** Berhenti menulis; semua segmen dihapus. */
    fun release() {
        runCatching { writer.abort() }
        synchronized(lock) {
            segments.forEach { runCatching { it.file.delete() } }
            segments.clear()
        }
        directory.listFiles()?.forEach { runCatching { it.delete() } }
    }

    private fun newTarget(): CacheFileTarget {
        counter++
        return CacheFileTarget(File(directory, "seg_${counter}.mp4"))
    }

    private fun previousFile(): File = File(directory, "seg_${counter - 1}.mp4")

    private companion object {
        const val TAG = "RoutAirReplay"
        const val SEGMENT_US = 5_000_000L
    }
}
