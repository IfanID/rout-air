package com.rout.air.data

import com.rout.air.core.Align
import com.rout.air.core.BlendMode
import com.rout.air.core.Crop
import com.rout.air.core.Scene
import com.rout.air.core.SceneCollection
import com.rout.air.core.SceneItem
import com.rout.air.core.SceneSource
import com.rout.air.core.SourceSettings
import com.rout.air.core.SourceType
import com.rout.air.core.Transform
import org.json.JSONArray
import org.json.JSONObject

/**
 * Serialisasi scene collection ke JSON dan sebaliknya.
 *
 * Dipisahkan dari [SceneStore] supaya logika format bisa diuji tanpa
 * `Context`, dan supaya penyimpanan berkas tidak tercampur dengan format.
 * Memakai `org.json` bawaan Android agar tidak menambah dependensi.
 */
internal object SceneJson {
    /** Mengubah collection menjadi teks JSON yang mudah dibaca. */
    fun encode(collection: SceneCollection): String = toJson(collection).toString(2)

    /** Membaca teks JSON; melempar `JSONException` bila format rusak. */
    fun decode(text: String): SceneCollection = parse(JSONObject(text))

    private fun toJson(c: SceneCollection): JSONObject = JSONObject().apply {
        put("version", c.version)
        put("activeSceneId", c.activeSceneId)
        put("sources", JSONArray().apply {
            c.sources.forEach { s ->
                put(JSONObject().apply {
                    put("id", s.id)
                    put("type", s.type.name)
                    put("name", s.name)
                    put("settings", JSONObject().apply {
                        s.settings.imagePath?.let { put("imagePath", it) }
                        put("text", s.settings.text)
                        put("textSize", s.settings.textSize.toDouble())
                        put("textColor", s.settings.textColor)
                        put(
                            "textHighlightIndices",
                            JSONArray(s.settings.textHighlightIndices),
                        )
                        put("textCenter", s.settings.textCenter)
                        put("textAutoFit", s.settings.textAutoFit)
                        put("textHighlightColor", s.settings.textHighlightColor)
                        put("color", s.settings.color)
                        put("width", s.settings.width)
                        put("height", s.settings.height)
                        put("audioDeviceId", s.settings.audioDeviceId)
                        s.settings.audioDeviceName?.let { put("audioDeviceName", it) }
                        put("audioDeviceType", s.settings.audioDeviceType)
                        put("audioScoEnabled", s.settings.audioScoEnabled)
                    })
                })
            }
        })
        put("scenes", JSONArray().apply {
            c.scenes.forEach { scene ->
                put(JSONObject().apply {
                    put("id", scene.id)
                    put("name", scene.name)
                    put("items", JSONArray().apply {
                        scene.items.forEach { item ->
                            put(JSONObject().apply {
                                put("id", item.id)
                                put("sourceId", item.sourceId)
                                put("z", item.z)
                                put("visible", item.visible)
                                put("locked", item.locked)
                                put("blend", item.blend.name)
                                put("opacity", item.opacity.toDouble())
                                put("transform", JSONObject().apply {
                                    put("x", item.transform.x.toDouble())
                                    put("y", item.transform.y.toDouble())
                                    put("scaleX", item.transform.scaleX.toDouble())
                                    put("scaleY", item.transform.scaleY.toDouble())
                                    put("rotation", item.transform.rotation.toDouble())
                                    put("align", item.transform.align.name)
                                    put("flipH", item.transform.flipH)
                                    put("flipV", item.transform.flipV)
                                })
                                put("crop", JSONObject().apply {
                                    put("left", item.crop.left.toDouble())
                                    put("top", item.crop.top.toDouble())
                                    put("right", item.crop.right.toDouble())
                                    put("bottom", item.crop.bottom.toDouble())
                                })
                            })
                        }
                    })
                })
            }
        })
    }

