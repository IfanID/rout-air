package com.rout.air.data

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList

/**
 * Bahasa aplikasi per aplikasi. Android 13+ punya `LocaleManager`; versi
 * lebih lama tidak bisa mengganti bahasa saat berjalan tanpa AppCompat, jadi
 * pemilih hanya ditampilkan di Android 13 ke atas.
 */
object AppLanguage {
    /** Bahasa yang tersedia, sesuai folder `values` (Indonesia) dan `values-en`. */
    val tags = listOf("in", "en")

    fun supported(context: Context): Boolean = Build.VERSION.SDK_INT >= 33

    fun current(context: Context): String {
        if (Build.VERSION.SDK_INT >= 33) {
            val locales = context.getSystemService(LocaleManager::class.java)
                ?.applicationLocales
            val tag = locales?.takeIf { !it.isEmpty }?.toLanguageTags()
            if (!tag.isNullOrBlank()) return tag.substringBefore('-')
        }
        return SettingsStore(context).languageTag()
    }

    fun set(context: Context, tag: String) {
        SettingsStore(context).setLanguageTag(tag)
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java)
                ?.applicationLocales = LocaleList.forLanguageTags(tag)
        }
    }
}
