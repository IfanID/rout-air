package com.rout.air.engine

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Kompositor multi-layer sederhana.
 *
 * Mendukung tiga jenis gambar:
 * - tekstur eksternal (`GL_TEXTURE_EXTERNAL_OES`) untuk tangkapan layar/kamera;
 * - tekstur 2D untuk gambar dan teks hasil render Bitmap;
 * - warna solid untuk sumber latar.
 *
 * Semua layer digambar dengan satu quad 0..1 dan matriks MVP kanvas, jadi
 * posisi, skala, dan rotasi item dihitung di sini. Crop diterapkan lewat
 * koordinat UV, meniru crop per sumber di OBS.
 */
class GlCompositor {
    private var externalProgram = 0
    private var textureProgram = 0
    private var colorProgram = 0

    private var externalMvp = 0
    private var externalTexMatrix = 0
    private var externalTexture = 0
    private var externalUvScale = 0
    private var externalUvBias = 0

    private var textureMvp = 0
    private var textureSampler = 0
    private var textureUvScale = 0
    private var textureUvBias = 0

    private var colorMvp = 0
    private var colorValue = 0

    private val quad: FloatBuffer = floatBuffer(
        floatArrayOf(
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f,
        ),
    )

    /** Koordinat tekstur 2D dibalik: baris bitmap pertama (atas) di v = 0. */
    private val quadUv2D: FloatBuffer = floatBuffer(
        floatArrayOf(
            0f, 1f,
            1f, 1f,
            0f, 0f,
            1f, 0f,
        ),
    )

    private val quadUvExternal: FloatBuffer = floatBuffer(
        floatArrayOf(
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f,
        ),
    )

    private val ortho = FloatArray(16)
    private val model = FloatArray(16)
    private val mvp = FloatArray(16)
    private val tmp = FloatArray(16)

    fun init() {
        externalProgram = GlUtil.compileProgram(VERTEX_EXTERNAL, FRAGMENT_EXTERNAL)
        externalMvp = GLES20.glGetUniformLocation(externalProgram, "uMvp")
        externalTexMatrix = GLES20.glGetUniformLocation(externalProgram, "uTexMatrix")
        externalTexture = GLES20.glGetUniformLocation(externalProgram, "uTexture")
        externalUvScale = GLES20.glGetUniformLocation(externalProgram, "uUvScale")
        externalUvBias = GLES20.glGetUniformLocation(externalProgram, "uUvBias")

        textureProgram = GlUtil.compileProgram(VERTEX_TEXTURE, FRAGMENT_TEXTURE)
        textureMvp = GLES20.glGetUniformLocation(textureProgram, "uMvp")
        textureSampler = GLES20.glGetUniformLocation(textureProgram, "uTexture")
        textureUvScale = GLES20.glGetUniformLocation(textureProgram, "uUvScale")
        textureUvBias = GLES20.glGetUniformLocation(textureProgram, "uUvBias")

        colorProgram = GlUtil.compileProgram(VERTEX_COLOR, FRAGMENT_COLOR)
        colorMvp = GLES20.glGetUniformLocation(colorProgram, "uMvp")
        colorValue = GLES20.glGetUniformLocation(colorProgram, "uColor")
    }

    fun setCanvasSize(width: Int, height: Int) {
        Matrix.orthoM(ortho, 0, 0f, width.toFloat(), 0f, height.toFloat(), -1f, 1f)
    }

