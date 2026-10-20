package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.decent.usbaudio.media3.DirectSignalMode
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqLabel
import com.lostf1sh.pixelplayeross.data.dac.fiio.ResonanceSource
import com.lostf1sh.pixelplayeross.data.service.player.AudioBackend
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteReason
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteState
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteTransition
import com.lostf1sh.pixelplayeross.data.volume.PristineModePolicy
import org.junit.jupiter.api.Test

/**
 * B30-P1.4: the honest labels. Purity is "nothing shapes the sound": the app's DSP off **and** the DAC's EQ known off or flat.
 * Resonance names its source. A DAC whose EQ could not be read gets neither word.
 */
class DacEqLabelsTest {
    private fun direct(mode: DirectSignalMode) = AudioRouteState(
        actualBackend = AudioBackend.USB_DIRECT,
        desiredBackend = AudioBackend.USB_DIRECT,
        transition = AudioRouteTransition.DIRECT_ACTIVE,
        reason = if (mode == DirectSignalMode.PRISTINE) AudioRouteReason.DIRECT_PRISTINE else AudioRouteReason.DIRECT_PROCESSED,
        usbDacPresent = true,
        signalMode = mode,
    )

    private val android = AudioRouteState(
        actualBackend = AudioBackend.ANDROID_AUDIO,
        transition = AudioRouteTransition.ANDROID_ACTIVE,
        reason = AudioRouteReason.CROSSFADE_REQUIRES_ANDROID,
        usbDacPresent = true,
    )

    private fun status(mode: DirectSignalMode, eq: DacEqLabel, lock: Boolean = false) =
        AudioStatus(purityPreference = lock, route = direct(mode), sinkDirectActive = true, dacEq = eq)

    // ── Purity ───────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `Purity shows only with no app DSP and the DAC's EQ known off or flat`() {
        assertThat(status(DirectSignalMode.PRISTINE, DacEqLabel.OFF_OR_FLAT).purityHolds).isTrue()
        assertThat(resolveDirectOutputMode(direct(DirectSignalMode.PRISTINE), DacEqLabel.OFF_OR_FLAT)).isEqualTo(DirectOutputMode.PURITY)

        // The DAC's EQ on: no Purity, even though the USB stream is untouched.
        val shaped = status(DirectSignalMode.PRISTINE, DacEqLabel.ON)
        assertThat(shaped.directPristine).isTrue()
        assertThat(shaped.purityHolds).isFalse()
        assertThat(resolveDirectOutputMode(direct(DirectSignalMode.PRISTINE), DacEqLabel.ON)).isEqualTo(DirectOutputMode.RESONANCE)

