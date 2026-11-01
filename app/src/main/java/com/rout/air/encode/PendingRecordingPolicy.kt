package com.rout.air.encode

/**
 * Aturan pembersihan file rekaman yang tertinggal dalam status pending.
 *
 * File pending dibuat MediaStore tetapi belum ditutup (mis. proses aplikasi
 * dibunuh saat merekam). File seperti ini tidak terlihat di galeri dan tidak
 * bisa diputar; dibersihkan saat aplikasi dibuka kembali.
 */
object PendingRecordingPolicy {
    const val MAX_AGE_MS = 60L * 60L * 1000L

    fun isStale(nowMs: Long, dateAddedMs: Long, pending: Boolean): Boolean {
        if (!pending) return false
        // dateAdded bisa 0 pada sebagian perangkat; jangan hapus tanpa bukti.
        if (dateAddedMs <= 0L) return false
        return nowMs - dateAddedMs > MAX_AGE_MS
    }
}
