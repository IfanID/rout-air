package com.rout.air.data.scene

import com.rout.air.core.model.Align
import com.rout.air.core.model.BlendMode
import com.rout.air.core.model.Crop
import com.rout.air.core.model.Scene
import com.rout.air.core.model.SceneCollection
import com.rout.air.core.model.SceneItem
import com.rout.air.core.model.SceneSource
import com.rout.air.core.model.SourceSettings
import com.rout.air.core.model.SourceType
import com.rout.air.core.model.Transform
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pembaca scene-collection OBS (`scene-collection.json`) untuk M7.
 *
 * Yang dipetakan: tangkap layar (monitor/display/game/window), gambar, teks
 * GDI+, warna, serta audio input/output. Sumber yang tidak punya padanan di
 * Android (kamera, browser, grup, media) dilewati dan dilaporkan ke pengguna.
 *
 * Gambar OBS menyimpan path PC yang tidak bisa dibaca Android, jadi sumbernya
 * tetap dibuat dengan ukuran asli namun perlu dipilih ulang gambarnya.
 */
object ObsImport {
    data class Report(
        val collection: SceneCollection?,
        val scenes: Int = 0,
        val sources: Int = 0,
        val skipped: List<Skipped> = emptyList(),
        val notes: List<Note> = emptyList(),
        val error: ParseError? = null,
    )

    /** Kesalahan yang bisa dialihbahasakan di UI. */
    enum class ParseError { NOT_JSON, NO_SOURCES, NO_SCENES, UNREADABLE }

    /** Sumber yang dilewati; [obsId] null berarti definisinya tidak ditemukan. */
    data class Skipped(val name: String, val obsId: String? = null)

    /** Catatan hasil impor; teksnya disusun di UI. */
    data class Note(val name: String, val kind: NoteKind)

    enum class NoteKind { SCREEN_FALLBACK, IMAGE_REPICK }

    /** Id OBS yang dianggap scene; group sengaja tidak didukung. */
    private val SCENE_IDS = setOf("scene")

    private val SCREEN_IDS = setOf(
        "monitor_capture",
        "display_capture",
        "screen_capture",
        "game_capture",
        "window_capture",
    )

    private val TEXT_IDS = setOf(
        "text_gdiplus",
        "text_gdiplus_v2",
        "text_gdiplus_v3",
        "text_ft2_source",
        "text_ft2_source_v2",
    )

    private val COLOR_IDS = setOf("color_source", "color_source_v3")

    private val IMAGE_IDS = setOf("image_source")

    private val BROWSER_IDS = setOf("browser_source")

    private val CAMERA_IDS = setOf(
        "dshow_input",
        "v4l2_input",
        "av_capture_input",
    )

    private val AUDIO_INPUT_IDS = setOf(
        "wasapi_input_capture",
        "wasapi_input_capture_v2",
        "pulse_input_capture",
        "coreaudio_input_capture",
        "audio_input_capture",
    )

    private val AUDIO_OUTPUT_IDS = setOf(
        "wasapi_output_capture",
        "pulse_output_capture",
        "coreaudio_output_capture",
        "desktop_audio_capture",
        "audio_output_capture",
    )

    /** Sumber yang dikenal tetapi belum punya padanan di aplikasi. */
    private val UNSUPPORTED_IDS = setOf(
        "group",
        "media_source",
        "ffmpeg_source",
        "ndi_source",
    )

