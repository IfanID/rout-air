package com.rout.air.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import android.content.res.Configuration
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.rout.air.R
import com.rout.air.core.Align
import com.rout.air.core.AudioChannelId
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
import com.rout.air.data.addScreenItem
import com.rout.air.data.addSource
import com.rout.air.data.duplicateScene
import com.rout.air.data.moveScene
import com.rout.air.data.moveLayer
import com.rout.air.data.removeItem
import com.rout.air.data.removeScene
import com.rout.air.data.removeSource
import com.rout.air.data.renameScene
import com.rout.air.data.setActiveScene
import com.rout.air.data.updateItem
import com.rout.air.data.updateSource
import com.rout.air.engine.SourceBitmap
import com.rout.air.data.AudioMixer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import androidx.compose.ui.res.stringResource

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
    var showAddSource by remember { mutableStateOf(false) }
    var showAppAudioPicker by remember { mutableStateOf(false) }
    var replaceSourceId by remember { mutableStateOf<String?>(null) }

    val activeScene = collection.activeScene()
    val hasScreenInActiveScene = activeScene?.items?.any { item ->
        collection.source(item.sourceId)?.type == SourceType.Screen
    } == true
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
        Text(stringResource(R.string.scenes_adegan), style = MaterialTheme.typography.headlineSmall)
        Text(
            text = stringResource(R.string.scenes_scene_sumber_dan_transisi),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.scenes_scene), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { showAddScene = true }) { Text(stringResource(R.string.scenes_tambah_2)) }
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
                            text = stringResource(R.string.scenes_n_sumber, scene.items.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { showRenameScene = true }) { Text(stringResource(R.string.audio_ganti_nama)) }
            if (collection.scenes.size > 1) {
                TextButton(
                    onClick = {
                        // Urutan scene di daftar: kiri = lebih awal.
                        activeScene?.let { onCollectionChange(collection.moveScene(it.id, -1)) }
                    },
                ) {
                    Text(stringResource(R.string.scenes_kiri))
                }
                TextButton(
                    onClick = {
                        activeScene?.let { onCollectionChange(collection.moveScene(it.id, +1)) }
                    },
                ) {
                    Text(stringResource(R.string.scenes_kanan))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(
                onClick = {
                    activeScene?.let { onCollectionChange(collection.duplicateScene(it.id)) }
                    onSelectItem(null)
                },
            ) {
                Text(stringResource(R.string.scenes_duplikat_scene))
            }
            if (collection.scenes.size > 1) {
                TextButton(
                    onClick = {
                        activeScene?.let { onCollectionChange(collection.removeScene(it.id)) }
                        onSelectItem(null)
                    },
                ) {
                    Text(stringResource(R.string.scenes_hapus_scene_aktif))
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.scenes_sumber), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { showAddSource = true }) { Text(stringResource(R.string.scenes_sumber_2)) }
        }

        if (activeScene == null || activeScene.items.isEmpty()) {
            Hint(stringResource(R.string.scenes_belum_ada_sumber_di_scene_ini_tambahkan))
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
                        if (source.type == SourceType.AudioInput || source.type == SourceType.AudioOutput) {
                            AudioMixer.setEnabled(
                                AudioChannelId.forSource(source.id),
                                !item.visible,
                            )
                        }
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
                    onMove = { delta ->
                        onCollectionChange(collection.moveLayer(item.id, delta))
                    },
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Hint(
            "Urutan atas = paling depan. Geser pegangan ☰ pada baris sumber " +
                "untuk memindahkan lapisan. Geser/putar/crop sumber langsung " +
                "di panel Pratinjau pada halaman Studio.",
        )
    }

    if (showAddScene) {
        NameDialog(
            title = stringResource(R.string.scenes_tambah_scene),
            initial = stringResource(R.string.scenes_adegan_n, collection.scenes.size + 1),
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
            title = stringResource(R.string.scenes_ganti_nama_scene),
            initial = activeScene.name,
            onDismiss = { showRenameScene = false },
            onConfirm = { name ->
                onCollectionChange(collection.renameScene(activeScene.id, name))
                showRenameScene = false
            },
        )
    }

    if (showAddSource) {
        AddSourceDialog(
            hasScreen = hasScreenInActiveScene,
            onScreen = {
                showAddSource = false
                val (next, itemId) = collection.addScreenItem()
                if (itemId != null) {
                    onCollectionChange(next)
                    onSelectItem(itemId)
                }
            },
            onImage = {
                showAddSource = false
                imagePicker.launch(
                    PickVisualMediaRequest(
                        ActivityResultContracts.PickVisualMedia.ImageOnly,
                    ),
                )
            },
            onText = {
                showAddSource = false
                val teksBaru = context.getString(R.string.scenes_teks_baru)
                val settings = SourceSettings(
                    text = teksBaru,
                    textSize = 48f,
                    width = canvasConfig.width,
                    height = SourceBitmap.measureTextHeight(
                        teksBaru,
                        48f,
                        canvasConfig.width,
                        density,
                    ),
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
            },
            onColor = {
                showAddSource = false
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
            },
            onApplicationAudio = {
                showAddSource = false
                showAppAudioPicker = true
            },
            onDismiss = { showAddSource = false },
        )
    }

    if (showAppAudioPicker) {
        ApplicationAudioPickerDialog(
            onSelect = { app ->
                AudioMixer.addApplicationAudio(
                    uid = app.uid,
                    packageName = app.packageName,
                    label = app.label,
                )
                android.widget.Toast.makeText(
                    context,
                    context.getString(R.string.scenes_app_ditambahkan, app.label),
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
                showAppAudioPicker = false
            },
            onDismiss = { showAppAudioPicker = false },
        )
    }
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
    /** Geser urutan sumber: +1 naik (lebih depan), -1 turun. */
    onMove: (Int) -> Unit,
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
        label = stringResource(R.string.scenes_geser_hapus),
    )
    // Geser pegangan naik/turun untuk memindahkan urutan lapisan.
    var rowHeightPx by remember(item.id) { mutableFloatStateOf(0f) }
    var dragY by remember(item.id) { mutableFloatStateOf(0f) }
    var reordering by remember(item.id) { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .zIndex(if (reordering) 1f else 0f)
            .onSizeChanged { rowHeightPx = it.height.toFloat() }
            .offset { IntOffset(0, dragY.roundToInt()) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Pegangan urutan berada di luar area geser-hapus. Jadi menarik
        // pegangan tidak pernah ikut menyingkap tombol Hapus.
        Box(
            modifier = Modifier
                .size(32.dp)
                .pointerInput(item.id) {
                    detectVerticalDragGestures(
                        onDragStart = { reordering = true },
                        onDragEnd = {
                            reordering = false
                            val pitch = rowHeightPx + 8.dp.toPx()
                            val step = -(dragY / pitch).roundToInt()
                            dragY = 0f
                            if (step != 0) onMove(step)
                        },
                        onDragCancel = {
                            reordering = false
                            dragY = 0f
                        },
                    ) { change, dragAmount ->
                        change.consume()
                        dragY += dragAmount
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            DragHandleIcon(
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                size = 18.dp,
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            // Latar tombol hapus; menempel di belakang kartu dan ikut
            // tersingkap saat kartu digeser ke kiri.
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
                        text = stringResource(R.string.common_hapus),
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
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
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
                        Text(
                            text = source.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${typeLabel(source.type)} • ${intrinsicLabel(source)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // Tiga aksi per item, masing-masing dengan ikon yang jelas.
                    RowIconAction(onClick = onReset) {
                        ResetIcon(
                            color = MaterialTheme.colorScheme.primary,
                            size = 20.dp,
                            description = stringResource(R.string.scenes_reset_posisi_skala_rotasi_dan_crop),
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
                    RowIconAction(
                        onClick = onToggleLock,
                        // Latar tipis saat terkunci supaya posisi tombol tetap
                        // terlihat jelas walau ikon berubah warna.
                        background = if (item.locked) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                        } else {
                            null
                        },
                    ) {
                        // Ikon vector (bukan Canvas) supaya pasti tergambar.
                        Icon(
                            painter = painterResource(
                                if (item.locked) R.drawable.ic_lock else R.drawable.ic_lock_open,
                            ),
                            contentDescription = if (item.locked) {
                                "Buka kunci"
                            } else {
                                "Kunci posisi"
                            },
                            tint = if (item.locked) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Tombol ikon ringkas dengan area sentuh yang nyaman. */
@Composable
private fun RowIconAction(
    onClick: () -> Unit,
    background: Color? = null,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .then(
                if (background != null) {
                    Modifier.background(background)
                } else {
                    Modifier
                },
            )
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
    var showDevicePicker by remember(source.id) { mutableStateOf(false) }
    val configuration = LocalConfiguration.current
    val portrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT
    val previewHeight = if (portrait) {
        (configuration.screenHeightDp * 0.46f).dp.coerceIn(300.dp, 440.dp)
    } else {
        (configuration.screenHeightDp * 0.55f).dp.coerceIn(220.dp, 380.dp)
    }
    val isAudioSource = source.type == SourceType.AudioInput || source.type == SourceType.AudioOutput

    // Menu konteks dari pratinjau (tahan lama / klik dua kali).
    var menuTarget by remember(item.id) { mutableStateOf<Pair<SceneItem, Offset>?>(null) }

    fun updateSource(next: SceneSource) = onCollectionChange(collection.updateSource(next))
    fun updateItem(next: SceneItem) = onCollectionChange(collection.updateItem(next))
    fun updateTransform(block: (Transform) -> Transform) =
        updateItem(item.copy(transform = block(item.transform)))

    if (isAudioSource) {
        AudioSourceEditor(
            canvas = canvas,
            collection = collection,
            item = item,
            source = source,
            onCollectionChange = onCollectionChange,
            onBack = onBack,
            onDelete = onDelete,
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.common_kembali_panah)) }
            Text(
                text = source.name,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // Kunci/unlock item langsung dari halaman sumber.
            RowIconAction(
                onClick = { updateItem(item.copy(locked = !item.locked)) },
                background = if (item.locked) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                } else {
                    null
                },
            ) {
                Icon(
                    painter = painterResource(
                        if (item.locked) R.drawable.ic_lock else R.drawable.ic_lock_open,
                    ),
                    contentDescription = if (item.locked) "Buka kunci" else "Kunci posisi",
                    tint = if (item.locked) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        // Pratinjau menempel di atas, sedangkan pengaturan di bawahnya yang
        // bergulir. Hasil geser slider jadi selalu terlihat.
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(previewHeight),
        ) {
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

            val hasScreen = collection.activeScene()?.items?.any {
                collection.source(it.sourceId)?.type == SourceType.Screen
            } == true
            if (hasScreen) {
                Text(
                    text = stringResource(R.string.scenes_layar_pratinjau_langsung_ada_di_tab_stud),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFB8C2CC),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xCC000000))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
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
            label = { Text(stringResource(R.string.scenes_nama_sumber)) },
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
                                    height = SourceBitmap.measureTextHeight(
                                        it,
                                        textSize,
                                        source.settings.width,
                                        density,
                                    ),
                                ),
                            ),
                        )
                    },
                    label = { Text(stringResource(R.string.scenes_isi_teks)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                LabeledSlider(
                    label = stringResource(R.string.scenes_ukuran_teks),
                    value = textSize,
                    range = 12f..160f,
                    format = { "%.0f sp".format(it) },
                ) {
                    textSize = it
                    updateSource(
                        source.copy(
                            settings = source.settings.copy(
                                textSize = it,
                                height = SourceBitmap.measureTextHeight(
                                    text,
                                    it,
                                    source.settings.width,
                                    density,
                                ),
                            ),
                        ),
                    )
                }
                ColorRow(
                    title = stringResource(R.string.scenes_warna_teks),
                    selected = source.settings.textColor,
                ) { color ->
                    updateSource(source.copy(settings = source.settings.copy(textColor = color)))
                }
            }

            SourceType.Color -> {
                ColorRow(
                    title = stringResource(R.string.scenes_warna),
                    selected = source.settings.color,
                ) { color ->
                    updateSource(source.copy(settings = source.settings.copy(color = color)))
                }
            }

            SourceType.Image -> {
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onPickImage) { Text(stringResource(R.string.scenes_ganti_gambar)) }
            }

            SourceType.Screen -> {
                Spacer(Modifier.height(8.dp))
                Hint(
                    "Tangkapan layar penuh. Ukuran mengikuti kanvas dan mode isi di Setelan. " +
                        "Layar default berada di lapisan paling bawah; ubah urutan lewat " +
                        "pegangan ☰ di daftar Sumber atau tombol Naikkan/Turunkan lapisan.",
                )
            }

            SourceType.AudioInput -> {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.scenes_perangkat_input_n, source.settings.audioDeviceName ?: androidx.compose.ui.platform.LocalContext.current.getString(R.string.common_default)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = when (source.settings.audioDeviceType) {
                        android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO ->
                            "Bluetooth / TWS. Mic TWS memakai profil SCO; musik A2DP " +
                                "bisa berhenti selama mic dipakai."

                        android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Mikrofon bawaan perangkat"
                        else -> "Volume, mute, filter, dan monitor diatur di halaman Audio."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { showDevicePicker = true }) {
                        Text(stringResource(R.string.scenes_ganti_perangkat_input))
                    }
                }
                if (source.settings.audioDeviceType == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.scenes_aktifkan_sco_mic_bluetooth))
                            Text(
                                "Coba aktifkan profil headset agar mic TWS bisa dipakai.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        androidx.compose.material3.Switch(
                            checked = source.settings.audioScoEnabled,
                            onCheckedChange = { enabled ->
                                updateSource(
                                    source.copy(
                                        settings = source.settings.copy(audioScoEnabled = enabled),
                                    ),
                                )
                            },
                        )
                    }
                }
            }

            SourceType.AudioOutput -> {
                Spacer(Modifier.height(8.dp))
                Hint(
                    "Audio Output Capture: audio dari aplikasi lain (AudioPlaybackCapture). " +
                        "Volume dan filter diatur di halaman Audio.",
                )
            }
        }

        if (!isAudioSource) {
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
                    // Semua transformasi dikembalikan; crop punya tombolnya
                    // sendiri di bagian Crop.
                    updateTransform { defaultTransform(source, canvas) }
                },
            ) {
                Text(stringResource(R.string.scenes_reset_transformasi))
            }
        }

        // Nilai awal sumber, dipakai tombol reset per properti.
        val resetTransform = defaultTransform(source, canvas)

        LabeledSlider(
            label = stringResource(R.string.scenes_posisi_x),
            value = item.transform.x,
            range = 0f..canvas.width.toFloat(),
            format = { "%.0f px".format(it) },
            onReset = { updateTransform { it.copy(x = resetTransform.x) } },
        ) { value -> updateTransform { it.copy(x = value) } }

        LabeledSlider(
            label = stringResource(R.string.scenes_posisi_y),
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
            label = stringResource(R.string.scenes_scale_x),
            value = item.transform.scaleX,
            range = 0.05f..4f,
            format = { "%.2f×".format(it) },
            onReset = { updateTransform { it.copy(scaleX = resetTransform.scaleX) } },
        ) { value -> updateTransform { it.copy(scaleX = value) } }

        LabeledSlider(
            label = stringResource(R.string.scenes_scale_y),
            value = item.transform.scaleY,
            range = 0.05f..4f,
            format = { "%.2f×".format(it) },
            onReset = { updateTransform { it.copy(scaleY = resetTransform.scaleY) } },
        ) { value -> updateTransform { it.copy(scaleY = value) } }

        LabeledSlider(
            label = stringResource(R.string.scenes_rotasi),
            value = item.transform.rotation,
            range = -180f..180f,
            format = { "%.0f°".format(it) },
            onReset = { updateTransform { it.copy(rotation = 0f) } },
        ) { value -> updateTransform { it.copy(rotation = value) } }

        // Opasitas per item, seperti slider Opacity di OBS.
        LabeledSlider(
            label = stringResource(R.string.scenes_opasitas),
            value = item.opacity,
            range = 0f..1f,
            format = { "%.0f%%".format(it * 100f) },
            onReset = { updateItem(item.copy(opacity = 1f)) },
        ) { value -> updateItem(item.copy(opacity = value)) }

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
                Text(stringResource(R.string.scenes_reset_crop))
            }
        }
        CropSlider(stringResource(R.string.scenes_kiri_2), item.crop.left) { value ->
            updateItem(item.copy(crop = item.crop.copy(left = value)))
        }
        CropSlider(stringResource(R.string.scenes_atas), item.crop.top) { value ->
            updateItem(item.copy(crop = item.crop.copy(top = value)))
        }
        CropSlider(stringResource(R.string.scenes_kanan_2), item.crop.right) { value ->
            updateItem(item.copy(crop = item.crop.copy(right = value)))
        }
        CropSlider(stringResource(R.string.scenes_bawah), item.crop.bottom) { value ->
            updateItem(item.copy(crop = item.crop.copy(bottom = value)))
        }
        }

        if (!isAudioSource) {
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = { onCollectionChange(collection.moveLayer(item.id, +1)) },
            ) { Text(stringResource(R.string.scenes_naikkan_lapisan)) }
            TextButton(
                onClick = { onCollectionChange(collection.moveLayer(item.id, -1)) },
            ) { Text(stringResource(R.string.scenes_turunkan_lapisan)) }
        }
        }

        Spacer(Modifier.height(8.dp))
        // Menghapus item ini saja; sumber dibuang hanya bila tak terpakai lagi.
        TextButton(onClick = onDelete) { Text(stringResource(R.string.scenes_hapus_item)) }
        }
    }

    if (showDevicePicker) {
        AudioDevicePickerDialog(
            currentId = source.settings.audioDeviceId,
            onSelect = { option ->
                updateSource(
                    source.copy(
                        settings = source.settings.copy(
                            audioDeviceId = option?.id ?: 0,
                            audioDeviceName = option?.name,
                            audioDeviceType = option?.type ?: 0,
                            audioScoEnabled = if (option?.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                                source.settings.audioScoEnabled
                            } else {
                                false
                            },
                        ),
                    ),
                )
                showDevicePicker = false
            },
            onDismiss = { showDevicePicker = false },
        )
    }
}

