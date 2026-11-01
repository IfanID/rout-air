package com.rout.air.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingClockTest {

    private val second = 1_000_000_000L

    @Test
    fun elapsed_countsFromStart() {
        val clock = RecordingClock()
        clock.start(10 * second)
        assertEquals(3 * second, clock.elapsedNs(13 * second))
        assertEquals(3_000L, clock.elapsedMs(13 * second))
    }

    @Test
    fun pause_freezesElapsed() {
        val clock = RecordingClock()
        clock.start(0)
        clock.pause(5 * second)
        assertTrue(clock.isPaused)
        // Meski jam dinding berjalan, durasi rekaman tetap di titik jeda.
        assertEquals(5 * second, clock.elapsedNs(60 * second))
    }

    @Test
    fun resume_excludesPausedTime() {
        val clock = RecordingClock()
        clock.start(0)
        clock.pause(5 * second)
        clock.resume(15 * second) // dijeda 10 detik
        assertFalse(clock.isPaused)
        assertEquals(10_000L, clock.pausedTotalMs)
        assertEquals(5 * second, clock.elapsedNs(15 * second))
        assertEquals(7 * second, clock.elapsedNs(17 * second))
    }

    @Test
    fun multiplePauses_accumulate() {
        val clock = RecordingClock()
        clock.start(0)
        clock.pause(2 * second)
        clock.resume(4 * second) // 2 detik
        clock.pause(10 * second)
        clock.resume(13 * second) // 3 detik
        assertEquals(5_000L, clock.pausedTotalMs)
        assertEquals(10 * second, clock.elapsedNs(15 * second))
    }

    @Test
    fun pauseAndResume_areIdempotent() {
        val clock = RecordingClock()
        clock.start(0)
        clock.resume(3 * second) // belum dijeda: tidak mengubah apa pun
        assertEquals(3 * second, clock.elapsedNs(3 * second))
        clock.pause(3 * second)
        clock.pause(8 * second) // jeda kedua diabaikan
        assertEquals(3 * second, clock.elapsedNs(20 * second))
    }

    @Test
    fun reset_clearsEverything() {
        val clock = RecordingClock()
        clock.start(0)
        clock.pause(2 * second)
        clock.reset()
        assertFalse(clock.isStarted)
        assertFalse(clock.isPaused)
        assertEquals(0L, clock.elapsedNs(10 * second))
    }

    @Test
    fun elapsed_neverNegative() {
        val clock = RecordingClock()
        clock.start(10 * second)
        assertEquals(0L, clock.elapsedNs(9 * second))
    }

    @Test
    fun tenMinuteSession_withPauses_staysConsistent() {
        // Simulasi rekaman 10 menit dengan lima jeda 30 detik. Durasi efektif
        // harus tepat 10 menit dikurangi total jeda, bukan waktu dinding.
        val clock = RecordingClock()
        clock.start(0)
        var now = 0L
        repeat(5) {
            now += 60 * second // 60 detik merekam
            clock.pause(now)
            now += 30 * second // 30 detik dijeda
            clock.resume(now)
        }
        now += 5 * 60 * second // 5 menit terakhir merekam
        val effective = 5 * 60 * second + 5 * 60 * second
        assertEquals(effective, clock.elapsedNs(now))
        assertEquals(150_000L, clock.pausedTotalMs)
    }
}