        // The app's DSP on: no Purity whatever the DAC says.
        assertThat(status(DirectSignalMode.PROCESSED, DacEqLabel.OFF_OR_FLAT).purityHolds).isFalse()
    }

    @Test
    fun `an unknown DAC EQ is not Purity and not Resonance - plain USB Direct`() {
        val unknown = status(DirectSignalMode.PRISTINE, DacEqLabel.UNKNOWN)
        assertThat(unknown.purityHolds).isFalse()
        assertThat(unknown.resonanceSource).isNull()
        assertThat(unknown.dacEqUnknownOnDirect).isTrue()
        assertThat(resolveDirectOutputMode(direct(DirectSignalMode.PRISTINE), DacEqLabel.UNKNOWN)).isNull()
    }

    @Test
    fun `a DAC the app cannot ask is labelled as before`() {
        val plain = status(DirectSignalMode.PRISTINE, DacEqLabel.NOT_APPLICABLE)
        assertThat(plain.purityHolds).isTrue()
        assertThat(plain.dacEqUnknownOnDirect).isFalse()
        assertThat(resolveDirectOutputMode(direct(DirectSignalMode.PRISTINE))).isEqualTo(DirectOutputMode.PURITY)
        assertThat(resolveDirectOutputMode(direct(DirectSignalMode.PROCESSED))).isEqualTo(DirectOutputMode.RESONANCE)
    }

    // ── Resonance and its source ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `Resonance says where the shaping comes from`() {
        assertThat(status(DirectSignalMode.PROCESSED, DacEqLabel.OFF_OR_FLAT).resonanceSource).isEqualTo(ResonanceSource.APP_DSP)
        assertThat(status(DirectSignalMode.PROCESSED, DacEqLabel.NOT_APPLICABLE).resonanceSource).isEqualTo(ResonanceSource.APP_DSP)
        assertThat(status(DirectSignalMode.PRISTINE, DacEqLabel.ON).resonanceSource).isEqualTo(ResonanceSource.DAC_EQ)
        assertThat(status(DirectSignalMode.PROCESSED, DacEqLabel.ON).resonanceSource).isEqualTo(ResonanceSource.BOTH)
        assertThat(status(DirectSignalMode.PRISTINE, DacEqLabel.OFF_OR_FLAT).resonanceSource).isNull()
    }

    @Test
    fun `the ledger's resonance still means the app's DSP only`() {
        // Stats reads `resonance`; B30 did not change it (a DAC EQ on a pristine stream is not the app's DSP).
        assertThat(status(DirectSignalMode.PRISTINE, DacEqLabel.ON).resonance).isFalse()
        assertThat(status(DirectSignalMode.PROCESSED, DacEqLabel.OFF_OR_FLAT).resonance).isTrue()
    }

    @Test
    fun `off the Direct route neither Purity nor Resonance is said, whatever the DAC's EQ is`() {
        val onAndroid = AudioStatus(route = android, dacEq = DacEqLabel.ON)
        assertThat(onAndroid.purityHolds).isFalse()
        assertThat(onAndroid.resonanceSource).isNull()
        assertThat(onAndroid.dacEqShaping).isFalse()
        assertThat(resolveDirectOutputMode(android, DacEqLabel.ON)).isNull()
    }

    // ── The lock's own line (Audio page) ─────────────────────────────────────────────────────────────────

    @Test
    fun `the lock never says untouched with the DAC's EQ on or unreadable`() {
        assertThat(status(DirectSignalMode.PRISTINE, DacEqLabel.OFF_OR_FLAT, lock = true).purity)
            .isEqualTo(PristineModePolicy.Status.ACTIVE)
        assertThat(status(DirectSignalMode.PRISTINE, DacEqLabel.NOT_APPLICABLE, lock = true).purity)
            .isEqualTo(PristineModePolicy.Status.ACTIVE)

        val on = status(DirectSignalMode.PRISTINE, DacEqLabel.ON, lock = true)
        assertThat(on.purity).isEqualTo(PristineModePolicy.Status.DAC_EQ_ON)
        assertThat(on.purityLine).isEqualTo(PurityLine(R.string.pristine_status_dac_eq_on, null))

        val unknown = status(DirectSignalMode.PRISTINE, DacEqLabel.UNKNOWN, lock = true)
        assertThat(unknown.purity).isEqualTo(PristineModePolicy.Status.DAC_EQ_UNKNOWN)
        assertThat(unknown.purityLine).isEqualTo(PurityLine(R.string.pristine_status_dac_eq_unknown, null))
    }

    @Test
    fun `with the lock off the reason names the source`() {
        assertThat(status(DirectSignalMode.PRISTINE, DacEqLabel.ON).purityLine)
            .isEqualTo(PurityLine(R.string.pristine_status_off, R.string.pristine_reason_resonance_dac))
        assertThat(status(DirectSignalMode.PROCESSED, DacEqLabel.OFF_OR_FLAT).purityLine)
            .isEqualTo(PurityLine(R.string.pristine_status_off, R.string.pristine_reason_resonance))
        assertThat(status(DirectSignalMode.PROCESSED, DacEqLabel.ON).purityLine)
            .isEqualTo(PurityLine(R.string.pristine_status_off, R.string.pristine_reason_resonance_both))
    }

    @Test
    fun `the policy table - the DAC's EQ only matters once everything else says untouched`() {
        val s = PristineModePolicy.Status.entries
        assertThat(s).containsAtLeast(PristineModePolicy.Status.DAC_EQ_ON, PristineModePolicy.Status.DAC_EQ_UNKNOWN)
        fun st(locked: Boolean, direct: Boolean, mode: DirectSignalMode, eq: DacEqLabel) =
            PristineModePolicy.statusWithDacEq(locked, direct, mode, eq)
        assertThat(st(false, true, DirectSignalMode.PRISTINE, DacEqLabel.ON)).isEqualTo(PristineModePolicy.Status.OFF)
        assertThat(st(true, false, DirectSignalMode.PRISTINE, DacEqLabel.ON)).isEqualTo(PristineModePolicy.Status.ONLY_IN_DIRECT)
        assertThat(st(true, true, DirectSignalMode.PROCESSED, DacEqLabel.ON)).isEqualTo(PristineModePolicy.Status.DIRECT_NOT_PRISTINE)
        assertThat(st(true, true, DirectSignalMode.PRISTINE, DacEqLabel.ON)).isEqualTo(PristineModePolicy.Status.DAC_EQ_ON)
        assertThat(st(true, true, DirectSignalMode.PRISTINE, DacEqLabel.UNKNOWN)).isEqualTo(PristineModePolicy.Status.DAC_EQ_UNKNOWN)
        assertThat(st(true, true, DirectSignalMode.PRISTINE, DacEqLabel.OFF_OR_FLAT)).isEqualTo(PristineModePolicy.Status.ACTIVE)
        assertThat(st(true, true, DirectSignalMode.PRISTINE, DacEqLabel.NOT_APPLICABLE)).isEqualTo(PristineModePolicy.Status.ACTIVE)
    }

    @Test
    fun `labels follow the DAC's EQ live`() {
        val route = direct(DirectSignalMode.PRISTINE)
        val before = AudioStatus(route = route, sinkDirectActive = true, dacEq = DacEqLabel.UNKNOWN)
        val after = before.copy(dacEq = DacEqLabel.OFF_OR_FLAT)
        val shaped = after.copy(dacEq = DacEqLabel.ON)
        assertThat(listOf(before, after, shaped).map { resolveDirectOutputMode(it.route, it.dacEq) })
            .containsExactly(null, DirectOutputMode.PURITY, DirectOutputMode.RESONANCE).inOrder()
    }
}
