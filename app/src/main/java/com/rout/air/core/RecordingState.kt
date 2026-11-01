package com.rout.air.core

/** Status sesi perekaman yang dipakai UI dan service. */
sealed interface RecordingState {
    data object Idle : RecordingState

    /** Sesi tangkap layar aktif tanpa menulis file; pratinjau berjalan. */
    data object Previewing : RecordingState

    data object Preparing : RecordingState

    /**
     * [pausedAtMs] berisi durasi efektif saat rekaman dijeda; null berarti
     * sedang berjalan. [pausedTotalMs] dipakai UI untuk menghitung durasi
     * tanpa ikut menghitung waktu jeda.
     */
    data class Recording(
        val startedAtMs: Long,
        val outputUri: String?,
        val audioActive: Boolean,
        val pausedAtMs: Long? = null,
        val pausedTotalMs: Long = 0L,
    ) : RecordingState {
        val isPaused: Boolean get() = pausedAtMs != null
    }

    data class Error(val message: String) : RecordingState
}
