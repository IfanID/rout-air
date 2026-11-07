package com.rout.air.engine.gl

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import com.rout.air.core.model.BlendMode
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import com.rout.air.core.model.Crop
import com.rout.air.engine.filter.FilterState

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
    private var checkerProgram = 0

    private var externalMvp = 0
    private var externalTexMatrix = 0
    private var externalTexture = 0
    private var externalUvScale = 0
    private var externalUvBias = 0
    private var externalAlpha = 0

    private var textureMvp = 0
    private var textureSampler = 0
    private var textureUvScale = 0
    private var textureUvBias = 0
    private var textureAlpha = 0

    private var colorMvp = 0
    private var colorValue = 0
    private var colorAlpha = 0

    private var checkerMvp = 0
    private var checkerCells = 0
    private var checkerColorA = 0
    private var checkerColorB = 0

    private var externalFilter: FilterUniforms? = null
    private var textureFilter: FilterUniforms? = null

    /** Lokasi uniform filter untuk satu program shader. */
    private class FilterUniforms(program: Int) {
        val chroma = GLES20.glGetUniformLocation(program, "uChroma")
        val keyColor = GLES20.glGetUniformLocation(program, "uKeyColor")
        val similarity = GLES20.glGetUniformLocation(program, "uSimilarity")
        val smoothness = GLES20.glGetUniformLocation(program, "uSmoothness")
        val spill = GLES20.glGetUniformLocation(program, "uSpill")
        val adjust = GLES20.glGetUniformLocation(program, "uAdjust")
        val brightness = GLES20.glGetUniformLocation(program, "uBrightness")
        val contrast = GLES20.glGetUniformLocation(program, "uContrast")
        val saturation = GLES20.glGetUniformLocation(program, "uSaturation")
        val gamma = GLES20.glGetUniformLocation(program, "uGamma")
        val hue = GLES20.glGetUniformLocation(program, "uHue")

        fun apply(state: FilterState) {
            GLES20.glUniform1f(chroma, if (state.chroma) 1f else 0f)
            GLES20.glUniform3f(keyColor, state.keyR, state.keyG, state.keyB)
            GLES20.glUniform1f(similarity, state.similarity)
            GLES20.glUniform1f(smoothness, state.smoothness)
            GLES20.glUniform1f(spill, state.spill)
            GLES20.glUniform1f(adjust, if (state.adjust) 1f else 0f)
            GLES20.glUniform1f(brightness, state.brightness)
            GLES20.glUniform1f(contrast, state.contrast)
            GLES20.glUniform1f(saturation, state.saturation)
            GLES20.glUniform1f(gamma, state.gamma)
            GLES20.glUniform1f(hue, state.hueRadians)
        }
    }

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

    private val uvRotation = FloatArray(16)
    private val texCombined = FloatArray(16)
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
        externalAlpha = GLES20.glGetUniformLocation(externalProgram, "uAlpha")
        externalFilter = FilterUniforms(externalProgram)

        textureProgram = GlUtil.compileProgram(VERTEX_TEXTURE, FRAGMENT_TEXTURE)
        textureMvp = GLES20.glGetUniformLocation(textureProgram, "uMvp")
        textureSampler = GLES20.glGetUniformLocation(textureProgram, "uTexture")
        textureUvScale = GLES20.glGetUniformLocation(textureProgram, "uUvScale")
        textureUvBias = GLES20.glGetUniformLocation(textureProgram, "uUvBias")
        textureAlpha = GLES20.glGetUniformLocation(textureProgram, "uAlpha")
        textureFilter = FilterUniforms(textureProgram)

        colorProgram = GlUtil.compileProgram(VERTEX_COLOR, FRAGMENT_COLOR)
        colorMvp = GLES20.glGetUniformLocation(colorProgram, "uMvp")
        colorValue = GLES20.glGetUniformLocation(colorProgram, "uColor")
        colorAlpha = GLES20.glGetUniformLocation(colorProgram, "uAlpha")

        checkerProgram = GlUtil.compileProgram(VERTEX_CHECKER, FRAGMENT_CHECKER)
        checkerMvp = GLES20.glGetUniformLocation(checkerProgram, "uMvp")
        checkerCells = GLES20.glGetUniformLocation(checkerProgram, "uCells")
        checkerColorA = GLES20.glGetUniformLocation(checkerProgram, "uColorA")
        checkerColorB = GLES20.glGetUniformLocation(checkerProgram, "uColorB")
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
        blend: BlendMode = BlendMode.Normal,
        opacity: Float = 1f,
        /** Putar tekstur saja (derajat), kuad tetap tegak. */
        texRotate: Float = 0f,
        filters: FilterState = FilterState.NONE,
    ) {
        buildMvp(left, bottom, width, height, rotation)
        GLES20.glUseProgram(externalProgram)
        externalFilter?.apply(filters)
        applyBlend(blend)
        GLES20.glUniform1f(externalAlpha, opacity.coerceIn(0f, 1f))
        bindQuad(externalProgram)
        GLES20.glUniformMatrix4fv(externalMvp, 1, false, mvp, 0)
        val appliedTexMatrix = if (texRotate != 0f) {
            // Rotasi di sekitar titik tengah tekstur (0.5, 0.5) supaya hasil
            // putaran tetap berada di kuad yang sama.
            Matrix.setIdentityM(uvRotation, 0)
            Matrix.translateM(uvRotation, 0, 0.5f, 0.5f, 0f)
            Matrix.rotateM(uvRotation, 0, -texRotate, 0f, 0f, 1f)
            Matrix.translateM(uvRotation, 0, -0.5f, -0.5f, 0f)
            Matrix.multiplyMM(texCombined, 0, uvRotation, 0, texMatrix, 0)
            texCombined
        } else {
            texMatrix
        }
        GLES20.glUniformMatrix4fv(externalTexMatrix, 1, false, appliedTexMatrix, 0)
        // Cermin dikerjakan lewat UV (skala negatif + bias), jadi tidak
        // mengubah posisi/rotasi item.
        val scaleU = (1f - cropLeft - cropRight) * if (flipH) -1f else 1f
        val scaleV = (1f - cropTop - cropBottom) * if (flipV) -1f else 1f
        GLES20.glUniform2f(externalUvScale, scaleU, scaleV)
        // Quad tekstur eksternal punya v=0 di bawah layar (kebalikan quad 2D),
        // jadi crop "atas" harus memotong v tinggi. Tanpa ini crop atas/bawah
        // tertukar dibanding pratinjau.
        GLES20.glUniform2f(
            externalUvBias,
            if (flipH) 1f - cropLeft else cropLeft,
            if (flipV) 1f - cropTop else cropBottom,
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
        blend: BlendMode = BlendMode.Normal,
        opacity: Float = 1f,
        /** Putar tekstur saja (derajat), kuad tetap tegak. */
        texRotate: Float = 0f,
        filters: FilterState = FilterState.NONE,
    ) {
        buildMvp(left, bottom, width, height, rotation)
        GLES20.glUseProgram(textureProgram)
        textureFilter?.apply(filters)
        applyBlend(blend)
        GLES20.glUniform1f(textureAlpha, opacity.coerceIn(0f, 1f))
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
        blend: BlendMode = BlendMode.Normal,
        opacity: Float = 1f,
    ) {
        buildMvp(left, bottom, width, height, rotation)
        GLES20.glUseProgram(colorProgram)
        applyBlend(blend)
        GLES20.glUniform1f(colorAlpha, opacity.coerceIn(0f, 1f))
        bindQuad(colorProgram)
        GLES20.glUniformMatrix4fv(colorMvp, 1, false, mvp, 0)
        GLES20.glUniform4f(colorValue, red, green, blue, alpha)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    /**
     * Latar kotak-kotak untuk panduan kanvas di tampilan editor. Tidak
     * dipakai saat menggambar ke encoder.
     */
    fun drawCheckerboard(
        width: Float,
        height: Float,
        cellsX: Float,
        cellsY: Float,
        colorA: FloatArray = floatArrayOf(0.106f, 0.106f, 0.106f, 1f),
        colorB: FloatArray = floatArrayOf(0.137f, 0.137f, 0.137f, 1f),
    ) {
        buildMvp(0f, 0f, width, height, 0f)
        GLES20.glUseProgram(checkerProgram)
        GLES20.glDisable(GLES20.GL_BLEND)
        bindQuad(checkerProgram)
        GLES20.glUniformMatrix4fv(checkerMvp, 1, false, mvp, 0)
        GLES20.glUniform2f(checkerCells, cellsX, cellsY)
        GLES20.glUniform4fv(checkerColorA, 1, colorA, 0)
        GLES20.glUniform4fv(checkerColorB, 1, colorB, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    /** Garis tepi kanvas, sama seperti panduan di tampilan Compose. */
    fun drawCanvasBorder(width: Float, height: Float, thickness: Float = 2f) {
        val red = 1f
        val green = 1f
        val blue = 1f
        val alpha = 0.20f
        drawColor(red, green, blue, alpha, 0f, height - thickness, width, thickness, 0f)
        drawColor(red, green, blue, alpha, 0f, 0f, width, thickness, 0f)
        drawColor(red, green, blue, alpha, 0f, 0f, thickness, height, 0f)
        drawColor(red, green, blue, alpha, width - thickness, 0f, thickness, height, 0f)
    }

    fun release() {
        listOf(externalProgram, textureProgram, colorProgram, checkerProgram).forEach {
            if (it != 0) GLES20.glDeleteProgram(it)
        }
        externalProgram = 0
        textureProgram = 0
        colorProgram = 0
        checkerProgram = 0
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

    /**
     * Menyalakan fungsi pencampuran sesuai mode layer. Pemanggil sudah
     * mengaktifkan `GL_BLEND`; di sini hanya rumus campurannya yang dipilih.
     */
    private fun applyBlend(blend: BlendMode) {
        when (blend) {
            BlendMode.Normal -> GLES20.glBlendFunc(
                GLES20.GL_SRC_ALPHA,
                GLES20.GL_ONE_MINUS_SRC_ALPHA,
            )

            BlendMode.Add -> GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
            BlendMode.Screen -> GLES20.glBlendFunc(GLES20.GL_ONE_MINUS_DST_COLOR, GLES20.GL_ONE)
            BlendMode.Multiply -> GLES20.glBlendFunc(
                GLES20.GL_DST_COLOR,
                GLES20.GL_ONE_MINUS_SRC_ALPHA,
            )
        }
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
            uniform float uAlpha;
            uniform float uChroma;
            uniform vec3 uKeyColor;
            uniform float uSimilarity;
            uniform float uSmoothness;
            uniform float uSpill;
            uniform float uAdjust;
            uniform float uBrightness;
            uniform float uContrast;
            uniform float uSaturation;
            uniform float uGamma;
            uniform float uHue;
            varying vec2 vTexCoord;
            vec4 routFilters(vec4 c) {
                if (uChroma > 0.5) {
                    float d = distance(c.rgb, uKeyColor);
                    float a = smoothstep(uSimilarity, uSimilarity + uSmoothness + 0.001, d);
                    c.rgb = clamp(c.rgb - uKeyColor * (uSpill * (1.0 - a)), 0.0, 1.0);
                    c.a *= a;
                }
                if (uAdjust > 0.5) {
                    vec3 rgb = pow(max(c.rgb, vec3(0.0)), vec3(uGamma));
                    rgb = (rgb - vec3(0.5)) * uContrast + vec3(0.5);
                    rgb = rgb * uBrightness;
                    float luma = dot(rgb, vec3(0.213, 0.715, 0.072));
                    rgb = mix(vec3(luma), rgb, uSaturation);
                    float ca = cos(uHue);
                    float sa = sin(uHue);
                    float m00 = 0.213 + 0.787 * ca - 0.213 * sa;
                    float m01 = 0.715 - 0.715 * ca - 0.715 * sa;
                    float m02 = 0.072 - 0.072 * ca + 0.928 * sa;
                    float m10 = 0.213 - 0.213 * ca + 0.143 * sa;
                    float m11 = 0.715 + 0.285 * ca + 0.140 * sa;
                    float m12 = 0.072 - 0.072 * ca - 0.283 * sa;
                    float m20 = 0.213 - 0.213 * ca - 0.787 * sa;
                    float m21 = 0.715 - 0.715 * ca + 0.715 * sa;
                    float m22 = 0.072 + 0.928 * ca + 0.072 * sa;
                    c.rgb = clamp(vec3(
                        rgb.r * m00 + rgb.g * m01 + rgb.b * m02,
                        rgb.r * m10 + rgb.g * m11 + rgb.b * m12,
                        rgb.r * m20 + rgb.g * m21 + rgb.b * m22
                    ), 0.0, 1.0);
                }
                return c;
            }
            void main() {
                vec4 c = routFilters(texture2D(uTexture, vTexCoord));
                gl_FragColor = vec4(c.rgb, c.a * uAlpha);
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
            uniform float uAlpha;
            uniform float uChroma;
            uniform vec3 uKeyColor;
            uniform float uSimilarity;
            uniform float uSmoothness;
            uniform float uSpill;
            uniform float uAdjust;
            uniform float uBrightness;
            uniform float uContrast;
            uniform float uSaturation;
            uniform float uGamma;
            uniform float uHue;
            varying vec2 vTexCoord;
            vec4 routFilters(vec4 c) {
                if (uChroma > 0.5) {
                    float d = distance(c.rgb, uKeyColor);
                    float a = smoothstep(uSimilarity, uSimilarity + uSmoothness + 0.001, d);
                    c.rgb = clamp(c.rgb - uKeyColor * (uSpill * (1.0 - a)), 0.0, 1.0);
                    c.a *= a;
                }
                if (uAdjust > 0.5) {
                    vec3 rgb = pow(max(c.rgb, vec3(0.0)), vec3(uGamma));
                    rgb = (rgb - vec3(0.5)) * uContrast + vec3(0.5);
                    rgb = rgb * uBrightness;
                    float luma = dot(rgb, vec3(0.213, 0.715, 0.072));
                    rgb = mix(vec3(luma), rgb, uSaturation);
                    float ca = cos(uHue);
                    float sa = sin(uHue);
                    float m00 = 0.213 + 0.787 * ca - 0.213 * sa;
                    float m01 = 0.715 - 0.715 * ca - 0.715 * sa;
                    float m02 = 0.072 - 0.072 * ca + 0.928 * sa;
                    float m10 = 0.213 - 0.213 * ca + 0.143 * sa;
                    float m11 = 0.715 + 0.285 * ca + 0.140 * sa;
                    float m12 = 0.072 - 0.072 * ca - 0.283 * sa;
                    float m20 = 0.213 - 0.213 * ca - 0.787 * sa;
                    float m21 = 0.715 - 0.715 * ca + 0.715 * sa;
                    float m22 = 0.072 + 0.928 * ca + 0.072 * sa;
                    c.rgb = clamp(vec3(
                        rgb.r * m00 + rgb.g * m01 + rgb.b * m02,
                        rgb.r * m10 + rgb.g * m11 + rgb.b * m12,
                        rgb.r * m20 + rgb.g * m21 + rgb.b * m22
                    ), 0.0, 1.0);
                }
                return c;
            }
            void main() {
                vec4 c = routFilters(texture2D(uTexture, vTexCoord));
                gl_FragColor = vec4(c.rgb, c.a * uAlpha);
            }
        """

        const val VERTEX_CHECKER = """
            uniform mat4 uMvp;
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vUv;
            void main() {
                gl_Position = uMvp * aPosition;
                vUv = aTexCoord;
            }
        """

        const val FRAGMENT_CHECKER = """
            precision mediump float;
            uniform vec2 uCells;
            uniform vec4 uColorA;
            uniform vec4 uColorB;
            varying vec2 vUv;
            void main() {
                vec2 cell = floor(vUv * uCells);
                float odd = mod(cell.x + cell.y, 2.0);
                gl_FragColor = mix(uColorA, uColorB, odd);
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
            uniform float uAlpha;
            void main() {
                gl_FragColor = vec4(uColor.rgb, uColor.a * uAlpha);
            }
        """
    }
}
