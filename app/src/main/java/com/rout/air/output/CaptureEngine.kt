package com.rout.air.output

import android.content.Context
import android.content.Intent
import android.view.Surface
import com.rout.air.core.CaptureConfig
import com.rout.air.core.RecordingState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    fun attachPreview(surface: Surface?, width: Int, height: Int) {
        // Pelepasan dengan surface tertentu hanya berlaku bila surface itu
        // memang yang sedang terpasang; mencegah surface lama menghapus
        // surface baru saat berpindah halaman.
        if (surface != null && (width <= 0 || height <= 0)) {
            if (surface !== previewSurface) return
        }
        previewSurface = surface
        previewWidth = width
        previewHeight = height

        // Pipeline yang sedang jalan harus ikut pindah ke surface baru,
        // karena SurfaceView membuat surface baru setelah app kembali dari background.
        synchronized(lock) { pipeline }?.updatePreview(surface, width, height)
    }

    /** Panel Program di Studio Mode: keluaran yang benar-benar direkam. */
    fun attachProgram(surface: Surface?, width: Int, height: Int) {
        if (surface != null && (width <= 0 || height <= 0)) {
            if (surface !== programSurface) return
        }
        programSurface = surface
        programWidth = width
        programHeight = height

        synchronized(lock) { pipeline }?.updateProgram(surface, width, height)
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
                synchronized(lock) {
                    if (pipeline === created) pipeline = null
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
     * ini hanya memberi jalan mencoba lagi lewat tombol rekam.
     */
    fun reportPermissionCancelled() {
        if (!_sessionActive.value) {
            _state.value = RecordingState.Error(
                "Izin tangkap layar dibatalkan • tekan rekam untuk mencoba lagi",
            )
        }
    }
}
