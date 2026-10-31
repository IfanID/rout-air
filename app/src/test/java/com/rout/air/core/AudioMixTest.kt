package com.rout.air.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mengunci perilaku skala fader dan model mixer agar cocok dengan OBS. */
class AudioMixTest {
    @Test
    fun deflectionRoundTripsToDb() {
        val samples = listOf(-96f, -60f, -40f, -20f, -6f, -1f, 0f)
        samples.forEach { db ->
            val deflection = FaderScale.dbToDeflection(db)
            assertTrue("deflection $db di luar 0..1", deflection in 0f..1f)
            val back = FaderScale.deflectionToDb(deflection)
            assertEquals("putaran balik $db", db, back, 0.25f)
        }
    }

    @Test
    fun faderIsMonotonic() {
        var previous = -120f
        for (step in 0..20) {
            val deflection = step / 20f
            val db = FaderScale.deflectionToDb(deflection)
            assertTrue("dB harus naik pada $deflection", db >= previous)
            previous = db
        }
        assertEquals(0f, previous, 0.01f)
    }

    @Test
    fun formatShowsInfinityForSilence() {
        assertEquals("-∞ dB", FaderScale.formatDb(FaderScale.MIN_DB))
        assertEquals("-12.0 dB", FaderScale.formatDb(-12f))
        assertEquals("0.0 dB", FaderScale.formatDb(0f))
    }

    @Test
    fun updateChannelKeepsOtherChannels() {
        val mix = AudioMix()
        val updated = mix.update(
            mix.channel(AudioChannelId.Microphone).copy(
                enabled = true,
                gainDb = -6f,
                muted = true,
            ),
        )

        val microphone = updated.channel(AudioChannelId.Microphone)
        assertTrue(microphone.enabled)
        assertTrue(microphone.muted)
        assertEquals(-6f, microphone.gainDb, 0.001f)
        assertTrue(updated.channel(AudioChannelId.Internal).enabled)
    }

    @Test
    fun snapshotConvertsDbToLinearGain() {
        val mix = AudioMix(
            masterEnabled = false,
            channels = listOf(
                AudioChannel(AudioChannelId.Internal, gainDb = 0f),
                AudioChannel(
                    AudioChannelId.Microphone,
                    enabled = false,
                    gainDb = -6f,
                    balance = -1f,
                    monitor = MonitorMode.MonitorOnly,
                ),
            ),
        )
        val snapshot = mix.snapshot()

        assertFalse(snapshot.masterEnabled)
        val internal = snapshot.forChannel(AudioChannelId.Internal)
        val microphone = snapshot.forChannel(AudioChannelId.Microphone)
        assertEquals(1f, internal.gainLinear, 0.001f)
        assertEquals(0.501f, microphone.gainLinear, 0.01f)
        // Balance penuh ke kiri mematikan kanal kanan.
        assertEquals(0f, microphone.rightGain, 0.001f)
        assertTrue(microphone.leftGain > 0f)
        assertTrue(microphone.monitor.monitors)
        assertFalse(microphone.monitor.outputs)
        assertEquals(0x01, snapshot.outputTracks)
        assertEquals(PeakMeterType.Sample, snapshot.peakMeterType)
    }

    @Test
    fun safeOutputTracksNeverEmpty() {
        assertEquals(0x01, AudioMix(outputTracks = 0).safeOutputTracks())
        assertEquals(0x05, AudioMix(outputTracks = 0x45).safeOutputTracks())
    }

    @Test
    fun filterDefaultsAreStable() {
        val gate = AudioFilter(id = "gate", type = AudioFilterType.NoiseGate)
        assertEquals(-26f, gate.param("openThresholdDb"), 0.001f)
        assertEquals(-32f, gate.param("closeThresholdDb"), 0.001f)
        assertTrue(gate.enabled)
    }

    @Test
    fun silenceAndMuteDisableAudibleFlag() {
        val silent = ChannelMix(
            enabled = true,
            muted = false,
            gainDb = FaderScale.MIN_DB,
            balance = 0f,
            monitor = MonitorMode.Off,
            syncOffsetMs = 0,
            forceMono = false,
            tracks = 0x01,
            filters = emptyList(),
            capture = AudioCaptureKind.Playback,
            deviceId = 0,
            sco = false,
            captureUid = 0,
        )
        assertFalse(silent.audible)

        val muted = silent.copy(gainDb = 0f, muted = true)
        assertFalse(muted.audible)
    }
}