    /**
     * Menggambar tekstur eksternal. `left`/`bottom` memakai koordinat OpenGL
     * (kiri-bawah), `rotation` derajat searah jarum jam di layar.
     */
    fun drawExternal(
        textureId: Int,
        texMatrix: FloatArray,
        left: Float,
        bottom: Float,
        width: Float,
        height: Float,
        rotation: Float,
        cropLeft: Float,
        cropTop: Float,
        cropRight: Float,
        cropBottom: Float,
        flipH: Boolean = false,
        flipV: Boolean = false,
    ) {
        buildMvp(left, bottom, width, height, rotation)
        GLES20.glUseProgram(externalProgram)
        bindQuad(externalProgram)
        GLES20.glUniformMatrix4fv(externalMvp, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(externalTexMatrix, 1, false, texMatrix, 0)
        // Cermin dikerjakan lewat UV (skala negatif + bias), jadi tidak
        // mengubah posisi/rotasi item.
        val scaleU = (1f - cropLeft - cropRight) * if (flipH) -1f else 1f
        val scaleV = (1f - cropTop - cropBottom) * if (flipV) -1f else 1f
        GLES20.glUniform2f(externalUvScale, scaleU, scaleV)
        GLES20.glUniform2f(
            externalUvBias,
            if (flipH) 1f - cropLeft else cropLeft,
            if (flipV) 1f - cropBottom else cropBottom,
        )
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(externalTexture, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    /** Menggambar tekstur 2D (gambar/teks) dengan crop UV. */
    fun drawTexture2D(
        textureId: Int,
        left: Float,
        bottom: Float,
        width: Float,
        height: Float,
        rotation: Float,
        cropLeft: Float,
        cropTop: Float,
        cropRight: Float,
        cropBottom: Float,
        flipH: Boolean = false,
        flipV: Boolean = false,
    ) {
        buildMvp(left, bottom, width, height, rotation)
        GLES20.glUseProgram(textureProgram)
        bindQuad(textureProgram, quadUv2D)
        GLES20.glUniformMatrix4fv(textureMvp, 1, false, mvp, 0)
        val scaleU = (1f - cropLeft - cropRight) * if (flipH) -1f else 1f
        val scaleV = (1f - cropTop - cropBottom) * if (flipV) -1f else 1f
        GLES20.glUniform2f(textureUvScale, scaleU, scaleV)
        GLES20.glUniform2f(
            textureUvBias,
            if (flipH) 1f - cropLeft else cropLeft,
            if (flipV) 1f - cropTop else cropTop,
        )
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(textureSampler, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    /** Menggambar warna solid (bisa transparan). */
    fun drawColor(
        red: Float,
        green: Float,
        blue: Float,
        alpha: Float,
        left: Float,
        bottom: Float,
        width: Float,
        height: Float,
        rotation: Float,
    ) {
        buildMvp(left, bottom, width, height, rotation)
        GLES20.glUseProgram(colorProgram)
        bindQuad(colorProgram)
        GLES20.glUniformMatrix4fv(colorMvp, 1, false, mvp, 0)
        GLES20.glUniform4f(colorValue, red, green, blue, alpha)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    fun release() {
        listOf(externalProgram, textureProgram, colorProgram).forEach {
            if (it != 0) GLES20.glDeleteProgram(it)
        }
        externalProgram = 0
        textureProgram = 0
        colorProgram = 0
    }

    private fun buildMvp(
        left: Float,
        bottom: Float,
        width: Float,
        height: Float,
        rotation: Float,
    ) {
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, left + width / 2f, bottom + height / 2f, 0f)
        // Rotasi GL positif berlawanan jarum jam; UI memakai derajat searah jam.
        Matrix.rotateM(model, 0, -rotation, 0f, 0f, 1f)
        Matrix.translateM(model, 0, -width / 2f, -height / 2f, 0f)
        Matrix.scaleM(model, 0, width, height, 1f)
        Matrix.multiplyMM(tmp, 0, ortho, 0, model, 0)
        System.arraycopy(tmp, 0, mvp, 0, 16)
    }

    private fun bindQuad(program: Int, uv: FloatBuffer = quadUvExternal) {
        val positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        val texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")

        quad.position(0)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, quad)

        // Program warna tidak memakai tekstur; atribut bisa dioptimalkan
        // keluar sehingga lokasinya -1 dan tidak boleh diaktifkan.
        if (texCoordHandle >= 0) {
            uv.position(0)
            GLES20.glEnableVertexAttribArray(texCoordHandle)
            GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, uv)
        }
    }

    private fun floatBuffer(values: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(values.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(values)
                position(0)
            }

    private companion object {
        const val VERTEX_EXTERNAL = """
            uniform mat4 uMvp;
            uniform mat4 uTexMatrix;
            uniform vec2 uUvScale;
            uniform vec2 uUvBias;
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = uMvp * aPosition;
                vTexCoord = (uTexMatrix * vec4(aTexCoord * uUvScale + uUvBias, 0.0, 1.0)).xy;
            }
        """

        const val FRAGMENT_EXTERNAL = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """

        const val VERTEX_TEXTURE = """
            uniform mat4 uMvp;
            uniform vec2 uUvScale;
            uniform vec2 uUvBias;
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = uMvp * aPosition;
                vTexCoord = aTexCoord * uUvScale + uUvBias;
            }
        """

        const val FRAGMENT_TEXTURE = """
            precision mediump float;
            uniform sampler2D uTexture;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """

        const val VERTEX_COLOR = """
            uniform mat4 uMvp;
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            void main() {
                gl_Position = uMvp * aPosition;
            }
        """

        const val FRAGMENT_COLOR = """
            precision mediump float;
            uniform vec4 uColor;
            void main() {
                gl_FragColor = uColor;
            }
        """
    }
}
