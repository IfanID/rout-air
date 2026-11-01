package com.rout.air.data

import com.rout.air.core.SceneCollection
import com.rout.air.core.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Layar jeda bawaan: latar hitam, judul RoutAir, subteks di bawahnya. */
class ScreensaverTest {

    private val measure: (String, Float, Int) -> Int = { _, size, _ -> (size * 1.4f).toInt() }

    @Test
    fun `scene bawaan berisi tiga lapisan berurutan`() {
        val built = buildScreensaverScene(1080, 2400, measure)
        assertEquals(SCREENSAVER_SCENE_NAME, built.scene.name)
        assertEquals(listOf(0, 1, 2), built.scene.items.map { it.z })
        assertEquals(3, built.sources.size)
    }

    @Test
    fun `latar menutup seluruh kanvas dengan warna hitam`() {
        val built = buildScreensaverScene(1080, 2400, measure)
        val background = built.sources.first { it.type == SourceType.Color }
        assertEquals(1080, background.settings.width)
        assertEquals(2400, background.settings.height)
        assertEquals(0xFF000000, background.settings.color)
        assertTrue(built.scene.items.first { it.sourceId == background.id }.locked)
    }

    @Test
    fun `judul dan subteks memakai teks bawaan`() {
        val built = buildScreensaverScene(1080, 2400, measure)
        val texts = built.sources.filter { it.type == SourceType.Text }
        assertEquals(2, texts.size)
        assertEquals("RoutAir", texts[0].settings.text)
        assertEquals("Segera kembali", texts[1].settings.text)
        // Subteks berada lebih rendah daripada judul.
        val titleItem = built.scene.items.first { it.sourceId == texts[0].id }
        val subtitleItem = built.scene.items.first { it.sourceId == texts[1].id }
        assertTrue(subtitleItem.transform.y > titleItem.transform.y)
    }

    @Test
    fun `tinggi teks mengikuti pengukur`() {
        val built = buildScreensaverScene(
            width = 1080,
            height = 2400,
            measure = { _, size, _ -> (size * 2).toInt() },
        )
        val title = built.sources.first { it.settings.text == "RoutAir" }
        assertEquals((title.settings.textSize * 2).toInt(), title.settings.height)
    }

    @Test
    fun `huruf R dan A pada RoutAir diberi warna cyan`() {
        val built = buildScreensaverScene(1080, 2400, measure)
        val title = built.sources.first { it.settings.text == "RoutAir" }
        assertEquals(listOf(0, 4), title.settings.textHighlightIndices)
        assertEquals(0xFF00E5FF, title.settings.textHighlightColor)
        assertEquals(0xFFFFFFFF, title.settings.textColor)
    }

    @Test
    fun `teks layar jeda rata tengah dan mengecil otomatis`() {
        val built = buildScreensaverScene(1080, 2400, measure)
        val texts = built.sources.filter { it.type == SourceType.Text }
        assertEquals(2, texts.size)
        texts.forEach { source ->
            assertTrue(source.settings.textCenter)
            assertTrue(source.settings.textAutoFit)
        }
    }

    @Test
    fun `judul berada tepat di tengah kanvas`() {
        val built = buildScreensaverScene(1080, 2400, measure)
        val title = built.sources.first { it.settings.text == "RoutAir" }
        val item = built.scene.items.first { it.sourceId == title.id }
        assertEquals(1200f, item.transform.y)
        assertEquals(540f, item.transform.x)
    }

    @Test
    fun `nama layar jeda berikutnya memakai nomor`() {
        val base = SceneCollection.default(1080, 2400)
        val (once, _) = base.withScreensaverScene(1080, 2400, measure)
        val (twice, id) = once.addScreensaverScene(1080, 2400, measure)
        assertEquals("Layar Jeda 2", twice.scenes.first { it.id == id }.name)
        assertEquals(2, twice.screensaverScenes().size)
    }

    @Test
    fun `scene layar jeda dikenali dari awalan nama`() {
        val (next, _) = SceneCollection.default(1080, 2400)
            .withScreensaverScene(1080, 2400, measure)
        val renamed = next.copy(
            scenes = next.scenes.map {
                if (it.name == SCREENSAVER_SCENE_NAME) it.copy(name = "BRB") else it
            },
        )
        assertEquals(1, next.screensaverScenes().size)
        assertEquals(0, renamed.screensaverScenes().size)
    }

    @Test
    fun `scene lama 1920x1080 digeser ke tengah kanvas portrait`() {
        val lama = buildScreensaverScene(1920, 1080, measure)
        val collection = SceneCollection.default(1920, 1080).copy(
            scenes = SceneCollection.default(1920, 1080).scenes + lama.scene,
            sources = SceneCollection.default(1920, 1080).sources + lama.sources,
        )
        val next = collection.upgradeScreensaverScenes(1080, 2400, measure)
        val scene = next.scenes.first { it.name == SCREENSAVER_SCENE_NAME }
        val title = next.sources.first { it.settings.text == "RoutAir" }
        val item = scene.items.first { it.sourceId == title.id }
        assertEquals(1200f, item.transform.y)
        assertEquals(540f, item.transform.x)
        assertEquals(1080, title.settings.width)
        assertEquals(listOf(0, 4), title.settings.textHighlightIndices)
        assertTrue(title.settings.textCenter)
    }

    @Test
    fun `sumber gambar tambahan tidak diubah saat penyesuaian`() {
        val base = SceneCollection.default(1080, 2400)
        val (withJeda, id) = base.withScreensaverScene(1080, 2400, measure)
        val scene = withJeda.scenes.first { it.id == id }
        val foto = com.rout.air.core.SceneSource(
            type = com.rout.air.core.SourceType.Image,
            name = "Foto",
            settings = com.rout.air.core.SourceSettings(imagePath = "/tmp/a.png", width = 800, height = 600),
        )
        val withFoto = withJeda.copy(
            sources = withJeda.sources + foto,
            scenes = withJeda.scenes.map {
                if (it.id == id) it.copy(items = it.items + com.rout.air.core.SceneItem(sourceId = foto.id, z = 3)) else it
            },
        )
        val next = withFoto.upgradeScreensaverScenes(1080, 2400, measure)
        assertEquals(800, next.sources.first { it.name == "Foto" }.settings.width)
        assertEquals(scene.items.size + 1, next.scenes.first { it.id == id }.items.size)
    }

    @Test
    fun `collection tanpa layar jeda mendapat scene baru`() {
        val base = SceneCollection.default(1080, 2400)
        val (next, id) = base.withScreensaverScene(1080, 2400, measure)
        assertEquals(base.scenes.size + 1, next.scenes.size)
        assertTrue(next.scenes.any { it.id == id && it.name == SCREENSAVER_SCENE_NAME })
        assertNotEquals(base.sources.size, next.sources.size)
    }

    @Test
    fun `scene layar jeda yang sudah ada tidak diduplikasi`() {
        val base = SceneCollection.default(1080, 2400)
        val (once, id) = base.withScreensaverScene(1080, 2400, measure)
        val (twice, againId) = once.withScreensaverScene(1080, 2400, measure)
        assertEquals(id, againId)
        assertEquals(once.scenes.size, twice.scenes.size)
        assertEquals(1, twice.scenes.count { it.name == SCREENSAVER_SCENE_NAME })
    }
}
