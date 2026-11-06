package com.rout.air.encode.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import kotlin.math.min
import com.rout.air.encode.mux.MediaSink

private const val MIME = android.media.MediaFormat.MIMETYPE_AUDIO_AAC
private const val DEQUEUE_TIMEOUT_US = 10_000L

internal class AudioTrackEncoder(
    val key: Int,
    private val sink: MediaSink,
    sampleRate: Int,
    channels: Int,
    bitrate: Int,
) {
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MIME)
    private val bufferInfo = MediaCodec.BufferInfo()
    private var trackIndex = -1

    init {
        val format = MediaFormat.createAudioFormat(MIME, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    fun encode(pcm: ShortArray, ptsUs: Long) {
        var offset = 0
        while (offset < pcm.size) {
            val index = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
            if (index < 0) return
            val buffer = codec.getInputBuffer(index) ?: continue
            buffer.clear()
            val capacity = buffer.capacity() / 2
            val count = min(capacity, pcm.size - offset)
            buffer.asShortBuffer().put(pcm, offset, count)
            buffer.position(0)
            buffer.limit(count * 2)
            codec.queueInputBuffer(index, 0, count * 2, ptsUs, 0)
            offset += count
        }
    }

    fun drain() {
        while (true) {
            val index = codec.dequeueOutputBuffer(bufferInfo, 0)
            when {
                index >= 0 -> {
                    if (bufferInfo.size > 0 && trackIndex >= 0) {
                        val buffer = codec.getOutputBuffer(index)
                        if (buffer != null) {
                            val data = ByteArray(bufferInfo.size)
                            buffer.position(bufferInfo.offset)
                            buffer.limit(bufferInfo.offset + bufferInfo.size)
                            buffer.get(data)
                            sink.write(
                                trackIndex,
                                data,
                                bufferInfo.presentationTimeUs,
                                bufferInfo.flags,
                            )
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                }

                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    trackIndex = sink.addAudioTrack(key, codec.outputFormat)
                }

                else -> return
            }
        }
    }

    fun finish(ptsUs: Long, limitPtsUs: Long, timeoutMs: Long) {
        runCatching {
            val index = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
            if (index >= 0) {
                codec.queueInputBuffer(
                    index,
                    0,
                    0,
                    ptsUs,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                )
            }
        }

        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val index = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
            when {
                index >= 0 -> {
                    val withinLimit = bufferInfo.presentationTimeUs < limitPtsUs
                    if (bufferInfo.size > 0 && trackIndex >= 0 && withinLimit) {
                        val buffer = codec.getOutputBuffer(index)
                        if (buffer != null) {
                            val data = ByteArray(bufferInfo.size)
                            buffer.position(bufferInfo.offset)
                            buffer.limit(bufferInfo.offset + bufferInfo.size)
                            buffer.get(data)
                            sink.write(
                                trackIndex,
                                data,
                                bufferInfo.presentationTimeUs,
                                bufferInfo.flags,
                            )
                        }
                    }
                    val eos = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(index, false)
                    if (eos) break
                }

                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    trackIndex = sink.addAudioTrack(key, codec.outputFormat)
                }

                else -> Unit
            }
        }

        runCatching { codec.stop() }
        runCatching { codec.release() }
    }

    fun release() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }
}
