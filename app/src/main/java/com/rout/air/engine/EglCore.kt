package com.rout.air.engine

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.EGLExt
import android.view.Surface

/**
 * Konteks EGL untuk satu thread.
 *
 * [shareWith] membuat konteks kedua yang berbagi tekstur dengan konteks utama.
 * Ini dipakai thread preview: kalau preview macet (mis. aplikasi ke background
 * sehingga SurfaceView berhenti mengambil buffer), thread encoder tetap bisa
 * merekam karena konteksnya terpisah.
 *
 * Display EGL sengaja tidak pernah di-terminate: konteks preview bisa masih
 * tertahan di dalam eglSwapBuffers saat rekaman berhenti, dan terminate akan
 * merusak konteks tersebut. Display EGL bersifat proses, bukan per rekaman.
 */
class EglCore(shareWith: EglCore? = null) {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null

    init {
        if (shareWith != null) {
            // Pakai display dan config milik konteks utama agar teksturnya bisa dibagi.
            display = shareWith.displayHandle()
            config = shareWith.configHandle()
        } else {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display != EGL14.EGL_NO_DISPLAY) { "Display EGL tidak tersedia" }

            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) {
                "eglInitialize gagal"
            }

            val attributes = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                // Wajib agar surface bisa dipakai sebagai input MediaCodec.
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE,
            )

            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            val chosen = EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0)
            check(chosen && count[0] > 0) { "Konfigurasi EGL tidak cocok" }
            config = configs[0]
        }

        val contextAttributes = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
            EGL14.EGL_NONE,
        )
        context = EGL14.eglCreateContext(
            display,
            checkNotNull(config),
            shareWith?.contextHandle() ?: EGL14.EGL_NO_CONTEXT,
            contextAttributes,
            0,
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext gagal" }
    }

    internal fun displayHandle(): EGLDisplay = display

    internal fun configHandle(): EGLConfig = checkNotNull(config)

    internal fun contextHandle(): EGLContext = context

    fun createWindowSurface(surface: Surface): EGLSurface {
        val attributes = intArrayOf(EGL14.EGL_NONE)
        val eglSurface = EGL14.eglCreateWindowSurface(
            display,
            checkNotNull(config),
            surface,
            attributes,
            0,
        )
        check(eglSurface != null && eglSurface != EGL14.EGL_NO_SURFACE) {
            "eglCreateWindowSurface gagal"
        }
        return eglSurface
    }

    fun makeCurrent(surface: EGLSurface) {
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) {
            "eglMakeCurrent gagal"
        }
    }

    /**
     * Mengaktifkan konteks tanpa window surface. Diperlukan saat pratinjau
     * berjalan tanpa encoder, misalnya untuk `updateTexImage` dan kompilasi
     * shader di thread GL.
     */
    fun makeCurrentNoSurface() {
        check(
            EGL14.eglMakeCurrent(
                display,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                context,
            ),
        ) { "eglMakeCurrent (tanpa surface) gagal" }
    }

    fun makeNothingCurrent() {
        EGL14.eglMakeCurrent(
            display,
            EGL14.EGL_NO_SURFACE,
            EGL14.EGL_NO_SURFACE,
            EGL14.EGL_NO_CONTEXT,
        )
    }

    fun swapBuffers(surface: EGLSurface): Boolean = EGL14.eglSwapBuffers(display, surface)

    /** Timestamp frame untuk encoder; harus dipanggil sebelum swapBuffers. */
    fun setPresentationTime(surface: EGLSurface, nanoseconds: Long) {
        EGLExt.eglPresentationTimeANDROID(display, surface, nanoseconds)
    }

    fun releaseSurface(surface: EGLSurface) {
        if (surface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(display, surface)
        }
    }

    fun release() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            makeNothingCurrent()
            if (context != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(display, context)
            }
            EGL14.eglReleaseThread()
        }
        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
        config = null
    }

    private companion object {
        const val EGL_RECORDABLE_ANDROID = 0x3142
        const val EGL_OPENGL_ES3_BIT = 0x0040
    }
}
