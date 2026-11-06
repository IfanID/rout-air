package com.rout.air.encode.probe

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import com.rout.air.core.model.VideoCodec
import com.rout.air.data.logging.DiagLog

/**
 * Satu encoder video yang dilaporkan MediaCodec beserta batas yang
 * dilaporkannya.
 *
 * Batas ukuran/fps di sini hanya informasi: sebagian encoder melaporkan angka
 * yang lebih sempit daripada yang benar-benar bisa dipakai (mis. kanvas
 * portrait 1080x2400 pada encoder yang mengaku maksimal 1080p). Karena itu
 * pemilihan codec tidak memakai angka ini sebagai syarat; encoder tetap
 * dicoba saat sesi dimulai dan RoutAir menurunkan codec bila configure gagal.
 */
class EncoderCaps(
    val codec: VideoCodec,
    val name: String,
    val hardware: Boolean,
    val maxWidth: Int,
    val maxHeight: Int,
    val maxFps: Int,
    val supportsCbr: Boolean,
    val supportsVbr: Boolean,
    val supportsCq: Boolean,
) {
    /** Ringkasan untuk log dan layar Informasi. */
    fun describe(): String = buildString {
        append(name)
        append(if (hardware) " (hardware" else " (software")
        if (maxWidth > 0 && maxHeight > 0) append(", lapor ${maxWidth}x$maxHeight")
        if (maxFps > 0) append(" @${maxFps}fps")
        append(')')
    }
}

/**
 * Menanyakan daftar encoder ke MediaCodec. Hasil di-cache karena pemindaian
 * ini dipakai layar setelan dan awal sesi.
 *
 * AV1 hanya diterima bila encoder-nya hardware: encoder AV1 software bawaan
 * Android terlalu lambat untuk live dan hanya menambah pilihan yang menyesatkan.
 */
object EncoderProbe {
    @Volatile
    private var cached: List<EncoderCaps>? = null

    fun all(refresh: Boolean = false): List<EncoderCaps> {
        cached?.let { if (!refresh) return it }
        val probed = runCatching { probe() }
            .onFailure { DiagLog.line("encoder: pemindaian gagal ${it.message}") }
            .getOrDefault(emptyList())
        cached = probed
        return probed
    }

    /** Encoder terbaik untuk codec ini, atau null bila codec tidak tersedia. */
    fun select(caps: List<EncoderCaps>, codec: VideoCodec): EncoderCaps? = caps
        .asSequence()
        .filter { it.codec == codec }
        .filter { codec != VideoCodec.AV1 || it.hardware }
        .sortedByDescending { it.hardware }
        .firstOrNull()

    fun select(codec: VideoCodec): EncoderCaps? = select(all(), codec)

    /** Urutan percobaan saat encoder yang diminta gagal disiapkan. */
    fun fallbackChain(codec: VideoCodec): List<VideoCodec> = when (codec) {
        VideoCodec.AVC -> listOf(VideoCodec.AVC)
        VideoCodec.HEVC -> listOf(VideoCodec.HEVC, VideoCodec.AVC)
        VideoCodec.AV1 -> listOf(VideoCodec.AV1, VideoCodec.HEVC, VideoCodec.AVC)
    }

    private fun probe(): List<EncoderCaps> {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val result = mutableListOf<EncoderCaps>()
        for (codec in VideoCodec.entries) {
            for (info in list.codecInfos) {
                if (!info.isEncoder) continue
                if (info.supportedTypes.none { it.equals(codec.mime, ignoreCase = true) }) continue
                val caps = runCatching { info.getCapabilitiesForType(codec.mime) }.getOrNull() ?: continue
                val video = caps.videoCapabilities ?: continue
                if (MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface !in caps.colorFormats) continue

                val maxWidth = runCatching { video.supportedWidths.upper }.getOrDefault(0)
                val maxHeight = runCatching { video.supportedHeights.upper }.getOrDefault(0)
                val maxFps = runCatching {
                    val rates = video.getAchievableFrameRatesFor(maxWidth, maxHeight)
                    (rates?.upper as? Int) ?: 0
                }.getOrDefault(0)
                val encoder = caps.encoderCapabilities
                val entry = EncoderCaps(
                    codec = codec,
                    name = info.name,
                    hardware = info.isHardwareAccelerated,
                    maxWidth = maxWidth,
                    maxHeight = maxHeight,
                    maxFps = maxFps,
                    supportsCbr = encoder?.isBitrateModeSupported(
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
                    ) == true,
                    supportsVbr = encoder?.isBitrateModeSupported(
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
                    ) == true,
                    supportsCq = encoder?.isBitrateModeSupported(
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ,
                    ) == true,
                )
                result += entry
                DiagLog.line("encoder: ${codec.label} ${entry.describe()} cbr=${entry.supportsCbr}")
            }
        }
        if (result.isEmpty()) DiagLog.line("encoder: tidak ada encoder video yang terdeteksi")
        return result
    }
}
