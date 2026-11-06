package com.rout.air.output.recording

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.widget.Toast
import com.rout.air.core.model.CaptureConfig
import com.rout.air.core.model.LiveConfig
import com.rout.air.core.model.LiveStats
import com.rout.air.core.model.RecordingState
import com.rout.air.core.model.RecordingStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.rout.air.live.rtmp.RtmpSink
import kotlinx.coroutines.flow.asStateFlow

/**
 * Titik masuk tunggal ke pipeline tangkap layar.
 *
 * Sejak M2.1 ada dua tahap: sesi pratinjau dibuka sekali saat aplikasi
 * dibuka, lalu tombol rekam hanya menyalakan encoder. Ini sesuai cara kerja
 * OBS dan menghindari dialog izin setiap kali menekan rekam.
 */
object CaptureEngine {
    private val lock = Any()
    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)

    val state: StateFlow<RecordingState> = _state.asStateFlow()

    private val _stats = MutableStateFlow(RecordingStats())
    val stats: StateFlow<RecordingStats> = _stats.asStateFlow()

    private val _liveStats = MutableStateFlow(LiveStats())
    val liveStats: StateFlow<LiveStats> = _liveStats.asStateFlow()

    // FPS encoder terakhir dari pipeline; dipakai melengkapi statistik live.
    private var lastEncoderFps = 0

    private val _sessionActive = MutableStateFlow(false)
    val sessionActive: StateFlow<Boolean> = _sessionActive.asStateFlow()

    @Volatile
    private var previewSurface: Surface? = null

    @Volatile
    private var previewWidth = 0

    @Volatile
    private var previewHeight = 0

    @Volatile
    private var programSurface: Surface? = null

    @Volatile
    private var programWidth = 0

    @Volatile
    private var programHeight = 0

    private var pipeline: RecordingPipeline? = null

    /** Konteks aplikasi untuk pesan singkat; diisi saat sesi dimulai. */
    @Volatile
    private var appContext: Context? = null

    /** Ukuran kanvas sesi aktif, dipakai overlay untuk rasio pratinjau. */
    @Volatile
    var sessionWidth: Int = 0
        private set
    @Volatile
    var sessionHeight: Int = 0
        private set

    private var appPreviewSurface: Surface? = null
    private var appPreviewWidth = 0
    private var appPreviewHeight = 0

    private var appProgramSurface: Surface? = null
    private var appProgramWidth = 0
    private var appProgramHeight = 0
    private var overlayProgramSurface: Surface? = null

    fun attachPreview(surface: Surface?, width: Int, height: Int) {
        if (surface != null && (width <= 0 || height <= 0)) {
            if (surface !== appPreviewSurface) return
        }
        appPreviewSurface = surface
        appPreviewWidth = width
        appPreviewHeight = height
        previewSurface = surface
        previewWidth = width
        previewHeight = height
        synchronized(lock) { pipeline }?.updatePreview(surface, width, height)
    }

    /** Panel Program di Studio Mode: keluaran yang benar-benar direkam. */
    fun attachProgram(surface: Surface?, width: Int, height: Int) {
        if (surface != null && (width <= 0 || height <= 0)) {
            if (surface !== appProgramSurface) return
        }
        appProgramSurface = surface
        appProgramWidth = width
        appProgramHeight = height
        programSurface = surface
        programWidth = width
        programHeight = height

        synchronized(lock) { pipeline }?.updateProgram(surface, width, height)
    }

    /**
     * Pratinjau overlay yang menampilkan keluaran program (apa yang sedang
     * mengudara), bukan scene hasil edit. Dipakai saat live aktif.
     */
    fun attachOverlayProgram(surface: Surface?, width: Int, height: Int) {
        if (surface != null && (width <= 0 || height <= 0)) {
            if (surface !== overlayProgramSurface) return
        }
        overlayProgramSurface = surface
        // Jalur terpisah: panel Program aplikasi tetap mendapat frame juga.
        synchronized(lock) { pipeline }?.updateOverlayProgram(surface, width, height)
    }

    /** Lepas pratinjau program milik overlay; panel aplikasi tidak terganggu. */
    fun detachOverlayProgram() {
        overlayProgramSurface = null
        synchronized(lock) { pipeline }?.updateOverlayProgram(null, 0, 0)
    }

    /** Membuka sesi pratinjau; belum menulis file. */
    fun startSession(
        context: Context,
        resultCode: Int,
        projectionData: Intent,
        config: CaptureConfig,
        autoRecord: Boolean = false,
    ) {
        val surface: Surface?
        val width: Int
        val height: Int
        val program: Surface?
        val programW: Int
        val programH: Int

        appContext = context.applicationContext
        sessionWidth = config.width
        sessionHeight = config.height
        synchronized(lock) {
            if (pipeline != null) return
            surface = previewSurface
            width = previewWidth
            height = previewHeight
            program = programSurface
            programW = programWidth
            programH = programHeight
        }

        var created: RecordingPipeline? = null
        created = RecordingPipeline(
            context = context.applicationContext,
            resultCode = resultCode,
            projectionData = projectionData,
            config = config,
            previewSurface = surface,
            previewWidth = width,
            previewHeight = height,
            programSurface = program,
            programWidth = programW,
            programHeight = programH,
            autoRecord = autoRecord,
            onSessionEnd = {
                _sessionActive.value = false
                lastEncoderFps = 0
                _liveStats.value = LiveStats()
                synchronized(lock) {
                    if (pipeline === created) pipeline = null
                }
            },
            onStats = { stats ->
                _stats.value = stats
                if (stats.fps > 0) lastEncoderFps = stats.fps
                // FPS encoder dipakai panel live; RtmpSink hanya melaporkan
                // jaringan, jadi angkanya ditempelkan dari pipeline.
                val live = _liveStats.value
                if (live.active) {
                    _liveStats.value = live.copy(fps = lastEncoderFps)
                }
            },
            onReplaySaved = { ok, detail ->
                Handler(Looper.getMainLooper()).post {
                    val message = if (ok) {
                        context.getString(com.rout.air.R.string.replay_saved)
                    } else {
                        detail
                    }
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            },
            onLiveStats = { live ->
                // Snapshot dari RtmpSink tidak memuat FPS; isi dari nilai
                // encoder terakhir supaya label tidak berkedip ke 0.
                _liveStats.value = if (live.active) {
                    live.copy(fps = lastEncoderFps)
                } else {
                    lastEncoderFps = 0
                    live
                }
            },
        ) { newState ->
            _state.value = newState
            if (newState is RecordingState.Idle) {
                _sessionActive.value = false
                synchronized(lock) {
                    if (pipeline === created) pipeline = null
                }
            }
            if (newState !is RecordingState.Recording) {
                _stats.value = RecordingStats()
            }
        }

        synchronized(lock) { pipeline = created }
        _sessionActive.value = true
        created.startSession()
    }

    fun startRecording() {
        synchronized(lock) { pipeline }?.startRecording()
    }

    fun stopRecording() {
        synchronized(lock) { pipeline }?.stopRecording()
    }

    /** Menyimpan isi replay buffer saat ini (tombol di overlay rekaman). */
    fun saveReplay() {
        val active = synchronized(lock) { pipeline }
        val message = if (active == null) {
            appContext?.getString(com.rout.air.R.string.replay_not_active)
        } else {
            null
        }
        if (active != null) {
            active.saveReplay()
        } else {
            appContext?.let { Toast.makeText(it, message, Toast.LENGTH_SHORT).show() }
        }
    }

    fun startLive(config: LiveConfig) {
        synchronized(lock) { pipeline }?.startLive(config)
    }

    /** Menyesuaikan kanvas pratinjau tanpa memulai ulang sesi. */
    fun updateCanvas(width: Int, height: Int) {
        synchronized(lock) { pipeline }?.updateCanvas(width, height)
    }

    fun stopLive() {
        synchronized(lock) { pipeline }?.stopLive()
    }

    /** Hentikan koneksi yang error lalu mulai ulang (dipakai tombol coba lagi). */
    fun restartLive(config: LiveConfig) {
        synchronized(lock) { pipeline }?.let {
            it.stopLive()
            it.startLive(config)
        }
    }

    fun pauseRecording() {
        synchronized(lock) { pipeline }?.pauseRecording()
    }

    fun resumeRecording() {
        synchronized(lock) { pipeline }?.resumeRecording()
    }

    /** Jeda/lanjutkan sesuai status rekaman saat ini (dipakai notifikasi). */
    fun togglePause() {
        when (val state = _state.value) {
            is RecordingState.Recording ->
                if (state.isPaused) resumeRecording() else pauseRecording()

            else -> Unit
        }
    }

    /** Menutup sesi pratinjau sekaligus (dipakai saat aplikasi ke background). */
    fun closeSession() {
        synchronized(lock) { pipeline }?.closeSession()
    }

    /** Preview berhenti digambar saat aplikasi tidak terlihat. */
    fun setPreviewVisible(visible: Boolean) {
        synchronized(lock) { pipeline }?.setPreviewVisible(visible)
    }

    fun clearError() {
        if (_state.value is RecordingState.Error) {
            _state.value = if (_sessionActive.value) {
                RecordingState.Previewing
            } else {
                RecordingState.Idle
            }
        }
    }

    /**
     * Dipanggil saat pengguna membatalkan dialog izin tangkap layar. Sesi
     * tidak pernah dimulai, jadi tidak ada tangkapan yang berjalan; pesan
     * ini mengarahkan ke tombol cadangan di pojok kanan atas.
     */
    fun reportPermissionCancelled() {
        if (!_sessionActive.value) {
            _state.value = RecordingState.Error(
                appContext?.getString(com.rout.air.R.string.capture_permission_cancelled)
                    .orEmpty(),
            )
        }
    }

    /**
     * Aksi rekam/siaran dipanggil tanpa sesi tangkapan. Dialog izin sengaja
     * tidak dibuka dari sini; tombol ikon di pojok kanan atas yang menjadi
     * satu-satunya cara manual meminta izin.
     */
    fun reportPermissionNeeded() {
        if (!_sessionActive.value) {
            _state.value = RecordingState.Error(
                "Izin tangkap layar belum aktif • tekan ikon izin di kanan atas",
            )
        }
    }
}
