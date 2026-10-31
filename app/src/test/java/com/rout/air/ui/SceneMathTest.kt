package com.rout.air.ui

import com.rout.air.core.Align
import com.rout.air.core.CaptureConfig
import com.rout.air.core.Crop
import com.rout.air.core.Scene
import com.rout.air.core.SceneCollection
import com.rout.air.core.SceneItem
import com.rout.air.core.SceneSource
import com.rout.air.core.SourceSettings
import com.rout.air.core.SourceType
import com.rout.air.core.Transform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Menguji matematika transform yang dipakai pratinjau dan panel Studio. */
class SceneMathTest {
    @Test
    fun anchorOffset_coversAllAlignments() {
        assertEquals(0f to 0f, anchorOffset(Align.TopLeft, 100f, 50f))
        assertEquals(50f to 0f, anchorOffset(Align.Top, 100f, 50f))
        assertEquals(100f to 0f, anchorOffset(Align.TopRight, 100f, 50f))
        assertEquals(0f to 25f, anchorOffset(Align.Left, 100f, 50f))
        assertEquals(50f to 25f, anchorOffset(Align.Center, 100f, 50f))
        assertEquals(100f to 25f, anchorOffset(Align.Right, 100f, 50f))
        assertEquals(0f to 50f, anchorOffset(Align.BottomLeft, 100f, 50f))
        assertEquals(50f to 50f, anchorOffset(Align.Bottom, 100f, 50f))
        assertEquals(100f to 50f, anchorOffset(Align.BottomRight, 100f, 50f))
    }

    @Test
    fun isAxisAligned_acceptsStraightAnglesOnly() {
        listOf(0f, 90f, -90f, 180f, 270f, 360f).forEach { angle ->
            assertTrue("sudut $angle seharusnya lurus", isAxisAligned(angle))
        }
        listOf(30f, 45f, 60f, 120f, 200f).forEach { angle ->
            assertFalse("sudut $angle seharusnya miring", isAxisAligned(angle))
        }
    }

    @Test
    fun itemCanvasRect_appliesScaleAndAnchor() {
        val source = SceneSource(
            id = "src-1",
            type = SourceType.Image,
            name = "Logo",
            settings = SourceSettings(width = 200, height = 100),
        )
        val item = SceneItem(
            sourceId = source.id,
            transform = Transform(
                x = 500f,
                y = 400f,
                scaleX = 2f,
                scaleY = 2f,
                rotation = 30f,
                align = Align.Center,
            ),
        )
        val scene = Scene(id = "scene-1", name = "Adegan", items = listOf(item))
        val collection = SceneCollection(
            activeSceneId = scene.id,
            scenes = listOf(scene),
            sources = listOf(source),
        )

        val rect = itemCanvasRect(collection, CaptureConfig(), item.id)

        assertNotNull(rect)
        // 200x100 diskalakan 2x menjadi 400x200, jangkar tengah di (500,400).
        assertEquals(300f, rect!!.left, 0.001f)
        assertEquals(300f, rect.top, 0.001f)
        assertEquals(400f, rect.width, 0.001f)
        assertEquals(200f, rect.height, 0.001f)
        assertEquals(30f, rect.rotation, 0.001f)
    }

    @Test
    fun naturalSize_screenFollowsCanvas() {
        val screen = SceneSource(type = SourceType.Screen, name = "Layar")
        val canvas = CaptureConfig(width = 2400, height = 1080)

        assertEquals(2400f to 1080f, naturalSize(screen, canvas))
    }

    @Test
    fun naturalSize_textUsesConfiguredWidthAndTextSize() {
        val text = SceneSource(
            type = SourceType.Text,
            name = "Judul",
            settings = SourceSettings(textSize = 50f, width = 600, height = 0),
        )

        val (width, height) = naturalSize(text, CaptureConfig())

        assertEquals(600f, width, 0.001f)
        assertEquals(70f, height, 0.001f)
    }

    @Test
    fun itemCanvasRect_cropShrinksBoxLikeObs() {
        val source = SceneSource(
            id = "src-1",
            type = SourceType.Image,
            name = "Logo",
            settings = SourceSettings(width = 200, height = 100),
        )
        val item = SceneItem(
            sourceId = source.id,
            transform = Transform(x = 500f, y = 400f, align = Align.Center),
            crop = Crop(left = 0.1f, right = 0.2f),
        )
        val scene = Scene(id = "scene-1", name = "Adegan", items = listOf(item))
        val collection = SceneCollection(
            activeSceneId = scene.id,
            scenes = listOf(scene),
            sources = listOf(source),
        )

        val rect = itemCanvasRect(collection, CaptureConfig(), item.id)

        // 200 px dipotong 10% kiri + 20% kanan = 140 px (tidak diregangkan).
        assertEquals(140f, rect!!.width, 0.001f)
        assertEquals(100f, rect.height, 0.001f)
        assertEquals(430f, rect.left, 0.001f)
    }

    @Test
    fun hitTestItems_respectsRotation() {
        val source = SceneSource(
            id = "src-1",
            type = SourceType.Image,
            name = "Logo",
            settings = SourceSettings(width = 200, height = 100),
        )
        val item = SceneItem(
            sourceId = source.id,
            transform = Transform(
                x = 500f,
                y = 400f,
                rotation = 90f,
                align = Align.Center,
            ),
        )
        val scene = Scene(id = "scene-1", name = "Adegan", items = listOf(item))
        val collection = SceneCollection(
            activeSceneId = scene.id,
            scenes = listOf(scene),
            sources = listOf(source),
        )

        // Setelah diputar 90°, sumbu panjang item menjadi vertikal.
        assertEquals(1, hitTestItems(collection, CaptureConfig(), 500f, 300f).size)
        assertEquals(0, hitTestItems(collection, CaptureConfig(), 400f, 400f).size)
    }

    @Test
    fun adjustCrop_clampsAndKeepsOppositeSide() {
        val crop = Crop(left = 0.1f, right = 0.1f, top = 0f, bottom = 0f)

        val widened = crop.adjustCrop(CropEdge.Left, -0.5f)
        assertEquals(0f, widened.left, 0.001f)

        val tooFar = crop.adjustCrop(CropEdge.Left, 0.95f)
        assertEquals(0.88f, tooFar.left, 0.001f)
        assertEquals(0.1f, tooFar.right, 0.001f)
    }
}
