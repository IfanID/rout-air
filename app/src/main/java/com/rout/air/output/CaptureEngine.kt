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
 * Titik masuk tunggal ke pipeline rekam. UI dan service berkomunikasi lewat
 * objek ini supaya Surface preview tetap satu proses dan tidak perlu
 * dikirim melalui Intent.
 */
object CaptureEngine {
    private val lock = Any()
    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)

    val state: StateFlow<RecordingState> = _state.asStateFlow()

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
        previewSurface = surface
        previewWidth = width
        previewHeight = height

        // Pipeline yang sedang jalan harus ikut pindah ke surface baru,
        // karena SurfaceView membuat surface baru setelah app kembali dari background.
        synchronized(lock) { pipeline }?.updatePreview(surface, width, height)
    }

    /** Panel Program di Studio Mode: keluaran yang benar-benar direkam. */
    fun attachProgram(surface: Surface?, width: Int, height: Int) {
        programSurface = surface
        programWidth = width
        programHeight = height

        synchronized(lock) { pipeline }?.updateProgram(surface, width, height)
    }

    fun start(
        context: Context,
        resultCode: Int,
        projectionData: Intent,
        config: CaptureConfig,
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
        ) { newState ->
            _state.value = newState
            if (newState is RecordingState.Idle || newState is RecordingState.Error) {
                synchronized(lock) {
                    if (pipeline === created) pipeline = null
                }
            }
        }

        synchronized(lock) { pipeline = created }
        created.start()
    }

    fun stop() {
        val active = synchronized(lock) { pipeline }
        active?.stop()
    }

    /** Preview berhenti digambar saat aplikasi tidak terlihat. */
    fun setPreviewVisible(visible: Boolean) {
        synchronized(lock) { pipeline }?.setPreviewVisible(visible)
    }

    fun clearError() {
        if (_state.value is RecordingState.Error) {
            _state.value = RecordingState.Idle
        }
    }
}
