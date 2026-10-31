package com.rout.air.ui

import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Dialog Tambah Sumber bergaya OBS. Tipe yang belum didukung tetap
 * ditampilkan sebagai opsi nonaktif agar struktur menu sama dengan OBS.
 */
@Composable
internal fun AddSourceDialog(
    hasScreen: Boolean,
    onScreen: () -> Unit,
    onImage: () -> Unit,
    onText: () -> Unit,
    onColor: () -> Unit,
    onApplicationAudio: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.94f),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("Tambah Sumber", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Pilih jenis sumber seperti di OBS",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))

                Column(
                    modifier = Modifier
                        .heightIn(max = 460.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    SourceOption("▣", "Layar", "Tangkap seluruh layar perangkat", !hasScreen, onScreen)
                    SourceOption("▤", "Gambar", "File gambar dari galeri", true, onImage)
                    SourceOption("T", "Teks", "Teks yang bisa diubah", true, onText)
                    SourceOption("■", "Warna", "Latar warna solid", true, onColor)
                    SourceOption(
                        "◉",
                        "Application Audio Capture",
                        "Suara satu aplikasi; kanal masuk ke tab Audio",
                        true,
                        onApplicationAudio,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Audio mikrofon dan audio internal dikelola di tab Audio.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Belum tersedia di Android",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                    SourceOption("▶", "Media Source", "File video/audio lokal", false) {}
                    SourceOption("◨", "Kamera", "Video Capture Device", false) {}
                    SourceOption("▦", "Aplikasi", "Application Audio/Window Capture", false) {}
                    SourceOption("◍", "Browser", "Browser source", false) {}
                    SourceOption("▥", "Grup", "Kelompokkan sumber", false) {}
                }

                Spacer(Modifier.height(6.dp))
                Row {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Batal") }
                }
            }
        }
    }
}

@Composable
private fun SourceOption(
    symbol: String,
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = symbol,
            style = MaterialTheme.typography.titleMedium,
            color = if (enabled) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.width(32.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Opsi perangkat input yang sudah difilter dari duplikat/virtual device. */
internal data class AudioInputOption(
    val id: Int,
    val name: String,
    val typeLabel: String,
    val type: Int,
)

private val INPUT_TYPE_PRIORITY = listOf(
    AudioDeviceInfo.TYPE_BUILTIN_MIC,
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_USB_HEADSET,
    AudioDeviceInfo.TYPE_USB_DEVICE,
    AudioDeviceInfo.TYPE_USB_ACCESSORY,
    AudioDeviceInfo.TYPE_DOCK,
    AudioDeviceInfo.TYPE_LINE_ANALOG,
    AudioDeviceInfo.TYPE_LINE_DIGITAL,
)

private fun inputTypeLabel(type: Int): String = when (type) {
    AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Mikrofon bawaan"
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth / TWS (SCO)"
    AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Headset kabel"
    AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE,
    AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB"
    AudioDeviceInfo.TYPE_DOCK -> "Dock"
    AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL -> "Line in"
    else -> "Audio input"
}

/**
 * Android sering melaporkan mikrofon bawaan beberapa kali (mic, telephony,
 * virtual) dan menyertakan remote-submix. Daftar ini hanya menampilkan device
 * input nyata, lalu menggabungkan entri dengan nama yang sama.
 */
internal fun loadAudioInputOptions(context: android.content.Context): List<AudioInputOption> {
    val manager = context.getSystemService(AudioManager::class.java) ?: return emptyList()
    val priority = INPUT_TYPE_PRIORITY.withIndex().associate { it.value to it.index }
    return manager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        .filter { it.type in priority }
        .groupBy {
            it.productName?.toString()?.trim()?.lowercase().orEmpty()
                .ifEmpty { "device-${it.id}" }
        }
        .mapNotNull { (_, group) -> group.minByOrNull { priority[it.type] ?: 99 } }
        .map {
            AudioInputOption(
                id = it.id,
                name = it.productName?.toString().orEmpty().ifEmpty { "Audio device ${it.id}" },
                typeLabel = inputTypeLabel(it.type),
                type = it.type,
            )
        }
        .sortedWith(compareBy({ if (it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) 0 else 1 }, { it.name.lowercase() }))
}

/** Langkah kedua untuk sumber audio: nama dan perangkat input. */
@Composable
internal fun AudioSourceSetupDialog(
    audioInput: Boolean,
    defaultName: String,
    onConfirm: (
        name: String,
        deviceId: Int,
        deviceName: String?,
        deviceType: Int,
        sco: Boolean,
    ) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val devices = remember(context, audioInput) {
        if (audioInput) loadAudioInputOptions(context) else emptyList()
    }

    var name by remember { mutableStateOf(defaultName) }
    var selected by remember { mutableStateOf<AudioInputOption?>(null) }
    val isBluetooth = selected?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
    var sco by remember(selected?.id) { mutableStateOf(isBluetooth) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    if (audioInput) "Tambah Audio Input" else "Tambah Audio Output",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nama sumber") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (audioInput) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Perangkat input",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(
                        modifier = Modifier
                            .heightIn(max = 260.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        DeviceRow(
                            title = "Default",
                            subtitle = "Ikuti perangkat input sistem",
                            selected = selected == null,
                            onClick = { selected = null },
                        )
                        devices.forEach { device ->
                            DeviceRow(
                                title = device.name,
                                subtitle = device.typeLabel,
                                selected = selected?.id == device.id,
                                onClick = { selected = device },
                            )
                        }
                    }

                    if (isBluetooth) {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "Aktifkan SCO (mic Bluetooth)",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    "TWS memakai profil headset. Musik A2DP bisa berhenti " +
                                        "atau turun kualitas selama mic TWS dipakai.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            androidx.compose.material3.Switch(
                                checked = sco,
                                onCheckedChange = { sco = it },
                            )
                        }
                    }
                } else {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Menangkap audio playback dari aplikasi lain yang mengizinkan " +
                            "AudioPlaybackCapture.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Batal") }
                    TextButton(
                        onClick = {
                            onConfirm(
                                name.trim().ifEmpty { defaultName },
                                selected?.id ?: 0,
                                selected?.name,
                                selected?.type ?: 0,
                                isBluetooth && sco,
                            )
                        },
                    ) { Text("Tambah") }
                }
            }
        }
    }
}

/** Dialog untuk mengganti perangkat input sumber yang sudah ada. */
@Composable
internal fun AudioDevicePickerDialog(
    currentId: Int,
    onSelect: (option: AudioInputOption?) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val devices = remember(context) { loadAudioInputOptions(context) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(16.dp)) {
                Text("Perangkat input", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    DeviceRow(
                        title = "Default",
                        subtitle = "Ikuti perangkat input sistem",
                        selected = currentId == 0,
                        onClick = { onSelect(null) },
                    )
                    devices.forEach { device ->
                        DeviceRow(
                            title = device.name,
                            subtitle = device.typeLabel,
                            selected = currentId == device.id,
                            onClick = { onSelect(device) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Tutup") }
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (selected) "◉" else "○",
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(24.dp),
        )
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

