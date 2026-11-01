package com.rout.air.live

import com.rout.air.core.LiveConfig

/** Hasil tes koneksi ke server RTMP (handshake + connect + publish). */
data class RtmpTestResult(
    val ok: Boolean,
    val latencyMs: Long,
    val message: String,
)

/**
 * Tes cepat ala tombol Bandwidth Test OBS: memastikan server, kunci stream,
 * dan autentikasi benar-benar bisa dipakai publish. Ini mengukur waktu
 * koneksi, bukan kecepatan unggah.
 */
object RtmpTester {
    fun test(config: LiveConfig, timeoutMs: Long = 12_000L): RtmpTestResult {
        if (!config.isConfigured) {
            return RtmpTestResult(false, 0L, "Server dan kunci stream belum diisi")
        }
        val endpoint = runCatching {
            RtmpEndpoint.parse(config.serverUrl, config.streamKey)
        }.getOrElse {
            return RtmpTestResult(false, 0L, it.message ?: "URL tidak valid")
        }
        val started = System.nanoTime()
        val connection = RtmpConnection(
            endpoint = endpoint,
            username = config.username,
            password = config.password,
        ) { }
        return try {
            val ok = connection.connect(timeoutMs)
            val latency = (System.nanoTime() - started) / 1_000_000L
            if (ok) {
                RtmpTestResult(true, latency, "Terhubung ke ${endpoint.host}")
            } else {
                RtmpTestResult(false, latency, "Server menolak koneksi/publish")
            }
        } catch (error: Throwable) {
            val latency = (System.nanoTime() - started) / 1_000_000L
            RtmpTestResult(false, latency, error.message ?: "Tes gagal")
        } finally {
            connection.close()
        }
    }
}
