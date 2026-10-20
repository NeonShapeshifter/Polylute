package com.lostf1sh.pixelplayeross.data.service.player

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * AUD-003 follow-up (measured on the HONOR): the app cannot read `/proc/asound`
 * (`avc: denied { search } … name="asound" … untrusted_app`), so the playback probe is UNKNOWN and
 * the guard fell back to Android's device list — which keeps the original attach's `usb_headset`
 * through the whole Direct session and says nothing about whether the card came back.
 *
 * After the app hands the DAC back, only a NEW `onAudioDevicesAdded` for a USB output counts: that is
 * Android's "Adding ALSA device card=1", which the device showed does happen when the card is whole.
 */
class UsbRouteSignalTest {

    @Test
    fun `after a release, the stale listing is not an output until a new USB device is added`() {
        val signal = UsbRouteSignal()
        signal.onReleasedToAndroid()
        assertThat(signal.available(UsbPlaybackProbe.Result.UNKNOWN, platformListsUsb = true)).isFalse()

        signal.onOutputDeviceAdded(isUsbOutput = true)
        assertThat(signal.available(UsbPlaybackProbe.Result.UNKNOWN, platformListsUsb = true)).isTrue()
    }

    @Test
    fun `a non-USB device added after the release does not count`() {
        val signal = UsbRouteSignal()
        signal.onReleasedToAndroid()
        signal.onOutputDeviceAdded(isUsbOutput = false)
        assertThat(signal.available(UsbPlaybackProbe.Result.UNKNOWN, platformListsUsb = true)).isFalse()
    }

    @Test
    fun `each release needs its own new event`() {
        val signal = UsbRouteSignal()
        signal.onReleasedToAndroid()
        signal.onOutputDeviceAdded(isUsbOutput = true)
        signal.onReleasedToAndroid()
        assertThat(signal.available(UsbPlaybackProbe.Result.UNKNOWN, platformListsUsb = true)).isFalse()
    }

    @Test
    fun `without any release the platform listing still decides, and a readable probe always wins`() {
        val signal = UsbRouteSignal()
        assertThat(signal.available(UsbPlaybackProbe.Result.UNKNOWN, platformListsUsb = true)).isTrue()
        signal.onReleasedToAndroid()
        assertThat(signal.available(UsbPlaybackProbe.Result.AVAILABLE, platformListsUsb = false)).isTrue()
        assertThat(signal.available(UsbPlaybackProbe.Result.UNAVAILABLE, platformListsUsb = true)).isFalse()
    }

    /**
     * Measured on the HONOR with build fix2: after the hand-back both interfaces were "already bound
     * to snd-usb-audio" and pcmC1D0p was added, but Android never removed `usb_headset` (the USB link
     * stayed up), so no new onAudioDevicesAdded arrived and the guard paused playback forever.
     */
    @Test
    fun `both interfaces back on snd-usb-audio is the route, without any new device event`() {
        val signal = UsbRouteSignal()
        signal.onReleasedToAndroid()
        signal.onKernelDriversAfterRelease(controlDriver = "snd-usb-audio", streamingDriver = "snd-usb-audio")
        assertThat(signal.available(UsbPlaybackProbe.Result.UNKNOWN, platformListsUsb = true)).isTrue()
    }

    @Test
    fun `a streaming interface left without snd-usb-audio is not a route`() {
        for (streaming in listOf(null, "usbfs")) {
            val signal = UsbRouteSignal()
            signal.onReleasedToAndroid()
            signal.onKernelDriversAfterRelease(controlDriver = "snd-usb-audio", streamingDriver = streaming)
            assertThat(signal.available(UsbPlaybackProbe.Result.UNKNOWN, platformListsUsb = true)).isFalse()
        }
    }

    @Test
    fun `the driver check does not override an unreadable platform listing or a readable probe`() {
        val signal = UsbRouteSignal()
        signal.onReleasedToAndroid()
        signal.onKernelDriversAfterRelease("snd-usb-audio", "snd-usb-audio")
        assertThat(signal.available(UsbPlaybackProbe.Result.UNAVAILABLE, platformListsUsb = true)).isFalse()
        assertThat(signal.available(UsbPlaybackProbe.Result.UNKNOWN, platformListsUsb = false)).isFalse()
    }
}
