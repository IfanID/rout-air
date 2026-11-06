package com.rout.air.ui.overlay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import com.rout.air.R
import com.rout.air.data.scene.SceneManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rout.air.data.screensaver.isScreensaverScene

/**
 * Panel daftar scene di overlay Live untuk berpindah program dengan satu
 * ketukan, tanpa membuka aplikasi.
 */
@Composable
internal fun OverlayScenePanel(onPick: (String) -> Boolean) {
    val edit by SceneManager.state.collectAsState()
    val program by SceneManager.program.collectAsState()
    // Panel Adegan hanya berisi scene siaran biasa; scene "Layar Jeda"
    // punya panel sendiri agar kedua fungsi tidak tertukar.
    // Daftar mengikuti koleksi edit supaya tambah/hapus scene langsung terlihat.
    val scenes = (edit ?: program)?.scenes.orEmpty().filterNot { it.isScreensaverScene() }
    val activeId = program?.activeSceneId

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = stringResource(R.string.overlay_scenes),
            color = Color.White,
            fontSize = 11.sp,
        )
        if (scenes.isEmpty()) {
            Text(
                text = stringResource(R.string.overlay_no_scenes),
                color = Color(0xFFB8C2CC),
                fontSize = 10.sp,
            )
            return@Column
        }
        scenes.forEach { scene ->
            val active = scene.id == activeId
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(scene.id) }
                    .padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (active) "●" else "○",
                    color = if (active) Color(0xFF00E5FF) else Color(0xFF8A94A0),
                    fontSize = 10.sp,
                    modifier = Modifier.padding(end = 6.dp),
                )
                Text(
                    text = scene.name,
                    color = if (active) Color.White else Color(0xFFB8C2CC),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
