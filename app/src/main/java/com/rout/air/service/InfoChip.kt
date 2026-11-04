package com.rout.air.service

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Chip teks kecil di atas layar, terpisah dari jendela overlay utama supaya
 * tetap terlihat walau panel ditutup. Chip bisa digeser ke segala arah dan
 * posisinya disimpan per [prefKey] sehingga Live dan Rekaman tidak menimpa.
 *
 * Posisi memakai koordinat absolut kiri-atas; gravity END tidak dipakai
 * karena pergeseran horizontalnya tidak diterapkan konsisten oleh
 * WindowManager saat jendela digeser.
 *
 * [defaultTopDp] hanya dipakai saat belum ada posisi tersimpan.
 */
class InfoChip(
    private val context: Context,
    private val prefKey: String,
    private val defaultTopDp: Int,
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val prefs by lazy {
        context.getSharedPreferences("overlay_info", Context.MODE_PRIVATE)
    }
    private var view: TextView? = null
    private var params: WindowManager.LayoutParams? = null

    /** Chip hanya menerima sentuhan saat panel overlay terbuka. */
    private var draggable = false

    /** Null atau kosong berarti chip disembunyikan. */
    fun setText(text: String?) {
        if (text.isNullOrBlank()) {
            detach()
            return
        }
        val chip = view ?: attach()
        chip.text = text
        params?.let { runCatching { windowManager.updateViewLayout(chip, it) } }
    }

    /**
     * Mengizinkan chip digeser hanya saat panel terbuka; di luar itu sentuhan
     * diteruskan ke aplikasi di bawahnya.
     */
    fun setDraggable(enabled: Boolean) {
        draggable = enabled
        val chip = view ?: return
        val layout = params ?: return
        layout.flags = if (enabled) {
            layout.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            layout.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        runCatching { windowManager.updateViewLayout(chip, layout) }
    }

    fun detach() {
        val chip = view ?: return
        view = null
        params = null
        runCatching { windowManager.removeView(chip) }
    }

    private fun attach(): TextView {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val chip = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            setShadowLayer(2f, 0f, 1f, Color.BLACK)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.argb(150, 0, 0, 0))
            }
            visibility = View.VISIBLE
        }
        val savedX = prefs.getInt("chip_px_$prefKey", UNSET)
        val savedY = prefs.getInt("chip_py_$prefKey", UNSET)
        val noTouch = if (draggable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val layout = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Sentuhan baru diterima setelah panel dibuka (setDraggable).
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                noTouch or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (savedX != UNSET) savedX else 0
            y = if (savedY != UNSET) savedY else dp(defaultTopDp)
        }
        chip.setOnTouchListener(dragListener(chip, layout))
        view = chip
        params = layout
        runCatching { windowManager.addView(chip, layout) }
        if (savedX == UNSET) {
            // Posisi awal (kanan atas) baru bisa dihitung setelah lebar chip
            // diketahui dari layout pass pertama.
            chip.post {
                val width = chip.width.coerceAtLeast(1)
                layout.x = (context.resources.displayMetrics.widthPixels - width - dp(8))
                    .coerceAtLeast(0)
                runCatching { windowManager.updateViewLayout(chip, layout) }
            }
        }
        return chip
    }

    /** Geser bebas: offset dihitung dari posisi awal saat jari menyentuh. */
    private fun dragListener(
        chip: TextView,
        layout: WindowManager.LayoutParams,
    ) = View.OnTouchListener { _, event ->
        if (!draggable) return@OnTouchListener false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = layout.x
                startY = layout.y
                downRawX = event.rawX
                downRawY = event.rawY
                true
            }

            MotionEvent.ACTION_MOVE -> {
                val metrics = context.resources.displayMetrics
                val moveX = startX + (event.rawX - downRawX).roundToInt()
                val moveY = startY + (event.rawY - downRawY).roundToInt()
                // Batasi agar chip tetap di dalam layar.
                layout.x = moveX.coerceIn(0, (metrics.widthPixels - chip.width).coerceAtLeast(0))
                layout.y = moveY.coerceIn(0, (metrics.heightPixels - chip.height).coerceAtLeast(0))
                runCatching { windowManager.updateViewLayout(chip, layout) }
                true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // Magnet: kalau dekat tepian, chip menempel rapat ke tepi.
                snapToEdges(chip, layout)
                runCatching { windowManager.updateViewLayout(chip, layout) }
                // Simpan supaya posisi tetap sama pada sesi berikutnya.
                prefs.edit()
                    .putInt("chip_px_$prefKey", layout.x)
                    .putInt("chip_py_$prefKey", layout.y)
                    .apply()
                true
            }

            else -> false
        }
    }

    private companion object {
        const val UNSET = Int.MIN_VALUE
        /** Jarak maksimum (dp) agar chip dianggap menempel ke tepi. */
        const val SNAP_DP = 28
        /** Jarak aman chip dari tepi layar setelah menempel (dp). */
        const val EDGE_DP = 4
    }

    /**
     * Menempelkan chip ke tepi terdekat bila jaraknya kurang dari [SNAP_DP].
     * Posisi memakai koordinat absolut kiri-atas.
     */
    private fun snapToEdges(chip: TextView, layout: WindowManager.LayoutParams) {
        val density = context.resources.displayMetrics.density
        val snap = (SNAP_DP * density).toInt()
        val edge = (EDGE_DP * density).toInt()
        val screenW = context.resources.displayMetrics.widthPixels
        val screenH = context.resources.displayMetrics.heightPixels
        val width = chip.width.coerceAtLeast(1)
        val height = chip.height.coerceAtLeast(1)

        val leftGap = layout.x
        val rightGap = screenW - (layout.x + width)
        val topGap = layout.y
        val bottomGap = screenH - (layout.y + height)

        when {
            // Tepi dengan jarak terkecil yang menang bila dua-duanya dekat.
            leftGap <= snap && leftGap <= rightGap -> layout.x = edge
            rightGap <= snap -> layout.x = (screenW - width - edge).coerceAtLeast(edge)
        }
        when {
            topGap <= snap && topGap <= bottomGap -> layout.y = edge
            bottomGap <= snap -> layout.y = (screenH - height - edge).coerceAtLeast(edge)
        }
    }

    private var startX = 0
    private var startY = 0
    private var downRawX = 0f
    private var downRawY = 0f
}
