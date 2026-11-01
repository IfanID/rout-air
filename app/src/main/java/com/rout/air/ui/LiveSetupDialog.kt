package com.rout.air.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rout.air.R

/**
 * Dialog sebelum siaran dimulai, meniru kolom judul/deskripsi/privasi pada
 * integrasi YouTube di OBS. Siaran baru dibuat setelah tombol ditekan.
 */
@Composable
fun LiveSetupDialog(
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onStart: (title: String, description: String, privacy: String) -> Unit,
) {
    var title by remember { mutableStateOf("RoutAir Live") }
    var description by remember { mutableStateOf("") }
    var privacy by remember { mutableStateOf("public") }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.live_setup_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.live_setup_name)) },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.live_setup_description)) },
                    minLines = 3,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.live_setup_privacy),
                    style = MaterialTheme.typography.labelMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "public" to R.string.live_privacy_public,
                        "unlisted" to R.string.live_privacy_unlisted,
                        "private" to R.string.live_privacy_private,
                    ).forEach { (value, label) ->
                        FilterChip(
                            selected = privacy == value,
                            enabled = !busy,
                            onClick = { privacy = value },
                            label = { Text(stringResource(label)) },
                        )
                    }
                }
                if (error != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (busy) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        stringResource(R.string.live_setup_creating),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && title.isNotBlank(),
                onClick = { onStart(title.trim(), description.trim(), privacy) },
            ) {
                Text(stringResource(R.string.live_setup_start))
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) {
                Text(stringResource(R.string.live_setup_cancel))
            }
        },
    )
}
