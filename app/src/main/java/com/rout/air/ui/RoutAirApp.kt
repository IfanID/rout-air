package com.rout.air.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.provider.Settings
import android.os.Build
import android.os.PowerManager
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.rout.air.core.LiveService
import com.rout.air.live.GoogleAuthStore
import com.rout.air.live.prepareYouTubeBroadcast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import com.rout.air.core.CanvasOrientation
import com.rout.air.core.CaptureConfig
import com.rout.air.core.ContentMode
import com.rout.air.core.RecordingState
import com.rout.air.core.SceneCollection
import com.rout.air.data.AudioMixer
import com.rout.air.data.SceneManager
import com.rout.air.data.SettingsStore
import com.rout.air.data.updateItem
import com.rout.air.output.CaptureEngine
import com.rout.air.service.CaptureService
import com.rout.air.service.OverlayLiveService
import com.rout.air.service.OverlayRecordingService
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import androidx.compose.ui.res.stringResource
import com.rout.air.R

/**
 * Enam halaman meniru dock OBS: Siaran, Studio Mode, Adegan, Audio,
 * Rekaman, dan Setelan. Studio Mode sengaja jadi halaman sendiri, bukan
 * disatukan ke Siaran.
 */
enum class AppTab(val title: String, val symbol: String) {
    Home("Siaran", "●"),
    Studio("Studio", "◫"),
    Scenes("Adegan", "▤"),
    Audio("Audio", "♪"),
    Recordings("Rekaman", "▶"),
    Settings("Setelan", "⚙"),
}

/** Overlay mana yang diminta pengguna; keduanya tidak boleh jalan bersamaan. */
private enum class OverlayTarget { Recording, Live }

