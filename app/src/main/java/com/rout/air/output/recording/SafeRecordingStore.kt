package com.rout.air.output.recording

import android.content.Context
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.util.Log
import com.rout.air.core.model.CaptureConfig
import com.rout.air.core.model.SaveLocation
import com.rout.air.encode.mux.CacheFileTarget
import com.rout.air.encode.mux.Mp4Concat
import com.rout.air.encode.video.OutputTarget
import java.io.File
import java.util.Properties

/**
 * Penyimpanan sementara rekaman aman.
 *
 * Selama merekam, video ditulis sebagai segmen MP4 yang masing-masing sudah
 * difinalisasi ke folder aplikasi, bukan langsung ke galeri. Bila proses mati,
 * segmen yang sudah selesai tetap utuh dan bisa digabung ke galeri saat
 * aplikasi dibuka lagi. Setelah berhenti normal, semuanya digabung menjadi
 * satu berkas dan folder sesi dihapus.
 */
object SafeRecordingStore {
    private const val TAG = "RoutAirSafeRec"
    private const val META_FILE = "meta.properties"
    private const val SEGMENT_PREFIX = "seg_"
    /** Berisi PID pemilik; sesi dengan PID yang masih hidup sedang direkam. */
    private const val ACTIVE_MARKER = "active.marker"
    /** Berisi PID yang sedang menggabungkan segmen. */
    private const val BUSY_MARKER = "busy.marker"

    data class Meta(
        val name: String,
        val location: SaveLocation,
        val startedAtMs: Long,
        val template: String,
        val noSpaceFileName: Boolean,
        /** Panjang tiap segmen; dipakai untuk memperkirakan durasi. */
        val segmentMinutes: Int = 5,
    )

    data class Session(val directory: File, val meta: Meta) {
        val segments: List<File>
            get() = directory.listFiles { file ->
                file.isFile && file.name.startsWith(SEGMENT_PREFIX) && file.name.endsWith(".mp4")
            }?.sortedBy { it.name }.orEmpty()

        val sizeBytes: Long get() = segments.sumOf { it.length() }
    }

