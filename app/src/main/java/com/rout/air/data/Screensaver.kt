package com.rout.air.data

import com.rout.air.core.Align
import com.rout.air.core.Scene
import com.rout.air.core.SceneCollection
import com.rout.air.core.SceneItem
import com.rout.air.core.SceneSource
import com.rout.air.core.SourceSettings
import com.rout.air.core.SourceType
import com.rout.air.core.Transform

/** Nama scene bawaan layar jeda; dipakai untuk menemukannya kembali. */
const val SCREENSAVER_SCENE_NAME = "Layar Jeda"

private const val TITLE_TEXT = "RoutAir"
private const val SUBTITLE_TEXT = "Segera kembali"
private const val TITLE_SIZE = 96f

/** Huruf R (0) dan A (4) pada "RoutAir" berwarna cyan. */
private val HIGHLIGHT_INDICES = listOf(0, 4)
private const val SUBTITLE_SIZE = 44f

/** Scene layar jeda beserta sumber-sumbernya. */
internal data class ScreensaverScene(val scene: Scene, val sources: List<SceneSource>)

/**
 * Membentuk layar jeda bawaan: latar hitam, judul "RoutAir", dan subteks
 * di bawahnya.
 *
 * `measure` dipakai agar tinggi kotak teks sama dengan hasil render, supaya
 * teks tidak terpotong saat digambar.
 */
internal fun buildScreensaverScene(
    width: Int,
    height: Int,
    measure: (text: String, size: Float, maxWidth: Int) -> Int,
    name: String = SCREENSAVER_SCENE_NAME,
): ScreensaverScene {
    val safeWidth = width.coerceAtLeast(2)
    val safeHeight = height.coerceAtLeast(2)

    val background = SceneSource(
        type = SourceType.Color,
        name = "Latar Jeda",
        settings = SourceSettings(
            color = 0xFF000000,
            width = safeWidth,
            height = safeHeight,
        ),
    )
    val title = SceneSource(
        type = SourceType.Text,
        name = "Judul Jeda",
        settings = SourceSettings(
            text = TITLE_TEXT,
            textSize = TITLE_SIZE,
            textColor = 0xFFFFFFFF,
            // Huruf R dan A pada RoutAir diberi warna cyan.
            textHighlightIndices = HIGHLIGHT_INDICES,
            textHighlightColor = 0xFF00E5FF,
            textCenter = true,
            textAutoFit = true,
            width = safeWidth,
            height = measure(TITLE_TEXT, TITLE_SIZE, safeWidth).coerceAtLeast(1),
        ),
    )
    val subtitle = SceneSource(
        type = SourceType.Text,
        name = "Subteks Jeda",
        settings = SourceSettings(
            text = SUBTITLE_TEXT,
            textSize = SUBTITLE_SIZE,
            textColor = 0xFFB8C2CC,
            textCenter = true,
            textAutoFit = true,
            width = safeWidth,
            height = measure(SUBTITLE_TEXT, SUBTITLE_SIZE, safeWidth).coerceAtLeast(1),
        ),
    )

    val scene = Scene(
        name = name,
        items = listOf(
            SceneItem(sourceId = background.id, z = 0, locked = true),
            SceneItem(
                sourceId = title.id,
                z = 1,
                transform = Transform(
                    x = safeWidth / 2f,
                    y = safeHeight / 2f,
                    align = Align.Center,
                ),
            ),
            SceneItem(
                sourceId = subtitle.id,
                z = 2,
                transform = Transform(
                    x = safeWidth / 2f,
                    y = safeHeight / 2f + safeHeight * 0.09f,
                    align = Align.Center,
                ),
            ),
        ),
    )
    return ScreensaverScene(scene, listOf(background, title, subtitle))
}

/** True bila namanya menandai scene layar jeda. */
fun Scene.isScreensaverScene(): Boolean = name.startsWith(SCREENSAVER_SCENE_NAME)

/** Scene layar jeda yang tersedia, untuk panel overlay. */
fun SceneCollection.screensaverScenes(): List<Scene> =
    scenes.filter { it.isScreensaverScene() }

/**
 * Menyesuaikan scene layar jeda dengan ukuran kanvas sebenarnya.
 *
 * Scene dibuat sebelum orientasi perangkat diketahui, jadi posisi teks bisa
 * meleset. Fungsi ini hanya menyentuh sumber bawaan ("Latar Jeda", "Judul
 * Jeda", "Subteks Jeda") supaya gambar atau teks tambahan pengguna tetap.
 */
