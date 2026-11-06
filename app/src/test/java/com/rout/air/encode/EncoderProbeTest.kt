package com.rout.air.encode

import com.rout.air.core.model.VideoCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import com.rout.air.encode.probe.EncoderCaps
import com.rout.air.encode.probe.EncoderProbe

/** Mengunci aturan pemilihan encoder tanpa perlu perangkat Android. */
class EncoderProbeTest {

    private fun caps(
        codec: VideoCodec,
        name: String,
        hardware: Boolean,
        maxWidth: Int = 1920,
        maxHeight: Int = 1080,
        maxFps: Int = 60,
    ): EncoderCaps = EncoderCaps(
        codec = codec,
        name = name,
        hardware = hardware,
        maxWidth = maxWidth,
        maxHeight = maxHeight,
        maxFps = maxFps,
        supportsCbr = true,
        supportsVbr = true,
        supportsCq = false,
    )

    @Test
    fun av1SoftwareIsRejected() {
        val software = caps(VideoCodec.AV1, "c2.android.av1.encoder", hardware = false)
        assertNull(EncoderProbe.select(listOf(software), VideoCodec.AV1))
    }

    @Test
    fun av1HardwareIsPreferredOverSoftware() {
        val hardware = caps(VideoCodec.AV1, "c2.qti.av1.encoder", hardware = true)
        val software = caps(VideoCodec.AV1, "c2.android.av1.encoder", hardware = false)
        assertSame(hardware, EncoderProbe.select(listOf(software, hardware), VideoCodec.AV1))
    }

    @Test
    fun reportedSizeDoesNotBlockSelection() {
        // Encoder yang mengaku maksimal 1080p tetap harus bisa dipilih pada
        // kanvas portrait; configure() yang memutuskan nanti.
        val limited = caps(VideoCodec.HEVC, "c2.qti.hevc.encoder", hardware = true, maxWidth = 1280, maxHeight = 720)
        assertSame(limited, EncoderProbe.select(listOf(limited), VideoCodec.HEVC))
    }

    @Test
    fun codecWithoutEncoderIsUnavailable() {
        val avc = caps(VideoCodec.AVC, "c2.qti.avc.encoder", hardware = true)
        assertNull(EncoderProbe.select(listOf(avc), VideoCodec.HEVC))
    }

    @Test
    fun fallbackAlwaysEndsInAvc() {
        assertEquals(
            listOf(VideoCodec.AV1, VideoCodec.HEVC, VideoCodec.AVC),
            EncoderProbe.fallbackChain(VideoCodec.AV1),
        )
        assertEquals(listOf(VideoCodec.AVC), EncoderProbe.fallbackChain(VideoCodec.AVC))
    }
}
