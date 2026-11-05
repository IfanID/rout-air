package com.rout.air.core

/**
 * Codec video yang bisa diminta ke MediaCodec.
 *
 * MIME dipakai untuk membuat format encoder, fourCC dipakai untuk tag FLV
 * gaya E-RTMP (HEVC/AV1). AVC tetap memakai jalur FLV legacy dengan CodecID 7
 * supaya kompatibel dengan server lama.
 */
enum class VideoCodec(
    val label: String,
    val mime: String,
    val fourCc: String,
    val fourCcId: Double,
) {
    AVC("H.264", "video/avc", "avc1", 1635141631.0),
    HEVC("H.265", "video/hevc", "hvc1", 1752589105.0),
    AV1("AV1", "video/av01", "av01", 1635135537.0),
    ;

    /** FLV legacy hanya mengenal AVC (CodecID 7); codec lain wajib E-RTMP. */
    val flvCodecId: Int get() = if (this == AVC) 7 else 0

    val enhanced: Boolean get() = this != AVC

    companion object {
        fun fromMime(mime: String?): VideoCodec? = entries.firstOrNull { it.mime == mime }

        fun fromFourCc(fourCc: String): VideoCodec? = entries.firstOrNull { it.fourCc == fourCc }
    }
}
