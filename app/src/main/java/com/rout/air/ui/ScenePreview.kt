package com.rout.air.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.rout.air.core.Align
import com.rout.air.core.CaptureConfig
import com.rout.air.core.Crop
import com.rout.air.core.SceneCollection
import com.rout.air.core.SceneItem
import com.rout.air.core.SceneSource
import com.rout.air.core.SourceType
import com.rout.air.engine.SourceBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Pratinjau susunan scene di halaman Adegan, tanpa perlu pindah ke Studio.
 *
 * Sumber layar digambar sebagai kotak pola karena gambar layar langsung baru
 * tersedia saat sesi MediaProjection aktif. Gambar, teks, dan warna tampil
 * sesuai transformasi, sehingga geser/cubit/putar bisa langsung di sini.
 */
@Composable
fun ScenePreview(
    canvas: CaptureConfig,
    collection: SceneCollection,
    selectedItemId: String?,
    onSelectItem: (String?) -> Unit,
    onTransform: (String?, Float, Float, Float, Float, Float) -> Unit,
    onItemMenu: ((SceneItem, Offset) -> Unit)? = null,
    /** Menarik pegangan crop di tepi item terpilih. */
    onCropDrag: ((String, Crop) -> Unit)? = null,
    /** Jika diisi, gestur hanya untuk item ini (dipakai panel properti). */
    gestureTargetItemId: String? = null,
    modifier: Modifier = Modifier,
) {
    val sources = collection.sources
    val density = LocalDensity.current.density
    val bitmaps by produceState<Map<String, ImageBitmap>>(
        initialValue = emptyMap(),
        sources,
        canvas.width,
        canvas.height,
        density,
    ) {
        value = withContext(Dispatchers.Default) {
            sources.mapNotNull { source ->
                val bitmap = when (source.type) {
                    SourceType.Image -> SourceBitmap.loadImage(
                        path = source.settings.imagePath,
                        maxWidth = canvas.width,
                        maxHeight = canvas.height,
                    )

                    SourceType.Text -> SourceBitmap.renderText(
                        text = source.settings.text,
                        textSizeSp = source.settings.textSize,
                        colorArgb = source.settings.textColor,
                        maxWidth = source.settings.width.takeIf { it > 0 } ?: canvas.width,
                        density = density,
                    )

                    else -> null
                }
                bitmap?.let { source.id to it.asImageBitmap() }
            }.toMap()
        }
    }

    val scene = collection.activeScene()
    val layers = remember(scene, sources, bitmaps, canvas) {
        buildPreviewLayers(scene?.items.orEmpty(), sources, bitmaps, canvas)
    }
    // Saat mengedit satu sumber (panel properti), gambar item itu paling
    // akhir agar tidak tertutup sumber layar penuh. Item lain diredupkan
    // supaya yang sedang diedit tetap terlihat.
    val drawLayers = remember(layers, gestureTargetItemId) {
        if (gestureTargetItemId == null) {
            layers
        } else {
            layers.sortedBy { if (it.item.id == gestureTargetItemId) 1 else 0 }
        }
    }

    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val aspect = canvas.aspectRatio
        var frameWidth = maxWidth
        var frameHeight = frameWidth / aspect
        if (frameHeight > maxHeight) {
            frameHeight = maxHeight
            frameWidth = frameHeight * aspect
        }

        val frameWidthPx = with(LocalDensity.current) { frameWidth.toPx() }
        val frameHeightPx = with(LocalDensity.current) { frameHeight.toPx() }

        val transformState = rememberUpdatedState(onTransform)
        val selectState = rememberUpdatedState(onSelectItem)
        val menuState = rememberUpdatedState(onItemMenu)
        val cropDragState = rememberUpdatedState(onCropDrag)

        Box(modifier = Modifier.fillMaxSize()) {
            // Kotak gambar mengikuti rasio kanvas dan berada di tengah panel.
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(width = frameWidth, height = frameHeight)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black),
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val scale = size.width / canvas.width.toFloat()
                    drawRect(Color(0xFF060708), size = size)

                    drawLayers.forEach { layer ->
                        val transform = layer.item.transform
                        val crop = layer.item.crop
                        val croppedWidth = layer.naturalWidth * (1f - crop.left - crop.right)
                        val croppedHeight = layer.naturalHeight * (1f - crop.top - crop.bottom)
                        val width = croppedWidth.coerceAtLeast(1f) * transform.scaleX * scale
                        val height = croppedHeight.coerceAtLeast(1f) * transform.scaleY * scale
                        val (anchorX, anchorY) = anchorOffset(transform.align, width, height)
                        val left = transform.x * scale - anchorX
                        val top = transform.y * scale - anchorY
                        // Saat mengedit satu sumber, sumber lain diredupkan
                        // agar item yang sedang diedit menonjol.
                        val isEdited = gestureTargetItemId == null ||
                            layer.item.id == gestureTargetItemId
                        val opacity = (layer.item.opacity *
                            if (isEdited) 1f else 0.3f).coerceIn(0f, 1f)

                        withTransform({
                            translate(left, top)
                            rotate(transform.rotation, Offset(width / 2f, height / 2f))
                            if (transform.flipH) scale(-1f, 1f, Offset(width / 2f, height / 2f))
                            if (transform.flipV) scale(1f, -1f, Offset(width / 2f, height / 2f))
                        }) {
                            when (layer.type) {
                                SourceType.Color -> drawRect(
                                    color = Color(layer.colorArgb),
                                    size = Size(width, height),
                                    alpha = opacity,
                                    blendMode = layer.blend,
                                )

                                SourceType.Screen -> drawScreenPlaceholder(width, height, opacity)

                                SourceType.Image, SourceType.Text -> layer.bitmap?.let { image ->
                                    val srcWidth = (image.width * (1f - crop.left - crop.right))
                                        .roundToInt()
                                        .coerceAtLeast(1)
                                    val srcHeight = (image.height * (1f - crop.top - crop.bottom))
                                        .roundToInt()
                                        .coerceAtLeast(1)
                                    drawImage(
                                        image = image,
                                        srcOffset = IntOffset(
                                            (image.width * crop.left).roundToInt(),
                                            (image.height * crop.top).roundToInt(),
                                        ),
                                        srcSize = IntSize(srcWidth, srcHeight),
                                        dstOffset = IntOffset.Zero,
                                        dstSize = IntSize(
                                            width.roundToInt().coerceAtLeast(1),
                                            height.roundToInt().coerceAtLeast(1),
                                        ),
                                        alpha = opacity,
                                        blendMode = layer.blend,
                                    )
                                }
                            }

                            if (layer.item.id == selectedItemId) {
                                drawRect(
                                    color = Color(0xFF00E5FF),
                                    size = Size(width, height),
                                    style = Stroke(width = 2f),
                                )
                                // Pegangan crop di tengah tiap tepi; hanya
                                // item terpilih yang bisa ditarik.
                                if (!layer.item.locked && onCropDrag != null) {
                                    drawCropHandles(width, height)
                                }
                            }
                        }
                    }

                    // Garis bantu rotasi: garis horizontal & vertikal lewat
                    // tengah item, jadi jelas mana arah tegak dan mendatar.
                    // Hanya tampil saat item miring; hilang begitu rotasinya
                    // lurus (0/90/180°).
                    itemCanvasRect(collection, canvas, selectedItemId)?.let { rect ->
                        if (!isAxisAligned(rect.rotation)) {
                            val guideWidth = 1.dp.toPx()
                            val cx = (rect.left + rect.width / 2f) * scale
                            val cy = (rect.top + rect.height / 2f) * scale
                            val guideColor = Color(0xAA00E5FF)
                            drawLine(
                                color = guideColor,
                                start = Offset(0f, cy),
                                end = Offset(size.width, cy),
                                strokeWidth = guideWidth,
                            )
                            drawLine(
                                color = guideColor,
                                start = Offset(cx, 0f),
                                end = Offset(cx, size.height),
                                strokeWidth = guideWidth,
                            )
                        }
                    }
                }

                if (layers.isEmpty()) {
                    Text(
                        text = "Belum ada sumber",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFF2F2F2),
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }

            // Lapisan gestur menutupi seluruh panel: jari kedua boleh mendarat
            // di luar kotak gambar dan cubitan tetap terbaca.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(
                        layers,
                        canvas,
                        collection,
                        selectedItemId,
                        frameWidthPx,
                        frameHeightPx,
                    ) {
                        // Ketuk = pilih, tahan lama / klik dua kali = menu
                        // konteks ala OBS.
                        val offsetLeft = (size.width - frameWidthPx) / 2f
                        val offsetTop = (size.height - frameHeightPx) / 2f
                        val scale = if (canvas.width > 0) frameWidthPx / canvas.width else 0f

                        fun insideFrame(position: Offset): Boolean =
                            scale > 0f &&
                                position.x >= offsetLeft &&
                                position.x <= offsetLeft + frameWidthPx &&
                                position.y >= offsetTop &&
                                position.y <= offsetTop + frameHeightPx

                        fun hitsAt(position: Offset): List<SceneItem> {
                            if (!insideFrame(position)) return emptyList()
                            return hitTestItems(
                                collection,
                                canvas,
                                (position.x - offsetLeft) / scale,
                                (position.y - offsetTop) / scale,
                            )
                        }

                        detectTapGestures(
                            onTap = { position ->
                                if (insideFrame(position)) {
                                    selectState.value(
                                        cycleSelection(hitsAt(position), selectedItemId),
                                    )
                                }
                            },
                            onLongPress = { position ->
                                menuTargetAt(hitsAt(position), selectedItemId)
                                    ?.let { menuState.value?.invoke(it, position) }
                            },
                            onDoubleTap = { position ->
                                menuTargetAt(hitsAt(position), selectedItemId)
                                    ?.let { menuState.value?.invoke(it, position) }
                            },
                        )
                    }
                    .pointerInput(
                        // Jangan pakai `layers`/`collection` sebagai kunci:
                        // keduanya berubah tiap kali item digeser, sehingga
                        // gestur akan terputus di tengah jalan (ini penyebab
                        // pratinjau di panel properti tidak bisa digeser).
                        canvas,
                        gestureTargetItemId,
                        selectedItemId,
                        frameWidthPx,
                        frameHeightPx,
                    ) {
                        // Gestur berlaku untuk item di bawah jari, bukan hanya
                        // item yang sudah terpilih (seperti OBS). Pemilihan
                        // baru dilakukan saat jari benar-benar mulai bergeser,
                        // supaya ketukan tetap bisa memilih item di bawahnya.
                        val offsetLeft = (size.width - frameWidthPx) / 2f
                        val offsetTop = (size.height - frameHeightPx) / 2f
                        val scale = if (canvas.width > 0) frameWidthPx / canvas.width else 0f

                        var activeId: String? = null
                        var pendingSelect = false
                        var cropEdge: CropEdge? = null
                        var cropItemId: String? = null
                        var cropBase: Crop? = null
                        var cropRawWidth = 1f
                        var cropRawHeight = 1f
                        var cropScaleX = 1f
                        var cropScaleY = 1f
                        detectItemTransformGestures(
                            onStart = { offset ->
                                val insideFrame = scale > 0f &&
                                    offset.x >= offsetLeft &&
                                    offset.x <= offsetLeft + frameWidthPx &&
                                    offset.y >= offsetTop &&
                                    offset.y <= offsetTop + frameHeightPx
                                val canvasX = (offset.x - offsetLeft) / scale
                                val canvasY = (offset.y - offsetTop) / scale
                                val hits = if (insideFrame) {
                                    hitTestItems(collection, canvas, canvasX, canvasY)
                                } else {
                                    emptyList()
                                }
                                // Item teratas yang bisa digeser menang. Bila
                                // yang tersentuh justru item terkunci (mis.
                                // sumber layar) atau area kosong, gestur
                                // memakai item yang sedang terpilih supaya
                                // jari boleh berada di luar item.
                                val direct = hits.firstOrNull { !it.locked }
                                val fallback = collection.item(selectedItemId)
                                    ?.takeIf { !it.locked && it.visible }
                                val candidate = if (gestureTargetItemId != null) {
                                    collection.item(gestureTargetItemId)
                                } else {
                                    direct ?: fallback
                                }
                                activeId = candidate?.id
                                pendingSelect = gestureTargetItemId == null && direct != null

                                // Sentuhan tepat di pegangan crop mengubah crop,
                                // bukan menggeser item.
                                cropEdge = null
                                cropBase = null
                                cropItemId = null
                                if (candidate != null && !candidate.locked &&
                                    insideFrame && scale > 0f
                                ) {
                                    val edge = cropHandleAt(
                                        collection,
                                        canvas,
                                        candidate.id,
                                        canvasX,
                                        canvasY,
                                        26.dp.toPx() / scale,
                                    )
                                    val source = collection.source(candidate.sourceId)
                                    if (edge != null && source != null) {
                                        val (rawWidth, rawHeight) = naturalSize(source, canvas)
                                        cropEdge = edge
                                        cropItemId = candidate.id
                                        cropBase = candidate.crop
                                        cropRawWidth = rawWidth.coerceAtLeast(1f)
                                        cropRawHeight = rawHeight.coerceAtLeast(1f)
                                        cropScaleX = candidate.transform.scaleX.coerceAtLeast(0.01f)
                                        cropScaleY = candidate.transform.scaleY.coerceAtLeast(0.01f)
                                        pendingSelect = false
                                    }
                                }
                            },
                            onEnd = {
                                activeId = null
                                pendingSelect = false
                                cropEdge = null
                                cropItemId = null
                                cropBase = null
                            },
                        ) { pan, zoom, rotation ->
                            val id = activeId ?: return@detectItemTransformGestures

                            val edge = cropEdge
                            val base = cropBase
                            val cropId = cropItemId
                            if (edge != null && base != null && cropId != null) {
                                // Ubah jarak geser (piksel panel) menjadi
                                // perubahan fraksi crop pada sumber asli.
                                val dx = pan.x / scale
                                val dy = pan.y / scale
                                val delta = when (edge) {
                                    CropEdge.Left -> dx / (cropRawWidth * cropScaleX)
                                    CropEdge.Right -> -dx / (cropRawWidth * cropScaleX)
                                    CropEdge.Top -> dy / (cropRawHeight * cropScaleY)
                                    CropEdge.Bottom -> -dy / (cropRawHeight * cropScaleY)
                                }
                                val next = base.adjustCrop(edge, delta)
                                cropBase = next
                                cropDragState.value?.invoke(cropId, next)
                                return@detectItemTransformGestures
                            }

                            if (pendingSelect) {
                                pendingSelect = false
                                selectState.value(id)
                            }
                            transformState.value(
                                id,
                                pan.x,
                                pan.y,
                                zoom,
                                rotation,
                                frameWidthPx,
                            )
                        }
                    },
            )
        }
    }
}

