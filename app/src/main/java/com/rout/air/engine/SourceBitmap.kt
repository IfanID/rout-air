package com.rout.air.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.opengl.GLES20
import android.opengl.GLUtils
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.Log
import java.io.File

/**
 * Pembuat tekstur untuk sumber non-layar (gambar dan teks).
 *
 * Semua fungsi yang menyentuh OpenGL wajib dipanggil saat konteks EGL aktif
 * di thread pemanggil. Pemuat gambar hanya menyentuh Bitmap dan aman di thread
 * mana pun.
 */
object SourceBitmap {
    private const val TAG = "RoutAirSource"

    /** Memuat gambar dengan penskalaan daya dua agar hemat memori. */
    fun loadImage(path: String?, maxWidth: Int, maxHeight: Int): Bitmap? {
        if (path.isNullOrBlank()) return null
        val file = File(path)
        if (!file.exists()) {
            Log.w(TAG, "Gambar tidak ditemukan: $path")
            return null
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxWidth &&
            bounds.outHeight / (sample * 2) >= maxHeight
        ) {
            sample *= 2
        }

        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(path, options)
    }

    /**
     * Ukuran hasil [loadImage] tanpa mendekode seluruh bitmap. Dipakai UI
     * supaya ukuran asli sumber tercatat sama dengan yang nanti dirender.
     */
    fun probeSize(path: String?, maxWidth: Int, maxHeight: Int): Pair<Int, Int>? {
        if (path.isNullOrBlank()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxWidth &&
            bounds.outHeight / (sample * 2) >= maxHeight
        ) {
            sample *= 2
        }
        return (bounds.outWidth / sample).coerceAtLeast(1) to
            (bounds.outHeight / sample).coerceAtLeast(1)
    }

    /** Merender teks menjadi Bitmap transparan memakai StaticLayout. */    fun renderText(
        text: String,
        textSizeSp: Float,
        colorArgb: Long,
        maxWidth: Int,
        density: Float,
    ): Bitmap {
        val safeWidth = maxWidth.coerceIn(64, 8192)
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            this.color = colorArgb.toInt()
            textSize = textSizeSp * density
            isSubpixelText = true
        }
        val content = if (TextUtils.isEmpty(text)) " " else text
        val layout = StaticLayout.Builder
            .obtain(content, 0, content.length, paint, safeWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .build()
        val height = layout.height.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(safeWidth, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.TRANSPARENT)
        Canvas(bitmap).also { layout.draw(it) }
        return bitmap
    }

    /** Mengunggah Bitmap menjadi tekstur 2D. Harus di thread GL. */
    fun createTexture(bitmap: Bitmap): Int {
        val handles = IntArray(1)
        GLES20.glGenTextures(1, handles, 0)
        val id = handles[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE,
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE,
        )
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        GlUtil.checkGlError("createTexture")
        return id
    }
}
