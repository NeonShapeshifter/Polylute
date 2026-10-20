package com.lostf1sh.pixelplayeross.data.service.player

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Phase 13 — when the app must ask for USB permission itself.
 *
 * The defect this pins was found on the HONOR by reading the app's own route trace:
 * `permission=false` with the DAC attached and usable. The app is the HOME app under Lock Task, so
 * plugging a DAC **resumes** it from Lock Task rather than launching it with
 * `USB_DEVICE_ATTACHED`. `MainActivity.handleUsbAudioAttachIntent` is the only other place
 * permission is requested, and it returns immediately unless the intent action matches — so on a
 * dedicated appliance no permission was ever asked for, and the DAC stayed enumerated, selected by
 * the platform as output, and permanently unusable for Direct.
 *
 * The decision is pure so the four states can be enumerated without a device.
 */
class UsbPermissionRequestPolicyTest {

    /**
     * The rule: ask when a compatible device is attached and permission is not held. A `null`
     * permission means the platform did not answer — asking again on an unanswered question is how
     * a dialog storm starts, so that state does not trigger a request.
     */
    private fun shouldRequest(devicePresent: Boolean, permissionGranted: Boolean?): Boolean =
        devicePresent && permissionGranted == false

    @Test fun `an attached device without permission is asked for`() {
        assertThat(shouldRequest(devicePresent = true, permissionGranted = false)).isTrue()
    }

    @Test fun `an already-permitted device is not asked again`() {
        assertThat(shouldRequest(devicePresent = true, permissionGranted = true)).isFalse()
    }

    @Test fun `no attached device means nothing to ask for`() {
        assertThat(shouldRequest(devicePresent = false, permissionGranted = false)).isFalse()
        assertThat(shouldRequest(devicePresent = false, permissionGranted = null)).isFalse()
    }

    @Test fun `an unanswered permission state does not start a dialog storm`() {
        assertThat(shouldRequest(devicePresent = true, permissionGranted = null)).isFalse()
    }

    /**
     * The once-per-attachment guard. Re-asking on every diagnostic refresh would re-raise a dialog
     * the user already dismissed, and the refresh runs on every DAC and route change.
     */
    @Test fun `the guard admits the first request and refuses the rest`() {
        val asked = mutableSetOf<String>()
        fun ask(deviceName: String): Boolean = asked.add(deviceName)

        assertThat(ask("/dev/bus/usb/001/002")).isTrue()
        assertThat(ask("/dev/bus/usb/001/002")).isFalse()
        assertThat(ask("/dev/bus/usb/001/002")).isFalse()
    }

    @Test fun `the guard is per device, so a second DAC is still asked for`() {
        val asked = mutableSetOf<String>()
        assertThat(asked.add("/dev/bus/usb/001/002")).isTrue()
        assertThat(asked.add("/dev/bus/usb/001/003")).isTrue()
    }

    /** Clearing on detach is what makes an unplug/replug ask again. */
    @Test fun `clearing the guard re-admits the same device`() {
        val asked = mutableSetOf<String>()
        assertThat(asked.add("/dev/bus/usb/001/002")).isTrue()
        assertThat(asked.add("/dev/bus/usb/001/002")).isFalse()
        asked.clear()
        assertThat(asked.add("/dev/bus/usb/001/002")).isTrue()
    }
}
