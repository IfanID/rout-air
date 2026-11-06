package com.rout.air.data.overlay

import android.content.Context
import com.rout.air.core.model.LiveStats
import com.rout.air.core.model.RecordingStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.rout.air.core.model.Scene
import kotlinx.coroutines.flow.asStateFlow

/** Jenis info yang bisa ditampilkan di pojok layar saat live/rekaman. */
enum class OverlayInfoItem(val key: String) {
    Fps("fps"),
    Drops("drops"),
    Reconnect("reconnect"),
    Resolution("resolution"),
    Scene("scene"),
    ;

    companion object {
        fun fromKey(key: String): OverlayInfoItem? = entries.firstOrNull { it.key == key }
    }
}

/**
 * Pilihan info overlay. Disimpan agar susunan chip tetap sama setelah
 * aplikasi ditutup, dan dibaca bersama oleh overlay Live maupun Rekaman.
 */
object OverlayInfoStore {
    private const val FILE = "overlay_info"
    private const val KEY = "items"

    private val _items = MutableStateFlow<Set<OverlayInfoItem>>(emptySet())
    val items: StateFlow<Set<OverlayInfoItem>> = _items.asStateFlow()

    private var prefs: android.content.SharedPreferences? = null

    fun attach(context: Context) {
        if (prefs != null) return
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs = p
        _items.value = p.getStringSet(KEY, emptySet()).orEmpty()
            .mapNotNull(OverlayInfoItem::fromKey)
            .toSet()
    }

    fun toggle(item: OverlayInfoItem) {
        val next = if (item in _items.value) _items.value - item else _items.value + item
        _items.value = next
        prefs?.edit()?.putStringSet(KEY, next.map { it.key }.toSet())?.apply()
    }
}

/**
 * Menyusun satu baris teks untuk chip: `60 fps • 3 drop • 1080×2400`.
 * Item yang datanya belum tersedia dilewati supaya chip tidak berisi "--".
 */
fun buildOverlayInfoText(
    items: Set<OverlayInfoItem>,
    stats: RecordingStats,
    live: LiveStats,
    sceneName: String?,
    width: Int,
    height: Int,
): String {
    if (items.isEmpty()) return ""
    val parts = mutableListOf<String>()
    if (OverlayInfoItem.Fps in items && stats.fps > 0) {
        parts += "${stats.fps} fps"
    }
    if (OverlayInfoItem.Drops in items) {
        parts += "${stats.droppedFrames} drop"
    }
    if (OverlayInfoItem.Reconnect in items) {
        parts += "${live.reconnects}x sambung"
    }
    if (OverlayInfoItem.Resolution in items && width > 0 && height > 0) {
        parts += "$width×$height"
    }
    if (OverlayInfoItem.Scene in items && !sceneName.isNullOrBlank()) {
        parts += sceneName
    }
    return parts.joinToString(" • ")
}
