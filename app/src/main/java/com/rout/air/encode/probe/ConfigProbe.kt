package com.rout.air.encode.probe

import android.media.MediaFormat
import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import com.rout.air.core.planning.AutoConfigPlan
import com.rout.air.core.model.ColorRangeMode
import com.rout.air.core.model.ColorSpaceMode
import com.rout.air.core.model.VideoBitrateMode
import com.rout.air.core.model.VideoCodec
import com.rout.air.core.model.VideoProfile
import com.rout.air.engine.gl.EglCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import com.rout.air.encode.benchmark.NullSink
import com.rout.air.encode.mux.MediaSink
import com.rout.air.encode.video.VideoEncoder

/**
 * Menguji satu kandidat resolusi/fps memakai jalur encoder yang sama dengan
 * rekaman. Beban GPU sengaja ringan (layar polos berubah warna): yang diukur
 * adalah apakah perangkat sanggup mengalirkan frame sebesar itu tepat waktu,
 * seperti tahap uji OBS.
 */
object ConfigProbe {

    suspend fun run(
        candidate: AutoConfigPlan.Candidate,
        codec: VideoCodec,
        seconds: Int = 4,
    ): AutoConfigPlan.Probe = withContext(Dispatchers.Default) {
        measure(candidate, codec, seconds)
    }

