package com.rout.air.service

import android.content.Intent
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.rout.air.R
import com.rout.air.core.RecordingState
import com.rout.air.data.SettingsStore
import com.rout.air.output.CaptureEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Tile pengaturan cepat untuk menyimpan replay buffer tanpa membuka overlay.
 *
 * Tile hanya aktif saat sedang merekam dan replay buffer memang dinyalakan di
 * Setelan; di luar itu mengetuk tile membuka aplikasi ke halaman Setelan.
 */
class ReplayTileService : TileService() {

    private var listenScope: CoroutineScope? = null

    private val replayEnabled: Boolean
        get() = SettingsStore(this).load().replayBufferEnabled

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
        val recording = CaptureEngine.state.value is RecordingState.Recording
        if (recording && replayEnabled) {
            CaptureEngine.saveReplay()
        } else {
            // Tanpa rekaman berjalan tidak ada isi buffer; arahkan pengguna
            // ke aplikasi daripada menampilkan tile yang diam saja.
            openApp()
        }
    }

    private fun updateTile(state: RecordingState) {
        val tile = qsTile ?: return
        val active = state is RecordingState.Recording && replayEnabled
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.qs_tile_replay)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_replay)
        tile.updateTile()
    }

    private fun openApp() {
        val intent = Intent(this, com.rout.air.MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = android.app.PendingIntent.getActivity(
                this,
                0,
                intent,
                android.app.PendingIntent.FLAG_IMMUTABLE or
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
