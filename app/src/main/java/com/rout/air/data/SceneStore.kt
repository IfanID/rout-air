package com.rout.air.data

import android.content.Context
import android.net.Uri
import com.rout.air.core.SceneCollection
import com.rout.air.core.SourceType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
        val parsed = runCatching { SceneJson.decode(file.readText()) }.getOrNull()
            ?: return null
        return migrate(parsed)
    }

    /**
     * Migrasi versi lama. Versi 4 mengunci item Layar supaya komposisi di
     * pratinjau tidak bergeser tanpa sengaja. Pengguna tetap bisa membuka
     * kunci lewat ikon gembok; migrasi hanya berjalan sekali per berkas.
     */
    private fun migrate(collection: SceneCollection): SceneCollection {
        if (collection.version >= 4) return collection
        val screenIds = collection.sources
            .filter { it.type == SourceType.Screen }
            .map { it.id }
            .toSet()
        return collection.copy(
            version = 4,
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
        runCatching { file.writeText(SceneJson.encode(collection)) }
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
