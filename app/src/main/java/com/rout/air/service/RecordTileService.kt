package com.rout.air.service

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.rout.air.MainActivity
import com.rout.air.R
import com.rout.air.core.RecordingState
import com.rout.air.output.CaptureEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Quick Settings tile: mulai/hentikan rekaman tanpa membuka aplikasi.
 *
 * Izin MediaProjection tidak bisa diminta dari tile, jadi saat sesi belum
 * aktif tile membuka aplikasi (dialog izin otomatis muncul di sana).
 */
class RecordTileService : TileService() {

    private var listenScope: CoroutineScope? = null

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

    override fun onClick() {
        super.onClick()
        when (val state = CaptureEngine.state.value) {
            is RecordingState.Recording -> CaptureEngine.stopRecording()
            RecordingState.Previewing -> CaptureEngine.startRecording()
            else -> openApp()
        }
        updateTile(CaptureEngine.state.value)
    }

    private fun updateTile(state: RecordingState) {
        val tile = qsTile ?: return
        val recording = state is RecordingState.Recording
        tile.state = if (recording) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(if (recording) R.string.qs_tile_stop else R.string.qs_tile_record)
        tile.icon = Icon.createWithResource(
            this,
            if (recording) R.drawable.ic_qs_stop else R.drawable.ic_qs_record,
        )
        tile.updateTile()
    }

    private fun openApp() {
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
