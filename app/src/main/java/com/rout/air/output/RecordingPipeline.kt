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
import com.rout.air.core.CaptureConfig
import com.rout.air.core.ContentMode
import com.rout.air.core.RecordingState
import com.rout.air.data.DiagLog
import com.rout.air.encode.InternalAudioEncoder
import com.rout.air.encode.Mp4Writer
import com.rout.air.encode.OutputTarget
import com.rout.air.encode.VideoEncoder
import com.rout.air.engine.EglCore
import com.rout.air.engine.GlCompositor
import com.rout.air.engine.GlUtil
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
                stop()
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
            if (lastFrame > 0 && now - lastFrame > STALL_TIMEOUT_NS) {
                diag("watchdog: frame berhenti")
                stopReason = "tangkapan layar berhenti"
                stop()
                return
            }
            // Frame tetap datang tetapi encoder tidak menghasilkan apa pun.
            if (lastEncoded > 0 && now - lastEncoded > STALL_TIMEOUT_NS) {
                diag("watchdog: encoder macet")
                stopReason = "encoder video macet"
                stop()
                return
            }
            if (consecutiveSwapFailures > 30) {
                diag("watchdog: surface encoder gagal")
                stopReason = "surface encoder gagal"
                stop()
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

    fun start() {
        onState(RecordingState.Preparing)
        glHandler.post {
            try {
                setup()
            } catch (error: Throwable) {
                fail(error)
            }
        }
    }

    fun stop() {
        if (teardownStarted) return
        teardownStarted = true
        running = false
        // Audio dipotong tepat di frame video terakhir supaya durasinya tidak
        // lebih panjang dari gambar.
        val stopPtsUs = if (hasEncodedFrame) lastEncodedNs / 1_000L else Long.MAX_VALUE
        // Audio dihentikan di luar thread GL supaya tidak menahan swapBuffers
        // terakhir; video baru ditutup setelah semua frame masuk encoder.
        Thread({
            runCatching { audioEncoder?.finish(stopPtsUs) }
                .onFailure { Log.w(TAG, "Audio gagal dihentikan: ${it.message}") }
            glHandler.post { teardown() }
        }, "rout-stop").start()
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

    private fun setup() {
        DiagLog.reset()
        diag("setup: video=${config.width}x${config.height} fps=${config.fps} mode=${config.contentMode}")

        val manager = context.getSystemService(MediaProjectionManager::class.java)
            ?: error("MediaProjectionManager tidak tersedia")
        val mediaProjection = manager.getMediaProjection(resultCode, projectionData)
            ?: error("Izin tangkap layar tidak valid")
        mediaProjection.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
        projection = mediaProjection

        val output = OutputTarget.create(context)
        target = output
        outputUri = output.uri.toString()

        val writer = Mp4Writer(output)
        muxer = writer

        val wantsAudio = config.recordAudio && hasAudioPermission()
        writer.expectTracks(if (wantsAudio) 2 else 1)
        if (wantsAudio) {
            audioEncoder = try {
                InternalAudioEncoder(
                    projection = mediaProjection,
                    sampleRate = config.sampleRate,
                    channels = config.channelCount,
                    bitrate = config.audioBitrate,
                    startTimeNs = startTimeNs,
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
                    if (!teardownStarted) {
                        stopReason = "encoder video berhenti: ${error.message}"
                        stop()
                    }
                }
            },
            onFrameEncoded = {
                encodedOutputs++
                lastEncodedWallNs = System.nanoTime()
            },
        )
        videoEncoder = encoder

        val core = EglCore()
        egl = core
        val encoderSurface = core.createWindowSurface(encoder.inputSurface)
        encoderEglSurface = encoderSurface
        // Konteks harus aktif sebelum membuat tekstur/SurfaceTexture.
        core.makeCurrent(encoderSurface)

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

        // Thread preview dengan konteks berbagi: tekstur frame ikut terpakai.
        startPreviewThread(core)

        running = true
        lastFrameWallNs = System.nanoTime()
        lastEncodedWallNs = System.nanoTime()
        lastPreviewWallNs = System.nanoTime()
        diag("mulai: audio=$audioActive layar=${sourceWidth}x$sourceHeight")
        glHandler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS)
        glHandler.postDelayed(pacer, frameIntervalMs)
        onState(RecordingState.Recording(System.currentTimeMillis(), outputUri, audioActive))
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

    private fun onFrameAvailable() {
        if (!running) return
        val core = egl ?: return
        val frameTexture = surfaceTexture ?: return
        val encoderSurface = encoderEglSurface

        // updateTexImage butuh konteks EGL aktif pada thread ini.
        if (encoderSurface != null) {
            try {
                core.makeCurrent(encoderSurface)
            } catch (error: IllegalStateException) {
                Log.w(TAG, "Konteks EGL tidak aktif: ${error.message}")
                return
            }
        }

        try {
            frameTexture.updateTexImage()
            frameTexture.getTransformMatrix(texMatrix)
        } catch (error: RuntimeException) {
            Log.w(TAG, "Frame layar tidak bisa dibaca: ${error.message}")
            return
        }
        lastFrameWallNs = System.nanoTime()

        val presentationNs = currentPresentationNs()
        capturedFrames++

        // Encode lebih dulu: preview punya thread sendiri, tapi urutan ini
        // tetap menjaga PTS rekaman tidak terpengaruh beban preview.
        if (encoderSurface != null &&
            (!hasEncodedFrame || presentationNs - lastEncodedNs >= frameIntervalNs - FRAME_SLACK_NS)
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

        if (!previewVisible) return
        if (presentationNs - lastPreviewNs < previewIntervalNs) return
        lastPreviewNs = presentationNs
        requestPreview(texMatrix.copyOf(), sourceWidth, sourceHeight)
    }

    /**
     * Waktu presentasi diambil dari jam monotonic, bukan timestamp SurfaceTexture.
     * Timestamp sumber bisa reset/mundur (mis. saat display berganti mode),
     * dan itu membuat frame berhenti dikirim karena dianggap terlalu awal.
     */
    private fun currentPresentationNs(): Long =
        (System.nanoTime() - startTimeNs).coerceAtLeast(0L)

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

    private fun requestPreview(matrix: FloatArray, sourceW: Int, sourceH: Int) {
        val handler = previewHandler ?: return
        if (!previewInFlight.compareAndSet(false, true)) return

        val frame = PreviewFrame(matrix, sourceW, sourceH)
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

        drawPane(core, painter, previewEglSurface, previewWidth, previewHeight, frame)
        drawPane(core, painter, programEglSurface, programWidth, programHeight, frame)

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
    ) {
        if (surface == null || surfaceWidth <= 0 || surfaceHeight <= 0) return

        try {
            core.makeCurrent(surface)
        } catch (error: IllegalStateException) {
            Log.w(TAG, "Surface render tidak valid: ${error.message}")
            return
        }

        val canvasRect = computeViewport(
            surfaceWidth,
            surfaceHeight,
            config.width,
            config.height,
        )
        val contentRect = computeContentRect(
            config.width,
            config.height,
            frame.sourceWidth,
            frame.sourceHeight,
            config.contentMode,
        )
        val viewport = mapRect(contentRect, canvasRect, config.width, config.height)

        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        // Scissor membatasi konten ke area kanvas; viewport bisa lebih besar
        // dari kanvas saat mode Penuh (crop).
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        GLES20.glScissor(canvasRect[0], canvasRect[1], canvasRect[2], canvasRect[3])
        GLES20.glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
        painter.draw(textureId, frame.matrix)
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

        val viewport = computeContentRect(
            config.width,
            config.height,
            sourceWidth,
            sourceHeight,
            config.contentMode,
        )

        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glViewport(0, 0, config.width, config.height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
        compositor?.draw(textureId, texMatrix)

        core.setPresentationTime(surface, presentationNs)
        if (!core.swapBuffers(surface)) {
            swapFailures++
            consecutiveSwapFailures++
            diag("swapBuffers encoder gagal")
        } else {
            consecutiveSwapFailures = 0
        }
    }

    private fun teardown() {
        running = false
        glHandler.removeCallbacks(watchdog)
        glHandler.removeCallbacks(pacer)

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

        if (saved) {
            val reason = stopReason
            if (reason == null) {
                onState(RecordingState.Idle)
            } else {
                onState(
                    RecordingState.Error("Rekaman tersimpan, berhenti lebih awal: $reason"),
                )
            }
        } else {
            val detail = stopReason?.let { " • $it" }.orEmpty()
            onState(
                RecordingState.Error(
                    "Rekaman gagal disimpan (frame $capturedFrames, video $encodedOutputs)$detail",
                ),
            )
        }
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
            onState(RecordingState.Error(error.message ?: "Gagal memulai rekaman"))
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
