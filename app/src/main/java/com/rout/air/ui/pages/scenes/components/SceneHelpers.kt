package com.rout.air.ui.pages.scenes.components

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.rout.air.R
import com.rout.air.core.model.Align
import com.rout.air.core.model.CaptureConfig
import com.rout.air.core.model.SceneCollection
import com.rout.air.core.model.SceneSource
import com.rout.air.core.model.SourceType
import com.rout.air.core.model.Transform
import com.rout.air.data.scene.ObsImport
import kotlin.math.max
import kotlin.math.min
import androidx.compose.ui.res.stringResource

internal fun uniqueSourceName(collection: SceneCollection, base: String): String {
    if (collection.sources.none { it.name == base }) return base
    var index = 2
    while (collection.sources.any { it.name == "$base $index" }) index++
    return "$base $index"
}

/** Skala awal agar sumber baru pas dan terpusat di kanvas. */
internal fun fitTransform(width: Int, height: Int, canvas: CaptureConfig): Transform {
    if (width <= 0 || height <= 0) return Transform()
    val scale = min(
        canvas.width.toFloat() / width,
        canvas.height.toFloat() / height,
    ) * 0.8f
    return Transform(
        x = canvas.width / 2f,
        y = canvas.height / 2f,
        scaleX = max(0.05f, scale),
        scaleY = max(0.05f, scale),
        align = Align.Center,
    )
}

/** Posisi awal saat transformasi direset, mengikuti tipe sumber. */
internal fun defaultTransform(source: SceneSource, canvas: CaptureConfig): Transform =
    when (source.type) {
        // Layar memenuhi kanvas penuh.
        SourceType.Screen -> Transform()
        SourceType.Text -> Transform(
            x = canvas.width / 2f,
            y = canvas.height * 0.8f,
            align = Align.Center,
        )

        else -> fitTransform(source.settings.width, source.settings.height, canvas)
    }

/** Pesan kesalahan impor OBS. */
@Composable
internal fun obsErrorText(error: ObsImport.ParseError): String = stringResource(
    when (error) {
        ObsImport.ParseError.NOT_JSON -> R.string.obs_error_not_json
        ObsImport.ParseError.NO_SOURCES -> R.string.obs_error_no_sources
        ObsImport.ParseError.NO_SCENES -> R.string.obs_error_no_scenes
        ObsImport.ParseError.UNREADABLE -> R.string.scenes_import_unreadable
    },
)

/** Satu baris daftar sumber yang dilewati saat impor OBS. */
@Composable
internal fun obsSkippedText(item: ObsImport.Skipped): String {
    val id = item.obsId
        ?: return stringResource(R.string.obs_skipped_not_found, item.name)
    return stringResource(R.string.obs_skipped_unsupported, item.name, obsKindLabel(id))
}

/** Jenis sumber OBS dalam bahasa aplikasi. */
@Composable
internal fun obsKindLabel(obsId: String): String = stringResource(
    when {
        obsId.startsWith("dshow") || obsId.startsWith("v4l2") ||
            obsId.startsWith("av_capture") -> R.string.obs_kind_camera
        obsId.startsWith("browser") -> R.string.obs_kind_browser
        obsId == "group" -> R.string.obs_kind_group
        obsId.contains("media") || obsId.startsWith("ffmpeg") -> R.string.obs_kind_media
        obsId.startsWith("ndi") -> R.string.obs_kind_ndi
        else -> R.string.obs_kind_unknown
    },
)

/** Catatan hasil impor OBS. */
@Composable
internal fun obsNoteText(note: ObsImport.Note): String = when (note.kind) {
    ObsImport.NoteKind.SCREEN_FALLBACK ->
        stringResource(R.string.obs_note_screen_fallback, note.name)
    ObsImport.NoteKind.IMAGE_REPICK ->
        stringResource(R.string.obs_note_image_repick, note.name)
}

@Composable
internal fun typeLabel(type: SourceType): String = when (type) {
    SourceType.Screen -> stringResource(R.string.scenes_tipe_screen)
    SourceType.Image -> stringResource(R.string.scenes_tipe_image)
    SourceType.Text -> stringResource(R.string.scenes_tipe_text)
    SourceType.Color -> stringResource(R.string.scenes_tipe_color)
    SourceType.AudioInput -> stringResource(R.string.scenes_tipe_audio_input)
    SourceType.AudioOutput -> stringResource(R.string.scenes_tipe_audio_output)
    SourceType.Camera -> stringResource(R.string.scenes_tipe_camera)
    SourceType.Media -> stringResource(R.string.scenes_tipe_media)
    SourceType.Browser -> stringResource(R.string.scenes_tipe_browser)
    SourceType.Group -> stringResource(R.string.scenes_tipe_grup)
    SourceType.SlideShow -> stringResource(R.string.scenes_tipe_slideshow)
}

internal fun typeSymbol(type: SourceType): String = when (type) {
    SourceType.Screen -> "▣"
    SourceType.Image -> "▤"
    SourceType.Text -> "T"
    SourceType.Color -> "■"
    SourceType.AudioInput -> "◉"
    SourceType.AudioOutput -> "◎"
    SourceType.Camera -> "◨"
    SourceType.Media -> "▶"
    SourceType.Browser -> "◍"
    SourceType.Group -> "▥"
    SourceType.SlideShow -> "▤"
}

@Composable
internal fun intrinsicLabel(source: SceneSource): String = when (source.type) {
    SourceType.Screen -> stringResource(R.string.scenes_intrinsic_screen)
    SourceType.AudioInput, SourceType.AudioOutput -> stringResource(R.string.scenes_intrinsic_audio)
    else -> "${source.settings.width}x${source.settings.height}"
}

internal fun alignSymbol(align: Align): String = when (align) {
    Align.TopLeft -> "↖"
    Align.Top -> "↑"
    Align.TopRight -> "↗"
    Align.Left -> "←"
    Align.Center -> "•"
    Align.Right -> "→"
    Align.BottomLeft -> "↙"
    Align.Bottom -> "↓"
    Align.BottomRight -> "↘"
}

/** Fraksi posisi jangkar pada kanvas (0 = kiri/atas, 0.5 = tengah, 1 = kanan/bawah). */
internal fun alignFraction(align: Align): Pair<Float, Float> {
    val x = when (align) {
        Align.Top, Align.Center, Align.Bottom -> 0.5f
        Align.TopRight, Align.Right, Align.BottomRight -> 1f
        else -> 0f
    }
    val y = when (align) {
        Align.Left, Align.Center, Align.Right -> 0.5f
        Align.BottomLeft, Align.Bottom, Align.BottomRight -> 1f
        else -> 0f
    }
    return x to y
}

internal val PRESET_COLORS = listOf(
    0xFFFFFFFF,
    0xFF000000,
    0xFF00E5FF,
    0xFFE53935,
    0xFF43A047,
    0xFF1E88E5,
    0xFFFFB300,
    0xFFD81B60,
    0xFFFF6D00,
    0xFF9E9E9E,
)
