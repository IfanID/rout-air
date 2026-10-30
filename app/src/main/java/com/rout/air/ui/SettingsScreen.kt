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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import com.rout.air.core.CanvasOrientation
import com.rout.air.core.CaptureConfig
import com.rout.air.core.ContentMode

private enum class SettingsPage(val title: String) {
    Root("Setelan"),
    Video("Video"),
    Output("Output"),
    Audio("Audio"),
    Hotkeys("Hotkeys"),
    Advanced("Advanced"),
}

/**
 * Setelan mengikuti struktur OBS: Video, Output, Audio, Hotkeys, Advanced.
 * Dipisah per halaman karena layar HP tidak cukup untuk satu dialog panjang.
 */
@Composable
fun SettingsScreen(
    config: CaptureConfig,
    canvasConfig: CaptureConfig,
    onConfigChange: (CaptureConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    var page by remember { mutableStateOf(SettingsPage.Root) }

    BackHandler(enabled = page != SettingsPage.Root) { page = SettingsPage.Root }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        if (page == SettingsPage.Root) {
            Text("Setelan", style = MaterialTheme.typography.headlineSmall)
            Text(
                text = "Preset rekaman dan perangkat",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            SettingsEntry("Video", "Kanvas, orientasi, resolusi, fps") {
                page = SettingsPage.Video
            }
            SettingsEntry("Output", "Encoder, bitrate, format, lokasi") {
                page = SettingsPage.Output
            }
            SettingsEntry("Audio", "Sumber audio, sample rate, kanal") {
                page = SettingsPage.Audio
            }
            SettingsEntry("Hotkeys", "Pintasan mulai/stop rekaman") {
                page = SettingsPage.Hotkeys
            }
            SettingsEntry("Advanced", "Informasi teknis dan diagnostik") {
                page = SettingsPage.Advanced
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { page = SettingsPage.Root }) { Text("‹ Kembali") }
                Text(page.title, style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.height(8.dp))

            when (page) {
                SettingsPage.Video -> VideoSettings(config, canvasConfig, onConfigChange)
                SettingsPage.Output -> OutputSettings(config, onConfigChange)
                SettingsPage.Audio -> AudioSettings(config, onConfigChange)
                SettingsPage.Hotkeys -> HotkeysSettings()
                SettingsPage.Advanced -> AdvancedSettings(canvasConfig)
                SettingsPage.Root -> Unit
            }
        }
    }
}

@Composable
private fun SettingsEntry(title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("›", style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun VideoSettings(
    config: CaptureConfig,
    canvasConfig: CaptureConfig,
    onConfigChange: (CaptureConfig) -> Unit,
) {
    SectionTitle("Base (Canvas) Resolution")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(1920 to 1080, 1280 to 720).forEach { (width, height) ->
            FilterChip(
                selected = config.width == width && config.height == height,
                onClick = {
                    onConfigChange(
                        config.copy(
                            width = width,
                            height = height,
                            videoBitrate = if (width == 1280) 4_000_000 else 6_000_000,
                        ),
                    )
                },
                label = { Text("${height}p") },
            )
        }
    }

    Spacer(Modifier.height(16.dp))
    SectionTitle("Orientasi video")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            CanvasOrientation.Auto to "Auto",
            CanvasOrientation.Portrait to "Vertikal",
            CanvasOrientation.Landscape to "Horizontal",
        ).forEach { (value, label) ->
            FilterChip(
                selected = config.canvasOrientation == value,
                onClick = { onConfigChange(config.copy(canvasOrientation = value)) },
                label = { Text(label) },
            )
        }
    }
    Hint(
        "Auto mengikuti orientasi HP saat rekaman dimulai. Vertikal/Horizontal " +
            "mengunci bentuk video; layar tetap bebas diputar.",
    )

    Spacer(Modifier.height(16.dp))
    SectionTitle("Mode isi")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            ContentMode.Fit to "Pas",
            ContentMode.Crop to "Penuh",
            ContentMode.Stretch to "Peregangan",
        ).forEach { (value, label) ->
            FilterChip(
                selected = config.contentMode == value,
                onClick = { onConfigChange(config.copy(contentMode = value)) },
                label = { Text(label) },
            )
        }
    }
    Hint(
        "Dipakai saat rasio layar beda dengan video: Pas = bar hitam, " +
            "Penuh = dipotong, Peregangan = dipaksa penuh.",
    )

    Spacer(Modifier.height(16.dp))
    SectionTitle("FPS")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(30, 60).forEach { fps ->
            FilterChip(
                selected = config.fps == fps,
                onClick = { onConfigChange(config.copy(fps = fps)) },
                label = { Text("$fps") },
            )
        }
    }

    Spacer(Modifier.height(16.dp))
    SectionTitle("Kanvas aktif")
    InfoRow("Ukuran", "${canvasConfig.width}x${canvasConfig.height}")
    InfoRow("Rasio", String.format("%.3f", canvasConfig.aspectRatio))
    InfoRow("Mode isi", contentModeLabel(canvasConfig))
    Hint("Output (Scaled) Resolution saat ini sama dengan kanvas.")
}

