package com.rout.air.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import com.rout.air.core.Align
import com.rout.air.core.BlendMode
import com.rout.air.core.CaptureConfig
import com.rout.air.core.Crop
import com.rout.air.core.SceneCollection
import com.rout.air.core.SceneItem
import com.rout.air.core.SceneSource
import com.rout.air.core.SourceType
import com.rout.air.core.VideoFilter
import com.rout.air.core.chromaKey
import com.rout.air.core.colorCorrection
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.ui.unit.dp
import com.rout.air.data.duplicateItem
import com.rout.air.data.moveLayer
import com.rout.air.data.moveLayerToEdge
import com.rout.air.data.removeItem
import com.rout.air.data.removeSource
import com.rout.air.data.updateItem
import com.rout.air.data.updateSource
import kotlin.math.min
import kotlin.math.roundToInt
import androidx.compose.ui.res.stringResource
import com.rout.air.R

/** Halaman menu konteks: utama, submenu Transformasi, Urutan, dan Blend. */
private enum class MenuPage { Main, Transform, Order, Blend, Filters }

/**
 * Menempatkan menu konteks pada titik ketukan di dalam pratinjau. Dipakai
 * pratinjau Compose (tanpa sesi) dan panel properti.
 */
@Composable
internal fun ItemMenuAnchor(
    target: Pair<SceneItem, Offset>?,
    collection: SceneCollection,
    canvas: CaptureConfig,
    onDismiss: () -> Unit,
    onCollectionChange: (SceneCollection) -> Unit,
    onSelectItem: (String?) -> Unit,
) {
    val value = target ?: return
    Box(
        modifier = Modifier.offset {
            IntOffset(value.second.x.roundToInt(), value.second.y.roundToInt())
        },
    ) {
        ItemContextMenu(
            collection = collection,
            item = value.first,
            source = collection.source(value.first.sourceId),
            canvas = canvas,
            onDismiss = onDismiss,
            onCollectionChange = onCollectionChange,
            onSelectItem = onSelectItem,
        )
    }
}

/**
 * Menu konteks item di pratinjau, meniru klik-kanan scene item di OBS:
 * Transformasi (flip/putar/fit/reset), Urutan lapisan, Duplikat, kunci,
 * sembunyikan, dan hapus.
 */
