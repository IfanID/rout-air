package com.rout.air.core

/**
 * Jam rekaman yang bisa dijeda.
 *
 * PTS video tidak boleh memakai jam dinding langsung: saat rekaman dijeda,
 * waktu yang terlewat tidak boleh ikut masuk ke timeline, kalau tidak file
 * akan punya lompatan/gap. Kelas ini menyimpan total waktu jeda dan
 * menguranginya dari durasi rekaman.
 */
class RecordingClock {
    private var startNs = 0L
    private var pausedAtNs: Long? = null
    private var pausedTotalNs = 0L
    private var started = false

    val isStarted: Boolean get() = started
    val isPaused: Boolean get() = pausedAtNs != null
    val pausedTotalMs: Long get() = pausedTotalNs / 1_000_000L
    val startTimeNs: Long get() = startNs

    fun start(nowNs: Long) {
        startNs = nowNs
        pausedAtNs = null
        pausedTotalNs = 0L
        started = true
    }

    fun pause(nowNs: Long) {
        if (!started || pausedAtNs != null) return
        pausedAtNs = nowNs
    }

    fun resume(nowNs: Long) {
        val began = pausedAtNs ?: return
        pausedTotalNs += (nowNs - began).coerceAtLeast(0L)
        pausedAtNs = null
    }

    /**
     * Waktu rekaman efektif: jam dinding sejak start dikurangi seluruh jeda,
     * termasuk jeda yang sedang berjalan.
     */
    fun elapsedNs(nowNs: Long): Long {
        if (!started) return 0L
        val runningPause = pausedAtNs?.let { (nowNs - it).coerceAtLeast(0L) } ?: 0L
        return (nowNs - startNs - pausedTotalNs - runningPause).coerceAtLeast(0L)
    }

    fun elapsedMs(nowNs: Long): Long = elapsedNs(nowNs) / 1_000_000L

    fun reset() {
        startNs = 0L
        pausedAtNs = null
        pausedTotalNs = 0L
        started = false
    }
}
