package com.rout.air.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rout.air.R
import com.rout.air.data.OverlayInfoItem
import com.rout.air.data.OverlayInfoStore

/**
 * Panel info di overlay: daftar item yang bisa dicentang. Item terpilih
 * tampil sebagai chip di pojok kanan atas layar, juga saat panel ditutup.
 */
@Composable
internal fun OverlayInfoPanel(showReconnect: Boolean = true) {
    val selected by OverlayInfoStore.items.collectAsState()
    val rows = buildList {
        add(OverlayInfoItem.Fps to stringResource(R.string.overlay_info_fps))
        add(OverlayInfoItem.Drops to stringResource(R.string.overlay_info_drops))
        if (showReconnect) {
            add(OverlayInfoItem.Reconnect to stringResource(R.string.overlay_info_reconnect))
        }
        add(OverlayInfoItem.Resolution to stringResource(R.string.overlay_info_resolution))
        add(OverlayInfoItem.Scene to stringResource(R.string.overlay_info_scene))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = stringResource(R.string.overlay_info_title),
            color = Color.White,
            fontSize = 11.sp,
        )
        Text(
            text = stringResource(R.string.overlay_info_desc),
            color = Color(0xFF8A94A0),
            fontSize = 9.sp,
        )
        rows.forEach { (item, label) ->
            val on = item in selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { OverlayInfoStore.toggle(item) }
                    .padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (on) "●" else "○",
                    color = if (on) Color(0xFF00E5FF) else Color(0xFF8A94A0),
                    fontSize = 10.sp,
                    modifier = Modifier.padding(end = 6.dp),
                )
                Text(
                    text = label,
                    color = if (on) Color.White else Color(0xFFB8C2CC),
                    fontSize = 11.sp,
                )
            }
        }
    }
}
