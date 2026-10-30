package com.rout.air.core

/** Status sesi perekaman yang dipakai UI dan service. */
sealed interface RecordingState {
    data object Idle : RecordingState

    data object Preparing : RecordingState

    data class Recording(
        val startedAtMs: Long,
        val outputUri: String?,
        val audioActive: Boolean,
    ) : RecordingState

    data class Error(val message: String) : RecordingState
}
