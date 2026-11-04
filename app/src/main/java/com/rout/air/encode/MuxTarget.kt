package com.rout.air.encode

import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream

/**
 * Berkas tujuan `MediaMuxer`. Dipakai rekaman biasa ([OutputTarget] lewat
 * MediaStore) dan segmen replay buffer (berkas cache sementara).
 */
interface MuxTarget {
    val fileDescriptor: FileDescriptor

    /** Nama tampilan tanpa ekstensi, dipakai sebagai dasar nama part. */
    val displayName: String

    /** Menutup berkas; `success=false` menghapus berkas yang gagal ditulis. */
    fun finish(success: Boolean)
}

/**
 * Berkas sementara untuk segmen replay. Disimpan di cache aplikasi supaya
 * tidak perlu izin penyimpanan dan otomatis dibersihkan sistem.
 */
class CacheFileTarget(val file: File) : MuxTarget {
    private val output = FileOutputStream(file)

    override val fileDescriptor: FileDescriptor = output.fd

    override val displayName: String = file.nameWithoutExtension

    override fun finish(success: Boolean) {
        runCatching { output.close() }
        if (!success) runCatching { file.delete() }
    }
}
