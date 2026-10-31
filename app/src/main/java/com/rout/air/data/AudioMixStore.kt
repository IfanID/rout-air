package com.rout.air.data

import android.content.Context
import com.rout.air.core.AudioChannel
import com.rout.air.core.AudioChannelId
import com.rout.air.core.AudioFilter
import com.rout.air.core.AudioFilterType
import com.rout.air.core.AudioMix
import com.rout.air.core.FaderScale
import com.rout.air.core.MeterDecay
import com.rout.air.core.MonitorMode
import com.rout.air.core.PeakMeterType
import org.json.JSONArray
import org.json.JSONObject

/**
 * Menyimpan mixer audio di SharedPreferences. Kanal disimpan per id dan
 * filter disimpan sebagai JSON kecil supaya penambahan tipe filter baru tidak
 * merusak pengaturan lama.
 */
class AudioMixStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): AudioMix {
        val keys = buildList {
            add(AudioChannelId.Internal.key)
            add(AudioChannelId.Microphone.key)
            addAll(decodeKeys(prefs.getString(KEY_CHANNEL_KEYS, null)))
        }.distinct()

        val channels = keys.map { key -> loadChannel(AudioChannelId.fromKey(key)) }

        return AudioMix(
            masterEnabled = prefs.getBoolean(KEY_MASTER, true),
            channels = channels,
            verticalLayout = prefs.getBoolean(KEY_VERTICAL, false),
            showHidden = prefs.getBoolean(KEY_SHOW_HIDDEN, false),
            showInactive = prefs.getBoolean(KEY_SHOW_INACTIVE, true),
            keepHiddenLast = prefs.getBoolean(KEY_KEEP_HIDDEN_LAST, true),
            keepInactiveLast = prefs.getBoolean(KEY_KEEP_INACTIVE_LAST, false),
            outputTracks = prefs.getInt(KEY_OUTPUT_TRACKS, 0x01).let {
                if ((it and 0x3F) == 0) 0x01 else it and 0x3F
            },
            meterDecay = enumOf(
                prefs.getString(KEY_METER_DECAY, MeterDecay.Medium.name),
                MeterDecay.Medium,
            ),
            peakMeterType = enumOf(
                prefs.getString(KEY_PEAK_TYPE, PeakMeterType.Sample.name),
                PeakMeterType.Sample,
            ),
            monitoringDeviceId = prefs.getInt(KEY_MONITOR_DEVICE, 0),
            volumePercent = prefs.getBoolean(KEY_VOLUME_PERCENT, false),
            pushToMuteEnabled = prefs.getBoolean(KEY_PUSH_MUTE, false),
            pushToTalkEnabled = prefs.getBoolean(KEY_PUSH_TALK, false),
        )
    }

    fun save(mix: AudioMix) {
        val editor = prefs.edit()
        editor.putString(KEY_CHANNEL_KEYS, mix.channels.map { it.id.key }.joinToString("\u0001"))
        editor.putBoolean(KEY_MASTER, mix.masterEnabled)
        editor.putBoolean(KEY_VERTICAL, mix.verticalLayout)
        editor.putBoolean(KEY_SHOW_HIDDEN, mix.showHidden)
        editor.putBoolean(KEY_SHOW_INACTIVE, mix.showInactive)
        editor.putBoolean(KEY_KEEP_HIDDEN_LAST, mix.keepHiddenLast)
        editor.putBoolean(KEY_KEEP_INACTIVE_LAST, mix.keepInactiveLast)
        editor.putInt(KEY_OUTPUT_TRACKS, mix.safeOutputTracks())
        editor.putString(KEY_METER_DECAY, mix.meterDecay.name)
        editor.putString(KEY_PEAK_TYPE, mix.peakMeterType.name)
        editor.putInt(KEY_MONITOR_DEVICE, mix.monitoringDeviceId)
        editor.putBoolean(KEY_VOLUME_PERCENT, mix.volumePercent)
        editor.putBoolean(KEY_PUSH_MUTE, mix.pushToMuteEnabled)
        editor.putBoolean(KEY_PUSH_TALK, mix.pushToTalkEnabled)

        mix.channels.forEach { channel ->
            editor.putString(key(channel.id, "name"), channel.name)
            editor.putBoolean(key(channel.id, "enabled"), channel.enabled)
            editor.putBoolean(key(channel.id, "muted"), channel.muted)
            editor.putFloat(key(channel.id, "gain"), channel.gainDb)
            editor.putFloat(key(channel.id, "balance"), channel.balance)
            editor.putString(key(channel.id, "monitor"), channel.monitor.name)
            editor.putBoolean(key(channel.id, "pinned"), channel.pinned)
            editor.putBoolean(key(channel.id, "hidden"), channel.hidden)
            editor.putBoolean(key(channel.id, "locked"), channel.locked)
            editor.putInt(key(channel.id, "sync"), channel.syncOffsetMs)
            editor.putBoolean(key(channel.id, "mono"), channel.forceMono)
            editor.putInt(key(channel.id, "tracks"), channel.tracks and 0x3F)
            editor.putInt(key(channel.id, "order"), channel.order)
            editor.putString(key(channel.id, "filters"), encodeFilters(channel.filters))
            editor.putString(key(channel.id, "capture"), channel.capture.name)
            editor.putInt(key(channel.id, "device"), channel.deviceId)
            editor.putString(key(channel.id, "device_name"), channel.deviceName)
            editor.putInt(key(channel.id, "device_type"), channel.deviceType)
            editor.putBoolean(key(channel.id, "sco"), channel.sco)
            editor.putBoolean(key(channel.id, "global"), channel.global)
            channel.sourceId?.let { editor.putString(key(channel.id, "source_id"), it) }
        }
        editor.apply()
    }

    private fun loadChannel(id: AudioChannelId): AudioChannel {
        val fallback = AudioChannel(id)
        return AudioChannel(
            id = id,
            name = prefs.getString(key(id, "name"), fallback.name) ?: fallback.name,
            enabled = prefs.getBoolean(key(id, "enabled"), fallback.enabled),
            muted = prefs.getBoolean(key(id, "muted"), false),
            gainDb = prefs.getFloat(key(id, "gain"), 0f)
                .coerceIn(FaderScale.MIN_DB, FaderScale.MAX_ADV_DB),
            balance = prefs.getFloat(key(id, "balance"), 0f).coerceIn(-1f, 1f),
            monitor = enumOf(
                prefs.getString(key(id, "monitor"), MonitorMode.Off.name),
                MonitorMode.Off,
            ),
            pinned = prefs.getBoolean(key(id, "pinned"), false),
            hidden = prefs.getBoolean(key(id, "hidden"), false),
            locked = prefs.getBoolean(key(id, "locked"), false),
            syncOffsetMs = prefs.getInt(key(id, "sync"), 0).coerceIn(-950, 20_000),
            forceMono = prefs.getBoolean(key(id, "mono"), false),
            tracks = prefs.getInt(key(id, "tracks"), 0x01).let {
                if ((it and 0x3F) == 0) 0x01 else it and 0x3F
            },
            order = prefs.getInt(key(id, "order"), fallback.order),
            filters = decodeFilters(prefs.getString(key(id, "filters"), null)),
            capture = enumOf(
                prefs.getString(key(id, "capture"), fallback.capture.name),
                fallback.capture,
            ),
            deviceId = prefs.getInt(key(id, "device"), 0),
            deviceName = prefs.getString(key(id, "device_name"), null),
            deviceType = prefs.getInt(key(id, "device_type"), 0),
            sco = prefs.getInt(key(id, "device_type"), 0) ==
                android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            global = prefs.getBoolean(key(id, "global"), fallback.global),
            sourceId = prefs.getString(key(id, "source_id"), fallback.sourceId),
        )
    }

    private fun decodeKeys(raw: String?): List<String> =
        raw?.split('\u0001')?.filter { it.isNotBlank() } ?: emptyList()

    private fun key(id: AudioChannelId, field: String): String = "${id.key}_$field"

    private inline fun <reified T : Enum<T>> enumOf(value: String?, fallback: T): T =
        runCatching { enumValueOf<T>(value ?: fallback.name) }.getOrDefault(fallback)

    private fun encodeFilters(filters: List<AudioFilter>): String {
        val array = JSONArray()
        filters.forEach { filter ->
            val params = JSONObject()
            filter.params.forEach { (key, value) -> params.put(key, value.toDouble()) }
            array.put(
                JSONObject().apply {
                    put("id", filter.id)
                    put("type", filter.type.name)
                    put("enabled", filter.enabled)
                    put("params", params)
                },
            )
        }
        return array.toString()
    }

    private fun decodeFilters(raw: String?): List<AudioFilter> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val type = enumValueOf<AudioFilterType>(item.optString("type"))
                    val params = item.optJSONObject("params") ?: JSONObject()
                    val values = buildMap {
                        type.defaults().keys.forEach { key ->
                            put(key, params.optDouble(key, type.defaults()[key]!!.toDouble()).toFloat())
                        }
                    }
                    add(
                        AudioFilter(
                            id = item.optString("id", "filter-$index"),
                            type = type,
                            enabled = item.optBoolean("enabled", true),
                            params = values,
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private companion object {
        const val PREFS_NAME = "rout_air_audio_mix"
        const val KEY_MASTER = "master_enabled"
        const val KEY_CHANNEL_KEYS = "channel_keys"
        const val KEY_VERTICAL = "vertical_layout"
        const val KEY_SHOW_HIDDEN = "show_hidden"
        const val KEY_SHOW_INACTIVE = "show_inactive"
        const val KEY_KEEP_HIDDEN_LAST = "keep_hidden_last"
        const val KEY_KEEP_INACTIVE_LAST = "keep_inactive_last"
        const val KEY_OUTPUT_TRACKS = "output_tracks"
        const val KEY_METER_DECAY = "meter_decay"
        const val KEY_PEAK_TYPE = "peak_type"
        const val KEY_MONITOR_DEVICE = "monitor_device"
        const val KEY_VOLUME_PERCENT = "volume_percent"
        const val KEY_PUSH_MUTE = "push_to_mute"
        const val KEY_PUSH_TALK = "push_to_talk"
    }
}
