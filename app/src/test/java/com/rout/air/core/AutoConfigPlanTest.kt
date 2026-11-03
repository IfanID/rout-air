package com.rout.air.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mengunci logika setup otomatis tanpa perlu perangkat Android. */
class AutoConfigPlanTest {

    @Test
    fun bitrateFormulaMatchesObsReference() {
        // OBS memakai 1080p60 = 5800 kbps sebagai patokan minimum.
        assertEquals(
            5800.0,
            AutoConfigPlan.minBitrateKbps(1920, 1080, 60).toDouble(),
            5.0,
        )
        // Batas atas 1080p60 di OBS sekitar 8,8 Mbps.
        assertEquals(
            8800.0,
            AutoConfigPlan.upperBitrateKbps(1920, 1080, 60).toDouble(),
            100.0,
        )
    }

    @Test
    fun bitrateGrowsWithResolutionAndFps() {
        val hd = AutoConfigPlan.upperBitrateKbps(1280, 720, 30)
        val fullHd = AutoConfigPlan.upperBitrateKbps(1920, 1080, 30)
        val fullHd60 = AutoConfigPlan.upperBitrateKbps(1920, 1080, 60)
        val uhd = AutoConfigPlan.upperBitrateKbps(3840, 2160, 60)
        assertTrue(hd < fullHd)
        assertTrue(fullHd < fullHd60)
        assertTrue(fullHd60 < uhd)
    }

    @Test
    fun youtubeTableFollowsOfficialNumbers() {
        assertEquals(
            17_000,
            AutoConfigPlan.youtubeBitrateKbps(1920, 1080, 60, VideoCodec.AVC),
        )
        assertEquals(
            12_000,
            AutoConfigPlan.youtubeBitrateKbps(1920, 1080, 60, VideoCodec.HEVC),
        )
        assertEquals(
            14_000,
            AutoConfigPlan.youtubeBitrateKbps(1920, 1080, 30, VideoCodec.AVC),
        )
    }

    @Test
    fun ladderFollowsCanvasAspect() {
        // Kanvas portrait: kandidat ikut rasio layar, bukan 16:9.
        val portrait = AutoConfigPlan.ladder(8, canvasWidth = 1080, canvasHeight = 2400)
        assertEquals(1080, portrait.first().width)
        assertEquals(2400, portrait.first().height)
        assertEquals(60, portrait.first().fps)
        assertTrue(portrait.any { it.width == 720 && it.height == 1600 })

        // Kanvas landscape.
        val landscape = AutoConfigPlan.ladder(8, canvasWidth = 2400, canvasHeight = 1080)
        assertEquals(2400, landscape.first().width)
        assertEquals(1080, landscape.first().height)
        assertTrue(landscape.any { it.width == 1600 && it.height == 720 })

        // Kandidat tidak boleh melebihi kanvas.
        assertTrue(portrait.all { it.width <= 1080 && it.height <= 2400 })
    }

    @Test
    fun ladderRespectsCoreLimit() {
        val many = AutoConfigPlan.ladder(10, canvasWidth = 2560, canvasHeight = 1440)
        assertEquals(1440, many.first().height)
        val mid = AutoConfigPlan.ladder(8, canvasWidth = 2560, canvasHeight = 1440)
        assertEquals(1080, mid.first().height)
        val few = AutoConfigPlan.ladder(2, canvasWidth = 1920, canvasHeight = 1080)
        assertEquals(720, few.first().height)
    }

    @Test
    fun fpsPreferenceChangesOrder() {
        val highFps = AutoConfigPlan.ladder(8, AutoConfigPlan.FpsPreference.PreferHighFps)
        assertEquals(1080, highFps.first().height)
        assertEquals(60, highFps.first().fps)
        // Setelah 1080p60, preferensi fps memilih 720p60 sebelum 1080p30.
        assertEquals(720, highFps[1].height)
        assertEquals(60, highFps[1].fps)

        val highRes = AutoConfigPlan.ladder(
            10,
            AutoConfigPlan.FpsPreference.PreferHighRes,
            canvasWidth = 2560,
            canvasHeight = 1440,
        )
        assertEquals(1440, highRes.first().height)
        assertEquals(60, highRes.first().fps)
        assertEquals(30, highRes[1].fps)
    }

    @Test
    fun forcedFpsFiltersCandidates() {
        assertTrue(
            AutoConfigPlan.ladder(8, AutoConfigPlan.FpsPreference.Force30).all { it.fps == 30 },
        )
        assertTrue(
            AutoConfigPlan.ladder(8, AutoConfigPlan.FpsPreference.Force60).all { it.fps == 60 },
        )
        assertTrue(
            AutoConfigPlan.ladder(4, AutoConfigPlan.FpsPreference.Force60).isNotEmpty(),
        )
    }

    @Test
    fun passNeedsNinetyPercentAndFewLateFrames() {
        val candidate = AutoConfigPlan.Candidate(1920, 1080, 60)
        val good = AutoConfigPlan.Probe(candidate, achievedFps = 57.5, frames = 240, lateFrames = 5)
        val slow = AutoConfigPlan.Probe(candidate, achievedFps = 50.0, frames = 240, lateFrames = 0)
        val late = AutoConfigPlan.Probe(candidate, achievedFps = 59.0, frames = 240, lateFrames = 30)
        assertTrue(good.passes())
        assertTrue(!slow.passes())
        assertTrue(!late.passes())
    }

    @Test
    fun recommendUsesFirstPassingCandidate() {
        val uhd = AutoConfigPlan.Candidate(2560, 1440, 60)
        val hd = AutoConfigPlan.Candidate(1920, 1080, 60)
        val recommendation = AutoConfigPlan.recommend(
            probes = listOf(
                AutoConfigPlan.Probe(uhd, achievedFps = 40.0, frames = 200, lateFrames = 40),
                AutoConfigPlan.Probe(hd, achievedFps = 59.0, frames = 236, lateFrames = 4),
            ),
            target = AutoConfigPlan.Target.Recording,
            codec = VideoCodec.AVC,
            hardwareEncoder = true,
        )
        assertEquals(1920, recommendation.width)
        assertEquals(60, recommendation.fps)
        assertTrue(recommendation.bitrateKbps >= AutoConfigPlan.minBitrateKbps(1920, 1080, 60))
    }

    @Test
    fun recommendCapsByUpload() {
        val candidate = AutoConfigPlan.Candidate(1920, 1080, 60)
        val recommendation = AutoConfigPlan.recommend(
            probes = listOf(
                AutoConfigPlan.Probe(candidate, achievedFps = 59.0, frames = 236, lateFrames = 2),
            ),
            target = AutoConfigPlan.Target.Streaming,
            codec = VideoCodec.AVC,
            hardwareEncoder = true,
            uploadKbps = 6000,
        )
        assertEquals(6000, recommendation.bitrateKbps)
    }
}
