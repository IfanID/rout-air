package com.rout.air.ui.pages.screensaver

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.rout.air.R
import com.rout.air.data.scene.SceneStore
import com.rout.air.data.screensaver.ScreensaverEntry
import com.rout.air.data.screensaver.ScreensaverStore
import com.rout.air.engine.source.SourceBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rout.air.core.model.Crop

/**
 * Halaman pengaturan layar jeda. Isinya daftar gambar jeda beserta
 * pratinjaunya; gambar yang ditambahkan tidak masuk ke scene pengguna.
 */
@Composable
internal fun ScreensaverPage(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val entries by ScreensaverStore.entries.collectAsState()
    var showOrientation by remember { mutableStateOf(false) }
    var vertical by remember { mutableStateOf(true) }

    BackHandler(onBack = onBack)

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val path = SceneStore(context).importImage(uri) ?: return@rememberLauncherForActivityResult
        val number = entries.count { it.imagePath != null } + 1
        ScreensaverStore.add(
            ScreensaverEntry(
                name = "Gambar $number",
                imagePath = path,
                vertical = vertical,
            ),
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) {
                Text(stringResource(R.string.screensaver_back))
            }
            Spacer(Modifier.weight(1f))
        }
        Text(
            text = stringResource(R.string.scenes_screensaver),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(R.string.screensaver_page_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))

        entries.forEach { entry ->
            ScreensaverRow(
                entry = entry,
                deletable = !ScreensaverStore.isDefault(entry.id),
                onDelete = { ScreensaverStore.remove(entry.id) },
            )
            Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.height(6.dp))
        Button(
            onClick = {
                vertical = true
                showOrientation = true
            },
        ) {
            Text(stringResource(R.string.screensaver_add_image))
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.screensaver_section_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (showOrientation) {
        AlertDialog(
            onDismissRequest = { showOrientation = false },
            title = { Text(stringResource(R.string.screensaver_orientation_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        TextButton(onClick = {
                            vertical = true
                            showOrientation = false
                            imagePicker.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly,
                                ),
                            )
                        }) {
                            Column(Modifier.padding(6.dp)) {
                                Text(stringResource(R.string.screensaver_vertical))
                                Text(
                                    text = stringResource(R.string.screensaver_vertical_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        TextButton(onClick = {
                            vertical = false
                            showOrientation = false
                            imagePicker.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly,
                                ),
                            )
                        }) {
                            Column(Modifier.padding(6.dp)) {
                                Text(stringResource(R.string.screensaver_horizontal))
                                Text(
                                    text = stringResource(R.string.screensaver_horizontal_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showOrientation = false }) {
                    Text(stringResource(R.string.common_batal))
                }
            },
        )
    }
}

@Composable
private fun ScreensaverRow(
    entry: ScreensaverEntry,
    deletable: Boolean,
    onDelete: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                if (entry.imagePath != null) {
                    val image by produceState<ImageBitmap?>(null, entry.imagePath) {
                        value = withContext(Dispatchers.IO) {
                            SourceBitmap.loadImage(entry.imagePath, 320, 320)?.asImageBitmap()
                        }
                    }
                    image?.let {
                        Image(
                            bitmap = it,
                            contentDescription = entry.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                } else {
                    Text(
                        text = "RoutAir",
                        color = Color(0xFF00E5FF),
                        fontSize = 10.sp,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = when {
                        entry.imagePath == null -> stringResource(R.string.screensaver_builtin)
                        entry.vertical -> stringResource(R.string.screensaver_vertical)
                        else -> stringResource(R.string.screensaver_horizontal)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (deletable) {
                TextButton(onClick = onDelete) {
                    Text(
                        text = stringResource(R.string.screensaver_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}