private data class PreviewLayer(
    val item: SceneItem,
    val type: SourceType,
    val naturalWidth: Float,
    val naturalHeight: Float,
    val colorArgb: Long,
    val bitmap: ImageBitmap?,
    val blend: androidx.compose.ui.graphics.BlendMode,
)

/** Terjemahan BlendMode item ke mode gambar Compose untuk pratinjau. */
private fun com.rout.air.core.BlendMode.toComposeBlendMode(): androidx.compose.ui.graphics.BlendMode =
    when (this) {
        com.rout.air.core.BlendMode.Normal -> androidx.compose.ui.graphics.BlendMode.SrcOver
        com.rout.air.core.BlendMode.Add -> androidx.compose.ui.graphics.BlendMode.Plus
        com.rout.air.core.BlendMode.Screen -> androidx.compose.ui.graphics.BlendMode.Screen
        com.rout.air.core.BlendMode.Multiply -> androidx.compose.ui.graphics.BlendMode.Multiply
    }

private fun buildPreviewLayers(
    items: List<SceneItem>,
    sources: List<SceneSource>,
    bitmaps: Map<String, ImageBitmap>,
    canvas: CaptureConfig,
): List<PreviewLayer> {
    val byId = sources.associateBy { it.id }
    return items
        .sortedBy { it.z }
        .mapNotNull { item ->
            if (!item.visible) return@mapNotNull null
            val source = byId[item.sourceId] ?: return@mapNotNull null
            when (source.type) {
                SourceType.Screen -> PreviewLayer(
                    item = item,
                    type = source.type,
                    naturalWidth = canvas.width.toFloat(),
                    naturalHeight = canvas.height.toFloat(),
                    colorArgb = 0,
                    blend = item.blend.toComposeBlendMode(),
                    bitmap = null,
                )

                SourceType.Image, SourceType.Text -> {
                    val bitmap = bitmaps[source.id] ?: return@mapNotNull null
                    PreviewLayer(
                        item = item,
                        type = source.type,
                        naturalWidth = bitmap.width.toFloat(),
                        naturalHeight = bitmap.height.toFloat(),
                        colorArgb = 0,
                        blend = item.blend.toComposeBlendMode(),
                        bitmap = bitmap,
                    )
                }

                SourceType.Color -> PreviewLayer(
                    item = item,
                    type = source.type,
                    naturalWidth = source.settings.width.coerceAtLeast(1).toFloat(),
                    naturalHeight = source.settings.height.coerceAtLeast(1).toFloat(),
                    colorArgb = source.settings.color,
                    blend = item.blend.toComposeBlendMode(),
                    bitmap = null,
                )
            }
        }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawScreenPlaceholder(
    width: Float,
    height: Float,
    opacity: Float = 1f,
) {
    drawRect(Color(0xFF151B22), size = Size(width, height), alpha = opacity)
    val stepX = width / 6f
    val stepY = height / 6f
    for (index in 1..5) {
        drawLine(
            color = Color(0x33FFFFFF),
            start = Offset(stepX * index, 0f),
            end = Offset(stepX * index, height),
            strokeWidth = 1f,
            alpha = opacity,
        )
        drawLine(
            color = Color(0x33FFFFFF),
            start = Offset(0f, stepY * index),
            end = Offset(width, stepY * index),
            strokeWidth = 1f,
            alpha = opacity,
        )
    }
}

internal fun anchorOffset(align: Align, width: Float, height: Float): Pair<Float, Float> {
    val x = when (align) {
        Align.Top, Align.Center, Align.Bottom -> width / 2f
        Align.TopRight, Align.Right, Align.BottomRight -> width
        else -> 0f
    }
    val y = when (align) {
        Align.Left, Align.Center, Align.Right -> height / 2f
        Align.BottomLeft, Align.Bottom, Align.BottomRight -> height
        else -> 0f
    }
    return x to y
}

/** Tepi item yang punya pegangan crop. */
internal enum class CropEdge { Left, Top, Right, Bottom }

/** Menggeser satu sisi crop; nilai dibatasi agar sisi berlawanan tetap ada. */
internal fun Crop.adjustCrop(edge: CropEdge, delta: Float): Crop = when (edge) {
    CropEdge.Left -> copy(left = (left + delta).coerceIn(0f, 0.98f - right))
    CropEdge.Top -> copy(top = (top + delta).coerceIn(0f, 0.98f - bottom))
    CropEdge.Right -> copy(right = (right + delta).coerceIn(0f, 0.98f - left))
    CropEdge.Bottom -> copy(bottom = (bottom + delta).coerceIn(0f, 0.98f - top))
}

/**
 * Tepi crop yang disentuh, atau null bila titik tidak mengenai pegangan.
 * Posisi pegangan ada di tengah tiap tepi kotak item dan ikut rotasi.
 */
internal fun cropHandleAt(
    collection: SceneCollection,
    canvas: CaptureConfig,
    itemId: String,
    x: Float,
    y: Float,
    radius: Float,
): CropEdge? {
    val rect = itemCanvasRect(collection, canvas, itemId) ?: return null
    val centerX = rect.left + rect.width / 2f
    val centerY = rect.top + rect.height / 2f
    val dx = x - centerX
    val dy = y - centerY
    val radians = Math.toRadians(rect.rotation.toDouble())
    val cosValue = cos(radians).toFloat()
    val sinValue = sin(radians).toFloat()
    // Balik rotasi supaya pemeriksaan pegangan memakai koordinat lokal item.
    val localX = dx * cosValue + dy * sinValue
    val localY = -dx * sinValue + dy * cosValue
    val halfWidth = rect.width / 2f
    val halfHeight = rect.height / 2f
    // Item kecil: kecilkan area pegangan agar seluruh badan item tidak
    // berubah menjadi area crop dan tetap bisa digeser.
    val effectiveRadius = radius.coerceAtMost(min(halfWidth, halfHeight) * 0.9f)
    val handles = listOf(
        CropEdge.Left to Offset(-halfWidth, 0f),
        CropEdge.Right to Offset(halfWidth, 0f),
        CropEdge.Top to Offset(0f, -halfHeight),
        CropEdge.Bottom to Offset(0f, halfHeight),
    )
    return handles
        .map { (edge, point) -> edge to hypot(localX - point.x, localY - point.y) }
        .filter { it.second <= effectiveRadius }
        .minByOrNull { it.second }
        ?.first
}

/** Empat titik pegangan crop, sebagai isyarat visual di pratinjau. */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCropHandles(
    width: Float,
    height: Float,
) {
    val radius = 4.5.dp.toPx()
    val stroke = 1.5.dp.toPx()
    listOf(
        Offset(0f, height / 2f),
        Offset(width, height / 2f),
        Offset(width / 2f, 0f),
        Offset(width / 2f, height),
    ).forEach { point ->
        drawCircle(color = Color(0xFF00E5FF), radius = radius, center = point)
        drawCircle(
            color = Color(0xFF061014),
            radius = radius,
            center = point,
            style = Stroke(width = stroke),
        )
    }
}

/** Kotak sumber di kanvas, dipakai untuk menggambar outline pilihan. */
internal data class ItemRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val rotation: Float,
)

