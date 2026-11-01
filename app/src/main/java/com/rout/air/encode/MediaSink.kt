package com.rout.air.encode

import android.media.MediaFormat

/**
 * Tujuan keluaran sampel encoder. Implementasi yang ada: [Mp4Writer] untuk
 * rekaman dan `RtmpSink` untuk live. Encoder hanya mengenal antarmuka ini,
 * sehingga satu encoder bisa dipakai untuk rekam + streaming bersamaan.
 *
 * [trackId] adalah id logis dari sink pemanggil, bukan indeks track muxer.
 */
interface MediaSink {
    fun addVideoTrack(format: MediaFormat): Int

    fun addAudioTrack(key: Int, format: MediaFormat): Int

    fun write(trackId: Int, data: ByteArray, presentationTimeUs: Long, flags: Int)

    fun finish(): Boolean
}
