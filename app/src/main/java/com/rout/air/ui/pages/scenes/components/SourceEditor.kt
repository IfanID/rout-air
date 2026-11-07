package com.rout.air.ui.pages.scenes.components

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import com.rout.air.R
import com.rout.air.core.model.Align
import com.rout.air.core.model.CameraStreamState
import com.rout.air.core.model.CameraStreamStatus
import com.rout.air.core.model.CaptureConfig
import com.rout.air.core.model.Crop
import com.rout.air.core.model.SceneCollection
import com.rout.air.ui.components.Hint
import com.rout.air.core.model.SceneItem
import com.rout.air.core.model.SceneSource
import com.rout.air.core.model.SourceType
import com.rout.air.core.model.Transform
import com.rout.air.data.scene.updateItem
import com.rout.air.data.scene.updateSource
import com.rout.air.engine.source.SourceBitmap
import com.rout.air.output.recording.CaptureEngine
import kotlin.math.abs
import kotlin.math.min
import androidx.compose.ui.res.stringResource
import com.rout.air.ui.components.ItemContextMenu
import com.rout.air.ui.components.ScenePreview
import com.rout.air.ui.dialogs.AudioDevicePickerDialog
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.rout.air.data.scene.moveLayer
import kotlin.math.roundToInt


@Composable
internal fun SourceEditor(
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
    val configuration = LocalConfiguration.current
    val isAudioSource = source.type == SourceType.AudioInput ||
        source.type == SourceType.AudioOutput

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

    val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val toggleLock: () -> Unit = {
        onCollectionChange(collection.updateItem(item.copy(locked = !item.locked)))
    }

    if (landscape) {
        // HP diputar: pratinjau di kiri dan panel pengaturan yang bergulir di
        // kanan, supaya semua kontrol terjangkau tanpa mengecilkan pratinjau.
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier
                    .weight(1.3f)
                    .fillMaxHeight(),
            ) {
                EditorHeader(
                    source = source,
                    item = item,
                    onBack = onBack,
                    onToggleLock = toggleLock,
                )
                Spacer(Modifier.height(6.dp))
                PreviewPane(
                    canvas = canvas,
                    collection = collection,
                    item = item,
                    onTransform = onTransform,
                    onSelectItem = onSelectItem,
                    onCollectionChange = onCollectionChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            }
            PropertyPanel(
                canvas = canvas,
                collection = collection,
                item = item,
                source = source,
                density = density,
                onCollectionChange = onCollectionChange,
                onDelete = onDelete,
                onPickImage = onPickImage,
                onPickMedia = onPickMedia,
                onEditBrowser = onEditBrowser,
                onAddSlides = onAddSlides,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
        }
    } else {
        // Pratinjau menempel di atas, sedangkan pengaturan di bawahnya yang
        // bergulir. Hasil geser slider jadi selalu terlihat.
        val previewHeight = (configuration.screenHeightDp * 0.46f).dp.coerceIn(300.dp, 440.dp)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            EditorHeader(
                source = source,
                item = item,
                onBack = onBack,
                onToggleLock = toggleLock,
            )
            Spacer(Modifier.height(8.dp))
            PreviewPane(
                canvas = canvas,
                collection = collection,
                item = item,
                onTransform = onTransform,
                onSelectItem = onSelectItem,
                onCollectionChange = onCollectionChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(previewHeight),
            )
            Spacer(Modifier.height(10.dp))
            PropertyPanel(
                canvas = canvas,
                collection = collection,
                item = item,
                source = source,
                density = density,
                onCollectionChange = onCollectionChange,
                onDelete = onDelete,
                onPickImage = onPickImage,
                onPickMedia = onPickMedia,
                onEditBrowser = onEditBrowser,
                onAddSlides = onAddSlides,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )
        }
    }
}

/** Baris judul editor sumber: kembali, nama, dan kunci item. */
@Composable
private fun EditorHeader(
    source: SceneSource,
    item: SceneItem,
    onBack: () -> Unit,
    onToggleLock: () -> Unit,
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
            onClick = onToggleLock,
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
}