/** Rotasi dianggap lurus bila sudah tegak/horizontal (kelipatan 90°). */
internal fun isAxisAligned(rotation: Float): Boolean {
    val normalized = ((rotation % 90f) + 90f) % 90f
    // Toleransi kecil agar garis bantu hilang saat rotasi sudah hampir pas.
    return normalized < 1.5f || normalized > 88.5f
}

/** Posisi dan ukuran item di kanvas (belum diskalakan ke ukuran panel). */
internal fun itemCanvasRect(
    collection: SceneCollection,
    canvas: CaptureConfig,
    itemId: String?,
): ItemRect? {
    val item = collection.item(itemId) ?: return null
    val source = collection.source(item.sourceId) ?: return null
    val (naturalWidth, naturalHeight) = croppedNaturalSize(source, item, canvas)
    val width = naturalWidth * item.transform.scaleX
    val height = naturalHeight * item.transform.scaleY
    val (anchorX, anchorY) = anchorOffset(item.transform.align, width, height)
    return ItemRect(
        left = item.transform.x - anchorX,
        top = item.transform.y - anchorY,
        width = width,
        height = height,
        rotation = item.transform.rotation,
    )
}

/**
 * Ukuran sumber setelah crop. Crop memendekkan kotak item (seperti OBS),
 * jadi skala dan jangkar dihitung dari ukuran ini, bukan ukuran asli.
 */
