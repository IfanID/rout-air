package com.rout.air.service

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.view.View
import com.rout.air.data.AudioMixer
import kotlin.math.max

/**
 * VU meter mini untuk toolbar overlay: menampilkan level audio aktif
 * (nilai maksimum dari kanal di halaman Audio), dua lane L/R.
 */
class MiniVuMeterView(
    context: Context,
    private val channelId: com.rout.air.core.AudioChannelId? = null,
    private val vertical: Boolean = false,
) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handler = Handler(Looper.getMainLooper())
    private val updater = object : Runnable {
        override fun run() {
            invalidate()
            handler.postDelayed(this, 60)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.post(updater)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(updater)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val levels = AudioMixer.levels.value
        var left = -60f
        var right = -60f
        val id = channelId
        if (id != null) {
            val channel = levels.forChannel(id)
            left = channel.leftDb
            right = channel.rightDb
        } else {
            levels.channels.values.forEach {
                left = max(left, it.leftDb)
                right = max(right, it.rightDb)
            }
        }
        if (vertical) {
            val gap = width * 0.2f
            val laneW = (width - gap) / 2f
            drawLaneV(canvas, 0f, laneW, left)
            drawLaneV(canvas, laneW + gap, laneW, right)
        } else {
            val gap = height * 0.18f
            val laneH = (height - gap) / 2f
            drawLane(canvas, 0f, laneH, left)
            drawLane(canvas, laneH + gap, laneH, right)
        }
    }

    private fun drawLaneV(canvas: Canvas, left: Float, laneW: Float, db: Float) {
        val h = height.toFloat()
        paint.color = Color.argb(90, 255, 255, 255)
        canvas.drawRect(left, 0f, left + laneW, h, paint)
        val norm = ((db + 60f) / 60f).coerceIn(0f, 1f)
        paint.color = when {
            db >= -9f -> Color.parseColor("#FF4C4C")
            db >= -20f -> Color.parseColor("#FFFF4C")
            else -> Color.parseColor("#4CFF4C")
        }
        canvas.drawRect(left, h - h * norm, left + laneW, h, paint)
    }

    private fun drawLane(canvas: Canvas, top: Float, laneH: Float, db: Float) {
        val w = width.toFloat()
        paint.color = Color.argb(90, 255, 255, 255)
        canvas.drawRect(0f, top, w, top + laneH, paint)
        val norm = ((db + 60f) / 60f).coerceIn(0f, 1f)
        paint.color = when {
            db >= -9f -> Color.parseColor("#FF4C4C")
            db >= -20f -> Color.parseColor("#FFFF4C")
            else -> Color.parseColor("#4CFF4C")
        }
        canvas.drawRect(0f, top, w * norm, top + laneH, paint)
    }
}
