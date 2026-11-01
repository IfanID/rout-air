package com.rout.air.live

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlvCodecTest {

    private val sps = byteArrayOf(0x67, 0x42, 0xC0.toByte(), 0x1F, 0x11, 0x22)
    private val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x33, 0x44)

    @Test
    fun splitAnnexB_handlesBothStartCodes() {
        val data = byteArrayOf(
            0, 0, 0, 1, 0x65, 0x01,
            0, 0, 1, 0x41, 0x02, 0x03,
        )
        val units = FlvCodec.splitAnnexB(data)
        assertEquals(2, units.size)
        assertArrayEquals(byteArrayOf(0x65, 0x01), units[0])
        assertArrayEquals(byteArrayOf(0x41, 0x02, 0x03), units[1])
    }

    @Test
    fun annexBToAvcc_usesFourByteLengths() {
        val data = byteArrayOf(0, 0, 0, 1, 0x65, 1, 2, 0, 0, 1, 0x41, 3)
        val avcc = FlvCodec.annexBToAvcc(data)
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 3, 0x65, 1, 2, 0, 0, 0, 2, 0x41, 3),
            avcc,
        )
    }

    @Test
    fun videoSequenceHeader_containsProfileAndParameterSets() {
        val header = FlvCodec.videoSequenceHeader(sps, pps)
        assertEquals(0x17, header[0].toInt())
        assertEquals(0x00, header[1].toInt())
        // configurationVersion, profile, compat, level
        assertEquals(0x01, header[5].toInt())
        assertEquals(0x42, header[6].toInt())
        assertEquals(0xC0, header[7].toInt() and 0xFF)
        assertEquals(0x1F, header[8].toInt())
        assertTrue(header.size > 5 + 6 + 4)
    }

    @Test
    fun videoFrame_setsKeyframeFlag() {
        val key = FlvCodec.videoFrame(byteArrayOf(0, 0, 0, 1, 0x65), keyframe = true)
        val delta = FlvCodec.videoFrame(byteArrayOf(0, 0, 0, 1, 0x41), keyframe = false)
        assertEquals(0x17, key[0].toInt())
        assertEquals(0x27, delta[0].toInt())
        assertEquals(0x01, key[1].toInt())
    }

    @Test
    fun audioHeaders_matchAacFlvLayout() {
        val config = byteArrayOf(0x12, 0x10)
        val sequence = FlvCodec.audioSequenceHeader(config)
        assertEquals(0xAF, sequence[0].toInt() and 0xFF)
        assertEquals(0x00, sequence[1].toInt())
        assertArrayEquals(config, sequence.copyOfRange(2, sequence.size))

        val frame = FlvCodec.audioFrame(byteArrayOf(1, 2, 3))
        assertEquals(0xAF, frame[0].toInt() and 0xFF)
        assertEquals(0x01, frame[1].toInt())
        assertEquals(3, frame.size - 2)
    }
}
