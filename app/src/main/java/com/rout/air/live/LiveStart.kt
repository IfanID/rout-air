package com.rout.air.live

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Hasil persiapan siaran: server dan kunci yang sudah siap dipakai RTMP. */
data class LiveTarget(
    val server: String,
    val key: String,
    val broadcastId: String,
)

/**
 * Membuat siaran YouTube lalu mengikatnya ke stream milik akun.
 *
 * Dipakai dialog dalam aplikasi maupun dialog melayang di overlay supaya
 * keduanya memakai jalur yang sama.
 */
suspend fun prepareYouTubeBroadcast(
    context: Context,
    title: String,
    description: String,
    privacy: String,
): Result<LiveTarget> = withContext(Dispatchers.IO) {
    val app = context.applicationContext
    val token = GoogleAuthStore(app).freshAccessToken()
    if (token.isNullOrBlank()) {
        return@withContext Result.failure(
            IllegalStateException("Sesi Google berakhir. Hubungkan akun lagi di Setelan > Stream."),
        )
    }
    val api = YouTubeApi(token)
    val target = runCatching { api.streamTarget() }.getOrNull()
        ?: return@withContext Result.failure(
            IllegalStateException("Stream key tidak ditemukan di akun ini."),
        )
    val broadcastId = runCatching { api.createBroadcast(title, description, privacy) }
        .getOrNull()
        ?: return@withContext Result.failure(
            IllegalStateException("Gagal membuat siaran. Periksa koneksi atau log."),
        )
    val bound = runCatching { api.bindBroadcast(broadcastId, target.streamId) }
        .getOrDefault(false)
    if (!bound) {
        return@withContext Result.failure(
            IllegalStateException("Gagal mengikat siaran ke stream."),
        )
    }
    Result.success(LiveTarget(target.server, target.key, broadcastId))
}
