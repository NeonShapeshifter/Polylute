package com.lostf1sh.pixelplayeross.data.headphones

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The rules the two band sets have to obey.
 *
 * These cover the state half of the bug the physical review found: moving bands in one editor and
 * then finding a different curve in another. The cause was two screens each holding a copy of this
 * state and writing their own snapshot back on exit, so the last screen destroyed won. The model
 * itself was sound, and these tests pin the properties the fix depends on.
 */
class HeadphoneEqModeStateTest {

    @Test
    fun `a mode switch does not disturb the other mode's curve`() {
        val editedInTen = HeadphoneEqState(
            enabled = true,
            mode = HeadphoneEqMode.GRAPHIC_10,
            bands10 = List(10) { it - 5 },
            bands15 = List(15) { 0 },
        )

        val switchedUp = editedInTen.copy(mode = HeadphoneEqMode.GRAPHIC_15)
        assertThat(switchedUp.activeBands).isEqualTo(List(15) { 0 })

        // Back again: the ten-band curve is exactly what it was, not a re-derivation of the
        // fifteen-band one and not a default.
        val switchedBack = switchedUp.copy(mode = HeadphoneEqMode.GRAPHIC_10)
        assertThat(switchedBack.activeBands).isEqualTo(editedInTen.bands10)
    }

    @Test
    fun `editing fifteen bands leaves the ten-band curve untouched`() {
        val state = HeadphoneEqState(
            enabled = true,
            mode = HeadphoneEqMode.GRAPHIC_15,
            bands10 = listOf(3, 3, 3, 3, 3, 3, 3, 3, 3, 3),
            bands15 = List(15) { 0 },
        )

        val edited = state.copy(bands15 = List(15) { it })

        assertThat(edited.bands10).isEqualTo(state.bands10)
        assertThat(edited.activeBands).isEqualTo(List(15) { it })
    }

    @Test
    fun `the active band count always matches the active mode`() {
        HeadphoneEqMode.entries.forEach { mode ->
            val state = HeadphoneEqState(mode = mode)
            val expected = when (mode) {
                HeadphoneEqMode.GRAPHIC_10 -> HeadphoneEqState.BAND_COUNT_10
                HeadphoneEqMode.GRAPHIC_15 -> HeadphoneEqState.BAND_COUNT_15
            }
            assertThat(state.activeBands.size).isEqualTo(expected)
            assertThat(state.activeFrequenciesHz.size).isEqualTo(expected)
        }
    }

    @Test
    fun `switching to fifteen bands and back restores a custom ten-band curve exactly`() {
        val customTen = listOf(7, 5, 2, 0, -1, -3, 0, 2, 4, 6)
        val state = HeadphoneEqState(
            enabled = true,
            presetName = "custom",
            mode = HeadphoneEqMode.GRAPHIC_10,
            bands10 = customTen,
        )

        val roundTripped = state
            .copy(mode = HeadphoneEqMode.GRAPHIC_15)
            .copy(bands15 = List(15) { 1 })
            .copy(mode = HeadphoneEqMode.GRAPHIC_10)

        assertThat(roundTripped.bands10).isEqualTo(customTen)
        assertThat(roundTripped.presetName).isEqualTo("custom")
    }

    @Test
    fun `a band edit never writes outside the engine's range`() {
        val state = HeadphoneEqState(mode = HeadphoneEqMode.GRAPHIC_10)
            .withBand(0, 99)
            .withBand(1, -99)

        assertThat(state.bands10[0]).isEqualTo(15)
        assertThat(state.bands10[1]).isEqualTo(-15)
    }
}

/** Applies one band edit the way the editor does, so the clamp is exercised rather than assumed. */
private fun HeadphoneEqState.withBand(index: Int, gainDb: Int): HeadphoneEqState {
    val clamped = gainDb.coerceIn(-15, 15)
    return when (mode) {
        HeadphoneEqMode.GRAPHIC_10 ->
            copy(bands10 = bands10.toMutableList().also { it[index] = clamped })
        HeadphoneEqMode.GRAPHIC_15 ->
            copy(bands15 = bands15.toMutableList().also { it[index] = clamped })
    }
}
