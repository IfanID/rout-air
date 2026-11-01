package com.rout.air.output

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageGuardTest {

    @Test
    fun enoughRoom_allowsRecording() {
        assertTrue(StorageGuard.hasRoom(500L * 1024 * 1024))
    }

    @Test
    fun belowMinimum_blocksRecording() {
        assertFalse(StorageGuard.hasRoom(100L * 1024 * 1024))
    }

    @Test
    fun exactlyMinimum_isAllowed() {
        assertTrue(StorageGuard.hasRoom(StorageGuard.MIN_FREE_BYTES))
    }

    @Test
    fun customMinimum_isRespected() {
        assertFalse(StorageGuard.hasRoom(999L, minimum = 1_000L))
        assertTrue(StorageGuard.hasRoom(1_000L, minimum = 1_000L))
    }
}
