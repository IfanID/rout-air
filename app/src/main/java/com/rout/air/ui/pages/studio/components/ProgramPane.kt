package com.rout.air.ui.pages.studio.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import com.rout.air.core.model.CaptureConfig
import com.rout.air.core.model.SceneCollection
import com.rout.air.output.recording.CaptureEngine
import androidx.compose.ui.res.stringResource
import com.rout.air.R
import com.rout.air.ui.components.ScenePreview
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * Panel Program. Saat sesi hidup memakai gambar pipeline (snapshot program);
 * saat tidak ada sesi memakai pratinjau Compose dari snapshot program juga,
 * sehingga hasil edit pratinjau tidak terlihat di sini sebelum Transisi.
 */
@Composable
internal fun ProgramPane(
    isLive: Boolean,
    canvasConfig: CaptureConfig,
    program: SceneCollection,
    modifier: Modifier = Modifier,
) {
    if (isLive) {
        PreviewPane(
            label = stringResource(R.string.studio_program_siaran),
            canvasAspect = canvasConfig.aspectRatio,
            live = true,
            modifier = modifier,
            attach = { surface, width, height ->
                CaptureEngine.attachProgram(surface, width, height)
            },
        )
    } else {
        Column(modifier = modifier) {
            PaneCaption(label = stringResource(R.string.studio_program_siaran), live = false)
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                ScenePreview(
                    canvas = canvasConfig,
                    collection = program,
                    selectedItemId = null,
                    onSelectItem = {},
                    onTransform = { _, _, _, _, _, _ -> },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** Caption panel di luar kotak gambar, sama untuk Pratinjau dan Program. */
@Composable
internal fun PaneCaption(label: String, live: Boolean) {
    Row(
        modifier = Modifier.padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (live) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFE53935)),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = Color(0xFFB8C2CC),
            textAlign = TextAlign.Start,
        )
    }
}
