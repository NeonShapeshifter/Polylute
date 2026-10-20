package com.decent.usbaudio

/**
 * Whether a USB audio device is one this driver can actually drive (AUD-005).
 *
 * The driver is UAC2-only: it sets the sample rate on a UAC2 Clock Source entity. A UAC1 device (or
 * a UAC2 one without a Clock Source) was accepted anyway, force-claimed — which detaches
 * `snd-usb-audio` — and then failed at SET_CUR on every track, leaving the DAC unusable by Android
 * until it was replugged. Both checks here run before anything is claimed.
 */
object UacCompatibility {
    /** `bInterfaceProtocol` of a UAC2 (IP_VERSION_02_00) audio interface. */
    const val UAC2_PROTOCOL = 0x20

    private const val USB_CLASS_AUDIO = 1
    private const val SUBCLASS_AUDIO_CONTROL = 1
    private const val SUBCLASS_AUDIO_STREAMING = 2
    private const val DESCRIPTOR_INTERFACE = 0x04
    private const val DESCRIPTOR_CS_INTERFACE = 0x24
    private const val AC_CLOCK_SOURCE = 0x0A

    fun isDirectStreamingInterface(interfaceClass: Int, subclass: Int, protocol: Int, hasIsoOut: Boolean): Boolean =
        interfaceClass == USB_CLASS_AUDIO && subclass == SUBCLASS_AUDIO_STREAMING &&
            protocol == UAC2_PROTOCOL && hasIsoOut

    fun hasClockSource(raw: ByteArray?): Boolean = clockSourceId(raw) >= 0

    /**
     * The `bClockID` of the first CLOCK_SOURCE descriptor inside an AudioControl interface, or -1.
     * Walks the raw configuration descriptors; a malformed length stops the walk.
     */
    fun clockSourceId(raw: ByteArray?): Int {
        raw ?: return -1
        var i = 0
        var inAudioControl = false
        while (i + 1 < raw.size) {
            val bLength = raw[i].toInt() and 0xFF
            if (bLength < 2 || i + bLength > raw.size) break
            val bDescriptorType = raw[i + 1].toInt() and 0xFF
            if (bDescriptorType == DESCRIPTOR_INTERFACE && bLength >= 9) {
                inAudioControl = (raw[i + 5].toInt() and 0xFF) == USB_CLASS_AUDIO &&
                    (raw[i + 6].toInt() and 0xFF) == SUBCLASS_AUDIO_CONTROL
            }
            if (inAudioControl && bDescriptorType == DESCRIPTOR_CS_INTERFACE && bLength >= 5 &&
                (raw[i + 2].toInt() and 0xFF) == AC_CLOCK_SOURCE
            ) {
                return raw[i + 3].toInt() and 0xFF
            }
            i += bLength
        }
        return -1
    }
}
