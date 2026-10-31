package com.rout.air.encode

import com.rout.air.core.AudioFilter
import com.rout.air.core.AudioFilterType
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Rantai filter per kanal, diproses sebelum fader dan mixing.
 *
 * State filter disimpan per id sehingga perubahan parameter tidak memutus
 * suara. Urutan filter mengikuti urutan di dialog Filters, sama seperti OBS.
 */
class ChannelFilterChain(private val sampleRate: Int) {
    private val states = LinkedHashMap<String, Entry>()

    private data class Entry(
        val type: AudioFilterType,
        val state: FilterState,
    )

    fun process(filters: List<AudioFilter>, left: FloatArray, right: FloatArray, frames: Int) {
        val activeIds = filters.mapTo(HashSet()) { it.id }
        states.keys.retainAll(activeIds)

        filters.forEach { filter ->
            var entry = states[filter.id]
            if (entry == null || entry.type != filter.type) {
                entry = Entry(filter.type, FilterState.create(filter.type, sampleRate))
                states[filter.id] = entry
            }
            if (filter.enabled) entry.state.process(filter, left, right, frames)
        }
    }

    private interface FilterState {
        fun process(filter: AudioFilter, left: FloatArray, right: FloatArray, frames: Int)

        companion object {
            fun create(type: AudioFilterType, sampleRate: Int): FilterState = when (type) {
                AudioFilterType.Gain -> GainState()
                AudioFilterType.NoiseGate -> GateState(sampleRate)
                AudioFilterType.Compressor -> CompressorState(sampleRate, limiter = false)
                AudioFilterType.Limiter -> CompressorState(sampleRate, limiter = true)
                AudioFilterType.InvertPolarity -> InvertState()
                AudioFilterType.NoiseSuppression -> NoiseSuppressionState(sampleRate)
                AudioFilterType.Equalizer -> EqualizerState(sampleRate)
            }
        }
    }

    private class GainState : FilterState {
        override fun process(filter: AudioFilter, left: FloatArray, right: FloatArray, frames: Int) {
            val gain = 10f.pow(filter.param("gainDb") / 20f)
            if (gain == 1f) return
            for (i in 0 until frames) {
                left[i] *= gain
                right[i] *= gain
            }
        }
    }

    private class InvertState : FilterState {
        override fun process(filter: AudioFilter, left: FloatArray, right: FloatArray, frames: Int) {
            for (i in 0 until frames) {
                left[i] = -left[i]
                right[i] = -right[i]
            }
        }
    }

    private class GateState(private val sampleRate: Int) : FilterState {
        private val attack = coef(5f, sampleRate)
        private val release = coef(100f, sampleRate)
        private var envelope = 0f
        private var open = false
        private var holdCounter = 0
        private var gain = 0f
        private var lastOpenDb = -26f
        private var lastCloseDb = -32f
        private var lastAttackMs = 5f
        private var lastReleaseMs = 100f
        private var attackCoef = attack
        private var releaseCoef = release

        override fun process(filter: AudioFilter, left: FloatArray, right: FloatArray, frames: Int) {
            val openDb = filter.param("openThresholdDb")
            val closeDb = filter.param("closeThresholdDb")
            val attackMs = filter.param("attackMs").coerceAtLeast(1f)
            val releaseMs = filter.param("releaseMs").coerceAtLeast(10f)
            val holdSamples = (filter.param("holdMs") * sampleRate / 1000f).toInt()

            if (openDb != lastOpenDb || closeDb != lastCloseDb ||
                attackMs != lastAttackMs || releaseMs != lastReleaseMs
            ) {
                lastOpenDb = openDb
                lastCloseDb = closeDb
                lastAttackMs = attackMs
                lastReleaseMs = releaseMs
                attackCoef = coef(attackMs, sampleRate)
                releaseCoef = coef(releaseMs, sampleRate)
            }

            val openLin = 10f.pow(openDb / 20f)
            val closeLin = 10f.pow(closeDb / 20f)

            for (i in 0 until frames) {
                val peak = max(abs(left[i]), abs(right[i]))
                envelope = if (peak > envelope) {
                    attackCoef * envelope + (1f - attackCoef) * peak
                } else {
                    releaseCoef * envelope + (1f - releaseCoef) * peak
                }

                if (!open && envelope >= openLin) {
                    open = true
                    holdCounter = holdSamples
                }
                if (open) {
                    if (envelope < closeLin) {
                        if (holdCounter > 0) holdCounter-- else open = false
                    } else {
                        holdCounter = holdSamples
                    }
                }

                val target = if (open) 1f else 0f
                gain = if (target > gain) {
                    attackCoef * gain + (1f - attackCoef) * target
                } else {
                    releaseCoef * gain + (1f - releaseCoef) * target
                }
                left[i] *= gain
                right[i] *= gain
            }
        }
    }

