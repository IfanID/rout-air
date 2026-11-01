package com.rout.air.ui

import android.content.res.Configuration
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.rout.air.core.CaptureConfig
import com.rout.air.core.RecordingState
import com.rout.air.core.RecordingStats
import com.rout.air.core.SceneCollection
import com.rout.air.core.SceneItem
import com.rout.air.output.CaptureEngine
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.rout.air.R

/**
 * Halaman Studio Mode: dua panel terpisah.
 *
 * - Pratinjau (mentah): komposisi yang sedang disunting.
 * - Program (siaran): keluaran yang benar-benar masuk file rekaman.
 *
 * Keduanya digambar dari pipeline yang sama; selama baru ada satu adegan,
 * isinya identik. Pemisahan bermakna saat transisi adegan tersedia.
 */
@Composable
fun StudioScreen(
    state: RecordingState,
    stats: RecordingStats,
    canvasConfig: CaptureConfig,
    fps: Int,
    elapsedMs: Long,
    collection: SceneCollection,
    programCollection: SceneCollection,
    selectedItemId: String?,
    onSelectItem: (String?) -> Unit,
    onTransform: (String?, Float, Float, Float, Float, Float) -> Unit,
    onCollectionChange: (SceneCollection) -> Unit,
    onCommitProgram: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onClearError: () -> Unit,
    fullscreen: Boolean,
    onFullscreenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isActive = state is RecordingState.Recording
    val paused = (state as? RecordingState.Recording)?.isPaused == true
    val sessionLive = state is RecordingState.Recording || state is RecordingState.Previewing
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    // Tombol kembali menutup mode pratinjau penuh, tanpa keluar aplikasi.
    if (fullscreen) {
        BackHandler { onFullscreenChange(false) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.studio_studio_mode), style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = statusText(state),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state is RecordingState.Error) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (state is RecordingState.Error) {
                TextButton(onClick = onClearError) { Text(stringResource(R.string.common_tutup)) }
            }
            // Satu tombol di header: ikon full screen saat normal, ikon ✕
            // saat pratinjau penuh. Posisinya sama supaya konsisten.
            PreviewFullscreenButton(
                fullscreen = fullscreen,
                onClick = { onFullscreenChange(!fullscreen) },
            )
        }

        Spacer(Modifier.height(10.dp))

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            if (fullscreen) {
                // Pratinjau penuh: panel Program disembunyikan supaya area
                // edit jadi luas, tetapi header dan tombol bawah tetap ada.
                Box(modifier = Modifier.fillMaxSize()) {
                    RawPreview(
                        isActive = sessionLive,
                        canvasConfig = canvasConfig,
                        collection = collection,
                        selectedItemId = selectedItemId,
                        onSelectItem = onSelectItem,
                        onTransform = onTransform,
                        onCollectionChange = onCollectionChange,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            } else if (landscape) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                        RawPreview(
                            isActive = sessionLive,
                            canvasConfig = canvasConfig,
                            collection = collection,
                            selectedItemId = selectedItemId,
                            onSelectItem = onSelectItem,
                            onTransform = onTransform,
                            onCollectionChange = onCollectionChange,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    ProgramPane(
                        isLive = sessionLive,
                        canvasConfig = canvasConfig,
                        program = programCollection,
                        modifier = Modifier.weight(1f).fillMaxSize(),
                    )
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        RawPreview(
                            isActive = sessionLive,
                            canvasConfig = canvasConfig,
                            collection = collection,
                            selectedItemId = selectedItemId,
                            onSelectItem = onSelectItem,
                            onTransform = onTransform,
                            onCollectionChange = onCollectionChange,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    ProgramPane(
                        isLive = sessionLive,
                        canvasConfig = canvasConfig,
                        program = programCollection,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(formatDuration(elapsedMs), style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "${canvasConfig.width}x${canvasConfig.height} • $fps fps • " +
                        "${orientationLabel(canvasConfig)} • ${contentModeLabel(canvasConfig)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (isActive) {
                    Text(
                        text = stringResource(
                            R.string.home_statistik,
                            formatRecordSize(stats.bytes),
                            String.format("%.1f", stats.bitrateKbps / 1000.0),
                            stats.frames,
                            stats.droppedFrames,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Tombol Transisi ala OBS: menerapkan hasil edit pratinjau ke
                // Program (dan ke rekaman). Sebelum ditekan, Program tetap.
                val pending = collection != programCollection
                TransitionButton(
                    pending = pending,
                    onClick = onCommitProgram,
                )
                Spacer(Modifier.width(16.dp))
                if (isActive) {
                    IconButton(
                        onClick = if (paused) onResume else onPause,
                        modifier = Modifier.size(44.dp),
                    ) {
                        Icon(
                            painter = painterResource(
                                if (paused) R.drawable.ic_play else R.drawable.ic_pause,
                            ),
                            contentDescription = stringResource(
                                if (paused) R.string.home_lanjutkan_rekaman
                                else R.string.home_jeda_rekaman,
                            ),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                }
                RecordButton(
                    isActive = isActive,
                    enabled = state !is RecordingState.Preparing,
                    preparing = state is RecordingState.Preparing,
                    paused = paused,
                    onClick = { if (isActive) onStop() else onStart() },
                )
            }
        }
    }
}

@Composable
private fun PreviewFullscreenButton(
    fullscreen: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .size(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xE6000000))
            .border(1.dp, Color(0x55FFFFFF), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .semantics {
                contentDescription =
                    if (fullscreen) "Keluar pratinjau penuh" else "Pratinjau penuh"
            },
        contentAlignment = Alignment.Center,
    ) {
        if (fullscreen) {
            // Ikon tutup sederhana, tanpa teks.
            Text(
                text = stringResource(R.string.studio_teks),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
            )
        } else {
            FullscreenIcon(color = Color.White, size = 18.dp)
        }
    }
}

/**
 * Panel Program. Saat sesi hidup memakai gambar pipeline (snapshot program);
 * saat tidak ada sesi memakai pratinjau Compose dari snapshot program juga,
 * sehingga hasil edit pratinjau tidak terlihat di sini sebelum Transisi.
 */
@Composable
private fun ProgramPane(
    isLive: Boolean,
    canvasConfig: CaptureConfig,
    program: SceneCollection,
    modifier: Modifier = Modifier,
) {
    if (isLive) {
        PreviewPane(
            label = stringResource(R.string.studio_program_siaran),
            canvasAspect = canvasConfig.aspectRatio,
            live = true,
            modifier = modifier,
            attach = { surface, width, height ->
                CaptureEngine.attachProgram(surface, width, height)
            },
        )
    } else {
        Column(modifier = modifier) {
            PaneCaption(label = stringResource(R.string.studio_program_siaran), live = false)
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                ScenePreview(
                    canvas = canvasConfig,
                    collection = program,
                    selectedItemId = null,
                    onSelectItem = {},
                    onTransform = { _, _, _, _, _, _ -> },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** Caption panel di luar kotak gambar, sama untuk Pratinjau dan Program. */
@Composable
private fun PaneCaption(label: String, live: Boolean) {
    Row(
        modifier = Modifier.padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (live) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFE53935)),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = Color(0xFFB8C2CC),
            textAlign = TextAlign.Start,
        )
    }
}

/**
 * Panel "Pratinjau (mentah)" seperti OBS: saat merekam memakai gambar langsung
 * dari pipeline, saat tidak merekam memakai pratinjau susunan yang tetap bisa
 * diketuk dan digeser.
 */
@Composable
private fun RawPreview(
    isActive: Boolean,
    canvasConfig: CaptureConfig,
    collection: SceneCollection,
    selectedItemId: String?,
    onSelectItem: (String?) -> Unit,
    onTransform: (String?, Float, Float, Float, Float, Float) -> Unit,
    onCollectionChange: (SceneCollection) -> Unit,
    showCaption: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val selected = collection.item(selectedItemId)
    // Menu konteks dari pratinjau (tahan lama / klik dua kali).
    var menuTarget by remember { mutableStateOf<Pair<SceneItem, Offset>?>(null) }
    val openMenu: (SceneItem, Offset) -> Unit = { item, position ->
        menuTarget = item to position
    }

    if (isActive) {
        PreviewPane(
            label = stringResource(R.string.studio_pratinjau_mentah),
            canvasAspect = canvasConfig.aspectRatio,
            modifier = modifier,
            canvas = canvasConfig,
            collection = collection,
            selectedItemId = selectedItemId,
            onSelectItem = onSelectItem,
            onTransform = onTransform,
            onItemMenu = openMenu,
            menuTarget = menuTarget,
            onMenuDismiss = { menuTarget = null },
            onCollectionChange = onCollectionChange,
            selection = itemCanvasRect(collection, canvasConfig, selectedItemId),
            showCaption = showCaption,
            attach = { surface, width, height ->
                CaptureEngine.attachPreview(surface, width, height)
            },
        )
    } else {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            ScenePreview(
                canvas = canvasConfig,
                collection = collection,
                selectedItemId = selectedItemId,
                onSelectItem = onSelectItem,
                onTransform = onTransform,
                onItemMenu = openMenu,
                modifier = Modifier.fillMaxSize(),
            )
            ItemMenuAnchor(
                target = menuTarget,
                collection = collection,
                canvas = canvasConfig,
                onDismiss = { menuTarget = null },
                onCollectionChange = onCollectionChange,
                onSelectItem = onSelectItem,
            )
            Text(
                text = if (selected == null) {
                    "Ketuk sumber untuk memilih, lalu geser/putar"
                } else {
                    stringResource(
                        R.string.studio_selected_n,
                        collection.source(selected.sourceId)?.name
                            ?: stringResource(R.string.scenes_sumber),
                    )
                },
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

/**
 * Satu panel pratinjau dengan SurfaceView sendiri.
 *
 * Ukuran kotak panel mengikuti rasio kanvas (mis. 2400x1080 = 2,22:1), bukan
 * memenuhi ruang yang tersedia, supaya bentuk panel sama dengan bentuk video.
 */
@Composable
private fun PreviewPane(
    label: String,
    canvasAspect: Float,
    modifier: Modifier = Modifier,
    live: Boolean = false,
    selection: ItemRect? = null,
    canvas: CaptureConfig? = null,
    collection: SceneCollection? = null,
    selectedItemId: String? = null,
    onSelectItem: ((String?) -> Unit)? = null,
    onTransform: ((String?, Float, Float, Float, Float, Float) -> Unit)? = null,
    onItemMenu: ((SceneItem, Offset) -> Unit)? = null,
    menuTarget: Pair<SceneItem, Offset>? = null,
    onMenuDismiss: () -> Unit = {},
    onCollectionChange: ((SceneCollection) -> Unit)? = null,
    showCaption: Boolean = true,
    attach: (Surface?, Int, Int) -> Unit,
) {
    val holderCallback = remember(attach) {
        object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = attachHolder(holder, attach)

            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int,
            ) = attachHolder(holder, attach)

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                // Lepas hanya surface ini; surface panel lain (mis. yang baru
                // terpasang setelah pindah tab) tidak ikut terhapus.
                attach(holder.surface, 0, 0)
            }
        }
    }

    Column(modifier = modifier) {
        // Caption di luar kotak supaya tidak menutupi gambar, seperti label
        // panel OBS.
        if (showCaption) {
            Row(
                modifier = Modifier.padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (live) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFE53935)),
                    )
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFFB8C2CC),
                    textAlign = TextAlign.Start,
                )
            }
        }

        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            var frameWidth = maxWidth
            var frameHeight = frameWidth / canvasAspect
            if (frameHeight > maxHeight) {
                frameHeight = maxHeight
                frameWidth = frameHeight * canvasAspect
            }
            val density = LocalDensity.current
            val frameWidthPx = with(density) { frameWidth.toPx() }
            val frameHeightPx = with(density) { frameHeight.toPx() }

            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(width = frameWidth, height = frameHeight)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.Black),
                ) {
                    AndroidView(
                        factory = { ctx ->
                            SurfaceView(ctx).apply { holder.addCallback(holderCallback) }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )

                    // Outline sumber terpilih; digambar di atas SurfaceView memakai
                    // skala kanvas -> ukuran panel.
                    if (selection != null && canvas != null && collection != null) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val scale = size.width / canvas.width.toFloat()
                            val left = selection.left * scale
                            val top = selection.top * scale
                            val width = selection.width * scale
                            val height = selection.height * scale
                            withTransform({
                                translate(left, top)
                                rotate(selection.rotation, Offset(width / 2f, height / 2f))
                            }) {
                                drawRect(
                                    color = Color(0xFF00E5FF),
                                    size = Size(width, height),
                                    style = Stroke(width = 2.dp.toPx()),
                                )
                            }

                            // Garis bantu rotasi: horizontal & vertikal lewat
                            // tengah item, hanya saat item miring. Hilang saat
                            // rotasi sudah lurus (0/90/180°).
                            if (!isAxisAligned(selection.rotation)) {
                                val guideWidth = 1.dp.toPx()
                                val cx = (selection.left + selection.width / 2f) * scale
                                val cy = (selection.top + selection.height / 2f) * scale
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
                }

                // Lapisan sentuh menutupi seluruh panel, bukan hanya kotak
                // gambar, supaya cubitan tetap terbaca walau satu jari
                // mendarat di luar gambar.
                if (canvas != null && collection != null &&
                    onSelectItem != null && onTransform != null
                ) {
                    PreviewGestureLayer(
                        collection = collection,
                        canvas = canvas,
                        selectedItemId = selectedItemId,
                        onSelectItem = onSelectItem,
                        onTransform = onTransform,
                        onItemMenu = onItemMenu,
                        frameWidthPx = frameWidthPx,
                        frameHeightPx = frameHeightPx,
                    )
                }

                // Menu konteks memakai koordinat panel yang sama dengan lapisan
                // sentuh, sehingga tetap muncul di tempat jari menekan.
                if (canvas != null && collection != null && onCollectionChange != null) {
                    ItemMenuAnchor(
                        target = menuTarget,
                        collection = collection,
                        canvas = canvas,
                        onDismiss = onMenuDismiss,
                        onCollectionChange = onCollectionChange,
                        onSelectItem = { onSelectItem?.invoke(it) },
                    )
                }
            }
        }
    }
}

