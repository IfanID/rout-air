package com.rout.air.live

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Alamat RTMP yang sudah dipecah menjadi bagian yang dibutuhkan protokol. */
data class RtmpEndpoint(
    val secure: Boolean,
    val host: String,
    val port: Int,
    val app: String,
    val streamName: String,
) {
    val tcUrl: String
        get() = buildString {
            append(if (secure) "rtmps" else "rtmp")
            append("://").append(host)
            if ((secure && port != 443) || (!secure && port != 1935)) {
                append(':').append(port)
            }
            append('/').append(app)
        }

    companion object {
        fun parse(serverUrl: String, streamKey: String): RtmpEndpoint {
            val uri = URI(serverUrl.trim())
            val secure = uri.scheme.equals("rtmps", ignoreCase = true)
            require(uri.scheme.equals("rtmp", true) || secure) {
                "URL harus diawali rtmp:// atau rtmps://"
            }
            val host = uri.host ?: error("Host RTMP tidak valid")
            val port = if (uri.port > 0) uri.port else if (secure) 443 else 1935
            val segments = uri.path.orEmpty().split('/').filter { it.isNotBlank() }
            val combined = if (streamKey.isBlank()) segments else segments + streamKey.trim()
            require(combined.isNotEmpty()) { "Nama stream/kunci belum diisi" }
            val stream = combined.last()
            val app = combined.dropLast(1).joinToString("/").ifBlank { "live" }
            return RtmpEndpoint(secure, host, port, app, stream)
        }
    }
}

/**
 * Satu koneksi RTMP untuk publish: handshake, connect, createStream, publish,
 * lalu mengirim tag audio/video. Pembacaan paket server berjalan di thread
 * terpisah agar ping dan status publish tertangani.
 */
