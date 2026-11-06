package com.rout.air.ui.components

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.rout.air.output.recording.CaptureEngine

/**
 * Surface pratinjau langsung dari pipeline (scene hasil edit, termasuk kamera).
 *
 * Dipakai saat sesi tangkap aktif agar editor menampilkan gambar nyata tanpa
 * membuka kamera kedua.
 */
@Composable
internal fun PreviewSurface(modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceView(ctx).apply {
                setSecure(true)
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) = attach(holder)

                    override fun surfaceChanged(
                        holder: SurfaceHolder,
                        format: Int,
                        width: Int,
                        height: Int,
                    ) = attach(holder)

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        CaptureEngine.attachPreview(null, 0, 0)
                    }

                    private fun attach(holder: SurfaceHolder) {
                        val frame = holder.surfaceFrame
                        if (frame.width() > 0 && frame.height() > 0) {
                            CaptureEngine.attachPreview(
                                holder.surface,
                                frame.width(),
                                frame.height(),
                            )
                        }
                    }
                })
            }
        },
        onRelease = { CaptureEngine.attachPreview(null, 0, 0) },
    )
}
