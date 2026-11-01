package com.rout.air.live

/**
 * Kebijakan koneksi ulang live: backoff eksponensial dengan batas atas.
 * Dipisah agar bisa diuji tanpa socket.
 */
object ReconnectPolicy {
    const val MAX_ATTEMPTS = 8
    const val BASE_DELAY_MS = 1_000L
    const val MAX_DELAY_MS = 10_000L

    /** null berarti jangan mencoba lagi. */
    fun delayMs(attempt: Int): Long? {
        if (attempt <= 0 || attempt > MAX_ATTEMPTS) return null
        var delay = BASE_DELAY_MS
        repeat(attempt - 1) {
            delay = (delay * 2).coerceAtMost(MAX_DELAY_MS)
        }
        return delay
    }
}
