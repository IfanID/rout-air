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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rout.air.R
import com.rout.air.data.SceneManager
import com.rout.air.data.ScreensaverStore
import com.rout.air.data.showScreensaverEntry

/**
 * Panel layar jeda di overlay Live: daftar tampilan jeda yang tersimpan di
 * luar scene. Memilih satu baris menyuntikkan tampilan itu ke program;
 * menekan baris aktif atau tombol matikan mengembalikan program semula.
 */
@Composable
internal fun OverlayScreensaverPanel() {
    val context = LocalContext.current
    val entries by ScreensaverStore.entries.collectAsState()
    var error by remember { mutableStateOf<String?>(null) }
    var activeId by remember { mutableStateOf<String?>(null) }
    val screensaverOn = activeId != null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = stringResource(R.string.scenes_screensaver),
            color = Color.White,
            fontSize = 11.sp,
        )
        if (entries.isEmpty()) {
            Text(
                text = "Belum ada tampilan jeda. Tambahkan gambar di halaman Adegan.",
                color = Color(0xFFB8C2CC),
                fontSize = 10.sp,
            )
            return@Column
        }
        entries.forEach { entry ->
            val shown = shown(entry.id, activeId)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (shown) {
                            SceneManager.hideScreensaver()
                            activeId = null
                        } else if (showScreensaverEntry(context, entry)) {
                            error = null
                            activeId = entry.id
                        } else {
                            error = "Gagal menampilkan layar jeda"
                        }
                    }
                    .padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (shown) "●" else "○",
                    color = if (shown) Color(0xFF00E5FF) else Color(0xFF8A94A0),
                    fontSize = 10.sp,
                    modifier = Modifier.padding(end = 6.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.name,
                        color = if (shown) Color.White else Color(0xFFB8C2CC),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = when {
                            entry.imagePath == null -> stringResource(R.string.screensaver_builtin)
                            entry.vertical -> stringResource(R.string.screensaver_vertical)
                            else -> stringResource(R.string.screensaver_horizontal)
                        },
                        color = Color(0xFF8A94A0),
                        fontSize = 9.sp,
                    )
                }
            }
        }
        error?.let {
            Text(
                text = it,
                color = Color(0xFFFF8A8A),
                fontSize = 10.sp,
            )
        }
        if (screensaverOn) {
            TextButton(onClick = {
                SceneManager.hideScreensaver()
                activeId = null
            }) {
                Text(
                    text = "Matikan layar jeda",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFFFF8A8A),
                )
            }
        }
    }
}

private fun shown(entryId: String, activeId: String?): Boolean = entryId == activeId