class RtmpConnection(
    private val endpoint: RtmpEndpoint,
    private val username: String = "",
    private val password: String = "",
    private val onClosed: (String) -> Unit,
) {
    private var socket: Socket? = null
    private var output: OutputStream? = null
    private var writer: RtmpChunkWriter? = null
    private val connectLatch = CountDownLatch(1)
    private val createLatch = CountDownLatch(1)
    private val publishLatch = CountDownLatch(1)

    @Volatile
    private var streamId = 0

    @Volatile
    private var closed = false

    @Volatile
    private var userClosed = false

    private var windowAckSize = 0
    private var lastAckBytes = 0L

    @Volatile
    private var failure: String? = null

    val isConnected: Boolean get() = !closed && streamId > 0

    fun connect(timeoutMs: Long = 12_000L): Boolean {
        try {
            val raw = Socket()
            raw.tcpNoDelay = true
            raw.connect(InetSocketAddress(endpoint.host, endpoint.port), timeoutMs.toInt())
            val active: Socket = if (endpoint.secure) {
                (SSLSocketFactory.getDefault() as SSLSocketFactory)
                    .createSocket(raw, endpoint.host, endpoint.port, true)
                    .also { (it as SSLSocket).startHandshake() }
            } else {
                raw
            }
            socket = active
            val out = active.getOutputStream()
            val input = active.getInputStream()
            output = out

            handshake(out, input)
            val chunkWriter = RtmpChunkWriter(out)
            writer = chunkWriter
            chunkWriter.writeSetChunkSize(CHUNK_SIZE)

            Thread(
                { readLoop(input, chunkWriter) },
                "rout-rtmp-read",
            ).apply { isDaemon = true }.start()

            sendCommand(0.0, connectionCommand())
            if (!connectLatch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                return fail("Server tidak menjawab connect")
            }
            if (failure != null) return false

            sendCommand(2.0, listOf("releaseStream", 2.0, null, endpoint.streamName))
            sendCommand(3.0, listOf("FCPublish", 3.0, null, endpoint.streamName))
            sendCommand(4.0, listOf("createStream", 4.0, null))
            if (!createLatch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                return fail("Server tidak menjawab createStream")
            }

            sendCommand(
                5.0,
                listOf("publish", 5.0, null, endpoint.streamName, "live"),
                streamId = streamId,
            )
            if (!publishLatch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                return fail("Server tidak mengonfirmasi publish")
            }
            return failure == null
        } catch (error: Throwable) {
            return fail(error.message ?: "Gagal terhubung ke RTMP")
        }
    }

    fun sendVideo(timestampMs: Int, payload: ByteArray) {
        writer?.write(RtmpType.VIDEO, streamId, timestampMs, payload)
    }

    fun sendAudio(timestampMs: Int, payload: ByteArray) {
        writer?.write(RtmpType.AUDIO, streamId, timestampMs, payload)
    }

    fun close() {
        userClosed = true
        closeSocket()
    }

    private fun closeSocket() {
        if (closed) return
        closed = true
        runCatching { socket?.close() }
        socket = null
        output = null
        writer = null
    }

    private fun fail(message: String): Boolean {
        failure = message
        closeSocket()
        return false
    }

    private fun handshake(out: OutputStream, input: InputStream) {
        val c1 = ByteArray(1536).also { java.util.Random().nextBytes(it) }
        out.write(0x03)
        out.write(c1)
        out.flush()

        if (readFully(input, 1)[0].toInt() != 0x03) {
            throw IllegalStateException("Versi RTMP server tidak didukung")
        }
        val s1 = readFully(input, 1536)
        readFully(input, 1536) // S2 tidak dipakai
        out.write(s1)
        out.flush()
    }

    private fun readLoop(rawInput: InputStream, chunkWriter: RtmpChunkWriter) {
        val input = CountingInputStream(rawInput)
        try {
            RtmpChunkReader(input) { message ->
                handleMessage(message, chunkWriter)
                maybeAcknowledge(input.count, chunkWriter)
            }.readLoop()
            if (!closed) fail("Koneksi RTMP ditutup server")
        } catch (error: EOFException) {
            if (!closed) fail("Koneksi RTMP terputus")
        } catch (error: Throwable) {
            if (failure == null) failure = error.message ?: "Koneksi RTMP error"
        }
        closeSocket()
        if (!userClosed) onClosed(failure ?: "Koneksi RTMP terputus")
    }

    private fun handleMessage(message: RtmpMessage, chunkWriter: RtmpChunkWriter) {
        when (message.type) {
            RtmpType.COMMAND_AMF0 -> handleCommand(message, chunkWriter)
            RtmpType.USER_CONTROL -> handleUserControl(message, chunkWriter)
            RtmpType.WINDOW_ACK_SIZE -> {
                if (message.payload.size >= 4) {
                    windowAckSize = beInt(message.payload)
                }
            }

            RtmpType.SET_PEER_BANDWIDTH -> {
                if (windowAckSize == 0 && message.payload.size >= 4) {
                    windowAckSize = beInt(message.payload)
                }
            }

            else -> Unit
        }
    }

    private fun handleCommand(message: RtmpMessage, chunkWriter: RtmpChunkWriter) {
        val values = runCatching { Amf0.decode(message.payload) }.getOrDefault(emptyList())
        if (values.isEmpty()) return
        val name = values.firstOrNull() as? String
        val transaction = (values.getOrNull(1) as? Number)?.toDouble()

        when {
            name == "_error" && (transaction == 1.0 || transaction == 4.0) -> {
                failure = "Server menolak perintah (${transaction?.toInt()})"
                connectLatch.countDown()
                createLatch.countDown()
            }

            name == "_result" && transaction == 1.0 -> {
                // Beberapa server mengirim status gagal di dalam info.
                val info = values.getOrNull(3) as? Map<*, *>
                val code = info?.get("code") as? String
                if (code != null && code.contains("Failed", ignoreCase = true)) {
                    failure = code
                }
                connectLatch.countDown()
            }

            name == "_result" && transaction == 4.0 -> {
                streamId = (values.getOrNull(3) as? Number)?.toInt() ?: 0
                createLatch.countDown()
            }

            name == "onStatus" -> {
                val info = values.getOrNull(3) as? Map<*, *>
                val code = info?.get("code") as? String ?: ""
                when {
                    code.contains("Publish.Start", ignoreCase = true) -> publishLatch.countDown()
                    code.contains("BadName", ignoreCase = true) ||
                        code.contains("Failed", ignoreCase = true) -> {
                        failure = code
                        publishLatch.countDown()
                    }
                }
            }

            name == "onBWDone" -> Unit
        }
    }

    private fun handleUserControl(message: RtmpMessage, chunkWriter: RtmpChunkWriter) {
        if (message.payload.size < 6) return
        val event = ((message.payload[0].toInt() and 0xFF) shl 8) or
            (message.payload[1].toInt() and 0xFF)
        if (event == 6) {
            // PingRequest -> PingResponse dengan timestamp yang sama.
            val response = byteArrayOf(0, 7) + message.payload.copyOfRange(2, 6)
            chunkWriter.write(RtmpType.USER_CONTROL, 0, 0, response)
        }
    }

    private fun connectionCommand(): List<Any?> {
        val values = linkedMapOf<String, Any?>(
            "app" to endpoint.app,
            "type" to "nonprivate",
            "flashVer" to "FMLE/3.0 (compatible; RoutAir)",
            "tcUrl" to endpoint.tcUrl,
            "fpad" to false,
            "capabilities" to 15.0,
            "audioCodecs" to 4071.0,
            "videoCodecs" to 252.0,
            "videoFunction" to 1.0,
            "objectEncoding" to 0.0,
        )
        // Autentikasi kustom ala OBS; didukung server yang membaca field ini.
        if (username.isNotBlank()) values["username"] = username
        if (password.isNotBlank()) values["password"] = password
        return listOf("connect", 1.0, values)
    }

    private fun maybeAcknowledge(received: Long, chunkWriter: RtmpChunkWriter) {
        if (windowAckSize <= 0) return
        if (received - lastAckBytes < windowAckSize) return
        lastAckBytes = received
        val payload = byteArrayOf(
            (received ushr 24).toByte(),
            (received ushr 16).toByte(),
            (received ushr 8).toByte(),
            received.toByte(),
        )
        chunkWriter.write(RtmpType.ACKNOWLEDGEMENT, 0, 0, payload)
    }

    private fun beInt(data: ByteArray): Int =
        ((data[0].toInt() and 0xFF) shl 24) or
            ((data[1].toInt() and 0xFF) shl 16) or
            ((data[2].toInt() and 0xFF) shl 8) or
            (data[3].toInt() and 0xFF)

    private class CountingInputStream(
        private val delegate: InputStream,
    ) : InputStream() {
        var count: Long = 0L
            private set

        override fun read(): Int {
            val value = delegate.read()
            if (value >= 0) count++
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val read = delegate.read(buffer, offset, length)
            if (read > 0) count += read
            return read
        }
    }

    private fun sendCommand(
        transaction: Double,
        values: List<Any?>,
        streamId: Int = 0,
    ) {
        val payload = Amf0.encode(values)
        writer?.write(RtmpType.COMMAND_AMF0, streamId, 0, payload)
    }

    private fun readFully(input: InputStream, size: Int): ByteArray {
        val data = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = input.read(data, offset, size - offset)
            if (read < 0) throw EOFException("Koneksi RTMP terputus saat handshake")
            offset += read
        }
        return data
    }

    private companion object {
        const val CHUNK_SIZE = 4096
    }
}
