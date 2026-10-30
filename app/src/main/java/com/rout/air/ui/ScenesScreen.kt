package com.rout.air.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rout.air.core.CaptureConfig

/**
 * Halaman Adegan: daftar scene, sumber, dan transisi.
 * Struktur mengikuti dock Scenes / Sources / Scene Transitions di OBS.
 */
@Composable
fun ScenesScreen(canvasConfig: CaptureConfig, modifier: Modifier = Modifier) {
    var showSourceDetail by remember { mutableStateOf(false) }

    BackHandler(enabled = showSourceDetail) { showSourceDetail = false }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        if (showSourceDetail) {
            SourceDetail(canvasConfig) { showSourceDetail = false }
            return@Column
        }

        Text("Adegan", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = "Scene, sumber, dan transisi",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))
        Text("Scene", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Scene 1", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "1 sumber • ${canvasConfig.width}x${canvasConfig.height}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text = "Aktif",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Text("Sumber", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showSourceDetail = true },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = CircleShape,
                    modifier = Modifier.size(36.dp),
                ) {
                    Text(
                        text = "▣",
                        modifier = Modifier.padding(8.dp),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Layar", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "Tangkap layar penuh • ${canvasConfig.width}x${canvasConfig.height}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text("›", style = MaterialTheme.typography.titleLarge)
            }
        }

        Spacer(Modifier.height(20.dp))
        Text("Transisi", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Cut", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "Tanpa animasi",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "0 ms",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            text = "Kamera, gambar, dan teks ditambahkan sebagai sumber baru pada tahap M2, " +
                "mengikuti obs_scene_item: posisi, skala, rotasi, crop, dan blend.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SourceDetail(canvasConfig: CaptureConfig, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack) { Text("‹ Kembali") }
        Text("Layar", style = MaterialTheme.typography.titleLarge)
    }

    Spacer(Modifier.height(8.dp))
    Text("Sumber", style = MaterialTheme.typography.titleSmall)
    DetailRow("Tipe", "Screen capture (MediaProjection)")
    DetailRow("Resolusi", "${canvasConfig.width}x${canvasConfig.height}")
    DetailRow("Terlihat", "Ya")
    DetailRow("Terkunci", "Tidak")

    Spacer(Modifier.height(16.dp))
    Text("Transformasi", style = MaterialTheme.typography.titleSmall)
    DetailRow("Posisi", "0, 0")
    DetailRow("Skala", "1.000 x 1.000")
    DetailRow("Rotasi", "0°")
    DetailRow("Crop", "0, 0, 0, 0")
    DetailRow("Alignment", "Kiri atas")
    DetailRow("Blend", "Normal")

    Spacer(Modifier.height(16.dp))
    Text("Filter", style = MaterialTheme.typography.titleSmall)
    DetailRow("Jumlah", "0")
    Hint("Filter per sumber (crop/pad, color correction, chroma key) menyusul di M3.")
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
