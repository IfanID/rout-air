package com.rout.air.encode

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingRecordingPolicyTest {

    private val now = 1_000_000_000_000L
    private val hour = 60L * 60L * 1000L

    @Test
    fun finishedFile_isNeverStale() {
        assertFalse(PendingRecordingPolicy.isStale(now, now - 5 * hour, pending = false))
    }

    @Test
    fun recentPending_isKept() {
        assertFalse(PendingRecordingPolicy.isStale(now, now - 10 * 60 * 1000L, pending = true))
    }

    @Test
    fun oldPending_isStale() {
        assertTrue(PendingRecordingPolicy.isStale(now, now - 2 * hour, pending = true))
    }

    @Test
    fun missingDateAdded_isKept() {
        assertFalse(PendingRecordingPolicy.isStale(now, 0L, pending = true))
    }
}
