package com.rout.air.output

import org.junit.Assert.assertEquals
import org.junit.Test

class WatchdogPolicyTest {

    private val stall = 5_000_000_000L // 5 detik

    @Test
    fun screenLocked_framesStop_recordingContinues() {
        // Layar dikunci: frame terakhir 60 detik lalu, tapi encoder tetap
        // menghasilkan frame ulangan setiap interval fps.
        val now = 1_000_000_000_000L
        val action = WatchdogPolicy.decide(
            recordingActive = true,
            nowNs = now,
            lastEncodedNs = now - 100_000_000L, // 100 ms lalu
            consecutiveSwapFailures = 0,
            stallTimeoutNs = stall,
        )
        assertEquals(WatchdogAction.Continue, action)
    }

    @Test
    fun screenOff_16minutes_framesStalled_encoderAlive_continues() {
        val action = WatchdogPolicy.decide(
            recordingActive = true,
            nowNs = 16 * 60 * 1_000_000_000L,
            lastEncodedNs = 16 * 60 * 1_000_000_000L - 50_000_000L,
            consecutiveSwapFailures = 0,
            stallTimeoutNs = stall,
        )
        assertEquals(WatchdogAction.Continue, action)
    }

    @Test
    fun encoderStalled_stopsRecording() {
        val now = 10_000_000_000L
        val action = WatchdogPolicy.decide(
            recordingActive = true,
            nowNs = now,
            lastEncodedNs = now - 6_000_000_000L,
            consecutiveSwapFailures = 0,
            stallTimeoutNs = stall,
        )
        assertEquals(WatchdogAction.EncoderStalled, action)
    }

    @Test
    fun repeatedSurfaceFailures_stopsRecording() {
        val action = WatchdogPolicy.decide(
            recordingActive = true,
            nowNs = 1_000_000_000L,
            lastEncodedNs = 900_000_000L,
            consecutiveSwapFailures = 31,
            stallTimeoutNs = stall,
        )
        assertEquals(WatchdogAction.EncoderSurfaceFailed, action)
    }

    @Test
    fun notRecording_neverStops() {
        val now = 100_000_000_000L
        val action = WatchdogPolicy.decide(
            recordingActive = false,
            nowNs = now,
            lastEncodedNs = now - 60_000_000_000L,
            consecutiveSwapFailures = 99,
            stallTimeoutNs = stall,
        )
        assertEquals(WatchdogAction.Continue, action)
    }

    @Test
    fun paused_longTime_isNotTreatedAsStall() {
        // Saat dijeda encoder memang tidak menerima frame; itu bukan macet.
        val now = 100_000_000_000L
        val action = WatchdogPolicy.decide(
            recordingActive = true,
            nowNs = now,
            lastEncodedNs = now - 60_000_000_000L,
            consecutiveSwapFailures = 0,
            stallTimeoutNs = stall,
            paused = true,
        )
        assertEquals(WatchdogAction.Continue, action)
    }

    @Test
    fun repeatedWriteFailures_stopsRecording() {
        val action = WatchdogPolicy.decide(
            recordingActive = true,
            nowNs = 1_000_000_000L,
            lastEncodedNs = 900_000_000L,
            consecutiveSwapFailures = 0,
            stallTimeoutNs = stall,
            writeFailures = WatchdogPolicy.MAX_WRITE_FAILURES + 1,
        )
        assertEquals(WatchdogAction.StorageFailed, action)
    }

    @Test
    fun fewWriteFailures_keepsRecording() {
        val action = WatchdogPolicy.decide(
            recordingActive = true,
            nowNs = 1_000_000_000L,
            lastEncodedNs = 900_000_000L,
            consecutiveSwapFailures = 0,
            stallTimeoutNs = stall,
            writeFailures = 2,
        )
        assertEquals(WatchdogAction.Continue, action)
    }
}
