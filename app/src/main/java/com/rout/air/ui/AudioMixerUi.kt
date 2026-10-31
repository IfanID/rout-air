package com.rout.air.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
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
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import androidx.compose.ui.res.stringResource
import com.rout.air.R

/** Kategori chip yang mengikuti prioritas OBS. */
internal enum class ChannelCategory {
    Unassigned,
    Pinned,
    Hidden,
    Active,
    Inactive,
}

@Composable
internal fun filterLabel(type: com.rout.air.core.AudioFilterType): String = when (type) {
    com.rout.air.core.AudioFilterType.Gain -> stringResource(R.string.audio_filter_gain)
    com.rout.air.core.AudioFilterType.NoiseGate -> stringResource(R.string.audio_filter_noise_gate)
    com.rout.air.core.AudioFilterType.Compressor -> stringResource(R.string.audio_filter_compressor)
    com.rout.air.core.AudioFilterType.Limiter -> stringResource(R.string.audio_filter_limiter)
    com.rout.air.core.AudioFilterType.InvertPolarity -> stringResource(R.string.audio_filter_invert)
    com.rout.air.core.AudioFilterType.NoiseSuppression -> stringResource(R.string.audio_filter_suppression)
    com.rout.air.core.AudioFilterType.Equalizer -> stringResource(R.string.audio_filter_equalizer)
}

@Composable
internal fun monitorLabel(mode: com.rout.air.core.MonitorMode): String = when (mode) {
    com.rout.air.core.MonitorMode.Off -> stringResource(R.string.audio_monitor_off)
    com.rout.air.core.MonitorMode.MonitorOnly -> stringResource(R.string.audio_monitor_only)
    com.rout.air.core.MonitorMode.MonitorAndOutput -> stringResource(R.string.audio_monitor_output)
}

@Composable
internal fun decayLabel(decay: com.rout.air.core.MeterDecay): String = when (decay) {
    com.rout.air.core.MeterDecay.Fast -> stringResource(R.string.audio_decay_fast)
    com.rout.air.core.MeterDecay.Medium -> stringResource(R.string.audio_decay_medium)
    com.rout.air.core.MeterDecay.Slow -> stringResource(R.string.audio_decay_slow)
}

@Composable
internal fun peakMeterLabel(type: com.rout.air.core.PeakMeterType): String = when (type) {
    com.rout.air.core.PeakMeterType.Sample -> stringResource(R.string.audio_peak_sample)
    com.rout.air.core.PeakMeterType.TruePeak -> stringResource(R.string.audio_peak_true)
}

@Composable
internal fun captureKindLabel(kind: com.rout.air.core.AudioCaptureKind): String = when (kind) {
    com.rout.air.core.AudioCaptureKind.Playback -> stringResource(R.string.audio_kind_playback)
    com.rout.air.core.AudioCaptureKind.Microphone -> stringResource(R.string.audio_kind_microphone)
}