    fun root(context: Context): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: context.filesDir
        return File(base, "pending")
    }

    /** Membuat folder sesi baru; segmen pertama ditulis oleh pemanggil. */
    fun begin(context: Context, config: CaptureConfig, nowMs: Long = System.currentTimeMillis()): Session {
        val root = root(context)
        root.mkdirs()
        // Nama folder unik walau dua sesi dimulai pada detik yang sama.
        var directory = File(root, "rec_$nowMs")
        var suffix = 1
        while (directory.exists()) {
            directory = File(root, "rec_${nowMs}_${suffix++}")
        }
        directory.mkdirs()
        val meta = Meta(
            name = OutputTarget.buildName(nowMs, config.fileNameTemplate, config.noSpaceFileName),
            location = config.saveLocation,
            startedAtMs = nowMs,
            template = config.fileNameTemplate,
            noSpaceFileName = config.noSpaceFileName,
            segmentMinutes = config.safeSegmentMinutes.coerceIn(1, 30),
        )
        writeMeta(directory, meta)
        // Penanda pemilik: pemindaian berikutnya tidak menawarkan sesi yang
        // masih ditulis proses ini sebagai rekaman yang bisa dipulihkan.
        writeMarker(directory, ACTIVE_MARKER)
        return Session(directory, meta)
    }

    /** Tujuan segmen ke-[part]; 1 untuk segmen pertama. */
    fun segmentTarget(session: Session, part: Int): CacheFileTarget =
        CacheFileTarget(File(session.directory, "%s%04d.mp4".format(SEGMENT_PREFIX, part)))

    /** Menandai sesi selesai ditulis sehingga aman ditawarkan untuk dipulihkan. */
    fun endActive(session: Session) {
        runCatching { File(session.directory, ACTIVE_MARKER).delete() }
    }

    /**
     * Semua sesi yang menyisakan segmen, biasanya karena proses mati.
     * Sesi yang masih direkam proses ini (penanda PID sama) dan sesi yang
     * sedang digabungkan dilewati.
     */
    fun pending(context: Context): List<Session> {
        val root = root(context)
        val dirs = root.listFiles { file -> file.isDirectory } ?: return emptyList()
        val myPid = android.os.Process.myPid().toString()
        return dirs.mapNotNull { directory ->
            val session = Session(directory, readMeta(directory))
            if (session.segments.isEmpty()) {
                // Folder kosong / sisa sesi yang sudah digabung.
                runCatching { directory.deleteRecursively() }
                return@mapNotNull null
            }
            val owner = readMarker(directory, ACTIVE_MARKER)
            if (owner == myPid) return@mapNotNull null
            // Penanda dari proses lain berarti prosesnya sudah mati.
            if (owner != null) runCatching { File(directory, ACTIVE_MARKER).delete() }
            val busyPid = readMarker(directory, BUSY_MARKER)
            if (busyPid == myPid) return@mapNotNull null
            if (busyPid != null) runCatching { File(directory, BUSY_MARKER).delete() }
            session
        }.sortedBy { it.meta.startedAtMs }
    }

    /**
     * Menggabungkan seluruh segmen sesi menjadi satu MP4 di galeri.
     * Folder sesi dihapus hanya bila penggabungan berhasil.
     */
    fun merge(context: Context, session: Session): Uri? {
        val segments = session.segments
        if (segments.isEmpty()) return null
        writeMarker(session.directory, BUSY_MARKER)
        try {
            return mergeLocked(context, session, segments)
        } finally {
            runCatching { File(session.directory, BUSY_MARKER).delete() }
        }
    }

    private fun mergeLocked(
        context: Context,
        session: Session,
        segments: List<File>,
    ): Uri? {
        val target = runCatching {
            OutputTarget.create(
                context = context,
                location = session.meta.location,
                nameTemplate = session.meta.template,
                noSpaceFileName = session.meta.noSpaceFileName,
                baseName = session.meta.name,
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
            success = Mp4Concat.concat(segments, muxer)
        } catch (error: Throwable) {
            Log.w(TAG, "Gagal menggabungkan segmen: ${error.message}")
        } finally {
            runCatching { if (success) muxer.stop() }
            runCatching { muxer.release() }
        }
        target.finish(success)
        if (success) {
            runCatching { session.directory.deleteRecursively() }
            return target.uri
        }
        return null
    }

    fun discard(session: Session) {
        runCatching { session.directory.deleteRecursively() }
    }

    private fun writeMarker(directory: File, name: String) {
        runCatching {
            File(directory, name).writeText(android.os.Process.myPid().toString())
        }
    }

    private fun readMarker(directory: File, name: String): String? = runCatching {
        File(directory, name).takeIf { it.exists() }?.readText()?.trim()
    }.getOrNull()

    private fun writeMeta(directory: File, meta: Meta) {
        val properties = Properties().apply {
            setProperty("name", meta.name)
            setProperty("location", meta.location.name)
            setProperty("started", meta.startedAtMs.toString())
            setProperty("template", meta.template)
            setProperty("noSpace", meta.noSpaceFileName.toString())
            setProperty("segmentMinutes", meta.segmentMinutes.toString())
        }
        runCatching {
            File(directory, META_FILE).outputStream().use { properties.store(it, null) }
        }.onFailure { Log.w(TAG, "Meta sesi gagal ditulis: ${it.message}") }
    }

    private fun readMeta(directory: File): Meta {
        val properties = Properties()
        runCatching {
            File(directory, META_FILE).inputStream().use { properties.load(it) }
        }
        val fallbackStamp = directory.name.removePrefix("rec_").substringBefore('_')
        return Meta(
            name = properties.getProperty("name")
                ?: "RoutAir_${fallbackStamp.ifBlank { "recovered" }}",
            location = runCatching {
                SaveLocation.valueOf(properties.getProperty("location") ?: "")
            }.getOrDefault(SaveLocation.MoviesRoutAir),
            startedAtMs = properties.getProperty("started")?.toLongOrNull() ?: 0L,
            template = properties.getProperty("template") ?: "RoutAir_{date}_{time}",
            noSpaceFileName = properties.getProperty("noSpace").toBoolean(),
            segmentMinutes = properties.getProperty("segmentMinutes")?.toIntOrNull() ?: 5,
        )
    }
}
