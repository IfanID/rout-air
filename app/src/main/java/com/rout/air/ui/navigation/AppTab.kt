package com.rout.air.ui.navigation

import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.rout.air.R

/**
 * Enam halaman meniru dock OBS: Siaran, Studio Mode, Adegan, Audio,
 * Rekaman, dan Setelan. Studio Mode sengaja jadi halaman sendiri, bukan
 * disatukan ke Siaran.
 */
enum class AppTab(@StringRes val labelRes: Int, val symbol: String) {
    Home(R.string.tab_siaran, "●"),
    Studio(R.string.tab_studio, "◫"),
    Scenes(R.string.tab_adegan, "▤"),
    Audio(R.string.tab_audio, "♪"),
    Recordings(R.string.tab_rekaman, "▶"),
    Settings(R.string.tab_setelan, "⚙"),
}
