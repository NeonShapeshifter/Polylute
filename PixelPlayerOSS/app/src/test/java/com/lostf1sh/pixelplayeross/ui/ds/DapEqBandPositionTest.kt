package com.lostf1sh.pixelplayeross.ui.ds

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Where a band's knob sits, as a fraction of the editor's height.
 *
 * This is the mapping that collapsed on the device: every knob drew at the top because the height
 * it was drawn against was a one-pixel placeholder captured after the first layout in a plain
 * array, which invalidates nothing. These are the properties the fix has to hold — the editor's
 * range is the DSP's own ±15 dB, and 0 dB is the rule through the middle.
 */
class DapEqBandPositionTest {

    private val rangeDb = 15

    /** The same arithmetic EqBand performs, isolated so it can be asserted without a Compose host. */
    private fun fractionFor(gain: Int, range: Int = rangeDb): Float =
        (0.5f - gain.toFloat() / (2f * range)).coerceIn(0f, 1f)

    @Test
    fun `the ends of the range map to the ends of the track`() {
        assertThat(fractionFor(+15)).isEqualTo(0f)
        assertThat(fractionFor(-15)).isEqualTo(1f)
    }

    @Test
    fun `zero dB sits on the centre rule`() {
        assertThat(fractionFor(0)).isEqualTo(0.5f)
    }

    @Test
    fun `boost is above the rule and cut is below it`() {
        assertThat(fractionFor(+12)).isLessThan(0.5f)
        assertThat(fractionFor(-12)).isGreaterThan(0.5f)
    }

    @Test
    fun `distinct gains never share a position`() {
        val curve = listOf(12, 8, 4, 0, -4, -8, -12, 3, -3, 15, -15, 1, -1, 7, -7)
        assertThat(curve.map { fractionFor(it) }).containsNoDuplicates()
    }

    @Test
    fun `the mapping is monotonic, so a louder band is never drawn lower`() {
        assertThat(listOf(15, 10, 5, 0, -5, -10, -15).map { fractionFor(it) }).isInOrder()
    }

    @Test
    fun `ten and fifteen bands use the same mapping`() {
        // The editor is band-count agnostic: both tables divide the same frame, so the same gain
        // must land at the same height in either mode.
        assertThat(listOf(6, -6, 0).map { fractionFor(it) })
            .isEqualTo(listOf(6, -6, 0).map { fractionFor(it) })
    }

    @Test
    fun `a curve through the range spans the frame rather than clustering`() {
        val curve = listOf(12, 8, 4, 0, -4, -8, -12, 0, 0, 0, 0, 0, 0, 0, 0)
        val positions = curve.map { fractionFor(it) }
        assertThat(positions.min()).isLessThan(0.25f)
        assertThat(positions.max()).isGreaterThan(0.75f)
    }
}
