package com.lostf1sh.pixelplayeross.data.service.player

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.dap.mode.EffectiveProductMode
import org.junit.jupiter.api.Test

/** B68 (option D): who may hand the DAC to other apps. Every combination of the switch, the Purity choice and the product mode. */
class DacSharePolicyTest {
    private val modes: List<EffectiveProductMode?> = listOf(null) + EffectiveProductMode.entries

    /** The table the owner chose, written out row by row rather than derived from the rule it checks. */
    private fun expected(switchOn: Boolean, purity: Boolean, mode: EffectiveProductMode?): Boolean = when {
        !switchOn -> false
        mode == EffectiveProductMode.APP -> true         // app mode: Purity and Resonance
        mode == EffectiveProductMode.PINNED -> false     // owner 11-23: no other app can be opened, nobody to share with
        mode == EffectiveProductMode.DEDICATED_SETUP_REQUIRED -> false
        mode == EffectiveProductMode.DEDICATED_ACTIVE -> false
        else -> !purity                                  // LAUNCHER, unknown: Resonance shares, Purity keeps the DAC
    }

    @Test
    fun `the whole table - switch x Purity x every effective mode, and unknown`() {
        var rows = 0
        for (switchOn in listOf(false, true)) for (purity in listOf(false, true)) for (mode in modes) {
            assertThat(DacSharePolicy.shareAllowed(switchOn, purity, mode)).isEqualTo(expected(switchOn, purity, mode))
            assertThat(DacSharePolicy.shareAllowed(switchOn, purity) { mode }).isEqualTo(expected(switchOn, purity, mode))
            rows++
        }
        assertThat(rows).isEqualTo(2 * 2 * 6)
    }

    @Test
    fun `named rows - the owner's S22 and the HONOR in Dedicated`() {
        // S22, app mode, Purity off, switch on: shared (before B68 it was not, with nothing for the DSP to do)
        assertThat(DacSharePolicy.shareAllowed(true, false, EffectiveProductMode.APP)).isTrue()
        // S22, app mode, Purity on, switch on: shared (option D)
        assertThat(DacSharePolicy.shareAllowed(true, true, EffectiveProductMode.APP)).isTrue()
        // HONOR, Dedicated, Purity on: never, whatever the switch
        assertThat(DacSharePolicy.shareAllowed(true, true, EffectiveProductMode.DEDICATED_ACTIVE)).isFalse()
        assertThat(DacSharePolicy.shareAllowed(false, true, EffectiveProductMode.DEDICATED_ACTIVE)).isFalse()
        // HONOR, Dedicated, Resonance, switch on: never either (owner 11-23: there is no other app to give it to)
        assertThat(DacSharePolicy.shareAllowed(true, false, EffectiveProductMode.DEDICATED_ACTIVE)).isFalse()
        // Launcher, Resonance, switch on: shared, as before
        assertThat(DacSharePolicy.shareAllowed(true, false, EffectiveProductMode.LAUNCHER)).isTrue()
        // the default (switch off): never, anywhere
        for (purity in listOf(false, true)) for (mode in modes) assertThat(DacSharePolicy.shareAllowed(false, purity, mode)).isFalse()
    }

    @Test
    fun `the lazy form asks the mode only when it decides`() {
        var reads = 0
        val mode = { reads++; EffectiveProductMode.APP }
        DacSharePolicy.shareAllowed(false, true, mode)
        DacSharePolicy.shareAllowed(false, false, mode)
        assertThat(reads).isEqualTo(0)
        DacSharePolicy.shareAllowed(true, true, mode)
        DacSharePolicy.shareAllowed(true, false, mode)
        assertThat(reads).isEqualTo(2)
    }

    @Test
    fun `the subtitle says what the engine does - app mode for both, DAP Purity keeps, DAP Resonance as before`() {
        for (purity in listOf(false, true)) {
            assertThat(DacSharePolicy.subtitle(purity, EffectiveProductMode.APP)).isEqualTo(DacSharePolicy.Subtitle.APP_MODE)
        }
        for (mode in listOf(EffectiveProductMode.LAUNCHER, null)) {
            assertThat(DacSharePolicy.subtitle(true, mode)).isEqualTo(DacSharePolicy.Subtitle.DAP_PURITY_KEEPS)
            assertThat(DacSharePolicy.subtitle(false, mode)).isEqualTo(DacSharePolicy.Subtitle.DAP_RESONANCE)
        }
        for (mode in listOf(EffectiveProductMode.PINNED, EffectiveProductMode.DEDICATED_SETUP_REQUIRED, EffectiveProductMode.DEDICATED_ACTIVE)) {
            for (purity in listOf(false, true)) assertThat(DacSharePolicy.subtitle(purity, mode)).isEqualTo(DacSharePolicy.Subtitle.HIDDEN)
        }
    }

    @Test
    fun `the subtitle and the engine agree - a subtitle that promises sharing is a mode where sharing is allowed with the switch on`() {
        for (purity in listOf(false, true)) for (mode in modes) {
            val subtitle = DacSharePolicy.subtitle(purity, mode)
            val promisesSharing = subtitle != DacSharePolicy.Subtitle.DAP_PURITY_KEEPS && subtitle != DacSharePolicy.Subtitle.HIDDEN
            assertThat(DacSharePolicy.shareAllowed(true, purity, mode)).isEqualTo(promisesSharing)
        }
    }

    @Test
    fun `nothing stored - on in app mode only, and the listener's choice always wins (owner 11-22)`() {
        for (mode in modes) {
            assertThat(DacSharePolicy.switchOn(null, mode)).isEqualTo(mode == EffectiveProductMode.APP)
            assertThat(DacSharePolicy.switchOn(null) { mode }).isEqualTo(mode == EffectiveProductMode.APP)
            assertThat(DacSharePolicy.switchOn(true, mode)).isTrue()
            assertThat(DacSharePolicy.switchOn(false, mode)).isFalse()
        }
        // a stored choice never asks Android for the mode
        assertThat(DacSharePolicy.switchOn(false) { error("asked for the mode") }).isFalse()
        assertThat(DacSharePolicy.switchOn(true) { error("asked for the mode") }).isTrue()
    }

    @Test
    fun `the HONOR in Dedicated with nothing stored keeps the DAC in Purity, as before`() {
        val on = DacSharePolicy.switchOn(null, EffectiveProductMode.DEDICATED_ACTIVE)
        assertThat(DacSharePolicy.shareAllowed(on, purityInForce = true, effectiveMode = EffectiveProductMode.DEDICATED_ACTIVE)).isFalse()
        assertThat(DacSharePolicy.shareAllowed(on, purityInForce = false, effectiveMode = EffectiveProductMode.DEDICATED_ACTIVE)).isFalse()
    }
}
