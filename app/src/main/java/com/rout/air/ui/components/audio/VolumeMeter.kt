package com.rout.air.ui.components.audio

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rout.air.core.audio.ChannelLevel
import com.rout.air.core.audio.MeterDecay
import com.rout.air.ui.theme.TextTertiary
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min
import androidx.compose.ui.res.stringResource
import com.rout.air.R

internal val MeterBgNominal = Color(0xFF267F26)

internal val MeterBgWarning = Color(0xFF7F7F26)

internal val MeterBgError = Color(0xFF7F2626)

internal val MeterFgNominal = Color(0xFF4CFF4C)

internal val MeterFgWarning = Color(0xFFFFFF4C)

internal val MeterFgError = Color(0xFFFF4C4C)

internal val MeterBgNominalOff = Color(0xFF5A5A5A)

internal val MeterBgWarningOff = Color(0xFF757575)

internal val MeterBgErrorOff = Color(0xFF414141)

internal val MeterFgNominalOff = Color(0xFFA3A3A3)

internal val MeterFgWarningOff = Color(0xFFD9D9D9)

internal val MeterFgErrorOff = Color(0xFF717171)

internal val MeterClipColor = Color(0xFFFFFFFF)

internal val MeterMagnitudeColor = Color(0xFF000000)

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
    peakType: com.rout.air.core.audio.PeakMeterType = com.rout.air.core.audio.PeakMeterType.Sample,
) {
    // True-peak memakai ambang EBU yang berbeda (OBS VolumeMeter::setPeakMeterType).
    val warningDb = if (peakType == com.rout.air.core.audio.PeakMeterType.TruePeak) -13f else -20f
    val errorDb = if (peakType == com.rout.air.core.audio.PeakMeterType.TruePeak) -2f else -9f
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

internal fun DrawScope.drawObsLane(
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

internal fun holdColor(hold: Float, muted: Boolean): Color = when {
    hold < METER_WARNING_DB -> if (muted) MeterFgNominalOff else MeterFgNominal
    hold < METER_ERROR_DB -> if (muted) MeterFgWarningOff else MeterFgWarning
    else -> if (muted) MeterFgErrorOff else MeterFgError
}

internal fun DrawScope.drawPeakIndicator(
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
