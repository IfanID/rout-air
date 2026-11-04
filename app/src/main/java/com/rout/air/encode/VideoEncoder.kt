package com.rout.air.encode

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.rout.air.core.ColorRangeMode
import com.rout.air.core.ColorSpaceMode
import com.rout.air.core.VideoBitrateMode
import com.rout.air.core.VideoCodec
import com.rout.air.core.VideoProfile
import com.rout.air.data.DiagLog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Encoder video dengan input berupa Surface, dipakai langsung sebagai target
 * render GL. Output dikirim ke [Mp4Writer] lewat callback MediaCodec.
 *
 * Codec tidak lagi dikunci ke H.264. Bila encoder yang diminta gagal
 * disiapkan, [fallbackChain] dicoba berurutan dan codec yang benar-benar
 * dipakai bisa dibaca lewat [activeCodec].
 */
class VideoEncoder(
    width: Int,
    height: Int,
    fps: Int,
    bitrate: Int,
    keyframeIntervalSec: Int = 2,
    bitrateMode: VideoBitrateMode = VideoBitrateMode.CBR,
    cqLevel: Int = 20,
    profile: VideoProfile = VideoProfile.Auto,
    colorSpace: ColorSpaceMode = ColorSpaceMode.BT709,
    colorRange: ColorRangeMode = ColorRangeMode.Limited,
    val requestedCodec: VideoCodec = VideoCodec.AVC,
    private val encoderName: String? = null,
    private val sink: MediaSink,
    private val onEncoderError: (Throwable) -> Unit = {},
    private val onFallback: (VideoCodec, String) -> Unit = { _, _ -> },
    private val onFrameEncoded: () -> Unit = {},
) {
    private val thread = HandlerThread("rout-video-encoder").apply { start() }
    private val handler = Handler(thread.looper)
    private val eosLatch = CountDownLatch(1)

    private val codec: MediaCodec
    private var inputSurfaceRef: Surface? = null
    private var trackIndex = -1

    /** Codec yang benar-benar dipakai encoder ini. */
    var activeCodec: VideoCodec = requestedCodec
        private set

    val inputSurface: Surface get() = checkNotNull(inputSurfaceRef)

    init {
        val created = createCodec(
            width = width,
            height = height,
            fps = fps,
            bitrate = bitrate,
            keyframeIntervalSec = keyframeIntervalSec,
            bitrateMode = bitrateMode,
            cqLevel = cqLevel,
            profile = profile,
            colorSpace = colorSpace,
            colorRange = colorRange,
        )
        codec = created.codec
        activeCodec = created.codecKind
        inputSurfaceRef = codec.createInputSurface()
        codec.start()
        DiagLog.line(
            "video: codec=${activeCodec.label} enc=${created.name} " +
                "ukuran=${width}x$height@${fps} " +
                "rate diminta=$bitrateMode dipakai=${created.rateControl}",
        )
        if (created.codecKind != requestedCodec) {
            onFallback(
                created.codecKind,
                "Encoder ${requestedCodec.label} gagal, memakai ${created.codecKind.label}",
            )
        }
    }

    fun finish(timeoutMs: Long = 4_000) {
        try {
            codec.signalEndOfInputStream()
        } catch (error: IllegalStateException) {
            Log.w(TAG, "signalEndOfInputStream gagal: ${error.message}")
        }

        eosLatch.await(timeoutMs, TimeUnit.MILLISECONDS)

        try {
            codec.stop()
        } catch (error: IllegalStateException) {
            Log.w(TAG, "codec.stop gagal: ${error.message}")
        }
        codec.release()
        inputSurfaceRef?.release()
        inputSurfaceRef = null
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
    }

    private class Created(
        val codec: MediaCodec,
        val codecKind: VideoCodec,
        val name: String,
        val rateControl: VideoBitrateMode,
    )

    private fun createCodec(
        width: Int,
        height: Int,
        fps: Int,
        bitrate: Int,
        keyframeIntervalSec: Int,
        bitrateMode: VideoBitrateMode,
        cqLevel: Int,
        profile: VideoProfile,
        colorSpace: ColorSpaceMode,
        colorRange: ColorRangeMode,
    ): Created {
        var lastError: Throwable? = null

        // AV1 hanya dipakai bila ada encoder hardware.
        val chain = EncoderProbe.fallbackChain(requestedCodec).filter { candidate ->
            candidate != VideoCodec.AV1 || EncoderProbe.select(candidate) != null
        }

        for (candidate in chain) {
            for (profileValue in profilesFor(candidate, profile)) {
                var instance: MediaCodec? = null
                try {
                    instance = open(candidate)
                    val chosenRate = rateControlFor(instance, candidate, bitrateMode)
                    val format = MediaFormat.createVideoFormat(candidate.mime, width, height).apply {
                        setInteger(
                            MediaFormat.KEY_COLOR_FORMAT,
                            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
                        )
                        setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                        setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                        // Minta codec dijadwalkan realtime pada fps target;
                        // tanpa ini sebagian encoder membatasi laju input.
                        setInteger(MediaFormat.KEY_OPERATING_RATE, fps)
                        setInteger(MediaFormat.KEY_PRIORITY, 0)
                        // 0 = auto: biarkan encoder memilih interval keyframe.
                        if (keyframeIntervalSec > 0) {
                            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, keyframeIntervalSec)
                        }
                        setInteger(MediaFormat.KEY_BITRATE_MODE, chosenRate.mediaCodecValue)
                        if (chosenRate == VideoBitrateMode.CQ) {
                            setInteger(MediaFormat.KEY_QUALITY, cqLevel.coerceIn(0, 51))
                        }
                        applyColor(this, candidate, colorSpace, colorRange)
                        // AV1 tidak punya KEY_PROFILE yang sama dengan H.264/HEVC.
                        if (profileValue >= 0) {
                            setInteger(MediaFormat.KEY_PROFILE, profileValue)
                        }
                    }

                    // Callback harus dipasang sebelum configure agar codec
                    // masuk mode async.
                    instance.setCallback(newCallback(), handler)
                    var actualRate = chosenRate
                    try {
                        instance.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    } catch (error: Throwable) {
                        // Sebagian encoder menolak CBR/CQ; fallback ke VBR.
                        if (chosenRate == VideoBitrateMode.VBR) throw error
                        format.setInteger(
                            MediaFormat.KEY_BITRATE_MODE,
                            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
                        )
                        format.removeKey(MediaFormat.KEY_QUALITY)
                        instance.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                        actualRate = VideoBitrateMode.VBR
                    }
                    return Created(
                        codec = instance,
                        codecKind = candidate,
                        name = runCatching { instance.name }.getOrDefault(encoderName ?: "-"),
                        rateControl = actualRate,
                    )
                } catch (error: Throwable) {
                    lastError = error
                    runCatching { instance?.release() }
                }
            }
        }

        throw IllegalStateException(
            "Encoder ${requestedCodec.label} tidak bisa disiapkan: ${lastError?.message}",
            lastError,
        )
    }

    private fun open(codecKind: VideoCodec): MediaCodec {
        val preferred = if (codecKind == requestedCodec) encoderName else null
        if (preferred != null) {
            runCatching { MediaCodec.createByCodecName(preferred) }
                .onSuccess { return it }
        }
        return MediaCodec.createEncoderByType(codecKind.mime)
    }

    /**
     * Memilih rate control yang benar-benar didukung encoder. Nilai ini
     * dikirim ke MediaFormat, jadi bukan sekadar label di layar.
     */
    private fun rateControlFor(
        codec: MediaCodec,
        codecKind: VideoCodec,
        requested: VideoBitrateMode,
    ): VideoBitrateMode {
        val capabilities = runCatching {
            codec.codecInfo.getCapabilitiesForType(codecKind.mime).encoderCapabilities
        }.getOrNull()

        fun supported(mode: Int): Boolean =
            runCatching { capabilities?.isBitrateModeSupported(mode) }.getOrNull() ?: true

        val cbr = supported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
        val cq = supported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ)
        return when (requested) {
            VideoBitrateMode.CQ -> when {
                cq -> VideoBitrateMode.CQ
                cbr -> VideoBitrateMode.CBR
                else -> VideoBitrateMode.VBR
            }

            VideoBitrateMode.CBR -> if (cbr) VideoBitrateMode.CBR else VideoBitrateMode.VBR
            VideoBitrateMode.VBR -> VideoBitrateMode.VBR
        }
    }

    private fun profilesFor(codecKind: VideoCodec, profile: VideoProfile): List<Int> =
        when (codecKind) {
            VideoCodec.AVC -> {
                val high = MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
                val baseline = MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
                when (profile) {
                    VideoProfile.Auto, VideoProfile.High -> listOf(high, baseline, -1)
                    VideoProfile.Baseline -> listOf(baseline, -1)
                }
            }

            VideoCodec.HEVC -> listOf(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain, -1)
            VideoCodec.AV1 -> listOf(-1)
        }

    private fun applyColor(
        format: MediaFormat,
        codecKind: VideoCodec,
        colorSpace: ColorSpaceMode,
        colorRange: ColorRangeMode,
    ) {
        // Kunci "color-space" lama hanya dipahami encoder AVC vendor.
        if (codecKind == VideoCodec.AVC) {
            format.setInteger(
                "color-space",
                when (colorSpace) {
                    ColorSpaceMode.BT709 -> 1
                    ColorSpaceMode.BT601 -> 2
                },
            )
        } else {
            format.setInteger(
                MediaFormat.KEY_COLOR_STANDARD,
                when (colorSpace) {
                    ColorSpaceMode.BT709 -> MediaFormat.COLOR_STANDARD_BT709
                    ColorSpaceMode.BT601 -> MediaFormat.COLOR_STANDARD_BT601_NTSC
                },
            )
        }
        format.setInteger(
            MediaFormat.KEY_COLOR_RANGE,
            when (colorRange) {
                ColorRangeMode.Limited -> MediaFormat.COLOR_RANGE_LIMITED
                ColorRangeMode.Full -> MediaFormat.COLOR_RANGE_FULL
            },
        )
    }

    /**
     * Dibuat sebagai fungsi, bukan properti, karena dipakai saat konstruktor
     * masih berjalan (urutan inisialisasi properti Kotlin).
     */
    private fun newCallback(): MediaCodec.Callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            // Input berasal dari Surface, bukan buffer biasa.
        }

        override fun onOutputBufferAvailable(
            codec: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo,
        ) {
            try {
                val buffer = codec.getOutputBuffer(index)
                if (buffer != null && info.size > 0) {
                    val data = ByteArray(info.size)
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    buffer.get(data)
                    sink.write(trackIndex, data, info.presentationTimeUs, info.flags)
                    onFrameEncoded()
                }
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    eosLatch.countDown()
                }
            } catch (error: IllegalStateException) {
                Log.w(TAG, "Output video gagal diproses: ${error.message}")
            } finally {
                try {
                    codec.releaseOutputBuffer(index, false)
                } catch (_: IllegalStateException) {
                    // Codec sudah dihentikan.
                }
            }
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            trackIndex = sink.addVideoTrack(format)
        }

        override fun onError(codec: MediaCodec, error: MediaCodec.CodecException) {
            Log.e(TAG, "Encoder video error: ${error.diagnosticInfo}", error)
            eosLatch.countDown()
            onEncoderError(error)
        }
    }

    private val VideoBitrateMode.mediaCodecValue: Int
        get() = when (this) {
            VideoBitrateMode.CBR -> MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
            VideoBitrateMode.VBR -> MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
            VideoBitrateMode.CQ -> MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ
        }

    private companion object {
        const val TAG = "RoutAirVideo"
    }
}
