package com.rout.air.output

/** Keputusan pengawas rekaman. */
enum class WatchdogAction {
    Continue,
    EncoderStalled,
    EncoderSurfaceFailed,
}

/**
 * Logika pengawas rekaman, dipisah dari pipeline agar bisa disimulasikan.
 *
 * Penting: frame layar yang berhenti (layar mati/terkunci) TIDAK menghentikan
 * rekaman, karena pengisi frame mengulang frame terakhir dan audio tetap
 * berjalan. Rekaman hanya dihentikan bila encoder benar-benar macet.
 */
object WatchdogPolicy {
    const val MAX_SWAP_FAILURES = 30

    fun decide(
        recordingActive: Boolean,
        nowNs: Long,
        lastEncodedNs: Long,
        consecutiveSwapFailures: Int,
        stallTimeoutNs: Long,
    ): WatchdogAction {
        if (!recordingActive) return WatchdogAction.Continue
        if (lastEncodedNs > 0 && nowNs - lastEncodedNs > stallTimeoutNs) {
            return WatchdogAction.EncoderStalled
        }
        if (consecutiveSwapFailures > MAX_SWAP_FAILURES) {
            return WatchdogAction.EncoderSurfaceFailed
        }
        return WatchdogAction.Continue
    }
}
