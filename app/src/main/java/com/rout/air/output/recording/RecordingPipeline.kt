package com.rout.air.output.recording

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.display.DisplayManager
import android.media.MediaPlayer
import android.media.projection.MediaProjection
import android.os.PowerManager
import android.media.projection.MediaProjectionManager
import android.net.TrafficStats
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Build
import android.os.Debug
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.StatFs
import android.util.DisplayMetrics
import android.util.Size
import android.util.Log
import android.view.Display
import android.view.Surface
import android.view.View
import android.widget.Toast
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.ContextCompat
import com.rout.air.R
import com.rout.air.capture.CameraSizing
import com.rout.air.capture.ScreenCapture
import com.rout.air.core.model.Align
import com.rout.air.core.audio.AudioRuntime
import com.rout.air.core.model.BlendMode
import com.rout.air.core.model.CaptureConfig
import com.rout.air.core.model.ContentMode
import com.rout.air.core.model.Crop
import com.rout.air.core.model.LiveConfig
import com.rout.air.core.model.LiveStats
import com.rout.air.core.audio.MixSnapshot
import com.rout.air.core.model.RecordingState
import com.rout.air.core.model.RecordingStats
import com.rout.air.core.timing.RecordingClock
import com.rout.air.core.model.SceneCollection
import com.rout.air.core.model.SourceMetrics
import com.rout.air.core.model.SceneSource
import com.rout.air.core.model.SourceSettings
import com.rout.air.core.model.SourceType
import com.rout.air.core.model.Transform
import com.rout.air.core.model.VideoCodec
import com.rout.air.data.audio.AudioMixer
import com.rout.air.data.logging.DiagLog
import com.rout.air.data.scene.SceneManager
import com.rout.air.data.scene.updateItem
import com.rout.air.data.scene.updateSource
import com.rout.air.encode.audio.AudioCaptureEngine
import com.rout.air.encode.probe.EncoderProbe
import com.rout.air.encode.mux.Mp4Writer
import com.rout.air.encode.video.OutputTarget
import com.rout.air.encode.mux.TeeSink
import java.io.File
import com.rout.air.encode.video.VideoEncoder
import com.rout.air.engine.gl.EglCore
import com.rout.air.engine.gl.GlCompositor
import com.rout.air.engine.gl.GlUtil
import com.rout.air.engine.source.SourceBitmap
import com.rout.air.live.rtmp.RtmpSink
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import com.rout.air.core.model.Scene
import kotlin.math.roundToInt
import com.rout.air.output.monitor.StorageGuard
import com.rout.air.output.monitor.WatchdogAction
import com.rout.air.output.monitor.WatchdogPolicy
import com.rout.air.output.replay.ReplayBuffer

/**
 * Pipeline rekam layar: MediaProjection -> SurfaceTexture -> GL kompositor
 * -> encoder video, dengan audio internal lewat token projection yang sama.
 *
 * Dua thread sengaja dipisah:
 * - thread `rout-gl` mengurus tangkapan, komposisi, dan encoder;
 * - thread `rout-preview` hanya menggambar pratinjau (dan panel Program di
 *   Studio Mode) memakai konteks EGL berbagi.
 *
 * Pemisahan ini penting: saat aplikasi ke background, SurfaceView berhenti
 * mengambil buffer sehingga eglSwapBuffers bisa menggantung. Dulu itu ikut
 * menghentikan encoder sehingga video terpotong; sekarang hanya thread preview
 * yang tertahan dan rekaman tetap utuh.
 */
