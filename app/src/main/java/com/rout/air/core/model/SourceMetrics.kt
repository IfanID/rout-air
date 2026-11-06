package com.rout.air.core.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Ukuran nyata tekstur sumber yang sedang dibuka pipeline (kamera, media,
 * browser, gambar). Editor memakainya untuk menggambar outline pilihan agar
 * posisinya sama dengan gambar yang benar-benar dirender, tanpa memaksa
 * tekstur masuk ke kotak `SourceSettings`.
 */
object SourceMetrics {
    private val _sizes = MutableStateFlow<Map<String, Pair<Int, Int>>>(emptyMap())
    val sizes: StateFlow<Map<String, Pair<Int, Int>>> = _sizes.asStateFlow()

    fun report(sourceId: String, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val current = _sizes.value[sourceId]
        if (current == width to height) return
        _sizes.value = _sizes.value + (sourceId to (width to height))
    }

    fun forget(sourceId: String) {
        if (sourceId !in _sizes.value) return
        _sizes.value = _sizes.value - sourceId
    }

    fun clear() {
        if (_sizes.value.isNotEmpty()) _sizes.value = emptyMap()
    }
}
