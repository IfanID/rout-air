package com.rout.air.output

/**
 * Pemeriksaan ruang penyimpanan sebelum rekaman dimulai.
 *
 * Batas minimal 200 MB memberi ruang untuk menutup file MP4 dengan benar;
 * tanpa itu MediaMuxer bisa gagal di tengah dan menyisakan file rusak.
 */
object StorageGuard {
    const val MIN_FREE_BYTES = 200L * 1024L * 1024L

    fun hasRoom(freeBytes: Long, minimum: Long = MIN_FREE_BYTES): Boolean =
        freeBytes >= minimum
}
