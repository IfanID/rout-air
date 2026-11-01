package com.rout.air.data

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Menyimpan jejak crash ke `log/crash.txt` sebelum proses dimatikan.
 *
 * Tanpa ini, penyebab crash hilang bersama proses dan hanya terlihat lewat
 * logcat yang tidak selalu tersedia di perangkat pengguna.
 */
object CrashLog {
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val dir = File(app.getExternalFilesDir(null), "log").apply { mkdirs() }
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                File(dir, FILE_NAME).appendText(
                    "\n=== $stamp thread=${thread.name} ===\n" +
                        Log.getStackTraceString(error) + "\n",
                )
                // Simpan juga isi log sesi yang belum sempat ditulis.
                DiagLog.flush(app)
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private const val FILE_NAME = "crash.txt"
}