internal fun croppedNaturalSize(
    source: SceneSource,
    item: SceneItem,
    canvas: CaptureConfig,
): Pair<Float, Float> {
    val (naturalWidth, naturalHeight) = naturalSize(source, canvas)
    val crop = item.crop
    val width = naturalWidth * (1f - crop.left - crop.right).coerceIn(0.02f, 1f)
    val height = naturalHeight * (1f - crop.top - crop.bottom).coerceIn(0.02f, 1f)
    return width to height
}

/** Ukuran asli sumber dalam piksel kanvas, tanpa memuat bitmap. */
internal fun naturalSize(source: SceneSource, canvas: CaptureConfig): Pair<Float, Float> =
    when (source.type) {
        SourceType.Screen -> canvas.width.toFloat() to canvas.height.toFloat()
        else -> {
            val width = source.settings.width.coerceAtLeast(1).toFloat()
            val height = if (source.settings.height > 0) {
                source.settings.height.toFloat()
            } else {
                (source.settings.textSize * 1.4f).coerceAtLeast(16f)
            }
            width to height
        }
    }

/**
 * Mencari item paling atas di titik kanvas (x, y). Dipakai panel Pratinjau
 * Studio untuk memilih sumber lewat ketukan, seperti OBS.
 */
internal fun hitTestItem(
    collection: SceneCollection,
    canvas: CaptureConfig,
    x: Float,
    y: Float,
): SceneItem? = hitTestItems(collection, canvas, x, y).firstOrNull()

