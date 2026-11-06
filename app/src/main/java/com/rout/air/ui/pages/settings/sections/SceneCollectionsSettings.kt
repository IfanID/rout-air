package com.rout.air.ui.pages.settings.sections

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.rout.air.data.scene.SceneManager
import androidx.compose.ui.res.stringResource
import com.rout.air.R
import kotlinx.coroutines.launch
import com.rout.air.ui.components.Hint
import com.rout.air.ui.pages.settings.components.SectionTitle

/**
 * Kelola beberapa koleksi scene (proyek) beserta ekspor/impor JSON.
 * Kanvas video tetap global; yang berpindah hanya susunan adegan dan sumber.
 */
@Composable
internal fun SceneCollectionsSettings() {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    val collections = remember(version) { SceneManager.collections() }
    val active = collections.firstOrNull { it.active } ?: collections.firstOrNull()
    var message by remember { mutableStateOf<String?>(null) }
    var renameId by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteId by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            val ok = SceneManager.exportActive(uri)
            message = context.getString(
                if (ok) R.string.collections_export_done else R.string.collections_export_failed,
            )
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val imported = SceneManager.importCollection(
                uri,
                context.getString(R.string.collections_imported_name),
            )
            message = context.getString(
                if (imported != null) R.string.collections_import_done
                else R.string.collections_import_failed,
            )
            if (imported != null) version++
        }
    }

    SectionTitle(stringResource(R.string.settings_entry_adegan))
    Hint(stringResource(R.string.collections_hint))

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        collections.forEach { info ->
            FilterChip(
                selected = info.active,
                onClick = {
                    if (!info.active && SceneManager.switchCollection(info.id)) version++
                },
                label = {
                    Text(
                        info.title.ifBlank { stringResource(R.string.collections_untitled) },
                    )
                },
            )
        }
    }

    val activeTitle = active?.title?.ifBlank {
        context.getString(R.string.collections_untitled)
    }.orEmpty()
    val copyName = stringResource(R.string.collections_copy_name, activeTitle)

    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            onClick = {
                SceneManager.duplicateCollection(newTitle = copyName)
                version++
            },
        ) { Text(stringResource(R.string.collections_duplicate)) }
        OutlinedButton(
            onClick = {
                active?.let {
                    renameId = it.id
                    renameText = it.title
                }
            },
            enabled = active != null,
        ) { Text(stringResource(R.string.collections_rename)) }
        OutlinedButton(
            onClick = { deleteId = active?.id },
            enabled = collections.size > 1 && active != null,
        ) { Text(stringResource(R.string.common_hapus)) }
    }

    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(onClick = { exportLauncher.launch("RoutAir_scenes.json") }) {
            Text(stringResource(R.string.collections_export))
        }
        OutlinedButton(
            onClick = { importLauncher.launch(arrayOf("application/json", "text/plain")) },
        ) {
            Text(stringResource(R.string.collections_import))
        }
    }

    message?.let {
        Spacer(Modifier.height(6.dp))
        Text(
            text = it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    renameId?.let { id ->
        AlertDialog(
            onDismissRequest = { renameId = null },
            title = { Text(stringResource(R.string.collections_rename)) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.collections_name)) },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (renameText.isNotBlank()) {
                            SceneManager.renameCollection(id, renameText.trim())
                            version++
                        }
                        renameId = null
                    },
                ) { Text(stringResource(R.string.common_simpan)) }
            },
            dismissButton = {
                TextButton(onClick = { renameId = null }) {
                    Text(stringResource(R.string.common_batal))
                }
            },
        )
    }

    deleteId?.let { id ->
        AlertDialog(
            onDismissRequest = { deleteId = null },
            title = { Text(stringResource(R.string.collections_delete_title)) },
            text = { Text(stringResource(R.string.collections_delete_desc)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        SceneManager.deleteCollection(id)
                        version++
                        deleteId = null
                    },
                ) { Text(stringResource(R.string.common_hapus)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteId = null }) {
                    Text(stringResource(R.string.common_batal))
                }
            },
        )
    }
}