class RecordingPipeline(
    private val context: Context,
    private val resultCode: Int,
    private val projectionData: Intent,
    private var config: CaptureConfig,
    previewSurface: Surface?,
    previewWidth: Int,
    previewHeight: Int,
    programSurface: Surface? = null,
    programWidth: Int = 0,
    programHeight: Int = 0,
    private val autoRecord: Boolean = false,
    private val onSessionEnd: () -> Unit = {},
    private val onStats: (RecordingStats) -> Unit = {},
    private val onLiveStats: (LiveStats) -> Unit = {},
    /** Hasil tombol Simpan replay: sukses dan pesan untuk pengguna. */
    private val onReplaySaved: (Boolean, String) -> Unit = { _, _ -> },
    private val onState: (RecordingState) -> Unit,
) {
    private val glThread = HandlerThread("rout-gl").apply { start() }
    private val glHandler = Handler(glThread.looper)
    private val startTimeNs = System.nanoTime()
    private val frameIntervalNs = 1_000_000_000L / config.fps
    private val frameIntervalMs = (1_000L / config.fps).coerceAtLeast(1L)

    /** Interval pacer aktif; bisa turun ke 30 fps saat perangkat panas. */
    @Volatile
    private var pacerIntervalNs = 1_000_000_000L / config.fps

    @Volatile
    private var thermalThrottled = false

    /** Waktu frame layar terakhir benar-benar berubah. */
    @Volatile
    private var lastCaptureChangeNs = 0L

    // Deteksi layar statis lewat sampling piksel (bukan kedatangan frame).
    private val pixelSample = ByteBuffer.allocateDirect(9 * 64 * 4)
        .order(ByteOrder.nativeOrder())
    private var lastScreenRectLogNs = 0L
    private var lastPixelSampleNs = 0L
    private var lastPixelBytes: ByteArray? = null
    private var lastVisualChangeNs = 0L
    private var changeStreak = 0
    private var pixelReadSupported = true
    // Pratinjau 30 fps (15 fps saat layar statis) agar GPU lebih hemat.
    @Volatile
    private var previewIntervalNs = 1_000_000_000L / 30
    private val texMatrix = FloatArray(16)

    // --- Thread preview: semua field di bawah hanya disentuh thread preview ---
    private var previewThread: HandlerThread? = null
    private var previewHandler: Handler? = null
    private var previewEgl: EglCore? = null
    private var previewPainter: GlCompositor? = null
    private var previewEglSurface: EGLSurface? = null
    private var programEglSurface: EGLSurface? = null
    private var overlayProgramEglSurface: EGLSurface? = null
    private var previewTarget: Surface? = previewSurface
    private var previewWidth = previewWidth
    private var previewHeight = previewHeight
    private var programTarget: Surface? = programSurface
    private var overlayProgramTarget: Surface? = null
    private var overlayProgramWidth = 0
    private var overlayProgramHeight = 0
    private var programWidth = programWidth
    private var programHeight = programHeight

    // --- Thread GL (tangkapan + encoder) ---
    private var egl: EglCore? = null
    private var compositor: GlCompositor? = null
    private var textureId = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var virtualDisplaySurface: Surface? = null
    private var projection: MediaProjection? = null
    private var screenCapture: ScreenCapture? = null
    private var displayManager: DisplayManager? = null
    private var displayListener: DisplayManager.DisplayListener? = null
    private var videoEncoder: VideoEncoder? = null
    private var audioEncoder: AudioCaptureEngine? = null
    private var muxer: Mp4Writer? = null
    private var replay: ReplayBuffer? = null
    /** Semua tujuan keluaran aktif: MP4 dan/atau RTMP. */
    private val sinks = TeeSink()
    private var target: OutputTarget? = null
    /** Folder segmen rekaman aman yang sedang berjalan. */
    private var safeSession: SafeRecordingStore.Session? = null
    private var encoderEglSurface: EGLSurface? = null
    private var outputUri: String? = null
    private var audioActive = false

    // --- Scene berlapis ---
    /** Daftar layer aktif; dibaca thread preview, ditulis thread GL. */
    @Volatile
    private var renderLayers: List<RenderLayer> = emptyList()

    /** Layer hasil edit (pratinjau), terpisah dari program. */
    @Volatile
    private var previewLayers: List<RenderLayer> = emptyList()

    /** Cache tekstur 2D per sumber (gambar/teks). */
    private val textureCache = mutableMapOf<String, CachedTexture>()
    private val retiredTextures = mutableListOf<Int>()
    /** Tekstur slide sebelumnya yang masih dipakai animasi fade. */
    private val slidePreviousTextures = mutableMapOf<String, Int>()
    private val slideFadeStartNs = mutableMapOf<String, Long>()

    /** Kamera aktif per sumber; hanya disentuh thread GL. */
    private val cameraSources = mutableMapOf<String, CameraTexture>()
    private val mediaSources = mutableMapOf<String, CameraTexture>()
    private val browserSources = mutableMapOf<String, BrowserTexture>()
    private val cameraHandler by lazy { Handler(glThread.looper) }
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    // Transisi: fade ke hitam, ganti layer di tengah, lalu fade masuk.
    private var pendingProgramCollection: SceneCollection? = null
    private var transitionStartNs = 0L
    private var transitionActive = false

    /** Perubahan program (hasil Transisi); dijalankan di thread GL. */
    private val sceneListener: (SceneCollection) -> Unit = { next ->
        glHandler.post {
            // Tanpa rekam/live tidak ada frame encoder untuk animasi fade,
            // jadi layer program langsung diganti.
            if (!recordingActive && !liveActive) {
                rebuildLayers()
                return@post
            }
            pendingProgramCollection = next
            if (!transitionActive) {
                transitionActive = true
                transitionStartNs = System.nanoTime()
                diag("transisi: mulai")
            }
        }
    }

    /**
     * Perubahan hasil edit: hanya memperbarui gambar pratinjau. Geser/cubit
     * mengirim perubahan hampir tiap frame, jadi permintaan digabung supaya
     * antrean thread GL tidak menumpuk dan UI tidak membeku.
     */
    private var previewRebuildQueued = false
    private var previewRebuildDirty = false
    private val previewRebuildTask = Runnable { runPreviewRebuild() }

    private fun runPreviewRebuild() {
        previewRebuildQueued = false
        rebuildPreviewLayers()
        // Perubahan yang datang saat rebuild berjalan belum tergambar.
        if (previewRebuildDirty) {
            previewRebuildDirty = false
            previewRebuildQueued = true
            glHandler.post(previewRebuildTask)
        }
    }
    private val editListener: (SceneCollection) -> Unit = {
        if (previewRebuildQueued) {
            previewRebuildDirty = true
        } else {
            previewRebuildQueued = true
            glHandler.post(previewRebuildTask)
        }
    }

    /** Ukuran layar yang sedang ditangkap, mengikuti rotasi perangkat. */
    private var sourceWidth = 0
    private var sourceHeight = 0

    @Volatile
    private var lastEncodedNs = Long.MIN_VALUE
    private var lastPreviewNs = 0L

    // FPS hasil publikasi statistik terakhir; dipakai baris diagnostik.
    @Volatile
    private var lastPublishedFps = 0
    private var lastSlowFrameLogNs = 0L

    // Titik acuan perhitungan FPS encoder antar publikasi statistik.
    private var statsBaseNs = 0L
    private var statsBaseFrames = 0

    @Volatile
    private var hasEncodedFrame = false

    @Volatile
    private var hasCapturedFrame = false
    private var capturedFrames = 0
    private var submittedFrames = 0
    private var pacedFrames = 0
    private var nextPacerNs = 0L
    private var swapFailures = 0
    private var consecutiveSwapFailures = 0
    private var watchdogTicks = 0
    private var lastCameraFrames = 0L
    private var lastCameraFrameNs = 0L
    private var teardownStarted = false
    private var stopReason: String? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // Baseline telemetri untuk log diagnostik.
    private var telemetryCpuNs = 0L
    private var telemetryTxBytes = 0L
    private var telemetryRxBytes = 0L

    @Volatile
    private var recordingActive = false
    private var recordingStopping = false

    /** Encoder video/audio hidup selama salah satu output aktif. */
    private var encodersRunning = false

    @Volatile
    private var liveActive = false
    private var liveSink: RtmpSink? = null

    /** PTS saat rekaman mulai; dipakai agar MP4 mulai dari 0 walau live sudah jalan. */
    private var recordingBaseElapsedNs = 0L

    /** Titik nol PTS rekaman, terpisah dari waktu sesi pratinjau. */
    private val recordingClock = RecordingClock()

    /** Jam dinding saat rekaman mulai; dipakai UI menghitung durasi. */
    @Volatile
    private var recordingStartedAtMs = 0L

    @Volatile
    private var lastFrameWallNs = 0L
    private var lastRecoveryAtMs = 0L


    @Volatile
    private var lastEncodedWallNs = 0L

    /** Jumlah buffer yang benar-benar keluar dari encoder (di-set dari thread encoder). */
    @Volatile
    private var encodedOutputs = 0

    @Volatile
    private var previewVisible = true

    @Volatile
    private var previewSurfaceAttached = false

    @Volatile
    private var programSurfaceAttached = false
    private var overlayProgramSurfaceAttached = false

    /** Satu frame preview menunggu; mencegah antrean menumpuk saat preview lambat. */
    private val previewInFlight = AtomicBoolean(false)

    @Volatile
    private var lastPreviewWallNs = 0L

    @Volatile
    private var previewSwaps = 0

    private var previewLogged = false

    @Volatile
    private var running = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            if (!teardownStarted) {
                Log.i(TAG, "MediaProjection dihentikan sistem")
                diag("projection: onStop dari sistem (layar mati/izin dicabut)")
                stopReason = "izin tangkap layar dihentikan"
                closeSession()
            }
        }
    }

    /**
     * Pengawas: kalau layar berhenti mengirim frame (layar mati atau izin
     * dicabut), rekaman ditutup supaya audio tidak berjalan sendirian.
     */
    private val watchdog = object : Runnable {
        override fun run() {
            if (!running) return
            val now = System.nanoTime()
            val lastFrame = lastFrameWallNs
            val lastEncoded = lastEncodedWallNs

            watchdogTicks++
            // Kamera berhenti mengirim frame (mis. DISABLED) membuat gambar
            // diam walau pratinjau layar tetap berjalan.
            if (cameraSources.isNotEmpty()) {
                val total = cameraSources.values.sumOf { it.frames }
                if (total != lastCameraFrames) {
                    lastCameraFrames = total
                    lastCameraFrameNs = now
                } else if (lastCameraFrameNs > 0 &&
                    now - lastCameraFrameNs > 2_000_000_000L
                ) {
                    if (watchdogTicks % 5 == 0) {
                        diag(
                            "kamera: tidak ada frame baru " +
                                "${(now - lastCameraFrameNs) / 1_000_000}ms",
                        )
                    }
                }
            }
            if (watchdogTicks % 5 == 0) {
                val seconds = (now - startTimeNs) / 1_000_000_000L
                diag(
                    "t=${seconds}s frames=$capturedFrames submitted=$submittedFrames " +
                        "encoded=$encodedOutputs fps=$lastPublishedFps " +
                        "swapFail=$swapFailures pace=$pacedFrames " +
                        "preview=$previewSwaps cam=${cameraSources.values.sumOf { it.frames }}" +
                        " camFail=${cameraSources.values.sumOf { it.updateFailures }} " +
                        "src=${sourceWidth}x$sourceHeight " +
                        "audio=$audioActive ${telemetryText()}",
                )
                DiagLog.flush(context)
            }
            // Frame berhenti > 3 detik padahal sesi aktif: biasanya tanda
            // VirtualDisplay berhenti setelah orientasi berubah.
            if (running && lastFrame > 0 && now - lastFrame > 3_000_000_000L &&
                !recordingClock.isPaused
            ) {
                recoverCapture("frame berhenti")
            }
            // Layar mati/terkunci membuat frame tangkapan berhenti. Jangan
            // hentikan rekaman: frame terakhir diulang oleh pengisi frame dan
            // audio tetap jalan, sehingga sesi + izin tidak hilang.
            if (recordingActive && lastFrame > 0 && now - lastFrame > STALL_TIMEOUT_NS) {
                if (watchdogTicks % 20 == 0) {
                    diag("watchdog: frame layar berhenti, pakai frame terakhir")
                }
            }
            // Keputusan diambil dari WatchdogPolicy (diuji lewat unit test):
            // frame layar berhenti TIDAK menghentikan rekaman, dan saat
            // dijeda encoder memang sengaja tidak menerima frame.
            val writer = muxer
            when (WatchdogPolicy.decide(
                recordingActive = recordingActive || liveActive,
                nowNs = now,
                lastEncodedNs = lastEncoded,
                consecutiveSwapFailures = consecutiveSwapFailures,
                stallTimeoutNs = STALL_TIMEOUT_NS,
                paused = recordingClock.isPaused,
                writeFailures = writer?.writeFailures ?: 0,
            )) {
                WatchdogAction.EncoderStalled -> {
                    diag("watchdog: encoder macet")
                    stopReason = "encoder video macet"
                    if (recordingActive) stopRecording() else stopLive()
                    return
                }

                WatchdogAction.EncoderSurfaceFailed -> {
                    diag("watchdog: surface encoder gagal")
                    stopReason = "surface encoder gagal"
                    if (recordingActive) stopRecording() else stopLive()
                    return
                }

                WatchdogAction.StorageFailed -> {
                    diag("watchdog: penulisan file gagal terus")
                    stopReason = com.rout.air.data.common.Msg.get(com.rout.air.R.string.error_storage_full)
                    if (recordingActive) stopRecording() else stopLive()
                    return
                }

                WatchdogAction.Continue -> Unit
            }

            if (watchdogTicks % 5 == 0) updateThermalPolicy()
            // Live tanpa rekaman juga butuh statistik (FPS/bitrate) untuk
            // overlay, jadi kedua mode mempublikasikan statistik.
            if (recordingActive || liveActive) publishStats(now)

            // Preview boleh tertahan tanpa mengganggu rekaman; dicatat saja.
            if (previewVisible && previewInFlight.get() &&
                now - lastPreviewWallNs > PREVIEW_STALL_NS
            ) {
                if (watchdogTicks % 5 == 0) {
                    diag("preview tertahan ${(now - lastPreviewWallNs) / 1_000_000} ms (rekaman lanjut)")
                }
            }
            glHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    /**
     * Pengisi frame: kalau layar berhenti mengirim frame (mis. saat animasi
     * rotasi), frame terakhir diulang setiap interval fps. Dengan begitu
     * timeline video tidak berlubang dan selalu sepanjang audio, sehingga
     * durasi MP4 tidak pernah lebih pendek dari suara.
     */
    private val pacer = object : Runnable {
        override fun run() {
            if (!running || teardownStarted) return

            applyPacerPolicy(System.nanoTime())
            val outputActive = (recordingActive || liveActive) && !recordingClock.isPaused
            if (outputActive && hasCapturedFrame) {
                val core = egl
                val encoderSurface = encoderEglSurface
                if (core != null && encoderSurface != null) {
                    val elapsed = currentPresentationNs()
                    val target = if (lastEncodedNs == Long.MIN_VALUE) {
                        elapsed
                    } else {
                        lastEncodedNs + pacerIntervalNs
                    }
                    // Jangan biarkan jadwal tertinggal jauh bila render lambat.
                    val pts = if (target < elapsed - pacerIntervalNs) elapsed else target
                    hasEncodedFrame = true
                    lastEncodedNs = pts
                    renderToEncoder(core, encoderSurface, pts)
                    submittedFrames++
                    pacedFrames++
                }
            }

            // Pratinjau digerakkan di sini, bukan dari onFrameAvailable, supaya
            // tetap hidup saat layar diam atau surface baru dipasang.
            if ((previewVisible || previewSurfaceAttached || programSurfaceAttached ||
                    overlayProgramSurfaceAttached) &&
                hasCapturedFrame
            ) {
                val previewNow = System.nanoTime()
                if (previewNow - lastPreviewNs >= previewIntervalNs) {
                    lastPreviewNs = previewNow
                    requestPreview(texMatrix.copyOf(), sourceWidth, sourceHeight, renderLayers)
                }
            }

            // Jadwal absolut dengan akumulasi pecahan agar rata-rata tepat 60 fps.
            if (nextPacerNs == 0L) nextPacerNs = System.nanoTime()
            nextPacerNs += pacerIntervalNs
            var delayNs = nextPacerNs - System.nanoTime()
            if (delayNs < 0L) {
                nextPacerNs = System.nanoTime()
                delayNs = 0L
            }
            glHandler.postDelayed(this, (delayNs / 1_000_000L).coerceAtLeast(0L))
        }
    }

    /** Membuka sesi tangkap layar: pratinjau hidup, belum menulis file. */
    fun startSession() {
        onState(RecordingState.Preparing)
        glHandler.post {
            try {
                setupSession()
            } catch (error: Throwable) {
                fail(error)
            }
        }
    }

    /** Mulai menulis MP4 memakai sesi pratinjau yang sudah berjalan. */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        // PARTIAL menjaga CPU; layar bright menjaga display tetap hidup agar
        // MediaProjection tidak dihentikan sistem saat layar auto-lock.
        val flags = PowerManager.PARTIAL_WAKE_LOCK
        wakeLock = power.newWakeLock(flags, "RoutAir::recording").apply {
            setReferenceCounted(false)
            runCatching { acquire(6 * 60 * 60 * 1000L) }
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.release() }
        wakeLock = null
    }

    fun startRecording() {
        glHandler.post {
            if (teardownStarted || recordingActive) return@post
            try {
                acquireWakeLock()
                beginRecording()
            } catch (error: Throwable) {
                Log.e(TAG, "Gagal memulai rekaman", error)
                diag("rekam gagal: ${error.message}")
                releaseWakeLock()
                abortRecording(error.message ?: context.getString(R.string.error_start_recording))
            }
        }
    }

    /** Hentikan penulisan file; live (bila aktif) dan pratinjau tetap berjalan. */
    fun stopRecording() {
        if (!recordingActive || recordingStopping) return
        recordingStopping = true
        releaseWakeLock()
        if (liveActive) {
            // Encoder dipakai live; cukup lepas MP4 tanpa menghentikan kodek.
            glHandler.post { detachRecording() }
            return
        }
        stopEncodersAsync()
    }

    /**
     * Melepas file MP4 saat live masih berjalan. Encoder tetap hidup sehingga
     * streaming tidak terputus.
     */
    private fun detachRecording() {
        if (!recordingActive) {
            recordingStopping = false
            return
        }
        recordingActive = false
        val writer = muxer
        val finalBytes = writer?.bytesWritten ?: 0L
        writer?.let { sinks.remove(it, finish = false) }
        releaseReplay()
        val saved = runCatching { writer?.finish() ?: false }.getOrDefault(false)
        muxer = null
        target = null

        diag("rekam: lepas (live lanjut) saved=$saved")
        publishStats(System.nanoTime(), finalBytes)
        // Encoder masih hidup untuk live, tapi acuan FPS diulang agar tidak
        // menghitung frame sebelum rekaman berhenti.
        statsBaseNs = 0L
        statsBaseFrames = 0
        recordingStopping = false
        recordingBaseElapsedNs = 0L
        recordingStartedAtMs = 0L
        if (saved) {
            onState(RecordingState.Previewing)
        } else {
            onState(RecordingState.Error("Rekaman gagal disimpan"))
        }
    }

    /** Menghentikan semua encoder setelah audio selesai (di luar thread GL). */
    private fun stopEncodersAsync() {
        val stopPtsUs = if (hasEncodedFrame) lastEncodedNs / 1_000L else Long.MAX_VALUE
        Thread({
            runCatching { audioEncoder?.endRecording(stopPtsUs) }
                .onFailure { Log.w(TAG, "Trek audio gagal dihentikan: ${it.message}") }
            glHandler.post { endRecording() }
        }, "rout-stop").start()
    }

    /** Jeda rekaman: frame dan audio berhenti masuk timeline. */
    fun pauseRecording() {
        glHandler.post {
            if (!recordingActive || recordingStopping) return@post
            // Satu encoder dipakai live; menjeda akan membekukan siaran.
            if (liveActive) return@post
            if (recordingClock.isPaused) return@post
            recordingClock.pause(System.nanoTime())
            audioEncoder?.pauseRecording()
            diag("rekam: dijeda")
            publishRecordingState()
        }
    }

    /** Lanjutkan rekaman setelah dijeda, tanpa gap di timeline. */
    fun resumeRecording() {
        glHandler.post {
            if (!recordingActive || recordingStopping) return@post
            if (!recordingClock.isPaused) return@post
            recordingClock.resume(System.nanoTime())
            audioEncoder?.resumeRecording()
            // Mulai jadwal pacer dari sekarang setelah jeda.
            nextPacerNs = 0L
            diag("rekam: dilanjutkan")
            publishRecordingState()
        }
    }

    /** Menutup sesi sepenuhnya: pratinjau berhenti dan projection dilepas. */
    fun closeSession() {
        if (teardownStarted) return
        teardownStarted = true
        running = false
        releaseWakeLock()
        val stopPtsUs = if (hasEncodedFrame) lastEncodedNs / 1_000L else Long.MAX_VALUE
        Thread({
            runCatching { audioEncoder?.shutdown(stopPtsUs) }
                .onFailure { Log.w(TAG, "Mesin audio gagal dihentikan: ${it.message}") }
            glHandler.post { teardownSession() }
        }, "rout-close").start()
    }

    /**
     * Memasang ulang surface pratinjau. SurfaceView membuat surface baru setiap
     * aplikasi kembali dari background, sedangkan EGL surface lama sudah mati.
     */
    fun updatePreview(surface: Surface?, width: Int, height: Int) {
        previewHandler?.post {
            val core = previewEgl
            releaseSurface(core, previewEglSurface)
            previewEglSurface = null
            previewTarget = surface
            previewSurfaceAttached = surface != null
            if (surface != null && width > 0 && height > 0) {
                previewWidth = width
                previewHeight = height
            }
            diag(
                if (surface == null || width <= 0) {
                    "preview: surface dilepas"
                } else {
                    "preview: surface ${width}x$height"
                },
            )
        }
        if (surface != null) requestPreviewNow()
    }

    /**
     * Surface program tambahan untuk pratinjau overlay live. Dipisah dari
     * [updateProgram] supaya panel Program di Studio dan overlay bisa
     * menampilkan gambar yang sama secara bersamaan.
     */
    fun updateOverlayProgram(surface: Surface?, width: Int, height: Int) {
        previewHandler?.post {
            val core = previewEgl
            releaseSurface(core, overlayProgramEglSurface)
            overlayProgramEglSurface = null
            overlayProgramTarget = surface
            overlayProgramSurfaceAttached = surface != null
            if (surface != null && width > 0 && height > 0) {
                overlayProgramWidth = width
                overlayProgramHeight = height
            }
            diag(
                if (surface == null || width <= 0) {
                    "program overlay: surface dilepas"
                } else {
                    "program overlay: surface ${width}x$height"
                },
            )
        }
        if (surface != null) requestPreviewNow()
    }

    /** Surface panel Program (Studio Mode); dirender di thread preview yang sama. */
    fun updateProgram(surface: Surface?, width: Int, height: Int) {
        previewHandler?.post {
            val core = previewEgl
            releaseSurface(core, programEglSurface)
            programEglSurface = null
            programTarget = surface
            programSurfaceAttached = surface != null
            if (surface != null && width > 0 && height > 0) {
                programWidth = width
                programHeight = height
            }
            diag(
                if (surface == null || width <= 0) {
                    "program: surface dilepas"
                } else {
                    "program: surface ${width}x$height"
                },
            )
        }
        if (surface != null) requestPreviewNow()
    }

    /** Segera gambar satu frame saat surface pratinjau baru terpasang. */
    private fun requestPreviewNow() {
        glHandler.post {
            if (!hasCapturedFrame || teardownStarted) return@post
            lastPreviewNs = 0L
            requestPreview(texMatrix.copyOf(), sourceWidth, sourceHeight, renderLayers)
        }
    }

    fun setPreviewVisible(visible: Boolean) {
        if (previewVisible == visible) return
        previewVisible = visible
        diag(if (visible) "preview: aktif" else "preview: nonaktif")
    }

    private fun setupSession() {
        DiagLog.reset()
        diag("sesi: video=${config.width}x${config.height} fps=${config.fps} mode=${config.contentMode}")
        diag(
            "setelan: encoder=${config.encoderWidth}x${config.encoderHeight} " +
                "bitrate=${config.videoBitrate / 1000}kbps " +
                "rate=${config.bitrateMode} cq=${config.cqLevel} " +
                "codec=${config.videoCodec.label} profil=${config.videoProfile} " +
                "keyframe=${config.keyframeIntervalSec}s " +
                "warna=${config.colorSpace}/${config.colorRange} " +
                "kanvas=${config.canvasOrientation}",
        )

        val manager = context.getSystemService(MediaProjectionManager::class.java)
            ?: error(context.getString(R.string.error_projection_missing))
        val mediaProjection = manager.getMediaProjection(resultCode, projectionData)
            ?: error(context.getString(R.string.error_projection_invalid))
        mediaProjection.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
        projection = mediaProjection

        val core = EglCore()
        egl = core
        // Belum ada encoder, jadi konteks diaktifkan tanpa window surface.
        core.makeCurrentNoSurface()

        val glCompositor = GlCompositor()
        glCompositor.init()
        glCompositor.setCanvasSize(config.width, config.height)
        compositor = glCompositor

        // Scene disusun untuk kanvas tertentu. Kalau orientasi/ukuran berubah
        // sejak terakhir disusun, posisinya disesuaikan agar tidak berada di
        // luar kanvas (yang membuat hasil rekaman hitam).
        val settings = com.rout.air.data.settings.SettingsStore(context)
        settings.loadSceneCanvas()?.let { (lastWidth, lastHeight) ->
            if (lastWidth != config.width || lastHeight != config.height) {
                SceneManager.rescaleCanvas(
                    lastWidth,
                    lastHeight,
                    config.width,
                    config.height,
                )
                diag("kanvas: scene disesuaikan ${lastWidth}x$lastHeight -> ${config.width}x${config.height}")
            }
        }
        settings.saveSceneCanvas(config.width, config.height)

        val texture = GlUtil.createExternalTexture()
        textureId = texture
        val frameTexture = SurfaceTexture(texture)
        surfaceTexture = frameTexture
        // Listener dipasang sebelum VirtualDisplay agar frame pertama tidak hilang.
        frameTexture.setOnFrameAvailableListener({ onFrameAvailable() }, glHandler)

        // Tangkapan mengikuti ukuran layar fisik, bukan ukuran video.
        val display = context.getSystemService(DisplayManager::class.java)
        displayManager = display
        readDisplaySize()?.let { (width, height) ->
            sourceWidth = width
            sourceHeight = height
        }
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            error(context.getString(R.string.error_screen_size))
        }
        frameTexture.setDefaultBufferSize(sourceWidth, sourceHeight)

        val displaySurface = Surface(frameTexture)
        virtualDisplaySurface = displaySurface

        screenCapture = ScreenCapture(mediaProjection).also {
            it.start(
                width = sourceWidth,
                height = sourceHeight,
                densityDpi = context.resources.displayMetrics.densityDpi,
                surface = displaySurface,
            )
        }
        registerDisplayListener()

        // Layer scene disiapkan di konteks GL, lalu dipakai bersama dengan
        // thread preview lewat konteks EGL berbagi.
        rebuildLayers()
        rebuildPreviewLayers()
        SceneManager.addListener(sceneListener)
        SceneManager.addEditListener(editListener)

        // Thread preview dengan konteks berbagi: tekstur frame ikut terpakai.
        startPreviewThread(core)

        running = true
        lastFrameWallNs = System.nanoTime()
        lastEncodedWallNs = System.nanoTime()
        lastPreviewWallNs = System.nanoTime()
        diag("pratinjau siap: layar=${sourceWidth}x$sourceHeight")
        glHandler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS)
        glHandler.postDelayed(pacer, frameIntervalMs)

        // Sumber audio hidup sejak pratinjau, seperti mixer OBS: meter dan
        // monitoring sudah bekerja sebelum tombol rekam ditekan.
        startAudioEngine(mediaProjection)

        onState(RecordingState.Previewing)

        if (autoRecord) startRecording()
    }

    /**
     * Menyiapkan mesin audio untuk seluruh sesi. Kodek baru dibuat saat
     * merekam, jadi mode pratinjau hanya menangkap, mencampur, dan mengukur.
     */
    private fun startAudioEngine(activeProjection: MediaProjection) {
        if (!hasAudioPermission()) {
            AudioMixer.setRuntime(AudioRuntime())
            return
        }

        AudioMixer.clearRuntime()
        diag(
            "audio: ${config.sampleRate}Hz ${config.channelCount}ch " +
                "${config.audioBitrate / 1000}kbps",
        )
        audioEncoder = try {
            AudioCaptureEngine(
                context = context,
                projection = activeProjection,
                sampleRate = config.sampleRate,
                channels = config.channelCount,
                bitrate = config.audioBitrate,
                liveMix = { AudioMixer.snapshot() },
                onLevels = { levels ->
                    AudioMixer.reportLevels(levels)
                },
                onAvailability = { ready ->
                    AudioMixer.setRuntime(
                        AudioRuntime(running = true, ready = ready),
                    )
                },
            ).also { it.start() }
        } catch (error: Throwable) {
            diag("audio: gagal disiapkan (${error.message})")
            Log.w(TAG, "Mesin audio gagal disiapkan: ${error.message}")
            AudioMixer.setRuntime(AudioRuntime())
            null
        }
    }

    /** Menyiapkan file MP4 memakai sesi dan encoder yang (mungkin) sudah jalan. */
    private fun beginRecording() {
        check(egl != null) { com.rout.air.data.common.Msg.get(com.rout.air.R.string.error_capture_not_ready) }
        check(projection != null) { com.rout.air.data.common.Msg.get(com.rout.air.R.string.error_projection_invalid) }

        // MediaMuxer baru bisa menulis AV1 mulai Android 14.
        val plannedCodec = if (encodersRunning) {
            videoEncoder?.activeCodec
        } else {
            EncoderProbe.select(config.videoCodec)?.codec
        }
        if (plannedCodec == VideoCodec.AV1 && Build.VERSION.SDK_INT < 34) {
            error("MP4 AV1 butuh Android 14 ke atas")
        }

        diag("rekam: mulai")
        val freeBytes = runCatching {
            StatFs(Environment.getExternalStorageDirectory().path).availableBytes
        }.getOrDefault(Long.MAX_VALUE)
        if (!StorageGuard.hasRoom(freeBytes)) {
            val freeMb = freeBytes / (1024 * 1024)
            error("Penyimpanan hampir penuh ($freeMb MB tersisa, minimal 200 MB)")
        }

        // Split recording dan replay memakai rotasi segmen yang sama; nama
        // part pertama dipakai sebagai dasar supaya cap waktunya seragam.
        val splitActive = config.splitMinutes > 0 || config.splitSizeMb > 0
        // Rekaman aman menulis segmen ke folder aplikasi dulu, lalu digabung
        // saat berhenti. Split eksplisit tidak memakai mode ini karena setiap
        // part-nya memang sudah berkas final sendiri.
        val safeActive = config.safeRecording && !splitActive
        if (safeActive) {
            val session = runCatching { SafeRecordingStore.begin(context, config) }.getOrNull()
            if (session != null) {
                safeSession = session
                val minutes = config.safeSegmentMinutes.coerceIn(1, 30)
                val spec = Mp4Writer.SegmentSpec(
                    maxDurationUs = minutes * 60_000_000L,
                    maxBytes = 0L,
                    next = { part -> SafeRecordingStore.segmentTarget(session, part) },
                )
                muxer = Mp4Writer(SafeRecordingStore.segmentTarget(session, 1), spec)
                target = null
                outputUri = null
                diag("rekam: aman segmen=$minutes menit folder=${session.directory.name}")
            } else {
                diag("rekam: folder segmen gagal dibuat, memakai MP4 biasa")
            }
        }
        if (muxer == null) {
            val output = OutputTarget.create(
                context = context,
                location = config.saveLocation,
                nameTemplate = config.fileNameTemplate,
                noSpaceFileName = config.noSpaceFileName,
            )
            target = output
            outputUri = output.uri.toString()
            val baseName = output.displayName
            val segmentSpec = if (splitActive) {
                Mp4Writer.SegmentSpec(
                    maxDurationUs = config.splitMinutes * 60_000_000L,
                    maxBytes = config.splitSizeMb.toLong() * 1024L * 1024L,
                    next = { part ->
                        runCatching {
                            OutputTarget.create(
                                context = context,
                                location = config.saveLocation,
                                nameTemplate = config.fileNameTemplate,
                                noSpaceFileName = config.noSpaceFileName,
                                part = part,
                                baseName = baseName,
                            )
                        }.getOrNull()
                    },
                )
            } else {
                null
            }
            muxer = Mp4Writer(output, segmentSpec)
        }
        val writer = muxer ?: error(
            com.rout.air.data.common.Msg.get(com.rout.air.R.string.error_create_file, config.saveLocation.relativePath),
        )

        val mixSnapshot = AudioMixer.snapshot()
        val wantsAudio = audioEncoder != null && mixSnapshot.masterEnabled &&
            mixSnapshot.channels.values.any { it.enabled }
        val trackCount = Integer.bitCount(mixSnapshot.outputTracks and 0x3F).coerceAtLeast(1)
        // Harus dijanjikan sebelum format pertama datang.
        writer.expectTracks(videoTracks = 1, audioTrackCount = if (wantsAudio) trackCount else 0)

        if (!recordingClock.isStarted) {
            recordingClock.start(System.nanoTime())
            resetTelemetryBaseline()
        }
        recordingBaseElapsedNs = recordingClock.elapsedNs(System.nanoTime())
        recordingStartedAtMs = System.currentTimeMillis()

        if (!encodersRunning) startEncoders(wantsAudio, mixSnapshot)
        sinks.add(writer)

        // Replay buffer menulis segmen 5 detik sendiri; dimatikan otomatis
        // saat batas penyimpanan habis supaya rekaman utama tidak terganggu.
        if (config.replayBufferEnabled) {
            replay = runCatching {
                ReplayBuffer(
                    context = context,
                    directory = File(context.cacheDir, "replay"),
                    targetDurationUs = config.replaySeconds * 1_000_000L,
                ).also {
                    it.expectTracks(if (wantsAudio) trackCount else 0)
                    sinks.add(it.sink())
                }
            }.onFailure { diag("replay: gagal disiapkan (${it.message})") }.getOrNull()
        }

        recordingStopping = false
        recordingActive = true

        diag("rekam: audio=$audioActive tujuan=$outputUri")
        onStats(RecordingStats())
        publishRecordingState()
    }

    /**
     * Mengubah ukuran kanvas tanpa memulai ulang sesi. Hanya dipakai saat
     * tidak merekam/menyiarkan, supaya berkas MP4 tidak berubah ukuran.
     */
    fun updateCanvas(width: Int, height: Int) {
        glHandler.post {
            if (width <= 0 || height <= 0) return@post
            if (config.width == width && config.height == height) return@post
            if (recordingActive || liveActive) return@post
            val oldWidth = config.width
            val oldHeight = config.height
            config = config.copy(width = width, height = height)
            compositor?.setCanvasSize(width, height)
            previewPainter?.setCanvasSize(width, height)
            // Sesi aktif: pipeline yang menyesuaikan scene supaya pemanggil
            // tidak melakukannya dua kali.
            com.rout.air.data.scene.SceneManager.rescaleCanvas(oldWidth, oldHeight, width, height)
            com.rout.air.data.settings.SettingsStore(context).saveSceneCanvas(width, height)
            diag("kanvas: diperbarui ${oldWidth}x$oldHeight -> ${width}x$height")
        }
    }

    /** Memulai live tanpa rekaman, atau menambah live saat rekaman berjalan. */
    fun startLive(liveConfig: LiveConfig) {
        glHandler.post {
            if (teardownStarted || liveActive) return@post
            if (!running) {
                onLiveStats(
                    LiveStats(
                        status = com.rout.air.core.model.LiveStatus.Error,
                        lastError = "Sesi tangkap layar belum aktif",
                    ),
                )
                return@post
            }
            try {
                if (!recordingClock.isStarted) {
                    recordingClock.start(System.nanoTime())
                    resetTelemetryBaseline()
                }
                if (!encodersRunning) {
                    val mix = AudioMixer.snapshot()
                    val wantsAudio = audioEncoder != null && mix.masterEnabled &&
                        mix.channels.values.any { it.enabled }
                    startEncoders(wantsAudio, mix, forLive = true)
                }
                // Sink dibuat setelah encoder supaya cek codec server memakai
                // codec yang benar-benar aktif, bukan yang diminta.
                val sink = RtmpSink(
                    config = liveConfig,
                    videoCodec = videoEncoder?.activeCodec ?: this.config.videoCodec,
                    onStats = onLiveStats,
                )
                liveSink = sink
                sinks.add(sink)
                sink.start()
                liveActive = true
                diag("live: mulai ${liveConfig.serverUrl}")
            } catch (error: Throwable) {
                diag("live gagal: ${error.message}")
                liveSink?.let { sinks.remove(it, finish = true) }
                liveSink = null
                onLiveStats(
                    LiveStats(
                        status = com.rout.air.core.model.LiveStatus.Error,
                        lastError = error.message ?: "Live gagal dimulai",
                    ),
                )
            }
        }
    }

    /** Menghentikan live; rekaman (bila ada) tetap berjalan. */
    fun stopLive() {
        glHandler.post {
            if (!liveActive) {
                onLiveStats(LiveStats())
                return@post
            }
            liveActive = false
            val sink = liveSink
            liveSink = null
            sink?.let { sinks.remove(it, finish = true) }
            diag("live: berhenti")
            onLiveStats(LiveStats())
            if (!recordingActive) stopEncodersAsync()
        }
    }

    /** Membuat encoder video+audio pertama kali untuk output apa pun. */
    private fun startEncoders(
        wantsAudio: Boolean,
        mixSnapshot: MixSnapshot,
        forLive: Boolean = false,
    ) {
        val core = egl ?: error("Sesi tangkap layar belum siap")

        var audioStarted = false
        if (wantsAudio) {
            try {
                audioEncoder?.beginRecording(sinks, recordingClock.startTimeNs, mixSnapshot)
                audioStarted = audioEncoder != null
            } catch (error: Throwable) {
                Log.w(TAG, "Trek audio gagal dimulai: ${error.message}")
            }
        }
        audioActive = audioStarted

        // Rekaman tanpa live boleh memakai HEVC (file lebih kecil) bila
        // encodernya hardware. Live tetap memakai codec yang dipilih karena
        // server RTMP umumnya hanya menerima H.264.
        val recordingOnly = !forLive && !liveActive
        val requestedCodec = if (recordingOnly && config.preferHevcRecording &&
            EncoderProbe.select(VideoCodec.HEVC)?.hardware == true
        ) {
            VideoCodec.HEVC
        } else {
            config.videoCodec
        }
        if (requestedCodec != config.videoCodec) {
            diag("encoder: HEVC (HW) untuk rekaman, live tetap ${config.videoCodec.label}")
        }
        val encoder = VideoEncoder(
            width = config.encoderWidth,
            height = config.encoderHeight,
            fps = config.fps,
            // Rekaman boleh punya bitrate sendiri, tetapi tetap satu encoder
            // saat live dan rekam berjalan bersamaan.
            bitrate = config.encoderBitrate(recordingOnly = !forLive && !liveActive),
            keyframeIntervalSec = config.keyframeIntervalSec,
            bitrateMode = config.bitrateMode,
            cqLevel = config.cqLevel,
            profile = config.videoProfile,
            colorSpace = config.colorSpace,
            colorRange = config.colorRange,
            requestedCodec = requestedCodec,
            encoderName = EncoderProbe.select(requestedCodec)?.name,
            sink = sinks,
            onFallback = { actual, message ->
                diag("encoder: $message (dipakai ${actual.label})")
            },
            onEncoderError = { error ->
                diag("encoder error: ${error.message}")
                glHandler.post {
                    if (recordingActive || liveActive) {
                        stopReason = "encoder video berhenti: ${error.message}"
                        if (recordingActive) stopRecording() else stopLive()
                    }
                }
            },
            onFrameEncoded = {
                encodedOutputs++
                lastEncodedWallNs = System.nanoTime()
            },
        )
        videoEncoder = encoder

        val encoderSurface = core.createWindowSurface(encoder.inputSurface)
        encoderEglSurface = encoderSurface
        // Konteks harus aktif pada surface encoder sebelum frame pertama.
        core.makeCurrent(encoderSurface)

        lastPixelSampleNs = 0L
        lastPixelBytes = null
        lastVisualChangeNs = 0L
        changeStreak = 0
        pixelReadSupported = true
        submittedFrames = 0
        pacedFrames = 0
        encodedOutputs = 0
        swapFailures = 0
        consecutiveSwapFailures = 0
        nextPacerNs = 0L
        hasEncodedFrame = false
        lastEncodedNs = Long.MIN_VALUE
        lastEncodedWallNs = System.nanoTime()
        // Titik acuan FPS baru dimulai bersama encoder yang baru.
        statsBaseNs = 0L
        statsBaseFrames = 0
        encodersRunning = true
    }

    /** Dipanggil setelah audio selesai; menutup semua output tanpa mematikan sesi. */
    private fun endRecording() {
        if (!recordingActive && !liveActive && !encodersRunning) {
            recordingStopping = false
            return
        }
        val hadRecording = recordingActive
        recordingActive = false
        liveActive = false
        glHandler.removeCallbacks(pacer)

        runCatching { videoEncoder?.finish() }
            .onFailure { Log.w(TAG, "Video gagal dihentikan: ${it.message}") }
        videoEncoder = null

        val core = egl
        encoderEglSurface?.let { runCatching { core?.releaseSurface(it) } }
        encoderEglSurface = null

        val finalBytes = muxer?.bytesWritten ?: 0L
        muxer?.let { sinks.remove(it, finish = false) }
        releaseReplay()
        val saved = runCatching { muxer?.finish() ?: false }.getOrDefault(false)
        muxer = null
        target = null
        val finishedSafe = safeSession
        safeSession = null
        if (finishedSafe != null) {
            // Penanda aktif dilepas dulu supaya sesi ini boleh ditawarkan
            // untuk dipulihkan bila penggabungan gagal.
            SafeRecordingStore.endActive(finishedSafe)
            if (saved) {
                mergeSafeRecording(finishedSafe)
            } else {
                // Segmen yang sudah difinalisasi tetap disimpan supaya bisa
                // dipulihkan lewat dialog saat aplikasi dibuka lagi.
                diag("rekam: segmen disimpan untuk dipulihkan (${finishedSafe.directory.name})")
            }
        }

        liveSink?.let { sinks.remove(it, finish = true) }
        liveSink = null
        sinks.finishAll()

        diag(
            "stop: reason=${stopReason ?: "-"} frames=$capturedFrames submitted=$submittedFrames " +
                "encoded=$encodedOutputs swapFail=$swapFailures pace=$pacedFrames " +
                "preview=$previewSwaps saved=$saved ${telemetryText()}",
        )
        DiagLog.writeToDownloads(context)

        publishStats(System.nanoTime(), finalBytes)
        onLiveStats(LiveStats())

        val reason = stopReason
        stopReason = null
        recordingStopping = false
        encodersRunning = false
        hasEncodedFrame = false
        lastEncodedNs = Long.MIN_VALUE
        lastEncodedWallNs = System.nanoTime()
        recordingClock.reset()
        recordingStartedAtMs = 0L
        recordingBaseElapsedNs = 0L
        statsBaseNs = 0L
        statsBaseFrames = 0

        if (!hadRecording || saved) {
            onState(RecordingState.Previewing)
            if (reason != null) Log.i(TAG, "Rekaman berhenti lebih awal: $reason")
        } else {
            val detail = reason?.let { " • $it" }.orEmpty()
            // Rekaman aman sudah menyimpan segmen, jadi pesannya mengarahkan
            // ke pemulihan, bukan sekadar "gagal disimpan".
            val message = if (finishedSafe != null) {
                context.getString(R.string.error_record_recoverable)
            } else {
                context.getString(
                    R.string.error_record_not_saved,
                    capturedFrames,
                    encodedOutputs,
                    detail,
                )
            }
            onState(RecordingState.Error(message))
        }
        // Sesi pratinjau masih hidup: pacer wajib jalan lagi, kalau tidak
        // panel Program (dan Pratinjau) membeku di frame terakhir.
        restartPacerIfRunning()
    }

    /**
     * Menggabungkan segmen rekaman aman menjadi satu MP4 di galeri.
     * Dijalankan di thread sendiri supaya thread GL tidak tertahan.
     */
    private fun mergeSafeRecording(session: SafeRecordingStore.Session) {
        Thread({
            val segmentCount = session.segments.size
            val uri = SafeRecordingStore.merge(context, session)
            DiagLog.line(
                "gabung: ${if (uri != null) "ok" else "gagal"} " +
                    "segmen=$segmentCount folder=${session.directory.name}",
            )
            Handler(Looper.getMainLooper()).post {
                if (uri != null) outputUri = uri.toString()
                val text = context.getString(
                    if (uri != null) R.string.recording_saved
                    else R.string.recording_kept_segments,
                )
                Toast.makeText(context, text, Toast.LENGTH_LONG).show()
            }
        }, "rout-safe-merge").start()
    }

    /**
     * Menyimpan segmen replay terakhir. Penggabungan dilakukan di thread
     * sendiri supaya frame yang sedang direkam tidak tertahan.
     */
    fun saveReplay() {
        val buffer = replay
        if (buffer == null) {
            onReplaySaved(false, com.rout.air.data.common.Msg.get(com.rout.air.R.string.replay_buffer_inactive))
            return
        }
        val location = config.saveLocation
        val template = config.fileNameTemplate
        val noSpace = config.noSpaceFileName
        Thread({
            val uri = runCatching { buffer.save(location, template, noSpace) }.getOrNull()
            if (uri != null) {
                diag("replay: disimpan ke $uri")
                onReplaySaved(true, uri.toString())
            } else {
                diag("replay: tidak ada segmen untuk disimpan")
                onReplaySaved(false, com.rout.air.data.common.Msg.get(com.rout.air.R.string.replay_no_segments))
            }
        }, "rout-replay-save").start()
    }

    private fun releaseReplay() {
        val buffer = replay ?: return
        replay = null
        sinks.remove(buffer.sink(), finish = false)
        runCatching { buffer.release() }
    }

    /**
     * Menghidupkan kembali pacer setelah encoder berhenti. Pacer adalah
     * penggerak satu-satunya frame pratinjau; tanpa ini gambar tertahan.
     */
    private fun restartPacerIfRunning() {
        if (!running || teardownStarted) return
        glHandler.removeCallbacks(pacer)
        nextPacerNs = 0L
        glHandler.postDelayed(pacer, frameIntervalMs)
    }

    /** Menghitung statistik live dari pipeline; dipanggil tiap detik. */
    private fun publishStats(nowNs: Long, bytesOverride: Long? = null) {
        val durationNs = (recordingClock.elapsedNs(nowNs) - recordingBaseElapsedNs)
            .coerceAtLeast(0L)
        val durationMs = durationNs / 1_000_000L
        val bytes = bytesOverride ?: muxer?.bytesWritten ?: 0L
        val seconds = durationMs / 1000.0
        val kbps = if (seconds > 0.5) {
            ((bytes * 8.0) / seconds / 1000.0).roundToInt()
        } else {
            0
        }
        // FPS = selisih frame encoder sejak publikasi sebelumnya. Publikasi
        // pertama hanya menjadi titik acuan sehingga nilainya 0.
        val fps = if (statsBaseNs == 0L || nowNs <= statsBaseNs) {
            statsBaseNs = nowNs
            statsBaseFrames = encodedOutputs
            0
        } else {
            val span = (nowNs - statsBaseNs) / 1_000_000_000.0
            val frames = (encodedOutputs - statsBaseFrames).coerceAtLeast(0)
            statsBaseNs = nowNs
            statsBaseFrames = encodedOutputs
            if (span > 0.0) (frames / span).roundToInt() else 0
        }
        lastPublishedFps = fps
        onStats(
            RecordingStats(
                durationMs = durationMs,
                frames = encodedOutputs,
                droppedFrames = swapFailures,
                bytes = bytes,
                bitrateKbps = kbps,
                paused = recordingClock.isPaused,
                fps = fps,
            ),
        )
    }

    /** Mengirim state rekaman terkini, termasuk status jeda, ke UI/service. */
    private fun publishRecordingState() {
        onState(
            RecordingState.Recording(
                startedAtMs = recordingStartedAtMs,
                outputUri = outputUri,
                audioActive = audioActive,
                pausedAtMs = if (recordingClock.isPaused) {
                    (recordingClock.elapsedMs(System.nanoTime()) -
                        recordingBaseElapsedNs / 1_000_000L).coerceAtLeast(0L)
                } else {
                    null
                },
                pausedTotalMs = recordingClock.pausedTotalMs,
            ),
        )
    }

    /** Gagal saat menyiapkan encoder: bersihkan file dan kembali ke pratinjau. */
    private fun abortRecording(message: String) {
        recordingActive = false
        liveActive = false
        glHandler.removeCallbacks(pacer)
        runCatching { audioEncoder?.endRecording() }
        runCatching { videoEncoder?.finish() }
        videoEncoder = null
        val core = egl
        encoderEglSurface?.let { runCatching { core?.releaseSurface(it) } }
        encoderEglSurface = null
        muxer?.let { sinks.remove(it, finish = false) }
        releaseReplay()
        runCatching { muxer?.abort() }
        muxer = null
        runCatching { target?.finish(false) }
        target = null
        safeSession?.let { SafeRecordingStore.discard(it) }
        safeSession = null
        sinks.finishAll()
        liveSink = null
        encodersRunning = false
        recordingStopping = false
        recordingClock.reset()
        recordingStartedAtMs = 0L
        recordingBaseElapsedNs = 0L
        statsBaseNs = 0L
        statsBaseFrames = 0
        onStats(RecordingStats())
        onLiveStats(LiveStats())
        onState(RecordingState.Error(message))
        // Sesi pratinjau tetap hidup setelah encoder gagal disiapkan.
        restartPacerIfRunning()
    }

    private fun readDisplaySize(): Pair<Int, Int>? {
        val manager = displayManager ?: return null
        val display = manager.getDisplay(Display.DEFAULT_DISPLAY) ?: return null
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) return null
        return metrics.widthPixels to metrics.heightPixels
    }

    private fun registerDisplayListener() {
        val manager = displayManager ?: return
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit

            override fun onDisplayRemoved(displayId: Int) = Unit

            override fun onDisplayChanged(displayId: Int) {
                if (displayId == Display.DEFAULT_DISPLAY) refreshSourceSize()
            }
        }
        manager.registerDisplayListener(listener, glHandler)
        displayListener = listener
    }

    /** Layar berputar: tangkapan ikut menyesuaikan, video tetap ukuran semula. */
    private fun refreshSourceSize() {
        val (width, height) = readDisplaySize() ?: return
        if (width == sourceWidth && height == sourceHeight) return

        val dpi = context.resources.displayMetrics.densityDpi
        surfaceTexture?.setDefaultBufferSize(width, height)
        val resized = screenCapture?.resize(width, height, dpi) ?: false
        sourceWidth = width
        sourceHeight = height
        diag("layar berubah: ${width}x$height (resize=$resized)")
        // Resize yang dilaporkan berhasil kadang tidak benar-benar mengirim
        // frame lagi; display dibuat ulang supaya tangkapan tidak hitam.
        if (!resized) recoverCapture("resize gagal")
    }

    /**
     * Membuat ulang VirtualDisplay pada SurfaceTexture yang sama.
     *
     * Dipakai saat tangkapan berhenti mengirim frame setelah orientasi
     * berubah. MediaProjection yang sama masih boleh membuat VirtualDisplay
     * baru, jadi izin pengguna tidak perlu diminta ulang.
     */
    private fun recoverCapture(reason: String) {
        val capture = screenCapture ?: return
        val surface = virtualDisplaySurface ?: return
        if (sourceWidth <= 0 || sourceHeight <= 0) return
        val nowMs = System.currentTimeMillis()
        // Jangan mencoba terlalu sering: satu percobaan per 10 detik.
        if (nowMs - lastRecoveryAtMs < 10_000L) return
        lastRecoveryAtMs = nowMs
        diag("kaptur: pulihkan display ($reason) ${sourceWidth}x$sourceHeight")
        runCatching { capture.stop() }
        val dpi = context.resources.displayMetrics.densityDpi
        runCatching { capture.start(sourceWidth, sourceHeight, dpi, surface) }
            .onFailure { diag("kaptur: gagal pulihkan (${it.message})") }
    }

    /**
     * Menyusun ulang daftar layer program dari snapshot yang sedang mengudara.
     * Dijalankan di thread GL setiap hasil Transisi berubah.
     */
    private fun rebuildLayers() {
        val collection = SceneManager.programCurrent()
        // Gagal menyiapkan konteks GL bukan berarti scene kosong; pertahankan
        // layer lama agar kamera/media tidak dilepas lalu membeku.
        val layers = buildLayers(collection) ?: return
        renderLayers = layers
        diag("scene: program=\"${collection.activeScene()?.name}\" layer=${layers.size}")
        pruneTextures()
    }

    /** Menyusun layer pratinjau dari hasil edit yang belum di-transisikan. */
    private fun rebuildPreviewLayers() {
        val startedNs = System.nanoTime()
        val layers = buildLayers(SceneManager.current()) ?: return
        previewLayers = layers
        pruneTextures()
        val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000
        // Hanya catat rebuild yang lambat agar log tidak membanjir saat geser.
        if (elapsedMs >= 30) diag("pratinjau: rebuild ${elapsedMs}ms layer=${previewLayers.size}")
    }

    private fun buildLayers(collection: SceneCollection): List<RenderLayer>? {
        val core = egl ?: return null
        val surface = encoderEglSurface

        try {
            if (surface != null) core.makeCurrent(surface) else core.makeCurrentNoSurface()
        } catch (error: IllegalStateException) {
            Log.w(TAG, "Scene: konteks EGL tidak aktif: ${error.message}")
            return null
        }

        val scene = collection.activeScene() ?: return emptyList()
        val layers = mutableListOf<RenderLayer>()
        val visitedScenes = mutableSetOf(scene.id)

        fun appendItems(items: List<com.rout.air.core.model.SceneItem>, parent: Transform?, depth: Int) {
        for (item in items.sortedBy { it.z }) {
            if (!item.visible) continue
            val source = collection.source(item.sourceId) ?: continue
            // Transform grup induk diterapkan sebagai skala+geser dari kiri-atas.
            val transform = parent?.let { p ->
                item.transform.copy(
                    x = p.x + item.transform.x * p.scaleX,
                    y = p.y + item.transform.y * p.scaleY,
                    scaleX = item.transform.scaleX * p.scaleX,
                    scaleY = item.transform.scaleY * p.scaleY,
                    rotation = item.transform.rotation + p.rotation,
                )
            } ?: item.transform

            when (source.type) {
                SourceType.Screen -> layers += RenderLayer(
                    type = SourceType.Screen,
                    textureId = textureId,
                    naturalWidth = 0,
                    naturalHeight = 0,
                    transform = transform,
                    crop = item.crop,
                    colorArgb = 0,
                    blend = item.blend,
                    opacity = item.opacity,
                    sourceId = null,
                    screenDisplay = source.settings.screenDisplay,
                    filters = com.rout.air.engine.filter.FilterState.from(source.filters),
                    boundsType = source.settings.boundsType,
                    boundsWidth = source.settings.boundsWidth,
                    boundsHeight = source.settings.boundsHeight,
                    boundsAlign = source.settings.boundsAlign,
                    cropToBounds = source.settings.cropToBounds,
                )

                SourceType.Image, SourceType.Text, SourceType.SlideShow -> {
                    val cached = ensureTexture(source) ?: continue
                    val fade = slideFadeState(source)
                    layers += RenderLayer(
                        type = source.type,
                        textureId = cached.textureId,
                        fadeTextureId = fade?.first ?: 0,
                        fadeOpacity = fade?.second ?: 1f,
                        naturalWidth = cached.width,
                        naturalHeight = cached.height,
                        transform = transform,
                        crop = item.crop,
                        colorArgb = 0,
                        blend = item.blend,
                        opacity = item.opacity,
                        sourceId = source.id,
                        filters = com.rout.air.engine.filter.FilterState.from(source.filters),
                        boundsType = source.settings.boundsType,
                        boundsWidth = source.settings.boundsWidth,
                        boundsHeight = source.settings.boundsHeight,
                        boundsAlign = source.settings.boundsAlign,
                        cropToBounds = source.settings.cropToBounds,
                    )
                }

                SourceType.Camera -> {
                    val camera = ensureCamera(source) ?: continue
                    SourceMetrics.report(source.id, camera.visualWidth, camera.visualHeight)
                    diag("kamera: layer src=${source.id} raw=${camera.width}x${camera.height} visual=${camera.visualWidth}x${camera.visualHeight} setelan=${source.settings.width}x${source.settings.height}")
                    layers += RenderLayer(
                        type = SourceType.Camera,
                        textureId = camera.textureId,
                        // Ukuran intrinsik = ukuran visual (setelah rotasi),
                        // seperti obs_source_get_width/height di OBS.
                        naturalWidth = camera.visualWidth.coerceAtLeast(1),
                        naturalHeight = camera.visualHeight.coerceAtLeast(1),
                        transform = transform,
                        crop = item.crop,
                        colorArgb = 0,
                        blend = item.blend,
                        opacity = item.opacity,
                        sourceId = source.id,
                        filters = com.rout.air.engine.filter.FilterState.from(source.filters),
                        boundsType = source.settings.boundsType,
                        boundsWidth = source.settings.boundsWidth,
                        boundsHeight = source.settings.boundsHeight,
                        boundsAlign = source.settings.boundsAlign,
                        cropToBounds = source.settings.cropToBounds,
                        camera = camera,
                    )
                }

                SourceType.Media -> {
                    val media = ensureMedia(source) ?: continue
                    SourceMetrics.report(source.id, media.width, media.height)
                    layers += RenderLayer(
                        type = SourceType.Media,
                        textureId = media.textureId,
                        naturalWidth = media.width,
                        naturalHeight = media.height,
                        transform = transform,
                        crop = item.crop,
                        colorArgb = 0,
                        blend = item.blend,
                        opacity = item.opacity,
                        sourceId = source.id,
                        filters = com.rout.air.engine.filter.FilterState.from(source.filters),
                        boundsType = source.settings.boundsType,
                        boundsWidth = source.settings.boundsWidth,
                        boundsHeight = source.settings.boundsHeight,
                        boundsAlign = source.settings.boundsAlign,
                        cropToBounds = source.settings.cropToBounds,
                        camera = media,
                    )
                }

                SourceType.Browser -> {
                    val browser = ensureBrowser(source) ?: continue
                    SourceMetrics.report(source.id, browser.width, browser.height)
                    layers += RenderLayer(
                        type = SourceType.Browser,
                        textureId = browser.textureId,
                        naturalWidth = browser.width,
                        naturalHeight = browser.height,
                        transform = transform,
                        crop = item.crop,
                        colorArgb = 0,
                        blend = item.blend,
                        opacity = item.opacity,
                        sourceId = source.id,
                        browser = browser,
                        boundsType = source.settings.boundsType,
                        boundsWidth = source.settings.boundsWidth,
                        boundsHeight = source.settings.boundsHeight,
                        boundsAlign = source.settings.boundsAlign,
                        cropToBounds = source.settings.cropToBounds,
                    )
                }

                SourceType.Color -> layers += RenderLayer(
                    type = SourceType.Color,
                    textureId = 0,
                    naturalWidth = source.settings.width.coerceAtLeast(1),
                    naturalHeight = source.settings.height.coerceAtLeast(1),
                    transform = transform,
                    crop = item.crop,
                    colorArgb = source.settings.color,
                    blend = item.blend,
                    opacity = item.opacity,
                    boundsType = source.settings.boundsType,
                    boundsWidth = source.settings.boundsWidth,
                    boundsHeight = source.settings.boundsHeight,
                    boundsAlign = source.settings.boundsAlign,
                    cropToBounds = source.settings.cropToBounds,
                )

                SourceType.Group -> {
                    val nestedId = source.settings.groupSceneId
                    if (depth < 4 && nestedId != null && visitedScenes.add(nestedId)) {
                        val nested = collection.scenes.firstOrNull { it.id == nestedId }
                        if (nested != null) appendItems(nested.items, transform, depth + 1)
                        visitedScenes.remove(nestedId)
                    }
                }

                SourceType.AudioInput, SourceType.AudioOutput -> Unit
            }
        }
        }
        appendItems(scene.items, null, 0)

        return layers
    }

    /** Halaman web yang digambar berkala menjadi tekstur 2D. */
    private fun ensureBrowser(source: SceneSource): BrowserTexture? {
        val url = source.settings.browserUrl ?: return null
        val zoom = source.settings.browserZoom.coerceIn(0.5f, 2f)
        val existing = browserSources[source.id]
        if (existing != null && existing.url == url && existing.zoom == zoom &&
            existing.desktop == source.settings.browserDesktop
        ) {
            existing.refreshSec = source.settings.browserRefreshSec.coerceIn(0.5f, 10f)
            if (existing.reloadToken != source.settings.browserReloadToken) {
                existing.reloadToken = source.settings.browserReloadToken
                val view = existing.webView
                if (view != null) mainHandler.post { runCatching { view.reload() } }
            }
            return existing
        }
        if (existing != null) releaseBrowser(source.id)

        val width = source.settings.width.takeIf { it > 0 } ?: config.width
        val height = source.settings.height.takeIf { it > 0 } ?: config.height
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE,
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE,
        )

        val entry = BrowserTexture(
            textureId = ids[0],
            width = width,
            height = height,
            url = url,
            zoom = zoom,
            desktop = source.settings.browserDesktop,
        ).apply {
            refreshSec = source.settings.browserRefreshSec.coerceIn(0.5f, 10f)
            reloadToken = source.settings.browserReloadToken
        }
        browserSources[source.id] = entry
        mainHandler.post { createBrowserView(entry) }
        diag("browser: $url ${width}x$height")
        return entry
    }

    private fun createBrowserView(entry: BrowserTexture) {
        if (entry.released) return
        runCatching {
            val web = WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = entry.zoom <= 1f
                if (entry.desktop) settings.userAgentString = DESKTOP_USER_AGENT
                setBackgroundColor(android.graphics.Color.BLACK)
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        // Zoom halaman lewat CSS; API setInitialScale sudah
                        // tidak tersedia di SDK terbaru.
                        if (entry.zoom != 1f) {
                            runCatching {
                                view.evaluateJavascript(
                                    "document.body.style.zoom='${entry.zoom}'",
                                    null,
                                )
                            }
                        }
                        renderBrowserView(entry, view)
                    }
                }
                layoutParams = android.view.ViewGroup.LayoutParams(entry.width, entry.height)
                measure(
                    View.MeasureSpec.makeMeasureSpec(entry.width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(entry.height, View.MeasureSpec.EXACTLY),
                )
                layout(0, 0, entry.width, entry.height)
                loadUrl(entry.url)
            }
            entry.webView = web
        }.onFailure { diag("browser: gagal membuat WebView (${it.message})") }
    }

    /** Dipanggil di thread utama; hasilnya diunggah saat frame berikutnya. */
    private fun renderBrowserView(entry: BrowserTexture, web: WebView? = entry.webView) {
        val view = web ?: return
        if (entry.released) return
        runCatching {
            if (view.width <= 0 || view.height <= 0) return@runCatching
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            entry.bitmap = bitmap
            entry.dirty = true
        }
    }

    private fun drawBrowser(
        painter: GlCompositor,
        browser: BrowserTexture,
        layer: RenderLayer,
        left: Float,
        bottom: Float,
        width: Float,
        height: Float,
    ) {
        val now = System.nanoTime()
        val intervalNs = (browser.refreshSec * 1_000_000_000L).toLong()
            .coerceAtLeast(100_000_000L)
        if (now - browser.lastRefreshNs > intervalNs) {
            browser.lastRefreshNs = now
            val view = browser.webView
            if (view != null) mainHandler.post { renderBrowserView(browser, view) }
        }
        val bitmap = browser.bitmap
        if (bitmap != null && browser.dirty) {
            runCatching {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, browser.textureId)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            }
            browser.dirty = false
        }
        val crop = layer.crop
        painter.drawTexture2D(
            textureId = browser.textureId,
            left = left,
            bottom = bottom,
            width = width,
            height = height,
            rotation = layer.transform.rotation,
            cropLeft = crop.left,
            cropTop = crop.top,
            cropRight = crop.right,
            cropBottom = crop.bottom,
            flipH = layer.transform.flipH,
            flipV = layer.transform.flipV,
            blend = layer.blend,
            opacity = layer.opacity,
            filters = layer.filters,
        )
    }

    private fun releaseBrowser(sourceId: String) {
        val entry = browserSources.remove(sourceId) ?: return
        entry.released = true
        entry.webView?.let { web -> mainHandler.post { runCatching { web.destroy() } } }
        entry.webView = null
        entry.bitmap = null
        runCatching { GLES20.glDeleteTextures(1, intArrayOf(entry.textureId), 0) }
    }

    private class BrowserTexture(
        val textureId: Int,
        val width: Int,
        val height: Int,
        val url: String,
        val zoom: Float = 1f,
        val desktop: Boolean = false,
    ) {
        @Volatile
        var refreshSec: Float = 1f
        @Volatile
        var reloadToken: Int = 0
        @Volatile
        var bitmap: Bitmap? = null
        @Volatile
        var dirty = false
        @Volatile
        var released = false
        @Volatile
        var lastRefreshNs = 0L
        var webView: WebView? = null
    }

    /** Kamera untuk sumber ini; dibuat sekali lalu dipakai ulang. */
    private fun ensureCamera(source: SceneSource): CameraTexture? {
        val existing = cameraSources[source.id]
        if (existing != null &&
            existing.facing == source.settings.cameraFacing &&
            existing.targetHeight == source.settings.cameraHeight &&
            existing.cameraId == source.settings.cameraId
        ) {
            updateCameraControls(existing, source.settings)
            return existing
        }
        if (existing != null) releaseCamera(source.id)

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            diag("kamera: izin belum diberikan")
            return null
        }
        val manager = context.getSystemService(CameraManager::class.java) ?: return null
        val id = CameraSizing.cameraIdFor(
            manager,
            source.settings.cameraFacing,
            source.settings.cameraId,
        ) ?: return null
        val characteristics = runCatching { manager.getCameraCharacteristics(id) }.getOrNull()
            ?: return null
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return null
        val sizes = map.getOutputSizes(SurfaceTexture::class.java).orEmpty()
        if (sizes.isEmpty()) return null

        // Ukuran intrinsik sumber mengikuti OBS: tekstur diputar oleh
        // getTransformMatrix, jadi dasar gambar adalah ukuran setelah rotasi.
        val rotated = CameraSizing.isRotated(characteristics, CameraSizing.displayRotation(context))
        // Pilih resolusi kamera yang paling dekat dengan orientasi kanvas,
        // bukan kotak sumber: kotak sumber justru mengikuti hasilnya.
        val canvasW = config.width.coerceAtLeast(1)
        val canvasH = config.height.coerceAtLeast(1)
        val targetAspect = if (rotated) {
            canvasH.toFloat() / canvasW
        } else {
            canvasW.toFloat() / canvasH
        }
        val chosen = CameraSizing.pickTextureSize(
            sizes,
            targetAspect,
            source.settings.cameraHeight,
        ) ?: return null
        val visualWidth = if (rotated) chosen.height else chosen.width
        val visualHeight = if (rotated) chosen.width else chosen.height

        // Ukuran sumber berubah (mis. 1280x720 -> 1080x1920). Seperti OBS,
        // skala item dibiarkan; kotak sumber mengikuti ukuran intrinsik baru.
        if (source.settings.width != visualWidth ||
            source.settings.height != visualHeight
        ) {
            diag(
                "kamera: intrinsik ${source.settings.width}x${source.settings.height} -> " +
                    "$visualWidth x $visualHeight (rotated=$rotated)",
            )
            val collection = SceneManager.current()
            val item = collection.scenes.flatMap { it.items }
                .firstOrNull { it.sourceId == source.id }
            if (item != null) {
                val newSource = source.copy(
                    settings = source.settings.copy(
                        width = visualWidth,
                        height = visualHeight,
                    ),
                )
                SceneManager.update(collection.updateSource(newSource))
            }
        }
        SourceMetrics.report(source.id, visualWidth, visualHeight)

        val texture = GlUtil.createExternalTexture()
        val surfaceTexture = SurfaceTexture(texture).apply {
            setDefaultBufferSize(chosen.width, chosen.height)
        }
        val surface = Surface(surfaceTexture)
        val entry = CameraTexture(
            textureId = texture,
            surfaceTexture = surfaceTexture,
            surface = surface,
            width = chosen.width,
            height = chosen.height,
            visualWidth = visualWidth,
            visualHeight = visualHeight,
            facing = source.settings.cameraFacing,
            targetHeight = source.settings.cameraHeight,
            cameraId = source.settings.cameraId,
        )
        surfaceTexture.setOnFrameAvailableListener({
            // Perbarui tekstur di thread callback supaya gambar tidak berhenti
            // walau thread pratinjau sibuk; drawCamera hanya memakai hasilnya.
            val ok = runCatching { surfaceTexture.updateTexImage() }.isSuccess
            if (ok) {
                entry.frames++
                runCatching { surfaceTexture.getTransformMatrix(entry.texMatrix) }
            } else {
                entry.updateFailures++
            }
            entry.frameAvailable = true
        }, cameraHandler)
        cameraSources[source.id] = entry

        runCatching {
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    entry.device = device
                    entry.sensorRect = characteristics.get(
                        CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE,
                    )
                    entry.maxZoom = characteristics.get(
                        CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM,
                    ) ?: 1f
                    device.createCaptureSession(
                        listOf(surface),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                entry.session = session
                                entry.zoom = source.settings.cameraZoom
                                entry.torch = source.settings.cameraTorch
                                refreshCameraRequest(entry)
                            }

                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                diag("kamera: sesi gagal dikonfigurasi")
                            }
                        },
                        cameraHandler,
                    )
                }

                override fun onDisconnected(device: CameraDevice) {
                    diag("kamera: terputus")
                    entry.device = null
                    device.close()
                }

                override fun onError(device: CameraDevice, error: Int) {
                    val name = when (error) {
                        1 -> "IN_USE"
                        2 -> "MAX_CAMERAS_IN_USE"
                        3 -> "DISABLED"
                        4 -> "DEVICE"
                        5 -> "SERVICE"
                        else -> "UNKNOWN"
                    }
                    diag("kamera: error $error ($name)")
                    entry.device = null
                    device.close()
                }
            }, cameraHandler)
        }.onFailure { diag("kamera: gagal dibuka (${it.message})") }

        diag("kamera: ${chosen.width}x${chosen.height} id=$id")
        return entry
    }

    /** Menerapkan zoom/senter tanpa membuat ulang sesi kamera. */
    private fun updateCameraControls(entry: CameraTexture, settings: SourceSettings) {
        val zoom = settings.cameraZoom.coerceIn(1f, entry.maxZoom.coerceAtLeast(1f))
        if (zoom == entry.zoom && settings.cameraTorch == entry.torch) return
        entry.zoom = zoom
        entry.torch = settings.cameraTorch
        refreshCameraRequest(entry)
    }

    /** Menyusun ulang repeating request sesuai zoom dan senter saat ini. */
    private fun refreshCameraRequest(entry: CameraTexture) {
        cameraHandler.post {
            val device = entry.device ?: return@post
            val session = entry.session ?: return@post
            runCatching {
                val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(entry.surface)
                    set(
                        CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                    )
                    val rect = entry.sensorRect
                    if (rect != null && entry.zoom > 1.001f) {
                        val cropWidth = (rect.width() / entry.zoom).toInt().coerceAtLeast(2)
                        val cropHeight = (rect.height() / entry.zoom).toInt().coerceAtLeast(2)
                        val cx = rect.centerX()
                        val cy = rect.centerY()
                        set(
                            CaptureRequest.SCALER_CROP_REGION,
                            Rect(
                                cx - cropWidth / 2,
                                cy - cropHeight / 2,
                                cx + cropWidth / 2,
                                cy + cropHeight / 2,
                            ),
                        )
                    }
                    if (entry.torch) {
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                    }
                }
                session.setRepeatingRequest(request.build(), null, cameraHandler)
            }.onFailure { diag("kamera: kontrol gagal (${it.message})") }
        }
    }

    private fun drawCamera(
        painter: GlCompositor,
        camera: CameraTexture,
        layer: RenderLayer,
        left: Float,
        bottom: Float,
        width: Float,
        height: Float,
    ) {
        synchronized(camera.lock) {
            // Tekstur diperbarui di callback; di sini cukup gambar dengan
            // matrix terbaru supaya tidak ada balapan antar thread.
            if (camera.frameAvailable) {
                camera.frameAvailable = false
            }
            val crop = layer.crop
            painter.drawExternal(
                textureId = camera.textureId,
                texMatrix = camera.texMatrix,
                left = left,
                bottom = bottom,
                width = width,
                height = height,
                rotation = layer.transform.rotation,
                cropLeft = crop.left,
                cropTop = crop.top,
                cropRight = crop.right,
                cropBottom = crop.bottom,
                flipH = layer.transform.flipH,
                flipV = layer.transform.flipV,
                blend = layer.blend,
                opacity = layer.opacity,
                filters = layer.filters,
            )
        }
    }

    private fun releaseCamera(sourceId: String) {
        val entry = cameraSources.remove(sourceId) ?: return
        runCatching { entry.session?.close() }
        entry.session = null
        runCatching { entry.device?.close() }
        entry.device = null
        runCatching { entry.surface.release() }
        runCatching { entry.surfaceTexture.release() }
        runCatching { GLES20.glDeleteTextures(1, intArrayOf(entry.textureId), 0) }
        diag("kamera: dilepas $sourceId")
    }

    private fun releaseAllCameras() {
        cameraSources.keys.toList().forEach { releaseCamera(it) }
    }

    /**
     * Sumber kamera aktif. Objek ini hidup selama sumbernya dipakai dan
     * menyimpan texture OES, SurfaceTexture, serta sesi Camera2.
     */
    private class CameraTexture(
        val textureId: Int,
        val surfaceTexture: SurfaceTexture,
        val surface: Surface,
        /** Ukuran tekstur mentah dari kamera (bisa landscape). */
        val width: Int,
        val height: Int,
        /** Ukuran intrinsik setelah rotasi; ini yang dipakai menggambar. */
        val visualWidth: Int = width,
        val visualHeight: Int = height,
        val facing: Int,
        val mediaPath: String? = null,
        /** Tinggi kamera yang diminta; berubah berarti sesi dibuat ulang. */
        val targetHeight: Int = 0,
        /** Lensa spesifik; berubah berarti sesi dibuat ulang. */
        val cameraId: String? = null,
    ) {
        val lock = Any()
        val texMatrix = FloatArray(16)
        @Volatile
        var frameAvailable = false
        @Volatile
        var frames = 0L
        @Volatile
        var updateFailures = 0L
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var player: MediaPlayer? = null
        // Kontrol kamera
        var sensorRect: Rect? = null
        var maxZoom: Float = 1f
        var zoom: Float = 1f
        var torch: Boolean = false
        // Kontrol media
        var prepared = false
        var paused = false
        var speed = 1f
        var looping = true
        var restartToken = 0
        var seekToken = 0
        var latestSettings: SourceSettings? = null
    }

    /** Video lokal sebagai sumber; diputar berulang tanpa audio. */
    private fun ensureMedia(source: SceneSource): CameraTexture? {
        val path = source.settings.mediaPath ?: return null
        val existing = mediaSources[source.id]
        if (existing != null && existing.mediaPath == path) {
            existing.latestSettings = source.settings
            applyMediaControls(existing, source.settings)
            return existing
        }
        if (existing != null) releaseMedia(source.id)
        if (!File(path).exists()) {
            diag("media: berkas tidak ditemukan ($path)")
            return null
        }
        val width = source.settings.width.coerceAtLeast(2)
        val height = source.settings.height.coerceAtLeast(2)
        val texture = GlUtil.createExternalTexture()
        val surfaceTexture = SurfaceTexture(texture).apply { setDefaultBufferSize(width, height) }
        val surface = Surface(surfaceTexture)
        val entry = CameraTexture(
            textureId = texture,
            surfaceTexture = surfaceTexture,
            surface = surface,
            width = width,
            height = height,
            facing = 0,
            mediaPath = path,
        )
        entry.latestSettings = source.settings
        surfaceTexture.setOnFrameAvailableListener({ entry.frameAvailable = true }, cameraHandler)
        val player = MediaPlayer()
        runCatching {
            player.setDataSource(path)
            player.setSurface(surface)
            player.isLooping = true
            // Audio media tidak masuk mixer rekaman; bisukan agar tidak
            // terdengar ganda saat pratinjau.
            player.setVolume(0f, 0f)
            player.setOnPreparedListener { ready ->
                entry.prepared = true
                runCatching { ready.start() }
                entry.latestSettings?.let { applyMediaControls(entry, it) }
            }
            player.setOnErrorListener { _, what, extra ->
                diag("media: error $what/$extra")
                true
            }
            player.prepareAsync()
        }.onFailure { diag("media: gagal disiapkan (${it.message})") }
        entry.player = player
        mediaSources[source.id] = entry
        diag("media: $path ${width}x$height")
        return entry
    }

    /** Menerapkan jeda, kecepatan, ulangan, dan lompatan ke MediaPlayer. */
    private fun applyMediaControls(entry: CameraTexture, settings: SourceSettings) {
        val player = entry.player ?: return
        if (!entry.prepared) return
        val speed = settings.mediaSpeed.coerceIn(0.25f, 4f)
        if (speed != entry.speed) {
            entry.speed = speed
            runCatching {
                player.playbackParams = player.playbackParams.setSpeed(speed)
            }.onFailure { diag("media: kecepatan gagal (${it.message})") }
        }
        if (settings.mediaLoop != entry.looping) {
            entry.looping = settings.mediaLoop
            player.isLooping = settings.mediaLoop
        }
        if (settings.mediaRestartToken != entry.restartToken) {
            entry.restartToken = settings.mediaRestartToken
            runCatching { player.seekTo(0) }
        }
        if (settings.mediaSeekToken != entry.seekToken) {
            entry.seekToken = settings.mediaSeekToken
            val duration = runCatching { player.duration }.getOrDefault(0)
            if (duration > 0) {
                val target = (duration * settings.mediaSeekPercent.coerceIn(0f, 100f) / 100f)
                runCatching { player.seekTo(target.toInt()) }
            }
        }
        if (settings.mediaPaused != entry.paused) {
            entry.paused = settings.mediaPaused
            if (settings.mediaPaused) {
                runCatching { player.pause() }
            } else {
                runCatching { player.start() }
            }
        }
    }

    private fun releaseMedia(sourceId: String) {
        val entry = mediaSources.remove(sourceId) ?: return
        runCatching { entry.player?.stop() }
        runCatching { entry.player?.release() }
        entry.player = null
        runCatching { entry.surface.release() }
        runCatching { entry.surfaceTexture.release() }
        runCatching { GLES20.glDeleteTextures(1, intArrayOf(entry.textureId), 0) }
    }

    /** Melepas tekstur yang tidak dipakai program maupun pratinjau. */
    private fun pruneTextures() {
        val keep = (renderLayers + previewLayers)
            .mapNotNull { it.sourceId }
            .toSet()
        val unused = textureCache.keys.filter { it !in keep }
        unused.forEach { sourceId ->
            textureCache.remove(sourceId)?.let { retiredTextures += it.textureId }
        }
        // Kamera yang tidak lagi dipakai program/pratinjau ikut dilepas.
        cameraSources.keys.filter { it !in keep }.forEach { releaseCamera(it) }
        mediaSources.keys.filter { it !in keep }.forEach { releaseMedia(it) }
        browserSources.keys.filter { it !in keep }.forEach { releaseBrowser(it) }
        slidePreviousTextures.keys.filter { it !in keep }.forEach { sourceId ->
            slidePreviousTextures.remove(sourceId)?.let { retiredTextures += it }
            slideFadeStartNs.remove(sourceId)
        }
        flushRetiredTextures()
    }

    /** Menghapus tekstur lama setelah frame pratinjau yang memakainya selesai. */
    private fun flushRetiredTextures() {
        if (retiredTextures.isEmpty() || teardownStarted) return
        if (previewInFlight.get()) {
            glHandler.postDelayed({ flushRetiredTextures() }, 50)
            return
        }
        val ids = retiredTextures.toIntArray()
        retiredTextures.clear()
        runCatching { GLES20.glDeleteTextures(ids.size, ids, 0) }
    }

    /** Memuat tekstur sumber gambar/teks; dipakai ulang selama isinya sama. */
    private fun ensureTexture(source: SceneSource): CachedTexture? {
        // Slide show berganti berkas mengikuti waktu, jadi indeks ikut tanda
        // tangan supaya tekstur dimuat ulang tepat saat gambar berganti.
        val slideIndex = if (source.type == SourceType.SlideShow) {
            val paths = source.settings.imagePaths
            if (paths.isEmpty()) {
                -1
            } else {
                val interval = source.settings.slideIntervalSec.coerceAtLeast(1).toLong()
                val raw = ((System.nanoTime() / 1_000_000_000L) / interval % paths.size).toInt()
                if (source.settings.slideShuffle) {
                    // Urutan acak tetap sama tiap putaran karena benihnya id sumber.
                    paths.indices
                        .shuffled(kotlin.random.Random(source.id.hashCode()))
                        .getOrElse(raw) { raw }
                } else {
                    raw
                }
            }
        } else {
            0
        }
        val signature = sourceSignature(source) + "|slide=$slideIndex"
        textureCache[source.id]?.let { if (it.signature == signature) return it }

        val bitmap = when (source.type) {
            SourceType.Image -> SourceBitmap.loadImage(
                path = source.settings.imagePath,
                maxWidth = config.width,
                maxHeight = config.height,
            )

            SourceType.SlideShow -> {
                val paths = source.settings.imagePaths
                if (slideIndex in paths.indices) {
                    SourceBitmap.loadImage(
                        path = paths[slideIndex],
                        maxWidth = config.width,
                        maxHeight = config.height,
                    )
                } else {
                    null
                }
            }

            SourceType.Text -> SourceBitmap.renderText(
                text = source.settings.text,
                textSizeSp = source.settings.textSize,
                colorArgb = source.settings.textColor,
                maxWidth = source.settings.width.takeIf { it > 0 } ?: config.width,
                density = context.resources.displayMetrics.density,
                highlightIndices = source.settings.textHighlightIndices,
                highlightColor = source.settings.textHighlightColor,
                center = source.settings.textCenter,
                autoFit = source.settings.textAutoFit,
            )

            else -> null
        }

        if (bitmap == null) {
            diag("scene: sumber ${source.name} tidak bisa dimuat")
            return null
        }

        val texture = SourceBitmap.createTexture(bitmap)
        val cached = CachedTexture(signature, texture, bitmap.width, bitmap.height)
        bitmap.recycle()

        textureCache[source.id]?.let { old ->
            if (source.type == SourceType.SlideShow &&
                source.settings.slideFade &&
                old.signature != signature
            ) {
                // Tahan tekstur lama sampai fade selesai, jangan langsung buang.
                slidePreviousTextures.put(source.id, old.textureId)?.let {
                    retiredTextures += it
                }
                slideFadeStartNs[source.id] = System.nanoTime()
            } else {
                retiredTextures += old.textureId
            }
        }
        textureCache[source.id] = cached
        return cached
    }

    /** Tekstur slide lama + progres fade, selama animasinya masih berjalan. */
    private fun slideFadeState(source: SceneSource): Pair<Int, Float>? {
        if (source.type != SourceType.SlideShow) return null
        if (!source.settings.slideFade) {
            // Fade dimatikan: buang tekstur lama yang mungkin masih tertahan.
            slidePreviousTextures.remove(source.id)?.let { retiredTextures += it }
            slideFadeStartNs.remove(source.id)
            return null
        }
        val previous = slidePreviousTextures[source.id] ?: return null
        val start = slideFadeStartNs[source.id] ?: return null
        val progress = ((System.nanoTime() - start).toFloat() / SLIDE_FADE_NS)
            .coerceIn(0f, 1f)
        if (progress >= 1f) {
            slidePreviousTextures.remove(source.id)
            slideFadeStartNs.remove(source.id)
            retiredTextures += previous
            return null
        }
        return previous to progress
    }

    private fun sourceSignature(source: SceneSource): String = buildString {
        append(source.type.name)
        append('|').append(source.settings.imagePath)
        append('|').append(source.settings.text)
        append('|').append(source.settings.textSize)
        append('|').append(source.settings.textColor)
        append('|').append(source.settings.width)
        append('|').append(source.settings.height)
        append('|').append(source.settings.cameraFacing)
        append('|').append(source.settings.imagePaths.joinToString(","))
        append('|').append(source.settings.slideIntervalSec)
        append('|').append(source.settings.slideShuffle)
    }

    /** Menggambar semua layer kanvas pada konteks yang sedang aktif. */
    private fun drawLayers(
        painter: GlCompositor,
        screenMatrix: FloatArray,
        layers: List<RenderLayer>,
        canvasWidth: Int,
        canvasHeight: Int,
    ) {
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        layers.forEach { layer ->
            drawLayer(painter, screenMatrix, layer, canvasWidth, canvasHeight)
        }
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun drawLayer(
        painter: GlCompositor,
        screenMatrix: FloatArray,
        layer: RenderLayer,
        canvasWidth: Int,
        canvasHeight: Int,
    ) {
        val naturalWidth = if (layer.type == SourceType.Screen) {
            canvasWidth.toFloat()
        } else {
            layer.naturalWidth.toFloat()
        }
        val naturalHeight = if (layer.type == SourceType.Screen) {
            canvasHeight.toFloat()
        } else {
            layer.naturalHeight.toFloat()
        }

        val crop = layer.crop
        val cLeft = crop.left.coerceIn(0f, 0.49f)
        val cTop = crop.top.coerceIn(0f, 0.49f)
        val cRight = crop.right.coerceIn(0f, 0.49f)
        val cBottom = crop.bottom.coerceIn(0f, 0.49f)

        // Crop memendekkan tepi kotak (seperti OBS), bukan meregangkan isi.
        var width = max(
            1f,
            naturalWidth * (1f - cLeft - cRight) * layer.transform.scaleX,
        )
        var height = max(
            1f,
            naturalHeight * (1f - cTop - cBottom) * layer.transform.scaleY,
        )

        // Bounds fitting ala OBS (calculate_bounds_data): memuat sumber ke dalam kotak bounds tanpa merusak rasio, kecuali mode Stretch.
        var boundsOffsetX = 0f
        var boundsOffsetY = 0f
        if (layer.boundsType != com.rout.air.core.model.BoundsType.None &&
            layer.boundsWidth > 0 && layer.boundsHeight > 0
        ) {
            val fit = computeBoundsFit(
                layer.naturalWidth,
                layer.naturalHeight,
                layer.transform.scaleX,
                layer.transform.scaleY,
                layer.boundsWidth,
                layer.boundsHeight,
                layer.boundsType,
                layer.boundsAlign,
            )
            width = fit[0]
            height = fit[1]
            boundsOffsetX = fit[2]
            boundsOffsetY = fit[3]
        }

        // Sumber Layar dengan template: kotak dihitung ulang dari kanvas dan
        // ukuran tangkapan, tidak memakai skala tersimpan. Dengan begitu
        // nilai lama (dari orientasi sebelumnya) tidak membuat gambar keluar
        // area kanvas.
        val template = layer.screenDisplay
        var templateRotate = false
        if (layer.type == SourceType.Screen && template != null &&
            template != com.rout.air.core.model.ScreenDisplay.Auto
        ) {
            val base = canvasWidth.toFloat() / canvasHeight.toFloat()
            val aspect = when (template) {
                com.rout.air.core.model.ScreenDisplay.Landscape ->
                    if (base >= 1f) base else 1f / base

                com.rout.air.core.model.ScreenDisplay.Portrait ->
                    if (base <= 1f) base else 1f / base

                com.rout.air.core.model.ScreenDisplay.Auto -> base
            }
            width = minOf(canvasWidth.toFloat(), canvasHeight * aspect)
            height = width / aspect
            templateRotate = com.rout.air.core.model.screenNeedsRotation(
                template,
                sourceWidth.toInt(),
                sourceHeight.toInt(),
            )
        }

        val anchorX = when (layer.transform.align) {
            Align.Top, Align.Center, Align.Bottom -> width / 2f
            Align.TopRight, Align.Right, Align.BottomRight -> width
            else -> 0f
        }
        val anchorY = when (layer.transform.align) {
            Align.Left, Align.Center, Align.Right -> height / 2f
            Align.BottomLeft, Align.Bottom, Align.BottomRight -> height
            else -> 0f
        }

        var left = layer.transform.x - anchorX + boundsOffsetX
        var top = layer.transform.y - anchorY + boundsOffsetY

        // Template layar mengikuti posisi yang diatur pengguna, tetapi
        // dijepit agar tidak keluar kanvas setelah orientasi berganti.
        if (layer.type == SourceType.Screen && template != null &&
            template != com.rout.air.core.model.ScreenDisplay.Auto
        ) {
            left = left.coerceIn(0f, (canvasWidth - width).coerceAtLeast(0f))
            top = top.coerceIn(0f, (canvasHeight - height).coerceAtLeast(0f))
        }
        var drawWidth = width
        var drawHeight = height

        // Sumber layar: template Vertikal/Horizontal memaksa arah tampil.
        // Konten yang diputar 90 derajat dimuat penuh ke kotak (fit, tanpa
        // crop), lalu dipusatkan pada area hasil fit.
        var extraRotation = 0f
        if (layer.type == SourceType.Screen) {
            val rotate = templateRotate
            if (rotate) extraRotation = 90f
            // Layar selalu memenuhi kotaknya. Kalau rasio kanvas sama dengan
            // layar, Stretch identik dengan Fit; kalau berbeda, tetap penuh.
            val mode = ContentMode.Stretch
            if (mode != ContentMode.Stretch) {
                val fitWidth = if (rotate) sourceHeight else sourceWidth
                val fitHeight = if (rotate) sourceWidth else sourceHeight
                val rect = computeContentRect(
                    width.roundToInt(),
                    height.roundToInt(),
                    fitWidth,
                    fitHeight,
                    mode,
                )
                left += rect[0]
                top += rect[1]
                drawWidth = rect[2].toFloat()
                drawHeight = rect[3].toFloat()
            }
        }

        if (layer.type == SourceType.Screen &&
            System.nanoTime() - lastScreenRectLogNs > 1_000_000_000L
        ) {
            lastScreenRectLogNs = System.nanoTime()
            diag(
                "layar: canvas=${config.width}x${config.height} " +
                    "src=${sourceWidth}x${sourceHeight} " +
                    "box=${width.roundToInt()}x${height.roundToInt()} " +
                    "draw=${drawWidth.roundToInt()}x${drawHeight.roundToInt()} " +
                    "left=${left.roundToInt()} top=${top.roundToInt()}",
            )
        }

        val bottom = canvasHeight - top - drawHeight

        when (layer.type) {
            SourceType.Screen -> painter.drawExternal(
                textureId = layer.textureId,
                texMatrix = screenMatrix,
                left = left,
                bottom = bottom,
                width = drawWidth,
                height = drawHeight,
                rotation = layer.transform.rotation,
                cropLeft = cLeft,
                cropTop = cTop,
                cropRight = cRight,
                cropBottom = cBottom,
                flipH = layer.transform.flipH,
                flipV = layer.transform.flipV,
                blend = layer.blend,
                opacity = layer.opacity,
                texRotate = extraRotation,
                filters = layer.filters,
            )

            SourceType.Image, SourceType.Text -> painter.drawTexture2D(
                textureId = layer.textureId,
                left = left,
                bottom = bottom,
                width = drawWidth,
                height = drawHeight,
                rotation = layer.transform.rotation,
                cropLeft = cLeft,
                cropTop = cTop,
                cropRight = cRight,
                cropBottom = cBottom,
                flipH = layer.transform.flipH,
                flipV = layer.transform.flipV,
                blend = layer.blend,
                opacity = layer.opacity,
                filters = layer.filters,
            )

            SourceType.SlideShow -> {
                fun drawSlide(textureId: Int, alpha: Float) = painter.drawTexture2D(
                    textureId = textureId,
                    left = left,
                    bottom = bottom,
                    width = drawWidth,
                    height = drawHeight,
                    rotation = layer.transform.rotation,
                    cropLeft = cLeft,
                    cropTop = cTop,
                    cropRight = cRight,
                    cropBottom = cBottom,
                    flipH = layer.transform.flipH,
                    flipV = layer.transform.flipV,
                    blend = layer.blend,
                    opacity = alpha,
                    filters = layer.filters,
                )
                // Crossfade: gambar lama penuh, gambar baru ditimpa dengan
                // alfa sesuai progres. Tanpa fade, cukup satu gambar.
                if (layer.fadeTextureId != 0) {
                    drawSlide(layer.fadeTextureId, layer.opacity)
                    drawSlide(layer.textureId, layer.opacity * layer.fadeOpacity)
                } else {
                    drawSlide(layer.textureId, layer.opacity)
                }
            }

            SourceType.Camera, SourceType.Media -> {
                val external = layer.camera ?: return
                drawCamera(painter, external, layer, left, bottom, drawWidth, drawHeight)
            }

            SourceType.Browser -> {
                val browser = layer.browser ?: return
                drawBrowser(painter, browser, layer, left, bottom, drawWidth, drawHeight)
            }

            // Grup sudah diratakan menjadi layer biasa saat build.
            SourceType.Group -> Unit

            SourceType.Color -> painter.drawColor(
                red = ((layer.colorArgb ushr 16) and 0xFF) / 255f,
                green = ((layer.colorArgb ushr 8) and 0xFF) / 255f,
                blue = (layer.colorArgb and 0xFF) / 255f,
                alpha = ((layer.colorArgb ushr 24) and 0xFF) / 255f * layer.opacity,
                left = left,
                bottom = bottom,
                width = drawWidth,
                height = drawHeight,
                rotation = layer.transform.rotation,
                blend = layer.blend,
            )

            SourceType.AudioInput, SourceType.AudioOutput -> Unit
        }
    }

    private fun onFrameAvailable() {
        if (!running) return
        val core = egl ?: return
        val frameTexture = surfaceTexture ?: return
        val encoderSurface = encoderEglSurface
        val recording = recordingActive || liveActive

        // updateTexImage butuh konteks EGL aktif pada thread ini. Saat hanya
        // pratinjau (belum ada encoder), konteks diaktifkan tanpa surface.
        try {
            if (recording && !recordingClock.isPaused && encoderSurface != null) {
                core.makeCurrent(encoderSurface)
            } else {
                core.makeCurrentNoSurface()
            }
        } catch (error: IllegalStateException) {
            Log.w(TAG, "Konteks EGL tidak aktif: ${error.message}")
            return
        }

        val nowNs = System.nanoTime()
        // Wajib dipanggil untuk SETIAP callback: ini melepas buffer
        // SurfaceTexture. Tanpa ini producer berhenti setelah 1-2 frame
        // sehingga rekaman/pratinjau membeku (frames=1).
        try {
            frameTexture.updateTexImage()
            frameTexture.getTransformMatrix(texMatrix)
        } catch (error: RuntimeException) {
            Log.w(TAG, "Frame layar tidak bisa dibaca: ${error.message}")
            return
        }
        lastFrameWallNs = nowNs
        lastCaptureChangeNs = nowNs
        if (capturedFrames % 300 == 0) {
            diag("kaptur: frame $capturedFrames src=${sourceWidth}x$sourceHeight")
        }
        if (capturedFrames == 0) {
            diag(
                "frame pertama: src=${sourceWidth}x$sourceHeight " +
                    "layer=${renderLayers.size} preview=$previewVisible",
            )
        }
        capturedFrames++
        hasCapturedFrame = true

        // Encoder dan pratinjau digerakkan pacer; di sini hanya tekstur
        // diperbarui agar tidak bergantung pada frekuensi callback layar.
    }

    /**
     * Waktu presentasi diambil dari jam monotonic, bukan timestamp SurfaceTexture.
     * Timestamp sumber bisa reset/mundur (mis. saat display berganti mode),
     * dan itu membuat frame berhenti dikirim karena dianggap terlalu awal.
     */
    private fun currentPresentationNs(): Long {
        if (recordingClock.isStarted) return recordingClock.elapsedNs(System.nanoTime())
        return (System.nanoTime() - startTimeNs).coerceAtLeast(0L)
    }

    private fun startPreviewThread(core: EglCore) {
        val thread = HandlerThread("rout-preview").apply { start() }
        previewThread = thread
        val handler = Handler(thread.looper)
        previewHandler = handler
        handler.post {
            try {
                // Konteks kedua berbagi tekstur dengan konteks encoder.
                previewEgl = EglCore(shareWith = core)
                diag("preview: thread siap")
            } catch (error: Throwable) {
                Log.w(TAG, "Preview tidak bisa disiapkan: ${error.message}")
                diag("preview: gagal disiapkan (${error.message})")
            }
        }
    }

    private fun requestPreview(
        matrix: FloatArray,
        sourceW: Int,
        sourceH: Int,
        layers: List<RenderLayer>,
    ) {
        val handler = previewHandler ?: return
        if (!previewInFlight.compareAndSet(false, true)) return

        val frame = PreviewFrame(matrix, sourceW, sourceH, layers, previewLayers)
        val posted = handler.post {
            try {
                renderPreview(frame)
            } catch (error: Throwable) {
                Log.w(TAG, "Preview gagal digambar: ${error.message}")
            } finally {
                lastPreviewWallNs = System.nanoTime()
                previewInFlight.set(false)
            }
        }
        if (!posted) previewInFlight.set(false)
    }

    /** Dijalankan di thread preview. */
    private fun renderPreview(frame: PreviewFrame) {
        val core = previewEgl ?: return
        val started = System.nanoTime()

        previewEglSurface = ensurePreviewSurface(core, previewEglSurface, previewTarget)
        programEglSurface = ensurePreviewSurface(core, programEglSurface, programTarget)
        overlayProgramEglSurface = ensurePreviewSurface(
            core,
            overlayProgramEglSurface,
            overlayProgramTarget,
        )

        // Kompositor preview dibuat setelah ada surface yang current; shader
        // tidak bisa dikompilasi tanpa konteks EGL aktif.
        val painter = ensurePainter(core, previewEglSurface ?: programEglSurface) ?: return

        if (!previewLogged) {
            previewLogged = true
            diag(
                "render pratinjau pertama: preview=${previewEglSurface != null} " +
                    "program=${programEglSurface != null} " +
                    "pw=$previewWidth ph=$previewHeight " +
                    "layerProgram=${frame.layers.size} layerPreview=${frame.previewLayers.size}",
            )
        }

        drawPane(core, painter, previewEglSurface, previewWidth, previewHeight, frame, frame.previewLayers)
        drawPane(core, painter, programEglSurface, programWidth, programHeight, frame, frame.layers)
        drawPane(
            core,
            painter,
            overlayProgramEglSurface,
            overlayProgramWidth,
            overlayProgramHeight,
            frame,
            frame.layers,
        )

        val costMs = (System.nanoTime() - started) / 1_000_000
        if (costMs > 100) diag("preview lambat: ${costMs} ms")
        previewSwaps++
    }

    private fun ensurePainter(core: EglCore, surface: EGLSurface?): GlCompositor? {
        previewPainter?.let { return it }
        if (surface == null) return null

        return try {
            core.makeCurrent(surface)
            GlCompositor().apply {
                init()
                setCanvasSize(config.width, config.height)
            }.also {
                previewPainter = it
                diag("preview: siap")
            }
        } catch (error: Throwable) {
            Log.w(TAG, "Kompositor preview gagal: ${error.message}")
            diag("preview: gagal (${error.message})")
            null
        }
    }

    private fun ensurePreviewSurface(
        core: EglCore,
        current: EGLSurface?,
        surface: Surface?,
    ): EGLSurface? {
        if (surface == null || !surface.isValid) {
            releaseSurface(core, current)
            return null
        }
        if (current != null) return current
        return runCatching { core.createWindowSurface(surface) }
            .onFailure { Log.w(TAG, "Surface preview gagal dibuat: ${it.message}") }
            .getOrNull()
    }

    /** Menggambar satu panel preview/program di thread preview. */
    private fun drawPane(
        core: EglCore,
        painter: GlCompositor,
        surface: EGLSurface?,
        surfaceWidth: Int,
        surfaceHeight: Int,
        frame: PreviewFrame,
        layers: List<RenderLayer>,
    ) {
        if (surface == null || surfaceWidth <= 0 || surfaceHeight <= 0) return

        try {
            core.makeCurrent(surface)
        } catch (error: IllegalStateException) {
            Log.w(TAG, "Surface render tidak valid: ${error.message}")
            return
        }

        // Panel memuat seluruh kanvas (letterbox), lalu seluruh layer digambar
        // dengan matriks yang sama seperti ke encoder.
        val canvasRect = computeViewport(
            surfaceWidth,
            surfaceHeight,
            config.width,
            config.height,
        )

        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        GLES20.glScissor(canvasRect[0], canvasRect[1], canvasRect[2], canvasRect[3])
        GLES20.glViewport(canvasRect[0], canvasRect[1], canvasRect[2], canvasRect[3])
        // Panduan kanvas hanya di tampilan editor, tidak masuk ke encoder.
        painter.drawCheckerboard(
            width = config.width.toFloat(),
            height = config.height.toFloat(),
            cellsX = (config.width / 60f).coerceAtLeast(1f),
            cellsY = (config.height / 60f).coerceAtLeast(1f),
        )
        drawLayers(painter, frame.matrix, layers, config.width, config.height)
        painter.drawCanvasBorder(
            width = config.width.toFloat(),
            height = config.height.toFloat(),
            thickness = (config.width / 720f).coerceAtLeast(1.5f),
        )
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)

        core.swapBuffers(surface)
    }

    private fun renderToEncoder(core: EglCore, surface: EGLSurface, presentationNs: Long) {
        try {
            core.makeCurrent(surface)
        } catch (error: IllegalStateException) {
            Log.w(TAG, "Surface encoder tidak valid: ${error.message}")
            diag("makeCurrent gagal: ${error.message}")
            return
        }

        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glViewport(0, 0, config.encoderWidth, config.encoderHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        // Fade ke hitam: layer lama dipakai sampai titik tengah, lalu diganti.
        val transitionAlpha = if (transitionActive) {
            val elapsed = System.nanoTime() - transitionStartNs
            if (elapsed >= TRANSITION_NS / 2 && pendingProgramCollection != null) {
                buildLayers(checkNotNull(pendingProgramCollection))?.let { renderLayers = it }
                pendingProgramCollection = null
            }
            if (elapsed >= TRANSITION_NS) {
                transitionActive = false
                diag("transisi: selesai")
                0f
            } else {
                (1f - kotlin.math.abs(2f * elapsed / TRANSITION_NS - 1f)).coerceIn(0f, 1f)
            }
        } else {
            0f
        }

        val painter = compositor ?: return
        val drawStart = System.nanoTime()
        drawLayers(painter, texMatrix, renderLayers, config.width, config.height)
        val drawMs = (System.nanoTime() - drawStart) / 1_000_000
        if (transitionAlpha > 0f) {
            painter.drawColor(
                red = 0f,
                green = 0f,
                blue = 0f,
                alpha = transitionAlpha,
                left = 0f,
                bottom = 0f,
                width = config.width.toFloat(),
                height = config.height.toFloat(),
                rotation = 0f,
            )
        }
        samplePixelChange(System.nanoTime())

        val frameStart = System.nanoTime()
        core.setPresentationTime(surface, presentationNs)
        val swapped = core.swapBuffers(surface)
        val swapMs = (System.nanoTime() - frameStart) / 1_000_000
        if (!swapped) {
            swapFailures++
            consecutiveSwapFailures++
            diag("swapBuffers encoder gagal")
        } else {
            consecutiveSwapFailures = 0
        }
        // Catat frame yang lambat agar penyebab stutter terlihat: waktu habis
        // di swapBuffers berarti encoder tidak sanggup menerima frame.
        if (swapMs > 35) {
            val now = System.nanoTime()
            if (now - lastSlowFrameLogNs > 2_000_000_000L) {
                lastSlowFrameLogNs = now
                diag("encoder lambat: draw=${drawMs}ms swap=${swapMs}ms fps=$lastPublishedFps")
            }
        }
    }

    private fun teardownSession() {
        // Tanpa ini status layar jeda tertinggal dan Transisi berhenti bekerja.
        runCatching { SceneManager.resetScreensaver() }
        running = false
        recordingActive = false
        liveActive = false
        glHandler.removeCallbacks(watchdog)
        glHandler.removeCallbacks(pacer)

        SceneManager.removeListener(sceneListener)
        SceneManager.removeEditListener(editListener)
        unregisterDisplayListener()

        runCatching { screenCapture?.stop() }
            .onFailure { Log.w(TAG, "Capture gagal dihentikan: ${it.message}") }
        screenCapture = null

        runCatching {
            surfaceTexture?.setOnFrameAvailableListener(null)
            surfaceTexture?.release()
        }
        surfaceTexture = null
        virtualDisplaySurface?.release()
        virtualDisplaySurface = null

        runCatching { videoEncoder?.finish() }
            .onFailure { Log.w(TAG, "Video gagal dihentikan: ${it.message}") }
        videoEncoder = null

        val core = egl
        encoderEglSurface?.let { runCatching { core?.releaseSurface(it) } }
        encoderEglSurface = null

        compositor?.release()
        compositor = null

        if (textureId != 0) {
            runCatching { GLES20.glDeleteTextures(1, intArrayOf(textureId), 0) }
            textureId = 0
        }

        // Tekstur sumber gambar/teks dilepas setelah semua frame selesai.
        val owned = textureCache.values.map { it.textureId } + retiredTextures
        if (owned.isNotEmpty()) {
            runCatching { GLES20.glDeleteTextures(owned.size, owned.toIntArray(), 0) }
        }
        releaseAllCameras()
        mediaSources.keys.toList().forEach { releaseMedia(it) }
        browserSources.keys.toList().forEach { releaseBrowser(it) }
        slidePreviousTextures.values.forEach { retiredTextures += it }
        slidePreviousTextures.clear()
        slideFadeStartNs.clear()
        textureCache.clear()
        retiredTextures.clear()

        // Preview dilepas di threadnya sendiri. Kalau thread itu sedang
        // tertahan di eglSwapBuffers, pelepasan menunggu sampai tidak macet
        // dan tidak pernah menahan penyimpanan file.
        previewHandler?.post { teardownPreview() }
        previewThread?.quitSafely()

        runCatching { core?.release() }
        egl = null

        muxer?.let { sinks.remove(it, finish = false) }
        sinks.finishAll()
        liveSink = null
        encodersRunning = false
        val saved = runCatching { muxer?.finish() ?: false }.getOrDefault(false)
        muxer = null
        val finishedSafe = safeSession
        safeSession = null
        if (finishedSafe != null) {
            SafeRecordingStore.endActive(finishedSafe)
            if (saved) {
                mergeSafeRecording(finishedSafe)
            } else {
                // Sesi berakhir paksa (mis. izin tangkap layar dicabut):
                // beri tahu bahwa segmen masih bisa dipulihkan.
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(
                        context,
                        context.getString(R.string.error_record_recoverable),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
        runCatching { audioEncoder?.shutdown() }
        audioEncoder = null
        AudioMixer.clearRuntime()
        onLiveStats(LiveStats())

        releaseProjection()

        runCatching { target?.finish(context, saved) }
        target = null

        diag(
            "stop: reason=${stopReason ?: "-"} frames=$capturedFrames submitted=$submittedFrames " +
                "encoded=$encodedOutputs swapFail=$swapFailures pace=$pacedFrames " +
                "preview=$previewSwaps saved=$saved ${telemetryText()}",
        )
        DiagLog.writeToDownloads(context)
        glThread.quitSafely()

        // Sesi berakhir: UI kembali ke Idle dan service boleh berhenti.
        // Pesan error rekaman sudah dikirim endRecording sebelum ini.
        onState(RecordingState.Idle)
        onSessionEnd()
    }

    /** Dijalankan di thread preview. */
    private fun teardownPreview() {
        val core = previewEgl
        releaseSurface(core, previewEglSurface)
        releaseSurface(core, programEglSurface)
        releaseSurface(core, overlayProgramEglSurface)
        previewEglSurface = null
        programEglSurface = null
        overlayProgramEglSurface = null
        previewTarget = null
        programTarget = null
        overlayProgramTarget = null
        overlayProgramSurfaceAttached = false
        previewPainter?.release()
        previewPainter = null
        runCatching { core?.release() }
        previewEgl = null
    }

    private fun releaseSurface(core: EglCore?, surface: EGLSurface?) {
        if (surface == null) return
        runCatching { core?.releaseSurface(surface) }
    }

    private fun unregisterDisplayListener() {
        val manager = displayManager
        val listener = displayListener
        if (manager != null && listener != null) {
            runCatching { manager.unregisterDisplayListener(listener) }
        }
        displayListener = null
        displayManager = null
    }

    private fun fail(error: Throwable) {
        Log.e(TAG, "Pipeline gagal disiapkan", error)
        running = false

        glHandler.post {
            if (!teardownStarted) {
                teardownStarted = true
                recordingActive = false
                SceneManager.removeListener(sceneListener)
                SceneManager.removeEditListener(editListener)
                unregisterDisplayListener()
                runCatching { screenCapture?.stop() }
                screenCapture = null
                runCatching { surfaceTexture?.release() }
                surfaceTexture = null
                virtualDisplaySurface?.release()
                virtualDisplaySurface = null
                runCatching { videoEncoder?.finish() }
                videoEncoder = null
                runCatching { audioEncoder?.shutdown() }
                audioEncoder = null
                AudioMixer.clearRuntime()
                previewHandler?.post { teardownPreview() }
                previewThread?.quitSafely()
                runCatching { egl?.release() }
                egl = null
                runCatching { muxer?.finish() }
                muxer = null
                sinks.finishAll()
                releaseProjection()
                runCatching { target?.finish(context, false) }
                target = null
                glThread.quitSafely()
            }
            onState(
                RecordingState.Error(
                    error.message ?: context.getString(R.string.error_start_capture),
                ),
            )
            onSessionEnd()
        }
    }

    private fun releaseProjection() {
        releaseWakeLock()
        val active = projection ?: return
        runCatching { active.unregisterCallback(projectionCallback) }
        runCatching { active.stop() }
        projection = null
    }

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Membaca 9 kotak kecil dari frame encoder sekali per detik dan
     * membandingkan hash-nya. Bila konten tidak berubah >2 detik, pacer
     * boleh turun ke 30 fps.
     */
    private fun samplePixelChange(nowNs: Long) {
        if (!config.staticThrottleEnabled || !pixelReadSupported) return
        // Saat dianggap statis, cek lebih sering agar respons kembali 60 fps
        // tidak menunggu sampai 1 detik.
        val intervalNs = if (lastVisualChangeNs > 0L &&
            nowNs - lastVisualChangeNs > 2_000_000_000L
        ) {
            250_000_000L
        } else {
            1_000_000_000L
        }
        if (lastPixelSampleNs > 0L && nowNs - lastPixelSampleNs < intervalNs) return
        lastPixelSampleNs = nowNs

        val width = config.encoderWidth
        val height = config.encoderHeight
        val size = 8
        // Area tengah saja: hindari jam/status bar yang berubah sendiri.
        val points = intArrayOf(
            width / 4, height / 4,
            width / 2, height / 4,
            width * 3 / 4, height / 4,
            width / 4, height / 2,
            width / 2, height / 2,
            width * 3 / 4, height / 2,
            width / 4, height * 3 / 4,
            width / 2, height * 3 / 4,
            width * 3 / 4, height * 3 / 4,
        )
        var index = 0
        var sampleIndex = 0
        while (index < points.size) {
            val x = (points[index] - size / 2).coerceIn(0, (width - size).coerceAtLeast(0))
            val y = (points[index + 1] - size / 2).coerceIn(0, (height - size).coerceAtLeast(0))
            pixelSample.position(sampleIndex * size * size * 4)
            GLES20.glReadPixels(x, y, size, size, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixelSample)
            index += 2
            sampleIndex++
        }
        if (GLES20.glGetError() != GLES20.GL_NO_ERROR) {
            // Sebagian GPU tidak mengizinkan readback surface; matikan deteksi.
            pixelReadSupported = false
            diag("pixel sample: tidak didukung, pakai deteksi frame")
            return
        }
        pixelSample.rewind()
        val current = ByteArray(pixelSample.remaining())
        pixelSample.get(current)
        val previous = lastPixelBytes
        lastPixelBytes = current
        if (previous == null || previous.size != current.size) {
            lastVisualChangeNs = nowNs
            return
        }
        var changed = 0
        for (i in current.indices) {
            if (kotlin.math.abs(current[i].toInt() - previous[i].toInt()) > 12) changed++
        }
        // Butuh 2 sampel berturut-turut (>5%) agar noise readback tidak
        // membuat fps bolak-balik 30/60.
        if (changed > current.size * 5 / 100) {
            changeStreak++
            if (changeStreak >= 2) {
                lastVisualChangeNs = nowNs
                changeStreak = 0
            }
        } else {
            changeStreak = 0
        }
    }

    /**
     * Pilih interval pacer: 60 fps normal, 30 fps saat perangkat panas atau
     * layar tidak berubah lebih dari 2 detik. Hemat CPU/GPU tanpa mengubah
     * durasi video.
     */
    private fun applyPacerPolicy(nowNs: Long) {
        val staticScreen = if (pixelReadSupported && lastVisualChangeNs > 0L) {
            nowNs - lastVisualChangeNs > 3_000_000_000L
        } else {
            hasCapturedFrame &&
                lastCaptureChangeNs > 0L &&
                nowNs - lastCaptureChangeNs > 2_000_000_000L
        }
        val thermalActive = config.thermalThrottleEnabled && thermalThrottled
        val staticActive = config.staticThrottleEnabled && staticScreen
        val desired = if (thermalActive || staticActive) {
            1_000_000_000L / 30
        } else {
            frameIntervalNs
        }
        val throttled = desired != frameIntervalNs
        previewIntervalNs = if (throttled) {
            1_000_000_000L / 15
        } else {
            1_000_000_000L / 30
        }
        if (desired == pacerIntervalNs) return
        pacerIntervalNs = desired
        nextPacerNs = nowNs
        diag(
            "pacer: fps=${if (desired == frameIntervalNs) config.fps else 30} " +
                "alasan=${when {
                    thermalActive -> "thermal"
                    staticActive -> "layar statis"
                    else -> "normal"
                }}",
        )
    }

    /** Turunkan pacer ke 30 fps saat perangkat panas, pulih saat dingin. */
    private fun updateThermalPolicy() {
        val power = context.getSystemService(PowerManager::class.java) ?: return
        val status = runCatching { power.currentThermalStatus }.getOrNull() ?: return
        val shouldThrottle = status >= PowerManager.THERMAL_STATUS_MODERATE
        if (shouldThrottle == thermalThrottled) return
        thermalThrottled = shouldThrottle
        applyPacerPolicy(System.nanoTime())
        val active = shouldThrottle && config.thermalThrottleEnabled
        diag("thermal: status=$status throttle=$active fps=${if (active) 30 else config.fps}")
    }

    private fun resetTelemetryBaseline() {
        telemetryCpuNs = Debug.threadCpuTimeNanos()
        telemetryTxBytes = TrafficStats.getTotalTxBytes().coerceAtLeast(0L)
        telemetryRxBytes = TrafficStats.getTotalRxBytes().coerceAtLeast(0L)
    }

    /** Memori, storage, jaringan, dan CPU thread untuk log diagnostik. */
    private fun telemetryText(): String {
        val runtime = Runtime.getRuntime()
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L)
        val maxMb = runtime.maxMemory() / (1024L * 1024L)
        val freeGb = runCatching {
            StatFs(Environment.getExternalStorageDirectory().path).availableBytes /
                (1024L * 1024L * 1024L)
        }.getOrDefault(-1L)
        val cpuNs = (Debug.threadCpuTimeNanos() - telemetryCpuNs).coerceAtLeast(0L)
        val txMb = ((TrafficStats.getTotalTxBytes().coerceAtLeast(0L) - telemetryTxBytes)
            .coerceAtLeast(0L)) / (1024L * 1024L)
        val rxMb = ((TrafficStats.getTotalRxBytes().coerceAtLeast(0L) - telemetryRxBytes)
            .coerceAtLeast(0L)) / (1024L * 1024L)
        val cpuSec = "%.2f".format(cpuNs / 1_000_000_000.0)
        val thermal = runCatching {
            context.getSystemService(PowerManager::class.java)?.currentThermalStatus
        }.getOrNull()
        return "mem=${usedMb}MB/${maxMb}MB storage=${freeGb}GB " +
            "netTx=+${txMb}MB netRx=+${rxMb}MB cpuThread=${cpuSec}s " +
            "thermal=${thermal ?: "-"} cache=${textureCache.size} throttled=$thermalThrottled"
    }

    private fun diag(message: String) {
        val elapsedMs = (System.nanoTime() - startTimeNs).coerceAtLeast(0L) / 1_000_000L
        val millis = (elapsedMs % 1000).toString().padStart(3, '0')
        DiagLog.line("[${elapsedMs / 1000}.${millis}] $message")
    }

    private data class PreviewFrame(
        val matrix: FloatArray,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val layers: List<RenderLayer>,
        val previewLayers: List<RenderLayer>,
    )

    /** Satu layer siap gambar, sudah diterjemahkan dari scene item. */
    private data class RenderLayer(
        val type: SourceType,
        val textureId: Int,
        val naturalWidth: Int,
        val naturalHeight: Int,
        val transform: Transform,
        val crop: Crop,
        val colorArgb: Long,
        val blend: BlendMode = BlendMode.Normal,
        val opacity: Float = 1f,
        val sourceId: String? = null,
        val screenDisplay: com.rout.air.core.model.ScreenDisplay? = null,
        val filters: com.rout.air.engine.filter.FilterState = com.rout.air.engine.filter.FilterState.NONE,
        val camera: CameraTexture? = null,
        val browser: BrowserTexture? = null,
        /** Tekstur slide sebelumnya untuk crossfade; 0 = tanpa fade. */
        val fadeTextureId: Int = 0,
        val fadeOpacity: Float = 1f,
        /** Mode bounds fitting ala OBS; None = tidak memaksa pas ke bounds. */
        val boundsType: com.rout.air.core.model.BoundsType = com.rout.air.core.model.BoundsType.None,
        /** Lebar kotak bounds dalam piksel kanvas; 0 = ukuran sumber asli. */
        val boundsWidth: Int = 0,
        /** Tinggi kotak bounds dalam piksel kanvas; 0 = ukuran sumber asli. */
        val boundsHeight: Int = 0,
        /** Perataan sumber di dalam kotak bounds. */
        val boundsAlign: com.rout.air.core.model.Align = com.rout.air.core.model.Align.Center,
        /** Potong tepi sumber yang keluar bounds. */
        val cropToBounds: Boolean = false,
    )

    private data class CachedTexture(
        val signature: String,
        val textureId: Int,
        val width: Int,
        val height: Int,
    )

    private companion object {
        const val TAG = "RoutAirPipeline"
        /** UA desktop untuk sumber Browser. */
        const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"
        const val STALL_TIMEOUT_NS = 5_000_000_000L
        const val PREVIEW_STALL_NS = 3_000_000_000L
        const val WATCHDOG_INTERVAL_MS = 1_000L

        /** Durasi total transisi fade ke hitam. */
        const val TRANSITION_NS = 500_000_000L

        /** Lama crossfade antar gambar slide show. */
        const val SLIDE_FADE_NS = 500_000_000f

        /** Letterbox kanvas ke dalam surface (pratinjau). */
        fun computeViewport(
            surfaceWidth: Int,
            surfaceHeight: Int,
            canvasWidth: Int,
            canvasHeight: Int,
        ): IntArray {
            if (surfaceWidth <= 0 || surfaceHeight <= 0) {
                return intArrayOf(0, 0, canvasWidth, canvasHeight)
            }

            val surfaceRatio = surfaceWidth.toFloat() / surfaceHeight
            val canvasRatio = canvasWidth.toFloat() / canvasHeight
            return if (surfaceRatio > canvasRatio) {
                val width = (surfaceHeight * canvasRatio).toInt()
                intArrayOf((surfaceWidth - width) / 2, 0, width, surfaceHeight)
            } else {
                val height = (surfaceWidth / canvasRatio).toInt()
                intArrayOf(0, (surfaceHeight - height) / 2, surfaceWidth, height)
            }
        }

        /**
         * Menghitung pas bounds ala OBS (`calculate_bounds_data`).
         *
         * Mengembalikan [fitWidth, fitHeight, offsetX, offsetY]:
         * - fitWidth/fitHeight = ukuran hasil render setelah bounds fitting
         * - offsetX/offsetY = pergeseran posisi top-left sesuai boundsAlign
         *
         * Jika boundsType = None, mengembalikan ukuran asli tanpa offset.
         */
        fun computeBoundsFit(
            sourceW: Int,
            sourceH: Int,
            scaleX: Float,
            scaleY: Float,
            boundsW: Int,
            boundsH: Int,
            boundsType: com.rout.air.core.model.BoundsType,
            boundsAlign: com.rout.air.core.model.Align,
        ): FloatArray {
            if (sourceW <= 0 || sourceH <= 0) {
                return floatArrayOf(sourceW.toFloat(), sourceH.toFloat(), 0f, 0f)
            }

            var curW = sourceW.toFloat() * scaleX
            var curH = sourceH.toFloat() * scaleY

            val itemAspect = curW / curH
            val boundsAspect = boundsW.toFloat() / boundsH.toFloat()

            var newScaleX = scaleX
            var newScaleY = scaleY

            when (boundsType) {
                com.rout.air.core.model.BoundsType.ScaleInner,
                com.rout.air.core.model.BoundsType.ScaleOuter -> {
                    // Mirror OBS: pilih sumbu yang menjadi pembatas.
                    var useWidth = boundsAspect < itemAspect
                    if (boundsType == com.rout.air.core.model.BoundsType.ScaleOuter) {
                        useWidth = !useWidth
                    }
                    val mul = if (useWidth) boundsW.toFloat() / curW else boundsH.toFloat() / curH
                    newScaleX *= mul
                    newScaleY *= mul
                }

                com.rout.air.core.model.BoundsType.ScaleToWidth -> {
                    newScaleX = boundsW.toFloat() / curW
                }

                com.rout.air.core.model.BoundsType.ScaleToHeight -> {
                    newScaleY = boundsH.toFloat() / curH
                }

                com.rout.air.core.model.BoundsType.Stretch -> {
                    newScaleX = Math.copySign(boundsW.toFloat() / sourceW.toFloat(), scaleX)
                    newScaleY = Math.copySign(boundsH.toFloat() / sourceH.toFloat(), scaleY)
                }

                com.rout.air.core.model.BoundsType.None -> {}
            }

            val fitW = sourceW.toFloat() * newScaleX
            val fitH = sourceH.toFloat() * newScaleY

            // Sisa ruang di bounds setelah fitting (positif = bounds lebih besar).
            val widthDiff = boundsW.toFloat() - Math.abs(fitW)
            val heightDiff = boundsH.toFloat() - Math.abs(fitH)

            // OBS add_alignment: RIGHT = penuh, tengah = setengah, LEFT = nol.
            val offsetX = when (boundsAlign) {
                com.rout.air.core.model.Align.Right,
                com.rout.air.core.model.Align.TopRight,
                com.rout.air.core.model.Align.BottomRight -> widthDiff
                com.rout.air.core.model.Align.Center,
                com.rout.air.core.model.Align.Top,
                com.rout.air.core.model.Align.Bottom -> widthDiff / 2f
                else -> 0f
            }
            val offsetY = when (boundsAlign) {
                com.rout.air.core.model.Align.Bottom,
                com.rout.air.core.model.Align.BottomLeft,
                com.rout.air.core.model.Align.BottomRight -> heightDiff
                com.rout.air.core.model.Align.Center,
                com.rout.air.core.model.Align.Left,
                com.rout.air.core.model.Align.Right -> heightDiff / 2f
                else -> 0f
            }

            return floatArrayOf(fitW, fitH, offsetX, offsetY)
        }

        /**
         * Posisi konten tangkapan di dalam kanvas sesuai mode isi.
         * Mode Penuh bisa menghasilkan rect di luar kanvas (dipotong).
         */
        fun computeContentRect(
            canvasWidth: Int,
            canvasHeight: Int,
            sourceWidth: Int,
            sourceHeight: Int,
            mode: ContentMode,
        ): IntArray {
            if (sourceWidth <= 0 || sourceHeight <= 0 || mode == ContentMode.Stretch) {
                return intArrayOf(0, 0, canvasWidth, canvasHeight)
            }

            val sourceRatio = sourceWidth.toFloat() / sourceHeight
            val canvasRatio = canvasWidth.toFloat() / canvasHeight

            val fitWidth: Int
            val fitHeight: Int
            if (mode == ContentMode.Crop) {
                // Skala terbesar: menutup seluruh kanvas, tepi luar terpotong.
                if (sourceRatio > canvasRatio) {
                    fitHeight = canvasHeight
                    fitWidth = (canvasHeight * sourceRatio).roundToInt()
                } else {
                    fitWidth = canvasWidth
                    fitHeight = (canvasWidth / sourceRatio).roundToInt()
                }
            } else {
                if (sourceRatio > canvasRatio) {
                    fitWidth = canvasWidth
                    fitHeight = (canvasWidth / sourceRatio).roundToInt()
                } else {
                    fitHeight = canvasHeight
                    fitWidth = (canvasHeight * sourceRatio).roundToInt()
                }
            }

            val width = max(2, fitWidth)
            val height = max(2, fitHeight)
            return intArrayOf(
                (canvasWidth - width) / 2,
                (canvasHeight - height) / 2,
                width,
                height,
            )
        }

        /** Memetakan rect kanvas ke rect surface preview. */
        fun mapRect(
            rect: IntArray,
            canvasRect: IntArray,
            canvasWidth: Int,
            canvasHeight: Int,
        ): IntArray {
            val scaleX = canvasRect[2].toFloat() / canvasWidth
            val scaleY = canvasRect[3].toFloat() / canvasHeight
            return intArrayOf(
                canvasRect[0] + (rect[0] * scaleX).roundToInt(),
                canvasRect[1] + (rect[1] * scaleY).roundToInt(),
                max(2, (rect[2] * scaleX).roundToInt()),
                max(2, (rect[3] * scaleY).roundToInt()),
            )
        }
    }
}
