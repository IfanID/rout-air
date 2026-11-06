package com.rout.air.ui.pages.scenes

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import com.rout.air.R
import com.rout.air.core.model.Align
import com.rout.air.core.audio.AudioChannelId
import com.rout.air.core.model.CaptureConfig
import com.rout.air.core.model.Crop
import com.rout.air.core.model.SceneCollection
import com.rout.air.core.model.SceneItem
import com.rout.air.core.model.SceneSource
import com.rout.air.core.model.SourceSettings
import com.rout.air.core.model.SourceType
import com.rout.air.core.model.Transform
import com.rout.air.data.scene.ObsImport
import com.rout.air.data.scene.SceneStore
import com.rout.air.data.scene.updateItem
import com.rout.air.data.scene.updateSource
import com.rout.air.engine.source.SourceBitmap
import com.rout.air.data.audio.AudioMixer
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.rout.air.ui.dialogs.AddSourceDialog
import com.rout.air.ui.dialogs.ApplicationAudioPickerDialog
import com.rout.air.ui.dialogs.UrlSourceDialog
import com.rout.air.ui.pages.screensaver.ScreensaverPage
import com.rout.air.data.scene.SceneManager
import com.rout.air.live.youtube.nameColor
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp
import com.rout.air.data.scene.addScene
import com.rout.air.data.scene.addScreenItem
import com.rout.air.data.scene.addSource
import com.rout.air.data.scene.duplicateScene
import com.rout.air.data.screensaver.isScreensaverScene
import com.rout.air.data.scene.moveLayer
import com.rout.air.data.scene.moveScene
import com.rout.air.data.scene.removeItem
import com.rout.air.data.scene.removeScene
import com.rout.air.data.scene.removeSource
import com.rout.air.data.scene.renameScene
import com.rout.air.data.scene.setActiveScene
import kotlinx.coroutines.launch
import com.rout.air.ui.components.Hint
import com.rout.air.ui.pages.scenes.components.NameDialog
import com.rout.air.ui.pages.scenes.components.SourceEditor
import com.rout.air.ui.pages.scenes.components.SourceRow
import com.rout.air.ui.pages.scenes.components.defaultTransform
import com.rout.air.ui.pages.scenes.components.fitTransform
import com.rout.air.ui.pages.scenes.components.obsErrorText
import com.rout.air.ui.pages.scenes.components.obsNoteText
import com.rout.air.ui.pages.scenes.components.obsSkippedText
import com.rout.air.ui.pages.scenes.components.uniqueSourceName

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
        // Ukuran intrinsik kamera mengikuti OBS: baca karakteristik perangkat
        // (tanpa membuka kamera) supaya sumber langsung punya rasio yang benar.
        val cameraSize = com.rout.air.capture.CameraSizing.resolve(
            context = context,
            facing = 0,
            cameraId = null,
            canvasWidth = canvasConfig.width,
            canvasHeight = canvasConfig.height,
        )
        val intrinsicWidth = cameraSize?.visual?.width ?: 1280
        val intrinsicHeight = cameraSize?.visual?.height ?: 720
        val source = SceneSource(
            type = SourceType.Camera,
            name = uniqueSourceName(collection, nameCamera),
            settings = SourceSettings(width = intrinsicWidth, height = intrinsicHeight),
        )
        val item = SceneItem(
            sourceId = source.id,
            transform = fitTransform(intrinsicWidth, intrinsicHeight, canvasConfig),
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
                        com.rout.air.data.media.ImageFetcher.download(context, url)
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
