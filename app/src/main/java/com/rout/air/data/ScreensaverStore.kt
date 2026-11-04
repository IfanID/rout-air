package com.rout.air.data

import android.content.Context
import com.rout.air.engine.SourceBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Satu tampilan layar jeda: gambar opsional dan orientasinya. */
data class ScreensaverEntry(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    /** Null berarti tampilan bawaan (latar hitam + judul RoutAir). */
    val imagePath: String? = null,
    /** Vertikal mengisi tinggi kanvas, horizontal mengisi lebarnya. */
    val vertical: Boolean = true,
)

/**
 * Konfigurasi layar jeda yang disimpan terpisah dari scene collection.
 *
 * Layar jeda tidak pernah menjadi scene: saat live, komposisinya dibangun
 * dari daftar ini lalu disuntikkan ke program.
 */
object ScreensaverStore {
    private const val FILE_NAME = "screensaver.json"
    private const val DEFAULT_ID = "jeda-bawaan"

    private val _entries = MutableStateFlow<List<ScreensaverEntry>>(emptyList())
    val entries: StateFlow<List<ScreensaverEntry>> = _entries.asStateFlow()

    private var file: File? = null

    fun attach(context: Context) {
        if (file != null) return
        val target = File(context.filesDir, FILE_NAME)
        file = target
        val loaded = runCatching { read(target) }.getOrNull().orEmpty()
        _entries.value = if (loaded.isEmpty()) listOf(defaultEntry()) else loaded
    }

    fun add(entry: ScreensaverEntry) {
        _entries.value = _entries.value + entry
        save()
    }

    fun remove(id: String) {
        val next = _entries.value.filterNot { it.id == id && it.id != DEFAULT_ID }
        if (next == _entries.value) return
        _entries.value = next
        save()
    }

    /** Tampilan bawaan tidak bisa dihapus. */
    fun isDefault(id: String): Boolean = id == DEFAULT_ID

    private fun defaultEntry() = ScreensaverEntry(
        id = DEFAULT_ID,
        name = "RoutAir — Segera kembali",
        imagePath = null,
        vertical = true,
    )

    private fun save() {
        val target = file ?: return
        runCatching {
            val array = JSONArray()
            _entries.value.forEach { entry ->
                array.put(
                    JSONObject().apply {
                        put("id", entry.id)
                        put("name", entry.name)
                        put("image", entry.imagePath ?: "")
                        put("vertical", entry.vertical)
                    },
                )
            }
            target.writeText(array.toString())
        }
    }

    private fun read(target: File): List<ScreensaverEntry> {
        if (!target.exists()) return emptyList()
        val array = JSONArray(target.readText())
        val out = mutableListOf<ScreensaverEntry>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val image = item.optString("image").takeIf { it.isNotBlank() }
            out += ScreensaverEntry(
                id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                name = item.optString("name").ifBlank { "Layar Jeda" },
                imagePath = image,
                vertical = item.optBoolean("vertical", true),
            )
        }
        return out
    }
}

/**
 * Membangun komposisi jeda dari [entry] lalu menyuntikkannya ke program.
 * Koleksi hasilnya terpisah dari scene pengguna.
 */
fun showScreensaverEntry(context: Context, entry: ScreensaverEntry): Boolean {
    val config = SettingsStore(context).load()
    val width = config.width.coerceAtLeast(2)
    val height = config.height.coerceAtLeast(2)
    val density = context.resources.displayMetrics.density
    val collection = buildScreensaverCollection(entry, width, height) { text, size, maxWidth ->
        SourceBitmap.measureTextHeight(text, size, maxWidth, density)
    }
    return SceneManager.showScreensaver(collection)
}
