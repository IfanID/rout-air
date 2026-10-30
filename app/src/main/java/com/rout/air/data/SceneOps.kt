package com.rout.air.data

import com.rout.air.core.Scene
import com.rout.air.core.SceneCollection
import com.rout.air.core.SceneItem
import com.rout.air.core.SceneSource
import com.rout.air.core.SourceType
import com.rout.air.core.newId

/**
 * Operasi edit scene collection yang murni (tanpa efek samping), supaya UI
 * cukup memanggil satu fungsi lalu menyimpan hasilnya lewat [SceneManager].
 */

fun SceneCollection.updateItem(item: SceneItem): SceneCollection = copy(
    scenes = scenes.map { scene ->
        if (scene.items.none { it.id == item.id }) {
            scene
        } else {
            scene.copy(items = scene.items.map { if (it.id == item.id) item else it })
        }
    },
)

fun SceneCollection.removeItem(itemId: String): SceneCollection = copy(
    scenes = scenes.map { scene ->
        scene.copy(items = scene.items.filterNot { it.id == itemId })
    },
)

fun SceneCollection.setActiveScene(sceneId: String): SceneCollection =
    if (scenes.any { it.id == sceneId }) copy(activeSceneId = sceneId) else this

fun SceneCollection.addScene(name: String): SceneCollection {
    val scene = Scene(name = name)
    return copy(scenes = scenes + scene, activeSceneId = scene.id)
}

fun SceneCollection.renameScene(sceneId: String, name: String): SceneCollection = copy(
    scenes = scenes.map { if (it.id == sceneId) it.copy(name = name) else it },
)

/** Menghapus adegan; adegan terakhir tidak boleh dihapus. */
fun SceneCollection.removeScene(sceneId: String): SceneCollection {
    if (scenes.size <= 1) return this
    val remaining = scenes.filterNot { it.id == sceneId }
    val active = if (activeSceneId == sceneId) remaining.first().id else activeSceneId
    return copy(scenes = remaining, activeSceneId = active)
}

/** Menambah sumber baru beserta item pertamanya di adegan aktif. */
fun SceneCollection.addSource(source: SceneSource, item: SceneItem): SceneCollection {
    val sceneId = activeSceneId
    return copy(
        sources = sources + source,
        scenes = scenes.map { scene ->
            if (scene.id != sceneId) return@map scene
            val nextZ = (scene.items.maxOfOrNull { it.z } ?: -1) + 1
            scene.copy(items = scene.items + item.copy(sourceId = source.id, z = nextZ))
        },
    )
}

/**
 * Menambah sumber Layar ke scene aktif.
 *
 * Sumber layar hanya satu untuk seluruh collection (satu tangkapan layar),
 * tetapi tiap scene punya item sendiri yang menunjuk sumber itu. Jadi
 * menambah Layar di scene lain **bukan** duplikat sumber: hanya item baru.
 * Mengembalikan id item baru agar UI bisa langsung memilihnya.
 */
fun SceneCollection.addScreenItem(): Pair<SceneCollection, String?> {
    val scene = activeScene() ?: return this to null
    // Satu scene hanya boleh punya satu item Layar.
    val hasScreen = scene.items.any { item ->
        source(item.sourceId)?.type == SourceType.Screen
    }
    if (hasScreen) return this to null
    val existing = sources.firstOrNull { it.type == SourceType.Screen }
    val item = SceneItem(
        sourceId = existing?.id ?: "pending",
        z = (scene.items.maxOfOrNull { it.z } ?: -1) + 1,
        // Layar penuh dikunci default agar tidak ikut tergeser.
        locked = true,
    )
    if (existing != null) {
        val next = copy(
            scenes = scenes.map {
                if (it.id == scene.id) it.copy(items = it.items + item) else it
            },
        )
        return next to item.id
    }
    val source = SceneSource(type = SourceType.Screen, name = "Layar")
    return addSource(source, item) to item.id
}

/** Menghapus sumber sekaligus semua item yang memakainya. */
fun SceneCollection.removeSource(sourceId: String): SceneCollection = copy(
    sources = sources.filterNot { it.id == sourceId },
    scenes = scenes.map { scene ->
        scene.copy(items = scene.items.filterNot { it.sourceId == sourceId })
    },
)

