package com.rout.air.encode

import android.media.MediaFormat

/**
 * Meneruskan sampel encoder ke beberapa [MediaSink] sekaligus, misalnya MP4
 * dan RTMP. Format yang sudah diterima disimpan supaya sink yang ditambahkan
 * belakangan (live dimulai saat rekaman berjalan) tetap menerima header.
 */
class TeeSink : MediaSink {
    private val lock = Any()
    private val children = LinkedHashSet<MediaSink>()
    private val videoTracks = HashMap<MediaSink, Int>()
    private val audioTracks = HashMap<MediaSink, HashMap<Int, Int>>()

    private var videoFormat: MediaFormat? = null
    private val audioFormats = LinkedHashMap<Int, MediaFormat>()

    val size: Int get() = synchronized(lock) { children.size }

    fun add(child: MediaSink) {        synchronized(lock) {
            if (!children.add(child)) return
            videoFormat?.let { format ->
                videoTracks[child] = child.addVideoTrack(format)
            }
            audioFormats.forEach { (key, format) ->
                audioTracks.getOrPut(child) { HashMap() }[key] = child.addAudioTrack(key, format)
            }
        }
    }

    /** Melepas child dan (opsional) menutupnya; sink lain tetap berjalan. */
    fun remove(child: MediaSink, finish: Boolean = true): Boolean {
        val removed = synchronized(lock) {
            val existed = children.remove(child)
            videoTracks.remove(child)
            audioTracks.remove(child)
            existed
        }
        if (removed && finish) runCatching { child.finish() }
        return removed
    }

    override fun addVideoTrack(format: MediaFormat): Int {
        synchronized(lock) {
            videoFormat = format
            children.forEach { child ->
                videoTracks[child] = child.addVideoTrack(format)
            }
        }
        return VIDEO_ID
    }

    override fun addAudioTrack(key: Int, format: MediaFormat): Int {
        synchronized(lock) {
            audioFormats[key] = format
            children.forEach { child ->
                audioTracks.getOrPut(child) { HashMap() }[key] = child.addAudioTrack(key, format)
            }
        }
        return AUDIO_ID_BASE + key
    }

    override fun write(trackId: Int, data: ByteArray, presentationTimeUs: Long, flags: Int) {
        synchronized(lock) {
            children.forEach { child ->
                val target = when {
                    trackId == VIDEO_ID -> videoTracks[child]
                    trackId >= AUDIO_ID_BASE -> audioTracks[child]?.get(trackId - AUDIO_ID_BASE)
                    else -> null
                } ?: return@forEach
                child.write(target, data, presentationTimeUs, flags)
            }
        }
    }

    override fun finish(): Boolean {
        finishAll()
        return true
    }

    fun finishAll() {
        val snapshot = synchronized(lock) {
            val copy = children.toList()
            children.clear()
            videoTracks.clear()
            audioTracks.clear()
            copy
        }
        snapshot.forEach { runCatching { it.finish() } }
    }

    companion object {
        const val VIDEO_ID = 1
        const val AUDIO_ID_BASE = 100
    }
}
