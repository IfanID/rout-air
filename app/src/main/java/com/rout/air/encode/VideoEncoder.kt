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
import com.rout.air.core.VideoProfile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Encoder H.264 dengan input berupa Surface, dipakai langsung sebagai target
 * render GL. Output dikirim ke [Mp4Writer] lewat callback MediaCodec.
 */
class VideoEncoder(
    width: Int,
    height: Int,
    fps: Int,
    bitrate: Int,
    keyframeIntervalSec: Int = 2,
    bitrateMode: VideoBitrateMode = VideoBitrateMode.VBR,
    profile: VideoProfile = VideoProfile.Auto,
    colorSpace: ColorSpaceMode = ColorSpaceMode.BT709,
    colorRange: ColorRangeMode = ColorRangeMode.Limited,
    private val sink: MediaSink,
    private val onEncoderError: (Throwable) -> Unit = {},
    private val onFrameEncoded: () -> Unit = {},
) {
    private val thread = HandlerThread("rout-video-encoder").apply { start() }
    private val handler = Handler(thread.looper)
    private val eosLatch = CountDownLatch(1)

    private val codec: MediaCodec
    private var inputSurfaceRef: Surface? = null
    private var trackIndex = -1

    val inputSurface: Surface get() = checkNotNull(inputSurfaceRef)

    init {
        codec = createCodec(
            width = width,
            height = height,
            fps = fps,
            bitrate = bitrate,
            keyframeIntervalSec = keyframeIntervalSec,
            bitrateMode = bitrateMode,
            profile = profile,
            colorSpace = colorSpace,
            colorRange = colorRange,
        )
        inputSurfaceRef = codec.createInputSurface()
        codec.start()
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

    private fun createCodec(
        width: Int,
        height: Int,
        fps: Int,
        bitrate: Int,
        keyframeIntervalSec: Int,
        bitrateMode: VideoBitrateMode,
        profile: VideoProfile,
        colorSpace: ColorSpaceMode,
        colorRange: ColorRangeMode,
    ): MediaCodec {
        // High lebih efisien, tetapi tidak semua encoder perangkat menerimanya.
        val high = MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
        val baseline = MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
        val profiles = when (profile) {
            VideoProfile.Auto -> listOf(high, baseline, -1)
            VideoProfile.High -> listOf(high, baseline, -1)
            VideoProfile.Baseline -> listOf(baseline, -1)
        }

        var lastError: Throwable? = null
        for (profile in profiles) {
            val codec = try {
                MediaCodec.createEncoderByType(MIME)
            } catch (error: Throwable) {
                lastError = error
                break
            }

            try {
                // Callback harus dipasang sebelum configure agar codec masuk mode async.
                codec.setCallback(newCallback(), handler)

                val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
                    setInteger(
                        MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
                    )
                    setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, keyframeIntervalSec)
                    setInteger(
                        MediaFormat.KEY_BITRATE_MODE,
                        when (bitrateMode) {
                            VideoBitrateMode.CBR ->
                                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR

                            VideoBitrateMode.VBR ->
                                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                        },
                    )
                    setInteger(
                        "color-space",
                        when (colorSpace) {
                            ColorSpaceMode.BT709 -> 1
                            ColorSpaceMode.BT601 -> 2
                        },
                    )
                    setInteger(
                        MediaFormat.KEY_COLOR_RANGE,
                        when (colorRange) {
                            ColorRangeMode.Limited -> MediaFormat.COLOR_RANGE_LIMITED
                            ColorRangeMode.Full -> MediaFormat.COLOR_RANGE_FULL
                        },
                    )
                    if (profile >= 0) {
                        setInteger(MediaFormat.KEY_PROFILE, profile)
                    }
                }

                try {
                    codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                } catch (error: Throwable) {
                    // Sebagian encoder menolak CBR; fallback ke VBR.
                    if (bitrateMode != VideoBitrateMode.CBR) throw error
                    format.setInteger(
                        MediaFormat.KEY_BITRATE_MODE,
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
                    )
                    codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                }
                return codec
            } catch (error: Throwable) {
                lastError = error
                codec.release()
            }
        }

        throw IllegalStateException(
            "Encoder H.264 tidak bisa disiapkan: ${lastError?.message}",
            lastError,
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

    private companion object {
        const val TAG = "RoutAirVideo"
        const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
    }
}