/**
 * Menduplikasi item seperti OBS: item baru memakai sumber yang sama, salinan
 * transform dan crop identik, diletakkan paling depan. Mengembalikan id item
 * baru supaya UI bisa langsung memilihnya.
 *
 * Sumber layar tidak boleh diduplikat karena satu tangkapan layar sudah
 * memenuhi kanvas; duplikatnya hanya menumpuk tanpa guna.
 */
fun SceneCollection.duplicateItem(itemId: String): Pair<SceneCollection, String?> {
    val scene = scenes.firstOrNull { s -> s.items.any { it.id == itemId } } ?: return this to null
    val item = scene.items.first { it.id == itemId }
    val source = source(item.sourceId) ?: return this to null
    if (source.type == SourceType.Screen) return this to null
    val copy = item.copy(
        id = newId("item"),
        z = (scene.items.maxOfOrNull { it.z } ?: 0) + 1,
    )
    val next = copy(
        scenes = scenes.map {
            if (it.id == scene.id) it.copy(items = it.items + copy) else it
        },
    )
    return next to copy.id
}

fun SceneCollection.updateSource(source: SceneSource): SceneCollection = copy(
    sources = sources.map { if (it.id == source.id) source else it },
)

/**
 * Menggeser lapisan item. `delta = +1` menaikkan satu lapis (lebih dekat ke
 * penonton), `-1` menurunkannya. Nilai `z` dinormalkan ulang setelah geser.
 */
fun SceneCollection.moveLayer(itemId: String, delta: Int): SceneCollection = copy(
    scenes = scenes.map { scene ->
        if (scene.items.none { it.id == itemId }) return@map scene
        val ordered = scene.items.sortedBy { it.z }.toMutableList()
        val index = ordered.indexOfFirst { it.id == itemId }
        if (index < 0) return@map scene
        val target = (index + delta).coerceIn(0, ordered.lastIndex)
        if (target == index) return@map scene
        val moving = ordered.removeAt(index)
        ordered.add(target, moving)
        scene.copy(items = ordered.mapIndexed { z, item -> item.copy(z = z) })
    },
)

/** Memindahkan item ke urutan paling depan (`top = true`) atau paling belakang. */
fun SceneCollection.moveLayerToEdge(itemId: String, top: Boolean): SceneCollection = copy(
    scenes = scenes.map { scene ->
        if (scene.items.none { it.id == itemId }) return@map scene
        val ordered = scene.items.sortedBy { it.z }.toMutableList()
        val index = ordered.indexOfFirst { it.id == itemId }
        if (index < 0) return@map scene
        val moving = ordered.removeAt(index)
        if (top) ordered.add(moving) else ordered.add(0, moving)
        scene.copy(items = ordered.mapIndexed { z, item -> item.copy(z = z) })
    },
)

/**
 * Menggeser urutan scene di daftar. `delta = -1` ke kiri (lebih awal),
 * `+1` ke kanan (lebih akhir). Adegan aktif tidak berubah.
 */
fun SceneCollection.moveScene(sceneId: String, delta: Int): SceneCollection {
    val index = scenes.indexOfFirst { it.id == sceneId }
    if (index < 0) return this
    val target = (index + delta).coerceIn(0, scenes.lastIndex)
    if (target == index) return this
    val ordered = scenes.toMutableList()
    val moving = ordered.removeAt(index)
    ordered.add(target, moving)
    return copy(scenes = ordered)
}

/**
 * Menduplikasi scene beserta itemnya. Item baru mendapat id baru tetapi tetap
 * memakai sumber yang sama (sumber tidak ikut diduplikasi). Salinan
 * ditempatkan tepat setelah aslinya dan langsung menjadi scene aktif.
 */
fun SceneCollection.duplicateScene(sceneId: String, name: String? = null): SceneCollection {
    val index = scenes.indexOfFirst { it.id == sceneId }
    if (index < 0) return this
    val original = scenes[index]
    val copy = original.copy(
        id = newId("scene"),
        name = name ?: "${original.name} (salinan)",
        items = original.items.map { it.copy(id = newId("item")) },
    )
    val ordered = scenes.toMutableList().apply { add(index + 1, copy) }
    return copy(scenes = ordered, activeSceneId = copy.id)
}