fun SceneCollection.upgradeScreensaverScenes(
    width: Int,
    height: Int,
    measure: (text: String, size: Float, maxWidth: Int) -> Int,
): SceneCollection {
    val safeWidth = width.coerceAtLeast(2)
    val safeHeight = height.coerceAtLeast(2)
    val jedaScenes = scenes.filter { it.isScreensaverScene() }
    if (jedaScenes.isEmpty()) return this
    val usedIds = jedaScenes.flatMap { scene -> scene.items.map { it.sourceId } }.toSet()

    fun resetSource(source: SceneSource): SceneSource {
        if (source.id !in usedIds) return source
        return when {
            source.type == SourceType.Text && source.settings.text == TITLE_TEXT ->
                source.copy(
                    settings = source.settings.copy(
                        textSize = TITLE_SIZE,
                        textColor = 0xFFFFFFFF,
                        textHighlightIndices = HIGHLIGHT_INDICES,
                        textHighlightColor = 0xFF00E5FF,
                        textCenter = true,
                        textAutoFit = true,
                        width = safeWidth,
                        height = measure(TITLE_TEXT, TITLE_SIZE, safeWidth).coerceAtLeast(1),
                    ),
                )

            source.type == SourceType.Text && source.settings.text == SUBTITLE_TEXT ->
                source.copy(
                    settings = source.settings.copy(
                        textSize = SUBTITLE_SIZE,
                        textColor = 0xFFB8C2CC,
                        textHighlightIndices = emptyList(),
                        textCenter = true,
                        textAutoFit = true,
                        width = safeWidth,
                        height = measure(SUBTITLE_TEXT, SUBTITLE_SIZE, safeWidth)
                            .coerceAtLeast(1),
                    ),
                )

            source.type == SourceType.Color && source.name == "Latar Jeda" ->
                source.copy(settings = source.settings.copy(width = safeWidth, height = safeHeight))

            else -> source
        }
    }

    fun placeItem(item: SceneItem): SceneItem {
        val source = sources.firstOrNull { it.id == item.sourceId } ?: return item
        return when {
            source.type == SourceType.Color && source.name == "Latar Jeda" ->
                item.copy(transform = Transform())

            source.settings.text == TITLE_TEXT ->
                item.copy(
                    transform = Transform(
                        x = safeWidth / 2f,
                        y = safeHeight / 2f,
                        align = Align.Center,
                    ),
                )

            source.settings.text == SUBTITLE_TEXT ->
                item.copy(
                    transform = Transform(
                        x = safeWidth / 2f,
                        y = safeHeight / 2f + safeHeight * 0.09f,
                        align = Align.Center,
                    ),
                )

            else -> item
        }
    }

    return copy(
        sources = sources.map(::resetSource),
        scenes = scenes.map { scene ->
            if (!scene.isScreensaverScene()) return@map scene
            scene.copy(items = scene.items.map(::placeItem))
        },
    )
}

/** Nama unik berikutnya: "Layar Jeda", "Layar Jeda 2", dan seterusnya. */
fun SceneCollection.uniqueScreensaverName(): String {
    if (scenes.none { it.name == SCREENSAVER_SCENE_NAME }) return SCREENSAVER_SCENE_NAME
    var index = 2
    while (scenes.any { it.name == "$SCREENSAVER_SCENE_NAME $index" }) index++
    return "$SCREENSAVER_SCENE_NAME $index"
}

/** Menambah scene layar jeda baru dan menjadikannya scene aktif. */
fun SceneCollection.addScreensaverScene(
    width: Int,
    height: Int,
    measure: (text: String, size: Float, maxWidth: Int) -> Int,
): Pair<SceneCollection, String> {
    val built = buildScreensaverScene(width, height, measure, uniqueScreensaverName())
    val next = copy(
        scenes = scenes + built.scene,
        sources = sources + built.sources,
        activeSceneId = built.scene.id,
    )
    return next to built.scene.id
}

/**
 * Memastikan collection punya scene layar jeda. Bila sudah ada, scene itu
 * yang dipakai supaya perubahan pengguna tidak tertimpa.
 */
fun SceneCollection.withScreensaverScene(
    width: Int,
    height: Int,
    measure: (text: String, size: Float, maxWidth: Int) -> Int,
): Pair<SceneCollection, String> {
    scenes.firstOrNull { it.name == SCREENSAVER_SCENE_NAME }?.let { return this to it.id }
    val built = buildScreensaverScene(width, height, measure)
    val next = copy(
        scenes = scenes + built.scene,
        sources = sources + built.sources,
    )
    return next to built.scene.id
}
