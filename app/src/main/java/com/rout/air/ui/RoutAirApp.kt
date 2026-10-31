package com.rout.air.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
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

@Composable
fun RoutAirApp(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val store = remember(context) { SettingsStore(context) }

    remember(context) { SceneManager.attach(context) }
    remember(context) { AudioMixer.attach(context) }
    val scenes by SceneManager.state.collectAsState()

    val audioMix by AudioMixer.mix.collectAsState()

    var config by remember { mutableStateOf(store.load()) }
    var tab by rememberSaveable { mutableStateOf(AppTab.Home) }
    var elapsedMs by remember { mutableStateOf(0L) }
    var selectedItemId by rememberSaveable { mutableStateOf<String?>(null) }
    // Status pratinjau penuh disimpan di sini supaya tetap diingat saat
    // berpindah tab lalu kembali ke Studio.
    var studioFullscreen by rememberSaveable { mutableStateOf(false) }

    val state by CaptureEngine.state.collectAsState()

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

    // Program = snapshot yang sedang mengudara. Perubahan pratinjau baru masuk
    // ke sini setelah tombol Transisi ditekan, seperti OBS Studio Mode.
    val programScenes by SceneManager.program.collectAsState()
    val programCollection = programScenes ?: collection

    // Sesi pratinjau hidup selama aplikasi terlihat. Tombol rekam tidak perlu
    // izin baru karena memakai sesi yang sama.
    var appForeground by remember { mutableStateOf(true) }
    var pendingAutoRecord by remember { mutableStateOf(false) }

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

    LaunchedEffect(config) {
        store.save(config)
        // Perubahan kanvas (mis. orientasi video) langsung dipakai selama
        // tidak sedang merekam. Rekaman yang berjalan tetap memakai ukuran
        // yang sudah dikunci saat sesi dimulai.
        if (state !is RecordingState.Recording) lockedCanvas = null
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
            // Izin dibatalkan: tidak ada tangkapan yang berjalan. Beri pesan
            // dan biarkan pengguna mencoba lagi lewat tombol rekam.
            CaptureEngine.reportPermissionCancelled()
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
    val endSession: () -> Unit = { CaptureEngine.closeSession() }

    LaunchedEffect(state) {
        val recording = state as? RecordingState.Recording
        if (recording == null) {
            elapsedMs = 0L
            return@LaunchedEffect
        }
        while (true) {
            elapsedMs = System.currentTimeMillis() - recording.startedAtMs
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
                    canvasConfig = canvasConfig,
                    fps = config.fps,
                    elapsedMs = elapsedMs,
                    onStart = startRecording,
                    onStop = stopRecording,
                    onEndSession = endSession,
                    onRequestPermission = { beginSession(autoRecord = false) },
                    onClearError = { CaptureEngine.clearError() },
                )

                AppTab.Studio -> StudioScreen(
                    state = state,
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
                    onConfigChange = { config = it },
                    onMixChange = { AudioMixer.update(it) },
                    onMasterEnabledChange = { AudioMixer.setMasterEnabled(it) },
                )
            }
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
private fun createCaptureIntent(manager: MediaProjectionManager): Intent =
    manager.createScreenCaptureIntent()

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
