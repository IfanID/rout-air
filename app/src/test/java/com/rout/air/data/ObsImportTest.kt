package com.rout.air.data

import com.rout.air.core.model.Align
import com.rout.air.core.model.Scene
import com.rout.air.core.model.SceneCollection
import com.rout.air.core.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.rout.air.data.scene.ObsImport

class ObsImportTest {
    private val sample = """
        {
          "current_scene": "Gameplay",
          "sources": [
            {
              "name": "Gameplay",
              "id": "scene",
              "settings": {
                "items": [
                  {
                    "name": "Display 1",
                    "visible": true,
                    "pos": {"x": 100, "y": 50},
                    "scale": {"x": 0.5, "y": 0.5},
                    "align": 0
                  },
                  {
                    "name": "Judul",
                    "visible": true,
                    "pos": {"x": 20, "y": 30},
                    "scale": {"x": 1.0, "y": 1.0},
                    "align": 5
                  },
                  {
                    "name": "Warna",
                    "visible": true,
                    "pos": {"x": 0, "y": 0}
                  },
                  {
                    "name": "Webcam",
                    "visible": true
                  },
                  {
                    "name": "NDI",
                    "visible": true
                  }
                ]
              }
            },
            {
              "name": "Mulai",
              "id": "scene",
              "settings": {"items": []}
            },
            {"name": "Display 1", "id": "monitor_capture", "width": 1920, "height": 1080},
            {
              "name": "Judul",
              "id": "text_gdiplus_v2",
              "settings": {"text": "Halo", "font": {"size": 72}}
            },
            {"name": "Webcam", "id": "dshow_input"},
            {"name": "NDI", "id": "ndi_source"},
            {"name": "Warna", "id": "color_source_v3", "settings": {"color": 4278255360}}
          ]
        }
    """.trimIndent()

    @Test
    fun `memetakan sumber yang didukung dan melewati yang tidak`() {
        val report = ObsImport.parse(sample, 1080, 1920)
        val collection = report.collection
        assertNotNull(collection)
        collection ?: return

        assertEquals(2, report.scenes)
        assertEquals(4, collection.sources.size)
        assertEquals(SourceType.Screen, collection.sources.first { it.name == "Display 1" }.type)
        assertEquals(SourceType.Text, collection.sources.first { it.name == "Judul" }.type)
        assertEquals(SourceType.Color, collection.sources.first { it.name == "Warna" }.type)

        val gameplay = collection.scenes.first { it.name == "Gameplay" }
        assertEquals(4, gameplay.items.size)
        val display = gameplay.items.first { item ->
            collection.source(item.sourceId)?.name == "Display 1"
        }
        assertEquals(Align.TopLeft, display.transform.align)
        assertEquals(0.5f, display.transform.scaleX, 0.001f)

        val title = gameplay.items.first { item ->
            collection.source(item.sourceId)?.name == "Judul"
        }
        assertEquals(Align.Right, title.transform.align)

        // Kamera OBS kini dipetakan; sumber NDI tetap dilaporkan dilewati.
        assertEquals(
            SourceType.Camera,
            collection.sources.first { it.name == "Webcam" }.type,
        )
        assertTrue(report.skipped.any { it.name == "NDI" })
        assertEquals("Gameplay", collection.activeScene()?.name)
    }

    @Test
    fun `impor ulang tidak menimpa scene dengan nama sama`() {
        val imported = ObsImport.parse(sample, 1080, 1920).collection!!
        val existing = SceneCollection(
            activeSceneId = "scene-lama",
            scenes = listOf(Scene(id = "scene-lama", name = "Gameplay")),
            sources = emptyList(),
        )

        val merged = ObsImport.merge(existing, imported)
        assertEquals(3, merged.scenes.size)
        assertEquals("Gameplay", merged.scenes.first().name)
        assertEquals("Gameplay (OBS)", merged.scenes[1].name)
        assertEquals("scene-lama", merged.activeSceneId)
    }

    @Test
    fun `berkas tanpa sources dilaporkan gagal`() {
        val report = ObsImport.parse("{\"current_scene\":\"X\"}", 1080, 1920)
        assertEquals(null, report.collection)
        assertNotNull(report.error)
    }
}
