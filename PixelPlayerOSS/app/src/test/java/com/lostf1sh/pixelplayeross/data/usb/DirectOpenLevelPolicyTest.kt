package com.lostf1sh.pixelplayeross.data.usb

import com.decent.usbaudio.DirectOpenLevelPolicy
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * AUD-118 (CRITICAL, confirmed by the owner): after a pass through the Android route the DAC came
 * back to Direct at full level. snd-usb-audio / the USB HAL set the Feature Unit near 0 dB while the
 * Android route owned it, and the open guard only lowered it when Safety resolved a `dac:<id>`
 * context — which it did not at that instant — so nothing was restored.
 *
 * The driver now remembers the last Direct level per USB device itself and restores it on every
 * claim, whatever Safety can resolve; Safety's ceiling only ever lowers it further.
 */
class DirectOpenLevelPolicyTest {

    private val zeroDb = 0
    private val minus30 = -30 * 256
    private val minus20 = -20 * 256

    @Test
    fun `a DAC reopened at 0 dB with -30 dB saved is written back to -30 before audio`() {
        assertThat(DirectOpenLevelPolicy.target(zeroDb, savedDirectUnits = minus30, ceilingUnits = null))
            .isEqualTo(minus30)
    }

    @Test
    fun `an ambiguous DAC (no Safety context, no ceiling) still gets its saved Direct level`() {
        // This is the device case: the ceiling callback returned null.
        assertThat(DirectOpenLevelPolicy.target(zeroDb, savedDirectUnits = minus30, ceilingUnits = null))
            .isEqualTo(minus30)
    }

    @Test
    fun `the quieter of the saved level and the ceiling wins`() {
        assertThat(DirectOpenLevelPolicy.target(zeroDb, savedDirectUnits = minus20, ceilingUnits = minus30))
            .isEqualTo(minus30)
        assertThat(DirectOpenLevelPolicy.target(zeroDb, savedDirectUnits = minus30, ceilingUnits = minus20))
            .isEqualTo(minus30)
    }

    @Test
    fun `it never raises`() {
        assertThat(DirectOpenLevelPolicy.target(minus30, savedDirectUnits = minus20, ceilingUnits = null)).isNull()
    }

    @Test
    fun `nothing known leaves the level alone`() {
        assertThat(DirectOpenLevelPolicy.target(zeroDb, savedDirectUnits = null, ceilingUnits = null)).isNull()
    }

    @Test
    fun `the level is keyed by the USB device being opened, serial included`() {
        assertThat(DirectOpenLevelPolicy.deviceKey(10610, 294, "ABC123")).isEqualTo("10610:294:ABC123")
        assertThat(DirectOpenLevelPolicy.deviceKey(10610, 294, null)).isEqualTo("10610:294:")
    }
}
