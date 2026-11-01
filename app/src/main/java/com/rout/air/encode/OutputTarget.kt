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
    val uri: Uri,
    private val descriptor: ParcelFileDescriptor,
) {
    val fileDescriptor get() = descriptor.fileDescriptor

    fun finish(context: Context, success: Boolean) {
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
        ): OutputTarget {
            val resolver = context.contentResolver
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(timestampMs))
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "RoutAir_$stamp.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, location.relativePath)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }

            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Tidak bisa membuat berkas di ${location.relativePath}")
            val descriptor = resolver.openFileDescriptor(uri, "w")
                ?: throw IOException("Tidak bisa membuka berkas tujuan")

            return OutputTarget(uri, descriptor)
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
