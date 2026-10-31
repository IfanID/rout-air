package com.rout.air.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Catatan diagnostik ringan setelah rekaman selesai.
 *
 * Selalu menulis ke satu berkas `Download/RoutAir/RoutAir_log.txt` (ditimpa
 * tiap sesi) supaya tidak menumpuk banyak file .txt.
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

    fun writeToDownloads(context: Context, name: String = FILE_NAME) {
        val body = synchronized(lock) { buffer.toString() }
        if (body.isEmpty()) return

        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val text = buildString {
            append("RoutAir — log sesi\n")
            append("Waktu  : ").append(stamp).append('\n')
            append("Berkas : ").append(name).append('\n')
            append("=".repeat(48)).append("\n\n")
            append(body)
        }

        runCatching {
            val resolver = context.contentResolver
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val existing = findExisting(resolver, collection, name)
            if (existing != null) {
                resolver.openOutputStream(existing, "wt")?.use { it.write(text.toByteArray()) }
                return@runCatching
            }

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    "${Environment.DIRECTORY_DOWNLOADS}/RoutAir",
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values) ?: return@runCatching
            resolver.openOutputStream(uri, "w")?.use { it.write(text.toByteArray()) }
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
        }
    }

    /** Mencari berkas log yang sudah ada agar bisa ditimpa, bukan diduplikasi. */
    private fun findExisting(
        resolver: android.content.ContentResolver,
        collection: Uri,
        name: String,
    ): Uri? {
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
        resolver.query(collection, projection, selection, arrayOf(name), null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(0)
                return ContentUris.withAppendedId(collection, id)
            }
        }
        return null
    }

    private const val FILE_NAME = "RoutAir_log.txt"
}
