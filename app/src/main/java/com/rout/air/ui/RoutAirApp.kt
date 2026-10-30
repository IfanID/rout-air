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
import com.rout.air.data.SettingsStore
import com.rout.air.output.CaptureEngine
import com.rout.air.service.CaptureService
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

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

    var config by remember { mutableStateOf(store.load()) }
    var tab by rememberSaveable { mutableStateOf(AppTab.Home) }
    var elapsedMs by remember { mutableStateOf(0L) }

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
    val recordingActive = state is RecordingState.Recording || state is RecordingState.Preparing

    LaunchedEffect(recordingActive) {
        if (!recordingActive) lockedCanvas = null
    }

    LaunchedEffect(config) { store.save(config) }

    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data: Intent? = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            // Ukuran video dikunci pada orientasi saat tombol rekam ditekan.
            lockedCanvas = canvasConfig
            ContextCompat.startForegroundService(
                context,
                CaptureService.startIntent(context, result.resultCode, data, canvasConfig),
            )
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        val manager = context.getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    fun beginCapture() {
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            val manager = context.getSystemService(MediaProjectionManager::class.java)
            projectionLauncher.launch(manager.createScreenCaptureIntent())
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

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
                Lifecycle.Event.ON_START -> CaptureEngine.setPreviewVisible(true)
                Lifecycle.Event.ON_STOP -> {
                    CaptureEngine.setPreviewVisible(false)
                    CaptureEngine.attachPreview(null, 0, 0)
                    CaptureEngine.attachProgram(null, 0, 0)
                }

                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        modifier = modifier,
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
                    onStart = { beginCapture() },
                    onStop = { context.startService(CaptureService.stopIntent(context)) },
                    onClearError = { CaptureEngine.clearError() },
                )

                AppTab.Studio -> StudioScreen(
                    state = state,
                    canvasConfig = canvasConfig,
                    fps = config.fps,
                    elapsedMs = elapsedMs,
                    onStart = { beginCapture() },
                    onStop = { context.startService(CaptureService.stopIntent(context)) },
                    onClearError = { CaptureEngine.clearError() },
                )

                AppTab.Scenes -> ScenesScreen(canvasConfig = canvasConfig)

                AppTab.Audio -> AudioScreen(
                    state = state,
                    recordAudio = config.recordAudio,
                    onRecordAudioChange = { config = config.copy(recordAudio = it) },
                )

                AppTab.Recordings -> RecordingsScreen()

                AppTab.Settings -> SettingsScreen(
                    config = config,
                    canvasConfig = canvasConfig,
                    onConfigChange = { config = it },
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

/** Label mode isi untuk ditampilkan di halaman Siaran/Studio/Setelan. */
internal fun contentModeLabel(config: CaptureConfig): String =
    when (config.contentMode) {
        ContentMode.Fit -> "Pas (letterbox)"
        ContentMode.Crop -> "Penuh (crop)"
        ContentMode.Stretch -> "Peregangan"
    }

/** Orientasi video yang sedang dipakai (bukan orientasi layar). */
internal fun orientationLabel(config: CaptureConfig): String = when {
    config.width > config.height -> "Horizontal"
    config.width < config.height -> "Vertikal"
    else -> "Persegi"
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
