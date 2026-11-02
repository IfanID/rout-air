package com.rout.air.data

import com.rout.air.core.Scene
import com.rout.air.core.SceneCollection
import com.rout.air.core.SceneItem
import com.rout.air.core.SceneSource
import com.rout.air.core.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Menguji operasi edit scene collection yang murni. */
class SceneOpsTest {
    private fun collection(
        sceneName: String = "Adegan",
        items: List<SceneItem>,
        sources: List<SceneSource>,
    ): SceneCollection {
        val scene = Scene(name = sceneName, items = items)
        return SceneCollection(activeSceneId = scene.id, scenes = listOf(scene), sources = sources)
    }

    @Test
    fun duplicateItem_copiesTransformAndSharesSource() {
        val source = SceneSource(type = SourceType.Image, name = "Logo")
        val item = SceneItem(sourceId = source.id)
        val original = collection(items = listOf(item), sources = listOf(source))

        val (next, newId) = original.duplicateItem(item.id)

        assertNotNull(newId)
        assertNotEquals(item.id, newId)
        assertEquals(2, next.scenes.first().items.size)
        // Sumber tidak diduplikat: kedua item menunjuk sumber yang sama.
        assertEquals(1, next.sources.size)
        assertTrue(next.scenes.first().items.all { it.sourceId == source.id })
        // Salinan berada paling depan.
        assertTrue(next.scenes.first().items.last().z > next.scenes.first().items.first().z)
    }

    @Test
    fun duplicateItem_refusesScreenSource() {
        val screen = SceneSource(type = SourceType.Screen, name = "Layar")
        val item = SceneItem(sourceId = screen.id)
        val original = collection(items = listOf(item), sources = listOf(screen))

        val (next, newId) = original.duplicateItem(item.id)

        assertNull(newId)
        assertSame(original, next)
        assertEquals(1, next.scenes.first().items.size)
    }

    @Test
    fun removeItem_keepsSourceUsedByOtherItems() {
        val source = SceneSource(type = SourceType.Text, name = "Judul")
        val first = SceneItem(sourceId = source.id)
        val second = SceneItem(sourceId = source.id)
        val original = collection(items = listOf(first, second), sources = listOf(source))

        val next = original.removeItem(first.id)

        assertEquals(1, next.scenes.first().items.size)
        assertEquals(second.id, next.scenes.first().items.first().id)
        assertEquals(1, next.sources.size)
    }

    @Test
    fun moveLayer_renormalizesZ() {
        val source = SceneSource(type = SourceType.Color, name = "Warna")
        val a = SceneItem(sourceId = source.id, z = 0)
        val b = SceneItem(sourceId = source.id, z = 1)
        val c = SceneItem(sourceId = source.id, z = 2)
        val original = collection(items = listOf(a, b, c), sources = listOf(source))

        val next = original.moveLayer(a.id, +1)

        assertEquals(listOf(b.id, a.id, c.id), next.scenes.first().items.map { it.id })
        assertEquals(listOf(0, 1, 2), next.scenes.first().items.map { it.z })
    }

    @Test
    fun moveLayerToEdge_movesToFrontAndBack() {
        val source = SceneSource(type = SourceType.Color, name = "Warna")
        val a = SceneItem(sourceId = source.id, z = 0)
        val b = SceneItem(sourceId = source.id, z = 1)
        val c = SceneItem(sourceId = source.id, z = 2)
        val original = collection(items = listOf(a, b, c), sources = listOf(source))

        val front = original.moveLayerToEdge(a.id, top = true)
        assertEquals(listOf(b.id, c.id, a.id), front.scenes.first().items.map { it.id })

        val back = original.moveLayerToEdge(c.id, top = false)
        assertEquals(listOf(c.id, a.id, b.id), back.scenes.first().items.map { it.id })
    }

    @Test
    fun removeScene_neverRemovesLastScene() {
        val source = SceneSource(type = SourceType.Screen, name = "Layar")
        val scene = Scene(name = "Satu", items = listOf(SceneItem(sourceId = source.id)))
        var c = SceneCollection(
            activeSceneId = scene.id,
            scenes = listOf(scene),
            sources = listOf(source),
        )

        c = c.removeScene(scene.id)

        assertEquals(1, c.scenes.size)
    }

