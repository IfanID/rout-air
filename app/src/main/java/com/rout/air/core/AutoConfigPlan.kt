package com.rout.air.core

import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Aturan setup otomatis, diturunkan dari Auto-Configuration Wizard OBS
 * (`frontend/wizards/AutoConfigTestPage.cpp`).
 *
 * Semua perhitungan di sini murni dan bisa diuji tanpa perangkat:
 * tangga kandidat, kriteria lulus, dan rumus bitrate OBS.
 */
object AutoConfigPlan {

    enum class Target { Streaming, Recording }

    /** Preferensi fps seperti halaman Video di wizard OBS. */
    enum class FpsPreference(val label: String) {
        PreferHighFps("Utamakan fps"),
        PreferHighRes("Utamakan resolusi"),
        Force30("Paksa 30 fps"),
        Force60("Paksa 60 fps"),
    }

    data class Candidate(val width: Int, val height: Int, val fps: Int) {
        val label get() = "${height}p$fps"
    }

    /** Hasil satu kandidat uji. */
    data class Probe(
        val candidate: Candidate,
        val achievedFps: Double,
        val frames: Int,
        val lateFrames: Int,
    ) {
        /** Lulus bila fps ≥ 90% target dan frame telat ≤ 5%. */
        fun passes(): Boolean =
            achievedFps >= candidate.fps * 0.9 &&
                frames > 0 &&
                lateFrames <= frames / 20
    }

    data class Recommendation(
        val width: Int,
        val height: Int,
        val fps: Int,
        val codec: VideoCodec,
        val bitrateKbps: Int,
        val reason: String,
    )

    /**
     * Semua kandidat yang masih masuk akal untuk jumlah inti ini, tanpa
     * memperhatikan preferensi. Batas atas mengikuti `maxDataRate` OBS:
     * 1440p hanya untuk perangkat di atas 8 inti logis.
     */
    private fun allCandidates(cores: Int): List<Candidate> = when {
        cores > 8 -> listOf(
            Candidate(2560, 1440, 60),
            Candidate(2560, 1440, 30),
            Candidate(1920, 1080, 60),
            Candidate(1920, 1080, 30),
            Candidate(1280, 720, 60),
            Candidate(1280, 720, 30),
            Candidate(854, 480, 30),
        )

        cores >= 6 -> listOf(
            Candidate(1920, 1080, 60),
            Candidate(1920, 1080, 30),
            Candidate(1280, 720, 60),
            Candidate(1280, 720, 30),
            Candidate(854, 480, 30),
        )

        cores >= 4 -> listOf(
            Candidate(1920, 1080, 30),
            Candidate(1280, 720, 60),
            Candidate(1280, 720, 30),
            Candidate(854, 480, 30),
        )

        else -> listOf(
            Candidate(1280, 720, 30),
            Candidate(854, 480, 30),
        )
    }

    /**
     * Tangga kandidat sesuai preferensi fps. Uji berhenti pada kandidat
     * pertama yang lulus, jadi urutan di sini menentukan hasilnya.
     */
    fun ladder(
        cores: Int,
        preference: FpsPreference = FpsPreference.PreferHighFps,
    ): List<Candidate> {
        val base = allCandidates(cores)
        return when (preference) {
            FpsPreference.Force30 -> base.filter { it.fps == 30 }
            FpsPreference.Force60 -> base.filter { it.fps == 60 }

            FpsPreference.PreferHighFps -> base.sortedWith(
                compareByDescending<Candidate> { it.fps }
                    .thenByDescending { it.width * it.height },
            )

            FpsPreference.PreferHighRes -> base.sortedWith(
                compareByDescending<Candidate> { it.width * it.height }
                    .thenByDescending { it.fps },
            )
        }
    }

    /** Nilai dasar rumus OBS; satuannya kbps. */
    private fun bitrateValue(width: Int, height: Int, fps: Int): Double =
        (width.toDouble() * height).pow(0.85) * sqrt(fps.toDouble().pow(1.1))

