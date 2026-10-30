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
import com.rout.air.data.duplicateItem
import com.rout.air.data.moveLayer
import com.rout.air.data.moveLayerToEdge
import com.rout.air.data.removeItem
import com.rout.air.data.removeSource
import com.rout.air.data.updateItem
import kotlin.math.min
import kotlin.math.roundToInt

/** Halaman menu konteks: utama, submenu Transformasi, Urutan, dan Blend. */
private enum class MenuPage { Main, Transform, Order, Blend }

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
                    text = { Text("Transformasi  ›") },
                    onClick = { page = MenuPage.Transform },
                )
                DropdownMenuItem(
                    text = { Text("Urutan  ›") },
                    onClick = { page = MenuPage.Order },
                )
                DropdownMenuItem(
                    text = { Text("Blend: ${item.blend.label}  ›") },
                    onClick = { page = MenuPage.Blend },
                )
                if (source?.type != SourceType.Screen) {
                    // Layar tidak boleh diduplikat: satu sumber tangkap layar
                    // sudah cukup, duplikatnya hanya membuat tumpukan penuh.
                    DropdownMenuItem(
                        text = { Text("Duplikat") },
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
                    text = { Text(if (item.locked) "Buka kunci" else "Kunci") },
                    onClick = { applyItem(item.copy(locked = !item.locked)) },
                )
                DropdownMenuItem(
                    text = { Text(if (item.visible) "Sembunyikan" else "Tampilkan") },
                    onClick = { applyItem(item.copy(visible = !item.visible)) },
                )
                DropdownMenuItem(
                    text = { Text("Hapus") },
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
                    text = { Text("‹ Kembali") },
                    onClick = { page = MenuPage.Main },
                )
                DropdownMenuItem(
                    text = { Text("Flip horizontal") },
                    onClick = {
                        applyItem(
                            item.copy(
                                transform = item.transform.copy(flipH = !item.transform.flipH),
                            ),
                        )
                    },
                )
                DropdownMenuItem(
                    text = { Text("Flip vertikal") },
                    onClick = {
                        applyItem(
                            item.copy(
                                transform = item.transform.copy(flipV = !item.transform.flipV),
                            ),
                        )
                    },
                )
                DropdownMenuItem(
                    text = { Text("Putar 90° searah jam") },
                    onClick = { applyItem(rotateBy(item, 90f)) },
                )
                DropdownMenuItem(
                    text = { Text("Putar 90° berlawanan") },
                    onClick = { applyItem(rotateBy(item, -90f)) },
                )
                DropdownMenuItem(
                    text = { Text("Putar 180°") },
                    onClick = { applyItem(rotateBy(item, 180f)) },
                )
                DropdownMenuItem(
                    text = { Text("Fit to screen") },
                    onClick = { applyItem(fitToScreen(item, source, canvas)) },
                )
                DropdownMenuItem(
                    text = { Text("Stretch to screen") },
                    onClick = { applyItem(stretchToScreen(item, source, canvas)) },
                )
                DropdownMenuItem(
                    text = { Text("Center to screen") },
                    onClick = { applyItem(centerToScreen(item, canvas)) },
                )
                DropdownMenuItem(
                    text = { Text("Reset transform") },
                    onClick = {
                        val reset = source?.let { defaultTransform(it, canvas) } ?: item.transform
                        applyItem(item.copy(transform = reset))
                    },
                )
                DropdownMenuItem(
                    text = { Text("Reset posisi") },
                    onClick = {
                        // Hanya X/Y yang dikembalikan; skala, rotasi, dan crop
                        // tetap seperti hasil pengaturan sebelumnya.
                        val base = source?.let { defaultTransform(it, canvas) }
                            ?: item.transform
                        applyItem(
                            item.copy(transform = item.transform.copy(x = base.x, y = base.y)),
                        )
                    },
                )
                DropdownMenuItem(
                    text = { Text("Reset rotasi") },
                    onClick = {
                        applyItem(item.copy(transform = item.transform.copy(rotation = 0f)))
                    },
                )
                DropdownMenuItem(
                    text = { Text("Reset crop") },
                    onClick = { applyItem(item.copy(crop = Crop())) },
                )
                DropdownMenuItem(
                    text = { Text("Reset semua (transform + crop)") },
                    onClick = {
                        val reset = source?.let { defaultTransform(it, canvas) } ?: item.transform
                        applyItem(item.copy(transform = reset, crop = Crop()))
                    },
                )
            }

            MenuPage.Order -> {
                DropdownMenuItem(
                    text = { Text("‹ Kembali") },
                    onClick = { page = MenuPage.Main },
                )
                DropdownMenuItem(
                    text = { Text("Naikkan satu lapis") },
                    onClick = { apply(collection.moveLayer(item.id, +1)) },
                )
                DropdownMenuItem(
                    text = { Text("Turunkan satu lapis") },
                    onClick = { apply(collection.moveLayer(item.id, -1)) },
                )
                DropdownMenuItem(
                    text = { Text("Ke paling depan") },
                    onClick = { apply(collection.moveLayerToEdge(item.id, top = true)) },
                )
                DropdownMenuItem(
                    text = { Text("Ke paling belakang") },
                    onClick = { apply(collection.moveLayerToEdge(item.id, top = false)) },
                )
            }

            MenuPage.Blend -> {
                DropdownMenuItem(
                    text = { Text("‹ Kembali") },
                    onClick = { page = MenuPage.Main },
                )
                BlendMode.values().forEach { mode ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (item.blend == mode) "✓ ${mode.label}" else mode.label,
                            )
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
