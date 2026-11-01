package com.rout.air.core

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
)
