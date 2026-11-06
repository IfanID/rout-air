package com.rout.air.service.tile

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import com.rout.air.R
import com.rout.air.core.model.RecordingState
import com.rout.air.output.recording.CaptureEngine

/**
 * Quick Settings tile: mulai/hentikan rekaman tanpa membuka aplikasi.
 *
 * Izin MediaProjection tidak bisa diminta dari tile, jadi saat sesi belum
 * aktif tile membuka aplikasi (dialog izin otomatis muncul di sana).
 */
class RecordTileService : TileServiceBase() {

    override fun onClick() {
        super.onClick()
        when (val state = CaptureEngine.state.value) {
            is RecordingState.Recording -> CaptureEngine.stopRecording()
            RecordingState.Previewing -> CaptureEngine.startRecording()
            else -> openApp()
        }
        updateTile(CaptureEngine.state.value)
    }

    protected override fun updateTile(state: RecordingState) {
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
}