/**
 * Lapisan sentuh di atas panel pratinjau. Area tangkapnya seluruh panel,
 * bukan hanya kotak gambar, sehingga cubitan dengan dua jari tetap terbaca
 * walau salah satu jari berada di luar gambar.
 */
@Composable
private fun PreviewGestureLayer(
    collection: SceneCollection,
    canvas: CaptureConfig,
    selectedItemId: String?,
    onSelectItem: (String?) -> Unit,
    onTransform: (String?, Float, Float, Float, Float, Float) -> Unit,
    onItemMenu: ((SceneItem, Offset) -> Unit)?,
    frameWidthPx: Float,
    frameHeightPx: Float,
) {
    val latestCollection by rememberUpdatedState(collection)
    val latestSelect by rememberUpdatedState(onSelectItem)
    val latestTransform by rememberUpdatedState(onTransform)
    val latestMenu by rememberUpdatedState(onItemMenu)
    val latestSelected by rememberUpdatedState(selectedItemId)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(canvas, frameWidthPx, frameHeightPx) {
                // Kotak gambar berada di tengah panel.
                val offsetLeft = (size.width - frameWidthPx) / 2f
                val offsetTop = (size.height - frameHeightPx) / 2f
                val scale = if (canvas.width > 0) frameWidthPx / canvas.width else 0f

                fun insideFrame(position: Offset): Boolean =
                    scale > 0f &&
                        position.x >= offsetLeft && position.x <= offsetLeft + frameWidthPx &&
                        position.y >= offsetTop && position.y <= offsetTop + frameHeightPx

                fun hitsAt(position: Offset): List<SceneItem> {
                    if (!insideFrame(position)) return emptyList()
                    return hitTestItems(
                        latestCollection,
                        canvas,
                        (position.x - offsetLeft) / scale,
                        (position.y - offsetTop) / scale,
                    )
                }

                detectTapGestures(
                    onTap = { position ->
                        // Ketuk di luar gambar diabaikan, ketuk berulang
                        // memilih item di bawahnya.
                        if (insideFrame(position)) {
                            latestSelect(cycleSelection(hitsAt(position), latestSelected))
                        }
                    },
                    onLongPress = { position ->
                        menuTargetAt(hitsAt(position), latestSelected)
                            ?.let { latestMenu?.invoke(it, position) }
                    },
                    onDoubleTap = { position ->
                        menuTargetAt(hitsAt(position), latestSelected)
                            ?.let { latestMenu?.invoke(it, position) }
                    },
                )
            }
            .pointerInput(canvas, frameWidthPx, frameHeightPx) {
                val offsetLeft = (size.width - frameWidthPx) / 2f
                val offsetTop = (size.height - frameHeightPx) / 2f
                val scale = if (canvas.width > 0) frameWidthPx / canvas.width else 0f

                var activeId: String? = null
                var pendingSelect = false
                detectItemTransformGestures(
                    onStart = { offset ->
                        val insideFrame = scale > 0f &&
                            offset.x >= offsetLeft && offset.x <= offsetLeft + frameWidthPx &&
                            offset.y >= offsetTop && offset.y <= offsetTop + frameHeightPx
                        val canvasX = (offset.x - offsetLeft) / scale
                        val canvasY = (offset.y - offsetTop) / scale
                        val hits = if (insideFrame) {
                            hitTestItems(latestCollection, canvas, canvasX, canvasY)
                        } else {
                            emptyList()
                        }
                        // Item teratas yang bisa digeser menang. Bila yang
                        // tersentuh item terkunci (mis. sumber layar) atau
                        // area kosong, pakai item terpilih supaya jari boleh
                        // menyeret dari luar batas item.
                        val direct = hits.firstOrNull { !it.locked }
                        val fallback = latestCollection.item(latestSelected)
                            ?.takeIf { !it.locked && it.visible }
                        val candidate = direct ?: fallback
                        activeId = candidate?.id
                        pendingSelect = direct != null
                    },
                    onEnd = {
                        activeId = null
                        pendingSelect = false
                    },
                ) { pan, zoom, rotation ->
                    val id = activeId ?: return@detectItemTransformGestures

                    if (pendingSelect) {
                        pendingSelect = false
                        latestSelect(id)
                    }
                    // Lebar acuan memakai lebar gambar, supaya jarak geser
                    // tetap sebanding dengan ukuran kanvas.
                    latestTransform(id, pan.x, pan.y, zoom, rotation, frameWidthPx)
                }
            },
    )
}

private fun attachHolder(holder: SurfaceHolder, attach: (Surface?, Int, Int) -> Unit) {
    val frame = holder.surfaceFrame
    if (frame.width() <= 0 || frame.height() <= 0) {
        attach(null, 0, 0)
        return
    }
    attach(holder.surface, frame.width(), frame.height())
}

@Composable
private fun statusText(state: RecordingState): String = when (state) {
    RecordingState.Idle -> stringResource(R.string.studio_pratinjau_dan_program_siap)
    RecordingState.Previewing -> stringResource(R.string.studio_pratinjau_aktif_tekan_rekam)
    RecordingState.Preparing -> stringResource(R.string.studio_menyiapkan_tangkap_layar)
    is RecordingState.Recording -> when {
        state.isPaused -> stringResource(R.string.home_rekaman_dijeda)
        state.audioActive -> stringResource(R.string.studio_merekam_program_file_mixer)
        else -> stringResource(R.string.studio_merekam_program_file_tanpa_audio)
    }

    is RecordingState.Error -> state.message
}
