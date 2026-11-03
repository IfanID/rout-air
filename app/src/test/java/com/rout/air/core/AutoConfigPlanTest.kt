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
    fun ladderStartsHighOnManyCores() {
        // 8 inti: mulai 1080p60 seperti maxDataRate OBS.
        assertEquals(1080, AutoConfigPlan.ladder(8).first().height)
        assertEquals(1920, AutoConfigPlan.ladder(8).first().width)
        // Di atas 8 inti baru 1440p.
        assertEquals(1440, AutoConfigPlan.ladder(10).first().height)
        assertEquals(1920, AutoConfigPlan.ladder(6).first().width)
        // Dengan "Utamakan fps", perangkat 4 inti mulai dari 720p60.
        assertEquals(60, AutoConfigPlan.ladder(4).first().fps)
        assertEquals(720, AutoConfigPlan.ladder(4).first().height)
        assertEquals(720, AutoConfigPlan.ladder(2).first().height)
    }

    @Test
    fun fpsPreferenceChangesOrder() {
        val highFps = AutoConfigPlan.ladder(8, AutoConfigPlan.FpsPreference.PreferHighFps)
        assertEquals(1080, highFps.first().height)
        assertEquals(60, highFps.first().fps)
        // Setelah 1080p60, preferensi fps memilih 720p60 sebelum 1080p30.
        assertEquals(720, highFps[1].height)
        assertEquals(60, highFps[1].fps)

        val highRes = AutoConfigPlan.ladder(10, AutoConfigPlan.FpsPreference.PreferHighRes)
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
        assertEquals(
            1,
            AutoConfigPlan.ladder(4, AutoConfigPlan.FpsPreference.Force60).size,
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
