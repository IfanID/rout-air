package com.rout.air.ui.theme

import android.content.Context
import androidx.compose.ui.graphics.Color
import com.rout.air.data.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pilihan aksen tema. Nilai [key] disimpan di preferensi supaya pilihan
 * pengguna bertahan setelah aplikasi ditutup.
 */
enum class AccentChoice(val key: String, val color: Color) {
    Cyan("cyan", Color(0xFF00E5FF)),
    Green("green", Color(0xFF4ADE80)),
    Blue("blue", Color(0xFF4D9DFF)),
    Purple("purple", Color(0xFFB388FF)),
    Amber("amber", Color(0xFFFFB74D)),
    Pink("pink", Color(0xFFFF6FAE));

    companion object {
        fun from(key: String?): AccentChoice =
            entries.firstOrNull { it.key == key } ?: Cyan
    }
}

/**
 * Tema aktif yang bisa diubah saat aplikasi berjalan. Service overlay ikut
 * membaca nilai yang sama karena objek ini hidup di proses aplikasi.
 */
object Appearance {
    private val _accent = MutableStateFlow(AccentChoice.Cyan)
    val accent: StateFlow<AccentChoice> = _accent.asStateFlow()

    private val _amoled = MutableStateFlow(false)
    val amoled: StateFlow<Boolean> = _amoled.asStateFlow()

    private var store: SettingsStore? = null

    fun attach(context: Context) {
        if (store != null) return
        val settings = SettingsStore(context.applicationContext)
        store = settings
        _accent.value = AccentChoice.from(settings.accentKey())
        _amoled.value = settings.amoledBlack()
    }

    fun setAccent(choice: AccentChoice) {
        _accent.value = choice
        store?.setAccentKey(choice.key)
    }

    fun setAmoled(enabled: Boolean) {
        _amoled.value = enabled
        store?.setAmoledBlack(enabled)
    }
}
