package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.equalizer.EqualizerPreset
import org.junit.jupiter.api.Test

/**
 * What the stored preset name becomes when the Equalizer screen is closed.
 *
 * Leaving the screen flushes the view model's state, and that flush must not rewrite history: a
 * preset the user selected and did not touch is still that preset when they come back, while a
 * curve they moved is `custom` and must not keep claiming a name it no longer matches.
 *
 * The regression this pins: the flush used to stamp every ten-band write as `custom`, so merely
 * opening the Equalizer with FLAT selected and leaving it again turned the stored preset into
 * CUSTOM even though the curve had not changed.
 */
class EqualizerExitPresetNameTest {

    @Test
    fun `an untouched named preset keeps its name`() {
        EqualizerPreset.ALL_PRESETS.forEach { preset ->
            assertThat(presetNameForExit(preset, preset.bandLevels)).isEqualTo(preset.name)
        }
    }

    @Test
    fun `flat survives closing the screen, which is the reported regression`() {
        assertThat(presetNameForExit(EqualizerPreset.FLAT, List(10) { 0 })).isEqualTo("flat")
    }

    @Test
    fun `a named preset stops claiming its name once the curve moved`() {
        val moved = EqualizerPreset.VOCAL.bandLevels.toMutableList().also { it[5] = it[5] + 1 }

        assertThat(presetNameForExit(EqualizerPreset.VOCAL, moved)).isEqualTo("custom")
    }

    @Test
    fun `a moved band returns the name once the curve is moved back`() {
        // The label follows the curve, not the fact that a slider was ever touched.
        val vocal = EqualizerPreset.VOCAL.bandLevels
        val moved = vocal.toMutableList().also { it[0] = 9 }

        assertThat(presetNameForExit(EqualizerPreset.VOCAL, moved)).isEqualTo("custom")
        assertThat(presetNameForExit(EqualizerPreset.VOCAL, vocal)).isEqualTo("vocal")
    }

    @Test
    fun `an already custom preset stays custom`() {
        val bands = listOf(-1, 0, 2, 3, 1, 0, -2, -3, 0, 4)

        assertThat(presetNameForExit(EqualizerPreset.custom(bands), bands)).isEqualTo("custom")
    }

    @Test
    fun `the flat curve of a custom preset is not renamed to flat`() {
        // Same ten zeros as FLAT, but it is the user's own curve, so it stays custom rather than
        // being attributed to a built-in preset the user never chose.
        assertThat(presetNameForExit(EqualizerPreset.custom(List(10) { 0 }), List(10) { 0 }))
            .isEqualTo("custom")
    }

    @Test
    fun `a different length curve does not match a ten-band preset`() {
        assertThat(presetNameForExit(EqualizerPreset.FLAT, List(15) { 0 })).isEqualTo("custom")
    }
}
