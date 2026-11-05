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
import androidx.compose.material3.FilterChip
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
import com.rout.air.data.ObsImport
import com.rout.air.data.SceneStore
import com.rout.air.data.addScene
import com.rout.air.data.addScreenItem
import com.rout.air.data.addSource
import com.rout.air.data.duplicateScene
import com.rout.air.data.isScreensaverScene
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
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    var showScreensaverPage by remember { mutableStateOf(false) }
    var showAppAudioPicker by remember { mutableStateOf(false) }
    var replaceSourceId by remember { mutableStateOf<String?>(null) }
    var replaceMediaSourceId by remember { mutableStateOf<String?>(null) }
    var showGroupDialog by remember { mutableStateOf(false) }
    var groupAsScene by remember { mutableStateOf(false) }
    var slideTargetId by remember { mutableStateOf<String?>(null) }
    var slideCreating by remember { mutableStateOf(false) }
    var showBrowserDialog by remember { mutableStateOf(false) }
    var browserEditSourceId by remember { mutableStateOf<String?>(null) }
    var browserUrl by remember { mutableStateOf("") }
    var importReport by remember { mutableStateOf<ObsImport.Report?>(null) }

    val activeScene = collection.activeScene()
    val hasScreenInActiveScene = activeScene?.items?.any { item ->
        collection.source(item.sourceId)?.type == SourceType.Screen
    } == true
    val selectedItem = collection.item(selectedItemId)
    val selectedSource = selectedItem?.let { collection.source(it.sourceId) }

    // Nama bawaan sumber harus dibaca di sini: callback launcher bukan
    // fungsi composable sehingga stringResource tidak bisa dipanggil di sana.
    val nameImage = stringResource(R.string.src_gambar)
    val nameSlideShow = stringResource(R.string.src_slideshow)
    val nameMedia = stringResource(R.string.src_media)
    val nameCamera = stringResource(R.string.src_kamera)
    val nameBrowser = stringResource(R.string.src_browser)
    val nameText = stringResource(R.string.src_teks)
    val nameColor = stringResource(R.string.src_warna)
    val nameGroup = stringResource(R.string.src_grup)
    val nameScene = stringResource(R.string.src_adegan)

    var showUrlDialog by remember { mutableStateOf(false) }
    var urlError by remember { mutableStateOf<String?>(null) }
    var urlLoading by remember { mutableStateOf(false) }
    val pageScope = rememberCoroutineScope()

    // Menambah sumber gambar baru atau mengganti gambar sumber terpilih.
    val applyImagePath: (String) -> Unit = { path ->
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
        } else {
            val source = SceneSource(
                type = SourceType.Image,
                name = uniqueSourceName(collection, nameImage),
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
    }

    // Pemilih gambar: disalin ke penyimpanan aplikasi agar path tetap valid.
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) {
            // Pilihan dibatalkan: jangan biarkan mode "ganti gambar" tertinggal.
            replaceSourceId = null
            return@rememberLauncherForActivityResult
        }
        val path = store.importImage(uri) ?: return@rememberLauncherForActivityResult
        applyImagePath(path)
    }

    // Slide show: banyak gambar sekaligus, disalin ke penyimpanan aplikasi.
    val slidePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(),
    ) { uris ->
        if (uris.isEmpty()) {
            slideTargetId = null
            slideCreating = false
            return@rememberLauncherForActivityResult
        }
        val paths = uris.mapNotNull { store.importImage(it) }
        if (paths.isEmpty()) {
            slideTargetId = null
            slideCreating = false
            return@rememberLauncherForActivityResult
        }
        val size = SourceBitmap.probeSize(paths.first(), canvasConfig.width, canvasConfig.height)
            ?: (canvasConfig.width to canvasConfig.height)
        val target = collection.source(slideTargetId.orEmpty())
        when {
            slideCreating -> {
                val source = SceneSource(
                    type = SourceType.SlideShow,
                    name = uniqueSourceName(collection, nameSlideShow),
                    settings = SourceSettings(
                        imagePaths = paths,
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

            target != null -> onCollectionChange(
                collection.updateSource(
                    target.copy(
                        settings = target.settings.copy(
                            imagePaths = target.settings.imagePaths + paths,
                        ),
                    ),
                ),
            )
        }
        slideTargetId = null
        slideCreating = false
    }

    // Izin kamera diminta sekali; sumber baru dibuat setelah disetujui.
    // Pemilih video untuk sumber Media (baru atau mengganti yang lama).
    val mediaPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) {
            replaceMediaSourceId = null
            return@rememberLauncherForActivityResult
        }
        val retriever = android.media.MediaMetadataRetriever()
        var width = 1280
        var height = 720
        runCatching {
            retriever.setDataSource(context, uri)
            val w = retriever.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH,
            )?.toIntOrNull() ?: 1280
            val h = retriever.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT,
            )?.toIntOrNull() ?: 720
            val rotation = retriever.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION,
            )?.toIntOrNull() ?: 0
            if (rotation == 90 || rotation == 270) {
                width = h
                height = w
            } else {
                width = w
                height = h
            }
        }
        runCatching { retriever.release() }
        val path = store.importMedia(uri) ?: return@rememberLauncherForActivityResult

        val replacing = collection.source(replaceMediaSourceId.orEmpty())
        replaceMediaSourceId = null
        if (replacing != null) {
            onCollectionChange(
                collection.updateSource(
                    replacing.copy(
                        settings = replacing.settings.copy(
                            mediaPath = path,
                            width = width,
                            height = height,
                        ),
                    ),
                ),
            )
        } else {
            val source = SceneSource(
                type = SourceType.Media,
                name = uniqueSourceName(collection, nameMedia),
                settings = SourceSettings(mediaPath = path, width = width, height = height),
            )
            val item = SceneItem(
                sourceId = source.id,
                transform = fitTransform(width, height, canvasConfig),
            )
            onCollectionChange(collection.addSource(source, item))
            onSelectItem(item.id)
        }
    }

    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            android.widget.Toast.makeText(
                context,
                context.getString(R.string.scenes_kamera_izin),
                android.widget.Toast.LENGTH_LONG,
            ).show()
            return@rememberLauncherForActivityResult
        }
        val source = SceneSource(
            type = SourceType.Camera,
            name = uniqueSourceName(collection, nameCamera),
            settings = SourceSettings(width = 1280, height = 720),
        )
        val item = SceneItem(
            sourceId = source.id,
            transform = fitTransform(1280, 720, canvasConfig),
        )
        onCollectionChange(collection.addSource(source, item))
        onSelectItem(item.id)
    }

    // Impor scene-collection OBS: pengguna memilih berkas JSON dari HP.
    val obsPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        if (text == null) {
            importReport = ObsImport.Report(
                collection = null,
                error = ObsImport.ParseError.UNREADABLE,
            )
            return@rememberLauncherForActivityResult
        }
        val report = ObsImport.parse(text, canvasConfig.width, canvasConfig.height)
        val imported = report.collection
        if (imported != null) {
            val merged = ObsImport.merge(collection, imported)
            // Langsung buka adegan yang aktif di OBS agar pengguna tidak
            // perlu mencari hasil impor di daftar Adegan.
            val target = merged.scenes.firstOrNull { it.id == imported.activeSceneId }
                ?: merged.scenes.lastOrNull()
            onCollectionChange(
                if (target != null) merged.copy(activeSceneId = target.id) else merged,
            )
            onSelectItem(null)
        }
        importReport = report
    }

    BackHandler(enabled = selectedItem != null) { onSelectItem(null) }

    if (showScreensaverPage) {
        ScreensaverPage(
            onBack = { showScreensaverPage = false },
            modifier = Modifier.fillMaxSize(),
        )
        return
    }

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
            onPickMedia = {
                replaceMediaSourceId = selectedSource.id
                mediaPicker.launch(arrayOf("video/*"))
            },
            onAddSlides = {
                slideTargetId = selectedSource.id
                slideCreating = false
                slidePicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
            onEditBrowser = {
                browserEditSourceId = selectedSource.id
                browserUrl = selectedSource.settings.browserUrl.orEmpty()
                showBrowserDialog = true
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
            TextButton(onClick = { obsPicker.launch(arrayOf("application/json", "text/plain", "*/*")) }) {
                Text(stringResource(R.string.scenes_import_obs))
            }
            TextButton(onClick = { showAddScene = true }) { Text(stringResource(R.string.scenes_tambah_2)) }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            collection.scenes.filterNot { it.isScreensaverScene() }.forEach { scene ->
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
            stringResource(R.string.scenes_hint_order),
        )

        // Layar jeda punya bagian sendiri, terpisah dari daftar scene.
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.scenes_screensaver),
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(Modifier.height(6.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showScreensaverPage = true },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_screensaver),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.screensaver_section_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(text = "›", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
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

    if (showUrlDialog) {
        UrlSourceDialog(
            loading = urlLoading,
            error = urlError,
            onDismiss = {
                if (!urlLoading) {
                    showUrlDialog = false
                    urlError = null
                }
            },
            onConfirm = { url ->
                urlLoading = true
                urlError = null
                pageScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        com.rout.air.data.ImageFetcher.download(context, url)
                    }
                    urlLoading = false
                    result.onSuccess { path ->
                        showUrlDialog = false
                        applyImagePath(path)
                    }.onFailure { error ->
                        urlError = error.message ?: "gagal"
                    }
                }
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
            onImageUrl = {
                showAddSource = false
                urlError = null
                showUrlDialog = true
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
                    name = uniqueSourceName(collection, nameText),
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
                    name = uniqueSourceName(collection, nameColor),
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
            onCamera = {
                showAddSource = false
                cameraPermission.launch(android.Manifest.permission.CAMERA)
            },
            onMedia = {
                showAddSource = false
                replaceMediaSourceId = null
                mediaPicker.launch(arrayOf("video/*"))
            },
            onBrowser = {
                showAddSource = false
                browserEditSourceId = null
                browserUrl = "https://"
                showBrowserDialog = true
            },
            onGroup = {
                showAddSource = false
                groupAsScene = false
                showGroupDialog = true
            },
            onScene = {
                showAddSource = false
                groupAsScene = true
                showGroupDialog = true
            },
            onSlideshow = {
                showAddSource = false
                slideTargetId = null
                slideCreating = true
                slidePicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
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

    if (showGroupDialog) {
        AlertDialog(
            onDismissRequest = { showGroupDialog = false },
            title = { Text(stringResource(R.string.scenes_grup_pilih)) },
            text = {
                Column {
                    collection.scenes.filterNot { it.isScreensaverScene() }.forEach { scene ->
                        TextButton(
                            onClick = {
                                val prefix = if (groupAsScene) nameScene else nameGroup
                                val source = SceneSource(
                                    type = SourceType.Group,
                                    name = uniqueSourceName(
                                        collection,
                                        context.getString(
                                            R.string.source_name_with_scene,
                                            prefix,
                                            scene.name,
                                        ),
                                    ),
                                    settings = SourceSettings(
                                        groupSceneId = scene.id,
                                        width = canvasConfig.width,
                                        height = canvasConfig.height,
                                    ),
                                )
                                val item = SceneItem(sourceId = source.id, transform = Transform())
                                onCollectionChange(collection.addSource(source, item))
                                onSelectItem(item.id)
                                showGroupDialog = false
                            },
                        ) { Text(scene.name) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showGroupDialog = false }) { Text(stringResource(R.string.common_batal)) }
            },
        )
    }

    if (showBrowserDialog) {
        AlertDialog(
            onDismissRequest = { showBrowserDialog = false },
            title = { Text(stringResource(R.string.scenes_tipe_browser)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = browserUrl,
                        onValueChange = { browserUrl = it },
                        label = { Text(stringResource(R.string.scenes_browser_url)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Hint(stringResource(R.string.scenes_browser_hint))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val url = browserUrl.trim()
                        if (url.isNotBlank()) {
                            val editing = collection.source(browserEditSourceId.orEmpty())
                            if (editing != null) {
                                onCollectionChange(
                                    collection.updateSource(
                                        editing.copy(
                                            settings = editing.settings.copy(browserUrl = url),
                                        ),
                                    ),
                                )
                            } else {
                                val source = SceneSource(
                                    type = SourceType.Browser,
                                    name = uniqueSourceName(collection, nameBrowser),
                                    settings = SourceSettings(
                                        browserUrl = url,
                                        width = canvasConfig.width,
                                        height = canvasConfig.height,
                                    ),
                                )
                                val item = SceneItem(
                                    sourceId = source.id,
                                    transform = Transform(),
                                )
                                onCollectionChange(collection.addSource(source, item))
                                onSelectItem(item.id)
                            }
                        }
                        showBrowserDialog = false
                    },
                ) { Text(stringResource(R.string.common_simpan)) }
            },
            dismissButton = {
                TextButton(onClick = { showBrowserDialog = false }) {
                    Text(stringResource(R.string.common_batal))
                }
            },
        )
    }

    importReport?.let { report ->
        AlertDialog(
            onDismissRequest = { importReport = null },
            title = { Text(stringResource(R.string.scenes_import_obs)) },
            text = {
                Column {
                    val error = report.error
                    if (error != null) {
                        Text(obsErrorText(error))
                    } else {
                        Text(
                            stringResource(
                                R.string.scenes_import_obs_hasil,
                                report.scenes,
                                report.sources,
                            ),
                        )
                        if (report.skipped.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text(stringResource(R.string.scenes_import_obs_dilewati))
                            report.skipped.forEach { item ->
                                Text("• ${obsSkippedText(item)}")
                            }
                        }
                        report.notes.forEach { note ->
                            Spacer(Modifier.height(4.dp))
                            Text(obsNoteText(note))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { importReport = null }) {
                    Text(stringResource(R.string.common_ok))
                }
            },
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
        label = "geser-hapus",
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
                            contentDescription = stringResource(
                                if (item.locked) R.string.icon_unlock else R.string.icon_lock,
                            ),
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
    onPickMedia: () -> Unit,
    onEditBrowser: () -> Unit,
    onAddSlides: () -> Unit,
) {
    var name by remember(source.id) { mutableStateOf(source.name) }
    var text by remember(source.id) { mutableStateOf(source.settings.text) }
    var textSize by remember(source.id) { mutableStateOf(source.settings.textSize) }
    var showDevicePicker by remember(source.id) { mutableStateOf(false) }
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
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
                    contentDescription = stringResource(
                        if (item.locked) R.string.icon_unlock else R.string.icon_lock,
                    ),
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

            SourceType.SlideShow -> {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(
                        R.string.scenes_slideshow_n_gambar,
                        source.settings.imagePaths.size,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onAddSlides) {
                    Text(stringResource(R.string.scenes_slideshow_tambah))
                }
                Text(stringResource(R.string.scenes_slideshow_interval))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(3, 5, 10).forEach { seconds ->
                        FilterChip(
                            selected = source.settings.slideIntervalSec == seconds,
                            onClick = {
                                updateSource(
                                    source.copy(
                                        settings = source.settings.copy(
                                            slideIntervalSec = seconds,
                                        ),
                                    ),
                                )
                            },
                            label = { Text("$seconds s") },
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.scenes_slideshow_acak),
                        modifier = Modifier.weight(1f),
                    )
                    androidx.compose.material3.Switch(
                        checked = source.settings.slideShuffle,
                        onCheckedChange = { enabled ->
                            updateSource(
                                source.copy(
                                    settings = source.settings.copy(slideShuffle = enabled),
                                ),
                            )
                        },
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.scenes_slideshow_fade),
                        modifier = Modifier.weight(1f),
                    )
                    androidx.compose.material3.Switch(
                        checked = source.settings.slideFade,
                        onCheckedChange = { enabled ->
                            updateSource(
                                source.copy(
                                    settings = source.settings.copy(slideFade = enabled),
                                ),
                            )
                        },
                    )
                }
            }

            SourceType.Image -> {
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onPickImage) { Text(stringResource(R.string.scenes_ganti_gambar)) }
            }

            SourceType.Group -> {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.scenes_tipe_grup), style = MaterialTheme.typography.titleSmall)
                val nestedName = collection.scenes
                    .firstOrNull { it.id == source.settings.groupSceneId }?.name
                Text(
                    text = nestedName ?: stringResource(R.string.scenes_grup_pilih),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Hint(stringResource(R.string.scenes_grup_hint))
            }

            SourceType.Browser -> {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.scenes_tipe_browser), style = MaterialTheme.typography.titleSmall)
                Text(
                    text = source.settings.browserUrl.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onEditBrowser) {
                    Text(stringResource(R.string.scenes_browser_buka))
                }
                Text(
                    stringResource(R.string.scenes_browser_refresh),
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0.5f, 1f, 2f, 5f).forEach { seconds ->
                        FilterChip(
                            selected = source.settings.browserRefreshSec == seconds,
                            onClick = {
                                updateSource(
                                    source.copy(
                                        settings = source.settings.copy(
                                            browserRefreshSec = seconds,
                                        ),
                                    ),
                                )
                            },
                            label = {
                                Text(
                                    stringResource(
                                        R.string.scenes_browser_interval_n,
                                        seconds,
                                    ),
                                )
                            },
                        )
                    }
                }
                Text(
                    stringResource(R.string.scenes_browser_zoom),
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0.75f, 1f, 1.25f, 1.5f).forEach { zoom ->
                        FilterChip(
                            selected = source.settings.browserZoom == zoom,
                            onClick = {
                                updateSource(
                                    source.copy(
                                        settings = source.settings.copy(browserZoom = zoom),
                                    ),
                                )
                            },
                            label = { Text("%.0f%%".format(zoom * 100f)) },
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.scenes_browser_desktop),
                        modifier = Modifier.weight(1f),
                    )
                    androidx.compose.material3.Switch(
                        checked = source.settings.browserDesktop,
                        onCheckedChange = { enabled ->
                            updateSource(
                                source.copy(
                                    settings = source.settings.copy(browserDesktop = enabled),
                                ),
                            )
                        },
                    )
                }
                TextButton(
                    onClick = {
                        updateSource(
                            source.copy(
                                settings = source.settings.copy(
                                    browserReloadToken =
                                        source.settings.browserReloadToken + 1,
                                ),
                            ),
                        )
                    },
                ) {
                    Text(stringResource(R.string.scenes_browser_reload))
                }
                Hint(stringResource(R.string.scenes_browser_hint))
            }

            SourceType.Media -> {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.scenes_tipe_media), style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = onPickMedia) { Text(stringResource(R.string.scenes_media_ganti)) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        false to stringResource(R.string.scenes_media_play),
                        true to stringResource(R.string.scenes_media_pause),
                    ).forEach { (paused, label) ->
                        FilterChip(
                            selected = source.settings.mediaPaused == paused,
                            onClick = {
                                updateSource(
                                    source.copy(
                                        settings = source.settings.copy(mediaPaused = paused),
                                    ),
                                )
                            },
                            label = { Text(label) },
                        )
                    }
                }
                Text(
                    stringResource(R.string.scenes_media_speed),
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0.5f, 1f, 1.5f, 2f).forEach { speed ->
                        FilterChip(
                            selected = source.settings.mediaSpeed == speed,
                            onClick = {
                                updateSource(
                                    source.copy(
                                        settings = source.settings.copy(mediaSpeed = speed),
                                    ),
                                )
                            },
                            label = {
                                Text(stringResource(R.string.scenes_media_speed_n, speed))
                            },
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.scenes_media_loop),
                        modifier = Modifier.weight(1f),
                    )
                    androidx.compose.material3.Switch(
                        checked = source.settings.mediaLoop,
                        onCheckedChange = { enabled ->
                            updateSource(
                                source.copy(
                                    settings = source.settings.copy(mediaLoop = enabled),
                                ),
                            )
                        },
                    )
                }
                TextButton(
                    onClick = {
                        updateSource(
                            source.copy(
                                settings = source.settings.copy(
                                    mediaRestartToken =
                                        source.settings.mediaRestartToken + 1,
                                ),
                            ),
                        )
                    },
                ) {
                    Text(stringResource(R.string.scenes_media_restart))
                }
                var seekPercent by remember(source.id) { mutableFloatStateOf(0f) }
                Text(
                    stringResource(
                        R.string.scenes_media_seek,
                        "${seekPercent.roundToInt()}%",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                Slider(
                    value = seekPercent,
                    onValueChange = { seekPercent = it },
                    onValueChangeFinished = {
                        // Seek hanya saat jari dilepas supaya MediaPlayer
                        // tidak dibanjiri permintaan posisi.
                        updateSource(
                            source.copy(
                                settings = source.settings.copy(
                                    mediaSeekPercent = seekPercent,
                                    mediaSeekToken = source.settings.mediaSeekToken + 1,
                                ),
                            ),
                        )
                    },
                    valueRange = 0f..100f,
                )
                Hint(stringResource(R.string.scenes_media_hint))
            }

            SourceType.Camera -> {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.scenes_tipe_camera), style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        0 to stringResource(R.string.scenes_kamera_belakang),
                        1 to stringResource(R.string.scenes_kamera_depan),
                    ).forEach { (facing, label) ->
                        FilterChip(
                            selected = source.settings.cameraFacing == facing,
                            onClick = {
                                // Ganti menghadap juga melepas pilihan lensa
                                // supaya tidak tertahan di lensa arah lama.
                                updateSource(
                                    source.copy(
                                        settings = source.settings.copy(
                                            cameraFacing = facing,
                                            cameraId = null,
                                        ),
                                    ),
                                )
                            },
                            label = { Text(label) },
                        )
                    }
                }
                val lenses = remember {
                    val manager = context.getSystemService(
                        android.hardware.camera2.CameraManager::class.java,
                    )
                    manager?.cameraIdList?.mapNotNull { id ->
                        val info = runCatching {
                            manager.getCameraCharacteristics(id)
                        }.getOrNull() ?: return@mapNotNull null
                        val front = info.get(
                            android.hardware.camera2.CameraCharacteristics.LENS_FACING,
                        ) == android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT
                        val focal = info.get(
                            android.hardware.camera2.CameraCharacteristics
                                .LENS_INFO_AVAILABLE_FOCAL_LENGTHS,
                        )?.firstOrNull()
                        Triple(id, front, focal)
                    }.orEmpty()
                }
                if (lenses.size > 1) {
                    Text(
                        stringResource(R.string.scenes_kamera_lensa),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        FilterChip(
                            selected = source.settings.cameraId == null,
                            onClick = {
                                updateSource(
                                    source.copy(
                                        settings = source.settings.copy(cameraId = null),
                                    ),
                                )
                            },
                            label = { Text(stringResource(R.string.settings_otomatis)) },
                        )
                        lenses.forEach { (id, front, focal) ->
                            val prefix = stringResource(
                                if (front) R.string.scenes_kamera_depan
                                else R.string.scenes_kamera_belakang,
                            )
                            FilterChip(
                                selected = source.settings.cameraId == id,
                                onClick = {
                                    updateSource(
                                        source.copy(
                                            settings = source.settings.copy(cameraId = id),
                                        ),
                                    )
                                },
                                label = {
                                    Text(
                                        if (focal != null) {
                                            "$prefix ${"%.0f".format(focal)} mm"
                                        } else {
                                            prefix
                                        },
                                    )
                                },
                            )
                        }
                    }
                }
                Text(
                    stringResource(R.string.scenes_kamera_resolusi),
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        0 to stringResource(R.string.settings_otomatis),
                        720 to "720p",
                        1080 to "1080p",
                    ).forEach { (height, label) ->
                        FilterChip(
                            selected = source.settings.cameraHeight == height,
                            onClick = {
                                updateSource(
                                    source.copy(
                                        settings = source.settings.copy(cameraHeight = height),
                                    ),
                                )
                            },
                            label = { Text(label) },
                        )
                    }
                }
                LabeledSlider(
                    label = stringResource(R.string.scenes_kamera_zoom),
                    value = source.settings.cameraZoom,
                    range = 1f..3f,
                    format = { "%.1f×".format(it) },
                    onReset = {
                        updateSource(
                            source.copy(settings = source.settings.copy(cameraZoom = 1f)),
                        )
                    },
                ) { value ->
                    updateSource(
                        source.copy(settings = source.settings.copy(cameraZoom = value)),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.scenes_kamera_torch),
                        modifier = Modifier.weight(1f),
                    )
                    androidx.compose.material3.Switch(
                        checked = source.settings.cameraTorch,
                        onCheckedChange = { enabled ->
                            updateSource(
                                source.copy(
                                    settings = source.settings.copy(cameraTorch = enabled),
                                ),
                            )
                        },
                    )
                }
                Hint(stringResource(R.string.scenes_kamera_hint))
            }

            SourceType.Screen -> {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.scenes_template),
                    style = MaterialTheme.typography.titleSmall,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(
                        com.rout.air.core.ScreenDisplay.Auto to
                            stringResource(R.string.settings_otomatis),
                        com.rout.air.core.ScreenDisplay.Portrait to
                            stringResource(R.string.common_vertikal),
                        com.rout.air.core.ScreenDisplay.Landscape to
                            stringResource(R.string.common_horizontal),
                    ).forEach { (display, label) ->
                        FilterChip(
                            selected = (source.settings.screenDisplay
                                ?: com.rout.air.core.ScreenDisplay.Auto) == display,
                            onClick = {
                                // Kotak item ikut dibentuk ulang agar bingkai
                                // pilihan di pratinjau juga ikut horizontal
                                // atau vertikal, bukan hanya isinya.
                                val base = canvas.width.toFloat() / canvas.height.toFloat()
                                val aspect = when (display) {
                                    com.rout.air.core.ScreenDisplay.Landscape ->
                                        if (base >= 1f) base else 1f / base

                                    com.rout.air.core.ScreenDisplay.Portrait ->
                                        if (base <= 1f) base else 1f / base

                                    com.rout.air.core.ScreenDisplay.Auto -> base
                                }
                                val boxWidth = min(canvas.width.toFloat(), canvas.height * aspect)
                                val boxHeight = boxWidth / aspect
                                val changed = collection
                                    .updateSource(
                                        source.copy(
                                            settings = source.settings.copy(
                                                screenDisplay = display,
                                            ),
                                        ),
                                    )
                                    .updateItem(
                                        item.copy(
                                            transform = Transform(
                                                x = canvas.width / 2f,
                                                y = canvas.height / 2f,
                                                scaleX = boxWidth / canvas.width,
                                                scaleY = boxHeight / canvas.height,
                                                align = Align.Center,
                                            ),
                                        ),
                                    )
                                onCollectionChange(changed)
                                // Template tampilan bukan perubahan komposisi:
                                // langsung dipakai Program supaya pratinjau dan
                                // hasil rekaman/live sama tanpa perlu Transisi.
                                com.rout.air.data.SceneManager.commitToProgram()
                            },
                            label = { Text(label) },
                        )
                    }
                }
                Hint(stringResource(R.string.scenes_template_hint))
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
                            stringResource(R.string.scenes_audio_bt_hint)

                        android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC ->
                            stringResource(R.string.scenes_audio_builtin)

                        else -> stringResource(R.string.scenes_audio_common_hint)
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
                                stringResource(R.string.scenes_audio_sco_hint),
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
                Hint(stringResource(R.string.scenes_audio_output_hint))
            }
        }

        if (!isAudioSource) {
        Spacer(Modifier.height(20.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.scenes_transformasi_title),
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
            label = stringResource(
                if (uniformDiffers) R.string.scenes_scale_uniform_mixed
                else R.string.scenes_scale_uniform,
            ),
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
        !channel.enabled -> stringResource(R.string.audio_status_off)
        !runtime.running -> stringResource(R.string.audio_status_no_session)
        !ready -> stringResource(R.string.audio_status_failed)
        channel.muted -> stringResource(R.string.audio_status_muted)
        else -> stringResource(R.string.audio_status_on)
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
                    contentDescription = stringResource(
                        if (item.locked) R.string.icon_unlock else R.string.icon_lock,
                    ),
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
                        stringResource(R.string.scenes_tipe_audio_input)
                    } else {
                        stringResource(R.string.scenes_tipe_audio_output)
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = if (source.type == SourceType.AudioInput) {
                        stringResource(R.string.scenes_audio_input_desc)
                    } else {
                        stringResource(R.string.scenes_audio_output_desc)
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
                            android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC ->
                                stringResource(R.string.audio_device_builtin)
                            android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO ->
                                stringResource(R.string.audio_device_bt)
                            android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ->
                                stringResource(R.string.audio_device_wired)
                            android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
                            android.media.AudioDeviceInfo.TYPE_USB_DEVICE,
                            android.media.AudioDeviceInfo.TYPE_USB_ACCESSORY ->
                                stringResource(R.string.audio_device_usb)
                            android.media.AudioDeviceInfo.TYPE_LINE_ANALOG,
                            android.media.AudioDeviceInfo.TYPE_LINE_DIGITAL ->
                                stringResource(R.string.audio_device_line)
                            0 -> stringResource(R.string.audio_device_follow)
                            else -> stringResource(R.string.scenes_tipe_audio_input)
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
            stringResource(R.string.audio_volume_hint),
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


/** Pesan kesalahan impor OBS. */
@Composable
private fun obsErrorText(error: ObsImport.ParseError): String = stringResource(
    when (error) {
        ObsImport.ParseError.NOT_JSON -> R.string.obs_error_not_json
        ObsImport.ParseError.NO_SOURCES -> R.string.obs_error_no_sources
        ObsImport.ParseError.NO_SCENES -> R.string.obs_error_no_scenes
        ObsImport.ParseError.UNREADABLE -> R.string.scenes_import_unreadable
    },
)

/** Satu baris daftar sumber yang dilewati saat impor OBS. */
@Composable
private fun obsSkippedText(item: ObsImport.Skipped): String {
    val id = item.obsId
        ?: return stringResource(R.string.obs_skipped_not_found, item.name)
    return stringResource(R.string.obs_skipped_unsupported, item.name, obsKindLabel(id))
}

/** Jenis sumber OBS dalam bahasa aplikasi. */
@Composable
private fun obsKindLabel(obsId: String): String = stringResource(
    when {
        obsId.startsWith("dshow") || obsId.startsWith("v4l2") ||
            obsId.startsWith("av_capture") -> R.string.obs_kind_camera
        obsId.startsWith("browser") -> R.string.obs_kind_browser
        obsId == "group" -> R.string.obs_kind_group
        obsId.contains("media") || obsId.startsWith("ffmpeg") -> R.string.obs_kind_media
        obsId.startsWith("ndi") -> R.string.obs_kind_ndi
        else -> R.string.obs_kind_unknown
    },
)

/** Catatan hasil impor OBS. */
@Composable
private fun obsNoteText(note: ObsImport.Note): String = when (note.kind) {
    ObsImport.NoteKind.SCREEN_FALLBACK ->
        stringResource(R.string.obs_note_screen_fallback, note.name)
    ObsImport.NoteKind.IMAGE_REPICK ->
        stringResource(R.string.obs_note_image_repick, note.name)
}

@Composable
private fun typeLabel(type: SourceType): String = when (type) {
    SourceType.Screen -> stringResource(R.string.scenes_tipe_screen)
    SourceType.Image -> stringResource(R.string.scenes_tipe_image)
    SourceType.Text -> stringResource(R.string.scenes_tipe_text)
    SourceType.Color -> stringResource(R.string.scenes_tipe_color)
    SourceType.AudioInput -> stringResource(R.string.scenes_tipe_audio_input)
    SourceType.AudioOutput -> stringResource(R.string.scenes_tipe_audio_output)
    SourceType.Camera -> stringResource(R.string.scenes_tipe_camera)
    SourceType.Media -> stringResource(R.string.scenes_tipe_media)
    SourceType.Browser -> stringResource(R.string.scenes_tipe_browser)
    SourceType.Group -> stringResource(R.string.scenes_tipe_grup)
    SourceType.SlideShow -> stringResource(R.string.scenes_tipe_slideshow)
}

private fun typeSymbol(type: SourceType): String = when (type) {
    SourceType.Screen -> "▣"
    SourceType.Image -> "▤"
    SourceType.Text -> "T"
    SourceType.Color -> "■"
    SourceType.AudioInput -> "◉"
    SourceType.AudioOutput -> "◎"
    SourceType.Camera -> "◨"
    SourceType.Media -> "▶"
    SourceType.Browser -> "◍"
    SourceType.Group -> "▥"
    SourceType.SlideShow -> "▤"
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
