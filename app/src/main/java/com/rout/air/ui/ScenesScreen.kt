package com.rout.air.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.rout.air.core.Align
import com.rout.air.core.CaptureConfig
import com.rout.air.core.Crop
import com.rout.air.core.SceneCollection
import com.rout.air.core.SceneItem
import com.rout.air.core.SceneSource
import com.rout.air.core.SourceSettings
import com.rout.air.core.SourceType
import com.rout.air.core.Transform
import com.rout.air.data.SceneStore
import com.rout.air.data.addScene
import com.rout.air.data.addSource
import com.rout.air.data.moveLayer
import com.rout.air.data.removeItem
import com.rout.air.data.removeScene
import com.rout.air.data.removeSource
import com.rout.air.data.renameScene
import com.rout.air.data.setActiveScene
import com.rout.air.data.updateItem
import com.rout.air.data.updateSource
import com.rout.air.engine.SourceBitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Halaman Adegan: daftar scene, sumber pada scene aktif, dan panel properti
 * yang meniru `obs_scene_item` (posisi, skala, rotasi, crop, alignment).
 *
 * Semua perubahan langsung dikirim ke SceneManager supaya pipeline rekaman
 * ikut memperbarui komposisi, termasuk saat sedang merekam.
 */
@Composable
fun ScenesScreen(
    canvasConfig: CaptureConfig,
    collection: SceneCollection,
    selectedItemId: String?,
    onSelectItem: (String?) -> Unit,
    onCollectionChange: (SceneCollection) -> Unit,
    onTransform: (String?, Float, Float, Float, Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val store = remember(context) { SceneStore(context) }
    val density = LocalDensity.current.density

    var showAddScene by remember { mutableStateOf(false) }
    var showRenameScene by remember { mutableStateOf(false) }
    var addMenuOpen by remember { mutableStateOf(false) }
    var replaceSourceId by remember { mutableStateOf<String?>(null) }

    val activeScene = collection.activeScene()
    val selectedItem = collection.item(selectedItemId)
    val selectedSource = selectedItem?.let { collection.source(it.sourceId) }

    // Pemilih gambar: disalin ke penyimpanan aplikasi agar path tetap valid.
    // Dipakai untuk menambah sumber baru maupun mengganti gambar yang ada.
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) {
            // Pilihan dibatalkan: jangan biarkan mode "ganti gambar" tertinggal.
            replaceSourceId = null
            return@rememberLauncherForActivityResult
        }
        val path = store.importImage(uri) ?: return@rememberLauncherForActivityResult
        val size = SourceBitmap.probeSize(path, canvasConfig.width, canvasConfig.height)
            ?: (canvasConfig.width to canvasConfig.height)

        val replacing = collection.source(replaceSourceId.orEmpty())
        replaceSourceId = null
        if (replacing != null) {
            onCollectionChange(
                collection.updateSource(
                    replacing.copy(
                        settings = replacing.settings.copy(
                            imagePath = path,
                            width = size.first,
                            height = size.second,
                        ),
                    ),
                ),
            )
            return@rememberLauncherForActivityResult
        }

        val source = SceneSource(
            type = SourceType.Image,
            name = uniqueSourceName(collection, "Gambar"),
            settings = SourceSettings(
                imagePath = path,
                width = size.first,
                height = size.second,
            ),
        )
        val item = SceneItem(
            sourceId = source.id,
            transform = fitTransform(size.first, size.second, canvasConfig),
        )
        onCollectionChange(collection.addSource(source, item))
        onSelectItem(item.id)
    }

    BackHandler(enabled = selectedItem != null) { onSelectItem(null) }

    if (selectedItem != null && selectedSource != null) {
        SourceEditor(
            canvas = canvasConfig,
            collection = collection,
            item = selectedItem,
            source = selectedSource,
            density = density,
            onCollectionChange = onCollectionChange,
            onTransform = onTransform,
            onSelectItem = onSelectItem,
            onBack = { onSelectItem(null) },
            onDelete = {
                // Hapus item ini saja. Sumber hanya dibuang bila memang sudah
                // tidak dipakai item lain (mis. hasil duplikat memakai sumber
                // yang sama), supaya item asli tidak ikut hilang.
                var next = collection.removeItem(selectedItem.id)
                val stillUsed = next.scenes.any { scene ->
                    scene.items.any { it.sourceId == selectedSource.id }
                }
                if (!stillUsed) next = next.removeSource(selectedSource.id)
                onCollectionChange(next)
                onSelectItem(null)
            },
            onPickImage = {
                replaceSourceId = selectedSource.id
                imagePicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Text("Adegan", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = "Scene, sumber, dan transisi",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Scene", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { showAddScene = true }) { Text("＋ Tambah") }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            collection.scenes.forEach { scene ->
                val active = scene.id == collection.activeSceneId
                Surface(
                    color = if (active) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.clickable {
                        onCollectionChange(collection.setActiveScene(scene.id))
                        onSelectItem(null)
                    },
                ) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text(
                            text = scene.name,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = "${scene.items.size} sumber",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row {
            TextButton(onClick = { showRenameScene = true }) { Text("Ganti nama scene") }
            if (collection.scenes.size > 1) {
                TextButton(
                    onClick = {
                        activeScene?.let { onCollectionChange(collection.removeScene(it.id)) }
                        onSelectItem(null)
                    },
                ) {
                    Text("Hapus scene aktif")
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Sumber", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.weight(1f))
            Box {
                TextButton(onClick = { addMenuOpen = true }) { Text("＋ Sumber") }
                DropdownMenu(
                    expanded = addMenuOpen,
                    onDismissRequest = { addMenuOpen = false },
                ) {
                    SourceMenuItem("Layar", hasScreen(collection)) {
                        addMenuOpen = false
                        val source = SceneSource(type = SourceType.Screen, name = "Layar")
                        // Layar dikunci default: ukurannya sudah memenuhi kanvas.
                        val item = SceneItem(sourceId = source.id, locked = true)
                        onCollectionChange(collection.addSource(source, item))
                        onSelectItem(item.id)
                    }
                    SourceMenuItem("Gambar", false) {
                        addMenuOpen = false
                        imagePicker.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly,
                            ),
                        )
                    }
                    SourceMenuItem("Teks", false) {
                        addMenuOpen = false
                        val settings = SourceSettings(
                            text = "Teks baru",
                            textSize = 48f,
                            width = canvasConfig.width,
                            height = approxTextHeight(48f, density),
                        )
                        val source = SceneSource(
                            type = SourceType.Text,
                            name = uniqueSourceName(collection, "Teks"),
                            settings = settings,
                        )
                        val item = SceneItem(
                            sourceId = source.id,
                            transform = Transform(
                                x = canvasConfig.width / 2f,
                                y = canvasConfig.height * 0.8f,
                                align = Align.Center,
                            ),
                        )
                        onCollectionChange(collection.addSource(source, item))
                        onSelectItem(item.id)
                    }
                    SourceMenuItem("Warna", false) {
                        addMenuOpen = false
                        val settings = SourceSettings(
                            color = 0xFF00E5FF,
                            width = 640,
                            height = 360,
                        )
                        val source = SceneSource(
                            type = SourceType.Color,
                            name = uniqueSourceName(collection, "Warna"),
                            settings = settings,
                        )
                        val item = SceneItem(
                            sourceId = source.id,
                            transform = fitTransform(640, 360, canvasConfig),
                        )
                        onCollectionChange(collection.addSource(source, item))
                        onSelectItem(item.id)
                    }
                }
            }
        }

        if (activeScene == null || activeScene.items.isEmpty()) {
            Hint("Belum ada sumber di scene ini. Tambahkan sumber untuk mulai menata.")
        } else {
            val ordered = activeScene.items.sortedByDescending { it.z }
            ordered.forEach { item ->
                val source = collection.source(item.sourceId) ?: return@forEach
                SourceRow(
                    item = item,
                    source = source,
                    selected = item.id == selectedItemId,
                    onSelect = { onSelectItem(item.id) },
                    onReset = {
                        // Reset hanya item ini: posisi/skala/rotasi dan crop.
                        onCollectionChange(
                            collection.updateItem(
                                item.copy(
                                    transform = defaultTransform(source, canvasConfig),
                                    crop = Crop(),
                                ),
                            ),
                        )
                    },
                    onToggleVisible = {
                        onCollectionChange(
                            collection.updateItem(item.copy(visible = !item.visible)),
                        )
                    },
                    onToggleLock = {
                        onCollectionChange(
                            collection.updateItem(item.copy(locked = !item.locked)),
                        )
                    },
                    onDelete = {
                        // Swipe kiri: hapus item ini saja; sumber dibuang bila
                        // sudah tidak dipakai item lain di scene mana pun.
                        var next = collection.removeItem(item.id)
                        val stillUsed = next.scenes.any { scene ->
                            scene.items.any { it.sourceId == item.sourceId }
                        }
                        if (!stillUsed) next = next.removeSource(item.sourceId)
                        onCollectionChange(next)
                        if (selectedItemId == item.id) onSelectItem(null)
                    },
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Hint(
            "Urutan atas = paling depan. Geser/putar sumber langsung di panel " +
                "Pratinjau pada halaman Studio.",
        )
    }

    if (showAddScene) {
        NameDialog(
            title = "Tambah scene",
            initial = "Adegan ${collection.scenes.size + 1}",
            onDismiss = { showAddScene = false },
            onConfirm = { name ->
                onCollectionChange(collection.addScene(name))
                onSelectItem(null)
                showAddScene = false
            },
        )
    }

    if (showRenameScene && activeScene != null) {
        NameDialog(
            title = "Ganti nama scene",
            initial = activeScene.name,
            onDismiss = { showRenameScene = false },
            onConfirm = { name ->
                onCollectionChange(collection.renameScene(activeScene.id, name))
                showRenameScene = false
            },
        )
    }
}

@Composable
private fun SourceMenuItem(label: String, disabled: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(if (disabled) "$label (sudah ada)" else label) },
        enabled = !disabled,
        onClick = onClick,
    )
}

@Composable
private fun SourceRow(
    item: SceneItem,
    source: SceneSource,
    selected: Boolean,
    onSelect: () -> Unit,
    onReset: () -> Unit,
    onToggleVisible: () -> Unit,
    onToggleLock: () -> Unit,
    onDelete: () -> Unit,
) {
    // Geser baris sedikit ke kiri: tombol Hapus tersingkap, lalu diketuk
    // untuk menghapus. Tidak langsung terhapus seperti swipe-to-dismiss.
    val density = LocalDensity.current
    val revealWidthPx = with(density) { 96.dp.toPx() }
    var target by remember(item.id) { mutableFloatStateOf(0f) }
    var dragging by remember(item.id) { mutableStateOf(false) }
    val offset by animateFloatAsState(
        targetValue = target,
        animationSpec = if (dragging) snap() else tween(durationMillis = 180),
        label = "geser-hapus",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        // Latar tombol hapus; menempel di belakang dan ikut tersingkap.
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.error)
                .clickable {
                    target = 0f
                    onDelete()
                },
            contentAlignment = Alignment.CenterEnd,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Hapus",
                    color = MaterialTheme.colorScheme.onError,
                    style = MaterialTheme.typography.titleSmall,
                )
                TrashIcon(color = MaterialTheme.colorScheme.onError)
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(offset.roundToInt(), 0) }
                .pointerInput(item.id) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = {
                            dragging = false
                            target = if (target <= -revealWidthPx * 0.4f) {
                                -revealWidthPx
                            } else {
                                0f
                            }
                        },
                        onDragCancel = {
                            dragging = false
                            target = 0f
                        },
                    ) { change, dragAmount ->
                        change.consume()
                        target = (target + dragAmount).coerceIn(-revealWidthPx, 0f)
                    }
                }
                .clickable {
                    // Saat terbuka, ketukan pertama menutup tombol hapus.
                    if (target != 0f) target = 0f else onSelect()
                },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(
                    color = if (selected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    },
                    shape = CircleShape,
                    modifier = Modifier.size(36.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (source.type == SourceType.Screen) {
                            ScreenIcon(
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                size = 18.dp,
                            )
                        } else {
                            Text(
                                text = typeSymbol(source.type),
                                style = MaterialTheme.typography.titleSmall,
                            )
                        }
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(source.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "${typeLabel(source.type)} • ${intrinsicLabel(source)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Tiga aksi per item, masing-masing dengan ikon yang jelas.
                RowIconAction(onClick = onReset) {
                    ResetIcon(
                        color = MaterialTheme.colorScheme.primary,
                        size = 20.dp,
                        description = "Reset posisi, skala, rotasi, dan crop",
                    )
                }
                RowIconAction(onClick = onToggleVisible) {
                    VisibilityIcon(
                        visible = item.visible,
                        color = if (item.visible) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                RowIconAction(onClick = onToggleLock) {
                    LockIcon(
                        locked = item.locked,
                        color = if (item.locked) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                ChevronRightIcon(
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 2.dp),
                )
            }
        }
    }
}

/** Tombol ikon ringkas dengan area sentuh yang nyaman. */
@Composable
private fun RowIconAction(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun SourceEditor(
    canvas: CaptureConfig,
    collection: SceneCollection,
    item: SceneItem,
    source: SceneSource,
    density: Float,
    onCollectionChange: (SceneCollection) -> Unit,
    onTransform: (String?, Float, Float, Float, Float, Float) -> Unit,
    onSelectItem: (String?) -> Unit,
    onBack: () -> Unit,
    onDelete: () -> Unit,
    onPickImage: () -> Unit,
) {
    var name by remember(source.id) { mutableStateOf(source.name) }
    var text by remember(source.id) { mutableStateOf(source.settings.text) }
    var textSize by remember(source.id) { mutableStateOf(source.settings.textSize) }

    // Menu konteks dari pratinjau (tahan lama / klik dua kali).
    var menuTarget by remember(item.id) { mutableStateOf<Pair<SceneItem, Offset>?>(null) }

    fun updateSource(next: SceneSource) = onCollectionChange(collection.updateSource(next))
    fun updateItem(next: SceneItem) = onCollectionChange(collection.updateItem(next))
    fun updateTransform(block: (Transform) -> Transform) =
        updateItem(item.copy(transform = block(item.transform)))

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Kembali") }
            Text(source.name, style = MaterialTheme.typography.titleLarge)
        }

        // Pratinjau menempel di atas, sedangkan pengaturan di bawahnya yang
        // bergulir. Hasil geser slider jadi selalu terlihat.
        Spacer(Modifier.height(8.dp))
        Box(modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
            ScenePreview(
                canvas = canvas,
                collection = collection,
                selectedItemId = item.id,
                onSelectItem = {},
                onTransform = onTransform,
                onItemMenu = { target, position -> menuTarget = target to position },
                gestureTargetItemId = item.id,
                modifier = Modifier.fillMaxSize(),
            )
            menuTarget?.let { (target, position) ->
                // Menu digeser ke titik ketukan di dalam pratinjau.
                Box(
                    modifier = Modifier.offset {
                        IntOffset(position.x.roundToInt(), position.y.roundToInt())
                    },
                ) {
                    ItemContextMenu(
                        collection = collection,
                        item = target,
                        source = collection.source(target.sourceId),
                        canvas = canvas,
                        onDismiss = { menuTarget = null },
                        onCollectionChange = onCollectionChange,
                        onSelectItem = onSelectItem,
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
        OutlinedTextField(
            value = name,
            onValueChange = {
                name = it
                updateSource(source.copy(name = it))
            },
            label = { Text("Nama sumber") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        when (source.type) {
            SourceType.Text -> {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        updateSource(
                            source.copy(
                                settings = source.settings.copy(
                                    text = it,
                                    height = approxTextHeight(textSize, density),
                                ),
                            ),
                        )
                    },
                    label = { Text("Isi teks") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                LabeledSlider(
                    label = "Ukuran teks",
                    value = textSize,
                    range = 12f..160f,
                    format = { "%.0f sp".format(it) },
                ) {
                    textSize = it
                    updateSource(
                        source.copy(
                            settings = source.settings.copy(
                                textSize = it,
                                height = approxTextHeight(it, density),
                            ),
                        ),
                    )
                }
                ColorRow(
                    title = "Warna teks",
                    selected = source.settings.textColor,
                ) { color ->
                    updateSource(source.copy(settings = source.settings.copy(textColor = color)))
                }
            }

            SourceType.Color -> {
                ColorRow(
                    title = "Warna",
                    selected = source.settings.color,
                ) { color ->
                    updateSource(source.copy(settings = source.settings.copy(color = color)))
                }
            }

            SourceType.Image -> {
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onPickImage) { Text("Ganti gambar") }
            }

            SourceType.Screen -> {
                Spacer(Modifier.height(8.dp))
                Hint("Tangkapan layar penuh. Ukuran mengikuti kanvas dan mode isi di Setelan.")
            }
        }

        Spacer(Modifier.height(20.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Transformasi",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = {
                    // Hanya transformasi; crop punya tombolnya sendiri.
                    updateTransform { defaultTransform(source, canvas) }
                },
            ) {
                Text("Reset transform")
            }
        }

        // Nilai awal sumber, dipakai tombol reset per properti.
        val resetTransform = defaultTransform(source, canvas)

        LabeledSlider(
            label = "Posisi X",
            value = item.transform.x,
            range = 0f..canvas.width.toFloat(),
            format = { "%.0f px".format(it) },
            onReset = { updateTransform { it.copy(x = resetTransform.x) } },
        ) { value -> updateTransform { it.copy(x = value) } }

        LabeledSlider(
            label = "Posisi Y",
            value = item.transform.y,
            range = 0f..canvas.height.toFloat(),
            format = { "%.0f px".format(it) },
            onReset = { updateTransform { it.copy(y = resetTransform.y) } },
        ) { value -> updateTransform { it.copy(y = value) } }

        // Tiga kontrol skala terpisah, mengikuti cara OBS: menarik sudut kotak
        // = skala seragam (X & Y sama), sedangkan Width/Height = sumbu bebas.
        val uniformScale = (item.transform.scaleX + item.transform.scaleY) / 2f
        val uniformDiffers = abs(item.transform.scaleX - item.transform.scaleY) > 0.005f
        LabeledSlider(
            label = if (uniformDiffers) "Scale seragam (X ≠ Y)" else "Scale seragam",
            value = uniformScale,
            range = 0.05f..4f,
            format = { "%.2f×".format(it) },
            onReset = {
                updateTransform {
                    it.copy(
                        scaleX = resetTransform.scaleX,
                        scaleY = resetTransform.scaleY,
                    )
                }
            },
        ) { value ->
            updateTransform { it.copy(scaleX = value, scaleY = value) }
        }

        LabeledSlider(
            label = "Scale X",
            value = item.transform.scaleX,
            range = 0.05f..4f,
            format = { "%.2f×".format(it) },
            onReset = { updateTransform { it.copy(scaleX = resetTransform.scaleX) } },
        ) { value -> updateTransform { it.copy(scaleX = value) } }

        LabeledSlider(
            label = "Scale Y",
            value = item.transform.scaleY,
            range = 0.05f..4f,
            format = { "%.2f×".format(it) },
            onReset = { updateTransform { it.copy(scaleY = resetTransform.scaleY) } },
        ) { value -> updateTransform { it.copy(scaleY = value) } }

        LabeledSlider(
            label = "Rotasi",
            value = item.transform.rotation,
            range = -180f..180f,
            format = { "%.0f°".format(it) },
            onReset = { updateTransform { it.copy(rotation = 0f) } },
        ) { value -> updateTransform { it.copy(rotation = value) } }

        AlignGrid(item.transform.align) { align ->
            // Tombol jangkar sekaligus memindahkan item: titik jangkar yang
            // dipilih diletakkan di posisi yang sama pada kanvas (pojok,
            // tepi, atau tengah), jadi hasilnya langsung terlihat.
            val (fractionX, fractionY) = alignFraction(align)
            updateTransform {
                it.copy(
                    align = align,
                    x = canvas.width * fractionX,
                    y = canvas.height * fractionY,
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Crop",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = { updateItem(item.copy(crop = Crop())) },
            ) {
                Text("Reset crop")
            }
        }
        CropSlider("Kiri", item.crop.left) { value ->
            updateItem(item.copy(crop = item.crop.copy(left = value)))
        }
        CropSlider("Atas", item.crop.top) { value ->
            updateItem(item.copy(crop = item.crop.copy(top = value)))
        }
        CropSlider("Kanan", item.crop.right) { value ->
            updateItem(item.copy(crop = item.crop.copy(right = value)))
        }
        CropSlider("Bawah", item.crop.bottom) { value ->
            updateItem(item.copy(crop = item.crop.copy(bottom = value)))
        }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = { onCollectionChange(collection.moveLayer(item.id, +1)) },
            ) { Text("Naikkan lapisan") }
            TextButton(
                onClick = { onCollectionChange(collection.moveLayer(item.id, -1)) },
            ) { Text("Turunkan lapisan") }
        }

        Spacer(Modifier.height(8.dp))
        // Menghapus item ini saja; sumber dibuang hanya bila tak terpakai lagi.
        TextButton(onClick = onDelete) { Text("Hapus item") }
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: (Float) -> String,
    onReset: (() -> Unit)? = null,
    onChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(format(value), style = MaterialTheme.typography.bodySmall)
            if (onReset != null) {
                // Reset khusus properti ini, mis. hanya Scale X.
                Box(
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onReset),
                    contentAlignment = Alignment.Center,
                ) {
                    ResetIcon(
                        color = MaterialTheme.colorScheme.primary,
                        size = 18.dp,
                        description = "Reset $label",
                    )
                }
            }
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
        )
    }
}

@Composable
private fun CropSlider(
    label: String,
    value: Float,
    onChange: (Float) -> Unit,
) {
    LabeledSlider(
        label = "Crop $label",
        value = value,
        range = 0f..0.49f,
        format = { "%.0f%%".format(it * 100f) },
        onReset = { onChange(0f) },
        onChange = onChange,
    )
}

@Composable
private fun AlignGrid(current: Align, onChange: (Align) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text("Titik jangkar", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(6.dp))
        val rows = listOf(
            listOf(Align.TopLeft, Align.Top, Align.TopRight),
            listOf(Align.Left, Align.Center, Align.Right),
            listOf(Align.BottomLeft, Align.Bottom, Align.BottomRight),
        )
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { align ->
                    Surface(
                        color = if (align == current) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier
                            .size(40.dp)
                            .clickable { onChange(align) },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = alignSymbol(align),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun ColorRow(title: String, selected: Long, onPick: (Long) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PRESET_COLORS.forEach { color ->
                val argb = color.toInt()
                val ring = if (selected == color) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                } else {
                    Modifier
                }
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(argb))
                        .then(ring)
                        .clickable { onPick(color) },
                )
            }
        }
    }
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                enabled = value.isNotBlank(),
                onClick = { onConfirm(value.trim()) },
            ) { Text("Simpan") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Batal") }
        },
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// --- Bantuan kecil ---

private fun hasScreen(collection: SceneCollection): Boolean =
    collection.sources.any { it.type == SourceType.Screen }

private fun uniqueSourceName(collection: SceneCollection, base: String): String {
    if (collection.sources.none { it.name == base }) return base
    var index = 2
    while (collection.sources.any { it.name == "$base $index" }) index++
    return "$base $index"
}

/** Skala awal agar sumber baru pas dan terpusat di kanvas. */
private fun fitTransform(width: Int, height: Int, canvas: CaptureConfig): Transform {
    if (width <= 0 || height <= 0) return Transform()
    val scale = min(
        canvas.width.toFloat() / width,
        canvas.height.toFloat() / height,
    ) * 0.8f
    return Transform(
        x = canvas.width / 2f,
        y = canvas.height / 2f,
        scaleX = max(0.05f, scale),
        scaleY = max(0.05f, scale),
        align = Align.Center,
    )
}

/** Posisi awal saat transformasi direset, mengikuti tipe sumber. */
internal fun defaultTransform(source: SceneSource, canvas: CaptureConfig): Transform =
    when (source.type) {
        // Layar memenuhi kanvas penuh.
        SourceType.Screen -> Transform()
        SourceType.Text -> Transform(
            x = canvas.width / 2f,
            y = canvas.height * 0.8f,
            align = Align.Center,
        )

        else -> fitTransform(source.settings.width, source.settings.height, canvas)
    }

private fun approxTextHeight(textSizeSp: Float, density: Float): Int =
    max(16, (textSizeSp * density * 1.4f).toInt())

private fun typeLabel(type: SourceType): String = when (type) {
    SourceType.Screen -> "Tangkap layar"
    SourceType.Image -> "Gambar"
    SourceType.Text -> "Teks"
    SourceType.Color -> "Warna"
}

private fun typeSymbol(type: SourceType): String = when (type) {
    SourceType.Screen -> "▣"
    SourceType.Image -> "▤"
    SourceType.Text -> "T"
    SourceType.Color -> "■"
}

private fun intrinsicLabel(source: SceneSource): String = when (source.type) {
    SourceType.Screen -> "mengikuti kanvas"
    else -> "${source.settings.width}x${source.settings.height}"
}

private fun alignSymbol(align: Align): String = when (align) {
    Align.TopLeft -> "↖"
    Align.Top -> "↑"
    Align.TopRight -> "↗"
    Align.Left -> "←"
    Align.Center -> "•"
    Align.Right -> "→"
    Align.BottomLeft -> "↙"
    Align.Bottom -> "↓"
    Align.BottomRight -> "↘"
}

/** Fraksi posisi jangkar pada kanvas (0 = kiri/atas, 0.5 = tengah, 1 = kanan/bawah). */
private fun alignFraction(align: Align): Pair<Float, Float> {
    val x = when (align) {
        Align.Top, Align.Center, Align.Bottom -> 0.5f
        Align.TopRight, Align.Right, Align.BottomRight -> 1f
        else -> 0f
    }
    val y = when (align) {
        Align.Left, Align.Center, Align.Right -> 0.5f
        Align.BottomLeft, Align.Bottom, Align.BottomRight -> 1f
        else -> 0f
    }
    return x to y
}

/** Offset titik jangkar terhadap pojok kiri-atas item. */
private fun anchorOffsetFor(align: Align, width: Float, height: Float): Pair<Float, Float> {
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

private val PRESET_COLORS = listOf(
    0xFFFFFFFF,
    0xFF000000,
    0xFF00E5FF,
    0xFFE53935,
    0xFF43A047,
    0xFF1E88E5,
    0xFFFFB300,
    0xFFD81B60,
    0xFFFF6D00,
    0xFF9E9E9E,
)
