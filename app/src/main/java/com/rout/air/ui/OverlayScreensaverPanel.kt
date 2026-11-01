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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rout.air.data.SceneManager
import com.rout.air.data.screensaverScenes

/**
 * Panel layar jeda di overlay Live: daftar scene "Layar Jeda" yang tersedia.
 * Memilih satu scene langsung mengganti program siaran; menekan baris yang
 * sedang aktif atau tombol matikan akan mengembalikan scene semula.
 */
@Composable
internal fun OverlayScreensaverPanel(
    onPick: (String) -> Unit,
    onStop: () -> Unit,
) {
    val program by SceneManager.program.collectAsState()
    val scenes = program?.screensaverScenes().orEmpty()
    val activeId = program?.activeSceneId

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (scenes.isEmpty()) {
            Text(
                text = "Belum ada scene Layar Jeda. Tambahkan lewat Adegan > Tambah Sumber.",
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
                    .clickable { if (active) onStop() else onPick(scene.id) }
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
        TextButton(onClick = onStop) {
            Text(
                text = "Matikan layar jeda",
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFFFF8A8A),
            )
        }
    }
}
