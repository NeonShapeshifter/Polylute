package com.decent.usbaudio

/**
 * The level a DAC must be at before Direct plays through it (AUD-118).
 *
 * Measured on the HONOR: after a pass through the Android route, snd-usb-audio / the USB HAL left
 * the Feature Unit near 0 dB, and the DAC came back to Direct at full level ("me rompió los oídos").
 * The previous guard lowered it only when Safety resolved a `dac:<id>` context, which it did not at
 * that instant. So the driver remembers the last Direct level per USB device itself and restores it
 * on every claim; Safety's ceiling, when there is one, only ever lowers it further.
 */
object DirectOpenLevelPolicy {
    /**
     * The level to write on open, in native units, or null to leave it: the quietest of the saved
     * Direct level and the ceiling, and only when that is below [currentUnits]. Never raises.
     */
    fun target(currentUnits: Int, savedDirectUnits: Int?, ceilingUnits: Int?): Int? {
        val quietest = listOfNotNull(savedDirectUnits, ceilingUnits).minOrNull() ?: return null
        return quietest.takeIf { it < currentUnits }
    }

    /** Identity of the physical DAC being opened: vendor, product and serial when readable. */
    fun deviceKey(vendorId: Int, productId: Int, serial: String?): String =
        "$vendorId:$productId:${serial.orEmpty()}"
}

/** Whether the level a freshly claimed DAC plays at is known to be safe. */
enum class OpenLevelCheck {
    /** Read, and restored where needed. */
    VERIFIED,

    /** No hardware volume to read (fixed output); nothing to restore. */
    NO_HARDWARE_VOLUME,

    /** The level could not be read or restored: nothing may play through it (AUD-118). */
    UNVERIFIED,
}
