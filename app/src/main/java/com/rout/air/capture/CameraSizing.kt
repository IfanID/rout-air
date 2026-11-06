package com.rout.air.capture

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Size
import android.view.Display
import android.view.Surface
import kotlin.math.abs

/**
 * Penentu ukuran kamera, dipakai bersama oleh pipeline dan editor.
 *
 * Mengikuti cara OBS: sumber kamera punya ukuran intrinsik tetap (resolusi
 * setelah rotasi), lalu transform (skala/posisi) diterapkan di atasnya.
 */
data class CameraSize(
    /** Ukuran tekstur mentah yang dikirim kamera (biasanya landscape). */
    val texture: Size,
    /** Ukuran intrinsik setelah rotasi; ini yang dipakai editor & pipeline. */
    val visual: Size,
    /** True bila tekstur perlu diputar 90/270 derajat. */
    val rotated: Boolean,
)

object CameraSizing {

    /** Rotasi layar saat ini (Surface.ROTATION_0..3). */
    fun displayRotation(context: Context): Int = runCatching {
        val manager = context.getSystemService(android.hardware.display.DisplayManager::class.java)
        manager?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation
    }.getOrNull() ?: Surface.ROTATION_0

    /** True bila kamera perlu diputar 90/270 derajat relatif layar. */
    fun isRotated(
        characteristics: CameraCharacteristics,
        displayRotation: Int,
    ): Boolean {
        val sensor = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            ?: CameraCharacteristics.LENS_FACING_BACK
        val degrees = if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
            (sensor + displayRotation * 90) % 360
        } else {
            (sensor - displayRotation * 90 + 360) % 360
        }
        return degrees == 90 || degrees == 270
    }

    /**
     * Pilih ukuran tekstur kamera terdekat dengan aspek kotak sumber.
     *
     * [targetAspect] adalah aspek yang diinginkan **untuk tekstur mentah**,
     * bukan untuk ukuran visual. Pemanggil membalik aspek bila kamera
     * dirotasi. [heightCap] membatasi tinggi (0 = batas bawaan 1080).
     */
    fun pickTextureSize(
        sizes: Array<out Size>,
        targetAspect: Float,
        heightCap: Int = 0,
    ): Size? {
        if (sizes.isEmpty()) return null
        val capped = sizes.filter { size ->
            if (heightCap > 0) {
                size.height <= heightCap && size.width <= 1920
            } else {
                size.width <= 1920 && size.height <= 1080
            }
        }
        val pool = capped.ifEmpty { sizes.toList() }
        return pool.minByOrNull { size ->
            abs(size.width.toFloat() / size.height - targetAspect)
        }
    }

    /**
     * Baca karakteristik kamera dan tentukan ukuran tekstur + intrinsik.
     * Tidak membuka kamera, jadi aman dipanggil dari UI.
     */
    fun resolve(
        context: Context,
        facing: Int,
        cameraId: String?,
        canvasWidth: Int,
        canvasHeight: Int,
        heightCap: Int = 0,
    ): CameraSize? {
        val manager = context.getSystemService(CameraManager::class.java) ?: return null
        val id = cameraIdFor(manager, facing, cameraId) ?: return null
        val characteristics = runCatching { manager.getCameraCharacteristics(id) }.getOrNull()
            ?: return null
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return null
        val sizes = map.getOutputSizes(android.graphics.SurfaceTexture::class.java).orEmpty()
        val rotated = isRotated(characteristics, displayRotation(context))
        // Agar ukuran visual mendekati kotak kanvas, aspek tekstur mentah
        // harus dibalik saat kamera dirotasi.
        val targetAspect = if (rotated) {
            canvasHeight.toFloat() / canvasWidth.coerceAtLeast(1)
        } else {
            canvasWidth.toFloat() / canvasHeight.coerceAtLeast(1)
        }
        val texture = pickTextureSize(sizes, targetAspect, heightCap) ?: return null
        val visual = if (rotated) Size(texture.height, texture.width) else texture
        return CameraSize(texture = texture, visual = visual, rotated = rotated)
    }

    fun cameraIdFor(manager: CameraManager, facing: Int, preferred: String?): String? {
        if (!preferred.isNullOrBlank() && preferred in manager.cameraIdList) return preferred
        val wanted = if (facing == 1) {
            CameraCharacteristics.LENS_FACING_FRONT
        } else {
            CameraCharacteristics.LENS_FACING_BACK
        }
        return manager.cameraIdList.firstOrNull { id ->
            runCatching {
                manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == wanted
            }.getOrDefault(false)
        } ?: manager.cameraIdList.firstOrNull()
    }
}
