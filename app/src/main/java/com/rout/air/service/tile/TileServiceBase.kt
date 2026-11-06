package com.rout.air.service.tile

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import com.rout.air.MainActivity
import com.rout.air.core.model.RecordingState
import com.rout.air.output.recording.CaptureEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Dasar tile pengaturan cepat: mengikuti status rekaman selama tile terlihat
 * dan menyediakan jalan membuka aplikasi saat aksi belum bisa dijalankan.
 */
abstract class TileServiceBase : TileService() {

    private var listenScope: CoroutineScope? = null

    /** Perbarui tampilan tile sesuai status rekaman terbaru. */
    protected abstract fun updateTile(state: RecordingState)

    override fun onStartListening() {
        super.onStartListening()
        updateTile(CaptureEngine.state.value)
        listenScope?.cancel()
        listenScope = CoroutineScope(Dispatchers.Main.immediate).also { scope ->
            scope.launch {
                CaptureEngine.state.collect { updateTile(it) }
            }
        }
    }

    override fun onStopListening() {
        listenScope?.cancel()
        listenScope = null
        super.onStopListening()
    }

    protected fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
