package com.rout.air.core.model

/**
 * Statistik rekaman yang diperbarui tiap detik dan ditampilkan di UI.
 * Semua nilai berasal dari pipeline, bukan perkiraan UI.
 */
data class RecordingStats(
    val durationMs: Long = 0L,
    val frames: Int = 0,
    val droppedFrames: Int = 0,
    val bytes: Long = 0L,
    val bitrateKbps: Int = 0,
    val paused: Boolean = false,
    /** Frame encoder per detik pada interval statistik terakhir. */
    val fps: Int = 0,
)
