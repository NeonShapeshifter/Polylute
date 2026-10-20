package com.lostf1sh.pixelplayeross.data.volume

import com.decent.usbaudio.UsbVolumeRange
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.headphones.SafetyMaxCalibration
import com.lostf1sh.pixelplayeross.data.headphones.SafetyVolumeBackendKind
import com.lostf1sh.pixelplayeross.data.service.player.HandoffVolumeVerdict
import org.junit.jupiter.api.Test

/**
 * B27-A3 (review R-03): the published level is the one the DAC reported; a write it refused is unverified and is never credited
 * with the level that was asked for, and a rejected reduction never enables an automatic resume.
 */
class VolumeWriteTest {

    @Test
    fun `a last partial hardware step is never rounded down to the highest selectable ceiling`() {
        val range = UsbVolumeRange(-127 * 256, 0, 256)
        val observed = UsbHardwareVolumePolicy.stepCeilFor(0, range)
        assertThat(observed).isGreaterThan(UsbHardwareVolumePolicy.maxStep(range))
        assertThat(range.minUnits + observed * UsbHardwareVolumePolicy.stepUnits(range)).isAtLeast(0)
    }

    @Test
    fun `the level published is the one observed, not the one asked for`() {
        assertThat(VolumeWrite(requestedStep = 20, observedStep = 30).publishedStep(lastKnown = 40)).isEqualTo(30)
        assertThat(VolumeWrite(requestedStep = 20, observedStep = 20).publishedStep(lastKnown = 40)).isEqualTo(20)
        assertThat(VolumeWrite(requestedStep = 20, observedStep = 10).publishedStep(lastKnown = 40)).isEqualTo(10)
    }

    @Test
    fun `a write with no reading publishes the last known level and is unverified`() {
        val write = VolumeWrite(requestedStep = 20, observedStep = null)

        assertThat(write.verified).isFalse()
        assertThat(write.accepted).isFalse()
        assertThat(write.publishedStep(lastKnown = 40)).isEqualTo(40)
    }

    @Test
    fun `a write is accepted only when the DAC reports exactly what was asked`() {
        assertThat(VolumeWrite(20, 20).accepted).isTrue()
        assertThat(VolumeWrite(20, 21).accepted).isFalse()
        assertThat(VolumeWrite(20, 19).accepted).isFalse()
        assertThat(VolumeWrite(20, 21).verified).isTrue()
    }

    @Test
    fun `a reduction failed when the level reported, or the last known, is still above what was asked`() {
        assertThat(VolumeWrite(20, null).reductionFailed(lastKnown = 40)).isTrue()      // no answer
        assertThat(VolumeWrite(20, 40).reductionFailed(lastKnown = 40)).isTrue()        // the DAC kept its level
        assertThat(VolumeWrite(20, 30).reductionFailed(lastKnown = 40)).isTrue()        // it moved, not far enough
        assertThat(VolumeWrite(20, 20).reductionFailed(lastKnown = 40)).isFalse()
        assertThat(VolumeWrite(20, 10).reductionFailed(lastKnown = 40)).isFalse()       // lower than asked is not a failure
        assertThat(VolumeWrite(50, null).reductionFailed(lastKnown = 40)).isFalse()    // a raise is not a reduction
    }

    // ── a level between two steps is the louder one ────────────────────────────────────────────────

    private val range = UsbVolumeRange(minUnits = 0, maxUnits = 60 * 512, resolutionUnits = 256)

    @Test
    fun `a reading between two steps is taken for the louder, never the quieter`() {
        assertThat(UsbHardwareVolumePolicy.stepCeilFor(20 * 512, range)).isEqualTo(20)
        assertThat(UsbHardwareVolumePolicy.stepCeilFor(20 * 512 + 1, range)).isEqualTo(21)
        assertThat(UsbHardwareVolumePolicy.stepCeilFor(21 * 512 - 1, range)).isEqualTo(21)
        assertThat(UsbHardwareVolumePolicy.stepFor(21 * 512 - 1, range)).isEqualTo(20)   // the readout's floor, unchanged
    }

    @Test
    fun `the ceil step is never below the floor step and stays inside the range`() {
        for (units in -1000..(60 * 512 + 1000) step 37) {
            val ceil = UsbHardwareVolumePolicy.stepCeilFor(units, range)
            assertThat(ceil).isAtLeast(UsbHardwareVolumePolicy.stepFor(units, range))
            assertThat(ceil).isAtMost(UsbHardwareVolumePolicy.maxStep(range))
            assertThat(ceil).isAtLeast(0)
        }
    }

    // ── the resume verdict ─────────────────────────────────────────────────────────────────────────

    private val calibration = SafetyMaxCalibration(
        outputContextId = "dac:x",
        backendKind = SafetyVolumeBackendKind.USB_HARDWARE_VOLUME,
        ceilingRaw = 20,
        backendMinRaw = 0,
        backendMaxRaw = 60,
        calibratedAtEpochMs = 1L,
    )

    private fun state(raw: Int, verified: Boolean, calibrated: Boolean = true) = SafetyVolumeState(
        backend = VolumeBackendSnapshot(
            SafetyVolumeBackendKind.USB_HARDWARE_VOLUME, "dac:x", "DAC", raw, 0, 60, true, levelVerified = verified,
        ),
        selectedProfileId = "p",
        safetyEnabled = calibrated,
        calibration = calibration.takeIf { calibrated },
        hardwareDecibels = -40f,
    )

    @Test
    fun `a level below the ceiling that the DAC did not confirm does not resume`() {
        assertThat(HandoffResumeSafety.verdict(state(raw = 15, verified = false))).isEqualTo(HandoffVolumeVerdict.WAIT)
    }

    @Test
    fun `a confirmed level at or below the ceiling resumes`() {
        assertThat(HandoffResumeSafety.verdict(state(raw = 20, verified = true))).isEqualTo(HandoffVolumeVerdict.RESUME)
    }

    @Test
    fun `without a ceiling an unconfirmed conservative level does not resume either`() {
        assertThat(HandoffResumeSafety.verdict(state(raw = 5, verified = false, calibrated = false))).isEqualTo(HandoffVolumeVerdict.WAIT)
        assertThat(HandoffResumeSafety.verdict(state(raw = 5, verified = true, calibrated = false))).isEqualTo(HandoffVolumeVerdict.RESUME)
    }

    @Test
    fun `a confirmed level above the ceiling waits, as before`() {
        assertThat(HandoffResumeSafety.verdict(state(raw = 21, verified = true))).isEqualTo(HandoffVolumeVerdict.WAIT)
    }

    @Test
    fun `no combination of verification and level resumes above the ceiling`() {
        for (raw in 21..60) {
            for (verified in listOf(true, false)) {
                assertThat(HandoffResumeSafety.verdict(state(raw, verified))).isNotEqualTo(HandoffVolumeVerdict.RESUME)
            }
        }
    }
}
