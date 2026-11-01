package com.rout.air.live

import android.media.MediaCodec
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Pembentuk tag FLV dari sampel MediaCodec.
 *
 * FLV menyimpan H.264 sebagai NAL unit berprefiks panjang 4 byte, sedangkan
 * MediaCodec mengeluarkan Annex-B (00 00 01). Konversi dan pembentukan
 * AVCDecoderConfigurationRecord ada di sini agar bisa diuji tanpa encoder.
 */
object FlvCodec {

    /** Memecah Annex-B menjadi daftar NAL unit (tanpa start code). */
    fun splitAnnexB(data: ByteArray): List<ByteArray> {
        val units = mutableListOf<ByteArray>()
        var index = 0
        var start = -1
        while (index + 2 < data.size) {
            val three = data[index].toInt() == 0 &&
                data[index + 1].toInt() == 0 &&
                data[index + 2].toInt() == 1
            val four = index + 3 < data.size &&
                data[index].toInt() == 0 &&
                data[index + 1].toInt() == 0 &&
                data[index + 2].toInt() == 0 &&
                data[index + 3].toInt() == 1
            if (three || four) {
                if (start >= 0) {
                    units += data.copyOfRange(start, index)
                }
                index += if (four) 4 else 3
                start = index
            } else {
                index++
            }
        }
        if (start >= 0 && start < data.size) units += data.copyOfRange(start, data.size)
        // Tanpa start code: anggap seluruh data satu NAL unit.
        return if (units.isEmpty()) listOf(data) else units
    }

    /** Mengubah Annex-B menjadi format AVCC (panjang 4 byte + NAL). */
    fun annexBToAvcc(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size + 16)
        splitAnnexB(data).forEach { unit ->
            if (unit.isEmpty()) return@forEach
            out.write((unit.size ushr 24) and 0xFF)
            out.write((unit.size ushr 16) and 0xFF)
            out.write((unit.size ushr 8) and 0xFF)
            out.write(unit.size and 0xFF)
            out.write(unit, 0, unit.size)
        }
        return out.toByteArray()
    }

    /**
     * AVCDecoderConfigurationRecord dari SPS/PPS. Nilai profil/level diambil
     * dari SPS supaya cocok dengan stream encoder perangkat.
     */
    fun avcDecoderConfigurationRecord(sps: ByteArray, pps: ByteArray): ByteArray {
        require(sps.size >= 4) { "SPS terlalu pendek" }
        val out = ByteArrayOutputStream(11 + sps.size + pps.size)
        out.write(0x01) // configurationVersion
        out.write(sps[1].toInt() and 0xFF) // AVCProfileIndication
        out.write(sps[2].toInt() and 0xFF) // profile_compatibility
        out.write(sps[3].toInt() and 0xFF) // AVCLevelIndication
        out.write(0xFF) // lengthSizeMinusOne = 3
        out.write(0xE1) // numOfSequenceParameterSets = 1
        writeShort(out, sps.size)
        out.write(sps, 0, sps.size)
        out.write(0x01) // numOfPictureParameterSets
        writeShort(out, pps.size)
        out.write(pps, 0, pps.size)
        return out.toByteArray()
    }

    /** Payload tag video sequence header FLV dari SPS/PPS. */
    fun videoSequenceHeader(sps: ByteArray, pps: ByteArray): ByteArray {
        val record = avcDecoderConfigurationRecord(sps, pps)
        return byteArrayOf(0x17, 0x00, 0x00, 0x00, 0x00) + record
    }

    /** Payload tag video untuk satu frame (AVCC + header FLV). */
    fun videoFrame(annexB: ByteArray, keyframe: Boolean): ByteArray {
        val avcc = annexBToAvcc(annexB)
        val header = byteArrayOf(
            if (keyframe) 0x17 else 0x27,
            0x01,
            0x00,
            0x00,
            0x00,
        )
        return header + avcc
    }

    /** Payload tag audio sequence header AAC dari AudioSpecificConfig. */
    fun audioSequenceHeader(config: ByteArray): ByteArray =
        byteArrayOf(0xAF.toByte(), 0x00) + config

    /** Payload tag audio AAC untuk satu frame. */
    fun audioFrame(aac: ByteArray): ByteArray =
        byteArrayOf(0xAF.toByte(), 0x01) + aac

    private fun writeShort(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }
}

/** Format FLV yang sudah siap dikirim ke RTMP. */
data class FlvTag(
    val timestampMs: Int,
    val payload: ByteArray,
)

/**
 * Membungkus MediaFormat dari encoder menjadi tag FLV. Hanya dipakai di
 * perangkat; logika byte-nya diuji lewat [FlvCodec].
 */
class FlvMuxer {
    private var videoSequence: ByteArray? = null
    private var audioSequence: ByteArray? = null

    fun setVideoFormat(format: MediaFormat) {
        val csd0 = format.bytes("csd-0") ?: return
        val csd1 = format.bytes("csd-1")
        val units = FlvCodec.splitAnnexB(csd0)
        val sps = units.firstOrNull() ?: return
        val pps = csd1?.let { FlvCodec.splitAnnexB(it).firstOrNull() }
            ?: units.getOrNull(1)
            ?: return
        videoSequence = FlvCodec.videoSequenceHeader(sps, pps)
    }

    fun setAudioFormat(format: MediaFormat) {
        audioSequence = format.bytes("csd-0")?.let { FlvCodec.audioSequenceHeader(it) }
    }

    fun videoSequenceTag(): FlvTag? =
        videoSequence?.let { FlvTag(timestampMs = 0, payload = it) }

    fun audioSequenceTag(): FlvTag? =
        audioSequence?.let { FlvTag(timestampMs = 0, payload = it) }

    fun videoTag(data: ByteArray, presentationTimeUs: Long, flags: Int): FlvTag {
        val keyframe = flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        return FlvTag(
            timestampMs = (presentationTimeUs / 1_000L).coerceAtLeast(0L).toInt(),
            payload = FlvCodec.videoFrame(data, keyframe),
        )
    }

    fun audioTag(data: ByteArray, presentationTimeUs: Long): FlvTag = FlvTag(
        timestampMs = (presentationTimeUs / 1_000L).coerceAtLeast(0L).toInt(),
        payload = FlvCodec.audioFrame(data),
    )

    private fun MediaFormat.bytes(key: String): ByteArray? {
        val buffer: ByteBuffer = try {
            getByteBuffer(key) ?: return null
        } catch (_: ClassCastException) {
            return null
        }
        val copy = ByteArray(buffer.remaining())
        buffer.get(copy)
        return copy
    }
}
