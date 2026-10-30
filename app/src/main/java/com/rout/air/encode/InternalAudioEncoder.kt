package com.rout.air.encode

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.util.Log

/**
 * Merekam audio internal (playback) memakai AudioPlaybackCapture, lalu
 * meng-encode AAC.
 *
 * Audio hanya tertangkap dari aplikasi yang mengizinkan playback capture;
 * aplikasi yang memblokir akan menghasilkan senyap. Karena itu kegagalan
 * inisialisasi tidak membatalkan rekaman, cukup dinonaktifkan.
 *
 * Encoder memakai mode sinkron di satu thread: baca PCM, isi input buffer,
 * lalu kuras output. Timestamp dihitung dari jumlah sampel agar tidak melar
 * saat thread terlambat dijadwalkan.
 */
class InternalAudioEncoder(
    projection: MediaProjection,
    private val sampleRate: Int,
    private val channels: Int,
    bitrate: Int,
    private val startTimeNs: Long,
    private val muxer: Mp4Writer,
) {
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MIME)
    private val recorder: AudioRecord
    private val thread = Thread(::processLoop, "rout-audio")
    private val bufferInfo = MediaCodec.BufferInfo()
    private var startOffsetUs = 0L

    private var trackIndex = -1
    private var totalSamples = 0L

    @Volatile
    private var running = false

    init {
        val channelMask = if (channels == 1) {
            AudioFormat.CHANNEL_IN_MONO
        } else {
            AudioFormat.CHANNEL_IN_STEREO
        }

        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minBuffer > 0) { "Perangkat tidak mendukung audio $sampleRate Hz" }

        // Tangkap audio aplikasi/game yang mengizinkan playback capture.
        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .addMatchingUsage(AudioAttributes.USAGE_ASSISTANT)
            .build()

        val bufferBytes = maxOf(minBuffer * 2, sampleRate / 2 * channels * 2)
        recorder = AudioRecord.Builder()
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build(),
            )
            .setBufferSizeInBytes(bufferBytes)
            .setAudioPlaybackCaptureConfig(captureConfig)
            .build()
        check(recorder.state == AudioRecord.STATE_INITIALIZED) {
            "AudioRecord untuk audio internal gagal diinisialisasi"
        }

        val format = MediaFormat.createAudioFormat(MIME, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    fun start() {
        recorder.startRecording()
        running = true
        thread.start()
    }

    fun finish(limitPtsUs: Long = Long.MAX_VALUE, timeoutMs: Long = 4_000) {
        running = false
        thread.join(1_500)

        runCatching { recorder.stop() }

        runCatching {
            val index = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
            if (index >= 0) {
                codec.queueInputBuffer(
                    index,
                    0,
                    0,
                    presentationTimeUs(),
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                )
            }
        }

        drainOutputsUntilEos(limitPtsUs, timeoutMs)

        try {
            codec.stop()
        } catch (error: IllegalStateException) {
            Log.w(TAG, "codec.stop gagal: ${error.message}")
        }
        codec.release()
        recorder.release()
    }

    private fun processLoop() {
        val pcm = ShortArray(CHUNK_FRAMES * channels)
        var firstRead = true
        while (running) {
            val read = recorder.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
            if (read > 0) {
                if (firstRead) {
                    // Offset dihitung saat sampel pertama benar-benar masuk,
                    // supaya audio tidak mendahului video saat penyiapan lambat.
                    startOffsetUs = ((System.nanoTime() - startTimeNs) / 1_000).coerceAtLeast(0L)
                    firstRead = false
                }
                encode(pcm, read)
            }
            drainOutputs()
        }
    }

    private fun encode(pcm: ShortArray, samples: Int) {
        // AudioRecord.read mengembalikan jumlah short, bukan jumlah frame.
        // PTS harus dihitung per frame (dibagi jumlah kanal) supaya durasi
        // audio tidak menjadi dua kali lipat pada rekaman stereo.
        var offset = 0
        while (offset < samples && running) {
            val index = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
            if (index < 0) return

            val buffer = codec.getInputBuffer(index) ?: continue
            buffer.clear()
            val capacity = buffer.capacity() / 2
            val count = minOf(capacity, samples - offset)

            buffer.asShortBuffer().put(pcm, offset, count)
            buffer.position(0)
            buffer.limit(count * 2)

            codec.queueInputBuffer(index, 0, count * 2, presentationTimeUs(), 0)
            totalSamples += count / channels
            offset += count
        }
    }

    private fun drainOutputs() {
        while (true) {
            val index = codec.dequeueOutputBuffer(bufferInfo, 0)
            when {
                index >= 0 -> {
                    if (bufferInfo.size > 0 && trackIndex >= 0) {
                        val buffer = codec.getOutputBuffer(index)
                        if (buffer != null) {
                            muxer.write(trackIndex, buffer, bufferInfo)
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                }

                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    trackIndex = muxer.addAudioTrack(codec.outputFormat)
                }

                else -> return
            }
        }
    }

    private fun drainOutputsUntilEos(limitPtsUs: Long, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val index = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
            when {
                index >= 0 -> {
                    // Sampel setelah titik stop video dibuang supaya audio tidak
                    // berjalan lebih panjang daripada gambar.
                    val withinLimit = bufferInfo.presentationTimeUs < limitPtsUs
                    if (bufferInfo.size > 0 && trackIndex >= 0 && withinLimit) {
                        val buffer = codec.getOutputBuffer(index)
                        if (buffer != null) {
                            muxer.write(trackIndex, buffer, bufferInfo)
                        }
                    }
                    val eos = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(index, false)
                    if (eos) return
                }

                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    trackIndex = muxer.addAudioTrack(codec.outputFormat)
                }

                else -> Unit
            }
        }
    }

    private fun presentationTimeUs(): Long =
        startOffsetUs + totalSamples * 1_000_000L / sampleRate

    private companion object {
        const val TAG = "RoutAirAudio"
        const val MIME = MediaFormat.MIMETYPE_AUDIO_AAC
        const val CHUNK_FRAMES = 1024
        const val DEQUEUE_TIMEOUT_US = 10_000L
    }
}
