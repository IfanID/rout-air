package com.rout.air.encode.benchmark

import android.media.MediaFormat
import com.rout.air.encode.mux.MediaSink

internal object NullSink : MediaSink {
    override fun addVideoTrack(format: MediaFormat): Int = 0

    override fun addAudioTrack(key: Int, format: MediaFormat): Int = 0

    override fun write(trackId: Int, data: ByteArray, presentationTimeUs: Long, flags: Int) = Unit

    override fun finish(): Boolean = true
}