@Composable
fun RoutAirApp(
    initialStudio: Boolean = false,
    initialLiveSetup: Boolean = false,
    initialSettings: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val store = remember(context) { SettingsStore(context) }

    remember(context) { SceneManager.attach(context) }
    remember(context) { AudioMixer.attach(context) }
    val scenes by SceneManager.state.collectAsState()

    val audioMix by AudioMixer.mix.collectAsState()

    var config by remember { mutableStateOf(store.load()) }
    var liveConfig by remember { mutableStateOf(store.loadLive()) }
    var tab by rememberSaveable {
        mutableStateOf(
            when {
                initialStudio -> AppTab.Studio
                initialSettings -> AppTab.Settings
                else -> AppTab.Home
            },
        )
    }

    // Overlay dapat meminta membuka tab Studio atau Setelan.
    LaunchedEffect(Unit) {
        com.rout.air.data.UiEvents.openStudio.collect { tab = AppTab.Studio }
    }
    LaunchedEffect(Unit) {
        com.rout.air.data.UiEvents.openSettings.collect { tab = AppTab.Settings }
    }
    var elapsedMs by remember { mutableStateOf(0L) }
    var selectedItemId by rememberSaveable { mutableStateOf<String?>(null) }
    // Status pratinjau penuh disimpan di sini supaya tetap diingat saat
    // berpindah tab lalu kembali ke Studio.
    var studioFullscreen by rememberSaveable { mutableStateOf(false) }
    // Status kedua overlay: rekaman dan live memakai service terpisah.
    val overlayRecordingRunning by OverlayRecordingService.running.collectAsState()
    val overlayLiveRunning by OverlayLiveService.running.collectAsState()

    val state by CaptureEngine.state.collectAsState()
    val stats by CaptureEngine.stats.collectAsState()
    val liveStats by CaptureEngine.liveStats.collectAsState()

    // Kanvas = orientasi video. Selama rekaman berjalan nilainya dibekukan
    // supaya ukuran video tidak berubah walau layar diputar.
    val configuration = LocalConfiguration.current
    val metrics = context.resources.displayMetrics
    val liveCanvas = remember(
        config,
        configuration.orientation,
        metrics.widthPixels,
        metrics.heightPixels,
    ) {
        buildCanvasConfig(
            config = config,
            metrics = metrics,
            devicePortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT,
        )
    }

    var lockedCanvas by remember { mutableStateOf<CaptureConfig?>(null) }
    val canvasConfig = lockedCanvas ?: liveCanvas
    val collection = scenes ?: SceneCollection.default(canvasConfig.width, canvasConfig.height)

    // Scene layar jeda dibuat sebelum orientasi diketahui; posisinya
    // disesuaikan dengan kanvas yang benar-benar dipakai.
    var lastCanvasSize by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    LaunchedEffect(canvasConfig.width, canvasConfig.height) {
        SceneManager.ensureScreensaverLayout(canvasConfig.width, canvasConfig.height)
        val previous = lastCanvasSize
        val currentSize = canvasConfig.width to canvasConfig.height
        if (previous != null && previous != currentSize) {
            // Kanvas berganti orientasi: posisi item ikut diskalakan supaya
            // tidak keluar area dan hasilnya tidak hitam.
            SceneManager.rescaleCanvas(
                previous.first,
                previous.second,
                currentSize.first,
                currentSize.second,
            )
            // Sesi pratinjau yang sedang aktif ikut memakai kanvas baru.
            CaptureEngine.updateCanvas(currentSize.first, currentSize.second)
        }
        lastCanvasSize = currentSize
        store.saveSceneCanvas(currentSize.first, currentSize.second)
    }

    // Program = snapshot yang sedang mengudara. Perubahan pratinjau baru masuk
    // ke sini setelah tombol Transisi ditekan, seperti OBS Studio Mode.
    val programScenes by SceneManager.program.collectAsState()
    val programCollection = programScenes ?: collection

    // Sesi pratinjau hidup selama aplikasi terlihat. Tombol rekam tidak perlu
    // izin baru karena memakai sesi yang sama.
    var appForeground by remember { mutableStateOf(true) }
    var pendingAutoRecord by remember { mutableStateOf(false) }
    var pendingLive by remember { mutableStateOf(false) }
    var setupOpen by remember { mutableStateOf(false) }
    var setupBusy by remember { mutableStateOf(false) }
    var setupError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // Otomatis membuka dialog izin hanya sekali per sesi foreground, supaya
    // pembatalan tidak memunculkan dialog terus-menerus.
    var autoPromptDone by remember { mutableStateOf(false) }

    // Simpan scene otomatis setelah 500 ms tanpa perubahan (debounce).
    LaunchedEffect(scenes) {
        if (scenes != null) {
            delay(500)
            SceneManager.save()
        }
    }

    // Sumber audio di panel Sumber ikut menjadi kanal mixer, seperti OBS.
    LaunchedEffect(scenes) {
        scenes?.let { AudioMixer.syncFromScene(it) }
    }

    // Kanvas mengikuti sesi (dikunci saat sesi dibuka) supaya pratinjau dan
    // hasil rekaman selalu memakai ukuran yang sama.
    LaunchedEffect(state) {
        if (state is RecordingState.Idle) lockedCanvas = null
    }

    // Rotasi layar saat tidak merekam: kanvas mengikuti orientasi baru agar
    // pratinjau/overlay tidak menampilkan isi kecil karena rasio lama.
    LaunchedEffect(configuration.orientation) {
        if (state !is RecordingState.Recording) lockedCanvas = null
    }

    LaunchedEffect(config) {
        store.save(config)
        // Perubahan kanvas (mis. orientasi video) langsung dipakai selama
        // tidak sedang merekam. Rekaman yang berjalan tetap memakai ukuran
        // yang sudah dikunci saat sesi dimulai.
        if (state !is RecordingState.Recording) lockedCanvas = null
    }

    LaunchedEffect(liveConfig) {
        store.saveLive(liveConfig)
    }

    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data: Intent? = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            lockedCanvas = canvasConfig
            ContextCompat.startForegroundService(
                context,
                CaptureService.startIntent(
                    context = context,
                    resultCode = result.resultCode,
                    projectionData = data,
                    config = canvasConfig,
                    autoRecord = pendingAutoRecord,
                ),
            )
            pendingAutoRecord = false
        } else {
            pendingAutoRecord = false
            pendingLive = false
            // Izin dibatalkan: tidak ada tangkapan yang berjalan. Beri pesan
            // dan biarkan pengguna mencoba lagi lewat tombol rekam.
            CaptureEngine.reportPermissionCancelled()
        }
    }

    // Overlay hanya satu yang boleh jalan: memulai yang baru menutup yang lain
    // supaya tidak ada dua panel dan dua pemilik surface pratinjau.
    val startOverlay: (OverlayTarget) -> Unit = { target ->
        when (target) {
            OverlayTarget.Recording -> {
                OverlayLiveService.stop(context)
                OverlayRecordingService.start(context)
            }

            OverlayTarget.Live -> {
                OverlayRecordingService.stop(context)
                OverlayLiveService.start(context)
            }
        }
    }

    var pendingOverlay by remember { mutableStateOf<OverlayTarget?>(null) }

    // Minta bebas battery optimization agar OEM tidak membunuh sesi saat layar mati.
    val batteryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        pendingOverlay?.let(startOverlay)
        pendingOverlay = null
    }

    val startOverlayWithBattery: (OverlayTarget) -> Unit = { target ->
        val power = context.getSystemService(PowerManager::class.java)
        if (power != null && !power.isIgnoringBatteryOptimizations(context.packageName)) {
            pendingOverlay = target
            batteryLauncher.launch(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${context.packageName}"),
                ),
            )
        } else {
            startOverlay(target)
        }
    }

    // Izin "tampil di atas aplikasi lain" untuk tombol melayang sistem.
    val overlayLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (Settings.canDrawOverlays(context)) {
            pendingOverlay?.let(startOverlayWithBattery)
        }
        pendingOverlay = null
    }

    val toggleOverlay: (OverlayTarget) -> Unit = { target ->
        val running = when (target) {
            OverlayTarget.Recording -> OverlayRecordingService.running.value
            OverlayTarget.Live -> OverlayLiveService.running.value
        }
        if (running) {
            when (target) {
                OverlayTarget.Recording -> OverlayRecordingService.stop(context)
                OverlayTarget.Live -> OverlayLiveService.stop(context)
            }
        } else {
            pendingOverlay = target
            if (!Settings.canDrawOverlays(context)) {
                overlayLauncher.launch(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    ),
                )
            } else {
                startOverlayWithBattery(target)
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        val manager = context.getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(createCaptureIntent(manager))
    }

    fun launchProjection() {
        val manager = context.getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(createCaptureIntent(manager))
    }

    fun beginSession(autoRecord: Boolean) {
        autoPromptDone = true
        pendingAutoRecord = autoRecord
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            launchProjection()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    // Buka sesi pratinjau otomatis saat aplikasi dibuka/kembali terlihat.
    LaunchedEffect(appForeground, state) {
        if (appForeground && !autoPromptDone && state is RecordingState.Idle &&
            !CaptureEngine.sessionActive.value
        ) {
            autoPromptDone = true
            beginSession(autoRecord = false)
        }
    }

    // Tombol rekam: kalau sesi pratinjau belum ada (mis. izin tadi dibatalkan),
    // buka sesi dulu — jangan langsung merekam. Rekaman dimulai oleh tekanan
    // berikutnya setelah sesi siap.
    val startRecording: () -> Unit = {
        if (CaptureEngine.sessionActive.value) {
            CaptureEngine.startRecording()
        } else {
            beginSession(autoRecord = false)
        }
    }
    val stopRecording: () -> Unit = { CaptureEngine.stopRecording() }
    val pauseRecording: () -> Unit = { CaptureEngine.pauseRecording() }
    val resumeRecording: () -> Unit = { CaptureEngine.resumeRecording() }
    val endSession: () -> Unit = { CaptureEngine.closeSession() }
    // Mulai siaran tanpa dialog; juga dipakai setelah dialog disetujui.
    val startLive: () -> Unit = {
        when {
            liveStats.status == com.rout.air.core.LiveStatus.Error -> {
                if (CaptureEngine.sessionActive.value) {
                    CaptureEngine.restartLive(liveConfig)
                } else {
                    pendingLive = true
                    beginSession(autoRecord = false)
                }
            }

            CaptureEngine.sessionActive.value -> CaptureEngine.startLive(liveConfig)
            else -> {
                pendingLive = true
                beginSession(autoRecord = false)
            }
        }
    }

    /**
     * YouTube dengan akun Google aktif: minta judul/deskripsi dulu seperti
     * OBS yang membuat siaran sebelum mulai streaming.
     */
    val openSetupOrStart: () -> Unit = {
        if (liveConfig.service == LiveService.YouTube &&
            GoogleAuthStore(context).loggedIn
        ) {
            setupError = null
            setupOpen = true
        } else {
            startLive()
        }
    }

    val toggleLive: () -> Unit = {
        when {
            liveStats.status == com.rout.air.core.LiveStatus.Error -> startLive()
            liveStats.active -> CaptureEngine.stopLive()
            !liveConfig.isConfigured -> Unit
            else -> openSetupOrStart()
        }
    }

    // Membuka dialog siap-siaran bila memungkinkan; kalau tidak (bukan
    // YouTube atau belum login), langsung mulai siaran.
    LaunchedEffect(Unit) {
        com.rout.air.data.UiEvents.openLiveSetup.collect { openSetupOrStart() }
    }
    // Overlay membuka aplikasi dengan permintaan yang sama.
    LaunchedEffect(initialLiveSetup) {
        if (initialLiveSetup) openSetupOrStart()
    }

    // Live diminta saat sesi belum aktif: tunggu sesi siap lalu mulai.
    LaunchedEffect(state, liveConfig) {
        if (state is RecordingState.Previewing && pendingLive) {
            pendingLive = false
            CaptureEngine.startLive(liveConfig)
        }
        if (state is RecordingState.Idle) pendingLive = false
    }

    LaunchedEffect(state) {
        val recording = state as? RecordingState.Recording
        if (recording == null) {
            elapsedMs = 0L
            return@LaunchedEffect
        }
        while (true) {
            elapsedMs = if (recording.isPaused) {
                recording.pausedAtMs ?: 0L
            } else {
                (System.currentTimeMillis() - recording.startedAtMs - recording.pausedTotalMs)
                    .coerceAtLeast(0L)
            }
            delay(250)
        }
    }

    DisposableEffect(state, activity) {
        val window = activity?.window
        val recording = state is RecordingState.Recording
        if (recording) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Saat aplikasi ke background, preview dihentikan. Thread preview memang
    // terpisah, tapi tetap lebih hemat kalau tidak menggambar sama sekali.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    appForeground = true
                    CaptureEngine.setPreviewVisible(true)
                }

                Lifecycle.Event.ON_STOP -> {
                    appForeground = false
                    CaptureEngine.setPreviewVisible(false)
                    CaptureEngine.attachPreview(null, 0, 0)
                    CaptureEngine.attachProgram(null, 0, 0)
                    // Sesi sengaja TIDAK ditutup saat ke background. Izin
                    // MediaProjection hanya berlaku sekali per persetujuan;
                    // menutup sesi berarti dialog izin muncul lagi setiap
                    // kembali ke aplikasi. Transparansinya lewat notifikasi
                    // foreground yang selalu terlihat, dan pengguna bisa
                    // mengakhiri sesi lewat tombol di aplikasi/notifikasi.
                }

                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Gestur di pratinjau (Studio maupun halaman Adegan) memakai logika yang
    // sama: item di bawah jari otomatis dipilih, lalu digeser/dicubit/diputar.
    val applyTransform: (String?, Float, Float, Float, Float, Float) -> Unit = transform@{
            itemId, panX, panY, zoom, rotation, paneWidthPx ->
        if (paneWidthPx <= 0f) return@transform
        val current = SceneManager.current()
        val item = current.item(itemId) ?: return@transform
        if (item.locked) return@transform

        val pxToCanvas = canvasConfig.width / paneWidthPx
        val t = item.transform
        val next = t.copy(
            x = t.x + panX * pxToCanvas,
            y = t.y + panY * pxToCanvas,
            scaleX = (t.scaleX * zoom).coerceIn(0.05f, 8f),
            scaleY = (t.scaleY * zoom).coerceIn(0.05f, 8f),
            rotation = ((t.rotation + rotation + 540f) % 360f) - 180f,
        )
        SceneManager.update(current.updateItem(item.copy(transform = next)))
    }

    Scaffold(
        modifier = modifier,
        // Navbar selalu tampil, termasuk saat pratinjau penuh di Studio.
        bottomBar = {
            NavigationBar {
                AppTab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = { Text(item.symbol) },
                        label = { Text(item.title) },
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when (tab) {
                AppTab.Home -> HomeScreen(
                    state = state,
                    stats = stats,
                    liveStats = liveStats,
                    liveConfigured = liveConfig.isConfigured,
                    canvasConfig = canvasConfig,
                    fps = config.fps,
                    elapsedMs = elapsedMs,
                    onStart = startRecording,
                    onStop = stopRecording,
                    onPause = pauseRecording,
                    onResume = resumeRecording,
                    onToggleLive = toggleLive,
                    onEndSession = endSession,
                    onRequestPermission = { beginSession(autoRecord = false) },
                    onClearError = { CaptureEngine.clearError() },
                    recordingOverlayEnabled = overlayRecordingRunning,
                    liveOverlayEnabled = overlayLiveRunning,
                    onToggleRecordingOverlay = { toggleOverlay(OverlayTarget.Recording) },
                    onToggleLiveOverlay = { toggleOverlay(OverlayTarget.Live) },
                )

                AppTab.Studio -> StudioScreen(
                    state = state,
                    stats = stats,
                    liveStats = liveStats,
                    liveConfigured = liveConfig.isConfigured,
                    canvasConfig = canvasConfig,
                    fps = config.fps,
                    elapsedMs = elapsedMs,
                    collection = collection,
                    programCollection = programCollection,
                    selectedItemId = selectedItemId,
                    onSelectItem = { selectedItemId = it },
                    onTransform = applyTransform,
                    onCollectionChange = { SceneManager.update(it) },
                    onCommitProgram = { SceneManager.commitToProgram() },
                    onStart = startRecording,
                    onStop = stopRecording,
                    onPause = pauseRecording,
                    onResume = resumeRecording,
                    onToggleLive = toggleLive,
                    onClearError = { CaptureEngine.clearError() },
                    fullscreen = studioFullscreen,
                    onFullscreenChange = { studioFullscreen = it },
                )

                AppTab.Scenes -> ScenesScreen(
                    canvasConfig = canvasConfig,
                    collection = collection,
                    selectedItemId = selectedItemId,
                    onSelectItem = { selectedItemId = it },
                    onCollectionChange = { SceneManager.update(it) },
                    onTransform = applyTransform,
                )

                AppTab.Audio -> AudioScreen(
                    state = state,
                    mix = audioMix,
                    onChannelChange = { AudioMixer.update(it) },
                    onMasterEnabledChange = { AudioMixer.setMasterEnabled(it) },
                )

                AppTab.Recordings -> RecordingsScreen()

                AppTab.Settings -> SettingsScreen(
                    config = config,
                    canvasConfig = canvasConfig,
                    mix = audioMix,
                    liveConfig = liveConfig,
                    onConfigChange = { config = it },
                    onMixChange = { AudioMixer.update(it) },
                    onMasterEnabledChange = { AudioMixer.setMasterEnabled(it) },
                    onLiveConfigChange = { liveConfig = it },
                )
            }
        }

        if (setupOpen) {
            LiveSetupDialog(
                busy = setupBusy,
                error = setupError,
                onDismiss = { if (!setupBusy) setupOpen = false },
                onStart = { title, description, privacy ->
                    setupBusy = true
                    setupError = null
                    scope.launch {
                        val result = prepareYouTubeBroadcast(
                            context,
                            title,
                            description,
                            privacy,
                        )
                        setupBusy = false
                        result.onSuccess { target ->
                            setupOpen = false
                            // Pastikan server/kunci terisi sebelum siaran dimulai.
                            liveConfig = liveConfig.copy(
                                serverUrl = target.server,
                                streamKey = target.key,
                                accountLinked = true,
                            )
                            startLive()
                        }.onFailure { error ->
                            setupError = error.message ?: "Gagal membuat siaran."
                        }
                    }
                },
            )
        }
    }
}

internal fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/**
 * Ukuran kanvas = base resolution ala OBS, sekaligus orientasi video.
 * Auto mengikuti orientasi HP saat rekaman dimulai; Portrait/Landscape
 * mengunci dimensi video tanpa memaksa layar berputar.
 */
internal fun buildCanvasConfig(
    config: CaptureConfig,
    metrics: DisplayMetrics,
    devicePortrait: Boolean,
): CaptureConfig {
    val portrait = when (config.canvasOrientation) {
        CanvasOrientation.Auto -> devicePortrait
        CanvasOrientation.Portrait -> true
        CanvasOrientation.Landscape -> false
    }

    val displayShort = minOf(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(1)
    val displayLong = maxOf(metrics.widthPixels, metrics.heightPixels)
    val shortSide = minOf(config.width, config.height)

    var longSide = (shortSide.toDouble() * displayLong / displayShort).roundToInt()
    if (longSide % 2 != 0) longSide += 1

    return if (portrait) {
        config.copy(width = shortSide, height = longSide)
    } else {
        config.copy(width = longSide, height = shortSide)
    }
}

/**
 * Intent izin tangkap layar. Memakai dialog bawaan sistem supaya pengguna
 * tetap bisa memilih "seluruh layar" atau "satu aplikasi" (Android 14+).
 */
private fun createCaptureIntent(manager: MediaProjectionManager): Intent {
    // Android 14+: dialog sistem menampilkan "Seluruh layar" dan
    // "Satu aplikasi", jadi pengguna bisa memilih cakupan tangkapan.
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        manager.createScreenCaptureIntent(
            MediaProjectionConfig.createConfigForUserChoice(),
        )
    } else {
        manager.createScreenCaptureIntent()
    }
}

/** Label mode isi untuk ditampilkan di halaman Siaran/Studio/Setelan. */
@Composable
internal fun contentModeLabel(config: CaptureConfig): String =
    when (config.contentMode) {
        ContentMode.Fit -> stringResource(R.string.common_pas_letterbox)
        ContentMode.Crop -> stringResource(R.string.common_penuh_crop)
        ContentMode.Stretch -> stringResource(R.string.common_peregangan)
    }

/** Orientasi video yang sedang dipakai (bukan orientasi layar). */
@Composable
internal fun orientationLabel(config: CaptureConfig): String = when {
    config.width > config.height -> stringResource(R.string.common_horizontal)
    config.width < config.height -> stringResource(R.string.common_vertikal)
    else -> stringResource(R.string.common_persegi)
}

internal fun formatDuration(milliseconds: Long): String {
    val totalSeconds = milliseconds / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

/** Ukuran berkas untuk statistik rekaman; satuannya mengikuti besar nilai. */
internal fun formatRecordSize(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}