    private class CompressorState(
        private val sampleRate: Int,
        private val limiter: Boolean,
    ) : FilterState {
        private var envelope = 0f
        private var gain = 1f
        private var attackCoef = coef(6f, sampleRate)
        private var releaseCoef = coef(60f, sampleRate)
        private var lastAttack = 6f
        private var lastRelease = 60f

        override fun process(filter: AudioFilter, left: FloatArray, right: FloatArray, frames: Int) {
            val threshold = filter.param("thresholdDb")
            val ratio = if (limiter) 20f else filter.param("ratio").coerceAtLeast(1f)
            val attackMs = if (limiter) 1f else filter.param("attackMs").coerceAtLeast(1f)
            val releaseMs = filter.param("releaseMs").coerceAtLeast(10f)
            val makeup = if (limiter) 0f else filter.param("outputGainDb")

            if (attackMs != lastAttack || releaseMs != lastRelease) {
                lastAttack = attackMs
                lastRelease = releaseMs
                attackCoef = coef(attackMs, sampleRate)
                releaseCoef = coef(releaseMs, sampleRate)
            }

            val slope = 1f - 1f / ratio
            val makeupLin = 10f.pow(makeup / 20f)

            for (i in 0 until frames) {
                val peak = max(abs(left[i]), abs(right[i]))
                envelope = if (peak > envelope) {
                    attackCoef * envelope + (1f - attackCoef) * peak
                } else {
                    releaseCoef * envelope + (1f - releaseCoef) * peak
                }

                val levelDb = if (envelope <= 1e-7f) -120f else 20f * log10(envelope)
                val target = if (levelDb > threshold) {
                    10f.pow(-(levelDb - threshold) * slope / 20f)
                } else {
                    1f
                }

                gain = if (target < gain) {
                    attackCoef * gain + (1f - attackCoef) * target
                } else {
                    releaseCoef * gain + (1f - releaseCoef) * target
                }

                val applied = gain * makeupLin
                left[i] *= applied
                right[i] *= applied
            }
        }
    }

    /**
     * Penyederhanaan noise suppression: expander lembut yang menurunkan
     * sinyal di bawah ambang. Bukan algoritma spectral seperti OBS, tetapi
     * perilakunya bisa diatur dan berjalan real-time di HP.
     */
    private class NoiseSuppressionState(sampleRate: Int) : FilterState {
        private var envelope = 0f
        private val smoothing = coef(20f, sampleRate)

        override fun process(filter: AudioFilter, left: FloatArray, right: FloatArray, frames: Int) {
            val amount = filter.param("amount").coerceIn(0f, 100f)
            val thresholdDb = -70f + amount * 0.30f
            val threshold = 10f.pow(thresholdDb / 20f)

            for (i in 0 until frames) {
                val peak = max(abs(left[i]), abs(right[i]))
                envelope = smoothing * envelope + (1f - smoothing) * peak
                if (envelope >= threshold) continue

                val ratio = envelope / threshold
                val attenuation = (ratio * ratio).coerceIn(0f, 1f)
                left[i] *= attenuation
                right[i] *= attenuation
            }
        }
    }

    private class EqualizerState(private val sampleRate: Int) : FilterState {
        private val low = Biquad()
        private val mid = Biquad()
        private val high = Biquad()
        private var lastLow = Float.NaN
        private var lastMid = Float.NaN
        private var lastHigh = Float.NaN

        override fun process(filter: AudioFilter, left: FloatArray, right: FloatArray, frames: Int) {
            val lowDb = filter.param("lowDb")
            val midDb = filter.param("midDb")
            val highDb = filter.param("highDb")

            if (lowDb != lastLow) {
                low.setLowShelf(sampleRate, 100f, 0.707f, lowDb)
                lastLow = lowDb
            }
            if (midDb != lastMid) {
                mid.setPeaking(sampleRate, 1000f, 1f, midDb)
                lastMid = midDb
            }
            if (highDb != lastHigh) {
                high.setHighShelf(sampleRate, 6000f, 0.707f, highDb)
                lastHigh = highDb
            }

            for (i in 0 until frames) {
                left[i] = high.process(mid.process(low.process(left[i])))
                right[i] = high.processRight(mid.processRight(low.processRight(right[i])))
            }
        }
    }