    @Test
    fun moveScene_swapsOrderAndKeepsActiveScene() {
        val a = Scene(name = "A")
        val b = Scene(name = "B")
        val c = Scene(name = "C")
        val original = SceneCollection(
            activeSceneId = b.id,
            scenes = listOf(a, b, c),
            sources = emptyList(),
        )

        val next = original.moveScene(b.id, -1)

        assertEquals(listOf(b.id, a.id, c.id), next.scenes.map { it.id })
        assertEquals(b.id, next.activeSceneId)
    }

    @Test
    fun duplicateScene_copiesItemsWithNewIdsAndSharesSources() {
        val source = SceneSource(type = SourceType.Image, name = "Logo")
        val item = SceneItem(sourceId = source.id)
        val scene = Scene(name = "Adegan", items = listOf(item))
        val original = SceneCollection(
            activeSceneId = scene.id,
            scenes = listOf(scene),
            sources = listOf(source),
        )

        val next = original.duplicateScene(scene.id)

        assertEquals(2, next.scenes.size)
        // Salinan ditempatkan tepat setelah aslinya dan menjadi aktif.
        assertEquals(next.scenes[1].id, next.activeSceneId)
        assertEquals("Adegan (salinan)", next.scenes[1].name)
        assertEquals(1, next.sources.size)
        assertEquals(1, next.scenes[1].items.size)
        assertNotEquals(item.id, next.scenes[1].items.first().id)
        assertEquals(item.sourceId, next.scenes[1].items.first().sourceId)
    }

    @Test
    fun addScreenItem_reusesSameSourceInAnotherScene() {
        val screen = SceneSource(type = SourceType.Screen, name = "Layar")
        val first = Scene(name = "Satu", items = listOf(SceneItem(sourceId = screen.id)))
        val second = Scene(name = "Dua")
        val original = SceneCollection(
            activeSceneId = second.id,
            scenes = listOf(first, second),
            sources = listOf(screen),
        )

        val (next, itemId) = original.addScreenItem()

        assertNotNull(itemId)
        // Bukan duplikat: sumber tetap satu, hanya item baru di scene aktif.
        assertEquals(1, next.sources.size)
        assertEquals(1, next.scenes[1].items.size)
        assertEquals(screen.id, next.scenes[1].items.first().sourceId)
        // Layar baru langsung terkunci supaya tidak bergeser.
        assertTrue(next.scenes[1].items.first().locked)
        assertEquals(1, next.scenes[0].items.size)
    }

    @Test
    fun addScreenItem_createsSourceWhenNoneExists() {
        val scene = Scene(name = "Satu")
        val original = SceneCollection(
            activeSceneId = scene.id,
            scenes = listOf(scene),
            sources = emptyList(),
        )

        val (next, itemId) = original.addScreenItem()

        assertNotNull(itemId)
        assertEquals(1, next.sources.size)
        assertEquals(SourceType.Screen, next.sources.first().type)
        assertEquals(1, next.scenes.first().items.size)
        assertEquals(next.sources.first().id, next.scenes.first().items.first().sourceId)
    }

    @Test
    fun defaultScene_allowsMovingScreen() {
        // Layar tidak dikunci supaya posisi bisa diatur saat kanvas berbeda
        // orientasi dengan tangkapan.
        val collection = SceneCollection.default(1920, 1080)

        assertFalse(collection.scenes.first().items.first().locked)
    }

    @Test
    fun addScreenItem_refusesSecondScreenInSameScene() {
        val screen = SceneSource(type = SourceType.Screen, name = "Layar")
        val scene = Scene(name = "Adegan", items = listOf(SceneItem(sourceId = screen.id)))
        val original = SceneCollection(
            activeSceneId = scene.id,
            scenes = listOf(scene),
            sources = listOf(screen),
        )

        val (next, itemId) = original.addScreenItem()

        assertNull(itemId)
        assertSame(original, next)
        assertEquals(1, next.scenes.first().items.size)
    }
}
