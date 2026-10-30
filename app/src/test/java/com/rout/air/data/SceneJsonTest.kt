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
import org.junit.Assert.assertEquals
import org.junit.Test

/** Menguji roundtrip JSON dan kompatibilitas nilai lama. */
class SceneJsonTest {
    private fun sample(): SceneCollection {
        val screen = SceneSource(
            id = "src-screen",
            type = SourceType.Screen,
            name = "Layar",
        )
        val image = SceneSource(
            id = "src-logo",
            type = SourceType.Image,
            name = "Logo",
            settings = SourceSettings(
                imagePath = "/data/logo.png",
                width = 320,
                height = 180,
            ),
        )
        val scene = Scene(
            id = "scene-1",
            name = "Adegan 1",
            items = listOf(
                SceneItem(
                    id = "item-screen",
                    sourceId = screen.id,
                    z = 0,
                    locked = true,
                ),
                SceneItem(
                    id = "item-logo",
                    sourceId = image.id,
                    z = 1,
                    visible = true,
                    blend = BlendMode.Multiply,
                    opacity = 0.6f,
                    transform = Transform(
                        x = 120.5f,
                        y = 240f,
                        scaleX = 1.25f,
                        scaleY = 0.75f,
                        rotation = 33.5f,
                        align = Align.Center,
                        flipH = true,
                        flipV = false,
                    ),
                    crop = Crop(left = 0.1f, top = 0.05f, right = 0.2f, bottom = 0.15f),
                ),
            ),
        )
        return SceneCollection(
            version = 2,
            activeSceneId = scene.id,
            scenes = listOf(scene),
            sources = listOf(screen, image),
        )
    }

    @Test
    fun encodeDecode_roundTripPreservesEverything() {
        val original = sample()

        val decoded = SceneJson.decode(SceneJson.encode(original))

        assertEquals(original, decoded)
    }

    @Test
    fun decode_unknownBlendFallsBackToNormal() {
        val text = """
            {
              "version": 2,
              "activeSceneId": "scene-1",
              "sources": [
                {"id": "src-1", "type": "Color", "name": "Warna", "settings": {}}
              ],
              "scenes": [
                {
                  "id": "scene-1",
                  "name": "Adegan",
                  "items": [
                    {"id": "item-1", "sourceId": "src-1", "blend": "TidakDikenal"}
                  ]
                }
              ]
            }
        """.trimIndent()

        val decoded = SceneJson.decode(text)

        assertEquals(
            BlendMode.Normal,
            decoded.scenes.first().items.first().blend,
        )
    }

    @Test
    fun decode_oldVersionWithoutBlendUsesDefault() {
        val text = """
            {
              "version": 1,
              "activeSceneId": "scene-1",
              "sources": [
                {"id": "src-1", "type": "Screen", "name": "Layar"}
              ],
              "scenes": [
                {
                  "id": "scene-1",
                  "name": "Adegan",
                  "items": [{"id": "item-1", "sourceId": "src-1"}]
                }
              ]
            }
        """.trimIndent()

        val decoded = SceneJson.decode(text)
        val item = decoded.scenes.first().items.first()

        assertEquals(1, decoded.version)
        assertEquals(BlendMode.Normal, item.blend)
        assertEquals(1f, item.transform.scaleX)
    }
}