@Composable
private fun AudioSourceEditor(
    canvas: CaptureConfig,
    collection: SceneCollection,
    item: SceneItem,
    source: SceneSource,
    onCollectionChange: (SceneCollection) -> Unit,
    onBack: () -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember(source.id) { mutableStateOf(source.name) }
    var showDevicePicker by remember(source.id) { mutableStateOf(false) }

    val mix by AudioMixer.mix.collectAsState()
    val levels by AudioMixer.levels.collectAsState()
    val runtime by AudioMixer.runtime.collectAsState()

    val channelId = AudioChannelId.forSource(source.id)
    val channel = mix.channel(channelId)
    val level = levels.forChannel(channelId)
    val ready = runtime.running && runtime.ready(channelId)
    val isBluetooth = source.settings.audioDeviceType == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO

    fun updateSource(next: SceneSource) = onCollectionChange(collection.updateSource(next))
    fun updateItem(next: SceneItem) = onCollectionChange(collection.updateItem(next))

    val statusText = when {
        !channel.enabled -> "Nonaktif"
        !runtime.running -> "Sesi belum aktif"
        !ready -> "Gagal dibuka — cek izin mikrofon/perangkat"
        channel.muted -> "Aktif (muted)"
        else -> "Aktif — mic mengirim suara"
    }
    val statusColor = when {
        !channel.enabled || !runtime.running -> MaterialTheme.colorScheme.onSurfaceVariant
        !ready -> MaterialTheme.colorScheme.error
        channel.muted -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.common_kembali_panah)) }
            Text(
                text = source.name,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // Kunci/unlock item langsung dari halaman sumber audio.
            RowIconAction(
                onClick = { updateItem(item.copy(locked = !item.locked)) },
                background = if (item.locked) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                } else {
                    null
                },
            ) {
                Icon(
                    painter = painterResource(
                        if (item.locked) R.drawable.ic_lock else R.drawable.ic_lock_open,
                    ),
                    contentDescription = if (item.locked) "Buka kunci" else "Kunci posisi",
                    tint = if (item.locked) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = if (source.type == SourceType.AudioInput) {
                        "Audio Input Capture"
                    } else {
                        "Audio Output Capture"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = if (source.type == SourceType.AudioInput) {
                        "Sumber audio dari mikrofon / perangkat input. Tidak tampil di kanvas."
                    } else {
                        "Sumber audio playback dari aplikasi lain (AudioPlaybackCapture)."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))

                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        updateSource(source.copy(name = it))
                    },
                    label = { Text(stringResource(R.string.scenes_nama_sumber)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (source.type == SourceType.AudioInput) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.scenes_perangkat_n, source.settings.audioDeviceName ?: androidx.compose.ui.platform.LocalContext.current.getString(R.string.common_default)),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = when (source.settings.audioDeviceType) {
                            android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Mikrofon bawaan"
                            android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth / TWS (SCO)"
                            android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Headset kabel"
                            android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
                            android.media.AudioDeviceInfo.TYPE_USB_DEVICE,
                            android.media.AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB"
                            android.media.AudioDeviceInfo.TYPE_LINE_ANALOG,
                            android.media.AudioDeviceInfo.TYPE_LINE_DIGITAL -> "Line in"
                            0 -> "Mengikuti perangkat input sistem"
                            else -> "Audio input"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { showDevicePicker = true }) {
                        Text(stringResource(R.string.scenes_ganti_perangkat_input))
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.scenes_level_input), style = MaterialTheme.typography.bodyMedium)
                VolumeMeter(
                    level = level,
                    active = ready && channel.enabled && !channel.muted,
                    decay = mix.meterDecay,
                    vertical = false,
                    modifier = Modifier.fillMaxWidth(),
                    showScale = true,
                    muted = channel.muted,
                    peakType = mix.peakMeterType,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColor,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "Volume, mute, filter, balance, sync offset, track, dan monitoring " +
                "diatur di tab Audio.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onDelete) { Text(stringResource(R.string.scenes_hapus_sumber)) }
    }

    if (showDevicePicker) {
        AudioDevicePickerDialog(
            currentId = source.settings.audioDeviceId,
            onSelect = { option ->
                updateSource(
                    source.copy(
                        settings = source.settings.copy(
                            audioDeviceId = option?.id ?: 0,
                            audioDeviceName = option?.name,
                            audioDeviceType = option?.type ?: 0,
                            audioScoEnabled = if (option?.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                                source.settings.audioScoEnabled
                            } else {
                                false
                            },
                        ),
                    ),
                )
                showDevicePicker = false
            },
            onDismiss = { showDevicePicker = false },
        )
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
                        description = stringResource(R.string.scenes_reset_properti, label),
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
        label = stringResource(R.string.scenes_crop_n, label),
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
        Text(stringResource(R.string.scenes_titik_jangkar), style = MaterialTheme.typography.bodyMedium)
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
            ) { Text(stringResource(R.string.common_simpan)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_batal)) }
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


@Composable
private fun typeLabel(type: SourceType): String = when (type) {
    SourceType.Screen -> stringResource(R.string.scenes_tipe_screen)
    SourceType.Image -> stringResource(R.string.scenes_tipe_image)
    SourceType.Text -> stringResource(R.string.scenes_tipe_text)
    SourceType.Color -> stringResource(R.string.scenes_tipe_color)
    SourceType.AudioInput -> stringResource(R.string.scenes_tipe_audio_input)
    SourceType.AudioOutput -> stringResource(R.string.scenes_tipe_audio_output)
}

private fun typeSymbol(type: SourceType): String = when (type) {
    SourceType.Screen -> "▣"
    SourceType.Image -> "▤"
    SourceType.Text -> "T"
    SourceType.Color -> "■"
    SourceType.AudioInput -> "◉"
    SourceType.AudioOutput -> "◎"
}

@Composable
private fun intrinsicLabel(source: SceneSource): String = when (source.type) {
    SourceType.Screen -> stringResource(R.string.scenes_intrinsic_screen)
    SourceType.AudioInput, SourceType.AudioOutput -> stringResource(R.string.scenes_intrinsic_audio)
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
