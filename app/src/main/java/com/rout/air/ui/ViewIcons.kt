package com.rout.air.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Ikon kecil digambar sendiri dengan Canvas supaya tajam di semua kerapatan
 * layar tanpa menambah dependensi material-icons.
 *
 * Semua koordinat memakai ruang 24x24 lalu diskalakan ke ukuran komponen.
 */

/** Panah melingkar: reset properti/item. */
@Composable
internal fun ResetIcon(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    description: String = "Reset",
) {
    Canvas(
        modifier = modifier
            .size(size)
            .semantics { contentDescription = description },
    ) {
        val u = this.size.minDimension / 24f
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        val radius = 8f * u
        val stroke = 2f * u

        // Busur hampir penuh, menyisakan celah di kanan atas untuk kepala panah.
        drawArc(
            color = color,
            startAngle = 35f,
            sweepAngle = 295f,
            useCenter = false,
            topLeft = Offset(center.x - radius, center.y - radius),
            size = Size(radius * 2f, radius * 2f),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )

        // Kepala panah sebagai segitiga kecil di ujung busur.
        val angle = 35.0 * PI / 180.0
        val tip = Offset(
            center.x + radius * cos(angle).toFloat(),
            center.y + radius * sin(angle).toFloat(),
        )
        val path = Path().apply {
            moveTo(tip.x, tip.y)
            lineTo(tip.x - 4.4f * u, tip.y + 1.0f * u)
            lineTo(tip.x + 1.0f * u, tip.y - 4.4f * u)
            close()
        }
        drawPath(path, color)
    }
}

/** Mata: sumber tampil atau disembunyikan. */
@Composable
internal fun VisibilityIcon(
    visible: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
) {
    Canvas(
        modifier = modifier
            .size(size)
            .semantics { contentDescription = if (visible) "Sembunyikan" else "Tampilkan" },
    ) {
        val u = this.size.minDimension / 24f
        val stroke = 1.9f * u

        val outline = Path().apply {
            moveTo(2.6f * u, 12f * u)
            cubicTo(6f * u, 6.4f * u, 18f * u, 6.4f * u, 21.4f * u, 12f * u)
            cubicTo(18f * u, 17.6f * u, 6f * u, 17.6f * u, 2.6f * u, 12f * u)
            close()
        }
        drawPath(
            path = outline,
            color = color,
            style = Stroke(width = stroke, join = StrokeJoin.Round),
        )
        drawCircle(
            color = color,
            radius = 3.1f * u,
            center = Offset(12f * u, 12f * u),
        )
        if (!visible) {
            // Garis miring menandakan disembunyikan.
            drawLine(
                color = color,
                start = Offset(4.2f * u, 4.2f * u),
                end = Offset(19.8f * u, 19.8f * u),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

/** Gembok: item terkunci atau bebas digeser. */
@Composable
internal fun LockIcon(
    locked: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
) {
    Canvas(
        modifier = modifier
            .size(size)
            .semantics { contentDescription = if (locked) "Buka kunci" else "Kunci posisi" },
    ) {
        val u = this.size.minDimension / 24f
        val stroke = 1.9f * u
        val shackleColor = color

        if (locked) {
            // Gembok terkunci: busur penuh di atas badan.
            drawArc(
                color = shackleColor,
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(7f * u, 4.5f * u),
                size = Size(10f * u, 10f * u),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        } else {
            // Gembok terbuka: busur digeser dan hanya sebagian.
            drawArc(
                color = shackleColor,
                startAngle = 200f,
                sweepAngle = 160f,
                useCenter = false,
                topLeft = Offset(9f * u, 3.5f * u),
                size = Size(10f * u, 10f * u),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }

        drawRoundRect(
            color = color,
            topLeft = Offset(4.5f * u, 10.5f * u),
            size = Size(15f * u, 9.5f * u),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.6f * u),
        )
        // Lubang kunci kecil agar bentuknya terbaca.
        drawCircle(
            color = Color.Black.copy(alpha = 0.55f),
            radius = 1.5f * u,
            center = Offset(12f * u, 15.2f * u),
        )
    }
}

/** Panah tipis ke kanan: membuka panel properti item. */
@Composable
internal fun ChevronRightIcon(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    description: String = "Buka properti",
) {
    Canvas(
        modifier = modifier
            .size(size)
            .semantics { contentDescription = description },
    ) {
        val u = this.size.minDimension / 24f
        val stroke = 2f * u
        drawLine(color, Offset(9.5f * u, 6f * u), Offset(15.5f * u, 12f * u), stroke, StrokeCap.Round)
        drawLine(color, Offset(15.5f * u, 12f * u), Offset(9.5f * u, 18f * u), stroke, StrokeCap.Round)
    }
}

/** Tempat sampah: aksi hapus item saat baris digeser. */
@Composable
internal fun TrashIcon(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
) {
    Canvas(
        modifier = modifier
            .size(size)
            .semantics { contentDescription = "Hapus" },
    ) {
        val u = this.size.minDimension / 24f
        val stroke = 1.9f * u
        // Tutup atas dan pegangan.
        drawLine(color, Offset(4f * u, 7f * u), Offset(20f * u, 7f * u), stroke, StrokeCap.Round)
        drawLine(color, Offset(9.5f * u, 4.5f * u), Offset(14.5f * u, 4.5f * u), stroke, StrokeCap.Round)
        // Badan tempat sampah.
        val body = Path().apply {
            moveTo(6f * u, 7f * u)
            lineTo(7.2f * u, 20f * u)
            lineTo(16.8f * u, 20f * u)
            lineTo(18f * u, 7f * u)
        }
        drawPath(body, color, style = Stroke(width = stroke, join = StrokeJoin.Round))
        // Garis isi.
        drawLine(color, Offset(10.5f * u, 10.5f * u), Offset(10.8f * u, 16.5f * u), stroke * 0.8f)
        drawLine(color, Offset(13.5f * u, 10.5f * u), Offset(13.2f * u, 16.5f * u), stroke * 0.8f)
    }
}

/** Monitor kecil: penanda sumber tangkap layar. */
@Composable
internal fun ScreenIcon(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
) {
    Canvas(modifier = modifier.size(size)) {
        val u = this.size.minDimension / 24f
        val stroke = 2f * u
        drawRoundRect(
            color = color,
            topLeft = Offset(2.5f * u, 4.5f * u),
            size = Size(19f * u, 13f * u),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f * u),
            style = Stroke(width = stroke),
        )
        drawLine(color, Offset(9f * u, 20.5f * u), Offset(15f * u, 20.5f * u), stroke, StrokeCap.Round)
        drawLine(color, Offset(12f * u, 17.5f * u), Offset(12f * u, 20.5f * u), stroke, StrokeCap.Round)
    }
}

/** Tiga garis sejajar: pegangan untuk menggeser urutan sumber. */
@Composable
internal fun DragHandleIcon(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
) {
    Canvas(modifier = modifier.size(size)) {
        val u = this.size.minDimension / 24f
        val stroke = 2f * u
        listOf(7f, 12f, 17f).forEach { y ->
            drawLine(
                color = color,
                start = Offset(5f * u, y * u),
                end = Offset(19f * u, y * u),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}
