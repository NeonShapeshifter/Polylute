package com.lostf1sh.pixelplayeross.ui.ds

import com.lostf1sh.pixelplayeross.ui.ds.LocalDapAccent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The graphic equalizer, drawn against the real engine.
 *
 * The gain range is the DSP's own: **±15 dB**. There is no preamp, because the engine has none —
 * this editor must never grow a control the signal path cannot honour.
 *
 * [gains] and [labels] are the active mode's own bands, in order, so the 10-band and 15-band tables
 * are the engine's, never a copy.
 */
private val EqEditorHeight = 168.dp
private val EqAxisWidth = 30.dp
private val EqTrackWidth = 2.dp

@Composable
fun DapEqEditor(
    gains: List<Int>,
    labels: List<String>,
    modifier: Modifier = Modifier,
    rangeDb: Int = 15,
    selectedIndex: Int? = null,
    onSelect: ((Int) -> Unit)? = null,
    summaryDescription: String,
    height: Dp = EqEditorHeight,
) {
    if (gains.isEmpty()) return

    BoxWithConstraints(modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = summaryDescription }) {
        // The axis column is measured rather than fixed. "+15" / "−15" grow with the font scale,
        // and at a fixed 30 dp the last glyph was clipped from about 1.3 upwards.
        val density = LocalDensity.current
        val measurer = rememberTextMeasurer()
        val axisWidth = remember(rangeDb, density.fontScale, measurer) {
            val widestPx = maxOf(
                measurer.measure("+$rangeDb", DapType.Axis, maxLines = 1).size.width,
                measurer.measure("−$rangeDb", DapType.Axis, maxLines = 1).size.width,
            )
            with(density) { widestPx.toDp() + DapSpacing.Xs }
        }.coerceAtLeast(EqAxisWidth)

        // Bands always divide the width they are given, so a 15-band table fits the same frame a
        // 10-band table does. Labels below thin out when they would otherwise collide.
        val usable = (maxWidth - axisWidth).coerceAtLeast(1.dp)
        val bandWidth = usable / gains.size
        val labelStride = remember(labels, bandWidth, density, measurer) {
            val bandWidthPx = with(density) { bandWidth.toPx() }
            val labelWidths = labels.map { measurer.measure(it, DapType.Axis, maxLines = 1).size.width }
            // Include font scale and the final, shorter span. Fixed dp thresholds truncated kHz
            // labels at 150 %, even though all fifteen band markers still fitted.
            (1..gains.size).firstOrNull { stride ->
                labels.indices.filter { it % stride == 0 }.all { index ->
                    labelWidths[index] <= bandWidthPx * minOf(stride, labels.size - index)
                }
            } ?: gains.size
        }

        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth()) {
                // BoxWithConstraints, not a measured height. The band column's height is a
                // constraint the layout already knows, so it is readable during composition —
                // whereas a value captured in onSizeChanged arrives after the first layout and,
                // held in a plain array, never invalidates anything. That is what pinned every
                // knob to the top of the frame in the shipped build.
                BoxWithConstraints(
                    Modifier
                        .weight(1f)
                        .height(height)
                ) {
                    val bandHeight = maxHeight
                    // The zero line. Above it is boost, below is cut.
                    Box(
                        Modifier
                            .align(Alignment.CenterStart)
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Dap.dividerStrong)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        gains.forEachIndexed { index, gain ->
                            EqBand(
                                gain = gain,
                                rangeDb = rangeDb,
                                isSelected = index == selectedIndex,
                                bandHeight = bandHeight,
                                onSelect = onSelect?.let { select -> { select(index) } },
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                            )
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .width(axisWidth)
                        .height(height)
                        .padding(start = DapSpacing.Xs),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("+$rangeDb", style = DapType.Axis, color = Dap.disabledText, maxLines = 1)
                    Text("0", style = DapType.Axis, color = Dap.disabledText, maxLines = 1)
                    Text("−$rangeDb", style = DapType.Axis, color = Dap.disabledText, maxLines = 1)
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = axisWidth, top = DapSpacing.Xs)
            ) {
                labels.forEachIndexed { index, label ->
                    if (index % labelStride != 0) return@forEachIndexed
                    // Each labelled position takes the width of the bands it stands for, not one
                    // band. Giving every label a single band's slot meant a 15-band table handed
                    // "16k" about 16 dp, which hard-clipped it; weighting by the stride keeps the
                    // row aligned to the bands underneath while giving each label room.
                    val span = minOf(labelStride, labels.size - index).coerceAtLeast(1)
                    Box(
                        modifier = Modifier.weight(span.toFloat()),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            style = DapType.Axis,
                            color = Dap.disabledText,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EqBand(
    gain: Int,
    rangeDb: Int,
    isSelected: Boolean,
    bandHeight: Dp,
    onSelect: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val accent = LocalDapAccent.current
    val range = rangeDb.coerceAtLeast(1)
    val h = bandHeight
    fun fractionFor(g: Int): Float = (0.5f - g.toFloat() / (2f * range)).coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .pointerInput(onSelect) {
                // A summary touch only chooses a band. Gain belongs to the wide editor below.
                detectTapGestures { onSelect?.invoke() }
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        val centerY = h / 2
        val knobY = h * fractionFor(gain)

        Box(
            Modifier
                .align(Alignment.TopCenter)
                .width(EqTrackWidth)
                .height(h)
                .background(Dap.trackStroke)
        )

        val top = minOf(centerY, knobY)
        val span = (maxOf(centerY, knobY) - top).coerceAtLeast(0.dp)
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .offset(y = top)
                .width(EqTrackWidth)
                .height(span)
                .background(if (isSelected) accent else Dap.secondaryText)
        )

        val knobRadius = if (isSelected) 9.dp else 6.dp
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .offset(y = (knobY - knobRadius).coerceAtLeast(0.dp))
                .size(knobRadius * 2)
                .background(
                    color = if (isSelected) accent else Dap.primaryText,
                    shape = CircleShape,
                )
        )
    }
}
