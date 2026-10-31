package com.rout.air.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.rout.air.core.AudioChannel
import com.rout.air.core.AudioChannelId
import com.rout.air.core.AudioCaptureKind
import com.rout.air.core.AudioFilter
import com.rout.air.core.AudioLevels
import com.rout.air.core.AudioMix
import com.rout.air.core.AudioRuntime
import com.rout.air.core.FaderScale
import com.rout.air.core.MeterDecay
import com.rout.air.core.MixSnapshot
import com.rout.air.core.PeakMeterType
import com.rout.air.core.SceneCollection
import com.rout.air.core.SourceType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Sumber kebenaran mixer audio.
 *
 * UI mengubah pengaturan lewat objek ini, lalu thread encoder membaca
 * [snapshot] setiap potongan PCM. Perubahan volume/mute/filter saat merekam
 * langsung terdengar tanpa menghentikan sesi.
 */
object AudioMixer {
    private val lock = Any()
    private var store: AudioMixStore? = null

    private val _mix = MutableStateFlow(AudioMix())
    val mix: StateFlow<AudioMix> = _mix.asStateFlow()

    private val _levels = MutableStateFlow(AudioLevels())
    val levels: StateFlow<AudioLevels> = _levels.asStateFlow()

    private val _runtime = MutableStateFlow(AudioRuntime())
    val runtime: StateFlow<AudioRuntime> = _runtime.asStateFlow()

    private val saveHandler = Handler(Looper.getMainLooper())
    private val saveTask = Runnable { store?.save(_mix.value) }

    // Tombol tahan push-to-mute/talk tidak disimpan; hanya berlaku saat ditekan.
    @Volatile
    private var pushToMuteActive = false

    @Volatile
    private var pushToTalkActive = false

    private var filterClipboard: List<AudioFilter> = emptyList()

    fun attach(context: Context) {
        synchronized(lock) {
            if (store != null) return
            val loaded = AudioMixStore(context.applicationContext)
            store = loaded
            _mix.value = loaded.load()
        }
    }

    fun update(channel: AudioChannel) {
        _mix.value = _mix.value.update(channel)
        scheduleSave()
    }

    /**
     * Menyelaraskan kanal mixer dengan sumber audio di scene collection.
     * Kanal global (Desktop/Mic/Aux) tetap ada; kanal sumber ditambah,
     * diperbarui, atau dibuang mengikuti panel Sumber.
     */
    fun syncFromScene(collection: SceneCollection) {
        val audioSources = collection.sources.filter {
            it.type == SourceType.AudioInput || it.type == SourceType.AudioOutput
        }
        val current = _mix.value
        // Kanal global dan kanal aplikasi (sourceId == null) tidak dikelola
        // oleh scene collection, jadi selalu dipertahankan.
        val kept = current.channels.filter { it.global || it.sourceId == null }
        val existing = current.channels.filter { it.sourceId != null }
            .associateBy { it.sourceId }

        val synced = audioSources.map { source ->
            val id = AudioChannelId.forSource(source.id)
            val capture = if (source.type == SourceType.AudioInput) {
                AudioCaptureKind.Microphone
            } else {
                AudioCaptureKind.Playback
            }
            val base = existing[source.id] ?: AudioChannel(
                id = id,
                name = source.name,
                enabled = true,
                capture = capture,
                deviceId = source.settings.audioDeviceId,
                deviceName = source.settings.audioDeviceName,
                deviceType = source.settings.audioDeviceType,
                sco = source.settings.audioDeviceType ==
                    android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                global = false,
                sourceId = source.id,
            )
            base.copy(
                id = id,
                name = source.name,
                capture = capture,
                deviceId = source.settings.audioDeviceId,
                deviceName = source.settings.audioDeviceName,
                deviceType = source.settings.audioDeviceType,
                sco = source.settings.audioDeviceType ==
                    android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                global = false,
                sourceId = source.id,
            )
        }

        val updates = (kept + synced).associateBy { it.id }
        val merged = mutableListOf<AudioChannel>()
        current.channels.forEach { old ->
            updates[old.id]?.let { merged += it.copy(order = old.order) }
        }
        var nextOrder = (merged.maxOfOrNull { it.order } ?: -1) + 1
        updates.values
            .filter { updated -> merged.none { it.id == updated.id } }
            .forEach { updated ->
                merged += updated.copy(order = nextOrder)
                nextOrder++
            }

        val next = current.copy(channels = merged)
        if (next != current) {
            _mix.value = next
            scheduleSave()
        }
    }

