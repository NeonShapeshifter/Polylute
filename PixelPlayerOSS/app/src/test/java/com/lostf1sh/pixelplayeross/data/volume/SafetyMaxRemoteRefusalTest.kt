package com.lostf1sh.pixelplayeross.data.volume

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.headphones.SafetyVolumeBackendKind
import org.junit.jupiter.api.Test

/**
 * F-07 (owner, 2026-10-06): with the phone locked in PRISTINE (the DAC's hardware volume) the volume
 * could not be raised; in PROCESSED it could. Locked, the keys reach the media session's remote
 * volume, which applies the Safety Max ceiling with no override gesture, and at the ceiling the press
 * was refused with no word to the listener.
 */
class SafetyMaxRemoteRefusalTest {

    private val dac = VolumeBackendSnapshot(
        SafetyVolumeBackendKind.USB_HARDWARE_VOLUME,
        "dac:fiio-melody",
        "FiiO Melody",
        rawLevel = 20,
        rawMin = 0,
        rawMax = 60,
        controllable = true,
    )
    private val ceiling = 24

    @Test
    fun `a raise from the ceiling is refused, and is the one the listener is told about`() {
        assertThat(SafetyVolumePolicy.raiseRefusedByCeiling(25, currentRaw = 24, ceilingRaw = ceiling, overrideActive = false)).isTrue()
        // A request to go further than one step, from the ceiling, is the same refusal.
        assertThat(SafetyVolumePolicy.raiseRefusedByCeiling(40, currentRaw = 24, ceilingRaw = ceiling, overrideActive = false)).isTrue()
        // Past the ceiling already (a clamp is about to land): still a refusal.
        assertThat(SafetyVolumePolicy.raiseRefusedByCeiling(31, currentRaw = 30, ceilingRaw = ceiling, overrideActive = false)).isTrue()
    }

    @Test
    fun `below the ceiling a raise goes up, to the ceiling, and is not a refusal`() {
        assertThat(SafetyVolumePolicy.raiseRefusedByCeiling(21, currentRaw = 20, ceilingRaw = ceiling, overrideActive = false)).isFalse()
        // Asking for more than the ceiling from below: not refused, applied as far as the ceiling.
        assertThat(SafetyVolumePolicy.raiseRefusedByCeiling(40, currentRaw = 22, ceilingRaw = ceiling, overrideActive = false)).isFalse()
        val applied = SafetyVolumePolicy.requestedRaw(40, dac.copy(rawLevel = 22), ceiling, overrideActive = false)
        assertThat(applied).isEqualTo(ceiling)
    }

    @Test
    fun `lowering, holding and the absence of a ceiling are never refusals`() {
        assertThat(SafetyVolumePolicy.raiseRefusedByCeiling(23, currentRaw = 24, ceilingRaw = ceiling, overrideActive = false)).isFalse()
        assertThat(SafetyVolumePolicy.raiseRefusedByCeiling(24, currentRaw = 24, ceilingRaw = ceiling, overrideActive = false)).isFalse()
        assertThat(SafetyVolumePolicy.raiseRefusedByCeiling(40, currentRaw = 24, ceilingRaw = null, overrideActive = false)).isFalse()
    }

    @Test
    fun `an override the listener made in the unlocked app lifts the refusal, nothing else does`() {
        assertThat(SafetyVolumePolicy.raiseRefusedByCeiling(25, currentRaw = 24, ceilingRaw = ceiling, overrideActive = true)).isFalse()
    }

    @Test
    fun `the remote path can never pass the ceiling without the override`() {
        // Whatever the remote channel asks, the level it can reach is the ceiling.
        for (current in 0..24) {
            for (requested in 0..60) {
                val applied = SafetyVolumePolicy.requestedRaw(
                    requestedRaw(requested, current),
                    dac.copy(rawLevel = current),
                    ceiling,
                    overrideActive = false,
                )
                assertThat(applied).isAtMost(ceiling)
            }
        }
        // And only the override opens it.
        assertThat(SafetyVolumePolicy.requestedRaw(40, dac.copy(rawLevel = 24), ceiling, overrideActive = true))
            .isEqualTo(40)
    }

    /** The remote channel's own pacing (REMOTE_MAX_RAISE_STEPS) then the ceiling, as `setLevel` does. */
    private fun requestedRaw(requested: Int, current: Int): Int =
        remoteRequestedRaw(requested, current, SafetyVolumePolicy.remoteRaiseSteps(dac.kind, ceilingRaw = ceiling))
}
