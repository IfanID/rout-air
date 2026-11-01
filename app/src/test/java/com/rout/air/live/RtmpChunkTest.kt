package com.rout.air.live

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class RtmpChunkTest {

    @Test
    fun messageLargerThanChunk_isReassembled() {
        val out = ByteArrayOutputStream()
        val writer = RtmpChunkWriter(out)
        val payload = ByteArray(300) { (it and 0xFF).toByte() }
        writer.write(RtmpType.VIDEO, streamId = 7, timestampMs = 1_234, payload = payload)

        val messages = mutableListOf<RtmpMessage>()
        RtmpChunkReader(ByteArrayInputStream(out.toByteArray())) { messages += it }.readLoop()

        assertEquals(1, messages.size)
        assertEquals(RtmpType.VIDEO, messages[0].type)
        assertEquals(7, messages[0].streamId)
        assertEquals(1_234, messages[0].timestampMs)
        assertArrayEquals(payload, messages[0].payload)
    }

    @Test
    fun setChunkSize_isAppliedBeforeNextMessage() {
        val out = ByteArrayOutputStream()
        val writer = RtmpChunkWriter(out)
        writer.writeSetChunkSize(4_096)
        val payload = ByteArray(1_000) { 1 }
        writer.write(RtmpType.AUDIO, streamId = 3, timestampMs = 50, payload = payload)

        val messages = mutableListOf<RtmpMessage>()
        RtmpChunkReader(ByteArrayInputStream(out.toByteArray())) { messages += it }.readLoop()

        assertEquals(1, messages.size)
        assertEquals(RtmpType.AUDIO, messages[0].type)
        assertEquals(1_000, messages[0].payload.size)
    }

    @Test
    fun multipleMessages_keepOrder() {
        val out = ByteArrayOutputStream()
        val writer = RtmpChunkWriter(out)
        writer.write(RtmpType.AUDIO, 1, 10, byteArrayOf(0xAF.toByte(), 1, 2))
        writer.write(RtmpType.VIDEO, 1, 20, byteArrayOf(0x17, 1, 3))

        val messages = mutableListOf<RtmpMessage>()
        RtmpChunkReader(ByteArrayInputStream(out.toByteArray())) { messages += it }.readLoop()

        assertEquals(listOf(RtmpType.AUDIO, RtmpType.VIDEO), messages.map { it.type })
        assertEquals(listOf(10, 20), messages.map { it.timestampMs })
    }
}
