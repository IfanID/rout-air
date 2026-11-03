package com.rout.air.live

import android.media.MediaCodec
import android.media.MediaFormat
import com.rout.air.core.VideoCodec
import com.rout.air.data.DiagLog
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Pembentuk tag FLV dari sampel MediaCodec.
 *
 * FLV menyimpan H.264 sebagai NAL unit berprefiks panjang 4 byte, sedangkan
 * MediaCodec mengeluarkan Annex-B (00 00 01). Konversi dan pembentukan
 * AVCDecoderConfigurationRecord ada di sini agar bisa diuji tanpa encoder.
 *
 * HEVC dan AV1 memakai ExVideoTagHeader E-RTMP: satu byte
 * 0x80 | packetType | frameType, diikuti fourCC, baru body.
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

    /**
     * Payload tag video sequence header. [config] berisi
     * AVCDecoderConfigurationRecord untuk AVC, hvcC untuk HEVC, atau
     * AV1CodecConfigurationRecord untuk AV1.
     */
    fun videoSequenceHeader(codec: VideoCodec, config: ByteArray): ByteArray =
        if (codec == VideoCodec.AVC) {
            // Jalur legacy: CodecID 7 + AVCPacketType 0.
            byteArrayOf(0x17, 0x00, 0x00, 0x00, 0x00) + config
        } else {
            byteArrayOf((EX_HEADER or FRAME_KEY or PACKET_SEQUENCE_START).toByte()) +
                fourCcBytes(codec) + config
        }

    /** Payload tag video untuk satu frame. */
    fun videoFrame(codec: VideoCodec, data: ByteArray, keyframe: Boolean): ByteArray {
        val frame = if (keyframe) FRAME_KEY else FRAME_INTER
        return when (codec) {
            VideoCodec.AVC -> byteArrayOf(
                (frame or VideoCodec.AVC.flvCodecId).toByte(),
                0x01,
                0x00,
                0x00,
                0x00,
            ) + annexBToAvcc(data)

            // HEVC tanpa B-frame: pts == dts, jadi CodedFramesX (tanpa CTS).
            VideoCodec.HEVC -> byteArrayOf(
                (EX_HEADER or frame or PACKET_CODED_FRAMES_X).toByte(),
            ) + fourCcBytes(codec) + annexBToAvcc(data)

            // AV1 tidak boleh CodedFramesX menurut spesifikasi; body sudah OBU.
            VideoCodec.AV1 -> byteArrayOf(
                (EX_HEADER or frame or PACKET_CODED_FRAMES).toByte(),
            ) + fourCcBytes(codec) + data
        }
    }

    /** Payload tag audio sequence header AAC dari AudioSpecificConfig. */
    fun audioSequenceHeader(config: ByteArray): ByteArray =
        byteArrayOf(0xAF.toByte(), 0x00) + config

    /** Payload tag audio AAC untuk satu frame. */
    fun audioFrame(aac: ByteArray): ByteArray =
        byteArrayOf(0xAF.toByte(), 0x01) + aac

    private fun fourCcBytes(codec: VideoCodec): ByteArray {
        val text = codec.fourCc
        return byteArrayOf(
            text[0].code.toByte(),
            text[1].code.toByte(),
            text[2].code.toByte(),
            text[3].code.toByte(),
        )
    }

    private fun writeShort(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private const val EX_HEADER = 0x80
    private const val FRAME_KEY = 0x10
    private const val FRAME_INTER = 0x20
    private const val PACKET_SEQUENCE_START = 0x00
    private const val PACKET_CODED_FRAMES = 0x01
    private const val PACKET_CODED_FRAMES_X = 0x03
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
    private var codec = VideoCodec.AVC
    private var videoSequence: ByteArray? = null
    private var audioSequence: ByteArray? = null
    private var metadata: ByteArray? = null
    private var width = 0
    private var height = 0
    private var fps = 0

    fun setVideoFormat(format: MediaFormat) {
        codec = VideoCodec.fromMime(format.string(MediaFormat.KEY_MIME)) ?: VideoCodec.AVC
        val csd0 = format.bytes("csd-0") ?: return
        val csd1 = format.bytes("csd-1")

        val config: ByteArray? = when (codec) {
            VideoCodec.AVC -> {
                val units = FlvCodec.splitAnnexB(csd0)
                val sps = units.firstOrNull()
                val pps = csd1?.let { FlvCodec.splitAnnexB(it).firstOrNull() }
                    ?: units.getOrNull(1)
                if (sps == null || pps == null) {
                    null
                } else {
                    FlvCodec.avcDecoderConfigurationRecord(sps, pps)
                }
            }

            VideoCodec.HEVC -> HevcConfig.configurationRecord(csd0, csd1)

            // MediaCodec AV1 sudah mengeluarkan AV1CodecConfigurationRecord.
            VideoCodec.AV1 -> csd0
        }
        if (config == null) {
            DiagLog.line("flv: sequence header ${codec.label} tidak bisa dibuat dari csd")
            return
        }
        videoSequence = FlvCodec.videoSequenceHeader(codec, config)

        width = format.int(MediaFormat.KEY_WIDTH)
        height = format.int(MediaFormat.KEY_HEIGHT)
        fps = format.int(MediaFormat.KEY_FRAME_RATE)
        metadata = buildMetadata()
    }

    fun setAudioFormat(format: MediaFormat) {
        audioSequence = format.bytes("csd-0")?.let { FlvCodec.audioSequenceHeader(it) }
    }

    /** onMetaData E-RTMP; memberi tahu server codec yang dipakai. */
    fun metadataTag(): FlvTag? = metadata?.let { FlvTag(timestampMs = 0, payload = it) }

    fun videoSequenceTag(): FlvTag? =
        videoSequence?.let { FlvTag(timestampMs = 0, payload = it) }

    fun audioSequenceTag(): FlvTag? =
        audioSequence?.let { FlvTag(timestampMs = 0, payload = it) }

    fun videoTag(data: ByteArray, presentationTimeUs: Long, flags: Int): FlvTag {
        val keyframe = flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        return FlvTag(
            timestampMs = (presentationTimeUs / 1_000L).coerceAtLeast(0L).toInt(),
            payload = FlvCodec.videoFrame(codec, data, keyframe),
        )
    }

    fun audioTag(data: ByteArray, presentationTimeUs: Long): FlvTag = FlvTag(
        timestampMs = (presentationTimeUs / 1_000L).coerceAtLeast(0L).toInt(),
        payload = FlvCodec.audioFrame(data),
    )

    private fun buildMetadata(): ByteArray {
        val values = linkedMapOf<String, Any?>(
            // AVC tetap memakai CodecID legacy 7; codec lain memakai fourCC.
            "videocodecid" to if (codec == VideoCodec.AVC) 7.0 else codec.fourCcId,
            "audiocodecid" to 10.0,
        )
        if (width > 0) values["width"] = width.toDouble()
        if (height > 0) values["height"] = height.toDouble()
        if (fps > 0) values["framerate"] = fps.toDouble()
        return Amf0.encode(listOf("@setDataFrame", "onMetaData", values))
    }

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

    private fun MediaFormat.string(key: String): String? =
        runCatching { getString(key) }.getOrNull()

    private fun MediaFormat.int(key: String): Int =
        runCatching { getInteger(key) }.getOrDefault(0)
}