    fun parse(text: String, canvasWidth: Int, canvasHeight: Int): Report {
        val root = runCatching { JSONObject(text) }.getOrNull()
            ?: return Report(collection = null, error = ParseError.NOT_JSON)
        val entries = root.optJSONArray("sources")
            ?: return Report(collection = null, error = ParseError.NO_SOURCES)

        // Dua tahap: kumpulkan sumber dulu supaya satu sumber bisa dipakai
        // beberapa scene tanpa terduplikasi.
        val defs = (0 until entries.length())
            .mapNotNull { entries.optJSONObject(it) }
            .associateBy { it.optString("name") }

        val skipped = LinkedHashSet<Skipped>()
        val notes = LinkedHashSet<Note>()
        val sources = mutableListOf<SceneSource>()
        val sourceIdByName = HashMap<String, String>()

        fun sourceFor(def: JSONObject): SceneSource? {
            val name = def.optString("name").ifBlank { return null }
            sourceIdByName[name]?.let { id -> return sources.firstOrNull { it.id == id } }
            val obsId = def.optString("id").ifBlank { def.optString("versioned_id") }
            if (obsId in UNSUPPORTED_IDS && obsId != "scene") {
                skipped += Skipped(name, obsId)
                return null
            }
            val type = when {
                obsId in SCREEN_IDS -> SourceType.Screen
                obsId in IMAGE_IDS -> SourceType.Image
                obsId in TEXT_IDS -> SourceType.Text
                obsId in COLOR_IDS -> SourceType.Color
                obsId in CAMERA_IDS -> SourceType.Camera
                obsId in BROWSER_IDS -> SourceType.Browser
                obsId in AUDIO_INPUT_IDS -> SourceType.AudioInput
                obsId in AUDIO_OUTPUT_IDS -> SourceType.AudioOutput
                else -> {
                    skipped += Skipped(name, obsId)
                    return null
                }
            }
            if (obsId == "game_capture" || obsId == "window_capture") {
                notes += Note(name, NoteKind.SCREEN_FALLBACK)
            }
            val settings = def.optJSONObject("settings") ?: JSONObject()
            val obsWidth = def.optInt("width", 0).takeIf { it > 0 }
                ?: settings.optInt("width", 0)
            val obsHeight = def.optInt("height", 0).takeIf { it > 0 }
                ?: settings.optInt("height", 0)
            val sourceSettings = when (type) {
                SourceType.Screen -> SourceSettings()
                SourceType.Image -> {
                    notes += Note(name, NoteKind.IMAGE_REPICK)
                    SourceSettings(
                        width = obsWidth.coerceAtLeast(canvasWidth),
                        height = obsHeight.coerceAtLeast(canvasHeight),
                    )
                }

                SourceType.Text -> SourceSettings(
                    text = settings.optString("text").ifBlank { name },
                    textSize = settings.optJSONObject("font")
                        ?.optDouble("size", 48.0)?.toFloat() ?: 48f,
                    width = obsWidth.takeIf { it > 0 } ?: canvasWidth,
                    height = obsHeight.takeIf { it > 0 } ?: canvasHeight,
                )

                SourceType.Color -> SourceSettings(
                    color = obsColor(settings.optLong("color", 0xFF00E5FF)),
                    width = obsWidth.takeIf { it > 0 } ?: canvasWidth,
                    height = obsHeight.takeIf { it > 0 } ?: canvasHeight,
                )

                SourceType.Camera -> SourceSettings(width = 1280, height = 720)
                SourceType.Media -> SourceSettings()
                SourceType.Browser -> SourceSettings(browserUrl = settings.optString("url").takeIf { it.isNotBlank() })
                SourceType.Group -> SourceSettings()
                SourceType.SlideShow -> SourceSettings()
                SourceType.AudioInput, SourceType.AudioOutput -> SourceSettings()
            }
            val source = SceneSource(type = type, name = name, settings = sourceSettings)
            sources += source
            sourceIdByName[name] = source.id
            return source
        }

        val scenes = mutableListOf<Scene>()
        for (index in 0 until entries.length()) {
            val def = entries.optJSONObject(index) ?: continue
            val obsId = def.optString("id").ifBlank { def.optString("versioned_id") }
            if (obsId !in SCENE_IDS) continue
            val sceneName = def.optString("name").ifBlank { "Adegan ${scenes.size + 1}" }
            val items = mutableListOf<SceneItem>()
            val list = def.optJSONObject("settings")?.optJSONArray("items") ?: JSONArray()
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                val sourceName = item.optString("name")
                val sourceDef = defs[sourceName]
                if (sourceDef == null) {
                    skipped += Skipped(sourceName)
                    continue
                }
                val source = sourceFor(sourceDef) ?: continue
                items += itemFor(item, source, canvasWidth, canvasHeight)
            }
            scenes += Scene(name = sceneName, items = items)
        }

