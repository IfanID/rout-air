package com.rout.air.data.logging

import android.app.ActivityManager
import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Menyalin riwayat proses mati milik aplikasi ini ke `log/exit.txt`.
 *
 * Crash native (SIGSEGV/SIGABRT) tidak melewati penangan exception Java,
 * sehingga satu-satunya jejaknya ada di riwayat keluaran proses dari sistem.
 * Fungsi ini dipanggil saat aplikasi dibuka agar kegagalan sesi sebelumnya
 * tetap bisa dibaca dari penyimpanan.
 */
object ExitReasons {

    fun record(context: Context) {
        runCatching {
            val manager = context.getSystemService(ActivityManager::class.java) ?: return
            val reasons = manager.getHistoricalProcessExitReasons(
                context.packageName,
                0,
                MAX_ENTRIES,
            )
            if (reasons.isEmpty()) return
            val now = System.currentTimeMillis()
            val dir = File(context.getExternalFilesDir(null), "log").apply { mkdirs() }
            val file = File(dir, FILE_NAME)
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            val text = buildString {
                reasons
                    .filter { now - it.timestamp <= MAX_AGE_MS }
                    .forEach { info ->
                        append("=== ${stamp.format(Date(info.timestamp))} ===\n")
                        append(
                            "reason=${info.reason} status=${info.status} " +
                                "importance=${info.importance} " +
                                "rss=${info.rss / 1024}KB pss=${info.pss / 1024}KB\n",
                        )
                        append("description=${info.description}\n")
                        val trace = runCatching {
                            info.traceInputStream?.bufferedReader()?.use { it.readText() }
                        }.getOrNull()
                        if (!trace.isNullOrBlank()) {
                            append(trace.take(MAX_TRACE_CHARS)).append('\n')
                        }
                    }
            }
            if (text.isNotBlank()) file.appendText(text)
        }
    }

    private const val FILE_NAME = "exit.txt"
    private const val MAX_ENTRIES = 5
    private const val MAX_AGE_MS = 30 * 60 * 1000L
    private const val MAX_TRACE_CHARS = 16_000
}
