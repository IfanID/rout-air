package com.rout.air.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.rout.air.core.AudioChannel
import com.rout.air.core.ChannelLevel
import com.rout.air.core.FaderScale
import com.rout.air.core.MeterDecay
import com.rout.air.data.AudioMixer
import com.rout.air.ui.theme.AccentCyan
import com.rout.air.ui.theme.Divider
import com.rout.air.ui.theme.ErrorRed
import com.rout.air.ui.theme.OnAccent
import com.rout.air.ui.theme.SurfaceBorder
import com.rout.air.ui.theme.TextPrimary
import com.rout.air.ui.theme.TextSecondary
import com.rout.air.ui.theme.TextTertiary
import com.rout.air.ui.theme.WarningAmber
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/** Kategori chip yang mengikuti prioritas OBS. */
internal enum class ChannelCategory(val label: String) {
    Unassigned("Tanpa track"),
    Pinned("Disematkan"),
    Hidden("Disembunyikan"),
    Active("Aktif"),
    Inactive("Nonaktif"),
}

internal data class CategoryStyle(
    val background: Color,
    val foreground: Color,
)

internal fun categoryFor(channel: AudioChannel, active: Boolean): ChannelCategory = when {
    channel.tracks and 0x3F == 0 -> ChannelCategory.Unassigned
    channel.pinned -> ChannelCategory.Pinned
    channel.hidden -> ChannelCategory.Hidden
    active -> ChannelCategory.Active
    else -> ChannelCategory.Inactive
}

private fun channelSubtitle(channel: AudioChannel): String = when {
    channel.appUid != 0 -> channel.appPackage ?: "Application Audio Capture"
    channel.capture == com.rout.air.core.AudioCaptureKind.Microphone ->
        channel.deviceName ?: "Mikrofon default"
    channel.sourceId != null -> "Sumber audio"
    else -> "Semua aplikasi (playback)"
}

internal fun categoryStyle(category: ChannelCategory): CategoryStyle = when (category) {
    ChannelCategory.Active -> CategoryStyle(AccentCyan.copy(alpha = 0.18f), AccentCyan)
    ChannelCategory.Pinned -> CategoryStyle(AccentCyan.copy(alpha = 0.3f), TextPrimary)
    ChannelCategory.Hidden -> CategoryStyle(Color(0xFF2E1A60), Color(0xFF997FDC))
    ChannelCategory.Unassigned -> CategoryStyle(Color(0xFF493709), Color(0xFFEABC48))
    ChannelCategory.Inactive -> CategoryStyle(Color(0xFF13141A), TextTertiary)
}