        if (scenes.isEmpty()) {
            return Report(
                collection = null,
                error = ParseError.NO_SCENES,
                skipped = skipped.toList(),
            )
        }
        val activeName = root.optString("current_scene")
        val activeId = scenes.firstOrNull { it.name == activeName }?.id ?: scenes.first().id
        val collection = SceneCollection(
            activeSceneId = activeId,
            scenes = scenes,
            sources = sources,
        )
        return Report(
            collection = collection,
            scenes = scenes.size,
            sources = sources.size,
            skipped = skipped.toList(),
            notes = notes.toList(),
        )
    }

    /**
     * Menggabungkan hasil impor ke koleksi yang sedang dipakai. Nama scene
     * yang bentrok diberi akhiran "(OBS)" supaya scene lama tidak tertimpa.
     */
    fun merge(existing: SceneCollection, imported: SceneCollection): SceneCollection {
        val usedNames = existing.scenes.map { it.name }.toMutableSet()
        val newScenes = imported.scenes.map { scene ->
            var name = scene.name
            if (name in usedNames) name = "$name (OBS)"
            var counter = 2
            while (name in usedNames) {
                name = "${scene.name} (OBS $counter)"
                counter++
            }
            usedNames += name
            scene.copy(name = name)
        }
        return existing.copy(
            scenes = existing.scenes + newScenes,
            sources = existing.sources + imported.sources,
        )
    }

    private fun itemFor(
        item: JSONObject,
        source: SceneSource,
        canvasWidth: Int,
        canvasHeight: Int,
    ): SceneItem {
        val pos = item.optJSONObject("pos")
        val scale = item.optJSONObject("scale")
        val align = when (item.optInt("align", 0)) {
            1 -> Align.Top
            2 -> Align.TopRight
            3 -> Align.Left
            4 -> Align.Center
            5 -> Align.Right
            6 -> Align.BottomLeft
            7 -> Align.Bottom
            8 -> Align.BottomRight
            else -> Align.TopLeft
        }
        val width = source.settings.width.takeIf { it > 0 } ?: canvasWidth
        val height = source.settings.height.takeIf { it > 0 } ?: canvasHeight
        val crop = Crop(
            left = (item.optDouble("crop_left", 0.0) / width).toFloat().coerceIn(0f, 0.49f),
            top = (item.optDouble("crop_top", 0.0) / height).toFloat().coerceIn(0f, 0.49f),
            right = (item.optDouble("crop_right", 0.0) / width).toFloat().coerceIn(0f, 0.49f),
            bottom = (item.optDouble("crop_bottom", 0.0) / height).toFloat().coerceIn(0f, 0.49f),
        )
        return SceneItem(
            sourceId = source.id,
            visible = item.optBoolean("visible", true),
            transform = Transform(
                x = pos?.optDouble("x", 0.0)?.toFloat() ?: 0f,
                y = pos?.optDouble("y", 0.0)?.toFloat() ?: 0f,
                scaleX = scale?.optDouble("x", 1.0)?.toFloat() ?: 1f,
                scaleY = scale?.optDouble("y", 1.0)?.toFloat() ?: 1f,
                rotation = item.optDouble("rotation", 0.0).toFloat(),
                align = align,
                flipH = item.optBoolean("flip_h", false),
                flipV = item.optBoolean("flip_v", false),
            ),
            crop = crop,
            blend = when (item.optInt("blend_type", 0)) {
                1 -> BlendMode.Add
                3 -> BlendMode.Screen
                4 -> BlendMode.Multiply
                else -> BlendMode.Normal
            },
        )
    }

    /** OBS menyimpan warna sebagai ABGR 32-bit. */
    private fun obsColor(value: Long): Long {
        val r = value and 0xFF
        val g = (value shr 8) and 0xFF
        val b = (value shr 16) and 0xFF
        val a = (value shr 24) and 0xFF
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

}