    /** Menggeser kanal naik/turun dan menomori ulang urutan 0..n. */
    fun moveChannel(id: AudioChannelId, delta: Int) {
        val current = _mix.value
        val sorted = current.channels.sortedBy { it.order }.toMutableList()
        val index = sorted.indexOfFirst { it.id == id }
        if (index < 0) return
        val target = (index + delta).coerceIn(0, sorted.lastIndex)
        if (target == index) return
        val item = sorted.removeAt(index)
        sorted.add(target, item)
        _mix.value = current.copy(
            channels = sorted.mapIndexed { position, channel ->
                channel.copy(order = position)
            },
        )
        scheduleSave()
    }

    /**
     * Mengganti perangkat input untuk kanal mikrofon, termasuk Mic/Aux global.
     * Untuk sumber audio di panel Sumber, pengaturan ikut ditulis ke scene
     * collection supaya tidak dikembalikan oleh sinkronisasi berikutnya.
     */
    fun changeInputDevice(
        id: AudioChannelId,
        deviceId: Int,
        deviceName: String?,
        deviceType: Int,
    ) {
        val channel = _mix.value.channel(id)
        if (channel.capture != AudioCaptureKind.Microphone) return
        val sco = deviceType == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        val updated = channel.copy(
            deviceId = deviceId,
            deviceName = deviceName,
            deviceType = deviceType,
            sco = sco,
        )
        update(updated)
        syncSceneDevice(updated)
    }

    fun setSco(id: AudioChannelId, enabled: Boolean) {
        val updated = _mix.value.channel(id).copy(sco = enabled)
        update(updated)
        syncSceneDevice(updated)
    }

    /** Menambahkan kanal Application Audio Capture untuk satu aplikasi. */
    fun addApplicationAudio(uid: Int, packageName: String, label: String) {
        if (uid <= 0 || packageName.isBlank()) return
        val id = AudioChannelId.forApp(uid, packageName)
        if (_mix.value.channels.any { it.id == id }) return
        val order = (_mix.value.channels.maxOfOrNull { it.order } ?: -1) + 1
        val channel = AudioChannel(
            id = id,
            name = label.ifBlank { packageName },
            enabled = true,
            capture = AudioCaptureKind.Playback,
            appUid = uid,
            appPackage = packageName,
            global = false,
            sourceId = null,
            order = order,
        )
        _mix.value = _mix.value.copy(channels = _mix.value.channels + channel)
        scheduleSave()
    }

    /** Menghapus kanal aplikasi dari mixer. Kanal global tidak bisa dihapus. */
    fun removeChannel(id: AudioChannelId) {
        val channel = _mix.value.channel(id)
        if (channel.global) return
        _mix.value = _mix.value.copy(
            channels = _mix.value.channels.filterNot { it.id == id },
        )
        scheduleSave()
    }

    private fun syncSceneDevice(channel: AudioChannel) {
        val sourceId = channel.sourceId ?: return
        val collection = runCatching { SceneManager.current() }.getOrNull() ?: return
        val next = collection.copy(
            sources = collection.sources.map { source ->
                if (source.id != sourceId) {
                    source
                } else {
                    source.copy(
                        settings = source.settings.copy(
                            audioDeviceId = channel.deviceId,
                            audioDeviceName = channel.deviceName,
                            audioDeviceType = channel.deviceType,
                            audioScoEnabled = channel.sco,
                        ),
                    )
                }
            },
        )
        SceneManager.update(next)
    }

    fun setMasterEnabled(enabled: Boolean) = mutate { it.withMasterEnabled(enabled) }
    fun setVerticalLayout(vertical: Boolean) = mutate { it.copy(verticalLayout = vertical) }
    fun setShowHidden(show: Boolean) = mutate { it.copy(showHidden = show) }
    fun setShowInactive(show: Boolean) = mutate { it.copy(showInactive = show) }
    fun setKeepHiddenLast(keep: Boolean) = mutate { it.copy(keepHiddenLast = keep) }
    fun setKeepInactiveLast(keep: Boolean) = mutate { it.copy(keepInactiveLast = keep) }
    fun setOutputTracks(tracks: Int) = mutate { it.copy(outputTracks = tracks and 0x3F) }
    fun setMeterDecay(decay: MeterDecay) = mutate { it.copy(meterDecay = decay) }
    fun setPeakMeterType(type: PeakMeterType) = mutate { it.copy(peakMeterType = type) }
    fun setMonitoringDevice(id: Int) = mutate { it.copy(monitoringDeviceId = id) }
    fun setVolumePercent(enabled: Boolean) = mutate { it.copy(volumePercent = enabled) }
    fun setPushToMuteEnabled(enabled: Boolean) = mutate { it.copy(pushToMuteEnabled = enabled) }
    fun setPushToTalkEnabled(enabled: Boolean) = mutate { it.copy(pushToTalkEnabled = enabled) }