@Composable
private fun categoryLabel(category: ChannelCategory): String = when (category) {
    ChannelCategory.Unassigned -> stringResource(R.string.audio_tanpa_track)
    ChannelCategory.Pinned -> stringResource(R.string.audio_disematkan)
    ChannelCategory.Hidden -> stringResource(R.string.audio_disembunyikan)
    ChannelCategory.Active -> stringResource(R.string.common_aktif)
    ChannelCategory.Inactive -> stringResource(R.string.common_nonaktif)
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
        text = categoryLabel(category),
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
    val volumeDesc = stringResource(R.string.audio_volume_desc, FaderScale.formatDb(gainDb))
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
            .semantics { contentDescription = volumeDesc },
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
// --- Meter ala OBS (VolumeMeter.cpp) --------------------------------------
private const val METER_MIN_DB = -60f
private const val METER_MAX_DB = 0f
private const val METER_WARNING_DB = -20f
private const val METER_ERROR_DB = -9f
private const val METER_INPUT_MIN_DB = -50f
private const val METER_PEAK_HOLD_SECONDS = 20.0
private const val METER_CLIP_FLASH_NS = 1_000_000_000L

private val MeterBgNominal = Color(0xFF267F26)
private val MeterBgWarning = Color(0xFF7F7F26)
private val MeterBgError = Color(0xFF7F2626)
private val MeterFgNominal = Color(0xFF4CFF4C)
private val MeterFgWarning = Color(0xFFFFFF4C)
private val MeterFgError = Color(0xFFFF4C4C)
private val MeterBgNominalOff = Color(0xFF5A5A5A)
private val MeterBgWarningOff = Color(0xFF757575)
private val MeterBgErrorOff = Color(0xFF414141)
private val MeterFgNominalOff = Color(0xFFA3A3A3)
private val MeterFgWarningOff = Color(0xFFD9D9D9)
private val MeterFgErrorOff = Color(0xFF717171)
private val MeterClipColor = Color(0xFFFFFFFF)
private val MeterMagnitudeColor = Color(0xFF000000)

/**
 * Meter volume mengikuti OBS VolumeMeter.cpp: band hijau/kuning/merah,
 * peak-hold 20 detik, indikator magnitude, indikator input, clip 1 detik,
 * dan label skala tiap 6 dB.
 */
@Composable
internal fun VolumeMeter(
    level: ChannelLevel,
    active: Boolean,
    decay: MeterDecay,
    vertical: Boolean,
    modifier: Modifier = Modifier,
    showScale: Boolean = false,
    muted: Boolean = false,
    peakType: com.rout.air.core.PeakMeterType = com.rout.air.core.PeakMeterType.Sample,
) {
    // True-peak memakai ambang EBU yang berbeda (OBS VolumeMeter::setPeakMeterType).
    val warningDb = if (peakType == com.rout.air.core.PeakMeterType.TruePeak) -13f else -20f
    val errorDb = if (peakType == com.rout.air.core.PeakMeterType.TruePeak) -2f else -9f
    val targetLeft = if (active) level.leftDb else METER_MIN_DB
    val targetRight = if (active) level.rightDb else METER_MIN_DB
    val left by animateFloatAsState(targetLeft, tween(33), label = stringResource(R.string.audio_meterl))
    val right by animateFloatAsState(targetRight, tween(33), label = stringResource(R.string.audio_meterr))

    var peakLeft by remember { mutableFloatStateOf(METER_MIN_DB) }
    var peakRight by remember { mutableFloatStateOf(METER_MIN_DB) }
    var holdLeft by remember { mutableFloatStateOf(METER_MIN_DB) }
    var holdRight by remember { mutableFloatStateOf(METER_MIN_DB) }
    var holdLeftAt by remember { mutableLongStateOf(0L) }
    var holdRightAt by remember { mutableLongStateOf(0L) }
    var magLeft by remember { mutableFloatStateOf(METER_MIN_DB) }
    var magRight by remember { mutableFloatStateOf(METER_MIN_DB) }
    var clipUntil by remember { mutableLongStateOf(0L) }

    val levelLeft by rememberUpdatedState(if (active) level.leftDb else METER_MIN_DB)
    val levelRight by rememberUpdatedState(if (active) level.rightDb else METER_MIN_DB)
    val peakLeftTarget by rememberUpdatedState(if (active) level.peakLeftDb else METER_MIN_DB)
    val peakRightTarget by rememberUpdatedState(if (active) level.peakRightDb else METER_MIN_DB)

    // Timer 16 ms seperti OBS; ballistics dihitung per selang waktu nyata.
    LaunchedEffect(active) {
        var last = System.nanoTime()
        while (true) {
            delay(16)
            val now = System.nanoTime()
            val dt = ((now - last) / 1_000_000_000.0).toFloat().coerceIn(0f, 1f)
            last = now
            if (!active) {
                peakLeft = METER_MIN_DB
                peakRight = METER_MIN_DB
                holdLeft = METER_MIN_DB
                holdRight = METER_MIN_DB
                magLeft = METER_MIN_DB
                magRight = METER_MIN_DB
                continue
            }
            val step = decay.dbPerSecond * dt
            peakLeft = if (peakLeftTarget >= peakLeft) {
                peakLeftTarget
            } else {
                max(peakLeftTarget, peakLeft - step)
            }
            peakRight = if (peakRightTarget >= peakRight) {
                peakRightTarget
            } else {
                max(peakRightTarget, peakRight - step)
            }

            if (peakLeftTarget >= holdLeft) {
                holdLeft = peakLeftTarget
                holdLeftAt = now
            } else if ((now - holdLeftAt) / 1_000_000_000.0 > METER_PEAK_HOLD_SECONDS) {
                holdLeft = peakLeftTarget
                holdLeftAt = now
            }
            if (peakRightTarget >= holdRight) {
                holdRight = peakRightTarget
                holdRightAt = now
            } else if ((now - holdRightAt) / 1_000_000_000.0 > METER_PEAK_HOLD_SECONDS) {
                holdRight = peakRightTarget
                holdRightAt = now
            }

            // Magnitude terintegrasi 99% dalam 300 ms.
            val k = ((dt / 0.3f) * 0.99f).coerceIn(0f, 1f)
            magLeft = (magLeft + (levelLeft - magLeft) * k).coerceIn(METER_MIN_DB, METER_MAX_DB)
            magRight = (magRight + (levelRight - magRight) * k).coerceIn(METER_MIN_DB, METER_MAX_DB)

            if (peakLeftTarget >= 0f || peakRightTarget >= 0f) {
                clipUntil = now + METER_CLIP_FLASH_NS
            }
        }
    }

    val textMeasurer = rememberTextMeasurer()
    val scaleStyle = TextStyle(color = TextTertiary, fontSize = 8.sp)
    val meterThickness = 12.dp
    val indicator = 3.dp
    val canvasSize = when {
        vertical && showScale -> Modifier.width(meterThickness + 26.dp)
        vertical -> Modifier.width(meterThickness)
        showScale -> Modifier.height(meterThickness + 16.dp)
        else -> Modifier.height(meterThickness)
    }

    Canvas(modifier = modifier.then(canvasSize)) {
        val gap = 1.5.dp.toPx()
        val meterPx = meterThickness.toPx()
        val indicatorPx = indicator.toPx()
        val clipping = System.nanoTime() < clipUntil
        val laneWidth = (meterPx - gap) / 2f

        if (vertical) {
            val meterLeft = size.width - meterPx
            val lanesHeight = (size.height - indicatorPx - 2.dp.toPx()).coerceAtLeast(1f)
            drawObsLane(
                left = meterLeft,
                width = laneWidth,
                height = lanesHeight,
                level = left,
                peak = peakLeft,
                hold = holdLeft,
                magnitude = magLeft,
                vertical = true,
                muted = muted,
                clipping = clipping,
                warningDb = warningDb,
                errorDb = errorDb,
            )
            drawObsLane(
                left = meterLeft + laneWidth + gap,
                width = laneWidth,
                height = lanesHeight,
                level = right,
                peak = peakRight,
                hold = holdRight,
                magnitude = magRight,
                vertical = true,
                muted = muted,
                clipping = clipping,
                warningDb = warningDb,
                errorDb = errorDb,
            )
            // Indikator input di dasar meter.
            drawPeakIndicator(
                left = meterLeft,
                width = laneWidth,
                top = size.height - indicatorPx,
                height = indicatorPx,
                hold = holdLeft,
                muted = muted,
                vertical = true,
                warningDb = warningDb,
                errorDb = errorDb,
            )
            drawPeakIndicator(
                left = meterLeft + laneWidth + gap,
                width = laneWidth,
                top = size.height - indicatorPx,
                height = indicatorPx,
                hold = holdRight,
                muted = muted,
                vertical = true,
                warningDb = warningDb,
                errorDb = errorDb,
            )
            if (showScale) {
                for (db in 0 downTo -60 step 6) {
                    val norm = ((db - METER_MIN_DB) / (METER_MAX_DB - METER_MIN_DB)).coerceIn(0f, 1f)
                    val y = lanesHeight * (1f - norm)
                    val layout = textMeasurer.measure(AnnotatedString(db.toString()), scaleStyle)
                    val top = (y - layout.size.height / 2f).coerceIn(0f, lanesHeight - layout.size.height)
                    drawText(
                        textLayoutResult = layout,
                        topLeft = Offset(meterLeft - 4.dp.toPx() - layout.size.width, top),
                    )
                    drawLine(
                        color = TextTertiary,
                        start = Offset(meterLeft - 2.dp.toPx(), y),
                        end = Offset(meterLeft, y),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
            }
        } else {
            val meterStart = indicatorPx + 2.dp.toPx()
            val lanesWidth = (size.width - meterStart).coerceAtLeast(1f)
            val laneHeight = (meterPx - gap) / 2f
            drawObsLane(
                left = meterStart,
                width = lanesWidth,
                top = 0f,
                height = laneHeight,
                level = left,
                peak = peakLeft,
                hold = holdLeft,
                magnitude = magLeft,
                vertical = false,
                muted = muted,
                clipping = clipping,
                warningDb = warningDb,
                errorDb = errorDb,
            )
            drawObsLane(
                left = meterStart,
                width = lanesWidth,
                top = laneHeight + gap,
                height = laneHeight,
                level = right,
                peak = peakRight,
                hold = holdRight,
                magnitude = magRight,
                vertical = false,
                muted = muted,
                clipping = clipping,
                warningDb = warningDb,
                errorDb = errorDb,
            )
            drawPeakIndicator(
                left = 0f,
                width = indicatorPx,
                top = 0f,
                height = laneHeight,
                hold = holdLeft,
                muted = muted,
                vertical = false,
                warningDb = warningDb,
                errorDb = errorDb,
            )
            drawPeakIndicator(
                left = 0f,
                width = indicatorPx,
                top = laneHeight + gap,
                height = laneHeight,
                hold = holdRight,
                muted = muted,
                vertical = false,
                warningDb = warningDb,
                errorDb = errorDb,
            )
            if (showScale) {
                for (db in 0 downTo -60 step 6) {
                    val norm = ((db - METER_MIN_DB) / (METER_MAX_DB - METER_MIN_DB)).coerceIn(0f, 1f)
                    val xBase = meterStart + norm * lanesWidth
                    val layout = textMeasurer.measure(AnnotatedString(db.toString()), scaleStyle)
                    val x = (xBase - layout.size.width / 2f)
                        .coerceIn(0f, size.width - layout.size.width)
                    drawText(
                        textLayoutResult = layout,
                        topLeft = Offset(x, meterPx + 2.dp.toPx()),
                    )
                    drawLine(
                        color = TextTertiary,
                        start = Offset(xBase, meterPx),
                        end = Offset(xBase, meterPx + 2.dp.toPx()),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
            }
        }
    }
}

private fun DrawScope.drawObsLane(
    left: Float,
    width: Float,
    top: Float = 0f,
    height: Float = size.height,
    level: Float,
    peak: Float,
    hold: Float,
    magnitude: Float,
    vertical: Boolean,
    muted: Boolean,
    clipping: Boolean,
    warningDb: Float = METER_WARNING_DB,
    errorDb: Float = METER_ERROR_DB,
) {
    fun pos(db: Float): Float =
        ((db - METER_MIN_DB) / (METER_MAX_DB - METER_MIN_DB)).coerceIn(0f, 1f)

    val bgNominal = if (muted) MeterBgNominalOff else MeterBgNominal
    val bgWarning = if (muted) MeterBgWarningOff else MeterBgWarning
    val bgError = if (muted) MeterBgErrorOff else MeterBgError
    val fgNominal = if (muted) MeterFgNominalOff else MeterFgNominal
    val fgWarning = if (muted) MeterFgWarningOff else MeterFgWarning
    val fgError = if (muted) MeterFgErrorOff else MeterFgError

    val warningPos = pos(warningDb)
    val errorPos = pos(errorDb)
    val p = if (clipping) 1f else pos(peak)

    if (vertical) {
        fun yOf(pv: Float) = top + (1f - pv) * height
        // Latar band.
        drawRect(bgError, Offset(left, top), Size(width, yOf(errorPos) - top))
        drawRect(bgWarning, Offset(left, yOf(errorPos)), Size(width, yOf(warningPos) - yOf(errorPos)))
        drawRect(bgNominal, Offset(left, yOf(warningPos)), Size(width, top + height - yOf(warningPos)))
        // Bar terang sampai puncak.
        if (p > errorPos) {
            drawRect(fgError, Offset(left, yOf(p)), Size(width, yOf(errorPos) - yOf(p)))
        }
        if (p > warningPos) {
            val topP = yOf(min(p, errorPos))
            drawRect(fgWarning, Offset(left, topP), Size(width, yOf(warningPos) - topP))
        }
        if (p > 0f) {
            val topP = yOf(min(p, warningPos))
            drawRect(fgNominal, Offset(left, topP), Size(width, yOf(0f) - topP))
        }
        // Peak hold 3dp.
        if (hold > METER_MIN_DB) {
            val hy = yOf(pos(hold))
            drawRect(
                holdColor(hold, muted),
                Offset(left, (hy - 3.dp.toPx()).coerceAtLeast(top)),
                Size(width, 3.dp.toPx()),
            )
        }
        // Indikator magnitude (hitam).
        if (magnitude > METER_MIN_DB) {
            val my = yOf(pos(magnitude))
            drawRect(
                MeterMagnitudeColor,
                Offset(left, (my - 3.dp.toPx()).coerceAtLeast(top)),
                Size(width, 3.dp.toPx()),
            )
        }
    } else {
        fun xOf(pv: Float) = left + pv * width
        drawRect(bgNominal, Offset(left, top), Size(xOf(warningPos) - left, height))
        drawRect(bgWarning, Offset(xOf(warningPos), top), Size(xOf(errorPos) - xOf(warningPos), height))
        drawRect(bgError, Offset(xOf(errorPos), top), Size(left + width - xOf(errorPos), height))
        if (p > 0f) {
            drawRect(fgNominal, Offset(left, top), Size(xOf(min(p, warningPos)) - left, height))
        }
        if (p > warningPos) {
            drawRect(fgWarning, Offset(xOf(warningPos), top), Size(xOf(min(p, errorPos)) - xOf(warningPos), height))
        }
        if (p > errorPos) {
            drawRect(fgError, Offset(xOf(errorPos), top), Size(xOf(p) - xOf(errorPos), height))
        }
        if (hold > METER_MIN_DB) {
            val hx = xOf(pos(hold))
            drawRect(
                holdColor(hold, muted),
                Offset((hx - 3.dp.toPx()).coerceAtLeast(left), top),
                Size(3.dp.toPx(), height),
            )
        }
        if (magnitude > METER_MIN_DB) {
            val mx = xOf(pos(magnitude))
            drawRect(
                MeterMagnitudeColor,
                Offset((mx - 3.dp.toPx()).coerceAtLeast(left), top),
                Size(3.dp.toPx(), height),
            )
        }
    }
    // level animasi tidak dipakai langsung: bar mengikuti peak seperti OBS.
    @Suppress("UNUSED_EXPRESSION")
    level
}

private fun holdColor(hold: Float, muted: Boolean): Color = when {
    hold < METER_WARNING_DB -> if (muted) MeterFgNominalOff else MeterFgNominal
    hold < METER_ERROR_DB -> if (muted) MeterFgWarningOff else MeterFgWarning
    else -> if (muted) MeterFgErrorOff else MeterFgError
}

private fun DrawScope.drawPeakIndicator(
    left: Float,
    width: Float,
    top: Float,
    height: Float,
    hold: Float,
    muted: Boolean,
    vertical: Boolean,
    warningDb: Float = METER_WARNING_DB,
    errorDb: Float = METER_ERROR_DB,
) {
    val color = when {
        hold < METER_INPUT_MIN_DB -> if (muted) MeterBgNominalOff else MeterBgNominal
        hold < warningDb -> if (muted) MeterFgNominalOff else MeterFgNominal
        hold < errorDb -> if (muted) MeterFgWarningOff else MeterFgWarning
        hold < METER_MAX_DB -> if (muted) MeterFgErrorOff else MeterFgError
        else -> MeterClipColor
    }
    if (vertical) {
        drawRect(color, Offset(left, top), Size(width, height))
    } else {
        drawRect(color, Offset(left, top), Size(width, height))
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
    peakType: com.rout.air.core.PeakMeterType,
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
        label = stringResource(R.string.audio_geser_audio),
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
                    showScale = true,
                    muted = channel.muted,
                    peakType = peakType,
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
    peakType: com.rout.air.core.PeakMeterType,
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
                modifier = Modifier.fillMaxHeight(),
                showScale = true,
                muted = channel.muted,
                peakType = peakType,
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
        DropdownMenuItem(text = { Text(stringResource(R.string.audio_tampilkan_semua_yang_disembunyikan)) }, onClick = onUnhideAll)
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
            text = { Text(stringResource(R.string.audio_pindah_ke_atas)) },
            onClick = {
                AudioMixer.moveChannel(channel.id, -1)
                onDismiss()
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.audio_pindah_ke_bawah)) },
            onClick = {
                AudioMixer.moveChannel(channel.id, 1)
                onDismiss()
            },
        )
        if (channel.capture == com.rout.air.core.AudioCaptureKind.Microphone) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.audio_ganti_perangkat_input)) },
                onClick = {
                    onDismiss()
                    onChangeDevice()
                },
            )
        }
        if (channel.appUid != 0) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.audio_hapus_kanal_audio)) },
                onClick = {
                    AudioMixer.removeChannel(channel.id)
                    onDismiss()
                },
            )
        }
        HorizontalDivider(color = Divider)
        DropdownMenuItem(
            text = { Text(stringResource(R.string.audio_salin_filter)) },
            enabled = channel.filters.isNotEmpty(),
            onClick = onCopyFilters,
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.audio_tempel_filter)) },
            enabled = canPasteFilters,
            onClick = onPasteFilters,
        )
        DropdownMenuItem(text = { Text(stringResource(R.string.audio_ganti_nama_2)) }, onClick = onRename)
        HorizontalDivider(color = Divider)
        DropdownMenuItem(text = { Text(stringResource(R.string.audio_filter)) }, onClick = onFilters)
        DropdownMenuItem(text = { Text(stringResource(R.string.audio_properti)) }, onClick = onProperties)
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
