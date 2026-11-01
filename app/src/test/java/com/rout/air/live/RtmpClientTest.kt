package com.rout.air.live

import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RtmpClientTest {

    @Test
    fun connectPublish_sendsVideoAndAudioMessages() {
        val server = ServerSocket(0)
        val media = LinkedBlockingQueue<RtmpMessage>()
        val error = AtomicReference<Throwable>()

        val serverThread = thread(name = "fake-rtmp") {
            try {
                server.accept().use { socket ->
                    val input = socket.getInputStream()
                    val output = socket.getOutputStream()
                    // C0 + C1
                    val c0c1 = readFully(input, 1537)
                    assertEquals(3, c0c1[0].toInt())
                    output.write(byteArrayOf(3) + ByteArray(1536) + ByteArray(1536))
                    output.flush()
                    readFully(input, 1536) // C2

                    val writer = RtmpChunkWriter(output)
                    RtmpChunkReader(input) { message ->
                        if (message.type == RtmpType.COMMAND_AMF0) {
                            val values = Amf0.decode(message.payload)
                            when (values.firstOrNull()) {
                                "connect" -> writer.write(
                                    RtmpType.COMMAND_AMF0,
                                    0,
                                    0,
                                    Amf0.encode(
                                        listOf(
                                            "_result",
                                            1.0,
                                            null,
                                            mapOf("code" to "NetConnection.Connect.Success"),
                                        ),
                                    ),
                                )

                                "createStream" -> writer.write(
                                    RtmpType.COMMAND_AMF0,
                                    0,
                                    0,
                                    Amf0.encode(listOf("_result", 4.0, null, 1.0)),
                                )

                                "publish" -> writer.write(
                                    RtmpType.COMMAND_AMF0,
                                    1,
                                    0,
                                    Amf0.encode(
                                        listOf(
                                            "onStatus",
                                            0.0,
                                            null,
                                            mapOf("code" to "NetStream.Publish.Start"),
                                        ),
                                    ),
                                )
                            }
                        } else {
                            media.add(message)
                        }
                    }.readLoop()
                }
            } catch (throwable: Throwable) {
                error.set(throwable)
            }
        }

        val endpoint = RtmpEndpoint.parse("rtmp://127.0.0.1:${server.localPort}/live", "key")
        val connection = RtmpConnection(endpoint) { }
        try {
            assertTrue("connect gagal: ${error.get()}", connection.connect(5_000))

            connection.sendVideo(120, byteArrayOf(0x17, 0x00, 0x00, 0x00, 0x00))
            connection.sendAudio(140, byteArrayOf(0xAF.toByte(), 0x01, 0x02))

            val video = media.poll(3, TimeUnit.SECONDS)
            val audio = media.poll(3, TimeUnit.SECONDS)
            assertNotNull("video tidak diterima", video)
            assertNotNull("audio tidak diterima", audio)
            assertEquals(RtmpType.VIDEO, video!!.type)
            assertEquals(120, video.timestampMs)
            assertArrayEquals(byteArrayOf(0x17, 0x00, 0x00, 0x00, 0x00), video.payload)
            assertEquals(RtmpType.AUDIO, audio!!.type)
            assertEquals(140, audio.timestampMs)
        } finally {
            connection.close()
            server.close()
            serverThread.join(2_000)
        }
        error.get()?.let { throw AssertionError("server test gagal", it) }
    }

    private fun readFully(input: InputStream, size: Int): ByteArray {
        val data = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = input.read(data, offset, size - offset)
            if (read < 0) error("koneksi test ditutup")
            offset += read
        }
        return data
    }
}