    fun setPushToMuteActive(active: Boolean) {
        pushToMuteActive = active
    }

    fun setPushToTalkActive(active: Boolean) {
        pushToTalkActive = active
    }

    fun setEnabled(id: AudioChannelId, enabled: Boolean) =
        update(_mix.value.channel(id).copy(enabled = enabled))

    fun setMuted(id: AudioChannelId, muted: Boolean) =
        update(_mix.value.channel(id).copy(muted = muted))

    fun setPinned(id: AudioChannelId, pinned: Boolean) =
        update(_mix.value.channel(id).copy(pinned = pinned, hidden = if (pinned) false else _mix.value.channel(id).hidden))

    fun setHidden(id: AudioChannelId, hidden: Boolean) =
        update(_mix.value.channel(id).copy(hidden = hidden, pinned = if (hidden) false else _mix.value.channel(id).pinned))

    fun setLocked(id: AudioChannelId, locked: Boolean) =
        update(_mix.value.channel(id).copy(locked = locked))

    fun rename(id: AudioChannelId, name: String) {
        val trimmed = name.trim().ifEmpty { id.defaultName }
        update(_mix.value.channel(id).copy(name = trimmed))
    }

    fun addFilter(id: AudioChannelId, filter: AudioFilter) {
        val channel = _mix.value.channel(id)
        update(channel.copy(filters = channel.filters + filter))
    }

    fun updateFilter(id: AudioChannelId, filter: AudioFilter) {
        val channel = _mix.value.channel(id)
        update(
            channel.copy(
                filters = channel.filters.map { if (it.id == filter.id) filter else it },
            ),
        )
    }

    fun removeFilter(id: AudioChannelId, filterId: String) {
        val channel = _mix.value.channel(id)
        update(channel.copy(filters = channel.filters.filterNot { it.id == filterId }))
    }

    fun moveFilter(id: AudioChannelId, filterId: String, delta: Int) {
        val channel = _mix.value.channel(id)
        val index = channel.filters.indexOfFirst { it.id == filterId }
        if (index < 0) return
        val target = (index + delta).coerceIn(0, channel.filters.lastIndex)
        if (target == index) return
        val filters = channel.filters.toMutableList()
        val item = filters.removeAt(index)
        filters.add(target, item)
        update(channel.copy(filters = filters))
    }

    fun copyFilters(id: AudioChannelId) {
        filterClipboard = _mix.value.channel(id).filters.toList()
    }

    fun pasteFilters(id: AudioChannelId) {
        if (filterClipboard.isEmpty()) return
        val channel = _mix.value.channel(id)
        update(
            channel.copy(
                filters = channel.filters + filterClipboard.map {
                    it.copy(id = java.util.UUID.randomUUID().toString())
                },
            ),
        )
    }

    fun hasFilterClipboard(): Boolean = filterClipboard.isNotEmpty()

    /** Dipanggil thread audio, jadi sengaja bebas alokasi UI. */
    fun snapshot(): MixSnapshot = _mix.value.snapshot(pushToMuteActive, pushToTalkActive)

    fun reportLevels(levels: AudioLevels) {
        _levels.value = AudioLevels(
            channels = levels.channels.mapValues { (_, level) ->
                level.copy(
                    leftDb = level.leftDb.coerceIn(FaderScale.MIN_DB, FaderScale.MAX_DB),
                    rightDb = level.rightDb.coerceIn(FaderScale.MIN_DB, FaderScale.MAX_DB),
                    peakLeftDb = level.peakLeftDb.coerceIn(FaderScale.MIN_DB, FaderScale.MAX_DB),
                    peakRightDb = level.peakRightDb.coerceIn(FaderScale.MIN_DB, FaderScale.MAX_DB),
                )
            },
        )
    }

    fun setRuntime(runtime: AudioRuntime) {
        _runtime.value = runtime
    }

    fun clearRuntime() {
        _runtime.value = AudioRuntime()
        _levels.value = AudioLevels()
    }    private inline fun mutate(block: (AudioMix) -> AudioMix) {
        _mix.value = block(_mix.value)
        scheduleSave()
    }

    /**
     * Fader mengirim perubahan puluhan kali per detik saat digeser. Tulis
     * preferensi ditunda sebentar supaya tidak membanjiri disk.
     */
    private fun scheduleSave() {
        saveHandler.removeCallbacks(saveTask)
        saveHandler.postDelayed(saveTask, SAVE_DELAY_MS)
    }

    private const val SAVE_DELAY_MS = 400L
}
