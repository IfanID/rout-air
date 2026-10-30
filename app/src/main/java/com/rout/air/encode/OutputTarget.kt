package com.rout.air.encode

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
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
        fun create(context: Context, timestampMs: Long = System.currentTimeMillis()): OutputTarget {
            val resolver = context.contentResolver
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(timestampMs))
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "RoutAir_$stamp.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_MOVIES}/RoutAir",
                )
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }

            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Tidak bisa membuat berkas di Movies/RoutAir")
            val descriptor = resolver.openFileDescriptor(uri, "w")
                ?: throw IOException("Tidak bisa membuka berkas tujuan")

            return OutputTarget(uri, descriptor)
        }
    }
}