@Composable
private fun OutputSettings(config: CaptureConfig, onConfigChange: (CaptureConfig) -> Unit) {
    SectionTitle("Encoder")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = true, onClick = {}, label = { Text("H.264 (MediaCodec)") })
    }
    Hint("HEVC/AV1 menyusul; saat ini encoder perangkat yang dipakai.")

    Spacer(Modifier.height(16.dp))
    SectionTitle("Bitrate video")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(4, 6, 10).forEach { mbps ->
            FilterChip(
                selected = config.videoBitrate == mbps * 1_000_000,
                onClick = { onConfigChange(config.copy(videoBitrate = mbps * 1_000_000)) },
                label = { Text("$mbps Mbps") },
            )
        }
    }

    Spacer(Modifier.height(16.dp))
    SectionTitle("Format")
    InfoRow("Kontainer", "MP4")
    InfoRow("Video", "H.264")
    InfoRow("Audio", "AAC-LC")
    InfoRow("Lokasi", "Movies/RoutAir")
    Hint("Split recording dan replay buffer menyusul.")
}

@Composable
private fun AudioSettings(config: CaptureConfig, onConfigChange: (CaptureConfig) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Rekam audio internal", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "AudioPlaybackCapture dari aplikasi yang mengizinkan",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = config.recordAudio,
            onCheckedChange = { onConfigChange(config.copy(recordAudio = it)) },
        )
    }

    Spacer(Modifier.height(16.dp))
    SectionTitle("Sample rate")
    InfoRow("Nilai", "${config.sampleRate} Hz")
    SectionTitle("Kanal")
    InfoRow("Nilai", if (config.channelCount == 1) "Mono" else "Stereo")
    Hint("Microphone sebagai kanal terpisah menyusul.")
}

@Composable
private fun HotkeysSettings() {
    SectionTitle("Rekaman")
    HotkeyRow("Mulai merekam", "Belum diatur")
    HotkeyRow("Stop merekam", "Belum diatur")
    HotkeyRow("Pause rekaman", "Menyusul")
    HotkeyRow("Replay buffer", "Menyusul")
    Hint("Pintasan tombol volume dan Quick Settings tile direncanakan.")
}

@Composable
private fun AdvancedSettings(canvasConfig: CaptureConfig) {
    SectionTitle("Informasi")
    InfoRow("Versi", "0.1 (M1)")
    InfoRow("Kanvas", "${canvasConfig.width}x${canvasConfig.height}")
    InfoRow("Renderer", "OpenGL ES 3.0")
    InfoRow("Encoder", "MediaCodec H.264 + AAC")
    InfoRow("Audio", "AudioPlaybackCapture")

    Spacer(Modifier.height(16.dp))
    SectionTitle("Diagnostik")
    Hint("Setiap rekaman menulis log ke Download/RoutAir/RoutAir_diag_*.txt.")
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun HotkeyRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
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
