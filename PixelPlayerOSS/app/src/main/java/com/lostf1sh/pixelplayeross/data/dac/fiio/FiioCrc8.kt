package com.lostf1sh.pixelplayeross.data.dac.fiio

/**
 * B30-P1: the check byte FiiO's HID protocol puts in front of the stop byte.
 *
 * CRC-8/MAXIM: polynomial 0x31 (0x8C reflected), initial value 0x00, input and output reflected, no final xor. It covers
 * the frame from its head byte (`BB` or `AA`) through the last data byte.
 *
 * What backs it:
 * - the five well-formed replies the Melody sent to `PROBE_DAC_HID` (`trial-exo-2026-10-06/diagnostics/dac-hid.txt`), as
 *   checked by the reviewer;
 * - thirteen more replies of another Melody unit recorded in DevicePEQ's `tests/captures/fiio_snowsky_melody.json` (0BSD),
 *   which `FiioCrc8Test` recomputes.
 *
 * What is not known: whether the DAC checks the byte on what it *receives*. Our GET frames have always carried `00` there
 * and were answered; WolfEQ and DevicePEQ send `00` in their SETs as well. The SET builder writes the real value (the
 * reply format is the only evidence of what the DAC computes), and [FiioCrcMode.ZERO] is the escape hatch for a device that
 * proves to want the other form.
 */
object FiioCrc8 {
    private const val REFLECTED_POLY = 0x8C

    fun maxim(bytes: ByteArray, from: Int = 0, until: Int = bytes.size): Int {
        var crc = 0
        for (index in from until until) {
            crc = crc xor (bytes[index].toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 1 != 0) (crc ushr 1) xor REFLECTED_POLY else crc ushr 1
            }
        }
        return crc and 0xFF
    }
}

/** What a frame we send carries in its check byte. */
enum class FiioCrcMode {
    /** The CRC-8/MAXIM of the frame, as the DAC computes it on its replies. */
    MAXIM,

    /** `00`, the form WolfEQ's and DevicePEQ's frames use (and our GETs). */
    ZERO,
}
