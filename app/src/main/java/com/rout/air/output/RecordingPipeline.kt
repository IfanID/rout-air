package com.rout.air.output

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.os.PowerManager
import android.media.projection.MediaProjectionManager
import android.net.TrafficStats
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Debug
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.StatFs
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Surface
import androidx.core.content.ContextCompat
import com.rout.air.capture.ScreenCapture
import com.rout.air.core.Align
import com.rout.air.core.AudioRuntime
import com.rout.air.core.BlendMode
import com.rout.air.core.CaptureConfig
import com.rout.air.core.ContentMode
import com.rout.air.core.Crop
import com.rout.air.core.LiveConfig
import com.rout.air.core.LiveStats
import com.rout.air.core.MixSnapshot
import com.rout.air.core.RecordingState
import com.rout.air.core.RecordingStats
import com.rout.air.core.RecordingClock
import com.rout.air.core.SceneCollection
import com.rout.air.core.SceneSource
import com.rout.air.core.SourceType
import com.rout.air.core.Transform
import com.rout.air.data.AudioMixer
import com.rout.air.data.DiagLog
import com.rout.air.data.SceneManager
import com.rout.air.encode.AudioCaptureEngine
import com.rout.air.encode.Mp4Writer
import com.rout.air.encode.OutputTarget
import com.rout.air.encode.TeeSink
import com.rout.air.encode.VideoEncoder
import com.rout.air.engine.EglCore
import com.rout.air.engine.GlCompositor
import com.rout.air.engine.GlUtil
import com.rout.air.engine.SourceBitmap
import com.rout.air.live.RtmpSink
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.roundToInt

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
    private var previewTarget: Surface? = previewSurface
    private var previewWidth = previewWidth
    private var previewHeight = previewHeight
    private var programTarget: Surface? = programSurface
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
    /** Semua tujuan keluaran aktif: MP4 dan/atau RTMP. */
    private val sinks = TeeSink()
    private var target: OutputTarget? = null
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

    /** Perubahan program (hasil Transisi); dijalankan di thread GL. */
    private val sceneListener: (SceneCollection) -> Unit = {
        glHandler.post { rebuildLayers() }
    }

    /** Perubahan hasil edit: hanya memperbarui gambar pratinjau. */
    private val editListener: (SceneCollection) -> Unit = {
        glHandler.post { rebuildPreviewLayers() }
    }

    /** Ukuran layar yang sedang ditangkap, mengikuti rotasi perangkat. */
    private var sourceWidth = 0
    private var sourceHeight = 0

    @Volatile
    private var lastEncodedNs = Long.MIN_VALUE
    private var lastPreviewNs = 0L

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
            if (watchdogTicks % 5 == 0) {
                val seconds = (now - startTimeNs) / 1_000_000_000L
                diag(
                    "t=${seconds}s frames=$capturedFrames submitted=$submittedFrames " +
                        "encoded=$encodedOutputs swapFail=$swapFailures pace=$pacedFrames " +
                        "preview=$previewSwaps src=${sourceWidth}x$sourceHeight " +
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
                    stopReason = "penyimpanan penuh atau gagal menulis"
                    if (recordingActive) stopRecording() else stopLive()
                    return
                }

                WatchdogAction.Continue -> Unit
            }

            if (watchdogTicks % 5 == 0) updateThermalPolicy()
            if (recordingActive) publishStats(now)

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
            if ((previewVisible || previewSurfaceAttached || programSurfaceAttached) &&
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
                abortRecording(error.message ?: "Gagal memulai rekaman")
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
        val saved = runCatching { writer?.finish() ?: false }.getOrDefault(false)
        muxer = null
        runCatching { target?.finish(context, saved) }
        target = null

        diag("rekam: lepas (live lanjut) saved=$saved")
        publishStats(System.nanoTime(), finalBytes)
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

        val manager = context.getSystemService(MediaProjectionManager::class.java)
            ?: error("MediaProjectionManager tidak tersedia")
        val mediaProjection = manager.getMediaProjection(resultCode, projectionData)
            ?: error("Izin tangkap layar tidak valid")
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
        val settings = com.rout.air.data.SettingsStore(context)
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
            error("Ukuran layar tidak terbaca")
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
            Log.w(TAG, "Mesin audio gagal disiapkan: ${error.message}")
            AudioMixer.setRuntime(AudioRuntime())
            null
        }
    }

    /** Menyiapkan file MP4 memakai sesi dan encoder yang (mungkin) sudah jalan. */
    private fun beginRecording() {
        check(egl != null) { "Sesi tangkap layar belum siap" }
        check(projection != null) { "Izin tangkap layar tidak valid" }

        diag("rekam: mulai")
        val freeBytes = runCatching {
            StatFs(Environment.getExternalStorageDirectory().path).availableBytes
        }.getOrDefault(Long.MAX_VALUE)
        if (!StorageGuard.hasRoom(freeBytes)) {
            val freeMb = freeBytes / (1024 * 1024)
            error("Penyimpanan hampir penuh ($freeMb MB tersisa, minimal 200 MB)")
        }

        val output = OutputTarget.create(
            context = context,
            location = config.saveLocation,
            nameTemplate = config.fileNameTemplate,
            noSpaceFileName = config.noSpaceFileName,
        )
        target = output
        outputUri = output.uri.toString()

        val writer = Mp4Writer(output)
        muxer = writer

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
            com.rout.air.data.SceneManager.rescaleCanvas(oldWidth, oldHeight, width, height)
            com.rout.air.data.SettingsStore(context).saveSceneCanvas(width, height)
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
                        status = com.rout.air.core.LiveStatus.Error,
                        lastError = "Sesi tangkap layar belum aktif",
                    ),
                )
                return@post
            }
            try {
                val sink = RtmpSink(liveConfig, onLiveStats)
                liveSink = sink
                if (!recordingClock.isStarted) {
                    recordingClock.start(System.nanoTime())
                    resetTelemetryBaseline()
                }
                if (!encodersRunning) {
                    val mix = AudioMixer.snapshot()
                    val wantsAudio = audioEncoder != null && mix.masterEnabled &&
                        mix.channels.values.any { it.enabled }
                    startEncoders(wantsAudio, mix)
                }
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
                        status = com.rout.air.core.LiveStatus.Error,
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
    private fun startEncoders(wantsAudio: Boolean, mixSnapshot: MixSnapshot) {
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

        val encoder = VideoEncoder(
            width = config.encoderWidth,
            height = config.encoderHeight,
            fps = config.fps,
            bitrate = config.videoBitrate,
            keyframeIntervalSec = config.keyframeIntervalSec,
            bitrateMode = config.bitrateMode,
            profile = config.videoProfile,
            colorSpace = config.colorSpace,
            colorRange = config.colorRange,
            sink = sinks,
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
        val saved = runCatching { muxer?.finish() ?: false }.getOrDefault(false)
        muxer = null
        runCatching { target?.finish(context, saved) }
        target = null

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

        if (!hadRecording || saved) {
            onState(RecordingState.Previewing)
            if (reason != null) Log.i(TAG, "Rekaman berhenti lebih awal: $reason")
        } else {
            val detail = reason?.let { " • $it" }.orEmpty()
            onState(
                RecordingState.Error(
                    "Rekaman gagal disimpan (frame $capturedFrames, video $encodedOutputs)$detail",
                ),
            )
        }
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
        onStats(
            RecordingStats(
                durationMs = durationMs,
                frames = encodedOutputs,
                droppedFrames = swapFailures,
                bytes = bytes,
                bitrateKbps = kbps,
                paused = recordingClock.isPaused,
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
        runCatching { muxer?.finish() }
        muxer = null
        runCatching { target?.finish(context, false) }
        target = null
        sinks.finishAll()
        liveSink = null
        encodersRunning = false
        recordingStopping = false
        recordingClock.reset()
        recordingStartedAtMs = 0L
        recordingBaseElapsedNs = 0L
        onStats(RecordingStats())
        onLiveStats(LiveStats())
        onState(RecordingState.Error(message))
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
        val layers = buildLayers(SceneManager.programCurrent(), "program")
        renderLayers = layers
        pruneTextures()
    }

    /** Menyusun layer pratinjau dari hasil edit yang belum di-transisikan. */
    private fun rebuildPreviewLayers() {
        previewLayers = buildLayers(SceneManager.current(), "pratinjau")
        pruneTextures()
    }

    private fun buildLayers(collection: SceneCollection, label: String): List<RenderLayer> {
        val core = egl ?: return emptyList()
        val surface = encoderEglSurface

        try {
            if (surface != null) core.makeCurrent(surface) else core.makeCurrentNoSurface()
        } catch (error: IllegalStateException) {
            Log.w(TAG, "Scene: konteks EGL tidak aktif: ${error.message}")
            return emptyList()
        }

        val scene = collection.activeScene() ?: return emptyList()
        val layers = mutableListOf<RenderLayer>()

        for (item in scene.items.sortedBy { it.z }) {
            if (!item.visible) continue
            val source = collection.source(item.sourceId) ?: continue

            when (source.type) {
                SourceType.Screen -> layers += RenderLayer(
                    type = SourceType.Screen,
                    textureId = textureId,
                    naturalWidth = 0,
                    naturalHeight = 0,
                    transform = item.transform,
                    crop = item.crop,
                    colorArgb = 0,
                    blend = item.blend,
                    opacity = item.opacity,
                    sourceId = null,
                    screenDisplay = source.settings.screenDisplay,
                )

                SourceType.Image, SourceType.Text -> {
                    val cached = ensureTexture(source) ?: continue
                    layers += RenderLayer(
                        type = source.type,
                        textureId = cached.textureId,
                        naturalWidth = cached.width,
                        naturalHeight = cached.height,
                        transform = item.transform,
                        crop = item.crop,
                        colorArgb = 0,
                        blend = item.blend,
                        opacity = item.opacity,
                        sourceId = source.id,
                    )
                }

                SourceType.Color -> layers += RenderLayer(
                    type = SourceType.Color,
                    textureId = 0,
                    naturalWidth = source.settings.width.coerceAtLeast(1),
                    naturalHeight = source.settings.height.coerceAtLeast(1),
                    transform = item.transform,
                    crop = item.crop,
                    colorArgb = source.settings.color,
                    blend = item.blend,
                    opacity = item.opacity,
                )

                SourceType.AudioInput, SourceType.AudioOutput -> Unit
            }
        }

        diag("scene $label: ${scene.name} layer=${layers.size}")
        return layers
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
        val signature = sourceSignature(source)
        textureCache[source.id]?.let { if (it.signature == signature) return it }

        val bitmap = when (source.type) {
            SourceType.Image -> SourceBitmap.loadImage(
                path = source.settings.imagePath,
                maxWidth = config.width,
                maxHeight = config.height,
            )

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

        textureCache[source.id]?.let { retiredTextures += it.textureId }
        textureCache[source.id] = cached
        return cached
    }

    private fun sourceSignature(source: SceneSource): String = buildString {
        append(source.type.name)
        append('|').append(source.settings.imagePath)
        append('|').append(source.settings.text)
        append('|').append(source.settings.textSize)
        append('|').append(source.settings.textColor)
        append('|').append(source.settings.width)
        append('|').append(source.settings.height)
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

        // Sumber Layar dengan template: kotak dihitung ulang dari kanvas dan
        // ukuran tangkapan, tidak memakai skala tersimpan. Dengan begitu
        // nilai lama (dari orientasi sebelumnya) tidak membuat gambar keluar
        // area kanvas.
        val template = layer.screenDisplay
        var templateRotate = false
        if (layer.type == SourceType.Screen && template != null &&
            template != com.rout.air.core.ScreenDisplay.Auto
        ) {
            val base = canvasWidth.toFloat() / canvasHeight.toFloat()
            val aspect = when (template) {
                com.rout.air.core.ScreenDisplay.Landscape ->
                    if (base >= 1f) base else 1f / base

                com.rout.air.core.ScreenDisplay.Portrait ->
                    if (base <= 1f) base else 1f / base

                com.rout.air.core.ScreenDisplay.Auto -> base
            }
            width = minOf(canvasWidth.toFloat(), canvasHeight * aspect)
            height = width / aspect
            templateRotate = com.rout.air.core.screenNeedsRotation(
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

        var left = layer.transform.x - anchorX
        var top = layer.transform.y - anchorY

        // Template layar mengikuti posisi yang diatur pengguna, tetapi
        // dijepit agar tidak keluar kanvas setelah orientasi berganti.
        if (layer.type == SourceType.Screen && template != null &&
            template != com.rout.air.core.ScreenDisplay.Auto
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
            val mode = if (layer.screenDisplay != null) {
                ContentMode.Fit
            } else {
                config.contentMode
            }
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
            )

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
        drawLayers(painter, frame.matrix, layers, config.width, config.height)
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
        drawLayers(compositor ?: return, texMatrix, renderLayers, config.width, config.height)
        samplePixelChange(System.nanoTime())

        core.setPresentationTime(surface, presentationNs)
        if (!core.swapBuffers(surface)) {
            swapFailures++
            consecutiveSwapFailures++
            diag("swapBuffers encoder gagal")
        } else {
            consecutiveSwapFailures = 0
        }
    }

    private fun teardownSession() {
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
        previewEglSurface = null
        programEglSurface = null
        previewTarget = null
        programTarget = null
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
            onState(RecordingState.Error(error.message ?: "Gagal memulai tangkap layar"))
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
        val screenDisplay: com.rout.air.core.ScreenDisplay? = null,
    )

    private data class CachedTexture(
        val signature: String,
        val textureId: Int,
        val width: Int,
        val height: Int,
    )

    private companion object {
        const val TAG = "RoutAirPipeline"
        const val STALL_TIMEOUT_NS = 5_000_000_000L
        const val PREVIEW_STALL_NS = 3_000_000_000L
        const val WATCHDOG_INTERVAL_MS = 1_000L

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