    private fun parse(json: JSONObject): SceneCollection {
        val sources = json.optJSONArray("sources").orEmpty().map { raw ->
            val o = raw as JSONObject
            val s = o.optJSONObject("settings") ?: JSONObject()
            SceneSource(
                id = o.getString("id"),
                type = enumOr(SourceType.Screen, o.optString("type")),
                name = o.optString("name", "Sumber"),
                settings = SourceSettings(
                    imagePath = s.optString("imagePath").takeIf { it.isNotBlank() },
                    text = s.optString("text", "Teks baru"),
                    textSize = s.optDouble("textSize", 48.0).toFloat(),
                    textColor = s.optLong("textColor", 0xFFFFFFFF),
                    textHighlightIndices = s.optJSONArray("textHighlightIndices")
                        ?.let { array -> (0 until array.length()).map { array.optInt(it) } }
                        .orEmpty(),
                    textCenter = s.optBoolean("textCenter", false),
                    textAutoFit = s.optBoolean("textAutoFit", false),
                    textHighlightColor = s.optLong("textHighlightColor", 0xFF00E5FF),
                    color = s.optLong("color", 0xFF00E5FF),
                    width = s.optInt("width", 0),
                    height = s.optInt("height", 0),
                    audioDeviceId = s.optInt("audioDeviceId", 0),
                    audioDeviceName = s.optString("audioDeviceName").takeIf { it.isNotBlank() },
                    audioDeviceType = s.optInt("audioDeviceType", 0),
                    audioScoEnabled = s.optBoolean("audioScoEnabled", false),
                ),
            )
        }

        val scenes = json.optJSONArray("scenes").orEmpty().map { raw ->
            val o = raw as JSONObject
            Scene(
                id = o.getString("id"),
                name = o.optString("name", "Adegan"),
                items = o.optJSONArray("items").orEmpty().map { itemRaw ->
                    val i = itemRaw as JSONObject
                    val t = i.optJSONObject("transform") ?: JSONObject()
                    val cr = i.optJSONObject("crop") ?: JSONObject()
                    SceneItem(
                        id = i.getString("id"),
                        sourceId = i.getString("sourceId"),
                        z = i.optInt("z", 0),
                        visible = i.optBoolean("visible", true),
                        locked = i.optBoolean("locked", false),
                        blend = enumOr(BlendMode.Normal, i.optString("blend")),
                        opacity = i.optDouble("opacity", 1.0).toFloat().coerceIn(0f, 1f),
                        transform = Transform(
                            x = t.optDouble("x", 0.0).toFloat(),
                            y = t.optDouble("y", 0.0).toFloat(),
                            scaleX = t.optDouble("scaleX", 1.0).toFloat(),
                            scaleY = t.optDouble("scaleY", 1.0).toFloat(),
                            rotation = t.optDouble("rotation", 0.0).toFloat(),
                            align = enumOr(Align.TopLeft, t.optString("align")),
                            flipH = t.optBoolean("flipH", false),
                            flipV = t.optBoolean("flipV", false),
                        ),
                        crop = Crop(
                            left = cr.optDouble("left", 0.0).toFloat(),
                            top = cr.optDouble("top", 0.0).toFloat(),
                            right = cr.optDouble("right", 0.0).toFloat(),
                            bottom = cr.optDouble("bottom", 0.0).toFloat(),
                        ),
                    )
                },
            )
        }

        require(scenes.isNotEmpty()) { "Scene collection kosong" }
        val active = json.optString("activeSceneId")
            .takeIf { id -> scenes.any { it.id == id } }
            ?: scenes.first().id
        return SceneCollection(
            version = json.optInt("version", 1),
            activeSceneId = active,
            scenes = scenes,
            sources = sources,
        )
    }

    private inline fun <reified T : Enum<T>> enumOr(fallback: T, value: String): T =
        enumValues<T>().firstOrNull { it.name == value } ?: fallback

    private fun JSONArray?.orEmpty(): List<Any> {
        if (this == null) return emptyList()
        return (0 until length()).map { get(it) }
    }
}
