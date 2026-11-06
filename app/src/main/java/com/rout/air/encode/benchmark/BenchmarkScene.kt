package com.rout.air.encode.benchmark

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import com.rout.air.core.model.Align

internal class BenchmarkScene(
    private val frameWidth: Int,
    private val frameHeight: Int,
    private val layers: Int,
    private val objects: Int,
    private val shadows: Boolean,
) {
    private var program = 0
    private var position = 0
    private var offset = 0
    private var scale = 0
    private var color = 0
    private var time = 0
    private val vertices: FloatBuffer

    // HUD: teks digambar ke Bitmap lalu diunggah sebagai tekstur.
    private var hudProgram = 0
    private var hudPos = 0
    private var hudUv = 0
    private var hudScaleLoc = 0
    private var hudOffsetLoc = 0
    private var hudTexLoc = 0
    private var hudTexture = 0
    private var hudBitmap: Bitmap? = null
    private var hudCanvas: Canvas? = null
    private var hudPaint: Paint? = null
    private var hudVertices: FloatBuffer
    private var hudReady = false

    // Wordmark RoutAir di tengah adegan.
    private var brandTexture = 0
    private var brandBitmap: Bitmap? = null

    init {
        val vertex = compile(
            GLES20.GL_VERTEX_SHADER,
            """
            attribute vec2 aPos;
            uniform vec2 uOffset;
            uniform vec2 uScale;
            void main() {
                gl_Position = vec4(aPos * uScale + uOffset, 0.0, 1.0);
            }
            """.trimIndent(),
        )
        val fragment = compile(
            GLES20.GL_FRAGMENT_SHADER,
            """
            precision mediump float;
            uniform vec4 uColor;
            uniform float uTime;
            void main() {
                float acc = 0.0;
                for (int i = 0; i < 24; i++) {
                    float f = float(i) + 1.0;
                    acc += sin(gl_FragCoord.x * f * 0.01 + uTime)
                         * cos(gl_FragCoord.y * f * 0.013 - uTime);
                }
                gl_FragColor = clamp(uColor + vec4(acc * 0.02), 0.0, 1.0);
            }
            """.trimIndent(),
        )
        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vertex)
            GLES20.glAttachShader(it, fragment)
            GLES20.glLinkProgram(it)
        }
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        check(linkStatus[0] == GLES20.GL_TRUE) {
            "Program benchmark gagal: ${GLES20.glGetProgramInfoLog(program)}"
        }
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        position = GLES20.glGetAttribLocation(program, "aPos")
        offset = GLES20.glGetUniformLocation(program, "uOffset")
        scale = GLES20.glGetUniformLocation(program, "uScale")
        color = GLES20.glGetUniformLocation(program, "uColor")
        time = GLES20.glGetUniformLocation(program, "uTime")
        vertices = ByteBuffer
            .allocateDirect(QUAD.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(QUAD)
                position(0)
            }

        val hudVertex = compile(
            GLES20.GL_VERTEX_SHADER,
            """
            attribute vec2 aPos;
            attribute vec2 aUv;
            uniform vec2 uOffset;
            uniform vec2 uScale;
            varying vec2 vUv;
            void main() {
                gl_Position = vec4(aPos * uScale + uOffset, 0.0, 1.0);
                vUv = aUv;
            }
            """.trimIndent(),
        )
        val hudFragment = compile(
            GLES20.GL_FRAGMENT_SHADER,
            """
            precision mediump float;
            varying vec2 vUv;
            uniform sampler2D uTex;
            void main() {
                gl_FragColor = texture2D(uTex, vUv);
            }
            """.trimIndent(),
        )
        hudProgram = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, hudVertex)
            GLES20.glAttachShader(it, hudFragment)
            GLES20.glLinkProgram(it)
        }
        GLES20.glDeleteShader(hudVertex)
        GLES20.glDeleteShader(hudFragment)
        hudPos = GLES20.glGetAttribLocation(hudProgram, "aPos")
        hudUv = GLES20.glGetAttribLocation(hudProgram, "aUv")
        hudOffsetLoc = GLES20.glGetUniformLocation(hudProgram, "uOffset")
        hudScaleLoc = GLES20.glGetUniformLocation(hudProgram, "uScale")
        hudTexLoc = GLES20.glGetUniformLocation(hudProgram, "uTex")
        hudVertices = ByteBuffer
            .allocateDirect(HUD_QUAD.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(HUD_QUAD)
                position(0)
            }
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        hudTexture = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, hudTexture)
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

        val brandIds = IntArray(1)
        GLES20.glGenTextures(1, brandIds, 0)
        brandTexture = brandIds[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, brandTexture)
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
        brandBitmap = buildBrand()
        upload(brandBitmap!!, brandTexture)
    }

    /** Wordmark dengan gradasi biru–ungu, sama seperti identitas RoutAir. */
    private fun buildBrand(): Bitmap {
        val width = 1024
        val height = 400
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        paint.shader = LinearGradient(
            0f,
            0f,
            width.toFloat(),
            0f,
            intArrayOf(0xFF3AA0FF.toInt(), 0xFF8E6BFF.toInt(), 0xFFFF6BD6.toInt()),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
        paint.textSize = 230f
        canvas.drawText("RoutAir", width / 2f, 245f, paint)
        paint.shader = null
        paint.color = 0xCCFFFFFF.toInt()
        paint.textSize = 48f
        paint.letterSpacing = 0.30f
        canvas.drawText("BENCHMARK", width / 2f, 340f, paint)
        return bitmap
    }

    private fun upload(bitmap: Bitmap, texture: Int) {
        val buffer = ByteBuffer
            .allocateDirect(bitmap.width * bitmap.height * 4)
            .order(ByteOrder.nativeOrder())
        bitmap.copyPixelsToBuffer(buffer)
        buffer.position(0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES20.GL_RGBA,
            bitmap.width,
            bitmap.height,
            0,
            GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            buffer,
        )
    }

    /** Memperbarui teks HUD; dipanggil sekali per detik. */
    fun setHud(lines: List<String>) {
        val hudWidth = 780
        val lineHeight = 36
        val hudHeight = 28 + lines.size * lineHeight
        var bitmap = hudBitmap
        if (bitmap == null || bitmap.height != hudHeight) {
            bitmap = Bitmap.createBitmap(hudWidth, hudHeight, Bitmap.Config.ARGB_8888)
            hudBitmap = bitmap
            hudCanvas = Canvas(bitmap)
        }
        val canvas = checkNotNull(hudCanvas)
        val paint = hudPaint ?: Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textSize = 25f
            color = Color.WHITE
        }.also { hudPaint = it }

        canvas.drawColor(0, PorterDuff.Mode.CLEAR)
        paint.color = 0xB4000000.toInt()
        paint.style = Paint.Style.FILL
        canvas.drawRect(0f, 0f, hudWidth.toFloat(), hudHeight.toFloat(), paint)
        paint.color = Color.WHITE
        lines.forEachIndexed { index, line ->
            canvas.drawText(line, 18f, 36f + index * lineHeight, paint)
        }

        upload(bitmap, hudTexture)
        hudReady = true
    }

    /** Menggambar HUD di kiri atas, lebarnya 36% layar. */
    private fun drawHud() {
        val bitmap = hudBitmap ?: return
        if (!hudReady || hudProgram == 0) return
        GLES20.glUseProgram(hudProgram)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, hudTexture)
        GLES20.glUniform1i(hudTexLoc, 0)

        val scaleX = 0.36f
        val scaleY = scaleX * frameWidth.toFloat() / frameHeight *
            (bitmap.height.toFloat() / bitmap.width)
        GLES20.glUniform2f(hudScaleLoc, scaleX, scaleY)
        GLES20.glUniform2f(hudOffsetLoc, -1f + scaleX, 1f - scaleY)

        GLES20.glEnableVertexAttribArray(hudPos)
        GLES20.glEnableVertexAttribArray(hudUv)
        hudVertices.position(0)
        GLES20.glVertexAttribPointer(hudPos, 2, GLES20.GL_FLOAT, false, 16, hudVertices)
        hudVertices.position(2)
        GLES20.glVertexAttribPointer(hudUv, 2, GLES20.GL_FLOAT, false, 16, hudVertices)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(hudPos)
        GLES20.glDisableVertexAttribArray(hudUv)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    fun draw(timeSec: Float) {
        // Bersihkan frame supaya sprite tidak menumpuk jadi pita panjang.
        GLES20.glClearColor(0.02f, 0.02f, 0.045f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(program)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUniform1f(time, timeSec)

        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, vertices)
        GLES20.glUniform2f(offset, 0f, 0f)
        GLES20.glUniform2f(scale, 1f, 1f)

        // Latar berlapis: overdraw seperti efek kota neon.
        repeat(layers) { layer ->
            val shade = 0.05f + 0.04f * layer
            GLES20.glUniform4f(color, shade, 0.08f + 0.03f * layer, 0.18f, 0.35f)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        if (shadows) {
            GLES20.glUniform4f(color, 0f, 0f, 0f, 0.30f)
            val shadowCount = objects / 4
            for (index in 0 until shadowCount) {
                place(index, shadowCount, timeSec, sizeScale = 0.22f, drift = 0.6f)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            }
        }

        // Sprite bergerak: meniru karakter dan kendaraan di layar.
        for (index in 0 until objects) {
            val phase = index * 0.618f + timeSec * 0.7f
            val x = (Math.sin(phase.toDouble()) * 0.82).toFloat()
            val y = (Math.cos(phase.toDouble() * 1.3) * 0.42).toFloat()
            GLES20.glUniform2f(offset, x, y)
            GLES20.glUniform2f(scale, 0.035f, 0.06f)
            GLES20.glUniform4f(
                color,
                0.6f + 0.4f * Math.sin(phase.toDouble()).toFloat(),
                0.5f,
                0.9f,
                0.55f,
            )
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisable(GLES20.GL_BLEND)
        drawBrand(timeSec)
        drawHud()
    }

    /** Wordmark RoutAir di tengah, berdenyut halus. */
    private fun drawBrand(timeSec: Float) {
        val bitmap = brandBitmap ?: return
        if (brandTexture == 0 || hudProgram == 0) return
        val pulse = 1f + 0.03f * Math.sin(timeSec * 2.0).toFloat()
        GLES20.glUseProgram(hudProgram)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, brandTexture)
        GLES20.glUniform1i(hudTexLoc, 0)

        val scaleX = 0.62f * pulse
        val scaleY = scaleX * frameWidth.toFloat() / frameHeight *
            (bitmap.height.toFloat() / bitmap.width)
        GLES20.glUniform2f(hudScaleLoc, scaleX, scaleY)
        GLES20.glUniform2f(hudOffsetLoc, 0f, 0.02f)

        GLES20.glEnableVertexAttribArray(hudPos)
        GLES20.glEnableVertexAttribArray(hudUv)
        hudVertices.position(0)
        GLES20.glVertexAttribPointer(hudPos, 2, GLES20.GL_FLOAT, false, 16, hudVertices)
        hudVertices.position(2)
        GLES20.glVertexAttribPointer(hudUv, 2, GLES20.GL_FLOAT, false, 16, hudVertices)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(hudPos)
        GLES20.glDisableVertexAttribArray(hudUv)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun place(index: Int, count: Int, timeSec: Float, sizeScale: Float, drift: Float) {
        val angle = (index.toFloat() / count) * 6.28318f + timeSec * drift
        val radius = 0.55f + 0.15f * Math.sin(angle * 2.0).toFloat()
        GLES20.glUniform2f(
            offset,
            Math.cos(angle.toDouble()).toFloat() * radius,
            Math.sin(angle.toDouble()).toFloat() * radius * 0.5f,
        )
        GLES20.glUniform2f(scale, sizeScale, sizeScale * 0.6f)
    }

    fun release() {
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
        if (hudProgram != 0) {
            GLES20.glDeleteProgram(hudProgram)
            hudProgram = 0
        }
        if (hudTexture != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(hudTexture), 0)
            hudTexture = 0
        }
        if (brandTexture != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(brandTexture), 0)
            brandTexture = 0
        }
        brandBitmap?.recycle()
        brandBitmap = null
        hudBitmap?.recycle()
        hudBitmap = null
        hudReady = false
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) {
            "Shader benchmark gagal: ${GLES20.glGetShaderInfoLog(shader)}"
        }
        return shader
    }

    private companion object {
        val QUAD = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)

        /** x, y, u, v. Baris bitmap 0 (atas) dipetakan ke v=0. */
        val HUD_QUAD = floatArrayOf(
            -1f, 1f, 0f, 0f,
            1f, 1f, 1f, 0f,
            -1f, -1f, 0f, 1f,
            1f, -1f, 1f, 1f,
        )
    }
}
