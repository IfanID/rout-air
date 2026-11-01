package com.rout.air.live

import android.media.MediaFormat
import android.util.Log
import com.rout.air.core.LiveConfig
import com.rout.air.core.LiveStats
import com.rout.air.core.LiveStatus
import com.rout.air.encode.MediaSink
import java.util.ArrayDeque

/**
 * Sink live RTMP. Menerima format/sampel yang sama dengan perekaman MP4,
 * membungkusnya menjadi tag FLV, lalu mengirim ke server.
 *
 * Koneksi berjalan di thread sendiri. Saat terputus, tag terbaru disimpan
 * sebentar di antrean dan koneksi dicoba ulang dengan backoff.
 */
class RtmpSink(
    private val config: LiveConfig,
    private val onStats: (LiveStats) -> Unit,
) : MediaSink {

    private data class QueuedTag(val type: Int, val tag: FlvTag)

    private val flv = FlvMuxer()
    private val lock = Any()
    private val queue = ArrayDeque<QueuedTag>()

    @Volatile
    private var running = true

    @Volatile
    private var connection: RtmpConnection? = null

    private var worker: Thread? = null

    // Statistik (hanya diubah worker atau dengan sinkronisasi lock).
    @Volatile
    private var status = LiveStatus.Connecting
    private var bytesSent = 0L
    private var tagsSent = 0L
    private var droppedTags = 0L
    private var reconnects = 0
    private var lastError: String? = null
    private var sessionStartedMs = System.currentTimeMillis()
    private var connectedSinceMs = 0L
    private var lastStatsAtMs = 0L

    fun start() {
        if (worker != null) return
        val endpoint = runCatching { RtmpEndpoint.parse(config.serverUrl, config.streamKey) }
            .getOrElse { error ->
                status = LiveStatus.Error
                lastError = error.message
                publishStats(force = true)
                return
            }
        worker = Thread({
            try {
                runConnection(endpoint)
            } catch (error: Throwable) {
                // Exception di thread ini tidak ditangkap siapa pun, jadi
                // tanpa ini proses aplikasi ikut mati.
                lastError = error.message ?: error.javaClass.simpleName
                com.rout.air.data.DiagLog.line("rtmp: worker berhenti ${error.message}")
                Log.e("RoutAirRtmp", "worker berhenti", error)
                status = LiveStatus.Error
                publishStats(force = true)
            }
        }, "rout-rtmp").apply {
            isDaemon = true
            start()
        }
        publishStats(force = true)
    }

    override fun addVideoTrack(format: MediaFormat): Int {
        flv.setVideoFormat(format)
        if (status == LiveStatus.Live && connection != null) {
            flv.videoSequenceTag()?.let { sendTag(RtmpType.VIDEO, it) }
        }
        return VIDEO_ID
    }

    override fun addAudioTrack(key: Int, format: MediaFormat): Int {
        if (key == config.audioTrack) {
            flv.setAudioFormat(format)
            if (status == LiveStatus.Live && connection != null) {
                flv.audioSequenceTag()?.let { sendTag(RtmpType.AUDIO, it) }
            }
        }
        return AUDIO_ID_BASE + key
    }

    override fun write(
        trackId: Int,
        data: ByteArray,
        presentationTimeUs: Long,
        flags: Int,
    ) {
        // Konfigurasi codec sudah dikirim sebagai sequence header FLV.
        if (flags and android.media.MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) return
        when {
            trackId == VIDEO_ID -> {
                sendTag(RtmpType.VIDEO, flv.videoTag(data, presentationTimeUs, flags))
            }

            trackId == AUDIO_ID_BASE + config.audioTrack -> {
                sendTag(RtmpType.AUDIO, flv.audioTag(data, presentationTimeUs))
            }
        }
    }

    override fun finish(): Boolean {
        running = false
        connection?.close()
        connection = null
        worker?.let { runCatching { it.join(1_500) } }
        worker = null
        status = LiveStatus.Idle
        publishStats(force = true)
        return true
    }

    private fun runConnection(endpoint: RtmpEndpoint) {
        var attempt = 0
        while (running) {
            val active = connection
            if (active == null) {
                updateStatus(if (attempt == 0) LiveStatus.Connecting else LiveStatus.Reconnecting)
                var created: RtmpConnection? = null
                created = RtmpConnection(
                    endpoint = endpoint,
                    username = config.username,
                    password = config.password,
                ) { reason ->
                    lastError = reason
                    if (connection === created) connection = null
                }
                val active = created ?: break
                val ok = active.connect()
                if (!running) {
                    active.close()
                    break
                }
                if (ok) {
                    connection = active
                    attempt = 0
                    connectedSinceMs = System.currentTimeMillis()
                    lastError = null
                    updateStatus(LiveStatus.Live, force = true)
                    sendSequenceHeaders(created)
                    drainQueue(created)
                } else {
                    lastError = "Gagal terhubung ke server"
                    attempt++
                    reconnects++
                    val delay = if (config.autoReconnect) ReconnectPolicy.delayMs(attempt) else null
                    if (delay == null) {
                        updateStatus(LiveStatus.Error, force = true)
                        break
                    }
                    updateStatus(LiveStatus.Reconnecting, force = true)
                    sleepUnlessStopped(delay)
                }
            } else {
                if (!active.isConnected) {
                    lastError = "Koneksi terputus"
                    connection = null
                    connectedSinceMs = 0L
                    reconnects++
                    updateStatus(LiveStatus.Reconnecting, force = true)
                } else {
                    // Pengiriman dilakukan worker agar encoder tidak pernah
                    // tertahan oleh jaringan yang lambat.
                    drainQueue(active)
                    Thread.sleep(20)
                }
            }
        }
        connection?.close()
        connection = null
        if (status != LiveStatus.Error) status = LiveStatus.Idle
        publishStats(force = true)
    }

    private fun sendSequenceHeaders(connection: RtmpConnection) {
        flv.videoSequenceTag()?.let {
            sendDirect(connection, RtmpType.VIDEO, it)
        }
        flv.audioSequenceTag()?.let {
            sendDirect(connection, RtmpType.AUDIO, it)
        }
    }

    private fun sendTag(type: Int, tag: FlvTag) {
        // Selalu lewat antrean; worker yang mengirim agar encoder tidak
        // menunggu socket (backpressure jaringan tidak menghentikan video).
        enqueue(type, tag)
    }

    private fun sendDirect(connection: RtmpConnection, type: Int, tag: FlvTag) {
        when (type) {
            RtmpType.VIDEO -> connection.sendVideo(tag.timestampMs, tag.payload)
            RtmpType.AUDIO -> connection.sendAudio(tag.timestampMs, tag.payload)
        }
        bytesSent += tag.payload.size
        tagsSent++
        publishStats()
    }

    private fun enqueue(type: Int, tag: FlvTag) {
        synchronized(lock) {
            if (queue.size >= MAX_QUEUE) {
                queue.removeFirst()
                droppedTags++
            }
            queue.addLast(QueuedTag(type, tag))
        }
    }

    private fun drainQueue(target: RtmpConnection) {
        while (running) {
            val item = synchronized(lock) { queue.pollFirst() } ?: break
            runCatching { sendDirect(target, item.type, item.tag) }
                .onFailure { error ->
                    lastError = error.message ?: "Gagal mengirim data live"
                    connection = null
                    target.close()
                    synchronized(lock) { queue.addFirst(item) }
                    return
                }
        }
    }

    private fun updateStatus(next: LiveStatus, force: Boolean = false) {
        if (next != status) {
            com.rout.air.data.DiagLog.line(
                "rtmp: status=$next error=${lastError ?: "-"}",
            )
        }
        status = next
        if (next == LiveStatus.Live) connectedSinceMs = System.currentTimeMillis()
        publishStats(force)
    }

    private fun publishStats(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastStatsAtMs < 500) return
        lastStatsAtMs = now
        val uptime = if (connectedSinceMs > 0) now - connectedSinceMs else 0L
        val seconds = (now - sessionStartedMs) / 1000.0
        val bitrate = if (seconds > 1) {
            (bytesSent * 8 / seconds / 1000).toInt()
        } else {
            0
        }
        val snapshot = LiveStats(
            status = status,
            bytesSent = bytesSent,
            tagsSent = tagsSent,
            droppedTags = droppedTags,
            bitrateKbps = bitrate,
            uptimeMs = uptime,
            reconnects = reconnects,
            lastError = lastError,
        )
        runCatching { onStats(snapshot) }
            .onFailure { Log.w(TAG, "Statistik live gagal dikirim: ${it.message}") }
    }

    private fun sleepUnlessStopped(ms: Long) {
        val deadline = System.currentTimeMillis() + ms
        while (running && System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
        }
    }

    private companion object {
        const val TAG = "RoutAirRtmp"
        const val VIDEO_ID = 1
        const val AUDIO_ID_BASE = 100
        const val MAX_QUEUE = 240
    }
}
