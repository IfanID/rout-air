package com.rout.air.ui

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rout.air.core.RecordingState

/**
 * Halaman Audio: meniru mixer OBS dengan satu kanal audio internal.
 */
@Composable
fun AudioScreen(
    state: RecordingState,
    recordAudio: Boolean,
    onRecordAudioChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val recording = state as? RecordingState.Recording
    val status = when {
        recording == null -> if (recordAudio) "Siap" else "Dimatikan"
        recording.audioActive -> "Aktif merekam"
        else -> "Tidak tersedia (aplikasi memblokir)"
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Text("Audio", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = "Kanal audio yang masuk ke rekaman",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Audio internal", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = recordAudio,
                    onCheckedChange = onRecordAudioChange,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Text(
            text = "Sumber audio memakai AudioPlaybackCapture. Suara hanya tertangkap " +
                "dari aplikasi yang mengizinkan tangkap audio; aplikasi yang memblokir " +
                "akan terdengar senyap.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(12.dp))

        Text(
            text = "Mikrofon sebagai kanal terpisah menyusul pada tahap berikutnya.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
