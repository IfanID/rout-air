package com.rout.air.live.format

import java.io.ByteArrayOutputStream

/**
 * Membentuk HEVCDecoderConfigurationRecord (hvcC) untuk sequence header
 * E-RTMP.
 *
 * Encoder HEVC Android umumnya mengeluarkan csd-0 dalam bentuk Annex-B
 * (VPS/SPS/PPS dengan start code), sedangkan E-RTMP meminta hvcC. Bila
 * csd-0 ternyata sudah hvcC, data dipakai apa adanya.
 */
object HevcConfig {

    fun configurationRecord(csd0: ByteArray, csd1: ByteArray? = null): ByteArray? {
        if (csd0.size >= 23 && (csd0[0].toInt() and 0xFF) == 1) return csd0

        val units = buildList {
            addAll(FlvCodec.splitAnnexB(csd0))
            if (csd1 != null && csd1.isNotEmpty()) addAll(FlvCodec.splitAnnexB(csd1))
        }
        val vps = units.firstOrNull { nalUnitType(it) == NAL_VPS } ?: return null
        val sps = units.firstOrNull { nalUnitType(it) == NAL_SPS } ?: return null
        val pps = units.firstOrNull { nalUnitType(it) == NAL_PPS } ?: return null
        val info = runCatching { parseSps(sps) }.getOrNull() ?: return null

        val out = ByteArrayOutputStream(23 + vps.size + sps.size + pps.size + 16)
        out.write(1) // configurationVersion
        out.write((info.profileSpace shl 6) or (info.tierFlag shl 5) or info.profileIdc)
        out.write(info.compatibility, 0, 4)
        out.write(info.constraints, 0, 6)
        out.write(info.levelIdc)
        out.write(0xF0) // reserved + min_spatial_segmentation_idc = 0
        out.write(0x00)
        out.write(0xFC) // reserved + parallelismType = 0
        out.write(0xFC or (info.chromaFormat and 0x03))
        out.write(0xF8 or (info.bitDepthLuma and 0x07))
        out.write(0xF8 or (info.bitDepthChroma and 0x07))
        out.write(0) // avgFrameRate
        out.write(0)
        out.write(
            (info.numTemporalLayers.coerceIn(1, 7) shl 3) or
                0x04 or // temporalIdNested = 1
                0x03, // lengthSizeMinusOne = 3
        )
        out.write(3) // numOfArrays: VPS, SPS, PPS
        writeArray(out, NAL_VPS, vps)
        writeArray(out, NAL_SPS, sps)
        writeArray(out, NAL_PPS, pps)
        return out.toByteArray()
    }

    private fun writeArray(out: ByteArrayOutputStream, type: Int, nal: ByteArray) {
        out.write(0x80 or type) // array_completeness = 1
        out.write(0)
        out.write(1) // numNalus
        out.write((nal.size ushr 8) and 0xFF)
        out.write(nal.size and 0xFF)
        out.write(nal, 0, nal.size)
    }

    private fun nalUnitType(nal: ByteArray): Int =
        if (nal.size < 2) -1 else (nal[0].toInt() ushr 1) and 0x3F

    private class SpsInfo(
        val profileSpace: Int,
        val tierFlag: Int,
        val profileIdc: Int,
        val compatibility: ByteArray,
        val constraints: ByteArray,
        val levelIdc: Int,
        val chromaFormat: Int,
        val bitDepthLuma: Int,
        val bitDepthChroma: Int,
        val numTemporalLayers: Int,
    )

    private fun parseSps(sps: ByteArray): SpsInfo {
        // Dua byte pertama adalah NAL unit header.
        val reader = BitReader(sps, startByte = 2)
        reader.skip(4) // sps_video_parameter_set_id
        val subLayersMinus1 = reader.bits(3)
        reader.skip(1) // sps_temporal_id_nesting_flag

        val profileSpace = reader.bits(2)
        val tierFlag = reader.bits(1)
        val profileIdc = reader.bits(5)
        val compatibility = ByteArray(4) { reader.bits(8).toByte() }
        // 4 flag sumber + 44 bit constraint, persis isi hvcC.
        val constraints = ByteArray(6) { reader.bits(8).toByte() }
        val levelIdc = reader.bits(8)

        if (subLayersMinus1 > 0) {
            val profilePresent = BooleanArray(subLayersMinus1)
            val levelPresent = BooleanArray(subLayersMinus1)
            for (index in 0 until subLayersMinus1) {
                profilePresent[index] = reader.bit() == 1
                levelPresent[index] = reader.bit() == 1
            }
            for (index in subLayersMinus1 until 8) reader.skip(2)
            for (index in 0 until subLayersMinus1) {
                if (profilePresent[index]) reader.skip(88)
                if (levelPresent[index]) reader.skip(8)
            }
        }

        reader.ue() // sps_seq_parameter_set_id
        val chromaFormat = reader.ue()
        if (chromaFormat == 3) reader.skip(1) // separate_colour_plane_flag
        reader.ue() // pic_width_in_luma_samples
        reader.ue() // pic_height_in_luma_samples
        if (reader.bit() == 1) {
            reader.ue()
            reader.ue()
            reader.ue()
            reader.ue()
        }
        val bitDepthLuma = reader.ue()
        val bitDepthChroma = reader.ue()

        return SpsInfo(
            profileSpace = profileSpace,
            tierFlag = tierFlag,
            profileIdc = profileIdc,
            compatibility = compatibility,
            constraints = constraints,
            levelIdc = levelIdc,
            chromaFormat = chromaFormat,
            bitDepthLuma = bitDepthLuma,
            bitDepthChroma = bitDepthChroma,
            numTemporalLayers = subLayersMinus1 + 1,
        )
    }

    /** Pembaca bit MSB-first dengan Exp-Golomb untuk SPS HEVC. */
    private class BitReader(
        private val data: ByteArray,
        startByte: Int,
    ) {
        private var position = startByte * 8

        fun bit(): Int {
            require(position < data.size * 8) { "SPS terlalu pendek" }
            val value = (data[position ushr 3].toInt() ushr (7 - (position and 7))) and 1
            position++
            return value
        }

        fun bits(count: Int): Int {
            var value = 0
            repeat(count) { value = (value shl 1) or bit() }
            return value
        }

        fun skip(count: Int) {
            require(position + count <= data.size * 8) { "SPS terlalu pendek" }
            position += count
        }

        fun ue(): Int {
            var zeros = 0
            while (bit() == 0) {
                zeros++
                require(zeros <= 31) { com.rout.air.data.common.Msg.get(com.rout.air.R.string.error_exp_golomb) }
            }
            if (zeros == 0) return 0
            return (1 shl zeros) - 1 + bits(zeros)
        }
    }

    private const val NAL_VPS = 32
    private const val NAL_SPS = 33
    private const val NAL_PPS = 34
}