@Composable
internal fun ItemContextMenu(
    collection: SceneCollection,
    item: SceneItem,
    source: SceneSource?,
    canvas: CaptureConfig,
    onDismiss: () -> Unit,
    onCollectionChange: (SceneCollection) -> Unit,
    onSelectItem: (String?) -> Unit,
) {
    var page by remember(item.id) { mutableStateOf(MenuPage.Main) }

    fun close() {
        page = MenuPage.Main
        onDismiss()
    }

    fun apply(next: SceneCollection) {
        onCollectionChange(next)
        close()
    }

    fun applyItem(next: SceneItem) = apply(collection.updateItem(next))

    DropdownMenu(expanded = true, onDismissRequest = { close() }) {
        when (page) {
            MenuPage.Main -> {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.scenes_transformasi)) },
                    onClick = { page = MenuPage.Transform },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.scenes_urutan)) },
                    onClick = { page = MenuPage.Order },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.scenes_blend_n, blendLabel(item.blend))) },
                    onClick = { page = MenuPage.Blend },
                )
                if (source != null && source.type != SourceType.AudioInput &&
                    source.type != SourceType.AudioOutput
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (source.filters.isNotEmpty()) {
                                    stringResource(R.string.item_filter_active)
                                } else {
                                    stringResource(R.string.item_filter)
                                },
                            )
                        },
                        onClick = { page = MenuPage.Filters },
                    )
                }
                if (source?.type != SourceType.Screen) {
                    // Layar tidak boleh diduplikat: satu sumber tangkap layar
                    // sudah cukup, duplikatnya hanya membuat tumpukan penuh.
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.scenes_duplikat)) },
                        onClick = {
                            val (next, newId) = collection.duplicateItem(item.id)
                            if (newId != null) {
                                onCollectionChange(next)
                                onSelectItem(newId)
                            }
                            close()
                        },
                    )
                }
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                if (item.locked) R.string.item_unlock
                                else R.string.item_lock,
                            ),
                        )
                    },
                    onClick = { applyItem(item.copy(locked = !item.locked)) },
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                if (item.visible) R.string.icon_hide
                                else R.string.icon_show,
                            ),
                        )
                    },
                    onClick = { applyItem(item.copy(visible = !item.visible)) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.common_hapus)) },
                    onClick = {
                        var next = collection.removeItem(item.id)
                        // Sumber yang tidak lagi dipakai item mana pun ikut
                        // dibersihkan supaya tidak menumpuk di JSON.
                        if (next.scenes.none { s -> s.items.any { it.sourceId == item.sourceId } }) {
                            next = next.removeSource(item.sourceId)
                        }
                        onCollectionChange(next)
                        onSelectItem(null)
                        close()
                    },
                )
            }

            MenuPage.Transform -> {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.common_kembali_panah)) },
                    onClick = { page = MenuPage.Main },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.scenes_flip_horizontal)) },
                    onClick = {
                        applyItem(
                            item.copy(
                                transform = item.transform.copy(flipH = !item.transform.flipH),
                            ),
                        )
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.scenes_flip_vertikal)) },
                    onClick = {
                        applyItem(
                            item.copy(
                                transform = item.transform.copy(flipV = !item.transform.flipV),
                            ),
                        )
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.scenes_putar_90_searah_jam)) },
                    onClick = { applyItem(rotateBy(item, 90f)) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.scenes_putar_90_berlawanan)) },
                    onClick = { applyItem(rotateBy(item, -90f)) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.scenes_putar_180)) },
                    onClick = { applyItem(rotateBy(item, 180f)) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.scenes_fit_to_screen)) },
                    onClick = { applyItem(fitToScreen(item, source, canvas)) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.scenes_stretch_to_screen)) },
                    onClick = { applyItem(stretchToScreen(item, source, canvas)) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.scenes_center_to_screen)) },
                    onClick = { applyItem(centerToScreen(item, canvas)) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.scenes_reset_posisi)) },
                    onClick = {
                        // Hanya lokasi X/Y; skala, rotasi, dan crop tetap.
                        val reset = source?.let { defaultTransform(it, canvas) }
                            ?: item.transform
                        applyItem(
                            item.copy(transform = item.transform.copy(x = reset.x, y = reset.y)),
                        )
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.scenes_reset_scale)) },
                    onClick = {
                        // Hanya skala yang dikembalikan ke ukuran awal.
                        val reset = source?.let { defaultTransform(it, canvas) }
                            ?: item.transform
                        applyItem(
                            item.copy(
                                transform = item.transform.copy(
                                    scaleX = reset.scaleX,
                                    scaleY = reset.scaleY,
                                ),
                            ),
                        )
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.scenes_reset_rotasi)) },
                    onClick = {
                        applyItem(item.copy(transform = item.transform.copy(rotation = 0f)))
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.scenes_reset_transformasi)) },
                    onClick = {
                        // Semua kembali ke kondisi awal, termasuk crop.
                        val reset = source?.let { defaultTransform(it, canvas) }
                            ?: item.transform
                        applyItem(item.copy(transform = reset, crop = Crop()))
                    },
                )
            }

            MenuPage.Order -> {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.common_kembali_panah)) },
                    onClick = { page = MenuPage.Main },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.scenes_naikkan_satu_lapis)) },
                    onClick = { apply(collection.moveLayer(item.id, +1)) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.scenes_turunkan_satu_lapis)) },
                    onClick = { apply(collection.moveLayer(item.id, -1)) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.scenes_ke_paling_depan)) },
                    onClick = { apply(collection.moveLayerToEdge(item.id, top = true)) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.scenes_ke_paling_belakang)) },
                    onClick = { apply(collection.moveLayerToEdge(item.id, top = false)) },
                )
            }

            MenuPage.Filters -> {
                val currentSource = source
                if (currentSource == null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.item_source_missing)) },
                        onClick = { page = MenuPage.Main },
                    )
                } else {
                    val chroma = currentSource.filters.chromaKey()
                    val color = currentSource.filters.colorCorrection()

                    fun setFilters(next: List<VideoFilter>) {
                        onCollectionChange(
                            collection.updateSource(currentSource.copy(filters = next)),
                        )
                    }

                    fun replace(transform: (VideoFilter) -> VideoFilter) {
                        setFilters(currentSource.filters.map(transform))
                    }

                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.common_kembali_panah)) },
                        onClick = { page = MenuPage.Main },
                    )
                    Column(
                        modifier = Modifier
                            .width(300.dp)
                            .padding(horizontal = 14.dp, vertical = 4.dp),
                    ) {
                        Text(
                            stringResource(R.string.item_chroma_key),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        FilterChip(
                            selected = chroma != null,
                            onClick = {
                                if (chroma == null) {
                                    setFilters(currentSource.filters + VideoFilter.ChromaKey())
                                } else {
                                    setFilters(currentSource.filters - chroma)
                                }
                            },
                            label = {
                            Text(
                                stringResource(
                                    if (chroma == null) R.string.common_nonaktif
                                    else R.string.common_aktif,
                                ),
                            )
                        },
                        )
                        if (chroma != null) {
                            Text(
                                stringResource(
                                    R.string.item_chroma_similarity,
                                    "%.2f".format(java.util.Locale.US, chroma.similarity),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Slider(
                                value = chroma.similarity,
                                onValueChange = { value ->
                                    replace { filter ->
                                        if (filter is VideoFilter.ChromaKey) {
                                            filter.copy(similarity = value)
                                        } else {
                                            filter
                                        }
                                    }
                                },
                                valueRange = 0f..1f,
                            )
                            Text(
                                stringResource(
                                    R.string.item_chroma_smoothness,
                                    "%.2f".format(java.util.Locale.US, chroma.smoothness),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Slider(
                                value = chroma.smoothness,
                                onValueChange = { value ->
                                    replace { filter ->
                                        if (filter is VideoFilter.ChromaKey) {
                                            filter.copy(smoothness = value)
                                        } else {
                                            filter
                                        }
                                    }
                                },
                                valueRange = 0f..0.5f,
                            )
                            Text(
                                stringResource(
                                    R.string.item_chroma_spill,
                                    "%.2f".format(java.util.Locale.US, chroma.spill),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Slider(
                                value = chroma.spill,
                                onValueChange = { value ->
                                    replace { filter ->
                                        if (filter is VideoFilter.ChromaKey) {
                                            filter.copy(spill = value)
                                        } else {
                                            filter
                                        }
                                    }
                                },
                                valueRange = 0f..1f,
                            )
                        }

                        Text(
                            stringResource(R.string.item_color_correction),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        FilterChip(
                            selected = color != null,
                            onClick = {
                                if (color == null) {
                                    setFilters(
                                        currentSource.filters + VideoFilter.ColorCorrection(),
                                    )
                                } else {
                                    setFilters(currentSource.filters - color)
                                }
                            },
                            label = {
                            Text(
                                stringResource(
                                    if (color == null) R.string.common_nonaktif
                                    else R.string.common_aktif,
                                ),
                            )
                        },
                        )
                        if (color != null) {
                            Text(
                                stringResource(
                                    R.string.item_brightness,
                                    "%.2f".format(java.util.Locale.US, color.brightness),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Slider(
                                value = color.brightness,
                                onValueChange = { value ->
                                    replace { filter ->
                                        if (filter is VideoFilter.ColorCorrection) {
                                            filter.copy(brightness = value)
                                        } else {
                                            filter
                                        }
                                    }
                                },
                                valueRange = 0.5f..2f,
                            )
                            Text(
                                stringResource(
                                    R.string.item_contrast,
                                    "%.2f".format(java.util.Locale.US, color.contrast),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Slider(
                                value = color.contrast,
                                onValueChange = { value ->
                                    replace { filter ->
                                        if (filter is VideoFilter.ColorCorrection) {
                                            filter.copy(contrast = value)
                                        } else {
                                            filter
                                        }
                                    }
                                },
                                valueRange = 0.5f..2f,
                            )
                            Text(
                                stringResource(
                                    R.string.item_saturation,
                                    "%.2f".format(java.util.Locale.US, color.saturation),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Slider(
                                value = color.saturation,
                                onValueChange = { value ->
                                    replace { filter ->
                                        if (filter is VideoFilter.ColorCorrection) {
                                            filter.copy(saturation = value)
                                        } else {
                                            filter
                                        }
                                    }
                                },
                                valueRange = 0f..2f,
                            )
                        }
                    }
                }
            }

            MenuPage.Blend -> {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.common_kembali_panah)) },
                    onClick = { page = MenuPage.Main },
                )
                BlendMode.values().forEach { mode ->
                    DropdownMenuItem(
                        text = {
                            val name = blendLabel(mode)
                            Text(if (item.blend == mode) "✓ $name" else name)
                        },
                        onClick = { applyItem(item.copy(blend = mode)) },
                    )
                }
            }
        }
    }
}

/** Rotasi bertambah, dinormalkan ke rentang -180..180 seperti panel properti. */
private fun rotateBy(item: SceneItem, degrees: Float): SceneItem {
    val rotation = ((item.transform.rotation + degrees + 540f) % 360f) - 180f
    return item.copy(transform = item.transform.copy(rotation = rotation))
}

/** Pas di dalam kanvas tanpa memotong, menjaga rasio (seperti OBS). */
private fun fitToScreen(
    item: SceneItem,
    source: SceneSource?,
    canvas: CaptureConfig,
): SceneItem {
    if (source == null) return item
    val (width, height) = croppedNaturalSize(source, item, canvas)
    if (width <= 0f || height <= 0f) return item
    val scale = min(canvas.width / width, canvas.height / height)
    return item.copy(
        transform = item.transform.copy(
            x = canvas.width / 2f,
            y = canvas.height / 2f,
            scaleX = scale,
            scaleY = scale,
            align = Align.Center,
        ),
    )
}

/** Penuhkan kanvas tanpa menjaga rasio. */
private fun stretchToScreen(
    item: SceneItem,
    source: SceneSource?,
    canvas: CaptureConfig,
): SceneItem {
    if (source == null) return item
    val (width, height) = croppedNaturalSize(source, item, canvas)
    if (width <= 0f || height <= 0f) return item
    return item.copy(
        transform = item.transform.copy(
            x = canvas.width / 2f,
            y = canvas.height / 2f,
            scaleX = canvas.width / width,
            scaleY = canvas.height / height,
            align = Align.Center,
        ),
    )
}

/** Pertahankan ukuran, pindahkan titik jangkar ke tengah kanvas. */
private fun centerToScreen(item: SceneItem, canvas: CaptureConfig): SceneItem =
    item.copy(
        transform = item.transform.copy(
            x = canvas.width / 2f,
            y = canvas.height / 2f,
            align = Align.Center,
        ),
    )

/** Label blend mode dari resource, menggantikan label enum di core. */
@Composable
private fun blendLabel(mode: BlendMode): String = when (mode) {
    BlendMode.Normal -> stringResource(R.string.scenes_blend_normal)
    BlendMode.Add -> stringResource(R.string.scenes_blend_add)
    BlendMode.Screen -> stringResource(R.string.scenes_blend_screen)
    BlendMode.Multiply -> stringResource(R.string.scenes_blend_multiply)
}
