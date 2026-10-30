package com.rout.air.data

import android.content.Context
import android.net.Uri
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet
import java.util.UUID

/**
 * Menyimpan scene collection sebagai JSON berversi di penyimpanan internal.
 *
 * Memakai `org.json` bawaan Android supaya tidak menambah dependensi baru;
 * formatnya tetap mudah dibaca dan bisa diimpor dari OBS nanti.
 */
class SceneStore(context: Context) {
    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, FILE_NAME)
    private val assets = File(appContext.filesDir, ASSET_DIR).apply { mkdirs() }

    fun load(): SceneCollection? {
        if (!file.exists()) return null
        val parsed = runCatching { parse(JSONObject(file.readText())) }.getOrNull()
            ?: return null
        return migrate(parsed)
    }

    /**
     * Migrasi versi lama: sejak versi 2 sumber layar dikunci default agar
     * tidak ikut tergeser saat menata item lain. Hanya dijalankan sekali per
     * berkas, jadi membuka kunci manual tetap dihormati.
     */
    private fun migrate(collection: SceneCollection): SceneCollection {
        if (collection.version >= 2) return collection
        val screenIds = collection.sources
            .filter { it.type == SourceType.Screen }
            .map { it.id }
            .toSet()
        return collection.copy(
            version = 2,
            scenes = collection.scenes.map { scene ->
                scene.copy(
                    items = scene.items.map { item ->
                        if (item.sourceId in screenIds) item.copy(locked = true) else item
                    },
                )
            },
        )
    }

    fun save(collection: SceneCollection) {
        runCatching { file.writeText(toJson(collection).toString(2)) }
    }

    /**
     * Menyalin gambar pilihan pengguna ke penyimpanan aplikasi. URI dari
     * Photo Picker tidak selalu bisa dipakai setelah proses mati, sedangkan
     * path lokal selalu valid dan ikut aman saat scene diimpor.
     */
    fun importImage(uri: Uri): String? {
        val name = "img_${UUID.randomUUID().toString().take(8)}"
        val target = File(assets, name)
        return runCatching {
            val input = appContext.contentResolver.openInputStream(uri)
                ?: return null
            input.use { source ->
                target.outputStream().use { sink -> source.copyTo(sink) }
            }
            target.absolutePath
        }.getOrNull()
    }

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
                        put("color", s.settings.color)
                        put("width", s.settings.width)
                        put("height", s.settings.height)
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
                    color = s.optLong("color", 0xFF00E5FF),
                    width = s.optInt("width", 0),
                    height = s.optInt("height", 0),
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

    private companion object {
        const val FILE_NAME = "scene_collection.json"
        const val ASSET_DIR = "scene_assets"
    }
}

/**
 * Sumber kebenaran tunggal untuk scene collection. UI dan pipeline rekaman
 * membaca objek yang sama, sehingga pergantian adegan saat merekam langsung
 * terlihat di file tanpa perlu mengirim ulang intent.
 */
object SceneManager {
    private val _state = MutableStateFlow<SceneCollection?>(null)
    val state: StateFlow<SceneCollection?> = _state.asStateFlow()

    /** Snapshot yang sedang mengudara: dipakai Program dan rekaman. */
    private val _program = MutableStateFlow<SceneCollection?>(null)
    val program: StateFlow<SceneCollection?> = _program.asStateFlow()

    private val listeners = CopyOnWriteArraySet<(SceneCollection) -> Unit>()
    private val editListeners = CopyOnWriteArraySet<(SceneCollection) -> Unit>()

    @Volatile
    private var collection: SceneCollection? = null

    @Volatile
    private var programCollection: SceneCollection? = null

    private var store: SceneStore? = null

    fun attach(context: Context) {
        if (store != null) return
        val s = SceneStore(context)
        store = s
        val loaded = s.load() ?: SceneCollection.default(1920, 1080)
        collection = loaded
        programCollection = loaded
        _state.value = loaded
        _program.value = loaded
    }

    /** Dipakai UI saat flow belum terisi (mis. sesi dipulihkan). */
    fun ensure(width: Int, height: Int): SceneCollection {
        collection?.let { return it }
        val created = SceneCollection.default(width, height)
        collection = created
        programCollection = created
        _state.value = created
        _program.value = created
        return created
    }

    fun current(): SceneCollection = collection ?: ensure(1920, 1080)

    /** Snapshot program; pemanggil pipeline memakai ini, bukan hasil edit. */
    fun programCurrent(): SceneCollection = programCollection ?: current()

    /**
     * Menyalin hasil edit ke program, seperti tombol Transisi di OBS.
     * Sebelum ini dipanggil, perubahan hanya terlihat di pratinjau.
     */
    fun commitToProgram() {
        val next = collection ?: return
        programCollection = next
        _program.value = next
        listeners.forEach { it(next) }
    }

    /** True bila ada perubahan pratinjau yang belum di-transisikan. */
    fun programDiffers(): Boolean = collection != null && collection != programCollection

    /** Perubahan hasil edit (pratinjau), tanpa menyentuh program. */
    fun update(next: SceneCollection) {
        collection = next
        _state.value = next
        editListeners.forEach { it(next) }
    }

    fun save() {
        collection?.let { store?.save(it) }
    }

    fun addListener(listener: (SceneCollection) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (SceneCollection) -> Unit) {
        listeners.remove(listener)
    }

    fun addEditListener(listener: (SceneCollection) -> Unit) {
        editListeners.add(listener)
    }

    fun removeEditListener(listener: (SceneCollection) -> Unit) {
        editListeners.remove(listener)
    }
}
