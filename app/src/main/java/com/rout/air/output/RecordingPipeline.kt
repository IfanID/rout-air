package com.rout.air.output

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Surface
import androidx.core.content.ContextCompat
import com.rout.air.capture.ScreenCapture
import com.rout.air.core.Align
import com.rout.air.core.BlendMode
import com.rout.air.core.CaptureConfig
import com.rout.air.core.ContentMode
import com.rout.air.core.Crop
import com.rout.air.core.RecordingState
import com.rout.air.core.SceneCollection
import com.rout.air.core.SceneSource
import com.rout.air.core.SourceType
import com.rout.air.core.Transform
import com.rout.air.data.DiagLog
import com.rout.air.data.SceneManager
import com.rout.air.encode.InternalAudioEncoder
import com.rout.air.encode.Mp4Writer
import com.rout.air.encode.OutputTarget
import com.rout.air.encode.VideoEncoder
import com.rout.air.engine.EglCore
import com.rout.air.engine.GlCompositor
import com.rout.air.engine.GlUtil
import com.rout.air.engine.SourceBitmap
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
    private val config: CaptureConfig,
    previewSurface: Surface?,
    previewWidth: Int,
    previewHeight: Int,
    programSurface: Surface? = null,
    programWidth: Int = 0,
    programHeight: Int = 0,
    private val autoRecord: Boolean = false,
    private val onSessionEnd: () -> Unit = {},
    private val onState: (RecordingState) -> Unit,
) {
    private val glThread = HandlerThread("rout-gl").apply { start() }
    private val glHandler = Handler(glThread.looper)
    private val startTimeNs = System.nanoTime()
    private val frameIntervalNs = 1_000_000_000L / config.fps
    private val frameIntervalMs = (1_000L / config.fps).coerceAtLeast(1L)
    private val previewIntervalNs = 1_000_000_000L / 60
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
    private var audioEncoder: InternalAudioEncoder? = null
    private var muxer: Mp4Writer? = null
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
    private var capturedFrames = 0
    private var submittedFrames = 0
    private var pacedFrames = 0
    private var pacerCapturedFrames = 0
    private var swapFailures = 0
    private var consecutiveSwapFailures = 0
    private var watchdogTicks = 0
    private var teardownStarted = false
    private var stopReason: String? = null

    @Volatile
    private var recordingActive = false
    private var recordingStopping = false

    /** Titik nol PTS rekaman, terpisah dari waktu sesi pratinjau. */
    @Volatile
    private var recordingStartNs = 0L

    @Volatile
    private var lastFrameWallNs = 0L

    @Volatile
    private var lastEncodedWallNs = 0L

    /** Jumlah buffer yang benar-benar keluar dari encoder (di-set dari thread encoder). */
    @Volatile
    private var encodedOutputs = 0

    @Volatile
    private var previewVisible = true

    /** Satu frame preview menunggu; mencegah antrean menumpuk saat preview lambat. */
    private val previewInFlight = AtomicBoolean(false)

    @Volatile
    private var lastPreviewWallNs = 0L

    @Volatile
    private var previewSwaps = 0

    @Volatile
    private var running = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            if (!teardownStarted) {
                Log.i(TAG, "MediaProjection dihentikan sistem")
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
                        "audio=$audioActive",
                )
            }
            if (recordingActive && lastFrame > 0 && now - lastFrame > STALL_TIMEOUT_NS) {
                diag("watchdog: frame berhenti")
                stopReason = "tangkapan layar berhenti"
                stopRecording()
                return
            }
            // Frame tetap datang tetapi encoder tidak menghasilkan apa pun.
            if (recordingActive && lastEncoded > 0 && now - lastEncoded > STALL_TIMEOUT_NS) {
                diag("watchdog: encoder macet")
                stopReason = "encoder video macet"
                stopRecording()
                return
            }
            if (recordingActive && consecutiveSwapFailures > 30) {
                diag("watchdog: surface encoder gagal")
                stopReason = "surface encoder gagal"
                stopRecording()
                return
            }
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

            if (!recordingActive) {
                // Saat hanya pratinjau, tidak ada yang perlu di-encode.
                glHandler.postDelayed(this, frameIntervalMs)
                return
            }

            val core = egl
            val encoderSurface = encoderEglSurface
            if (core != null && encoderSurface != null) {
                if (capturedFrames == pacerCapturedFrames) {
                    // Tidak ada frame baru sejak tick sebelumnya.
                    if (hasEncodedFrame) {
                        val pts = maxOf(currentPresentationNs(), lastEncodedNs + 1_000L)
                        lastEncodedNs = pts
                        renderToEncoder(core, encoderSurface, pts)
                        submittedFrames++
                        pacedFrames++
                    }
                } else {
                    pacerCapturedFrames = capturedFrames
                }
            }
            glHandler.postDelayed(this, frameIntervalMs)
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
    fun startRecording() {
        glHandler.post {
            if (teardownStarted || recordingActive) return@post
            try {
                beginRecording()
            } catch (error: Throwable) {
                Log.e(TAG, "Gagal memulai rekaman", error)
                diag("rekam gagal: ${error.message}")
                abortRecording(error.message ?: "Gagal memulai rekaman")
            }
        }
    }

    /** Hentikan penulisan file; pratinjau tetap berjalan. */
    fun stopRecording() {
        if (!recordingActive || recordingStopping) return
        recordingStopping = true
        // Audio dipotong tepat di frame video terakhir supaya durasinya tidak
        // lebih panjang dari gambar.
        val stopPtsUs = if (hasEncodedFrame) lastEncodedNs / 1_000L else Long.MAX_VALUE
        // Audio dihentikan di luar thread GL supaya tidak menahan swapBuffers
        // terakhir; video baru ditutup setelah semua frame masuk encoder.
        Thread({
            runCatching { audioEncoder?.finish(stopPtsUs) }
                .onFailure { Log.w(TAG, "Audio gagal dihentikan: ${it.message}") }
            glHandler.post { endRecording() }
        }, "rout-stop").start()
    }

    /** Menutup sesi sepenuhnya: pratinjau berhenti dan projection dilepas. */
    fun closeSession() {
        if (teardownStarted) return
        teardownStarted = true
        running = false
        val stopPtsUs = if (hasEncodedFrame) lastEncodedNs / 1_000L else Long.MAX_VALUE
        Thread({
            runCatching { audioEncoder?.finish(stopPtsUs) }
                .onFailure { Log.w(TAG, "Audio gagal dihentikan: ${it.message}") }
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
    }

    /** Surface panel Program (Studio Mode); dirender di thread preview yang sama. */
    fun updateProgram(surface: Surface?, width: Int, height: Int) {
        previewHandler?.post {
            val core = previewEgl
            releaseSurface(core, programEglSurface)
            programEglSurface = null
            programTarget = surface
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
        onState(RecordingState.Previewing)

        if (autoRecord) startRecording()
    }

    /** Menyiapkan encoder dan file output memakai sesi yang sudah berjalan. */
    private fun beginRecording() {
        val core = egl ?: error("Sesi tangkap layar belum siap")
        val activeProjection = projection ?: error("Izin tangkap layar tidak valid")

        diag("rekam: mulai")
        val output = OutputTarget.create(context)
        target = output
        outputUri = output.uri.toString()

        val writer = Mp4Writer(output)
        muxer = writer

        recordingStartNs = System.nanoTime()

        val wantsAudio = config.recordAudio && hasAudioPermission()
        writer.expectTracks(if (wantsAudio) 2 else 1)
        if (wantsAudio) {
            audioEncoder = try {
                InternalAudioEncoder(
                    projection = activeProjection,
                    sampleRate = config.sampleRate,
                    channels = config.channelCount,
                    bitrate = config.audioBitrate,
                    startTimeNs = recordingStartNs,
                    muxer = writer,
                ).also { it.start() }
            } catch (error: Throwable) {
                Log.w(TAG, "Audio internal dinonaktifkan: ${error.message}")
                writer.expectTracks(1)
                null
            }
        }
        audioActive = audioEncoder != null

        val encoder = VideoEncoder(
            width = config.width,
            height = config.height,
            fps = config.fps,
            bitrate = config.videoBitrate,
            muxer = writer,
            onEncoderError = { error ->
                diag("encoder error: ${error.message}")
                glHandler.post {
                    if (recordingActive) {
                        stopReason = "encoder video berhenti: ${error.message}"
                        stopRecording()
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

        // Statistik perekaman dimulai dari nol untuk setiap file.
        submittedFrames = 0
        pacedFrames = 0
        encodedOutputs = 0
        swapFailures = 0
        consecutiveSwapFailures = 0
        pacerCapturedFrames = capturedFrames
        hasEncodedFrame = false
        lastEncodedNs = Long.MIN_VALUE
        lastEncodedWallNs = System.nanoTime()
        recordingStopping = false
        recordingActive = true

        diag("rekam: audio=$audioActive tujuan=$outputUri")
        onState(RecordingState.Recording(System.currentTimeMillis(), outputUri, audioActive))
    }

    /** Dipanggil setelah audio selesai; menutup file tanpa mematikan sesi. */
    private fun endRecording() {
        if (!recordingActive) {
            recordingStopping = false
            return
        }
        recordingActive = false
        glHandler.removeCallbacks(pacer)

        runCatching { videoEncoder?.finish() }
            .onFailure { Log.w(TAG, "Video gagal dihentikan: ${it.message}") }
        videoEncoder = null

        val core = egl
        encoderEglSurface?.let { runCatching { core?.releaseSurface(it) } }
        encoderEglSurface = null

        val saved = runCatching { muxer?.finish() ?: false }.getOrDefault(false)
        muxer = null
        audioEncoder = null
        runCatching { target?.finish(context, saved) }
        target = null

        diag(
            "stop: reason=${stopReason ?: "-"} frames=$capturedFrames submitted=$submittedFrames " +
                "encoded=$encodedOutputs swapFail=$swapFailures pace=$pacedFrames " +
                "preview=$previewSwaps saved=$saved",
        )
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        DiagLog.writeToDownloads(context, "RoutAir_diag_$stamp.txt")

        val reason = stopReason
        stopReason = null
        recordingStopping = false
        hasEncodedFrame = false
        lastEncodedNs = Long.MIN_VALUE
        lastEncodedWallNs = System.nanoTime()
        recordingStartNs = 0L

        if (saved) {
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

    /** Gagal saat menyiapkan encoder: bersihkan file dan kembali ke pratinjau. */
    private fun abortRecording(message: String) {
        recordingActive = false
        glHandler.removeCallbacks(pacer)
        runCatching { audioEncoder?.finish() }
        audioEncoder = null
        runCatching { videoEncoder?.finish() }
        videoEncoder = null
        val core = egl
        encoderEglSurface?.let { runCatching { core?.releaseSurface(it) } }
        encoderEglSurface = null
        runCatching { muxer?.finish() }
        muxer = null
        runCatching { target?.finish(context, false) }
        target = null
        recordingStopping = false
        recordingStartNs = 0L
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
    }

    /**
     * Menyusun ulang daftar layer program dari snapshot yang sedang mengudara.
     * Dijalankan di thread GL setiap hasil Transisi berubah.
     */
    private fun rebuildLayers() {
        val layers = buildLayers(SceneManager.programCurrent(), "program")
        renderLayers = layers
    }

    /** Menyusun layer pratinjau dari hasil edit yang belum di-transisikan. */
    private fun rebuildPreviewLayers() {
        previewLayers = buildLayers(SceneManager.current(), "pratinjau")
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
            }
        }

        diag("scene $label: ${scene.name} layer=${layers.size}")
        return layers
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
        val width = max(
            1f,
            naturalWidth * (1f - cLeft - cRight) * layer.transform.scaleX,
        )
        val height = max(
            1f,
            naturalHeight * (1f - cTop - cBottom) * layer.transform.scaleY,
        )

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
        var drawWidth = width
        var drawHeight = height

        // Sumber layar mengikuti mode isi (Fit/Crop/Stretch) di dalam kotaknya,
        // sama seperti perilaku M1 sebelum scene berlapis.
        if (layer.type == SourceType.Screen && config.contentMode != ContentMode.Stretch) {
            val rect = computeContentRect(
                width.roundToInt(),
                height.roundToInt(),
                sourceWidth,
                sourceHeight,
                config.contentMode,
            )
            left += rect[0]
            top += rect[1]
            drawWidth = rect[2].toFloat()
            drawHeight = rect[3].toFloat()
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
        }
    }

    private fun onFrameAvailable() {
        if (!running) return
        val core = egl ?: return
        val frameTexture = surfaceTexture ?: return
        val encoderSurface = encoderEglSurface
        val recording = recordingActive

        // updateTexImage butuh konteks EGL aktif pada thread ini. Saat hanya
        // pratinjau (belum ada encoder), konteks diaktifkan tanpa surface.
        try {
            if (recording && encoderSurface != null) {
                core.makeCurrent(encoderSurface)
            } else {
                core.makeCurrentNoSurface()
            }
        } catch (error: IllegalStateException) {
            Log.w(TAG, "Konteks EGL tidak aktif: ${error.message}")
            return
        }

        try {
            frameTexture.updateTexImage()
            frameTexture.getTransformMatrix(texMatrix)
        } catch (error: RuntimeException) {
            Log.w(TAG, "Frame layar tidak bisa dibaca: ${error.message}")
            return
        }
        lastFrameWallNs = System.nanoTime()
        capturedFrames++

        // Jam pratinjau selalu dihitung dari awal sesi, sedangkan PTS rekaman
        // dihitung dari saat tombol rekam ditekan.
        val sessionNs = (System.nanoTime() - startTimeNs).coerceAtLeast(0L)

        if (recording && encoderSurface != null) {
            val presentationNs = if (recordingStartNs > 0L) {
                (System.nanoTime() - recordingStartNs).coerceAtLeast(0L)
            } else {
                sessionNs
            }
            if (!hasEncodedFrame ||
                presentationNs - lastEncodedNs >= frameIntervalNs - FRAME_SLACK_NS
            ) {
                // Jangan bandingkan dengan Long.MIN_VALUE: selisihnya overflow.
                // PTS dibuat selalu naik walau timestamp sumber jitter/duplikat.
                val pts = if (hasEncodedFrame) {
                    maxOf(presentationNs, lastEncodedNs + 1_000L)
                } else {
                    presentationNs
                }
                hasEncodedFrame = true
                lastEncodedNs = pts
                renderToEncoder(core, encoderSurface, pts)
                submittedFrames++
                if (submittedFrames == 1 || submittedFrames % 300 == 0) {
                    Log.i(TAG, "Submit $submittedFrames frame (${pts / 1_000_000} ms)")
                }
            }
        }

        if (!previewVisible) return
        if (sessionNs - lastPreviewNs < previewIntervalNs) return
        lastPreviewNs = sessionNs
        requestPreview(texMatrix.copyOf(), sourceWidth, sourceHeight, renderLayers)
    }

    /**
     * Waktu presentasi diambil dari jam monotonic, bukan timestamp SurfaceTexture.
     * Timestamp sumber bisa reset/mundur (mis. saat display berganti mode),
     * dan itu membuat frame berhenti dikirim karena dianggap terlalu awal.
     */
    private fun currentPresentationNs(): Long {
        val base = if (recordingStartNs > 0L) recordingStartNs else startTimeNs
        return (System.nanoTime() - base).coerceAtLeast(0L)
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
        GLES20.glViewport(0, 0, config.width, config.height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        drawLayers(compositor ?: return, texMatrix, renderLayers, config.width, config.height)

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

        val saved = runCatching { muxer?.finish() ?: false }.getOrDefault(false)
        muxer = null
        audioEncoder = null

        releaseProjection()

        runCatching { target?.finish(context, saved) }
        target = null

        diag(
            "stop: reason=${stopReason ?: "-"} frames=$capturedFrames submitted=$submittedFrames " +
                "encoded=$encodedOutputs swapFail=$swapFailures pace=$pacedFrames " +
                "preview=$previewSwaps saved=$saved",
        )
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        DiagLog.writeToDownloads(context, "RoutAir_diag_$stamp.txt")

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
                runCatching { audioEncoder?.finish() }
                audioEncoder = null
                previewHandler?.post { teardownPreview() }
                previewThread?.quitSafely()
                runCatching { egl?.release() }
                egl = null
                runCatching { muxer?.finish() }
                muxer = null
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
        val active = projection ?: return
        runCatching { active.unregisterCallback(projectionCallback) }
        runCatching { active.stop() }
        projection = null
    }

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

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
    )

    private data class CachedTexture(
        val signature: String,
        val textureId: Int,
        val width: Int,
        val height: Int,
    )

    private companion object {
        const val TAG = "RoutAirPipeline"
        const val FRAME_SLACK_NS = 2_000_000L
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
