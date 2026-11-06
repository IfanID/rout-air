package com.rout.air.service.tile

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import com.rout.air.R
import com.rout.air.core.model.RecordingState
import com.rout.air.data.settings.SettingsStore
import com.rout.air.output.recording.CaptureEngine

/**
 * Tile pengaturan cepat untuk menyimpan replay buffer tanpa membuka overlay.
 *
 * Tile hanya aktif saat sedang merekam dan replay buffer memang dinyalakan di
 * Setelan; di luar itu mengetuk tile membuka aplikasi.
 */
class ReplayTileService : TileServiceBase() {

    private val replayEnabled: Boolean
        get() = SettingsStore(this).load().replayBufferEnabled

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

    protected override fun updateTile(state: RecordingState) {
        val tile = qsTile ?: return
        val active = state is RecordingState.Recording && replayEnabled
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.qs_tile_replay)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_replay)
        tile.updateTile()
    }
}
