package com.rout.air.ui.util

import android.util.DisplayMetrics
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.rout.air.core.model.CanvasOrientation
import com.rout.air.core.model.CaptureConfig
import com.rout.air.core.model.ContentMode
import androidx.compose.ui.res.stringResource
import com.rout.air.R
import kotlin.math.roundToInt
import com.rout.air.core.model.Crop

/**
 * Ukuran kanvas = base resolution ala OBS, sekaligus orientasi video.
 * Auto mengikuti orientasi HP saat rekaman dimulai; Portrait/Landscape
 * mengunci dimensi video tanpa memaksa layar berputar.
 */
internal fun buildCanvasConfig(
    config: CaptureConfig,
    metrics: DisplayMetrics,
    devicePortrait: Boolean,
): CaptureConfig {
    val portrait = when (config.canvasOrientation) {
        CanvasOrientation.Auto -> devicePortrait
        CanvasOrientation.Portrait -> true
        CanvasOrientation.Landscape -> false
    }

    val displayShort = minOf(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(1)
    val displayLong = maxOf(metrics.widthPixels, metrics.heightPixels)
    val shortSide = minOf(config.width, config.height)

    var longSide = (shortSide.toDouble() * displayLong / displayShort).roundToInt()
    if (longSide % 2 != 0) longSide += 1

    return if (portrait) {
        config.copy(width = shortSide, height = longSide)
    } else {
        config.copy(width = longSide, height = shortSide)
    }
}

/** Label mode isi untuk ditampilkan di halaman Siaran/Studio/Setelan. */
@Composable
internal fun contentModeLabel(config: CaptureConfig): String =
    when (config.contentMode) {
        ContentMode.Fit -> stringResource(R.string.common_pas_letterbox)
        ContentMode.Crop -> stringResource(R.string.common_penuh_crop)
        ContentMode.Stretch -> stringResource(R.string.common_peregangan)
    }

/** Orientasi video yang sedang dipakai (bukan orientasi layar). */
@Composable
internal fun orientationLabel(config: CaptureConfig): String = when {
    config.width > config.height -> stringResource(R.string.common_horizontal)
    config.width < config.height -> stringResource(R.string.common_vertikal)
    else -> stringResource(R.string.common_persegi)
}

internal fun formatDuration(milliseconds: Long): String {
    val totalSeconds = milliseconds / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

/** Ukuran berkas untuk statistik rekaman; satuannya mengikuti besar nilai. */
internal fun formatRecordSize(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}
