package com.rout.air.encode

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.util.Log
import com.rout.air.core.AudioCaptureKind
import com.rout.air.core.AudioChannelId
import com.rout.air.core.AudioLevels
import com.rout.air.core.ChannelLevel
import com.rout.air.core.ChannelMix
import com.rout.air.core.FaderScale
import com.rout.air.core.MixSnapshot
import com.rout.air.core.PeakMeterType
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Mesin audio sesi: menangkap audio internal dan mikrofon, memproses filter,
 * gain/balance, sync offset, lalu mencampur ke trek output.
 *
 * Penangkapan + meter + monitoring berjalan selama sesi (seperti OBS).
 * Encoder AAC baru dibuat saat rekaman dimulai, dan multitrack hanya membuat
 * encoder untuk track yang dipilih.
 */
class AudioCaptureEngine(
    private val context: Context,
    private val projection: MediaProjection?,
    private val sampleRate: Int,
    private val channels: Int,
    private val bitrate: Int,
    private val liveMix: () -> MixSnapshot,
    private val onLevels: (AudioLevels) -> Unit,
    private val onAvailability: (ready: Set<AudioChannelId>) -> Unit,
) {
    private val codecLock = Any()
    private var encoders: List<TrackEncoder> = emptyList()
    private var sink: MediaSink? = null
    private var recordingTracks = 0
    private var totalSamples = 0L
    private var startOffsetUs = 0L

    private val thread = Thread(::processLoop, "rout-audio-mix")
    private val sources = LinkedHashMap<AudioChannelId, CaptureSource>()
    private val monitor = MonitorOutput(context, sampleRate, channels)

    private val trackInt = Array(6) { IntArray(CHUNK_FRAMES * channels) }
    private val trackShort = Array(6) { ShortArray(CHUNK_FRAMES * channels) }
    private val monitorInt = IntArray(CHUNK_FRAMES * channels)
    private val monitorChunk = ShortArray(CHUNK_FRAMES * channels)

    private val failedSources = HashMap<AudioChannelId, String>()
    private var lastAvailability: Set<AudioChannelId>? = null

    private var mixedFrames = 0L
    private var lastLevelAtNs = 0L
    private var scoActive = false
    private var scoAttempted = false
    private var scoFailed = false
    private var scoAttemptAtNs = 0L
    private var scoSignature = ""

    @Volatile
    private var running = false

    /** Saat dijeda, chunk tetap dicampur untuk meter/monitoring, tetapi
     *  tidak dikirim ke encoder sehingga timeline audio ikut berhenti. */
    @Volatile
    private var paused = false

    init {
        syncSources(liveMix())
    }

    fun start() {
        running = true
        thread.start()
        notifyAvailability()
    }

    /**
     * Mulai trek audio. [mix] dipakai sekali agar jumlah track dan pilihan
     * track konsisten dengan yang sudah dijanjikan ke muxer.
     */
    fun beginRecording(target: MediaSink, recordingStartNs: Long, mix: MixSnapshot) {
        synchronized(codecLock) {
            if (encoders.isNotEmpty()) return

            val tracks = mix.outputTracks and 0x3F
            val ids = (1..6).filter { tracks and (1 shl (it - 1)) != 0 }
            if (ids.isEmpty()) error("Tidak ada track audio yang dipilih")

            val created = mutableListOf<TrackEncoder>()
            try {
                ids.forEach { key ->
                    created += TrackEncoder(
                        key = key,
                        sink = target,
                        sampleRate = sampleRate,
                        channels = channels,
                        bitrate = bitrate,
                    )
                }
            } catch (error: Throwable) {
                created.forEach { it.release() }
                throw error
            }

            this.sink = target
            encoders = created
            recordingTracks = tracks
            totalSamples = 0L
            startOffsetUs = ((System.nanoTime() - recordingStartNs) / 1_000L).coerceAtLeast(0L)
            sources.values.forEach { it.discard() }
        }
    }

    fun endRecording(limitPtsUs: Long = Long.MAX_VALUE, timeoutMs: Long = 4_000) {
        synchronized(codecLock) {
            if (encoders.isEmpty()) return
            val pts = presentationTimeUs()
            encoders.forEach { it.finish(pts, limitPtsUs, timeoutMs) }
            encoders = emptyList()
            sink = null
            recordingTracks = 0
            paused = false
        }
    }

    /** Jeda pengiriman audio ke encoder; capture + meter tetap hidup. */
    fun pauseRecording() {
        paused = true
    }

    fun resumeRecording() {
        paused = false
    }

    fun shutdown(limitPtsUs: Long = Long.MAX_VALUE) {
        endRecording(limitPtsUs)
        running = false
        thread.join(1_500)
        sources.values.forEach { it.stop() }
        sources.clear()
        failedSources.clear()
        stopSco()
        monitor.release()
    }

    // --- Pencampuran -------------------------------------------------------

    private fun processLoop() {
        var clockStartNs = 0L
        var firstChunk = true
        while (running) {
            val mix = liveMix()
            ensureSources(mix)

            if (firstChunk) {
                val active = sources.filter { mix.forChannel(it.key).enabled }
                if (active.isNotEmpty()) {
                    val deadline = System.nanoTime() + FIRST_CHUNK_WAIT_NS
                    while (running && System.nanoTime() < deadline &&
                        active.values.none { it.framesAvailable() >= CHUNK_FRAMES }
                    ) {
                        Thread.sleep(1)
                    }
                }
                clockStartNs = System.nanoTime()
                firstChunk = false
            } else {
                val dueNs = clockStartNs + mixedFrames * 1_000_000_000L / sampleRate
                while (running && System.nanoTime() < dueNs) {
                    Thread.sleep(1)
                }
            }

            mixChunk(mix)
            mixedFrames += CHUNK_FRAMES

            synchronized(codecLock) {
                if (encoders.isNotEmpty() && !paused) {
                    val pts = presentationTimeUs()
                    encoders.forEach { encoder ->
                        encoder.encode(trackShort[encoder.key - 1], pts)
                        encoder.drain()
                    }
                    totalSamples += CHUNK_FRAMES
                }
            }
        }
    }

    private fun mixChunk(mix: MixSnapshot) {
        val monitorNeeded = mix.channels.values.any { it.enabled && it.monitor.monitors }

        if (!mix.masterEnabled) {
            for (track in 0 until 6) {
                trackInt[track].fill(0)
                trackShort[track].fill(0)
            }
            monitorInt.fill(0)
            monitor.release()
            reportLevels(emptyMap())
            return
        }

        if (recordingTracks != 0) {
            for (track in 0 until 6) {
                if (recordingTracks and (1 shl track) != 0) trackInt[track].fill(0)
            }
        }
        if (monitorNeeded) monitorInt.fill(0) else monitor.release()

        val levels = mutableMapOf<AudioChannelId, ChannelLevel>()

        sources.forEach { (id, source) ->
            val channelMix = mix.forChannel(id)
            if (!channelMix.enabled) {
                source.discard()
                return@forEach
            }

            val frames = source.readInto(CHUNK_FRAMES)
            levels[id] = processSource(source, channelMix, frames, mix, monitorNeeded)
        }

        if (recordingTracks != 0) {
            for (track in 0 until 6) {
                if (recordingTracks and (1 shl track) == 0) continue
                val ints = trackInt[track]
                val shorts = trackShort[track]
                for (i in shorts.indices) {
                    shorts[i] = ints[i].coerceIn(-32768, 32767).toShort()
                }
            }
        }

        if (monitorNeeded) {
            for (i in monitorChunk.indices) {
                monitorChunk[i] = monitorInt[i].coerceIn(-32768, 32767).toShort()
            }
            monitor.write(monitorChunk, resolveMonitorDeviceId(mix.monitoringDeviceId))
        }

        reportLevels(levels)
    }

    /**
     * Memproses satu sumber: filter -> gain/balance -> level -> sync offset ->
     * campur ke trek/monitor. Level dihitung pasca-fader seperti OBS.
     */
    private fun processSource(
        source: CaptureSource,
        channelMix: ChannelMix,
        frames: Int,
        mix: MixSnapshot,
        monitorNeeded: Boolean,
    ): ChannelLevel {
        if (frames <= 0) return ChannelLevel()

        val pushTalk = channelMix.capture == AudioCaptureKind.Microphone && mix.pushToTalkActive
        val pushMute = channelMix.capture == AudioCaptureKind.Microphone &&
            mix.pushToMuteActive && !pushTalk
        val muted = !pushTalk && (channelMix.muted || pushMute)

        // Konversi PCM 16-bit ke float -1..1.
        val captureChannels = source.captureChannels
        for (i in 0 until frames) {
            val l = source.scratch[i * captureChannels] / 32768f
            val r = if (captureChannels == 2) {
                source.scratch[i * captureChannels + 1] / 32768f
            } else {
                l
            }
            if (channelMix.forceMono) {
                val mono = (l + r) * 0.5f
                source.left[i] = mono
                source.right[i] = mono
            } else {
                source.left[i] = l
                source.right[i] = r
            }
        }

        source.filterChain.process(channelMix.filters, source.left, source.right, frames)

        val gainLeft = channelMix.leftGain
        val gainRight = channelMix.rightGain
        if (gainLeft != 1f || gainRight != 1f) {
            for (i in 0 until frames) {
                source.left[i] *= gainLeft
                source.right[i] *= gainRight
            }
        }

        val level = if (muted) {
            ChannelLevel()
        } else {
            measure(source.left, source.right, frames, mix.peakMeterType)
        }

        source.aligner.process(
            left = source.left,
            right = source.right,
            frames = frames,
            offsetMs = channelMix.syncOffsetMs,
            outLeft = source.outLeft,
            outRight = source.outRight,
        )

        if (!muted) {
            val recordTracks = if (channelMix.monitor.outputs) {
                channelMix.tracks and recordingTracks
            } else {
                0
            }
            if (recordTracks != 0) {
                for (track in 0 until 6) {
                    if (recordTracks and (1 shl track) == 0) continue
                    addToBuffer(trackInt[track], source.outLeft, source.outRight, frames)
                }
            }
            if (monitorNeeded && channelMix.monitor.monitors) {
                addToBuffer(monitorInt, source.outLeft, source.outRight, frames)
            }
        }

        return level
    }

    private fun addToBuffer(dest: IntArray, left: FloatArray, right: FloatArray, frames: Int) {
        if (channels == 1) {
            for (i in 0 until frames) {
                dest[i] += ((left[i] + right[i]) * 0.5f * 32767f).roundToInt()
            }
        } else {
            for (i in 0 until frames) {
                dest[i * 2] += (left[i] * 32767f).roundToInt()
                dest[i * 2 + 1] += (right[i] * 32767f).roundToInt()
            }
        }
    }

    private fun measure(
        left: FloatArray,
        right: FloatArray,
        frames: Int,
        peakType: PeakMeterType,
    ): ChannelLevel {
        var sumL = 0.0
        var sumR = 0.0
        var peakL = 0f
        var peakR = 0f
        var previousL = 0f
        var previousR = 0f

        for (i in 0 until frames) {
            val l = left[i]
            val r = right[i]
            sumL += l.toDouble() * l
            sumR += r.toDouble() * r

            var pl = abs(l)
            var pr = abs(r)
            if (peakType == PeakMeterType.TruePeak && i > 0) {
                // Perkiraan true peak dengan interpolasi linear 2x.
                pl = max(pl, (abs(l) + abs(previousL)) * 0.5f)
                pr = max(pr, (abs(r) + abs(previousR)) * 0.5f)
            }
            peakL = max(peakL, pl)
            peakR = max(peakR, pr)
            previousL = l
            previousR = r
        }

        val rmsL = sqrt(sumL / frames)
        val rmsR = sqrt(sumR / frames)
        return ChannelLevel(
            leftDb = amplitudeToDb(rmsL),
            rightDb = amplitudeToDb(rmsR),
            peakLeftDb = amplitudeToDb(peakL.toDouble()),
            peakRightDb = amplitudeToDb(peakR.toDouble()),
        )
    }

    private fun amplitudeToDb(amplitude: Double): Float {
        if (amplitude <= 1e-7) return FaderScale.MIN_DB
        return (20.0 * log10(amplitude)).toFloat().coerceIn(FaderScale.MIN_DB, FaderScale.MAX_DB)
    }

    private fun reportLevels(levels: Map<AudioChannelId, ChannelLevel>) {
        val now = System.nanoTime()
        if (now - lastLevelAtNs < LEVEL_INTERVAL_NS) return
        lastLevelAtNs = now
        onLevels(AudioLevels(levels))
    }

    // --- Sumber audio ------------------------------------------------------

    private fun ensureSources(mix: MixSnapshot) {
        if (!running) return
        syncSources(mix)
    }

    /** Membuka/menutup recorder mengikuti daftar kanal mixer yang aktif. */
    private fun syncSources(mix: MixSnapshot) {
        val desired = mix.channels.filterValues { it.enabled }

        // SCO/Bluetooth dikelola otomatis. Hanya kanal mikrofon Bluetooth yang
        // membutuhkannya; tidak ada toggle manual yang bisa menyalahkan state.
        val bluetoothMicSignature = desired.values
            .filter { it.capture == AudioCaptureKind.Microphone && it.sco }
            .map { it.deviceId }
            .sorted()
            .joinToString(",")

        if (bluetoothMicSignature != scoSignature) {
            scoSignature = bluetoothMicSignature
            scoFailed = false
            scoAttempted = false
            if (bluetoothMicSignature.isEmpty()) stopSco()
        }

        if (bluetoothMicSignature.isNotEmpty()) {
            if (!scoAttempted && !scoFailed) startSco()
            if (scoAttempted && !scoFailed) {
                val manager = context.getSystemService(AudioManager::class.java)
                scoActive = runCatching { manager?.isBluetoothScoOn == true }.getOrDefault(false)
                if (!scoActive &&
                    System.nanoTime() - scoAttemptAtNs > SCO_CONNECT_TIMEOUT_NS
                ) {
                    Log.w(TAG, "SCO Bluetooth tidak terhubung; memakai fallback mic")
                    scoFailed = true
                    stopSco()
                }
            }
        } else {
            scoActive = false
        }

        val bluetoothMicReady = bluetoothMicSignature.isEmpty() || scoActive || scoFailed

        sources.entries.toList().forEach { (id, source) ->
            val channel = desired[id]
            if (channel == null ||
                source.capture != channel.capture ||
                source.deviceId != channel.deviceId
            ) {
                sources.remove(id)?.stop()
            }
        }

        failedSources.keys.toList().forEach { id ->
            val channel = desired[id]
            if (channel == null || failedSources[id] != signature(channel)) {
                failedSources.remove(id)
            }
        }

        desired.forEach { (id, channel) ->
            if (sources.containsKey(id)) return@forEach
            // Tunggu SCO terhubung sebelum membuka mic Bluetooth; kalau gagal
            // atau timeout, buka dengan fallback mic default.
            if (channel.capture == AudioCaptureKind.Microphone &&
                channel.sco && !bluetoothMicReady
            ) {
                return@forEach
            }
            val signature = signature(channel)
            if (failedSources[id] == signature) return@forEach

            val source = openSource(id, channel)
            if (source != null && source.start()) {
                sources[id] = source
                failedSources.remove(id)
            } else {
                failedSources[id] = signature
            }
        }

        notifyAvailability()
    }

    private fun signature(channel: ChannelMix): String =
        "${channel.capture.name}:${channel.deviceId}:${channel.sco}:${channel.captureUid}"

    @Suppress("DEPRECATION")
    private fun startSco() {
        val manager = context.getSystemService(AudioManager::class.java) ?: return
        scoAttempted = true
        scoAttemptAtNs = System.nanoTime()
        runCatching {
            manager.startBluetoothSco()
            manager.isBluetoothScoOn = true
        }.onFailure {
            Log.w(TAG, "SCO Bluetooth gagal dimulai: ${it.message}")
            scoFailed = true
            scoAttempted = false
            scoActive = false
        }
    }

    @Suppress("DEPRECATION")
    private fun stopSco() {
        val manager = context.getSystemService(AudioManager::class.java) ?: return
        runCatching {
            manager.isBluetoothScoOn = false
            manager.stopBluetoothSco()
        }
        scoActive = false
        scoAttempted = false
    }

    private fun notifyAvailability() {
        val current = sources.keys.toSet()
        if (current == lastAvailability) return
        lastAvailability = current
        onAvailability(current)
    }

    private fun openSource(id: AudioChannelId, channel: ChannelMix): CaptureSource? =
        when (channel.capture) {
            AudioCaptureKind.Playback -> openPlayback(id, channel.captureUid)
            AudioCaptureKind.Microphone -> openMicrophone(id, channel.deviceId)
        }

    private fun openPlayback(id: AudioChannelId, captureUid: Int): CaptureSource? {
        val activeProjection = projection ?: return null
        val builder = AudioPlaybackCaptureConfiguration.Builder(activeProjection)
        if (captureUid != 0) {
            builder.addMatchingUid(captureUid)
        } else {
            builder
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .addMatchingUsage(AudioAttributes.USAGE_ASSISTANT)
        }
        val captureConfig = builder.build()

        for (captureChannels in listOf(channels, 1).distinct()) {
            try {
                val recorder = buildRecorder(
                    captureChannels = captureChannels,
                    playback = captureConfig,
                )
                return CaptureSource(
                    id = id,
                    recorder = recorder,
                    captureChannels = captureChannels,
                    capture = AudioCaptureKind.Playback,
                    deviceId = 0,
                )
            } catch (error: Throwable) {
                Log.w(TAG, "Playback ${id.key} $captureChannels kanal gagal: ${error.message}")
            }
        }
        return null
    }

    private fun openMicrophone(id: AudioChannelId, deviceId: Int): CaptureSource? {
        val candidates = listOf(
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.MIC,
        )
        for (audioSource in candidates) {
            for (captureChannels in listOf(channels, 1).distinct()) {
                try {
                    val recorder = buildRecorder(
                        captureChannels = captureChannels,
                        playback = null,
                        audioSource = audioSource,
                        deviceId = deviceId,
                    )
                    return CaptureSource(
                        id = id,
                        recorder = recorder,
                        captureChannels = captureChannels,
                        capture = AudioCaptureKind.Microphone,
                        deviceId = deviceId,
                    )
                } catch (error: Throwable) {
                    Log.w(TAG, "Mikrofon ${id.key} (device $deviceId) gagal: ${error.message}")
                }
            }
        }
        // Perangkat pilihan (mis. TWS yang sedang tidak dalam mode SCO) gagal:
        // jangan biarkan kanal mati total, coba mikrofon default — tetapi
        // hanya jika belum ada sumber mikrofon lain yang memakai mic default.
        if (deviceId != 0 && sources.values.none { it.capture == AudioCaptureKind.Microphone }) {
            return openMicrophone(id, 0)
        }
        return null
    }

    private fun buildRecorder(
        captureChannels: Int,
        playback: AudioPlaybackCaptureConfiguration?,
        audioSource: Int = MediaRecorder.AudioSource.DEFAULT,
        deviceId: Int = 0,
    ): AudioRecord {
        val channelMask = if (captureChannels == 1) {
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

        val builder = AudioRecord.Builder()
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuffer * 2, sampleRate / 10 * captureChannels * 2))

        if (playback != null) {
            builder.setAudioPlaybackCaptureConfig(playback)
        } else {
            builder.setAudioSource(audioSource)
        }

        val recorder = builder.build()
        if (playback == null && deviceId != 0) {
            val manager = context.getSystemService(AudioManager::class.java)
            val device = manager?.getDevices(AudioManager.GET_DEVICES_INPUTS)
                ?.firstOrNull { it.id == deviceId }
            if (device != null) {
                runCatching { recorder.preferredDevice = device }
            }
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            error("AudioRecord gagal diinisialisasi")
        }
        return recorder
    }

    private fun presentationTimeUs(): Long =
        startOffsetUs.coerceAtLeast(0L) + totalSamples * 1_000_000L / sampleRate

    /**
     * Menentukan output monitoring. Jika pengguna memilih perangkat, pakai itu.
     * Jika "Otomatis", ikuti perangkat komunikasi saat SCO aktif, lalu pilih
     * output Bluetooth/headset/USB yang tersedia sebelum speaker bawaan.
     */
    private fun resolveMonitorDeviceId(requested: Int): Int {
        if (requested != 0) return requested
        val manager = context.getSystemService(AudioManager::class.java) ?: return 0
        if (scoActive) {
            val communication = manager.communicationDevice
            if (communication != null) return communication.id
        }
        val outputs = runCatching {
            manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        }.getOrDefault(emptyList())
        val priority = listOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        )
        return priority.firstNotNullOfOrNull { type ->
            outputs.firstOrNull { it.type == type }?.id
        } ?: 0
    }

    // --- Encoder trek ------------------------------------------------------

    private class TrackEncoder(
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

    // --- Kelas bantu -------------------------------------------------------

    private inner class CaptureSource(
        val id: AudioChannelId,
        private val recorder: AudioRecord,
        val captureChannels: Int,
        val capture: AudioCaptureKind,
        val deviceId: Int,
    ) {
        private val ring = PcmRing(RING_FRAMES, captureChannels)
        private val readBuffer = ShortArray(READ_FRAMES * captureChannels)
        val scratch = ShortArray(CHUNK_FRAMES * captureChannels)
        val left = FloatArray(CHUNK_FRAMES)
        val right = FloatArray(CHUNK_FRAMES)
        val outLeft = FloatArray(CHUNK_FRAMES)
        val outRight = FloatArray(CHUNK_FRAMES)
        val filterChain = ChannelFilterChain(sampleRate)
        val aligner = SyncAligner(sampleRate)

        @Volatile
        private var active = false
        private var reader: Thread? = null
        private var leftover = 0

        fun start(): Boolean {
            val started = runCatching {
                recorder.startRecording()
                recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING
            }.getOrDefault(false)

            if (!started) {
                runCatching { recorder.release() }
                active = false
                return false
            }

            active = true
            reader = Thread(::readLoop, "rout-audio-${id.key}").also { it.start() }
            return true
        }

        fun stop() {
            active = false
            runCatching { recorder.stop() }
            reader?.join(500)
            reader = null
            runCatching { recorder.release() }
            ring.clear()
        }

        fun framesAvailable(): Int = ring.framesAvailable()

        fun discard() {
            ring.clear()
            scratch.fill(0)
            left.fill(0f)
            right.fill(0f)
            leftover = 0
            aligner.reset()
        }

        fun readInto(frames: Int): Int {
            val read = ring.read(scratch, frames)
            val filled = read * captureChannels
            if (filled < scratch.size) scratch.fill(0, filled, scratch.size)
            return read
        }

        private fun readLoop() {
            while (active) {
                val read = try {
                    recorder.read(
                        readBuffer,
                        leftover,
                        readBuffer.size - leftover,
                        AudioRecord.READ_BLOCKING,
                    )
                } catch (error: Throwable) {
                    Log.w(TAG, "Baca $id gagal: ${error.message}")
                    -1
                }
                if (read <= 0) {
                    if (read < 0) Log.w(TAG, "Baca $id gagal ($read)")
                    if (active) Thread.sleep(5)
                    continue
                }

                val total = read + leftover
                val frames = total / captureChannels
                if (frames > 0) ring.write(readBuffer, frames * captureChannels)

                val remainder = total % captureChannels
                if (remainder > 0) {
                    System.arraycopy(
                        readBuffer,
                        frames * captureChannels,
                        readBuffer,
                        0,
                        remainder,
                    )
                }
                leftover = remainder
            }
        }
    }

    /** Ring buffer PCM antar-thread; frame tertua dibuang saat penuh. */
    private class PcmRing(capacityFrames: Int, private val channels: Int) {
        private val capacity = capacityFrames
        private val data = ShortArray(capacity * channels)
        private var readPos = 0
        private var writePos = 0
        private var size = 0

        @Synchronized
        fun write(src: ShortArray, shorts: Int) {
            var frames = shorts / channels
            if (frames <= 0) return

            var srcOffset = 0
            if (frames > capacity) {
                srcOffset = (frames - capacity) * channels
                frames = capacity
                readPos = 0
                writePos = 0
                size = 0
            }

            val free = capacity - size
            if (frames > free) {
                val drop = frames - free
                readPos = (readPos + drop) % capacity
                size -= drop
            }

            val first = min(frames, capacity - writePos)
            System.arraycopy(src, srcOffset, data, writePos * channels, first * channels)
            if (first < frames) {
                System.arraycopy(
                    src,
                    srcOffset + first * channels,
                    data,
                    0,
                    (frames - first) * channels,
                )
            }
            writePos = (writePos + frames) % capacity
            size += frames
        }

        @Synchronized
        fun read(dest: ShortArray, frames: Int): Int {
            val count = min(frames, size)
            if (count <= 0) return 0

            val first = min(count, capacity - readPos)
            System.arraycopy(data, readPos * channels, dest, 0, first * channels)
            if (first < count) {
                System.arraycopy(data, 0, dest, first * channels, (count - first) * channels)
            }
            readPos = (readPos + count) % capacity
            size -= count
            return count
        }

        @Synchronized
        fun framesAvailable(): Int = size

        @Synchronized
        fun clear() {
            readPos = 0
            writePos = 0
            size = 0
        }
    }

    /**
     * Output monitoring. `ALLOW_CAPTURE_BY_NONE` mencegah suara monitor
     * tertangkap lagi oleh AudioPlaybackCapture.
     */
    private class MonitorOutput(
        private val context: Context,
        sampleRate: Int,
        private val channels: Int,
    ) {
        private var track: AudioTrack? = null
        private val sampleRate = sampleRate
        private var deviceId = 0

        fun write(pcm: ShortArray, requestedDeviceId: Int) {
            val current = track ?: create()?.also { track = it } ?: return
            if (requestedDeviceId != deviceId) {
                deviceId = requestedDeviceId
                applyDevice(current)
            }
            val written = runCatching {
                current.write(pcm, 0, pcm.size, AudioTrack.WRITE_NON_BLOCKING)
            }.getOrDefault(-1)
            if (written < 0) {
                release()
            }
        }

        fun release() {
            runCatching { track?.pause() }
            runCatching { track?.flush() }
            runCatching { track?.release() }
            track = null
        }

        private fun applyDevice(track: AudioTrack) {
            val device = if (deviceId == 0) {
                null
            } else {
                val manager = context.getSystemService(AudioManager::class.java)
                manager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    ?.firstOrNull { it.id == deviceId }
            }
            val applied = runCatching {
                if (device == null) {
                    track.setPreferredDevice(null)
                } else {
                    track.setPreferredDevice(device)
                }
            }.getOrDefault(false)
            Log.i(
                TAG,
                "Monitoring output: ${device?.productName ?: "otomatis"} (id=$deviceId, ok=$applied)",
            )
        }

        private fun create(): AudioTrack? = try {
            val channelMask = if (channels == 1) {
                AudioFormat.CHANNEL_OUT_MONO
            } else {
                AudioFormat.CHANNEL_OUT_STEREO
            }
            val minBuffer = AudioTrack.getMinBufferSize(
                sampleRate,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            check(minBuffer > 0) { "AudioTrack tidak mendukung audio $sampleRate Hz" }

            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelMask)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minBuffer, sampleRate / 10 * channels * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
                .also {
                    applyDevice(it)
                    it.play()
                }
        } catch (error: Throwable) {
            Log.w(TAG, "Monitoring tidak tersedia: ${error.message}")
            null
        }
    }

    private companion object {
        const val TAG = "RoutAirAudio"
        const val MIME = MediaFormat.MIMETYPE_AUDIO_AAC
        const val CHUNK_FRAMES = 1024
        const val READ_FRAMES = 480
        const val RING_FRAMES = 8192
        const val DEQUEUE_TIMEOUT_US = 10_000L
        const val FIRST_CHUNK_WAIT_NS = 250_000_000L
        const val LEVEL_INTERVAL_NS = 50_000_000L
        const val SCO_CONNECT_TIMEOUT_NS = 2_500_000_000L
    }
}
