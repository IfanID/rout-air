package com.rout.air.data.scene

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Surface
import com.rout.air.core.model.SceneCollection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.roundToInt
import com.rout.air.core.model.Align
import com.rout.air.core.model.Scene
import com.rout.air.core.model.SceneItem
import com.rout.air.core.model.ScreenDisplay
import com.rout.air.core.model.SourceType
import com.rout.air.core.model.newId
import kotlinx.coroutines.flow.asStateFlow
import com.rout.air.data.logging.DiagLog
import com.rout.air.data.screensaver.isScreensaverScene
import com.rout.air.data.settings.SettingsStore

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

    /**
     * Ukuran kanvas yang benar-benar dipakai koleksi saat ini. Dipakai sebagai
     * acuan saat menyesuaikan scene, supaya pemanggil dengan ukuran lama yang
     * basi tidak menggeser item dua kali.
     */
    @Volatile
    private var layoutCanvas: Pair<Int, Int>? = null

    /** Rotasi layar yang sesuai dengan [layoutCanvas]. */
    @Volatile
    private var layoutRotation: Int = -1

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
        layoutCanvas = settings.loadSceneCanvas()
        layoutRotation = settings.loadSceneRotation()
    }

    /**
     * Pastikan scene mengikuti ukuran kanvas aktif.
     *
     * Dipakai UI saat tidak ada sesi: acuan diambil dari [layoutCanvas], bukan
     * dari pemanggil, sehingga rotasi beruntun tidak pernah menggeser item
     * dua kali.
     */
    fun ensureCanvas(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val from = layoutCanvas ?: return
        if (from.first == width && from.second == height) return
        rescaleCanvas(from.first, from.second, width, height)
    }

    /**
     * Menyesuaikan seluruh item ketika ukuran kanvas berubah.
     *
     * Kanvas portrait dan landscape adalah layar yang sama, hanya diputar.
     * Jadi posisi item **diputar** mengikuti arah putaran layar (bukan
     * diskalakan): ukuran tetap, jarak ke tepi layar tetap, dan tidak ada
     * sumber yang keluar bingkai.
     */
    fun rescaleCanvas(fromWidth: Int, fromHeight: Int, toWidth: Int, toHeight: Int) {
        if (fromWidth <= 0 || fromHeight <= 0 || toWidth <= 0 || toHeight <= 0) return
        val current = collection ?: return
        // Abaikan ukuran asal yang basi: pakai kanvas yang benar-benar dipakai.
        val reference = layoutCanvas
        val fromW: Int
        val fromH: Int
        if (reference != null && reference != (fromWidth to fromHeight)) {
            DiagLog.line(
                "kanvas: acuan disesuaikan ${fromWidth}x$fromHeight -> " +
                    "${reference.first}x${reference.second}",
            )
            fromW = reference.first
            fromH = reference.second
        } else {
            fromW = fromWidth
            fromH = fromHeight
        }
        if (fromW == toWidth && fromH == toHeight) {
            layoutCanvas = toWidth to toHeight
            return
        }

        val toRotation = currentRotation()
        // Surface.ROTATION_* berupa indeks 0..3; ubah ke derajat.
        // 90 derajat = perangkat diputar berlawanan jarum jam.
        val delta = if (layoutRotation >= 0 && toRotation >= 0) {
            (((toRotation - layoutRotation) % 4 + 4) % 4) * 90
        } else {
            -1
        }
        val factorX = toWidth.toFloat() / fromW
        val factorY = toHeight.toFloat() / fromH
        DiagLog.line(
            "scene: putar ${fromW}x$fromH -> ${toWidth}x$toHeight " +
                "arah=${if (delta < 0) "?" else delta.toString()}",
        )

        val next = current.copy(
            scenes = current.scenes.map { scene ->
                scene.copy(
                    items = scene.items.map { item ->
                        val source = current.source(item.sourceId)
                        // Ukuran kotak sebelum diputar.
                        val boxWidth: Float
                        val boxHeight: Float
                        if (source?.type == SourceType.Screen) {
                            boxWidth = fromW.toFloat()
                            boxHeight = fromH.toFloat()
                        } else if (source != null) {
                            val size = itemDisplaySize(item, source, fromW, fromH)
                            boxWidth = size.first
                            boxHeight = size.second
                        } else {
                            boxWidth = 0f
                            boxHeight = 0f
                        }
                        val (anchorX, anchorY) = anchorFraction(item.transform.align)
                        val centerX = item.transform.x - anchorX * boxWidth + boxWidth / 2f
                        val centerY = item.transform.y - anchorY * boxHeight + boxHeight / 2f
                        val (newCenterX, newCenterY) = rotateCenter(
                            centerX,
                            centerY,
                            fromW,
                            fromH,
                            delta,
                            factorX,
                            factorY,
                        )

                        val display = source?.settings?.screenDisplay
                        val transform = when {
                            source?.type == SourceType.Screen &&
                                display != null && display != ScreenDisplay.Auto -> {
                                // Template layar: kotak dihitung ulang untuk
                                // orientasi baru, posisi mengikuti pusat.
                                val base = toWidth.toFloat() / toHeight.toFloat()
                                val aspect = when (display) {
                                    ScreenDisplay.Landscape ->
                                        if (base >= 1f) base else 1f / base

                                    ScreenDisplay.Portrait ->
                                        if (base <= 1f) base else 1f / base

                                    ScreenDisplay.Auto -> base
                                }
                                val boxW = minOf(toWidth.toFloat(), toHeight * aspect)
                                val boxH = boxW / aspect
                                item.transform.copy(
                                    x = newCenterX - boxW / 2f + anchorX * boxW,
                                    y = newCenterY - boxH / 2f + anchorY * boxH,
                                    scaleX = boxW / toWidth,
                                    scaleY = boxH / toHeight,
                                )
                            }

                            source?.type == SourceType.Screen -> {
                                // Layar otomatis: ukuran mengikuti kanvas baru.
                                item.transform.copy(
                                    x = newCenterX - toWidth / 2f + anchorX * toWidth,
                                    y = newCenterY - toHeight / 2f + anchorY * toHeight,
                                )
                            }

                            boxWidth <= 0f || boxHeight <= 0f -> item.transform

                            else -> keepInside(
                                item.transform,
                                boxWidth,
                                boxHeight,
                                newCenterX - boxWidth / 2f + anchorX * boxWidth,
                                newCenterY - boxHeight / 2f + anchorY * boxHeight,
                                toWidth,
                                toHeight,
                            )
                        }
                        if (transform != item.transform) {
                            DiagLog.line(
                                "scene:   ${source?.name ?: item.sourceId} " +
                                    "x=${"%.0f".format(item.transform.x)}->" +
                                    "${"%.0f".format(transform.x)} y=" +
                                    "${"%.0f".format(item.transform.y)}->" +
                                    "${"%.0f".format(transform.y)}",
                            )
                        }
                        item.copy(transform = transform)
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
            // Pipeline menyusun ulang layer program dari snapshot ini. Tanpa
            // pemberitahuan, layer lama (posisi sebelum rotasi) terus dipakai
            // sehingga sumber tampak hilang.
            listeners.forEach { it(next) }
        }
        editListeners.forEach { it(next) }
        layoutCanvas = toWidth to toHeight
        if (toRotation >= 0) {
            layoutRotation = toRotation
            appContext?.let { SettingsStore(it).saveSceneRotation(toRotation) }
        }
        store?.save(next)
    }

    /**
     * Memutar satu titik mengikuti putaran layar. [delta] 90 berarti perangkat
     * diputar berlawanan jarum jam, 270 searah jarum jam.
     */
    private fun rotateCenter(
        x: Float,
        y: Float,
        fromWidth: Int,
        fromHeight: Int,
        delta: Int,
        factorX: Float,
        factorY: Float,
    ): Pair<Float, Float> = when (delta) {
        90 -> y to (fromWidth - x)
        180 -> (fromWidth - x) to (fromHeight - y)
        270 -> (fromHeight - y) to x
        // Arah tidak diketahui: pakai proporsi kanvas sebagai cadangan.
        else -> (x * factorX) to (y * factorY)
    }

    /** Rotasi layar saat ini (Surface.ROTATION_*), -1 bila tidak diketahui. */
    private fun currentRotation(): Int = runCatching {
        val context = appContext ?: return -1
        val manager = context.getSystemService(DisplayManager::class.java) ?: return -1
        manager.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0
    }.getOrDefault(-1)

    /** Ukuran tampil item sebelum crop dalam koordinat kanvas. */
    private fun itemDisplaySize(
        item: SceneItem,
        source: com.rout.air.core.model.SceneSource,
        canvasWidth: Int,
        canvasHeight: Int,
    ): Pair<Float, Float> {
        if (source.type == SourceType.Screen) {
            return canvasWidth.toFloat() to canvasHeight.toFloat()
        }
        // Ukuran nyata dari pipeline lebih akurat untuk kamera/media/browser;
        // ukuran tersimpan dipakai bila sumber belum pernah tampil.
        val reported = com.rout.air.core.model.SourceMetrics.sizes.value[source.id]
        val naturalWidth = (reported?.first ?: source.settings.width).toFloat()
        val naturalHeight = (reported?.second ?: source.settings.height).toFloat()
        if (naturalWidth <= 0f || naturalHeight <= 0f) return 0f to 0f
        val crop = item.crop
        val width = naturalWidth * (1f - crop.left - crop.right).coerceIn(0.02f, 1f) *
            item.transform.scaleX
        val height = naturalHeight * (1f - crop.top - crop.bottom).coerceIn(0.02f, 1f) *
            item.transform.scaleY
        return width to height
    }

    /**
     * Posisi item yang dijamin tetap terlihat di kanvas: kotak yang lebih kecil
     * dari kanvas dipaksa seluruhnya masuk, kotak yang lebih besar dibiarkan
     * menutupi kanvas (mis. latar yang diperbesar).
     */
    private fun keepInside(
        transform: com.rout.air.core.model.Transform,
        width: Float,
        height: Float,
        x: Float,
        y: Float,
        canvasWidth: Int,
        canvasHeight: Int,
    ): com.rout.air.core.model.Transform {
        val (anchorX, anchorY) = anchorFraction(transform.align)
        val left = clampSide(x - anchorX * width, width, canvasWidth)
        val top = clampSide(y - anchorY * height, height, canvasHeight)
        return transform.copy(
            x = left + anchorX * width,
            y = top + anchorY * height,
        )
    }

    private fun clampSide(value: Float, size: Float, canvas: Int): Float =
        if (size <= canvas) {
            value.coerceIn(0f, canvas - size)
        } else {
            value.coerceIn(canvas - size, 0f)
        }

    /**
     * Menjepit posisi semua item agar tidak ada sumber yang berada di luar
     * kanvas. Dipanggil setiap koleksi diperbarui (geser, skala, slider).
     */
    private fun clampToCanvas(
        next: SceneCollection,
        width: Int,
        height: Int,
    ): SceneCollection {
        if (width <= 0 || height <= 0) return next
        var changed = false
        val scenes = next.scenes.map { scene ->
            var sceneChanged = false
            val items = scene.items.map { item ->
                val source = next.source(item.sourceId)
                if (source == null || source.type == SourceType.Screen) return@map item
                val (itemWidth, itemHeight) = itemDisplaySize(item, source, width, height)
                if (itemWidth <= 0f || itemHeight <= 0f) return@map item
                val bounded = keepInside(
                    item.transform,
                    itemWidth,
                    itemHeight,
                    item.transform.x,
                    item.transform.y,
                    width,
                    height,
                )
                val same = kotlin.math.abs(bounded.x - item.transform.x) < 0.01f &&
                    kotlin.math.abs(bounded.y - item.transform.y) < 0.01f
                if (same) {
                    item
                } else {
                    sceneChanged = true
                    item.copy(transform = bounded)
                }
            }
            if (sceneChanged) {
                changed = true
                scene.copy(items = items)
            } else {
                scene
            }
        }
        return if (changed) next.copy(scenes = scenes) else next
    }

    /** Titik jangkar item sebagai fraksi 0..1 dari kotaknya. */
    private fun anchorFraction(align: Align): Pair<Float, Float> = when (align) {
        Align.TopLeft -> 0f to 0f
        Align.Top -> 0.5f to 0f
        Align.TopRight -> 1f to 0f
        Align.Left -> 0f to 0.5f
        Align.Center -> 0.5f to 0.5f
        Align.Right -> 1f to 0.5f
        Align.BottomLeft -> 0f to 1f
        Align.Bottom -> 0.5f to 1f
        Align.BottomRight -> 1f to 1f
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
        // Gestur mengirim salinan yang sama berkali-kali; abaikan agar
        // listener pipeline dan UI tidak dijalankan tanpa perubahan nyata.
        if (collection == next) return
        val canvas = layoutCanvas
        val bounded = if (canvas != null) {
            clampToCanvas(next, canvas.first, canvas.second)
        } else {
            next
        }
        collection = bounded
        _state.value = bounded
        editListeners.forEach { it(bounded) }
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
