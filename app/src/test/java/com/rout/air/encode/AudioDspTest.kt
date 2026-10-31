package com.rout.air.encode

import com.rout.air.core.AudioFilter
import com.rout.air.core.AudioFilterType
import org.junit.Assert.assertEquals
import org.junit.Test

/** Uji DSP inti yang tidak membutuhkan Android (gain, delay, sync offset). */
class AudioDspTest {
    @Test
    fun gainFilterScalesSamples() {
        val chain = ChannelFilterChain(48_000)
        val filter = AudioFilter(
            id = "gain",
            type = AudioFilterType.Gain,
            params = mapOf("gainDb" to -6f),
        )
        val left = floatArrayOf(1f, 1f, 1f, 1f)
        val right = floatArrayOf(-1f, -1f, -1f, -1f)

        chain.process(listOf(filter), left, right, 4)

        assertEquals(0.501f, left[0], 0.01f)
        assertEquals(-0.501f, right[0], 0.01f)
    }

    @Test
    fun positiveSyncOffsetDelaysAudio() {
        val aligner = SyncAligner(sampleRate = 1_000)
        val left = floatArrayOf(1f, 2f, 3f, 4f)
        val right = floatArrayOf(-1f, -2f, -3f, -4f)
        val outLeft = FloatArray(4)
        val outRight = FloatArray(4)

        aligner.process(left, right, 4, 2, outLeft, outRight)
        assertEquals(0f, outLeft[0], 0.001f)
        assertEquals(0f, outRight[0], 0.001f)
        assertEquals(1f, outLeft[2], 0.001f)
        assertEquals(2f, outLeft[3], 0.001f)
    }

    @Test
    fun negativeSyncOffsetDropsSamples() {
        val aligner = SyncAligner(sampleRate = 1_000)
        val left = floatArrayOf(1f, 2f, 3f, 4f)
        val right = floatArrayOf(-1f, -2f, -3f, -4f)
        val outLeft = FloatArray(4)
        val outRight = FloatArray(4)

        aligner.process(left, right, 4, -2, outLeft, outRight)
        assertEquals(3f, outLeft[0], 0.001f)
        assertEquals(4f, outLeft[1], 0.001f)
        assertEquals(0f, outLeft[2], 0.001f)
        assertEquals(0f, outLeft[3], 0.001f)
    }
}
