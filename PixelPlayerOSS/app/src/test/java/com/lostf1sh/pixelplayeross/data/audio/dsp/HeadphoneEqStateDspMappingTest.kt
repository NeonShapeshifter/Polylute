package com.lostf1sh.pixelplayeross.data.audio.dsp

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.audio.dsp.HeadphoneGraphicEqController.Companion.toDspSettings
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneEqMode
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneEqState
import org.junit.jupiter.api.Test

/**
 * The single mapping between what a profile stores and what the DSP renders.
 *
 * This is the seam where a 15-band profile could accidentally be rendered with ten filters, or a
 * ten-band profile could pick up leftovers from the fifteen-band store. Both would be silent in the
 * UI and audible in the headphones, so both are pinned here.
 */
class HeadphoneEqStateDspMappingTest {

    private fun state(
        mode: HeadphoneEqMode = HeadphoneEqMode.GRAPHIC_10,
        enabled: Boolean = true,
        bands10: List<Int> = List(10) { 0 },
        bands15: List<Int> = List(15) { 0 },
        presetName: String = "flat",
    ) = HeadphoneEqState(
        enabled = enabled,
        presetName = presetName,
        bands10 = bands10,
        mode = mode,
        bands15 = bands15,
    )

    @Test
    fun `the ten band mode renders exactly ten filters on the ten band table`() {
        val rendered = state(mode = HeadphoneEqMode.GRAPHIC_10).toDspSettings()

        assertThat(rendered.bandCount).isEqualTo(10)
        assertThat(rendered.frequenciesHz.toList()).isEqualTo(GraphicEqBands.TEN_BAND_HZ.toList())
        assertThat(rendered.gainsDb).hasLength(10)
    }

    @Test
    fun `the fifteen band mode renders exactly fifteen filters on the fifteen band table`() {
        val rendered = state(mode = HeadphoneEqMode.GRAPHIC_15).toDspSettings()

        assertThat(rendered.bandCount).isEqualTo(15)
        assertThat(rendered.frequenciesHz.toList()).isEqualTo(GraphicEqBands.FIFTEEN_BAND_HZ.toList())
        assertThat(rendered.gainsDb).hasLength(15)
    }

    @Test
    fun `band gains cross over in order, so a slider moves the filter it labels`() {
        val tenGains = (0 until 10).map { it * 1 } // 0..9 dB
        val fifteenGains = (0 until 15).map { -it } // 0..-14 dB

        val ten = state(mode = HeadphoneEqMode.GRAPHIC_10, bands10 = tenGains).toDspSettings()
        val fifteen = state(mode = HeadphoneEqMode.GRAPHIC_15, bands15 = fifteenGains).toDspSettings()

        assertThat(ten.gainsDb.toList()).isEqualTo(tenGains.map { it.toFloat() })
        assertThat(fifteen.gainsDb.toList()).isEqualTo(fifteenGains.map { it.toFloat() })
    }

    @Test
    fun `the fifteen band curve cannot be influenced by the ten band store`() {
        // A ten-band store full of extremes, a fifteen-band store that is flat.
        val rendered = state(
            mode = HeadphoneEqMode.GRAPHIC_15,
            bands10 = List(10) { 15 },
            bands15 = List(15) { 0 },
        ).toDspSettings()

        assertThat(rendered.gainsDb.toList()).isEqualTo(List(15) { 0f })
    }

    @Test
    fun `the ten band curve cannot be influenced by the fifteen band store`() {
        val rendered = state(
            mode = HeadphoneEqMode.GRAPHIC_10,
            bands10 = List(10) { 0 },
            bands15 = List(15) { -15 },
        ).toDspSettings()

        assertThat(rendered.gainsDb.toList()).isEqualTo(List(10) { 0f })
    }

    @Test
    fun `switching mode switches the rendered band count without touching either stored curve`() {
        val stored = state(
            mode = HeadphoneEqMode.GRAPHIC_10,
            bands10 = List(10) { 3 },
            bands15 = List(15) { -7 },
        )

        assertThat(stored.toDspSettings().bandCount).isEqualTo(10)
        assertThat(stored.copy(mode = HeadphoneEqMode.GRAPHIC_15).toDspSettings().bandCount).isEqualTo(15)
        // Neither store's contents moved: they are independent, not a redisplay of one curve.
        assertThat(stored.bands10).isEqualTo(List(10) { 3 })
        assertThat(stored.bands15).isEqualTo(List(15) { -7 })
        assertThat(stored.copy(mode = HeadphoneEqMode.GRAPHIC_15).toDspSettings().gainsDb.toList())
            .isEqualTo(List(15) { -7f })
    }

    @Test
    fun `a disabled equalizer is bypassed whatever the bands say`() {
        val rendered = state(
            mode = HeadphoneEqMode.GRAPHIC_15,
            enabled = false,
            bands15 = List(15) { 15 },
        ).toDspSettings()

        assertThat(rendered.isBypass).isTrue()
    }

    @Test
    fun `an enabled equalizer with a flat curve is bypassed too`() {
        val rendered = state(
            mode = HeadphoneEqMode.GRAPHIC_15,
            enabled = true,
            bands15 = List(15) { 0 },
        ).toDspSettings()

        // Enabled but mathematically transparent: no reason to run fifteen filters over PCM.
        assertThat(rendered.isBypass).isTrue()
    }

    @Test
    fun `an enabled equalizer with any non-zero band is not bypassed`() {
        val rendered = state(
            mode = HeadphoneEqMode.GRAPHIC_10,
            enabled = true,
            bands10 = List(10) { 0 }.toMutableList().also { it[9] = 1 },
        ).toDspSettings()

        assertThat(rendered.isBypass).isFalse()
    }
}
