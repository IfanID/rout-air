package com.rout.air.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Dialog siap-siaran di dalam aplikasi. Isinya sama dengan versi melayang
 * yang dipakai overlay, keduanya memakai [LiveSetupCard].
 */
@Composable
fun LiveSetupDialog(
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onStart: (title: String, description: String, privacy: String) -> Unit,
) {
    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .padding(vertical = 24.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Box(modifier = Modifier.padding(16.dp)) {
                LiveSetupCard(
                    busy = busy,
                    error = error,
                    onStart = onStart,
                    onCancel = onDismiss,
                )
            }
        }
    }
}
