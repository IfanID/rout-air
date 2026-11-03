package com.rout.air.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rout.air.core.SourceType
import com.rout.air.data.DiagLog
import com.rout.air.data.SceneManager

/**
 * Penjaga pantulan pratinjau.
 *
 * Saat program memakai sumber Layar, pratinjau menampilkan tangkapan layar
 * yang berisi pratinjau itu sendiri — gambar memantul berulang dan tampak
 * "tertarik ke bawah". Selama aplikasi di depan, isi panel ditutup sampai
 * pengguna memilih menampilkannya.
 */
object PreviewGuard {
    /** True = panel ditutup. Disetel ulang setiap aplikasi dibuka. */
    var hidden by mutableStateOf(true)
}

/**
 * Lapisan penutup untuk satu panel pratinjau/program. Hanya aktif bila
 * program memakai sumber Layar.
 */
@Composable
internal fun PreviewMirrorGuard(enabled: Boolean, modifier: Modifier = Modifier) {
    if (!enabled) return
    val program by SceneManager.program.collectAsState()
    val hasScreen = remember(program) {
        val active = program?.activeScene() ?: return@remember false
        active.items.any { item ->
            program?.source(item.sourceId)?.type == SourceType.Screen
        }
    }
    if (!hasScreen) return

    LaunchedEffect(PreviewGuard.hidden) {
        DiagLog.line("pratinjau: penjaga pantulan hidden=${PreviewGuard.hidden}")
    }

    if (PreviewGuard.hidden) {
        Box(
            modifier = modifier.background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "Pratinjau disembunyikan agar tidak memantul",
                    color = Color(0xFFB8C2CC),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                )
                TextButton(onClick = { PreviewGuard.hidden = false }) {
                    Text("Tampilkan pratinjau")
                }
            }
        }
    } else {
        Box(modifier = modifier, contentAlignment = Alignment.TopEnd) {
            TextButton(
                onClick = { PreviewGuard.hidden = true },
                modifier = Modifier
                    .padding(6.dp)
                    .background(Color(0x99000000)),
            ) {
                Text("Sembunyikan pratinjau", fontSize = 11.sp, color = Color(0xFFB8C2CC))
            }
        }
    }
}
