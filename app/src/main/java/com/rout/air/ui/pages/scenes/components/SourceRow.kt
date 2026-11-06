package com.rout.air.ui.pages.scenes.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import com.rout.air.R
import com.rout.air.core.model.SceneItem
import com.rout.air.core.model.SceneSource
import com.rout.air.core.model.SourceType
import androidx.compose.ui.res.stringResource
import com.rout.air.ui.components.DragHandleIcon
import com.rout.air.ui.components.ResetIcon
import com.rout.air.ui.components.ScreenIcon
import com.rout.air.ui.components.TrashIcon
import com.rout.air.ui.components.VisibilityIcon
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

@Composable
internal fun SourceRow(
    item: SceneItem,
    source: SceneSource,
    selected: Boolean,
    onSelect: () -> Unit,
    onReset: () -> Unit,
    onToggleVisible: () -> Unit,
    onToggleLock: () -> Unit,
    onDelete: () -> Unit,
    /** Geser urutan sumber: +1 naik (lebih depan), -1 turun. */
    onMove: (Int) -> Unit,
) {
    // Geser baris sedikit ke kiri: tombol Hapus tersingkap, lalu diketuk
    // untuk menghapus. Tidak langsung terhapus seperti swipe-to-dismiss.
    val density = LocalDensity.current
    val revealWidthPx = with(density) { 96.dp.toPx() }
    var target by remember(item.id) { mutableFloatStateOf(0f) }
    var dragging by remember(item.id) { mutableStateOf(false) }
    val offset by animateFloatAsState(
        targetValue = target,
        animationSpec = if (dragging) snap() else tween(durationMillis = 180),
        label = "geser-hapus",
    )
    // Geser pegangan naik/turun untuk memindahkan urutan lapisan.
    var rowHeightPx by remember(item.id) { mutableFloatStateOf(0f) }
    var dragY by remember(item.id) { mutableFloatStateOf(0f) }
    var reordering by remember(item.id) { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .zIndex(if (reordering) 1f else 0f)
            .onSizeChanged { rowHeightPx = it.height.toFloat() }
            .offset { IntOffset(0, dragY.roundToInt()) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Pegangan urutan berada di luar area geser-hapus. Jadi menarik
        // pegangan tidak pernah ikut menyingkap tombol Hapus.
        Box(
            modifier = Modifier
                .size(32.dp)
                .pointerInput(item.id) {
                    detectVerticalDragGestures(
                        onDragStart = { reordering = true },
                        onDragEnd = {
                            reordering = false
                            val pitch = rowHeightPx + 8.dp.toPx()
                            val step = -(dragY / pitch).roundToInt()
                            dragY = 0f
                            if (step != 0) onMove(step)
                        },
                        onDragCancel = {
                            reordering = false
                            dragY = 0f
                        },
                    ) { change, dragAmount ->
                        change.consume()
                        dragY += dragAmount
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            DragHandleIcon(
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                size = 18.dp,
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            // Latar tombol hapus; menempel di belakang kartu dan ikut
            // tersingkap saat kartu digeser ke kiri.
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.error)
                    .clickable {
                        target = 0f
                        onDelete()
                    },
                contentAlignment = Alignment.CenterEnd,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.common_hapus),
                        color = MaterialTheme.colorScheme.onError,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    TrashIcon(color = MaterialTheme.colorScheme.onError)
                }
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset { IntOffset(offset.roundToInt(), 0) }
                    .pointerInput(item.id) {
                        detectHorizontalDragGestures(
                            onDragStart = { dragging = true },
                            onDragEnd = {
                                dragging = false
                                target = if (target <= -revealWidthPx * 0.4f) {
                                    -revealWidthPx
                                } else {
                                    0f
                                }
                            },
                            onDragCancel = {
                                dragging = false
                                target = 0f
                            },
                        ) { change, dragAmount ->
                            change.consume()
                            target = (target + dragAmount).coerceIn(-revealWidthPx, 0f)
                        }
                    }
                    .clickable {
                        // Saat terbuka, ketukan pertama menutup tombol hapus.
                        if (target != 0f) target = 0f else onSelect()
                    },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Surface(
                        color = if (selected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer
                        },
                        shape = CircleShape,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (source.type == SourceType.Screen) {
                                ScreenIcon(
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    size = 18.dp,
                                )
                            } else {
                                Text(
                                    text = typeSymbol(source.type),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                            }
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = source.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${typeLabel(source.type)} • ${intrinsicLabel(source)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // Tiga aksi per item, masing-masing dengan ikon yang jelas.
                    RowIconAction(onClick = onReset) {
                        ResetIcon(
                            color = MaterialTheme.colorScheme.primary,
                            size = 20.dp,
                            description = stringResource(R.string.scenes_reset_posisi_skala_rotasi_dan_crop),
                        )
                    }
                    RowIconAction(onClick = onToggleVisible) {
                        VisibilityIcon(
                            visible = item.visible,
                            color = if (item.visible) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    RowIconAction(
                        onClick = onToggleLock,
                        // Latar tipis saat terkunci supaya posisi tombol tetap
                        // terlihat jelas walau ikon berubah warna.
                        background = if (item.locked) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                        } else {
                            null
                        },
                    ) {
                        // Ikon vector (bukan Canvas) supaya pasti tergambar.
                        Icon(
                            painter = painterResource(
                                if (item.locked) R.drawable.ic_lock else R.drawable.ic_lock_open,
                            ),
                            contentDescription = stringResource(
                                if (item.locked) R.string.icon_unlock else R.string.icon_lock,
                            ),
                            tint = if (item.locked) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Tombol ikon ringkas dengan area sentuh yang nyaman. */
@Composable
internal fun RowIconAction(
    onClick: () -> Unit,
    background: Color? = null,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .then(
                if (background != null) {
                    Modifier.background(background)
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