@Composable
internal fun StatusChip(category: ChannelCategory, modifier: Modifier = Modifier) {
    val style = categoryStyle(category)
    Text(
        text = category.label,
        style = MaterialTheme.typography.labelSmall.copy(
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
        ),
        color = style.foreground,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(style.background)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** Label volume OBS: teks polos, bukan kotak. */
@Composable
internal fun VolumeReadout(gainDb: Float, usePercent: Boolean, modifier: Modifier = Modifier) {
    Text(
        text = if (usePercent) {
            "${percentOfDb(gainDb)}%"
        } else {
            FaderScale.formatDb(gainDb)
        },
        style = MaterialTheme.typography.labelMedium.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
        ),
        color = TextPrimary,
        maxLines = 1,
        textAlign = TextAlign.End,
        modifier = modifier.width(72.dp),
    )
}

/**
 * Fader log OBS: groove tipis, isi cyan, handle putih, tick tiap 10 dB.
 */
@Composable
internal fun VolumeFader(
    gainDb: Float,
    enabled: Boolean,
    vertical: Boolean,
    onGainChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val latest by rememberUpdatedState(onGainChange)
    val deflection = FaderScale.dbToDeflection(gainDb)
    val knobColor = if (enabled) TextPrimary else TextTertiary
    val fillColor = if (enabled) AccentCyan else TextTertiary

    Canvas(
        modifier = modifier
            .then(if (vertical) Modifier.width(34.dp) else Modifier.height(34.dp))
            .pointerInput(enabled, vertical) {
                if (!enabled) return@pointerInput
                fun emit(position: Offset) {
                    val raw = if (vertical) {
                        1f - position.y / size.height.toFloat()
                    } else {
                        position.x / size.width.toFloat()
                    }
                    latest(FaderScale.snapDb(FaderScale.deflectionToDb(raw)))
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    emit(down.position)
                    drag(down.id) { change ->
                        emit(change.position)
                        change.consume()
                    }
                }
            }
            .semantics { contentDescription = "Volume ${FaderScale.formatDb(gainDb)}" },
    ) {
        val pad = 7.dp.toPx()
        val groove = 4.dp.toPx()
        val knobShort = 10.dp.toPx()
        val knobLong = 20.dp.toPx()
        val tickColor = Color(0xFF5B6273)
        val grooveColor = Color(0xFF1D1F26)

        if (vertical) {
            val centerX = size.width / 2f
            val top = pad
            val bottom = size.height - pad
            val length = bottom - top
            val knobY = top + (1f - deflection) * length

            drawRoundRect(
                color = grooveColor,
                topLeft = Offset(centerX - groove / 2f, top),
                size = Size(groove, length),
                cornerRadius = CornerRadius(groove / 2f),
            )
            drawRoundRect(
                color = fillColor,
                topLeft = Offset(centerX - groove / 2f, knobY),
                size = Size(groove, bottom - knobY),
                cornerRadius = CornerRadius(groove / 2f),
            )
            for (db in -10 downTo -96 step 10) {
                val y = top + (1f - FaderScale.dbToDeflection(db.toFloat())) * length
                drawLine(
                    color = tickColor,
                    start = Offset(centerX - 5.dp.toPx(), y),
                    end = Offset(centerX + 5.dp.toPx(), y),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            drawRoundRect(
                color = knobColor,
                topLeft = Offset(centerX - knobLong / 2f, knobY - knobShort / 2f),
                size = Size(knobLong, knobShort),
                cornerRadius = CornerRadius(4.dp.toPx()),
            )
        } else {
            val centerY = size.height / 2f
            val left = pad
            val right = size.width - pad
            val length = right - left
            val knobX = left + deflection * length

            drawRoundRect(
                color = grooveColor,
                topLeft = Offset(left, centerY - groove / 2f),
                size = Size(length, groove),
                cornerRadius = CornerRadius(groove / 2f),
            )
            drawRoundRect(
                color = fillColor,
                topLeft = Offset(left, centerY - groove / 2f),
                size = Size(knobX - left, groove),
                cornerRadius = CornerRadius(groove / 2f),
            )
            for (db in -10 downTo -90 step 10) {
                val x = left + FaderScale.dbToDeflection(db.toFloat()) * length
                drawLine(
                    color = tickColor,
                    start = Offset(x, centerY - 6.dp.toPx()),
                    end = Offset(x, centerY + 6.dp.toPx()),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            drawRoundRect(
                color = knobColor,
                topLeft = Offset(knobX - knobShort / 2f, centerY - knobLong / 2f),
                size = Size(knobShort, knobLong),
                cornerRadius = CornerRadius(4.dp.toPx()),
            )
        }
    }
}

/**
 * Meter OBS: dua lane L/R, band hijau -60..-20, kuning -20..-9, merah -9..0,
 * peak hold dengan decay, dan penanda clip.
 */
@Composable
internal fun VolumeMeter(
    level: ChannelLevel,
    active: Boolean,
    decay: MeterDecay,
    vertical: Boolean,
    modifier: Modifier = Modifier,
) {
    val targetLeft = if (active) level.leftDb else FaderScale.MIN_DB
    val targetRight = if (active) level.rightDb else FaderScale.MIN_DB
    val left by animateFloatAsState(targetLeft, tween(60), label = "meterL")
    val right by animateFloatAsState(targetRight, tween(60), label = "meterR")

    var peakLeft by remember { mutableFloatStateOf(FaderScale.MIN_DB) }
    var peakRight by remember { mutableFloatStateOf(FaderScale.MIN_DB) }
    val targetPeakLeft = if (active) level.peakLeftDb else FaderScale.MIN_DB
    val targetPeakRight = if (active) level.peakRightDb else FaderScale.MIN_DB
    val currentPeakLeft by rememberUpdatedState(targetPeakLeft)
    val currentPeakRight by rememberUpdatedState(targetPeakRight)
    val decayPerTick = decay.dbPerSecond * 0.05f

    LaunchedEffect(active) {
        while (true) {
            delay(50)
            peakLeft = if (!active) {
                FaderScale.MIN_DB
            } else {
                max(currentPeakLeft, peakLeft - decayPerTick)
            }
            peakRight = if (!active) {
                FaderScale.MIN_DB
            } else {
                max(currentPeakRight, peakRight - decayPerTick)
            }
        }
    }

    Canvas(
        modifier = modifier.then(
            if (vertical) Modifier.width(12.dp) else Modifier.height(12.dp),
        ),
    ) {
        val gap = 1.5.dp.toPx()
        if (vertical) {
            val laneWidth = (size.width - gap) / 2f
            drawLane(
                left = 0f,
                width = laneWidth,
                level = left,
                peak = if (active) peakLeft else FaderScale.MIN_DB,
                vertical = true,
                active = active,
            )
            drawLane(
                left = laneWidth + gap,
                width = laneWidth,
                level = right,
                peak = if (active) peakRight else FaderScale.MIN_DB,
                vertical = true,
                active = active,
            )
        } else {
            val laneHeight = (size.height - gap) / 2f
            drawLane(
                left = 0f,
                width = size.width,
                top = 0f,
                height = laneHeight,
                level = left,
                peak = if (active) peakLeft else FaderScale.MIN_DB,
                vertical = false,
                active = active,
            )
            drawLane(
                left = 0f,
                width = size.width,
                top = laneHeight + gap,
                height = laneHeight,
                level = right,
                peak = if (active) peakRight else FaderScale.MIN_DB,
                vertical = false,
                active = active,
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLane(
    left: Float,
    width: Float,
    level: Float,
    peak: Float,
    vertical: Boolean,
    active: Boolean,
    top: Float = 0f,
    height: Float = size.height,
) {
    val meterMin = -60f
    val meterMax = 0f

    fun position(db: Float): Float =
        ((db - meterMin) / (meterMax - meterMin)).coerceIn(0f, 1f)

    val greenEnd = position(-20f)
    val yellowEnd = position(-9f)
    val bands = listOf(
        Triple(0f, greenEnd, Color(0xFF17641E)),
        Triple(greenEnd, yellowEnd, Color(0xFF6E520D)),
        Triple(yellowEnd, 1f, Color(0xFF7D1224)),
    )
    val bandAlpha = if (active) 0.45f else 0.10f
    val bright = listOf(
        Triple(0f, greenEnd, Color(0xFF37D247)),
        Triple(greenEnd, yellowEnd, Color(0xFFE5AF24)),
        Triple(yellowEnd, 1f, Color(0xFFE33B57)),
    )

    if (vertical) {
        bands.forEach { (start, end, color) ->
            val y1 = size.height - end * size.height
            val y2 = size.height - start * size.height
            drawRect(
                color = color.copy(alpha = bandAlpha),
                topLeft = Offset(left, y1),
                size = Size(width, y2 - y1),
            )
        }
        val levelPos = position(level)
        bright.forEach { (start, end, color) ->
            val topDb = start
            val bottomDb = minOf(end, levelPos)
            if (levelPos > start) {
                val y1 = size.height - bottomDb * size.height
                val y2 = size.height - topDb * size.height
                drawRect(color = color, topLeft = Offset(left, y1), size = Size(width, y2 - y1))
            }
        }
        val peakPos = position(peak)
        if (peakPos > 0.001f) {
            drawLine(
                color = Color.White.copy(alpha = 0.9f),
                start = Offset(left, size.height - peakPos * size.height),
                end = Offset(left + width, size.height - peakPos * size.height),
                strokeWidth = 1.5.dp.toPx(),
            )
        }
        if (peak >= 0f) {
            drawRect(
                color = Color(0xFFE33B57),
                topLeft = Offset(left, 0f),
                size = Size(width, 2.dp.toPx()),
            )
        }
    } else {
        bands.forEach { (start, end, color) ->
            val x1 = left + start * width
            val x2 = left + end * width
            drawRect(
                color = color.copy(alpha = bandAlpha),
                topLeft = Offset(x1, top),
                size = Size(x2 - x1, height),
            )
        }
        val levelPos = position(level)
        bright.forEach { (start, end, color) ->
            val litEnd = minOf(end, levelPos)
            if (levelPos > start) {
                val x1 = left + start * width
                val x2 = left + litEnd * width
                drawRect(color = color, topLeft = Offset(x1, top), size = Size(x2 - x1, height))
            }
        }
        val peakPos = position(peak)
        if (peakPos > 0.001f) {
            val x = left + peakPos * width
            drawLine(
                color = Color.White.copy(alpha = 0.9f),
                start = Offset(x, top),
                end = Offset(x, top + height),
                strokeWidth = 1.5.dp.toPx(),
            )
        }
        if (peak >= 0f) {
            drawRect(
                color = Color(0xFFE33B57),
                topLeft = Offset(left + width - 2.dp.toPx(), top),
                size = Size(2.dp.toPx(), height),
            )
        }
    }
}

/** Baris kanal untuk tata letak horizontal OBS. */
@Composable
internal fun AudioChannelRow(
    channel: AudioChannel,
    category: ChannelCategory,
    level: ChannelLevel,
    active: Boolean,
    usePercent: Boolean,
    decay: MeterDecay,
    onChannelChange: (AudioChannel) -> Unit,
    onUnhideAll: () -> Unit,
    onRename: () -> Unit,
    onFilters: () -> Unit,
    onProperties: () -> Unit,
    canPasteFilters: Boolean,
    onCopyFilters: () -> Unit,
    onPasteFilters: () -> Unit,
    onChangeDevice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var rowHeightPx by remember(channel.id) { mutableFloatStateOf(0f) }
    var dragY by remember(channel.id) { mutableFloatStateOf(0f) }
    var reordering by remember(channel.id) { mutableStateOf(false) }
    val density = LocalDensity.current
    val dragOffset by animateFloatAsState(
        targetValue = dragY,
        animationSpec = if (reordering) snap() else tween(durationMillis = 160),
        label = "geser-audio",
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .zIndex(if (reordering) 1f else 0f)
            .onSizeChanged { rowHeightPx = it.height.toFloat() }
            .offset { IntOffset(0, dragOffset.roundToInt()) }
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Box {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .pointerInput(channel.id) {
                            detectVerticalDragGestures(
                                onDragStart = { reordering = true },
                                onDragEnd = {
                                    reordering = false
                                    val pitch = (rowHeightPx + with(density) { 1.dp.toPx() })
                                        .coerceAtLeast(1f)
                                    val step = (dragY / pitch).roundToInt()
                                    dragY = 0f
                                    if (step != 0) {
                                        AudioMixer.moveChannel(channel.id, step)
                                    }
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
                    DragHandleIcon(color = TextTertiary, size = 16.dp)
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { menuOpen = true }
                        .padding(vertical = 2.dp),
                ) {
                    Text(
                        text = channel.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (channel.enabled && active) TextPrimary else TextTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = channelSubtitle(channel),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = TextTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                StatusChip(category)
                Spacer(Modifier.width(4.dp))
                // On/off kanal tepat di kanan label Aktif/Nonaktif.
                MixerIconButton(
                    onClick = {
                        onChannelChange(channel.copy(enabled = !channel.enabled))
                    },
                    description = if (channel.enabled) "Nonaktifkan kanal" else "Aktifkan kanal",
                    active = !channel.enabled,
                    activeColor = ErrorRed,
                    activeBackground = Color.Transparent,
                ) { color -> PowerIcon(color, size = 16.dp) }
                Spacer(Modifier.width(8.dp))
                VolumeReadout(channel.gainDb, usePercent)
            }
            ChannelContextMenu(
                expanded = menuOpen,
                channel = channel,
                canPasteFilters = canPasteFilters,
                onDismiss = { menuOpen = false },
                onChangeDevice = onChangeDevice,
                onUnhideAll = {
                    menuOpen = false
                    onUnhideAll()
                },
                onPin = {
                    onChannelChange(channel.copy(pinned = !channel.pinned, hidden = false))
                    menuOpen = false
                },
                onHide = {
                    onChannelChange(channel.copy(hidden = !channel.hidden, pinned = false))
                    menuOpen = false
                },
                onLock = {
                    onChannelChange(channel.copy(locked = !channel.locked))
                    menuOpen = false
                },
                onCopyFilters = {
                    onCopyFilters()
                    menuOpen = false
                },
                onPasteFilters = {
                    onPasteFilters()
                    menuOpen = false
                },
                onRename = {
                    menuOpen = false
                    onRename()
                },
                onFilters = {
                    menuOpen = false
                    onFilters()
                },
                onProperties = {
                    menuOpen = false
                    onProperties()
                },
            )
        }

        Spacer(Modifier.height(6.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                MixerIconButton(
                    onClick = {
                        onChannelChange(channel.copy(muted = !channel.muted))
                    },
                    description = if (channel.muted) "Aktifkan suara" else "Bisukan",
                    active = channel.muted,
                    activeColor = ErrorRed,
                    activeBackground = Color.Transparent,
                ) { color -> SpeakerIcon(channel.muted, color, 18.dp) }
                MixerIconButton(
                    onClick = {
                        onChannelChange(
                            channel.copy(
                                monitor = if (channel.monitor.monitors) {
                                    com.rout.air.core.MonitorMode.Off
                                } else {
                                    com.rout.air.core.MonitorMode.MonitorAndOutput
                                },
                            ),
                        )
                    },
                    description = if (channel.monitor.monitors) {
                        "Matikan monitoring"
                    } else {
                        "Aktifkan monitoring"
                    },
                    active = channel.monitor.monitors,
                    activeColor = Color.White,
                    activeBackground = Color(0xFF1E8528),
                ) { color -> HeadphoneIcon(channel.monitor.monitors, color, 18.dp) }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                VolumeFader(
                    gainDb = channel.gainDb,
                    enabled = channel.enabled && !channel.locked,
                    vertical = false,
                    onGainChange = { onChannelChange(channel.copy(gainDb = it)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                VolumeMeter(
                    level = level,
                    active = active && channel.enabled,
                    decay = decay,
                    vertical = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (channel.locked) {
                Spacer(Modifier.width(8.dp))
                LockIcon(locked = true, color = TextTertiary, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** Kolom kanal untuk tata letak vertikal OBS. */
@Composable
internal fun AudioChannelColumn(
    channel: AudioChannel,
    category: ChannelCategory,
    level: ChannelLevel,
    active: Boolean,
    usePercent: Boolean,
    decay: MeterDecay,
    width: Dp,
    onChannelChange: (AudioChannel) -> Unit,
    onUnhideAll: () -> Unit,
    onRename: () -> Unit,
    onFilters: () -> Unit,
    onProperties: () -> Unit,
    canPasteFilters: Boolean,
    onCopyFilters: () -> Unit,
    onPasteFilters: () -> Unit,
    onChangeDevice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .width(width)
            .fillMaxHeight()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            // Label status + tombol on/off berdampingan.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                StatusChip(category)
                MixerIconButton(
                    onClick = {
                        onChannelChange(channel.copy(enabled = !channel.enabled))
                    },
                    description = if (channel.enabled) "Nonaktifkan kanal" else "Aktifkan kanal",
                    active = !channel.enabled,
                    activeColor = ErrorRed,
                    activeBackground = Color.Transparent,
                ) { color -> PowerIcon(color, size = 15.dp) }
            }
        }
        Spacer(Modifier.height(4.dp))
        Box {
            Text(
                text = channel.name,
                style = MaterialTheme.typography.labelMedium,
                color = if (channel.enabled && active) TextPrimary else TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { menuOpen = true }
                    .padding(vertical = 2.dp),
            )
            ChannelContextMenu(
                expanded = menuOpen,
                channel = channel,
                canPasteFilters = canPasteFilters,
                onDismiss = { menuOpen = false },
                onChangeDevice = onChangeDevice,
                onUnhideAll = {
                    menuOpen = false
                    onUnhideAll()
                },
                onPin = {
                    onChannelChange(channel.copy(pinned = !channel.pinned, hidden = false))
                    menuOpen = false
                },
                onHide = {
                    onChannelChange(channel.copy(hidden = !channel.hidden, pinned = false))
                    menuOpen = false
                },
                onLock = {
                    onChannelChange(channel.copy(locked = !channel.locked))
                    menuOpen = false
                },
                onCopyFilters = {
                    onCopyFilters()
                    menuOpen = false
                },
                onPasteFilters = {
                    onPasteFilters()
                    menuOpen = false
                },
                onRename = {
                    menuOpen = false
                    onRename()
                },
                onFilters = {
                    menuOpen = false
                    onFilters()
                },
                onProperties = {
                    menuOpen = false
                    onProperties()
                },
            )
        }
        VolumeReadout(channel.gainDb, usePercent)
        Spacer(Modifier.height(6.dp))

        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.Center,
        ) {
            VolumeFader(
                gainDb = channel.gainDb,
                enabled = channel.enabled && !channel.locked,
                vertical = true,
                onGainChange = { onChannelChange(channel.copy(gainDb = it)) },
                modifier = Modifier.fillMaxHeight().width(34.dp),
            )
            Spacer(Modifier.width(4.dp))
            VolumeMeter(
                level = level,
                active = active && channel.enabled,
                decay = decay,
                vertical = true,
                modifier = Modifier.fillMaxHeight().width(12.dp),
            )
        }

        if (channel.locked) {
            Spacer(Modifier.height(4.dp))
            LockIcon(locked = true, color = TextTertiary, modifier = Modifier.size(14.dp))
        }

        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            MixerIconButton(
                onClick = { onChannelChange(channel.copy(muted = !channel.muted)) },
                description = if (channel.muted) "Aktifkan suara" else "Bisukan",
                active = channel.muted,
                activeColor = ErrorRed,
                activeBackground = Color.Transparent,
            ) { color -> SpeakerIcon(channel.muted, color, 17.dp) }
            MixerIconButton(
                onClick = {
                    onChannelChange(
                        channel.copy(
                            monitor = if (channel.monitor.monitors) {
                                com.rout.air.core.MonitorMode.Off
                            } else {
                                com.rout.air.core.MonitorMode.MonitorAndOutput
                            },
                        ),
                    )
                },
                description = if (channel.monitor.monitors) {
                    "Matikan monitoring"
                } else {
                    "Aktifkan monitoring"
                },
                active = channel.monitor.monitors,
                activeColor = Color.White,
                activeBackground = Color(0xFF1E8528),
            ) { color -> HeadphoneIcon(channel.monitor.monitors, color, 17.dp) }
        }
    }
}

@Composable
private fun ChannelContextMenu(
    expanded: Boolean,
    channel: AudioChannel,
    canPasteFilters: Boolean,
    onDismiss: () -> Unit,
    onChangeDevice: () -> Unit,
    onUnhideAll: () -> Unit,
    onPin: () -> Unit,
    onHide: () -> Unit,
    onLock: () -> Unit,
    onCopyFilters: () -> Unit,
    onPasteFilters: () -> Unit,
    onRename: () -> Unit,
    onFilters: () -> Unit,
    onProperties: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text("Tampilkan semua yang disembunyikan") }, onClick = onUnhideAll)
        HorizontalDivider(color = Divider)
        DropdownMenuItem(
            text = { Text(if (channel.pinned) "Lepas sematan" else "Sematkan") },
            onClick = onPin,
        )
        DropdownMenuItem(
            text = { Text(if (channel.hidden) "Tampilkan" else "Sembunyikan") },
            onClick = onHide,
        )
        DropdownMenuItem(
            text = { Text(if (channel.locked) "Buka kunci volume" else "Kunci volume") },
            onClick = onLock,
        )
        HorizontalDivider(color = Divider)
        DropdownMenuItem(
            text = { Text("Pindah ke atas") },
            onClick = {
                AudioMixer.moveChannel(channel.id, -1)
                onDismiss()
            },
        )
        DropdownMenuItem(
            text = { Text("Pindah ke bawah") },
            onClick = {
                AudioMixer.moveChannel(channel.id, 1)
                onDismiss()
            },
        )
        if (channel.capture == com.rout.air.core.AudioCaptureKind.Microphone) {
            DropdownMenuItem(
                text = { Text("Ganti perangkat input…") },
                onClick = {
                    onDismiss()
                    onChangeDevice()
                },
            )
        }
        if (channel.appUid != 0) {
            DropdownMenuItem(
                text = { Text("Hapus kanal audio") },
                onClick = {
                    AudioMixer.removeChannel(channel.id)
                    onDismiss()
                },
            )
        }
        HorizontalDivider(color = Divider)
        DropdownMenuItem(
            text = { Text("Salin filter") },
            enabled = channel.filters.isNotEmpty(),
            onClick = onCopyFilters,
        )
        DropdownMenuItem(
            text = { Text("Tempel filter") },
            enabled = canPasteFilters,
            onClick = onPasteFilters,
        )
        DropdownMenuItem(text = { Text("Ganti nama…") }, onClick = onRename)
        HorizontalDivider(color = Divider)
        DropdownMenuItem(text = { Text("Filter…") }, onClick = onFilters)
        DropdownMenuItem(text = { Text("Properti…") }, onClick = onProperties)
    }
}

/** Tombol ikon datar ala OBS. */
@Composable
internal fun MixerIconButton(
    onClick: () -> Unit,
    description: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    activeColor: Color = AccentCyan,
    activeBackground: Color = Color.Transparent,
    enabled: Boolean = true,
    content: @Composable (Color) -> Unit,
) {
    Box(
        modifier = modifier
            .size(width = 30.dp, height = 30.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (active) activeBackground else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        content(
            when {
                !enabled -> TextTertiary
                active -> activeColor
                else -> TextSecondary
            },
        )
    }
}

// --- Ikon vektor kecil -----------------------------------------------------

@Composable
internal fun SpeakerIcon(muted: Boolean, color: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val u = this.size.minDimension / 24f
        val body = Path().apply {
            moveTo(3.5f * u, 9.5f * u)
            lineTo(7.5f * u, 9.5f * u)
            lineTo(12f * u, 5.5f * u)
            lineTo(12f * u, 18.5f * u)
            lineTo(7.5f * u, 14.5f * u)
            lineTo(3.5f * u, 14.5f * u)
            close()
        }
        drawPath(body, color)
        val stroke = 1.8f * u
        if (muted) {
            drawLine(
                color,
                Offset(15f * u, 9f * u),
                Offset(20.5f * u, 15f * u),
                stroke,
                StrokeCap.Round,
            )
            drawLine(
                color,
                Offset(20.5f * u, 9f * u),
                Offset(15f * u, 15f * u),
                stroke,
                StrokeCap.Round,
            )
        } else {
            drawArc(
                color,
                -50f,
                100f,
                false,
                Offset(11f * u, 8f * u),
                Size(7f * u, 8f * u),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            drawArc(
                color,
                -50f,
                100f,
                false,
                Offset(12f * u, 5.5f * u),
                Size(10f * u, 13f * u),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
    }
}

@Composable
internal fun HeadphoneIcon(active: Boolean, color: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val u = this.size.minDimension / 24f
        val stroke = 1.9f * u
        drawArc(
            color,
            180f,
            180f,
            false,
            Offset(4f * u, 5f * u),
            Size(16f * u, 16f * u),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        drawRoundRect(
            color = if (active) color else Color.Transparent,
            topLeft = Offset(4f * u, 12.5f * u),
            size = Size(4.2f * u, 7f * u),
            cornerRadius = CornerRadius(1.8f * u),
        )
        drawRoundRect(
            color = if (active) color else Color.Transparent,
            topLeft = Offset(15.8f * u, 12.5f * u),
            size = Size(4.2f * u, 7f * u),
            cornerRadius = CornerRadius(1.8f * u),
        )
        drawRoundRect(
            color,
            Offset(4f * u, 12.5f * u),
            Size(4.2f * u, 7f * u),
            CornerRadius(1.8f * u),
            style = Stroke(width = 1.6f * u),
        )
        drawRoundRect(
            color,
            Offset(15.8f * u, 12.5f * u),
            Size(4.2f * u, 7f * u),
            CornerRadius(1.8f * u),
            style = Stroke(width = 1.6f * u),
        )
    }
}

/** Ikon power: menandai kanal aktif/nonaktif. */
@Composable
internal fun PowerIcon(color: Color, size: Dp = 18.dp) {
    Canvas(Modifier.size(size)) {
        val u = this.size.minDimension / 24f
        val stroke = 2.2f * u
        // Busur hampir penuh dengan celah di atas.
        drawArc(
            color = color,
            startAngle = 300f,
            sweepAngle = 300f,
            useCenter = false,
            topLeft = Offset(4f * u, 4f * u),
            size = Size(16f * u, 16f * u),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        // Garis vertikal di tengah atas.
        drawLine(
            color = color,
            start = Offset(12f * u, 2.8f * u),
            end = Offset(12f * u, 11.5f * u),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
internal fun LockIcon(locked: Boolean, color: Color, modifier: Modifier = Modifier) {    Canvas(modifier) {
        val u = this.size.minDimension / 24f
        val stroke = 2f * u
        if (locked) {
            drawArc(
                color,
                180f,
                180f,
                false,
                Offset(7f * u, 4.5f * u),
                Size(10f * u, 10f * u),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        } else {
            drawArc(
                color,
                200f,
                160f,
                false,
                Offset(9f * u, 3.5f * u),
                Size(10f * u, 10f * u),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        drawRoundRect(
            color,
            Offset(4.5f * u, 10.5f * u),
            Size(15f * u, 9.5f * u),
            CornerRadius(2.6f * u),
        )
    }
}

@Composable
internal fun GearIcon(color: Color, size: Dp = 18.dp) {
    Canvas(Modifier.size(size)) {
        val u = this.size.minDimension / 24f
        val stroke = 1.8f * u
        drawCircle(color, 4.2f * u, Offset(12f * u, 12f * u), style = Stroke(width = stroke))
        for (i in 0 until 8) {
            val angle = i * 45.0 * Math.PI / 180.0
            val start = Offset(
                12f * u + (6.2f * u * kotlin.math.cos(angle)).toFloat(),
                12f * u + (6.2f * u * kotlin.math.sin(angle)).toFloat(),
            )
            val end = Offset(
                12f * u + (9f * u * kotlin.math.cos(angle)).toFloat(),
                12f * u + (9f * u * kotlin.math.sin(angle)).toFloat(),
            )
            drawLine(color, start, end, stroke, StrokeCap.Round)
        }
    }
}

@Composable
internal fun LayoutIcon(vertical: Boolean, color: Color, size: Dp = 18.dp) {
    Canvas(Modifier.size(size)) {
        val u = this.size.minDimension / 24f
        val stroke = 1.7f * u
        drawRoundRect(
            color,
            Offset(3f * u, 4f * u),
            Size(18f * u, 16f * u),
            CornerRadius(2.5f * u),
            style = Stroke(width = stroke),
        )
        if (vertical) {
            drawLine(color, Offset(9f * u, 4f * u), Offset(9f * u, 20f * u), stroke)
            drawLine(color, Offset(15f * u, 4f * u), Offset(15f * u, 20f * u), stroke)
        } else {
            drawLine(color, Offset(3f * u, 9.3f * u), Offset(21f * u, 9.3f * u), stroke)
            drawLine(color, Offset(3f * u, 14.7f * u), Offset(21f * u, 14.7f * u), stroke)
        }
    }
}

@Composable
internal fun FilterIcon(color: Color, size: Dp = 16.dp) {
    Canvas(Modifier.size(size)) {
        val u = this.size.minDimension / 24f
        val path = Path().apply {
            moveTo(3f * u, 5f * u)
            lineTo(21f * u, 5f * u)
            lineTo(14f * u, 12.5f * u)
            lineTo(14f * u, 19f * u)
            lineTo(10f * u, 21f * u)
            lineTo(10f * u, 12.5f * u)
            close()
        }
        drawPath(path, color)
    }
}

/** Tombol tahan untuk push-to-talk / push-to-mute. */
@Composable
internal fun HoldButton(
    label: String,
    activeColor: Color,
    onHoldChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var held by remember { mutableStateOf(false) }
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = if (held) Color.White else TextSecondary,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (held) activeColor else Color.Transparent)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    held = true
                    onHoldChange(true)
                    waitForUpOrCancellation()
                    held = false
                    onHoldChange(false)
                }
            }
            .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

private fun percentOfDb(gainDb: Float): Int =
    (10f.pow(gainDb / 20f) * 100f).roundToInt()
