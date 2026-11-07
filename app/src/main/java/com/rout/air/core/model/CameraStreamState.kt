package com.rout.air.core.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Status koneksi stream kamera H.264 per sumber. */
sealed interface CameraStreamState {
    data object Connecting : CameraStreamState
    data class Connected(val host: String, val port: Int) : CameraStreamState
    data class Streaming(val width: Int, val height: Int) : CameraStreamState
    data class Error(val message: String) : CameraStreamState
    data object Disconnected : CameraStreamState
}

/** Menyimpan status terakhir tiap sumber kamera agar UI bisa menampilkannya. */
object CameraStreamStatus {
    private val _states = MutableStateFlow<Map<String, CameraStreamState>>(emptyMap())
    val states: StateFlow<Map<String, CameraStreamState>> = _states.asStateFlow()

    fun report(sourceId: String, state: CameraStreamState) {
        _states.value = _states.value + (sourceId to state)
    }

    fun clear(sourceId: String) {
        if (sourceId !in _states.value) return
        _states.value = _states.value - sourceId
    }
}
