package com.rout.air.capture

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Penerima bitstream H.264 mentah (Annex B) dari HP kamera lewat TCP.
 *
 * Format URL: `tcp://<ip>:<port>` atau `<ip>:<port>` (default port 3333).
 * Setiap NAL unit yang lengkap diteruskan ke [onNal]; dekode dilakukan oleh
 * pemanggil (lihat `decode/H264Decoder`).
 */
class H264Receiver(
    private val url: String,
    private val onNal: (ByteArray) -> Unit,
    private val onEvent: (Event) -> Unit = {},
    private val onLog: (String) -> Unit = {},
) {
    /** Kejadian koneksi yang bisa ditampilkan UI. */
    sealed interface Event {
        data object Connecting : Event
        data class Connected(val host: String, val port: Int) : Event
        data class Error(val message: String) : Event
        data object Disconnected : Event
    }
    private val running = AtomicBoolean(true)
    private var thread: Thread? = null

    fun start() {
        if (thread != null) return
        thread = Thread({ loop() }, "rout-h264-recv").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running.set(false)
        thread?.interrupt()
        thread = null
    }

    private fun loop() {
        while (running.get()) {
            var socket: Socket? = null
            try {
                val endpoint = parse(url) ?: run {
                    onEvent(Event.Error("URL tidak valid: $url"))
                    return
                }
                onEvent(Event.Connecting)
                socket = Socket().apply {
                    tcpNoDelay = true
                    connect(InetSocketAddress(endpoint.first, endpoint.second), CONNECT_TIMEOUT_MS)
                    soTimeout = READ_TIMEOUT_MS
                }
                onEvent(Event.Connected(endpoint.first, endpoint.second))
                onLog("connect ${endpoint.first}:${endpoint.second}")
                // Banyak server kamera menunggu request HTTP sebelum mulai
                // mengirim. Kirim probe GET; server TCP mentah mengabaikannya
                // dan header respons (bila ada) dilewati pencari start code.
                runCatching {
                    val request =
                        "GET / HTTP/1.0\r\nHost: ${endpoint.first}\r\n" +
                            "Connection: close\r\n\r\n"
                    socket.getOutputStream().write(request.toByteArray())
                    socket.getOutputStream().flush()
                    onLog("GET probe dikirim")
                }
                BufferedInputStream(socket.getInputStream(), 64 * 1024).use { input ->
                    readNals(input)
                }
            } catch (error: Throwable) {
                onLog("error: ${error.javaClass.simpleName}: ${error.message}")
                onEvent(Event.Error(error.message ?: "koneksi gagal"))
            } finally {
                runCatching { socket?.close() }
            }
            if (!running.get()) break
            onEvent(Event.Disconnected)
            runCatching { Thread.sleep(RECONNECT_DELAY_MS) }
        }
    }

    /** Pisahkan NAL unit berdasarkan start code Annex B. */
    private fun readNals(input: InputStream) {
        val accumulator = ByteArrayOutputStream(512 * 1024)
        val chunk = ByteArray(64 * 1024)
        var totalBytes = 0L
        var nalCount = 0L
        var firstChunk = true
        var lastStatsNs = System.nanoTime()
        while (running.get()) {
            val read = input.read(chunk)
            if (read < 0) return
            totalBytes += read
            if (firstChunk) {
                firstChunk = false
                val preview = chunk.copyOfRange(0, minOf(read, 24))
                onLog(
                    "byte pertama (${read}): " +
                        preview.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) },
                )
            }
            accumulator.write(chunk, 0, read)
            var data = accumulator.toByteArray()
            var start = findStartCode(data, 0)
            while (start >= 0) {
                val next = findStartCode(data, start + 3)
                if (next < 0) break
                val nal = data.copyOfRange(start, next)
                nalCount++
                if (nalCount <= 3 || nalCount % 100L == 0L) {
                    onLog("nal #$nalCount size=${nal.size}")
                }
                onNal(nal)
                data = data.copyOfRange(next, data.size)
                start = 0
            }
            accumulator.reset()
            accumulator.write(data)
            val now = System.nanoTime()
            if (now - lastStatsNs >= 2_000_000_000L) {
                lastStatsNs = now
                onLog("rx bytes=$totalBytes nal=$nalCount buffer=${accumulator.size()}")
            }
        }
    }

    /** Indeks awal start code (`00 00 01` atau `00 00 00 01`) atau -1. */
    private fun findStartCode(data: ByteArray, from: Int): Int {
        var index = from
        while (index + 2 < data.size) {
            if (data[index] == 0.toByte() && data[index + 1] == 0.toByte()) {
                if (data[index + 2] == 1.toByte()) return index
                if (index + 3 < data.size &&
                    data[index + 2] == 0.toByte() &&
                    data[index + 3] == 1.toByte()
                ) {
                    return index
                }
            }
            index++
        }
        return -1
    }

    private fun parse(raw: String): Pair<String, Int>? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null
        val withoutScheme = trimmed
            .removePrefix("tcp://")
            .removePrefix("TCP://")
        val hostPort = withoutScheme.substringBefore('/')
        val host = hostPort.substringBefore(':').takeIf { it.isNotBlank() } ?: return null
        val port = hostPort.substringAfter(':', DEFAULT_PORT.toString()).toIntOrNull()
            ?: DEFAULT_PORT
        return host to port
    }

    private companion object {
        const val DEFAULT_PORT = 3333
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 10_000
        const val RECONNECT_DELAY_MS = 1_000L
    }
}
