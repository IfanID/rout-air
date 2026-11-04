package com.rout.air.encode

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import com.rout.air.core.SaveLocation
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tujuan penyimpanan rekaman memakai MediaStore, sehingga tidak perlu izin
 * storage pada minSdk 31 dan file langsung terlihat di galeri.
 *
 * File dibuat berstatus pending; statusnya dibuka saat rekaman selesai, atau
 * dihapus bila perekaman gagal supaya tidak menyisakan file rusak.
 */
class OutputTarget private constructor(
    private val context: Context,
    val uri: Uri,
    private val descriptor: ParcelFileDescriptor,
    override val displayName: String,
) : MuxTarget {
    override val fileDescriptor get() = descriptor.fileDescriptor

    /** Versi lama dengan context eksplisit; tetap dipakai pemanggil lain. */
    fun finish(context: Context, success: Boolean) = finish(success)

    override fun finish(success: Boolean) {
        try {
            descriptor.close()
        } catch (_: IOException) {
            // Deskriptor mungkin sudah ditutup oleh MediaMuxer.
        }

        if (success) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }
            context.contentResolver.update(uri, values, null, null)
        } else {
            context.contentResolver.delete(uri, null, null)
        }
    }

    companion object {
        private const val TAG = "RoutAirOutput"

        fun create(
            context: Context,
            location: SaveLocation = SaveLocation.MoviesRoutAir,
            timestampMs: Long = System.currentTimeMillis(),
            nameTemplate: String = "RoutAir_{date}_{time}",
            noSpaceFileName: Boolean = false,
            /** >1 dipakai split recording: nama diberi akhiran `_partN`. */
            part: Int = 1,
            /** Nama dasar part pertama, supaya semua part memakai cap waktu sama. */
            baseName: String? = null,
        ): OutputTarget {
            val resolver = context.contentResolver
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(timestampMs))
            val time = SimpleDateFormat("HH-mm-ss", Locale.US).format(Date(timestampMs))
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(timestampMs))
            var name = baseName ?: nameTemplate
                .replace("{date}", date)
                .replace("{time}", time)
                .replace("{datetime}", stamp)
                .ifBlank { "RoutAir_$stamp" }
            if (baseName == null &&
                !name.contains(stamp) &&
                !nameTemplate.contains("{date}") &&
                !nameTemplate.contains("{time}") &&
                !nameTemplate.contains("{datetime}")
            ) {
                name = "${name}_$stamp"
            }
            if (noSpaceFileName) name = name.replace(' ', '_')
            val finalName = if (part > 1) "${name}_part$part" else name
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "$finalName.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, location.relativePath)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }

            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException(
                    context.getString(
                        com.rout.air.R.string.error_create_file,
                        location.relativePath,
                    ),
                )
            val descriptor = resolver.openFileDescriptor(uri, "w")
                ?: throw IOException(context.getString(com.rout.air.R.string.error_open_file))

            return OutputTarget(context.applicationContext, uri, descriptor, finalName)
        }

        /**
         * Menghapus rekaman pending yang tertinggal dari sesi yang mati tanpa
         * sempat menutup file. Dipanggil sekali saat aplikasi dibuka.
         */
        fun cleanupOrphans(context: Context, nowMs: Long = System.currentTimeMillis()) {
            val resolver = context.contentResolver
            val projection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DATE_ADDED,
                MediaStore.Video.Media.IS_PENDING,
            )
            runCatching {
                resolver.query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    "${MediaStore.Video.Media.IS_PENDING}=1",
                    null,
                    null,
                )?.use { cursor ->
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                    val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                    val pendingColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.IS_PENDING)
                    while (cursor.moveToNext()) {
                        val dateAddedMs = cursor.getLong(dateColumn) * 1_000L
                        val pending = cursor.getInt(pendingColumn) != 0
                        if (!PendingRecordingPolicy.isStale(nowMs, dateAddedMs, pending)) continue
                        val id = cursor.getLong(idColumn)
                        val uri = Uri.withAppendedPath(
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                            id.toString(),
                        )
                        resolver.delete(uri, null, null)
                    }
                }
            }.onFailure { Log.w(TAG, "Pembersihan rekaman pending gagal: ${it.message}") }
        }
    }
}
