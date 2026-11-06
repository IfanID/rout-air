package com.rout.air.live

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.rout.air.live.format.HevcConfig

/** Mengunci pembentukan hvcC dari VPS/SPS/PPS hasil encoder HEVC Android. */
class HevcConfigTest {

    @Test
    fun buildsHvcCFromAnnexB() {
        val record = HevcConfig.configurationRecord(annexB())
        assertNotNull(record)
        record!!

        assertEquals(1, record[0].toInt()) // configurationVersion
        assertEquals(1, record[1].toInt()) // profile_space 0, tier 0, profile_idc 1
        assertArrayEquals(byteArrayOf(0x60, 0, 0, 0), record.copyOfRange(2, 6))
        assertEquals(0xB0, record[6].toInt() and 0xFF) // progressive + non-packed + frame-only
        assertEquals(120, record[12].toInt() and 0xFF) // level 4.0
        assertEquals(0xF0, record[13].toInt() and 0xFF)
        assertEquals(0x00, record[14].toInt() and 0xFF)
        assertEquals(0xFC, record[15].toInt() and 0xFF)
        assertEquals(0xFD, record[16].toInt() and 0xFF) // chroma_format_idc = 1
        assertEquals(0xF8, record[17].toInt() and 0xFF) // bit depth 8
        assertEquals(0xF8, record[18].toInt() and 0xFF)
        assertEquals(0x0F, record[21].toInt() and 0xFF) // 1 temporal layer, nested, 4 byte
        assertEquals(3, record[22].toInt()) // VPS, SPS, PPS
        assertTrue(record.size > 40)
    }

    @Test
    fun passesThroughExistingHvcC() {
        val existing = ByteArray(30) { index -> if (index == 0) 1 else index.toByte() }
        assertArrayEquals(existing, HevcConfig.configurationRecord(existing))
    }

    @Test
    fun returnsNullWhenParameterSetsMissing() {
        assertNull(HevcConfig.configurationRecord(byteArrayOf(0, 0, 0, 1, 0x42, 0x01)))
    }

    private fun annexB(): ByteArray =
        byteArrayOf(0, 0, 0, 1) + vps() +
            byteArrayOf(0, 0, 0, 1) + sps() +
            byteArrayOf(0, 0, 0, 1) + pps()

    private fun vps(): ByteArray = byteArrayOf(0x40, 0x01, 0x0C, 0x01)

    private fun pps(): ByteArray = byteArrayOf(0x44, 0x01, 0xC1.toByte(), 0x73)

    /** SPS sintetis dengan nilai yang bisa diprediksi. */
    private fun sps(): ByteArray {
        val writer = BitWriter()
        writer.bits(0, 4) // sps_video_parameter_set_id
        writer.bits(0, 3) // sps_max_sub_layers_minus1
        writer.bit(1) // sps_temporal_id_nesting_flag
        writer.bits(0, 2) // general_profile_space
        writer.bit(0) // general_tier_flag
        writer.bits(1, 5) // general_profile_idc = Main
        writer.bits(0x60000000, 32) // compatibility flags
        writer.bit(1) // progressive_source
        writer.bit(0) // interlaced_source
        writer.bit(1) // non_packed_constraint
        writer.bit(1) // frame_only_constraint
        repeat(44) { writer.bit(0) } // constraint flags
        writer.bits(120, 8) // general_level_idc
        writer.ue(0) // sps_seq_parameter_set_id
        writer.ue(1) // chroma_format_idc 4:2:0
        writer.ue(1920) // pic_width_in_luma_samples
        writer.ue(1080) // pic_height_in_luma_samples
        writer.bit(0) // conformance_window_flag
        writer.ue(0) // bit_depth_luma_minus8
        writer.ue(0) // bit_depth_chroma_minus8
        writer.bit(1) // rbsp_stop_one_bit
        return byteArrayOf(0x42, 0x01) + writer.toBytes()
    }

    private class BitWriter {
        private val bits = mutableListOf<Int>()

        fun bit(value: Int) {
            bits += value and 1
        }

        fun bits(value: Int, count: Int) {
            for (index in count - 1 downTo 0) bit(value ushr index)
        }

        fun ue(value: Int) {
            val code = value + 1
            val length = 32 - Integer.numberOfLeadingZeros(code)
            repeat(length - 1) { bit(0) }
            bits(code, length)
        }

        fun toBytes(): ByteArray {
            val out = ByteArray((bits.size + 7) / 8)
            bits.forEachIndexed { index, value ->
                if (value == 1) {
                    out[index / 8] = (out[index / 8].toInt() or (0x80 ushr (index % 8))).toByte()
                }
            }
            return out
        }
    }
}