/**
 * Semua item di titik kanvas, dari lapisan paling depan ke belakang. Dipakai
 * supaya item yang tertutup sumber layar penuh tetap bisa dipilih dengan
 * ketukan berulang (seperti Alt+klik di OBS).
 */
internal fun hitTestItems(
    collection: SceneCollection,
    canvas: CaptureConfig,
    x: Float,
    y: Float,
): List<SceneItem> {
    val scene = collection.activeScene() ?: return emptyList()
    val sources = collection.sources.associateBy { it.id }
    val hits = mutableListOf<SceneItem>()
    for (item in scene.items.sortedByDescending { it.z }) {
        if (!item.visible) continue
        val source = sources[item.sourceId] ?: continue
        val (naturalWidth, naturalHeight) = croppedNaturalSize(source, item, canvas)
        val transform = item.transform
        val width = naturalWidth * transform.scaleX
        val height = naturalHeight * transform.scaleY
        val (anchorX, anchorY) = anchorOffset(transform.align, width, height)
        val left = transform.x - anchorX
        val top = transform.y - anchorY
        val centerX = left + width / 2f
        val centerY = top + height / 2f
        val dx = x - centerX
        val dy = y - centerY
        val radians = Math.toRadians(transform.rotation.toDouble())
        val cosValue = cos(radians).toFloat()
        val sinValue = sin(radians).toFloat()
        // Balik rotasi item supaya ketukan pada item miring tetap tepat.
        val localX = dx * cosValue + dy * sinValue
        val localY = -dx * sinValue + dy * cosValue
        if (abs(localX) <= width / 2f && abs(localY) <= height / 2f) {
            hits += item
        }
    }
    return hits
}

