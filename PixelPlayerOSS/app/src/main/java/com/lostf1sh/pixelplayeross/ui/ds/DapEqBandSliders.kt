package com.lostf1sh.pixelplayeross.ui.ds

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.res.stringResource
import com.lostf1sh.pixelplayeross.R
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The graphic equalizer's bands, one vertical slider per filter (B22, `Equalizer.dc.html`).
 *
 * **The thumb follows the band's value**: 0 dB in the middle of the track, +[rangeDb] at the top end and −[rangeDb] at
 * the bottom end ([eqThumbFraction]). The design drew two different values (+3 and −1) at the same height; here the
 * only thing that decides where a thumb sits is the value it shows above it, and the same function reads a touch back
 * into a value ([eqLevelForFraction]), so what is drawn and what is written cannot disagree.
 *
 * The gain range is the DSP's own, ±15 dB in whole dB: the engine has no preamp and no finer step, so neither does this.
 * A column is a touch target of its own: put a finger anywhere on it and the band goes there; drag and it follows.
 *
 * Every band is a slider for accessibility too (value, range, and `setProgress`), so TalkBack and a keyboard step it by
 * 1 dB like any slider.
 */

/** Where a band's thumb sits on its track, from the top end (+[rangeDb] dB) at 0 to the bottom end (−[rangeDb] dB) at 1. */
fun eqThumbFraction(gainDb: Int, rangeDb: Int = 15): Float {
    val range = rangeDb.coerceAtLeast(1)
    return (range - gainDb.coerceIn(-range, range)) / (2f * range)
}

/** The whole-dB level a position on the track stands for: the inverse of [eqThumbFraction], rounded to the engine's step. */
fun eqLevelForFraction(fraction: Float, rangeDb: Int = 15): Int {
    val range = rangeDb.coerceAtLeast(1)
    return (range - fraction.coerceIn(0f, 1f) * 2f * range).roundToInt().coerceIn(-range, range)
}

/** "31Hz" -> "31", "1.6kHz" -> "1.6k": the frequency under a band. The unit is on the response axis, once. */
fun eqCompactFrequency(label: String): String = label.removeSuffix("Hz")

/** "+3", "0", "-1": the value over a band. */
fun eqGainLabel(gainDb: Int): String = if (gainDb == 0) "0" else String.format(Locale.US, "%+d", gainDb)

private val ThumbDiameter = 14.dp
private val TrackWidth = 2.dp

@Composable
fun DapEqBandSliders(
    gains: List<Int>,
    labels: List<String>,
    onGainChange: (index: Int, gainDb: Int) -> Unit,
    modifier: Modifier = Modifier,
    rangeDb: Int = 15,
    trackHeight: Dp = 160.dp,
) {
    if (gains.isEmpty()) return
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val latestChange by rememberUpdatedState(onGainChange)

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val gap = 2.dp
        val columnWidth = (maxWidth - gap * (gains.size - 1)) / gains.size
        // Fifteen columns of about 19 dp: the value over a band and its frequency drop to the axis size, and a
        // frequency that would run into its neighbour is left out (every second one), never squeezed.
        val dense = columnWidth < 28.dp
        val valueStyle = if (dense) DapType.Axis else DapType.ValueSmall
        val labelStride = remember(labels, columnWidth, density.fontScale, measurer) {
            val columnPx = with(density) { columnWidth.toPx() }
            val widths = labels.map { measurer.measure(eqCompactFrequency(it), DapType.Axis, maxLines = 1).size.width }
            (1..labels.size).firstOrNull { stride ->
                labels.indices.filter { it % stride == 0 }.all { widths[it] <= columnPx * stride - 2f }
            } ?: labels.size
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            gains.forEachIndexed { index, gain ->
                val label = labels.getOrElse(index) { "" }
                val gainText = eqGainLabel(gain)
                // "125 Hz, +1 dB": what a screen reader says for a band.
                val description = stringResource(R.string.eq_band_accessible, label, gainText)
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(DapSpacing.Sm),
                ) {
                    Text(
                        text = gainText,
                        style = valueStyle,
                        color = Dap.primaryText,
                        maxLines = 1,
                        softWrap = false,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.wrapContentWidth(unbounded = true),
                    )
                    BandTrack(
                        gain = gain,
                        rangeDb = rangeDb,
                        trackHeight = trackHeight,
                        onGainChange = { latestChange(index, it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                contentDescription = description
                                stateDescription = gainText
                                progressBarRangeInfo = ProgressBarRangeInfo(gain.toFloat(), -rangeDb.toFloat()..rangeDb.toFloat(), steps = 2 * rangeDb - 1)
                                setProgress { target ->
                                    latestChange(index, target.roundToInt().coerceIn(-rangeDb, rangeDb))
                                    true
                                }
                            },
                    )
                    Box(Modifier.fillMaxWidth().height(14.dp), contentAlignment = Alignment.TopCenter) {
                        if (index % labelStride == 0) {
                            Text(
                                text = eqCompactFrequency(label),
                                style = DapType.Axis,
                                color = Dap.tertiaryText,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.wrapContentWidth(unbounded = true),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** One band's track: the rule, the zero tick, the fill from zero to the thumb, and the thumb. */
@Composable
private fun BandTrack(
    gain: Int,
    rangeDb: Int,
    trackHeight: Dp,
    onGainChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val latest by rememberUpdatedState(onGainChange)
    val density = LocalDensity.current
    val trackPx = with(density) { trackHeight.toPx() }
    val thumbRadiusPx = with(density) { (ThumbDiameter / 2).toPx() }
    val trackColor = Dap.interactiveTrack
    val zeroColor = Dap.dividerStrong
    val fillColor = Dap.primaryText
    val thumbColor = Dap.primaryText

    fun levelAt(y: Float): Int = eqLevelForFraction(((y - thumbRadiusPx) / trackPx).coerceIn(0f, 1f), rangeDb)

    Canvas(
        modifier
            .height(trackHeight + ThumbDiameter)
            .pointerInput(rangeDb, trackPx) {
                detectTapGestures(onPress = { offset ->
                    latest(levelAt(offset.y))
                    tryAwaitRelease()
                })
            }
            .pointerInput(rangeDb, trackPx) {
                detectVerticalDragGestures(
                    onDragStart = { offset -> latest(levelAt(offset.y)) },
                    onVerticalDrag = { change, _ -> latest(levelAt(change.position.y)) },
                )
            },
    ) {
        val x = size.width / 2f
        val top = thumbRadiusPx
        val zeroY = top + eqThumbFraction(0, rangeDb) * trackPx
        val thumbY = top + eqThumbFraction(gain, rangeDb) * trackPx
        val stroke = TrackWidth.toPx()
        drawLine(trackColor, Offset(x, top), Offset(x, top + trackPx), strokeWidth = stroke, cap = StrokeCap.Round)
        // The zero tick: a short rule across the track, so "no change" has a place to be.
        drawLine(zeroColor, Offset(x - 6.dp.toPx(), zeroY), Offset(x + 6.dp.toPx(), zeroY), strokeWidth = 1.dp.toPx())
        if (gain != 0) {
            drawLine(fillColor, Offset(x, zeroY), Offset(x, thumbY), strokeWidth = stroke, cap = StrokeCap.Round)
        }
        drawCircle(thumbColor, radius = thumbRadiusPx, center = Offset(x, thumbY))
    }
}