/**
 * Pratinjau adegan untuk satu item. Dipakai baik saat portrait (di atas
 * pengaturan) maupun landscape (di panel kiri).
 */
@Composable
private fun PreviewPane(
    canvas: CaptureConfig,
    collection: SceneCollection,
    item: SceneItem,
    onTransform: (String?, Float, Float, Float, Float, Float) -> Unit,
    onSelectItem: (String?) -> Unit,
    onCollectionChange: (SceneCollection) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Menu konteks dari pratinjau (tahan lama / klik dua kali).
    var menuTarget by remember(item.id) { mutableStateOf<Pair<SceneItem, Offset>?>(null) }
    // Saat sesi aktif, tampilkan render pipeline (termasuk kamera) supaya
    // pratinjau sumber sama persis dengan Studio.
    val sessionActive by CaptureEngine.sessionActive.collectAsState()
    Box(modifier = modifier) {
        ScenePreview(
            canvas = canvas,
            collection = collection,
            selectedItemId = item.id,
            onSelectItem = {},
            onTransform = onTransform,
            onItemMenu = { target, position -> menuTarget = target to position },
            gestureTargetItemId = item.id,
            liveBackground = sessionActive,
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
}

/** Panel pengaturan sumber; selalu bisa digulir penuh. */
@Composable
private fun PropertyPanel(
    canvas: CaptureConfig,
    collection: SceneCollection,
    item: SceneItem,
    source: SceneSource,
    density: Float,
    onCollectionChange: (SceneCollection) -> Unit,
    onDelete: () -> Unit,
    onPickImage: () -> Unit,
    onPickMedia: () -> Unit,
    onEditBrowser: () -> Unit,
    onAddSlides: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by remember(source.id) { mutableStateOf(source.name) }
    var text by remember(source.id) { mutableStateOf(source.settings.text) }
    var textSize by remember(source.id) { mutableStateOf(source.settings.textSize) }
    var showDevicePicker by remember(source.id) { mutableStateOf(false) }

    fun updateSource(next: SceneSource) = onCollectionChange(collection.updateSource(next))
    fun updateItem(next: SceneItem) = onCollectionChange(collection.updateItem(next))
    fun updateTransform(block: (Transform) -> Transform) =
        updateItem(item.copy(transform = block(item.transform)))

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
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
                // Kamera lokal sudah dihapus. Sumber ini menerima stream
                // H.264 dari HP lain lewat Wi-Fi lokal.
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.scenes_kamera_stream_url),
                    style = MaterialTheme.typography.bodySmall,
                )
                val existingUrl = source.settings.cameraUrl.orEmpty()
                val existingEndpoint = existingUrl.removePrefix("tcp://")
                val initialHost = existingEndpoint.substringBefore(':')
                    .ifBlank { "192.168.43.57" }
                val initialPort = existingEndpoint.substringAfter(':', "3333")
                    .substringBefore('/')
                    .ifBlank { "3333" }
                var cameraHost by remember(item.id) { mutableStateOf(initialHost) }
                var cameraPort by remember(item.id) { mutableStateOf(initialPort) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = cameraHost,
                        onValueChange = { cameraHost = it },
                        label = { Text(stringResource(R.string.scenes_kamera_host)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = cameraPort,
                        onValueChange = { input ->
                            cameraPort = input.filter { it.isDigit() }.take(5)
                        },
                        label = { Text(stringResource(R.string.scenes_kamera_port)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(110.dp),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            val host = cameraHost.trim()
                            val port = cameraPort.toIntOrNull()?.coerceIn(1, 65535) ?: 3333
                            updateSource(
                                source.copy(
                                    settings = source.settings.copy(
                                        cameraUrl = if (host.isBlank()) {
                                            null
                                        } else {
                                            "tcp://$host:$port"
                                        },
                                    ),
                                ),
                            )
                        },
                    ) { Text(stringResource(R.string.common_simpan)) }
                    TextButton(
                        onClick = {
                            val host = cameraHost.trim()
                            val port = cameraPort.toIntOrNull()?.coerceIn(1, 65535) ?: 3333
                            CameraStreamStatus.report(
                                source.id,
                                CameraStreamState.Connecting,
                            )
                            updateSource(
                                source.copy(
                                    settings = source.settings.copy(
                                        cameraUrl = if (host.isBlank()) {
                                            null
                                        } else {
                                            "tcp://$host:$port"
                                        },
                                        // Token baru memaksa receiver menyambung
                                        // ulang walau URL tidak berubah.
                                        cameraConnectToken =
                                            source.settings.cameraConnectToken + 1,
                                    ),
                                ),
                            )
                        },
                    ) { Text(stringResource(R.string.scenes_kamera_connect)) }
                }
                val streamStates by CameraStreamStatus.states.collectAsState()
                val streamState = streamStates[source.id]
                if (streamState != null) {
                    val statusText = when (streamState) {
                        CameraStreamState.Connecting ->
                            stringResource(R.string.scenes_kamera_connecting)
                        is CameraStreamState.Connected -> stringResource(
                            R.string.scenes_kamera_connected,
                            streamState.host,
                            streamState.port,
                        )
                        is CameraStreamState.Streaming -> stringResource(
                            R.string.scenes_kamera_streaming,
                            streamState.width,
                            streamState.height,
                        )
                        is CameraStreamState.Error -> stringResource(
                            R.string.scenes_kamera_connect_error,
                            streamState.message,
                        )
                        CameraStreamState.Disconnected ->
                            stringResource(R.string.scenes_kamera_disconnected)
                    }
                    val statusColor = when (streamState) {
                        is CameraStreamState.Error -> MaterialTheme.colorScheme.error
                        is CameraStreamState.Connected,
                        is CameraStreamState.Streaming -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = statusColor,
                    )
                }
                Hint(stringResource(R.string.scenes_kamera_stream_hint))
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
                        com.rout.air.core.model.ScreenDisplay.Auto to
                            stringResource(R.string.settings_otomatis),
                        com.rout.air.core.model.ScreenDisplay.Portrait to
                            stringResource(R.string.common_vertikal),
                        com.rout.air.core.model.ScreenDisplay.Landscape to
                            stringResource(R.string.common_horizontal),
                    ).forEach { (display, label) ->
                        FilterChip(
                            selected = (source.settings.screenDisplay
                                ?: com.rout.air.core.model.ScreenDisplay.Auto) == display,
                            onClick = {
                                // Kotak item ikut dibentuk ulang agar bingkai
                                // pilihan di pratinjau juga ikut horizontal
                                // atau vertikal, bukan hanya isinya.
                                val base = canvas.width.toFloat() / canvas.height.toFloat()
                                val aspect = when (display) {
                                    com.rout.air.core.model.ScreenDisplay.Landscape ->
                                        if (base >= 1f) base else 1f / base

                                    com.rout.air.core.model.ScreenDisplay.Portrait ->
                                        if (base <= 1f) base else 1f / base

                                    com.rout.air.core.model.ScreenDisplay.Auto -> base
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
                                com.rout.air.data.scene.SceneManager.commitToProgram()
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
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = { onCollectionChange(collection.moveLayer(item.id, +1)) },
            ) { Text(stringResource(R.string.scenes_naikkan_lapisan)) }
            TextButton(
                onClick = { onCollectionChange(collection.moveLayer(item.id, -1)) },
            ) { Text(stringResource(R.string.scenes_turunkan_lapisan)) }
        }
        Spacer(Modifier.height(8.dp))
        // Menghapus item ini saja; sumber dibuang hanya bila tak terpakai lagi.
        TextButton(onClick = onDelete) { Text(stringResource(R.string.scenes_hapus_item)) }
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
