package com.rout.air

import android.app.Application
import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import com.rout.air.encode.OutputTarget

/** Application yang mencatat crash ke Download/RoutAir/RoutAir_crash.txt. */
class RoutAirApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { writeCrash(error) }
            previous?.uncaughtException(thread, error)
        }
        // File pending dari sesi yang mati tanpa menutup MP4 tidak terlihat di
        // galeri; bersihkan di latar belakang agar tidak menumpuk.
        Thread({ runCatching { OutputTarget.cleanupOrphans(this) } }, "rout-cleanup").start()
    }

    private fun writeCrash(error: Throwable) {
        val text = buildString {
            append("RoutAir crash\n")
            append(error.stackTraceToString())
            append("\n\nPenyebab:\n")
            var cause = error.cause
            while (cause != null) {
                append(cause.stackTraceToString()).append('\n')
                cause = cause.cause
            }
        }
        val resolver = contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "RoutAir_crash.txt")
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/RoutAir",
            )
        }
        val uri = resolver.insert(collection, values) ?: return
        resolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) }
    }
}