    private val minReference by lazy { bitrateValue(1920, 1080, 60) / 5800.0 }
    private val upperReference by lazy { bitrateValue(1280, 720, 30) / 3000.0 }

    /** Batas bawah bitrate agar gambar tidak pecah (rumus OBS). */
    fun minBitrateKbps(width: Int, height: Int, fps: Int): Int =
        (bitrateValue(width, height, fps) / minReference).toInt()

    /** Batas atas bitrate yang masuk akal untuk ukuran dan fps ini (rumus OBS). */
    fun upperBitrateKbps(width: Int, height: Int, fps: Int): Int =
        (bitrateValue(width, height, fps) / upperReference).toInt()

    /**
     * Rekomendasi bitrate YouTube Live untuk resolusi/fps ini.
     * Null bila kombinasi tidak ada di tabel resmi.
     */
    fun youtubeBitrateKbps(width: Int, height: Int, fps: Int, codec: VideoCodec): Int? {
        // Angka resmi YouTube Live (kbps). H.264 dan HEVC/AV1 dipisah karena
        // codec yang lebih efisien butuh bitrate lebih rendah.
        val h264: Int
        val efficient: Int
        when {
            height >= 2160 && fps >= 50 -> {
                h264 = 50_000; efficient = 35_000
            }
            height >= 2160 -> {
                h264 = 42_000; efficient = 30_000
            }
            height >= 1440 && fps >= 50 -> {
                h264 = 34_000; efficient = 24_000
            }
            height >= 1440 -> {
                h264 = 21_000; efficient = 15_000
            }
            height >= 1080 && fps >= 50 -> {
                h264 = 17_000; efficient = 12_000
            }
            height >= 1080 -> {
                h264 = 14_000; efficient = 10_000
            }
            height >= 720 -> {
                h264 = 8_000; efficient = 6_000
            }
            height >= 360 -> {
                h264 = 4_000; efficient = 3_000
            }
            else -> return null
        }
        return if (codec == VideoCodec.AVC) h264 else efficient
    }

    /**
     * Menyusun rekomendasi akhir.
     *
     * @param uploadKbps hasil tes upload; null bila tidak diuji.
     */
    fun recommend(
        probes: List<Probe>,
        target: Target,
        codec: VideoCodec,
        hardwareEncoder: Boolean,
        uploadKbps: Int? = null,
    ): Recommendation {
        val passed = probes.firstOrNull { it.passes() }
        val best = passed ?: probes.lastOrNull()
            ?: return Recommendation(
                width = 1280,
                height = 720,
                fps = 30,
                codec = codec,
                bitrateKbps = upperBitrateKbps(1280, 720, 30),
                reason = "Tidak ada kandidat yang diuji; memakai 720p30",
            )

        val candidate = best.candidate
        val upper = upperBitrateKbps(candidate.width, candidate.height, candidate.fps)
        var bitrate = when (target) {
            Target.Recording -> if (hardwareEncoder) upper * 114 / 100 else upper
            Target.Streaming -> youtubeBitrateKbps(
                candidate.width,
                candidate.height,
                candidate.fps,
                codec,
            ) ?: upper
        }

        uploadKbps?.let { measured ->
            if (measured > 0) bitrate = minOf(bitrate, measured)
        }
        bitrate = bitrate.coerceAtLeast(minBitrateKbps(candidate.width, candidate.height, candidate.fps))

        val reason = buildString {
            append(if (passed != null) "Lulus uji " else "Turun ke kandidat terakhir ")
            append(candidate.label)
            append(" (")
            append(String.format(java.util.Locale.US, "%.1f", best.achievedFps))
            append(" fps)")
            if (uploadKbps != null && uploadKbps > 0) append(" • dibatasi upload")
        }
        return Recommendation(
            width = candidate.width,
            height = candidate.height,
            fps = candidate.fps,
            codec = codec,
            bitrateKbps = bitrate,
            reason = reason,
        )
    }
}
