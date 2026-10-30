package com.rout.air.data

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore

/**
 * Catatan diagnostik ringan yang ditulis ke berkas setelah rekaman selesai.
 * Dipakai karena logcat tidak bisa diambil di lingkungan pengembangan ini.
 */
object DiagLog {
    private val buffer = StringBuilder()
    private val lock = Any()

    fun reset() {
        synchronized(lock) { buffer.setLength(0) }
    }

    fun line(message: String) {
        synchronized(lock) {
            if (buffer.length > 64_000) return
            buffer.append(message).append('\n')
        }
    }

    fun writeToDownloads(context: Context, name: String) {
        val text = synchronized(lock) { buffer.toString() }
        if (text.isEmpty()) return

        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    "${Environment.DIRECTORY_DOWNLOADS}/RoutAir",
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return
            resolver.openOutputStream(uri, "w")?.use { it.write(text.toByteArray()) }
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
        }
    }
}
