package com.rout.air.ui

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import androidx.compose.foundation.clickable
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

internal data class ApplicationAudioOption(
    val uid: Int,
    val packageName: String,
    val label: String,
    val playing: Boolean,
)

/**
 * Application Audio Capture: daftar aplikasi yang bisa dipilih sebagai
 * sumber audio. Aplikasi yang sedang memutar audio diletakkan di atas.
 */
@Composable
internal fun ApplicationAudioPickerDialog(
    onSelect: (ApplicationAudioOption) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val apps = remember(context) { loadApplicationAudioOptions(context) }
    var query by remember { mutableStateOf("") }

    val filtered = apps.filter {
        query.isBlank() ||
            it.label.contains(query, ignoreCase = true) ||
            it.packageName.contains(query, ignoreCase = true)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.97f).heightIn(max = 560.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("Application Audio Capture", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Pilih aplikasi yang suaranya ingin direkam. Hanya aplikasi yang " +
                        "mengizinkan AudioPlaybackCapture yang akan menghasilkan suara.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Cari aplikasi") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))

                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (filtered.isEmpty()) {
                        Text(
                            "Tidak ada aplikasi yang cocok.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                    filtered.forEach { app ->
                        ApplicationRow(app) { onSelect(app) }
                    }
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
private fun ApplicationRow(app: ApplicationAudioOption, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.width(32.dp).height(32.dp),
        ) {
            androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                Text(
                    text = app.label.take(1).uppercase(),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(app.label, style = MaterialTheme.typography.bodyMedium)
            Text(
                app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (app.playing) {
            Text(
                "sedang memutar",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

private fun loadApplicationAudioOptions(context: Context): List<ApplicationAudioOption> {
    val pm = context.packageManager
    val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val self = context.packageName
    return pm.queryIntentActivities(mainIntent, 0)
        .mapNotNull { resolved ->
            val appInfo = resolved.activityInfo?.applicationInfo ?: return@mapNotNull null
            if (appInfo.packageName == self) return@mapNotNull null
            ApplicationAudioOption(
                uid = appInfo.uid,
                packageName = appInfo.packageName,
                label = pm.getApplicationLabel(appInfo).toString(),
                playing = false,
            )
        }
        .distinctBy { it.uid }
        .sortedWith(compareBy({ !it.playing }, { it.label.lowercase() }))
}
