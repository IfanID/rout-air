package com.rout.air.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.rout.air.core.model.CaptureConfig
import com.rout.air.core.model.VideoBitrateMode
import com.rout.air.core.model.VideoCodec

/** Mengunci aturan Rescale Output dan bitrate rekaman agar sesuai OBS. */
class CaptureConfigTest {

    @Test
    fun defaultsMatchObs() {
        val config = CaptureConfig()
        assertEquals(VideoBitrateMode.CBR, config.bitrateMode)
        assertEquals(VideoCodec.AVC, config.videoCodec)
        assertEquals(2, config.keyframeIntervalSec)
    }

    @Test
    fun defaultFollowsCanvas() {        val config = CaptureConfig(width = 1080, height = 1920)
        assertEquals(1080, config.encoderWidth)
        assertEquals(1920, config.encoderHeight)
        assertFalse(config.rescaled)
    }

    @Test
    fun customResolutionOverridesCanvas() {
        val config = CaptureConfig(
            width = 1080,
            height = 1920,
            outputWidth = 1280,
            outputHeight = 720,
        )
        assertEquals(1280, config.encoderWidth)
        assertEquals(720, config.encoderHeight)
        assertTrue(config.rescaled)
    }

    @Test
    fun oddResolutionRoundsUpToEven() {
        val config = CaptureConfig(
            outputWidth = 1279,
            outputHeight = 719,
        )
        assertEquals(1280, config.encoderWidth)
        assertEquals(720, config.encoderHeight)
    }

    @Test
    fun partialResolutionIsIgnored() {
        val config = CaptureConfig(width = 1080, height = 1920, outputWidth = 1280)
        assertEquals(1080, config.encoderWidth)
        assertEquals(1920, config.encoderHeight)
    }

    @Test
    fun recordingBitrateOnlyAppliesWhenRecordingAlone() {
        val config = CaptureConfig(videoBitrate = 6_000_000, recordVideoBitrate = 12_000_000)
        assertEquals(12_000_000, config.encoderBitrate(recordingOnly = true))
        assertEquals(6_000_000, config.encoderBitrate(recordingOnly = false))
    }

    @Test
    fun zeroRecordingBitrateFallsBackToStreaming() {
        val config = CaptureConfig(videoBitrate = 6_000_000, recordVideoBitrate = 0)
        assertEquals(6_000_000, config.encoderBitrate(recordingOnly = true))
    }
}
