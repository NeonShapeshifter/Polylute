package com.lostf1sh.pixelplayeross.data.volume

import com.decent.usbaudio.DacLevelGuard
import com.decent.usbaudio.FeatureUnitPort
import com.decent.usbaudio.OpenLevelCheck
import com.decent.usbaudio.UsbVolumeRange
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * B39-S1 (hearing safety): a tester's dongle started at its maximum volume on the first play. The host's open guard answered "nothing
 * to change" for a DAC it had no record of, and the driver then let it play at whatever it had booted at.
 *
 * Here the driver's own restore ([DacLevelGuard], the code that runs after every claim and reconfigure and decides whether a stream may
 * start) is driven by the host's policy with a simulated DAC that boots at 100 percent: the level must be the first-use one before the
 * first packet, and a level the DAC does not confirm must keep the first packet from ever being sent.
 */
class FirstUseDacLevelTest {
    private val range = UsbVolumeRange(-127 * 256, 0, 256)
    private val firstUse = UsbHardwareVolumePolicy.firstUseUnits(range)

    /** A DAC that boots at its loudest. [refuseWrites] keeps the level where it is; [unreadable] answers no read at all. */
    private class BootingDac(
        var units: Int,
        val range: UsbVolumeRange,
        val refuseWrites: Boolean = false,
        val unreadable: Boolean = false,
    ) : FeatureUnitPort {
        var levelAtFirstPacket: Int? = null
        var writes = 0
        fun firstPacket() { levelAtFirstPacket = units }
        override fun readRange() = range
        override fun readUnits(): Int? = if (unreadable) null else units
        override fun writeUnits(units: Int): Int? {
            writes++
            if (!refuseWrites) this.units = range.clamp(units)
            return if (unreadable) null else this.units
        }
    }

    /** What the sink does at a claim: the host's guard answer into the driver's restore, then the first packet only if it was not refused. */
    private fun open(dac: BootingDac, saved: Int?, ceiling: Int?, unknown: Boolean): OpenLevelCheck {
        val current = dac.units
        val guardTarget = UsbHardwareVolumePolicy.openTarget(current, saved, ceiling, if (unknown) firstUse else null)
        val check = DacLevelGuard {}.restoreAfterReconfigure(dac, saved, guardTarget, "open")
        if (check != OpenLevelCheck.UNVERIFIED) dac.firstPacket()
        return check
    }

    @Test
    fun `an unknown DAC reporting 100 percent is at the first-use level before the first packet`() {
        val dac = BootingDac(units = 0, range = range)
        assertThat(open(dac, saved = null, ceiling = null, unknown = true)).isEqualTo(OpenLevelCheck.VERIFIED)
        assertThat(dac.levelAtFirstPacket).isEqualTo(firstUse)
        assertThat(dac.levelAtFirstPacket!!).isAtMost(-30 * 256)
    }

    @Test
    fun `a first-use write the DAC does not take sends no packet at all`() {
        val dac = BootingDac(units = 0, range = range, refuseWrites = true)
        assertThat(open(dac, saved = null, ceiling = null, unknown = true)).isEqualTo(OpenLevelCheck.UNVERIFIED)
        assertThat(dac.writes).isEqualTo(1)
        assertThat(dac.levelAtFirstPacket).isNull()
    }

    @Test
    fun `a DAC whose level cannot be read sends no packet either`() {
        val dac = BootingDac(units = 0, range = range, unreadable = true)
        assertThat(open(dac, saved = null, ceiling = null, unknown = true)).isEqualTo(OpenLevelCheck.UNVERIFIED)
        assertThat(dac.levelAtFirstPacket).isNull()
    }

    @Test
    fun `a known DAC keeps the level it remembers and is never given the first-use one`() {
        val remembered = -12 * 256
        val dac = BootingDac(units = 0, range = range)
        assertThat(open(dac, saved = remembered, ceiling = null, unknown = false)).isEqualTo(OpenLevelCheck.VERIFIED)
        assertThat(dac.levelAtFirstPacket).isEqualTo(remembered)
        // Louder than the first-use level, on purpose: that was the listener's choice.
        assertThat(dac.levelAtFirstPacket!!).isGreaterThan(firstUse)
    }

    @Test
    fun `a known DAC already quieter than the first-use level is not raised`() {
        val dac = BootingDac(units = -50 * 256, range = range)
        assertThat(open(dac, saved = null, ceiling = null, unknown = true)).isEqualTo(OpenLevelCheck.VERIFIED)
        assertThat(dac.writes).isEqualTo(0)
        assertThat(dac.levelAtFirstPacket).isEqualTo(-50 * 256)
    }

    @Test
    fun `a calibrated ceiling above the first-use level still clamps a loud DAC to the ceiling`() {
        val ceiling = -6 * 256
        val dac = BootingDac(units = 0, range = range)
        assertThat(open(dac, saved = null, ceiling = ceiling, unknown = false)).isEqualTo(OpenLevelCheck.VERIFIED)
        assertThat(dac.levelAtFirstPacket).isEqualTo(ceiling)
    }
}