    /** Biquad RBJ dengan state terpisah untuk L/R. */
    private class Biquad {
        private var b0 = 1f
        private var b1 = 0f
        private var b2 = 0f
        private var a1 = 0f
        private var a2 = 0f
        private var z1L = 0f
        private var z2L = 0f
        private var z1R = 0f
        private var z2R = 0f

        fun setLowShelf(sampleRate: Int, frequency: Float, q: Float, gainDb: Float) {
            if (gainDb == 0f) {
                normalize(1f, 0f, 0f, 0f, 0f)
                return
            }
            val a = 10f.pow(gainDb / 40f)
            val w0 = 2.0 * PI * frequency / sampleRate
            val cosw = cos(w0).toFloat()
            val sinw = sin(w0).toFloat()
            val alpha = sinw / 2f * sqrt((a + 1f / a) * (1f / q - 1f) + 2f)
            val sqrtA = sqrt(a)
            val b0 = a * ((a + 1f) - (a - 1f) * cosw + 2f * sqrtA * alpha)
            val b1 = 2f * a * ((a - 1f) - (a + 1f) * cosw)
            val b2 = a * ((a + 1f) - (a - 1f) * cosw - 2f * sqrtA * alpha)
            val a0 = (a + 1f) + (a - 1f) * cosw + 2f * sqrtA * alpha
            val a1 = -2f * ((a - 1f) + (a + 1f) * cosw)
            val a2 = (a + 1f) + (a - 1f) * cosw - 2f * sqrtA * alpha
            normalize(b0, b1, b2, a1, a2, a0)
        }

        fun setHighShelf(sampleRate: Int, frequency: Float, q: Float, gainDb: Float) {
            if (gainDb == 0f) {
                normalize(1f, 0f, 0f, 0f, 0f)
                return
            }
            val a = 10f.pow(gainDb / 40f)
            val w0 = 2.0 * PI * frequency / sampleRate
            val cosw = cos(w0).toFloat()
            val sinw = sin(w0).toFloat()
            val alpha = sinw / 2f * sqrt((a + 1f / a) * (1f / q - 1f) + 2f)
            val sqrtA = sqrt(a)
            val b0 = a * ((a + 1f) + (a - 1f) * cosw + 2f * sqrtA * alpha)
            val b1 = -2f * a * ((a - 1f) + (a + 1f) * cosw)
            val b2 = a * ((a + 1f) + (a - 1f) * cosw - 2f * sqrtA * alpha)
            val a0 = (a + 1f) - (a - 1f) * cosw + 2f * sqrtA * alpha
            val a1 = 2f * ((a - 1f) - (a + 1f) * cosw)
            val a2 = (a + 1f) - (a - 1f) * cosw - 2f * sqrtA * alpha
            normalize(b0, b1, b2, a1, a2, a0)
        }

        fun setPeaking(sampleRate: Int, frequency: Float, q: Float, gainDb: Float) {
            if (gainDb == 0f) {
                normalize(1f, 0f, 0f, 0f, 0f)
                return
            }
            val a = 10f.pow(gainDb / 40f)
            val w0 = 2.0 * PI * frequency / sampleRate
            val cosw = cos(w0).toFloat()
            val alpha = sin(w0).toFloat() / (2f * q)
            val b0 = 1f + alpha * a
            val b1 = -2f * cosw
            val b2 = 1f - alpha * a
            val a0 = 1f + alpha / a
            val a1 = -2f * cosw
            val a2 = 1f - alpha / a
            normalize(b0, b1, b2, a1, a2, a0)
        }

        private fun normalize(b0: Float, b1: Float, b2: Float, a1: Float, a2: Float, a0: Float = 1f) {
            val inv = 1f / a0
            this.b0 = b0 * inv
            this.b1 = b1 * inv
            this.b2 = b2 * inv
            this.a1 = a1 * inv
            this.a2 = a2 * inv
        }

        fun process(x: Float): Float {
            val y = b0 * x + z1L
            z1L = b1 * x - a1 * y + z2L
            z2L = b2 * x - a2 * y
            return y
        }

        fun processRight(x: Float): Float {
            val y = b0 * x + z1R
            z1R = b1 * x - a1 * y + z2R
            z2R = b2 * x - a2 * y
            return y
        }
    }

    private companion object {
        fun coef(milliseconds: Float, sampleRate: Int): Float {
            val seconds = (milliseconds / 1000f).coerceAtLeast(0.0001f)
            return exp(-1.0 / (seconds * sampleRate)).toFloat()
        }
    }
}
