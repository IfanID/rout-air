package com.rout.air.data.common

import android.content.Context
import androidx.annotation.StringRes
import com.rout.air.R

/**
 * Pengambil teks untuk kelas non-UI (encoder, jaringan, pipeline) yang tidak
 * memegang `Context`. Diisi sekali dari `Application`; bila belum siap,
 * kembalikan kosong agar tidak pernah memakai teks literal di kode.
 */
object Msg {
    private var context: Context? = null

    fun attach(context: Context) {
        this.context = context.applicationContext
    }

    fun get(@StringRes id: Int, vararg args: Any): String {
        val app = context ?: return ""
        return app.getString(id, *args)
    }

    /** Untuk pesan yang butuh suffix opsional tanpa placeholder kosong. */
    fun recordNotSaved(frames: Long, videos: Long, detail: String): String =
        get(R.string.error_record_not_saved, frames, videos, detail)
}
