package com.rout.air.encode

/**
 * Penunda/penyela audio per kanal untuk Sync Offset.
 *
 * Offset positif menunda audio (buffer melingkar), offset negatif membuang
 * sampel di awal supaya audio terdengar lebih awal. Buffer dialokasikan
 * sesuai offset yang dipakai agar tidak memakan memori 20 detik terus-menerus.
 */
class SyncAligner(private val sampleRate: Int) {
    private var bufferL = FloatArray(0)
    private var bufferR = FloatArray(0)
    private var writePos = 0
    private var currentOffset = Int.MIN_VALUE
    private var skipRemaining = 0
    private var delayFrames = 0

    fun process(
        left: FloatArray,
        right: FloatArray,
        frames: Int,
        offsetMs: Int,
        outLeft: FloatArray,
        outRight: FloatArray,
    ) {
        val offset = offsetMs.coerceIn(-950, 20_000)
        if (offset != currentOffset) {
            currentOffset = offset
            delayFrames = (offset.coerceAtLeast(0) * sampleRate / 1000f).toInt()
            skipRemaining = (-offset.coerceAtMost(0) * sampleRate / 1000f).toInt()
            ensureCapacity(delayFrames + frames * 2 + 4)
            bufferL.fill(0f)
            bufferR.fill(0f)
            writePos = 0
        }

        if (delayFrames <= 0) {
            // Tanpa delay: salin langsung, sambil membuang sampel untuk offset
            // negatif (audio maju).
            val drop = minOf(skipRemaining, frames)
            skipRemaining -= drop
            for (i in 0 until frames) {
                val source = i + drop
                outLeft[i] = if (source < frames) left[source] else 0f
                outRight[i] = if (source < frames) right[source] else 0f
            }
            return
        }

        ensureCapacity(delayFrames + frames * 2 + 4)
        val capacity = bufferL.size

        // Tulis blok saat ini, lalu baca dari posisi mundur sebesar delay.
        for (i in 0 until frames) {
            val index = (writePos + i) % capacity
            bufferL[index] = left[i]
            bufferR[index] = right[i]
        }

        for (i in 0 until frames) {
            var index = (writePos + i - delayFrames) % capacity
            if (index < 0) index += capacity
            outLeft[i] = bufferL[index]
            outRight[i] = bufferR[index]
        }

        writePos = (writePos + frames) % capacity
    }

    fun reset() {
        bufferL.fill(0f)
        bufferR.fill(0f)
        writePos = 0
        skipRemaining = 0
    }

    private fun ensureCapacity(required: Int) {
        if (bufferL.size >= required) return
        var size = maxOf(2048, bufferL.size.coerceAtLeast(1))
        while (size < required) size *= 2
        val newL = FloatArray(size)
        val newR = FloatArray(size)
        // Salin isi lama secara sederhana; offset berubah jarang sehingga
        // kehilangan beberapa milidetik audio tidak terdengar.
        if (bufferL.isNotEmpty()) {
            val copy = minOf(bufferL.size, size)
            System.arraycopy(bufferL, 0, newL, 0, copy)
            System.arraycopy(bufferR, 0, newR, 0, copy)
        }
        bufferL = newL
        bufferR = newR
        writePos %= size
    }
}
