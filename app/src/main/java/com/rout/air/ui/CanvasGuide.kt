package com.rout.air.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.rout.air.R

/**
 * Menggambar panduan kanvas: kotak-kotak gelap dan garis tepi. Dipakai
 * bersama oleh tab Siaran dan ScenePreview supaya bentuknya identik.
 */
internal fun DrawScope.drawCanvasGuide(canvasAspect: Float) {
    if (size.width <= 0f || size.height <= 0f || canvasAspect <= 0f) return
    var canvasWidth = size.width
    var canvasHeight = canvasWidth / canvasAspect
    if (canvasHeight > size.height) {
        canvasHeight = size.height
        canvasWidth = canvasHeight * canvasAspect
    }
    val left = (size.width - canvasWidth) / 2f
    val top = (size.height - canvasHeight) / 2f
    val area = Size(canvasWidth, canvasHeight)

    drawRect(Color(0xFF1B1B1B), Offset(left, top), area)
    val cell = (canvasWidth / 18f).coerceAtLeast(4f)
    var row = 0
    var y = top
    while (y < top + canvasHeight) {
        var col = 0
        var x = left
        while (x < left + canvasWidth) {
            if ((row + col) % 2 == 1) {
                drawRect(
                    color = Color(0xFF232323),
                    topLeft = Offset(x, y),
                    size = Size(
                        minOf(cell, left + canvasWidth - x),
                        minOf(cell, top + canvasHeight - y),
                    ),
                )
            }
            x += cell
            col++
        }
        y += cell
        row++
    }
    drawRect(
        color = Color(0x33FFFFFF),
        topLeft = Offset(left, top),
        size = area,
        style = Stroke(width = 1f),
    )
}

/**
 * Latar panduan kanvas untuk tampilan editor: kotak-kotak gelap di dalam
 * batas kanvas, hitam di luarnya. Tidak pernah dipakai saat menggambar ke
 * encoder, jadi hasil rekaman/live tetap bersih.
 */
@Composable
internal fun CanvasGuideBackground(
    canvasAspect: Float,
    showEmptyLabel: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCanvasGuide(canvasAspect)
        }
        if (showEmptyLabel) {
            Text(
                text = stringResource(R.string.scenes_belum_ada_sumber),
                color = Color(0xFFB8C2CC),
                textAlign = TextAlign.Center,
            )
        }
    }
}
