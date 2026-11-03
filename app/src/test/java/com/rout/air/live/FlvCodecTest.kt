package com.rout.air.live

import com.rout.air.core.VideoCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlvCodecTest {

    private val sps = byteArrayOf(0x67, 0x42, 0xC0.toByte(), 0x1F, 0x11, 0x22)
    private val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x33, 0x44)
    private val hvc1 = byteArrayOf('h'.code.toByte(), 'v'.code.toByte(), 'c'.code.toByte(), '1'.code.toByte())
    private val av01 = byteArrayOf('a'.code.toByte(), 'v'.code.toByte(), '0'.code.toByte(), '1'.code.toByte())

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
    fun avcSequenceHeader_containsProfileAndParameterSets() {
        val avcC = FlvCodec.avcDecoderConfigurationRecord(sps, pps)
        val header = FlvCodec.videoSequenceHeader(VideoCodec.AVC, avcC)
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
    fun avcFrame_keepsLegacyHeader() {
        val key = FlvCodec.videoFrame(
            VideoCodec.AVC,
            byteArrayOf(0, 0, 0, 1, 0x65),
            keyframe = true,
        )
        val delta = FlvCodec.videoFrame(
            VideoCodec.AVC,
            byteArrayOf(0, 0, 0, 1, 0x41),
            keyframe = false,
        )
        assertEquals(0x17, key[0].toInt())
        assertEquals(0x27, delta[0].toInt())
        assertEquals(0x01, key[1].toInt())
    }

    @Test
    fun hevcSequenceHeader_usesEnhancedHeaderAndFourCc() {
        val config = byteArrayOf(0x01, 0x02, 0x03)
        val header = FlvCodec.videoSequenceHeader(VideoCodec.HEVC, config)
        assertEquals(0x90, header[0].toInt() and 0xFF)
        assertArrayEquals(hvc1, header.copyOfRange(1, 5))
        assertArrayEquals(config, header.copyOfRange(5, header.size))
    }

    @Test
    fun av1SequenceHeader_usesEnhancedHeaderAndFourCc() {
        val config = byteArrayOf(0x81.toByte(), 0x00, 0x0C)
        val header = FlvCodec.videoSequenceHeader(VideoCodec.AV1, config)
        assertEquals(0x90, header[0].toInt() and 0xFF)
        assertArrayEquals(av01, header.copyOfRange(1, 5))
        assertArrayEquals(config, header.copyOfRange(5, header.size))
    }

    @Test
    fun hevcFrame_usesCodedFramesXAndLengthPrefixedNalus() {
        val key = FlvCodec.videoFrame(
            VideoCodec.HEVC,
            byteArrayOf(0, 0, 0, 1, 0x26, 0x01, 0, 0, 0, 1, 0x02, 0x02),
            keyframe = true,
        )
        assertEquals(0x93, key[0].toInt() and 0xFF)
        assertArrayEquals(hvc1, key.copyOfRange(1, 5))
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 2, 0x26, 0x01, 0, 0, 0, 2, 0x02, 0x02),
            key.copyOfRange(5, key.size),
        )

        val delta = FlvCodec.videoFrame(VideoCodec.HEVC, byteArrayOf(0, 0, 0, 1, 0x02), keyframe = false)
        assertEquals(0xA3, delta[0].toInt() and 0xFF)
    }

    @Test
    fun av1Frame_sendsObusWithoutLengthPrefix() {
        val obu = byteArrayOf(0x0A, 0x0B, 0x0C)
        val key = FlvCodec.videoFrame(VideoCodec.AV1, obu, keyframe = true)
        assertEquals(0x91, key[0].toInt() and 0xFF)
        assertArrayEquals(av01, key.copyOfRange(1, 5))
        assertArrayEquals(obu, key.copyOfRange(5, key.size))

        val delta = FlvCodec.videoFrame(VideoCodec.AV1, obu, keyframe = false)
        assertEquals(0xA1, delta[0].toInt() and 0xFF)
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
