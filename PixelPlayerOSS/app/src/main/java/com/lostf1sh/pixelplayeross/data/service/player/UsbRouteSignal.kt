package com.lostf1sh.pixelplayeross.data.service.player

/**
 * Whether the Android path can play to the USB DAC, when `/proc/asound` cannot be read (AUD-003).
 *
 * Measured on the HONOR: the app's SELinux domain cannot search `/proc/asound`, so
 * [UsbPlaybackProbe] is UNKNOWN; and Android's device list keeps the original attach's
 * `usb_headset` for the whole Direct session, so it cannot tell whether the card came back after the
 * app handed the DAC over. What does happen when the card is whole is Android adding the ALSA device
 * again — a *new* `onAudioDevicesAdded` for a USB output — or, as measured with build fix2 where
 * Android kept `usb_headset` and never re-added it, the hand-back itself reading both interfaces
 * back on `snd-usb-audio` (USBDEVFS_GETDRIVER). After each hand-back, only one of those counts.
 *
 * Main thread only, like the engine's route loop and the device callback.
 */
class UsbRouteSignal {
    private var awaitingNewUsbOutput = false

    /** The app just released the DAC to Android; the old listing no longer means anything. */
    fun onReleasedToAndroid() {
        awaitingNewUsbOutput = true
    }

    /** Kernel drivers of the control and streaming interfaces, read by the hand-back itself. */
    fun onKernelDriversAfterRelease(controlDriver: String?, streamingDriver: String?) {
        // Both interfaces rebound to snd-usb-audio is a card with its playback stream: the route.
        // Android need not re-add the device — it never removed it while the USB link stayed up.
        if (controlDriver == SND_USB_AUDIO && streamingDriver == SND_USB_AUDIO) awaitingNewUsbOutput = false
    }

    fun onOutputDeviceAdded(isUsbOutput: Boolean) {
        if (isUsbOutput) awaitingNewUsbOutput = false
    }

    fun available(probe: UsbPlaybackProbe.Result, platformListsUsb: Boolean): Boolean = when (probe) {
        UsbPlaybackProbe.Result.AVAILABLE -> true
        UsbPlaybackProbe.Result.UNAVAILABLE -> false
        UsbPlaybackProbe.Result.UNKNOWN -> platformListsUsb && !awaitingNewUsbOutput
    }

    private companion object {
        const val SND_USB_AUDIO = "snd-usb-audio"
    }
}
