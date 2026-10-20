package com.lostf1sh.pixelplayeross.data.usb

import com.decent.usbaudio.UacCompatibility
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * AUD-005: the Direct driver speaks UAC2 only — it sets the rate on a Clock Source entity, which
 * UAC1 does not have. A UAC1 DAC used to be force-claimed (detaching `snd-usb-audio`), fail at
 * SET_CUR every time, and be left unusable by Android until replugged. It must be recognised before
 * any claim and left to Android.
 */
class UacCompatibilityTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    /** Configuration descriptor, 9 bytes. */
    private val config = bytes(9, 0x02, 0, 0, 2, 1, 0, 0x80, 50)

    private fun audioControlInterface(protocol: Int) = bytes(9, 0x04, 0, 0, 0, 0x01, 0x01, protocol, 0)

    /** CS_INTERFACE AC header (subtype 0x01). */
    private val acHeader = bytes(9, 0x24, 0x01, 0x00, 0x02, 0x08, 0x40, 0x00, 0x00)

    /** UAC2 Clock Source (subtype 0x0A), bClockID = 0x29. */
    private val clockSource = bytes(8, 0x24, 0x0A, 0x29, 0x03, 0x07, 0x00, 0x00)

    /** UAC1 Input Terminal (subtype 0x02). */
    private val inputTerminal = bytes(12, 0x24, 0x02, 0x01, 0x01, 0x01, 0x00, 0x02, 0x03, 0x00, 0x00, 0x00)

    /** An AudioStreaming interface carrying a byte that looks like a clock source, outside AC. */
    private val streamingInterface = bytes(9, 0x04, 1, 1, 1, 0x01, 0x02, 0x20, 0)
    private val strayOutsideAc = bytes(8, 0x24, 0x0A, 0x29, 0x03, 0x07, 0x00, 0x00)

    @Test
    fun `a UAC2 descriptor set with a Clock Source is Direct capable`() {
        val raw = config + audioControlInterface(0x20) + acHeader + clockSource
        assertThat(UacCompatibility.hasClockSource(raw)).isTrue()
    }

    @Test
    fun `a UAC1 descriptor set has no Clock Source`() {
        val raw = config + audioControlInterface(0x00) + acHeader + inputTerminal
        assertThat(UacCompatibility.hasClockSource(raw)).isFalse()
    }

    @Test
    fun `a subtype 0x0A outside the AudioControl interface does not count`() {
        val raw = config + audioControlInterface(0x00) + acHeader + inputTerminal +
            streamingInterface + strayOutsideAc
        assertThat(UacCompatibility.hasClockSource(raw)).isFalse()
    }

    @Test
    fun `missing or truncated descriptors are not Direct capable`() {
        assertThat(UacCompatibility.hasClockSource(null)).isFalse()
        assertThat(UacCompatibility.hasClockSource(bytes(9, 0x04, 0))).isFalse()
    }

    @Test
    fun `only a UAC2 AudioStreaming interface with an iso OUT endpoint qualifies`() {
        assertThat(UacCompatibility.isDirectStreamingInterface(1, 2, 0x20, hasIsoOut = true)).isTrue()
        // UAC1 AudioStreaming: protocol 0.
        assertThat(UacCompatibility.isDirectStreamingInterface(1, 2, 0x00, hasIsoOut = true)).isFalse()
        assertThat(UacCompatibility.isDirectStreamingInterface(1, 2, 0x20, hasIsoOut = false)).isFalse()
        assertThat(UacCompatibility.isDirectStreamingInterface(1, 1, 0x20, hasIsoOut = true)).isFalse()
    }
}
