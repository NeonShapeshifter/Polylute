package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * AUD-126: the rules for a remote raise and for labelling a Feature Unit write are tested as rules
 * in `RemoteVolumeRaiseLimitTest`; this asserts the controller actually uses them.
 *
 * The volume authority is Android-bound — it needs `AudioManager`, the DAC profiles and a claimed
 * USB device — so the wiring is checked at source level, the way the rest of this area is.
 */
class RemoteRaiseGuardBoundaryTest {

    private val java = "java/com/lostf1sh/pixelplayeross"
    private val controller = File("src/main", "$java/data/volume/SafetyVolumeController.kt")
        .readText().replace("\r\n", "\n")

    @Test
    fun `an uncalibrated DAC caps a remote raise through the shared policy`() {
        assertThat(controller).contains(
            "maxRaiseSteps = SafetyVolumePolicy.remoteRaiseSteps(current.backend.kind, ceiling)"
        )
        assertThat(controller).contains("VolumeKeyRepeatPolicy.remoteRaiseAllowed(now, lastRemoteRaiseAtMs)")
        // The pacing only ever refuses a raise: lowering goes through untouched.
        assertThat(controller).contains("if (requested > current.backend.rawLevel) {")
    }

    @Test
    fun `every volume write reaches the DAC with the origin that asked for it`() {
        assertThat(controller).contains("setBackendRaw(manager, current.backend, raw, origin)")
        // B27-A3: the same call, with the units worked out one line above it so the read-back can be compared with them.
        assertThat(controller).contains("val units = UsbHardwareVolumePolicy.unitsFor(raw, range)")
        assertThat(controller).contains("usbDevice.writeHardwareVolume(units, origin, access)")
        assertThat(controller).contains("stepVolumeKey(current, -1, ceiling)")
        assertThat(controller).contains("stepVolumeKey(current, 1, ceiling)")
        assertThat(controller).contains("enqueueUsbVolume(backend, target, \"key\", relative = target - backend.rawLevel)")
        assertThat(controller).contains("enqueueUsbVolume(current.backend, requested, \"remote\",")
        assertThat(controller).contains("setRawLevelNow(manager, current, step, \"pristine\")")
        assertThat(controller).contains("setBackendRaw(manager, backend, clamp, \"clamp\")")
    }
}
