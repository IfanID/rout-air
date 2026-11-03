package com.rout.air.encode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mengunci perhitungan beban benchmark tanpa perlu perangkat Android. */
class GameLoadBenchmarkTest {

    @Test
    fun trafficScalesObjects() {
        assertEquals(600, GameLoadBenchmark.effectiveObjects(GameLoadBenchmark.Preset.High, 100))
        assertEquals(300, GameLoadBenchmark.effectiveObjects(GameLoadBenchmark.Preset.High, 50))
        // Traffic 0 tetap menyisakan objek minimal supaya adegan tidak kosong.
        assertEquals(8, GameLoadBenchmark.effectiveObjects(GameLoadBenchmark.Preset.High, 0))
    }

    @Test
    fun trafficIsClamped() {
        assertEquals(
            GameLoadBenchmark.Preset.Low.objects,
            GameLoadBenchmark.effectiveObjects(GameLoadBenchmark.Preset.Low, 150),
        )
    }

    @Test
    fun presetsGrowInWeight() {
        val presets = GameLoadBenchmark.Preset.entries
        presets.zipWithNext { lower, higher ->
            assertTrue(
                "${higher.label} harus lebih berat dari ${lower.label}",
                higher.objects > lower.objects && higher.layers >= lower.layers,
            )
        }
    }

    @Test
    fun bitrateScalesWithPixelsAndFps() {
        assertEquals(12_000_000, GameLoadBenchmark.bitrateFor(1920, 1080, 60))
        // 720p30 hanya seperempat beban piksel, tetapi tetap minimal 4 Mbps.
        assertEquals(4_000_000, GameLoadBenchmark.bitrateFor(1280, 720, 30))
        // 4K120 delapan kali acuan, dibatasi 60 Mbps.
        assertEquals(60_000_000, GameLoadBenchmark.bitrateFor(3840, 2160, 120))
    }

    @Test
    fun manualBitrateIsClamped() {
        assertEquals(2_000_000, GameLoadBenchmark.manualBitrate(0f))
        assertEquals(12_000_000, GameLoadBenchmark.manualBitrate(12f))
        assertEquals(100_000_000, GameLoadBenchmark.manualBitrate(500f))
    }

    @Test
    fun bitrateNeverDropsBelowFloor() {
        assertTrue(GameLoadBenchmark.bitrateFor(640, 360, 30) >= 4_000_000)
    }

    @Test
    fun worstSecondUsesSlowestSecond() {
        assertEquals(48.0, GameLoadBenchmark.worstSecond(listOf(60, 58, 48, 59)), 0.001)
        assertEquals(0.0, GameLoadBenchmark.worstSecond(emptyList()), 0.001)
    }

    @Test
    fun thermalNamesAreReadable() {
        assertEquals("normal", GameLoadBenchmark.thermalName(0))
        assertEquals("panas", GameLoadBenchmark.thermalName(2))
        assertEquals("tidak diketahui", GameLoadBenchmark.thermalName(99))
    }
}