/** Ketukan berulang: pilih item berikutnya di bawah item yang sedang aktif. */
internal fun cycleSelection(hits: List<SceneItem>, currentId: String?): String? {
    if (hits.isEmpty()) return null
    val index = hits.indexOfFirst { it.id == currentId }
    return hits[(index + 1) % hits.size].id
}

/**
 * Item tujuan menu konteks. Item yang sedang terpilih didahulukan bila
 * tersentuh, sehingga sumber layar penuh di lapisan atas tidak "mencuri"
 * menu dari item yang sengaja dipilih (mis. gambar).
 */
internal fun menuTargetAt(hits: List<SceneItem>, selectedId: String?): SceneItem? =
    hits.firstOrNull { it.id == selectedId } ?: hits.firstOrNull()


/**
 * Deteksi gestur geser/cubit/putar dengan callback awal, supaya pemanggil
 * bisa menentukan item mana yang berada di bawah jari saat gestur dimulai.
 * Dipakai bersama oleh pratinjau Compose dan panel Studio.
 */
internal suspend fun PointerInputScope.detectItemTransformGestures(
    onStart: (Offset) -> Unit,
    onEnd: () -> Unit,
    onGesture: (pan: Offset, zoom: Float, rotation: Float) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        onStart(down.position)
        var pastSlop = false
        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.any { it.pressed }
            val pan = event.calculatePan()
            val zoom = event.calculateZoom()
            val rotation = event.calculateRotation()
            val centroid = event.calculateCentroid(useCurrent = true)
            if (!pastSlop && centroid != Offset.Unspecified &&
                (centroid - down.position).getDistance() > viewConfiguration.touchSlop
            ) {
                pastSlop = true
            }
            if (pressed && pastSlop) {
                onGesture(pan, zoom, rotation)
                event.changes.forEach { it.consume() }
            }
            if (!pressed) break
        }
        onEnd()
    }
}
