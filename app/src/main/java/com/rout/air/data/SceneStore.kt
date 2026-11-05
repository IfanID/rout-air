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
class SceneStore(
    context: Context,
    val collectionId: String = DEFAULT_ID,
) {
    private val appContext = context.applicationContext
    private val file = dataFile(appContext, collectionId)
    private val backup = backupFile(appContext, collectionId)
    private val assets = File(appContext.filesDir, ASSET_DIR).apply { mkdirs() }

    fun load(): SceneCollection? {
        read(file)?.let { return migrate(it) }
        // Berkas utama rusak atau hilang: pakai salinan terakhir yang masih
        // valid, lalu pulihkan supaya penyimpanan berikutnya normal.
        val restored = read(backup) ?: return null
        runCatching { backup.copyTo(file, overwrite = true) }
        return migrate(restored)
    }

    private fun read(source: File): SceneCollection? {
        if (!source.exists()) return null
        return runCatching { SceneJson.decode(source.readText()) }.getOrNull()
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
        val text = SceneJson.encode(collection)
        runCatching {
            // Tulis ke berkas sementara lalu ganti nama: crash di tengah
            // penulisan tidak meninggalkan JSON setengah jadi.
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(text)
            if (file.exists()) runCatching { file.copyTo(backup, overwrite = true) }
            if (!temp.renameTo(file)) {
                file.writeText(text)
                temp.delete()
            }
        }
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

    /** Menyalin video pilihan ke penyimpanan aplikasi agar path tetap valid. */
    fun importMedia(uri: Uri): String? {
        val name = "media_${UUID.randomUUID().toString().take(8)}.mp4"
        val target = File(assets, name)
        return runCatching {
            val input = appContext.contentResolver.openInputStream(uri) ?: return null
            input.use { source ->
                target.outputStream().use { sink -> source.copyTo(sink) }
            }
            target.absolutePath
        }.getOrNull()
    }

    /** Menghapus berkas koleksi ini beserta cadangannya. */
    fun deleteFiles() {
        runCatching { file.delete() }
        runCatching { backup.delete() }
        runCatching { File(file.parentFile, "${file.name}.tmp").delete() }
    }

    /** Ekspor koleksi aktif ke URI pilihan pengguna (SAF). */
    fun export(uri: Uri, collection: SceneCollection): Boolean = runCatching {
        val output = appContext.contentResolver.openOutputStream(uri) ?: return false
        output.use { it.write(SceneJson.encode(collection).toByteArray()) }
        true
    }.getOrDefault(false)

    /** Membaca koleksi dari URI hasil impor. */
    fun readFrom(uri: Uri): SceneCollection? = runCatching {
        val text = appContext.contentResolver.openInputStream(uri)?.use {
            it.readBytes().toString(Charsets.UTF_8)
        } ?: return null
        SceneJson.decode(text)
    }.getOrNull()

    companion object {
        const val DEFAULT_ID = "default"
        private const val FILE_NAME = "scene_collection.json"
        private const val BACKUP_NAME = "scene_collection.bak.json"
        private const val ASSET_DIR = "scene_assets"
        private const val PREFIX = "collection_"

        fun dataFile(context: Context, id: String): File =
            if (id == DEFAULT_ID) {
                File(context.filesDir, FILE_NAME)
            } else {
                File(context.filesDir, "$PREFIX$id.json")
            }

        fun backupFile(context: Context, id: String): File =
            if (id == DEFAULT_ID) {
                File(context.filesDir, BACKUP_NAME)
            } else {
                File(context.filesDir, "$PREFIX$id.bak.json")
            }

        /** Id koleksi yang tersimpan; koleksi default selalu di urutan pertama. */
        fun ids(context: Context): List<String> = buildList {
            if (dataFile(context, DEFAULT_ID).exists()) add(DEFAULT_ID)
            context.filesDir.listFiles { file ->
                file.isFile &&
                    file.name.startsWith(PREFIX) &&
                    file.name.endsWith(".json") &&
                    !file.name.endsWith(".bak.json")
            }?.mapTo(this) { it.name.removePrefix(PREFIX).removeSuffix(".json") }
        }.distinct()
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
    private var appContext: Context? = null

    /** Id koleksi yang sedang dipakai. */
    @Volatile
    var activeCollectionId: String = SceneStore.DEFAULT_ID
        private set

    fun attach(context: Context) {
        if (store != null) return
        val settings = SettingsStore(context)
        // Koleksi yang tercatat bisa saja sudah dihapus; kembali ke default.
        val savedId = settings.activeCollection()
        val id = if (SceneStore.dataFile(context, savedId).exists()) {
            savedId
        } else {
            SceneStore.DEFAULT_ID
        }
        settings.setActiveCollection(id)
        val s = SceneStore(context, id)
        store = s
        activeCollectionId = id
        appContext = context.applicationContext
        val config = settings.load()
        val width = config.width.coerceAtLeast(2)
        val height = config.height.coerceAtLeast(2)

        var loaded = s.load() ?: SceneCollection.default(
            width,
            height,
            context.getString(com.rout.air.R.string.src_layar),
            context.getString(com.rout.air.R.string.scenes_adegan_n, 1),
        )
        // Scene layar jeda lama dipindahkan keluar dari koleksi scene.
        // Layar jeda kini hidup sebagai konfigurasi tersendiri dan hanya
        // disuntik saat live, jadi tidak boleh tampil sebagai scene.
        val jedaSceneIds = loaded.scenes.filter { it.isScreensaverScene() }.map { it.id }.toSet()
        if (jedaSceneIds.isNotEmpty()) {
            val remaining = loaded.scenes.filterNot { it.id in jedaSceneIds }
            // Jangan sampai koleksi kosong bila semua scene bertanda jeda.
            if (remaining.isNotEmpty()) {
                val active = if (loaded.activeSceneId in jedaSceneIds) {
                    remaining.first().id
                } else {
                    loaded.activeSceneId
                }
                val usedSources = remaining.flatMap { scene ->
                    scene.items.map { it.sourceId }
                }.toSet()
                loaded = loaded.copy(
                    scenes = remaining,
                    sources = loaded.sources.filter { it.id in usedSources },
                    activeSceneId = active,
                )
                s.save(loaded)
                DiagLog.line("jeda: scene layar jeda lama dipindahkan dari daftar scene")
            }
        }

        collection = loaded
        programCollection = loaded
        _state.value = loaded
        _program.value = loaded
    }

    /**
     * Menyesuaikan seluruh item ketika ukuran kanvas berubah.
     *
     * Tanpa ini, item yang tadinya di tengah kanvas portrait bisa berada di
     * luar kanvas landscape sehingga hasilnya hitam. Posisi diskalakan
     * proporsional; sumber Layar dengan template dihitung ulang kotaknya.
     */
    fun rescaleCanvas(fromWidth: Int, fromHeight: Int, toWidth: Int, toHeight: Int) {
        if (fromWidth <= 0 || fromHeight <= 0 || toWidth <= 0 || toHeight <= 0) return
        val current = collection ?: return
        if (fromWidth == toWidth && fromHeight == toHeight) return
        val factorX = toWidth.toFloat() / fromWidth
        val factorY = toHeight.toFloat() / fromHeight

        val next = current.copy(
            scenes = current.scenes.map { scene ->
                scene.copy(
                    items = scene.items.map { item ->
                        val source = current.sources.firstOrNull { it.id == item.sourceId }
                        val display = source?.settings?.screenDisplay
                        val scale = if (source?.type == com.rout.air.core.SourceType.Screen &&
                            display != null &&
                            display != com.rout.air.core.ScreenDisplay.Auto
                        ) {
                            val base = toWidth.toFloat() / toHeight.toFloat()
                            val aspect = when (display) {
                                com.rout.air.core.ScreenDisplay.Landscape ->
                                    if (base >= 1f) base else 1f / base

                                com.rout.air.core.ScreenDisplay.Portrait ->
                                    if (base <= 1f) base else 1f / base

                                com.rout.air.core.ScreenDisplay.Auto -> base
                            }
                            val boxWidth = minOf(toWidth.toFloat(), toHeight * aspect)
                            val boxHeight = boxWidth / aspect
                            boxWidth / toWidth to boxHeight / toHeight
                        } else {
                            item.transform.scaleX to item.transform.scaleY
                        }
                        item.copy(
                            transform = item.transform.copy(
                                x = item.transform.x * factorX,
                                y = item.transform.y * factorY,
                                scaleX = scale.first,
                                scaleY = scale.second,
                            ),
                        )
                    },
                )
            },
        )
        collection = next
        _state.value = next
        // Program ikut diperbarui agar pratinjau dan hasil rekaman sama.
        if (programBeforeScreensaver == null) {
            programCollection = next
            _program.value = next
        }
        store?.save(next)
    }

    fun current(): SceneCollection = collection ?: SceneCollection.default(
        1920,
        1080,
        appContext?.getString(com.rout.air.R.string.src_layar).orEmpty(),
        appContext?.getString(com.rout.air.R.string.scenes_adegan_n, 1).orEmpty(),
    )

    /** Snapshot program; pemanggil pipeline memakai ini, bukan hasil edit. */
    fun programCurrent(): SceneCollection = programCollection ?: current()

    /**
     * Menyalin hasil edit ke program, seperti tombol Transisi di OBS.
     * Sebelum ini dipanggil, perubahan hanya terlihat di pratinjau.
     */
    fun commitToProgram() {
        val next = collection ?: return
        // Transisi saat layar jeda tampil dianggap perintah operator untuk
        // kembali mengudara: layar jeda diakhiri, hasil edit langsung dipakai.
        if (programBeforeScreensaver != null) {
            programBeforeScreensaver = null
            DiagLog.line("jeda: transisi mengakhiri layar jeda")
        }
        programCollection = next
        _program.value = next
        listeners.forEach { it(next) }
    }

    /** Program sebelum layar jeda aktif; null berarti layar jeda tidak aktif. */
    private var programBeforeScreensaver: SceneCollection? = null

    /** True bila program sedang menampilkan layar jeda. */
    fun screensaverActive(): Boolean = programBeforeScreensaver != null

    /**
     * Menampilkan komposisi layar jeda di program tanpa mengubah koleksi
     * scene pengguna. Live dan rekaman tetap berjalan, hanya gambarnya yang
     * diganti. Koleksi [next] tidak pernah disimpan.
     */
    fun showScreensaver(next: SceneCollection): Boolean {
        if (next.scenes.isEmpty()) return false
        val base = programCollection ?: current()
        if (programBeforeScreensaver == null) programBeforeScreensaver = base
        programCollection = next
        _program.value = next
        listeners.forEach { it(next) }
        DiagLog.line("jeda: suntik \"${next.activeScene()?.name}\"")
        return true
    }

    /**
     * Ganti scene program langsung dari panel overlay, tanpa Transisi.
     * Layar jeda ikut diakhiri karena operator memilih scene lain.
     */
    fun switchProgramScene(sceneId: String): Boolean {
        val base = programCollection ?: current()
        val target = base.scenes.firstOrNull { it.id == sceneId } ?: return false
        programBeforeScreensaver = null
        val next = base.copy(activeSceneId = target.id)
        programCollection = next
        _program.value = next
        // Pratinjau edit ikut pindah scene supaya tombol Transisi tidak
        // menyala padahal hanya beda scene aktif.
        collection?.let { edit ->
            val editNext = edit.copy(activeSceneId = target.id)
            collection = editNext
            _state.value = editNext
            editListeners.forEach { it(editNext) }
        }
        listeners.forEach { it(next) }
        DiagLog.line("scene: program diganti ke \"${target.name}\" dari overlay")
        return true
    }

    /**
     * Mengembalikan program seperti sebelum layar jeda. Dipakai juga saat
     * sesi berhenti supaya status tidak tertinggal dan memblokir Transisi.
     */
    fun hideScreensaver(): Boolean {
        val previous = programBeforeScreensaver ?: return false
        DiagLog.line("jeda: kembali ke scene sebelumnya")
        programBeforeScreensaver = null
        programCollection = previous
        _program.value = previous
        listeners.forEach { it(previous) }
        return true
    }

    /** Reset aman saat sesi/pipeline berhenti. */
    fun resetScreensaver() {
        if (programBeforeScreensaver == null) return
        runCatching { hideScreensaver() }
        DiagLog.line("jeda: status direset saat sesi berhenti")
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

    /** Ringkasan koleksi untuk halaman Setelan → Adegan. */
    data class CollectionInfo(val id: String, val title: String, val active: Boolean)

    fun collections(): List<CollectionInfo> {
        val context = appContext ?: return emptyList()
        return SceneStore.ids(context).map { id ->
            val title = if (id == activeCollectionId) {
                collection?.title.orEmpty()
            } else {
                SceneStore(context, id).load()?.title.orEmpty()
            }
            CollectionInfo(id = id, title = title, active = id == activeCollectionId)
        }
    }

    /** Mengganti koleksi aktif tanpa menutup aplikasi. */
    fun switchCollection(id: String): Boolean {
        val context = appContext ?: return false
        if (id == activeCollectionId) return false
        val target = SceneStore(context, id)
        val loaded = target.load() ?: return false
        save()
        store = target
        activeCollectionId = id
        SettingsStore(context).setActiveCollection(id)
        programBeforeScreensaver = null
        collection = loaded
        programCollection = loaded
        _state.value = loaded
        _program.value = loaded
        editListeners.forEach { it(loaded) }
        listeners.forEach { it(loaded) }
        DiagLog.line("koleksi: beralih ke \"${loaded.title.ifBlank { id }}\"")
        return true
    }

    /** Menyalin sebuah koleksi (atau koleksi aktif) ke id baru. */
    fun duplicateCollection(id: String = activeCollectionId, newTitle: String): String? {
        val context = appContext ?: return null
        if (id == activeCollectionId) save()
        val source = SceneStore(context, id).load() ?: return null
        val newId = java.util.UUID.randomUUID().toString().take(8)
        val copy = source.copy(title = newTitle)
        SceneStore(context, newId).save(copy)
        DiagLog.line("koleksi: duplikat \"${source.title}\" -> \"$newTitle\"")
        return newId
    }

    fun renameCollection(id: String, title: String): Boolean {
        val context = appContext ?: return false
        val storeForId = if (id == activeCollectionId) {
            collection?.let { current ->
                val next = current.copy(title = title)
                collection = next
                _state.value = next
                editListeners.forEach { it(next) }
                store?.save(next)
                true
            } ?: false
        } else {
            val other = SceneStore(context, id).load() ?: return false
            SceneStore(context, id).save(other.copy(title = title))
            true
        }
        return storeForId
    }

    fun deleteCollection(id: String): Boolean {
        val context = appContext ?: return false
        val ids = SceneStore.ids(context)
        if (ids.size <= 1) return false
        if (id == activeCollectionId) {
            val fallback = ids.first { it != id }
            if (!switchCollection(fallback)) return false
        }
        SceneStore(context, id).deleteFiles()
        DiagLog.line("koleksi: dihapus $id")
        return true
    }

    /** Ekspor koleksi aktif ke URI pilihan pengguna. */
    fun exportActive(uri: android.net.Uri): Boolean {
        val context = appContext ?: return false
        val current = current()
        return (store ?: SceneStore(context, activeCollectionId)).export(uri, current)
    }

    /** Impor koleksi dari berkas JSON dan jadikan koleksi baru. */
    fun importCollection(uri: android.net.Uri, title: String): String? {
        val context = appContext ?: return null
        val imported = SceneStore(context, activeCollectionId).readFrom(uri) ?: return null
        val newId = java.util.UUID.randomUUID().toString().take(8)
        SceneStore(context, newId).save(imported.copy(title = title))
        DiagLog.line("koleksi: impor \"$title\"")
        return newId
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
