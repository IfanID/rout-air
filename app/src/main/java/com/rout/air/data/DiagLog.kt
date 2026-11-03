package com.rout.air.data

import android.app.ActivityManager
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Satu berkas diagnostik per sesi aplikasi.
 *
 * Lokasi:
 * `<penyimpanan>/Android/data/com.rout.air/files/log/RoutAir_log.txt`
 *
 * Berkas ditimpa setiap aplikasi dibuka, jadi tidak pernah menumpuk.
 */
object DiagLog {
    private val buffer = StringBuilder()
    private val lock = Any()
    private var file: File? = null

    /** Preamble sesi (perangkat, Android, CPU, RAM) yang ikut di setiap flush. */
    private var header = ""

    /** Dipanggil saat aplikasi dibuka: kosongkan buffer dan timpa berkas. */
    fun startSession(context: Context) {
        synchronized(lock) {
            buffer.setLength(0)
            val dir = File(context.getExternalFilesDir(null), "log").apply { mkdirs() }
            file = File(dir, FILE_NAME)
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val memory = runCatching {
                val manager = context.getSystemService(ActivityManager::class.java)
                val info = ActivityManager.MemoryInfo()
                manager?.getMemoryInfo(info)
                (info.totalMem / (1024 * 1024))
            }.getOrDefault(0L)
            header = "RoutAir — log sesi\n" +
                "Dibuka : $stamp\n" +
                "Perangkat: ${Build.MANUFACTURER} ${Build.MODEL}\n" +
                "Android : ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
                "CPU     : ${Runtime.getRuntime().availableProcessors()} inti\n" +
                "RAM     : ${memory} MB\n" +
                "=".repeat(48) + "\n\n"
            runCatching {
                file?.writeText(header)
            }.onFailure { Log.w(TAG, "Gagal membuat log: ${it.message}") }
        }
    }

    fun reset() {
        synchronized(lock) { buffer.setLength(0) }
    }

    fun line(message: String) {
        synchronized(lock) {
            if (buffer.length > 64_000) return
            buffer.append(message).append('\n')
        }
    }

    /** Tulis ulang seluruh buffer ke satu berkas (truncate, bukan append). */
    fun flush(context: Context) {
        synchronized(lock) {
            val target = file ?: run {
                startSession(context)
                file
            } ?: return
            if (buffer.isEmpty()) return
            runCatching {
                target.writeText(header + buffer)
            }.onFailure { Log.w(TAG, "Gagal menulis log: ${it.message}") }
        }
    }

    /**
     * Menyalin log sesi ke Download/RoutAir/RoutAir_diag_<waktu>.txt.
     * Berkas di Android/data tidak terlihat dari pengelola berkas biasa,
     * jadi tombol diagnostik menyalinnya ke folder Download.
     *
     * @return path yang bisa dibaca pengguna, atau null bila gagal.
     */
    fun exportToDownloads(context: Context): String? {
        flush(context)
        val source = file ?: return null
        val text = runCatching { source.readText() }.getOrNull() ?: return null
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = "RoutAir_diag_$stamp.txt"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/RoutAir",
            )
        }
        val resolver = context.contentResolver
        val uri = runCatching {
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        }.getOrNull() ?: return null
        val wrote = runCatching {
            resolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) }
        }.isSuccess
        if (!wrote) {
            runCatching { resolver.delete(uri, null, null) }
            return null
        }
        return "Download/RoutAir/$name"
    }

    /** Nama lama; sekarang hanya menulis ke berkas tunggal. */
    fun writeToDownloads(context: Context, name: String = FILE_NAME) {
        flush(context)
    }

    private const val FILE_NAME = "RoutAir_log.txt"
    private const val TAG = "RoutAirDiag"
}
