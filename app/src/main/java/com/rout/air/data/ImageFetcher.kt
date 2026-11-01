package com.rout.air.data

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Mengunduh gambar dari internet untuk dipakai sebagai sumber Gambar.
 *
 * Hasil disimpan di penyimpanan aplikasi supaya path tetap valid setelah
 * proses mati, sama seperti gambar dari galeri yang disalin lebih dulu.
 */
object ImageFetcher {
    private const val MAX_BYTES = 10L * 1024 * 1024

    fun download(context: Context, rawUrl: String): Result<String> {
        val url = rawUrl.trim()
        val parsed = runCatching { URL(url) }.getOrNull()
            ?: return Result.failure(IllegalArgumentException("URL tidak valid"))
        if (parsed.protocol != "http" && parsed.protocol != "https") {
            return Result.failure(IllegalArgumentException("Hanya http/https"))
        }
        val dir = File(context.filesDir, "images").apply { mkdirs() }
        val target = File(dir, "url-${System.currentTimeMillis()}.img")
        val connection = runCatching { parsed.openConnection() as HttpURLConnection }.getOrNull()
            ?: return Result.failure(IllegalStateException("Tidak bisa membuka koneksi"))
        return try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "RoutAir")
            val code = connection.responseCode
            if (code !in 200..299) {
                return Result.failure(IllegalStateException("HTTP $code"))
            }
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        total += read
                        if (total > MAX_BYTES) {
                            return Result.failure(
                                IllegalStateException("Ukuran melebihi 10 MB"),
                            )
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }
            Result.success(target.absolutePath)
        } catch (error: Exception) {
            target.delete()
            Result.failure(error)
        } finally {
            connection.disconnect()
        }
    }
}
