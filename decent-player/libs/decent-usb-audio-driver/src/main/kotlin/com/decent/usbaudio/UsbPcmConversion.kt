package com.decent.usbaudio

/**
 * Which source depths a DAC container can carry without loss — the Kotlin mirror of
 * `usbPcmCanConvert` in `usb-pcm-convert.cpp` (tested by the native gate). The sink asks it before
 * claiming, so an unsupported combination is refused up front instead of ending the native decode
 * thread mid-track, which the sink used to read as a natural end of track (AUD-008).
 */
object UsbPcmConversion {
    private val CONTAINERS = setOf(16, 24, 32)

    fun canConvert(sourceBits: Int, containerBits: Int): Boolean =
        sourceBits in CONTAINERS && containerBits in CONTAINERS && containerBits >= sourceBits
}
