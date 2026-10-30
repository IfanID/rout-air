package com.rout.air.capture

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.util.Log
import android.view.Surface

/**
 * Menyalin layar ke sebuah Surface lewat MediaProjection yang sudah dibuat
 * pemanggil. Kepemilikan MediaProjection (callback dan stop) ada di
 * RecordingPipeline supaya token yang sama bisa dipakai untuk audio internal.
 *
 * Ukuran mengikuti layar fisik, bukan ukuran video, supaya isi tangkapan
 * selalu tegak walaupun HP diputar di tengah rekaman. Penyesuaian ke ukuran
 * video dilakukan di GlCompositor.
 */
class ScreenCapture(private val projection: MediaProjection) {
    private var virtualDisplay: VirtualDisplay? = null
    private var surface: Surface? = null
    private var width = 0
    private var height = 0
    private var densityDpi = 0

    fun start(width: Int, height: Int, densityDpi: Int, surface: Surface) {
        this.surface = surface
        this.width = width
        this.height = height
        this.densityDpi = densityDpi
        virtualDisplay = projection.createVirtualDisplay(
            "RoutAir",
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            null,
            null,
        )
    }

    /**
     * Menyesuaikan ukuran tangkapan saat layar berputar. Bila perangkat
     * menolak resize, VirtualDisplay dibuat ulang pada surface yang sama.
     */
    fun resize(newWidth: Int, newHeight: Int, newDensityDpi: Int): Boolean {
        if (newWidth <= 0 || newHeight <= 0) return false
        if (newWidth == width && newHeight == height) return true

        val display = virtualDisplay ?: return false
        val resized = runCatching { display.resize(newWidth, newHeight, newDensityDpi) }.isSuccess
        if (!resized) {
            Log.w(TAG, "resize gagal, membuat ulang VirtualDisplay")
            val target = surface ?: return false
            runCatching { display.release() }
            virtualDisplay = runCatching {
                projection.createVirtualDisplay(
                    "RoutAir",
                    newWidth,
                    newHeight,
                    newDensityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    target,
                    null,
                    null,
                )
            }.getOrNull()
            if (virtualDisplay == null) return false
        }

        width = newWidth
        height = newHeight
        densityDpi = newDensityDpi
        return true
    }

    fun stop() {
        runCatching { virtualDisplay?.release() }
            .onFailure { Log.w(TAG, "Gagal melepas VirtualDisplay: ${it.message}") }
        virtualDisplay = null
        surface = null
    }

    private companion object {
        const val TAG = "RoutAirScreen"
    }
}
