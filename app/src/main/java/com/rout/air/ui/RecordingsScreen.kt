package com.rout.air.ui

import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.res.stringResource
import com.rout.air.R

private data class RecordingItem(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val dateAddedSeconds: Long,
)

/**
 * Halaman Rekaman: daftar hasil rekaman di Movies/RoutAir.
 */
@Composable
fun RecordingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var items by remember { mutableStateOf<List<RecordingItem>>(emptyList()) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var pendingDelete by remember { mutableStateOf<RecordingItem?>(null) }

    LaunchedEffect(refreshKey) {
        items = withContext(Dispatchers.IO) { loadRecordings(context) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.recordings_rekaman), style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = stringResource(R.string.recordings_n_berkas, items.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { refreshKey++ }) { Text(stringResource(R.string.common_muat_ulang)) }
        }

        Spacer(Modifier.height(12.dp))

        if (items.isEmpty()) {
            Text(
                text = stringResource(R.string.recordings_belum_ada_rekaman),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items, key = { it.uri.toString() }) { item ->
                    RecordingRow(
                        item = item,
                        onOpen = { openRecording(context, item.uri) },
                        onShare = { shareRecording(context, item.uri) },
                        onDelete = { pendingDelete = item },
                    )
                }
            }
        }
    }

    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.recordings_hapus_rekaman)) },
            text = { Text(item.name) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                runCatching {
                                    context.contentResolver.delete(item.uri, null, null)
                                }
                            }
                            refreshKey++
                        }
                    },
                ) { Text(stringResource(R.string.common_hapus)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.common_batal)) }
            },
        )
    }
}

@Composable
private fun RecordingRow(
    item: RecordingItem,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(16.dp),
        ) {
            Text(item.name, style = MaterialTheme.typography.titleSmall)
            Text(
                text = "${formatBytes(item.sizeBytes)} • ${formatDuration(item.durationMs)} • " +
                    formatDate(item.dateAddedSeconds),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onOpen) { Text(stringResource(R.string.recordings_buka)) }
                TextButton(onClick = onShare) { Text(stringResource(R.string.recordings_bagikan)) }
                TextButton(onClick = onDelete) { Text(stringResource(R.string.common_hapus)) }
            }
        }
    }
}

private fun loadRecordings(context: Context): List<RecordingItem> {
    val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    val projection = arrayOf(
        MediaStore.Video.Media._ID,
        MediaStore.Video.Media.DISPLAY_NAME,
        MediaStore.Video.Media.SIZE,
        MediaStore.Video.Media.DURATION,
        MediaStore.Video.Media.DATE_ADDED,
    )
    val selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
    val args = arrayOf("%Movies/RoutAir%")

    return runCatching {
        context.contentResolver.query(
            collection,
            projection,
            selection,
            args,
            "${MediaStore.Video.Media.DATE_ADDED} DESC",
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    add(
                        RecordingItem(
                            uri = ContentUris.withAppendedId(collection, id),
                            name = cursor.getString(1) ?: "rekaman.mp4",
                            sizeBytes = cursor.getLong(2),
                            durationMs = cursor.getLong(3),
                            dateAddedSeconds = cursor.getLong(4),
                        ),
                    )
                }
            }
        } ?: emptyList()
    }.getOrElse {
        Log.w("RoutAirRecordings", "Gagal membaca daftar rekaman: ${it.message}")
        emptyList()
    }
}

private fun openRecording(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "video/mp4")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(intent) }
        .onFailure { Log.w("RoutAirRecordings", "Tidak ada pemutar video: ${it.message}") }
}

private fun shareRecording(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "video/mp4"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(Intent.createChooser(intent, "Bagikan rekaman"))
    } catch (_: ActivityNotFoundException) {
        Log.w("RoutAirRecordings", "Tidak ada aplikasi untuk membagikan")
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 MB"
    val megabytes = bytes / (1024.0 * 1024.0)
    return if (megabytes >= 1024) {
        String.format(Locale.US, "%.2f GB", megabytes / 1024)
    } else {
        String.format(Locale.US, "%.1f MB", megabytes)
    }
}

private fun formatDate(seconds: Long): String {
    val formatter = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())
    return formatter.format(Date(seconds * 1000))
}
