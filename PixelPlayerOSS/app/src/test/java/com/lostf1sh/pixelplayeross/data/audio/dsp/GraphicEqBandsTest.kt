package com.lostf1sh.pixelplayeross.data.audio.dsp

import com.google.common.truth.Truth.assertThat
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The band tables are the contract between the sliders, the DSP and the profile store. If a label
 * ever names a frequency the filter bank does not run, the UI is lying about what it does.
 */
class GraphicEqBandsTest {

    /** Closed-form constant-Q choice for a table of [count] bands spanning first to last. */
    private fun expectedQualityFactor(frequencies: IntArray): Double {
        val octavesPerStep = ln(frequencies.last().toDouble() / frequencies.first().toDouble()) /
            ln(2.0) / (frequencies.size - 1)
        val ratio = 2.0.pow(octavesPerStep)
        return sqrt(ratio) / (ratio - 1.0)
    }

    @Test
    fun `ten band table is the documented one`() {
        assertThat(GraphicEqBands.TEN_BAND_HZ.toList())
            .containsExactly(31, 62, 125, 250, 500, 1_000, 2_000, 4_000, 8_000, 16_000)
            .inOrder()
    }

    @Test
    fun `fifteen band table is the documented one`() {
        assertThat(GraphicEqBands.FIFTEEN_BAND_HZ.toList())
            .containsExactly(
                25, 40, 63, 100, 160, 250, 400, 630, 1_000, 1_600, 2_500, 4_000, 6_300, 10_000,
                16_000
            )
            .inOrder()
    }

    @Test
    fun `the two tables are genuinely different filter sets`() {
        assertThat(GraphicEqBands.FIFTEEN_BAND_HZ.size)
            .isNotEqualTo(GraphicEqBands.TEN_BAND_HZ.size)
        // They share 250 Hz, 1 kHz, 4 kHz and 16 kHz. Everything else must be its own filter, not a
        // relabelled ten-band slider.
        val tenOnly = GraphicEqBands.TEN_BAND_HZ.filterNot { it in GraphicEqBands.FIFTEEN_BAND_HZ.toSet() }
        val fifteenOnly = GraphicEqBands.FIFTEEN_BAND_HZ.filterNot { it in GraphicEqBands.TEN_BAND_HZ.toSet() }
        assertThat(tenOnly).containsExactly(31, 62, 125, 500, 2_000, 8_000)
        assertThat(fifteenOnly).containsExactly(25, 40, 63, 100, 160, 400, 630, 1_600, 2_500, 6_300, 10_000)
    }

    @Test
    fun `every table is strictly ascending`() {
        listOf(GraphicEqBands.TEN_BAND_HZ, GraphicEqBands.FIFTEEN_BAND_HZ).forEach { table ->
            table.toList().zipWithNext().forEach { (low, high) ->
                assertThat(high).isGreaterThan(low)
            }
        }
    }

    @Test
    fun `labels are one per filter`() {
        assertThat(GraphicEqBands.labelsFor(10)).hasSize(10)
        assertThat(GraphicEqBands.labelsFor(15)).hasSize(15)
    }

    @Test
    fun `labels parse back to exactly the filter frequencies`() {
        // This is the assertion that stops a slider caption from ever disagreeing with the filter
        // actually running behind it.
        listOf(10, 15).forEach { bandCount ->
            val frequencies = GraphicEqBands.frequenciesFor(bandCount)
            val labels = GraphicEqBands.labelsFor(bandCount)
            labels.forEachIndexed { index, label ->
                assertThat(parseLabel(label)).isEqualTo(frequencies[index])
            }
        }
    }

    @Test
    fun `labelsFor a ten band count matches the ten band table and not the fifteen band one`() {
        assertThat(GraphicEqBands.labelsFor(10)).isEqualTo(GraphicEqBands.TEN_BAND_LABELS)
        assertThat(GraphicEqBands.labelsFor(15)).isEqualTo(GraphicEqBands.FIFTEEN_BAND_LABELS)
        assertThat(GraphicEqBands.labelsFor(10)).isNotEqualTo(GraphicEqBands.FIFTEEN_BAND_LABELS)
    }

    @Test
    fun `frequenciesFor hands out a copy so callers cannot corrupt the table`() {
        val first = GraphicEqBands.frequenciesFor(10)
        first[0] = 999
        assertThat(GraphicEqBands.frequenciesFor(10)[0]).isEqualTo(31)
    }

    @Test
    fun `ten band quality factor follows the constant-Q closed form`() {
        val q = GraphicEqBands.qualityFactor(GraphicEqBands.TEN_BAND_HZ)
        assertThat(q.toDouble()).isWithin(1e-6).of(expectedQualityFactor(GraphicEqBands.TEN_BAND_HZ))
        // One octave per band puts Q just above sqrt(2)/1.
        assertThat(q.toDouble()).isWithin(0.01).of(1.4125)
    }

    @Test
    fun `fifteen band quality factor follows the constant-Q closed form`() {
        val q = GraphicEqBands.qualityFactor(GraphicEqBands.FIFTEEN_BAND_HZ)
        assertThat(q.toDouble()).isWithin(1e-6).of(expectedQualityFactor(GraphicEqBands.FIFTEEN_BAND_HZ))
        // Two thirds of an octave per band gives a narrower, higher-Q filter.
        assertThat(q.toDouble()).isWithin(0.01).of(2.1486)
    }

    @Test
    fun `narrower bands always get a higher quality factor`() {
        val ten = GraphicEqBands.qualityFactor(GraphicEqBands.TEN_BAND_HZ)
        val fifteen = GraphicEqBands.qualityFactor(GraphicEqBands.FIFTEEN_BAND_HZ)
        assertThat(fifteen).isGreaterThan(ten)
    }

    @Test
    fun `quality factor does not depend on the absolute frequency scale`() {
        val base = intArrayOf(100, 200, 400, 800, 1_600)
        val shifted = base.map { it * 7 }.toIntArray()
        assertThat(GraphicEqBands.qualityFactor(shifted))
            .isWithin(1e-6f)
            .of(GraphicEqBands.qualityFactor(base))
    }

    @Test
    fun `an exactly one octave table gives the textbook quality factor`() {
        // 2^(1/2) / (2^1 - 1) = sqrt(2)
        val octave = intArrayOf(100, 200, 400, 800)
        assertThat(GraphicEqBands.qualityFactor(octave).toDouble())
            .isWithin(1e-6)
            .of(sqrt(2.0))
    }

    @Test
    fun `a table with fewer than two bands is rejected rather than silently defaulted`() {
        assertThrows<IllegalArgumentException> { GraphicEqBands.qualityFactor(intArrayOf(1_000)) }
        assertThrows<IllegalArgumentException> { GraphicEqBands.qualityFactor(IntArray(0)) }
    }

    /** Parses a UI caption such as `"1.6kHz"` or `"31Hz"` back into hertz. */
    private fun parseLabel(label: String): Int {
        val digits = label.removeSuffix("Hz")
        return if (digits.endsWith("k")) {
            Math.round(digits.removeSuffix("k").toDouble() * 1000.0).toInt()
        } else {
            digits.toInt()
        }
    }
}
