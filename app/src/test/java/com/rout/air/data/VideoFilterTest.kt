package com.rout.air.data

import com.rout.air.core.model.Scene
import com.rout.air.core.model.SceneCollection
import com.rout.air.core.model.SceneItem
import com.rout.air.core.model.SceneSource
import com.rout.air.core.model.SourceType
import com.rout.air.core.model.VideoFilter
import com.rout.air.engine.filter.FilterState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.rout.air.core.model.chromaKey
import com.rout.air.data.scene.SceneJson

/** Filter GPU: serialisasi JSON dan pemetaan ke nilai shader. */
class VideoFilterTest {

    private fun collectionWithFilters(): SceneCollection {
        val source = SceneSource(
            id = "src-logo",
            type = SourceType.Image,
            name = "Logo",
            filters = listOf(
                VideoFilter.ChromaKey(
                    color = 0xFF00FF00,
                    similarity = 0.42f,
                    smoothness = 0.11f,
                    spill = 0.23f,
                ),
                VideoFilter.ColorCorrection(
                    brightness = 1.2f,
                    contrast = 0.9f,
                    saturation = 1.35f,
                    gamma = 1.1f,
                    hueDegrees = 30f,
                ),
            ),
        )
        val scene = Scene(
            id = "scene-1",
            name = "Adegan 1",
            items = listOf(SceneItem(id = "item-1", sourceId = source.id)),
        )
        return SceneCollection(activeSceneId = scene.id, scenes = listOf(scene), sources = listOf(source))
    }

    @Test
    fun jsonRoundTripKeepsFilters() {
        val original = collectionWithFilters()
        val decoded = SceneJson.decode(SceneJson.encode(original))
        assertEquals(original.sources.first().filters, decoded.sources.first().filters)
    }

    @Test
    fun sceneWithoutFiltersStaysEmpty() {
        val decoded = SceneJson.decode(
            SceneJson.encode(
                SceneCollection(
                    activeSceneId = "scene-1",
                    scenes = listOf(
                        Scene(
                            id = "scene-1",
                            name = "Adegan 1",
                            items = listOf(SceneItem(id = "i", sourceId = "s")),
                        ),
                    ),
                    sources = listOf(
                        SceneSource(id = "s", type = SourceType.Color, name = "Warna"),
                    ),
                ),
            ),
        )
        assertTrue(decoded.sources.first().filters.isEmpty())
    }

    @Test
    fun filterStateCopiesValuesForShader() {
        val state = FilterState.from(collectionWithFilters().sources.first().filters)
        assertTrue(state.chroma)
        assertTrue(state.adjust)
        assertEquals(0f, state.keyR, 0.001f)
        assertEquals(1f, state.keyG, 0.001f)
        assertEquals(0f, state.keyB, 0.001f)
        assertEquals(0.42f, state.similarity, 0.001f)
        assertEquals(1.2f, state.brightness, 0.001f)
    }

    @Test
    fun emptyFiltersUseNeutralState() {
        val state = FilterState.from(emptyList())
        assertFalse(state.chroma)
        assertFalse(state.adjust)
        assertEquals(1f, state.brightness, 0.001f)
        assertEquals(0f, state.hueRadians, 0.001f)
    }

    @Test
    fun filterHelpersPickFirstOfEachType() {
        val filters = collectionWithFilters().sources.first().filters
        assertEquals(0.42f, filters.chromaKey()?.similarity ?: 0f, 0.001f)
    }
}
