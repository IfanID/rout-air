package com.rout.air.data

import android.content.Context
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

    /** Dipanggil saat aplikasi dibuka: kosongkan buffer dan timpa berkas. */
    fun startSession(context: Context) {
        synchronized(lock) {
            buffer.setLength(0)
            val dir = File(context.getExternalFilesDir(null), "log").apply { mkdirs() }
            file = File(dir, FILE_NAME)
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            runCatching {
                file?.writeText(
                    "RoutAir — log sesi\n" +
                        "Dibuka : $stamp\n" +
                        "=".repeat(48) + "\n\n",
                )
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
                target.writeText(
                    "RoutAir — log sesi\n" +
                        "=".repeat(48) + "\n\n" +
                        buffer,
                )
            }.onFailure { Log.w(TAG, "Gagal menulis log: ${it.message}") }
        }
    }

    /** Nama lama; sekarang hanya menulis ke berkas tunggal. */
    fun writeToDownloads(context: Context, name: String = FILE_NAME) {
        flush(context)
    }

    private const val FILE_NAME = "RoutAir_log.txt"
    private const val TAG = "RoutAirDiag"
}
