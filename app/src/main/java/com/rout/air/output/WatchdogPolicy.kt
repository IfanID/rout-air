package com.rout.air.output

/** Keputusan pengawas rekaman. */
enum class WatchdogAction {
    Continue,
    EncoderStalled,
    EncoderSurfaceFailed,
    StorageFailed,
}

/**
 * Logika pengawas rekaman, dipisah dari pipeline agar bisa disimulasikan.
 *
 * Penting: frame layar yang berhenti (layar mati/terkunci) TIDAK menghentikan
 * rekaman, karena pengisi frame mengulang frame terakhir dan audio tetap
 * berjalan. Rekaman juga tidak dianggap macet saat dijeda. Rekaman hanya
 * dihentikan bila encoder benar-benar macet atau penulisan file gagal terus.
 */
object WatchdogPolicy {
    const val MAX_SWAP_FAILURES = 30
    const val MAX_WRITE_FAILURES = 10

    fun decide(
        recordingActive: Boolean,
        nowNs: Long,
        lastEncodedNs: Long,
        consecutiveSwapFailures: Int,
        stallTimeoutNs: Long,
        paused: Boolean = false,
        writeFailures: Int = 0,
    ): WatchdogAction {
        if (!recordingActive) return WatchdogAction.Continue
        if (writeFailures > MAX_WRITE_FAILURES) {
            return WatchdogAction.StorageFailed
        }
        if (consecutiveSwapFailures > MAX_SWAP_FAILURES) {
            return WatchdogAction.EncoderSurfaceFailed
        }
        // Saat dijeda tidak ada frame yang dikirim, jadi "tidak ada output"
        // bukan tanda encoder macet.
        if (paused) return WatchdogAction.Continue
        if (lastEncodedNs > 0 && nowNs - lastEncodedNs > stallTimeoutNs) {
            return WatchdogAction.EncoderStalled
        }
        return WatchdogAction.Continue
    }
}
