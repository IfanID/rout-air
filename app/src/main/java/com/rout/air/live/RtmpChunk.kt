package com.rout.air.live

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/** Pesan RTMP lengkap (setelah dirakit dari beberapa chunk). */
data class RtmpMessage(
    val type: Int,
    val streamId: Int,
    val timestampMs: Int,
    val payload: ByteArray,
)

object RtmpType {
    const val SET_CHUNK_SIZE = 1
    const val ABORT = 2
    const val ACKNOWLEDGEMENT = 3
    const val USER_CONTROL = 4
    const val WINDOW_ACK_SIZE = 5
    const val SET_PEER_BANDWIDTH = 6
    const val AUDIO = 8
    const val VIDEO = 9
    const val DATA_AMF0 = 18
    const val COMMAND_AMF0 = 20
}

/**
 * Penulis chunk RTMP sederhana: setiap pesan memakai header fmt 0 supaya
 * tidak bergantung pada header sebelumnya. Cukup untuk publish satu stream.
 */
class RtmpChunkWriter(private val output: OutputStream) {
    @Volatile
    var chunkSize: Int = 128
        private set

    fun writeSetChunkSize(size: Int) {
        val payload = byteArrayOf(
            (size ushr 24).toByte(),
            (size ushr 16).toByte(),
            (size ushr 8).toByte(),
            size.toByte(),
        )
        write(RtmpType.SET_CHUNK_SIZE, streamId = 0, timestampMs = 0, payload = payload)
        chunkSize = size
    }

    @Synchronized
    fun write(type: Int, streamId: Int, timestampMs: Int, payload: ByteArray) {
        val length = payload.size
        val header = ByteArray(12)
        header[0] = CHUNK_STREAM_ID.toByte()
        header[1] = ((timestampMs ushr 16) and 0xFF).toByte()
        header[2] = ((timestampMs ushr 8) and 0xFF).toByte()
        header[3] = (timestampMs and 0xFF).toByte()
        header[4] = ((length ushr 16) and 0xFF).toByte()
        header[5] = ((length ushr 8) and 0xFF).toByte()
        header[6] = (length and 0xFF).toByte()
        header[7] = type.toByte()
        header[8] = (streamId and 0xFF).toByte()
        header[9] = ((streamId ushr 8) and 0xFF).toByte()
        header[10] = ((streamId ushr 16) and 0xFF).toByte()
        header[11] = ((streamId ushr 24) and 0xFF).toByte()
        output.write(header)

        var offset = 0
        while (offset < length) {
            val count = minOf(chunkSize, length - offset)
            output.write(payload, offset, count)
            offset += count
            if (offset < length) {
                // Chunk lanjutan: basic header fmt 3 untuk csid yang sama.
                output.write(0xC0 or CHUNK_STREAM_ID)
            }
        }
        output.flush()
    }

    private companion object {
        const val CHUNK_STREAM_ID = 3
    }
}

/**
 * Pembaca chunk RTMP. Mendukung fmt 0-3 dan extended timestamp. Setiap pesan
 * lengkap dikirim ke [onMessage] di thread pemanggil [readLoop].
 */
class RtmpChunkReader(
    private val input: InputStream,
    private val onMessage: (RtmpMessage) -> Unit,
) {
    private var chunkSize = 128

    private data class ChunkState(
        var timestampMs: Int = 0,
        var length: Int = 0,
        var type: Int = 0,
        var streamId: Int = 0,
        var buffer: ByteArray = ByteArray(0),
        var received: Int = 0,
    )

    private val states = HashMap<Int, ChunkState>()

    fun readLoop() {
        while (true) {
            val message = readMessage() ?: return
            when (message.type) {
                RtmpType.SET_CHUNK_SIZE -> {
                    if (message.payload.size >= 4) {
                        chunkSize = beInt(message.payload, 0).coerceIn(1, 16 * 1024 * 1024)
                    }
                }

                RtmpType.ABORT -> Unit
                else -> onMessage(message)
            }
        }
    }

    private fun readMessage(): RtmpMessage? {
        val first = input.read()
        if (first < 0) return null
        val fmt = first ushr 6
        var csid = first and 0x3F
        if (csid == 0) {
            csid = 64 + (input.read().takeIf { it >= 0 } ?: throw EOFException())
        } else if (csid == 1) {
            val low = input.read().takeIf { it >= 0 } ?: throw EOFException()
            val high = input.read().takeIf { it >= 0 } ?: throw EOFException()
            csid = 64 + (low and 0xFF) + ((high and 0xFF) shl 8)
        }

        val state = states.getOrPut(csid) { ChunkState() }
        var timestamp = state.timestampMs
        when (fmt) {
            0 -> {
                timestamp = readUInt24()
                state.length = readUInt24()
                state.type = readByte()
                state.streamId = readIntLe()
                state.buffer = ByteArray(state.length)
                state.received = 0
                state.timestampMs = timestamp
            }

            1 -> {
                timestamp = readUInt24()
                state.length = readUInt24()
                state.type = readByte()
                state.buffer = ByteArray(state.length)
                state.received = 0
                state.timestampMs = timestamp
            }

            2 -> {
                timestamp = readUInt24()
                state.timestampMs = timestamp
            }

            else -> Unit
        }

        if (timestamp == 0xFFFFFF) {
            timestamp = readIntBe()
            state.timestampMs = timestamp
        }

        if (state.received == 0 && state.buffer.isEmpty() && state.length > 0) {
            state.buffer = ByteArray(state.length)
        }
        if (state.length == 0) return RtmpMessage(state.type, state.streamId, timestamp, ByteArray(0))

        val remaining = state.length - state.received
        val count = minOf(chunkSize, remaining).coerceAtLeast(0)
        val read = input.read(state.buffer, state.received, count)
        if (read < 0) return null
        state.received += read

        if (state.received < state.length) {
            // Pesan belum lengkap; panggil lagi untuk chunk berikutnya.
            return readMessage()
        }

        val payload = state.buffer
        state.received = 0
        state.buffer = ByteArray(0)
        return RtmpMessage(state.type, state.streamId, timestamp, payload)
    }

    private fun readByte(): Int {
        val value = input.read()
        if (value < 0) throw EOFException("RTMP terputus")
        return value
    }

    private fun readUInt24(): Int =
        (readByte() shl 16) or (readByte() shl 8) or readByte()

    private fun readIntBe(): Int =
        (readByte() shl 24) or (readByte() shl 16) or (readByte() shl 8) or readByte()

    private fun readIntLe(): Int =
        readByte() or (readByte() shl 8) or (readByte() shl 16) or (readByte() shl 24)

    private fun beInt(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)
}