    private fun measure(
        candidate: AutoConfigPlan.Candidate,
        codec: VideoCodec,
        seconds: Int,
    ): AutoConfigPlan.Probe {
        val frames = AtomicInteger()
        val lateFrames = AtomicInteger()
        var encoder: VideoEncoder? = null
        var core: EglCore? = null
        var scene: ProbeScene? = null
        var closed = false
        val durationNs = seconds.coerceAtLeast(1) * 1_000_000_000L
        val intervalNs = 1_000_000_000L / candidate.fps

        try {
            encoder = VideoEncoder(
                width = candidate.width,
                height = candidate.height,
                fps = candidate.fps,
                bitrate = AutoConfigPlan.upperBitrateKbps(
                    candidate.width,
                    candidate.height,
                    candidate.fps,
                ) * 1_000,
                keyframeIntervalSec = 2,
                bitrateMode = VideoBitrateMode.CBR,
                cqLevel = 20,
                profile = VideoProfile.Auto,
                colorSpace = ColorSpaceMode.BT709,
                colorRange = ColorRangeMode.Limited,
                requestedCodec = codec,
                encoderName = EncoderProbe.select(codec)?.name,
                sink = NullSink,
                onFrameEncoded = { frames.incrementAndGet() },
            )

            core = EglCore()
            val window = core.createWindowSurface(encoder.inputSurface)
            core.makeCurrent(window)
            GLES20.glViewport(0, 0, candidate.width, candidate.height)
            // Beban komposisi ringan: tanpa ini uji hanya mengukur glClear dan
            // hasilnya terlalu optimistis dibanding rekaman sungguhan.
            scene = ProbeScene().also { it.create() }

            val startNs = System.nanoTime()
            var nextFrameNs = startNs
            while (true) {
                val now = System.nanoTime()
                val elapsed = now - startNs
                if (elapsed >= durationNs) break

                if (now < nextFrameNs) {
                    sleepUntil(nextFrameNs)
                } else if (now - nextFrameNs > intervalNs) {
                    lateFrames.incrementAndGet()
                    nextFrameNs = now
                }

                // Warna berubah tiap frame supaya encoder tidak menganggap
                // frame identik.
                val phase = (elapsed % 1_000_000_000L) / 1_000_000_000f
                GLES20.glClearColor(0.15f + 0.3f * phase, 0.2f, 0.4f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                scene?.draw(phase)
                core.setPresentationTime(window, elapsed)
                if (!core.swapBuffers(window)) error("eglSwapBuffers gagal")
                nextFrameNs += intervalNs
            }

            encoder.finish()
            closed = true
            return AutoConfigPlan.Probe(
                candidate = candidate,
                achievedFps = frames.get().toDouble() / seconds,
                frames = frames.get(),
                lateFrames = lateFrames.get(),
            )
        } finally {
            runCatching { scene?.release() }
            runCatching { core?.makeNothingCurrent() }
            runCatching { core?.release() }
            if (!closed) runCatching { encoder?.finish(timeoutMs = 2_000L) }
        }
    }

    private fun sleepUntil(deadlineNs: Long) {
        while (true) {
            val remaining = deadlineNs - System.nanoTime()
            if (remaining <= 0) return
            if (remaining > 2_000_000L) {
                Thread.sleep((remaining / 1_000_000L) - 1)
            } else {
                Thread.yield()
            }
        }
    }

    /**
     * Adegan uji: satu quad penuh layar dengan shader per piksel dan 200
     * sprite berlapis. Ukurannya tetap supaya hasil antarkandidat sebanding.
     */
    private class ProbeScene {
        private var program = 0
        private var position = 0
        private var offsetLoc = 0
        private var scaleLoc = 0
        private var colorLoc = 0
        private var timeLoc = 0
        private var quad: FloatBuffer = ByteBuffer.allocateDirect(0).asFloatBuffer()

        fun create() {
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
                    for (int i = 0; i < 16; i++) {
                        float f = float(i) + 1.0;
                        acc += sin(gl_FragCoord.x * f * 0.012 + uTime)
                             * cos(gl_FragCoord.y * f * 0.015 - uTime);
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
            GLES20.glDeleteShader(vertex)
            GLES20.glDeleteShader(fragment)
            position = GLES20.glGetAttribLocation(program, "aPos")
            offsetLoc = GLES20.glGetUniformLocation(program, "uOffset")
            scaleLoc = GLES20.glGetUniformLocation(program, "uScale")
            colorLoc = GLES20.glGetUniformLocation(program, "uColor")
            timeLoc = GLES20.glGetUniformLocation(program, "uTime")
            quad = ByteBuffer
                .allocateDirect(QUAD.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(QUAD)
                    position(0)
                }
        }

        fun draw(timeSec: Float) {
            if (program == 0) return
            GLES20.glUseProgram(program)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            GLES20.glUniform1f(timeLoc, timeSec)
            GLES20.glEnableVertexAttribArray(position)
            GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, quad)

            // Latar berlapis sebagai overdraw.
            GLES20.glUniform2f(offsetLoc, 0f, 0f)
            GLES20.glUniform2f(scaleLoc, 1f, 1f)
            repeat(3) { layer ->
                GLES20.glUniform4f(colorLoc, 0.06f + 0.04f * layer, 0.08f, 0.2f, 0.4f)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            }

            // Sprite bergerak, ukuran sama untuk semua kandidat.
            GLES20.glUniform2f(scaleLoc, 0.03f, 0.05f)
            GLES20.glUniform4f(colorLoc, 0.8f, 0.5f, 0.95f, 0.5f)
            for (index in 0 until OBJECTS) {
                val phase = index * 0.618f + timeSec * 0.6f
                GLES20.glUniform2f(
                    offsetLoc,
                    (Math.sin(phase.toDouble()) * 0.85).toFloat(),
                    (Math.cos(phase.toDouble() * 1.3) * 0.45).toFloat(),
                )
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            }

            GLES20.glDisableVertexAttribArray(position)
            GLES20.glDisable(GLES20.GL_BLEND)
        }

        fun release() {
            if (program != 0) {
                GLES20.glDeleteProgram(program)
                program = 0
            }
        }

        private fun compile(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            return shader
        }

        private companion object {
            const val OBJECTS = 200
            val QUAD = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        }
    }

    private object NullSink : MediaSink {
        override fun addVideoTrack(format: MediaFormat): Int = 0

        override fun addAudioTrack(key: Int, format: MediaFormat): Int = 0

        override fun write(trackId: Int, data: ByteArray, presentationTimeUs: Long, flags: Int) = Unit

        override fun finish(): Boolean = true
    }
}
