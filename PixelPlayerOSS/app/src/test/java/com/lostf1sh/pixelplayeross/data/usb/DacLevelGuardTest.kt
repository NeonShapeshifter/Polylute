package com.lostf1sh.pixelplayeross.data.usb

import com.decent.usbaudio.DacLevelGuard
import com.decent.usbaudio.FeatureUnitPort
import com.decent.usbaudio.OpenLevelCheck
import com.decent.usbaudio.UsbVolumeRange
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * AUD-126 (critical, confirmed on the device 28/09 12:12 → 12:23): the SNOWSKY Melody's Feature Unit
 * went from −6 dB (the owner's Safety ceiling, set by the AUD-118 guard at the claim) to 0 dB while
 * nobody touched a volume. In between: ten NEXT with rate changes (96 ↔ 44.1 ↔ 48 kHz), each one
 * `setAlt(0)` → clock `SET_CUR` → `setAlt(3)` on the device already open ("Device already open,
 * reusing") — past the guard, which only ran on a claim.
 *
 * The simulated DAC below resets its Feature Unit to 0 dB on every clock `SET_CUR` and every
 * `setAlt`, which is the firmware behaviour the owner's session points at. After a rate change the
 * level must be back at the saved Direct level before the first audio packet.
 */
class DacLevelGuardTest {
    private val sixDbDown = -6 * UsbVolumeRange.UNITS_PER_DB
    private val twentyDbDown = -20 * UsbVolumeRange.UNITS_PER_DB

    /** A DAC whose firmware puts its Feature Unit back to 0 dB on every clock or alt change. */
    private class ResettingDac(var units: Int, var refuseWrites: Boolean = false) : FeatureUnitPort {
        val range = UsbVolumeRange(-127 * 256, 0, 256)
        var levelAtFirstPacket: Int? = null
        fun setAlt(@Suppress("UNUSED_PARAMETER") alt: Int) { units = 0 }
        fun setClock(@Suppress("UNUSED_PARAMETER") rate: Int) { units = 0 }
        fun firstPacket() { levelAtFirstPacket = units }
        override fun readRange() = range
        override fun readUnits() = units
        override fun writeUnits(units: Int): Int? {
            if (!refuseWrites) this.units = range.clamp(units)
            return this.units
        }
    }

    /** The sink's reconfigure sequence (Steps 1-7 of configureUsbBitPerfect), with the guard at 5½. */
    private fun rateChange(dac: ResettingDac, guard: DacLevelGuard, saved: Int?, ceiling: Int?): OpenLevelCheck {
        dac.setAlt(0)
        dac.setClock(96_000)
        dac.setAlt(0)
        dac.setAlt(3)
        val check = guard.restoreAfterReconfigure(dac, saved, ceiling, "rate=96000 alt=3")
        if (check != OpenLevelCheck.UNVERIFIED) dac.firstPacket()
        return check
    }

    @Test
    fun `after a rate change the level is back at the saved Direct level before the first packet`() {
        val log = mutableListOf<String>()
        val dac = ResettingDac(sixDbDown)
        val guard = DacLevelGuard { log += it }
        guard.onWrite("open-restore", sixDbDown, sixDbDown, sixDbDown)
        assertThat(rateChange(dac, guard, saved = sixDbDown, ceiling = sixDbDown)).isEqualTo(OpenLevelCheck.VERIFIED)
        assertThat(dac.levelAtFirstPacket).isEqualTo(sixDbDown)
        // (b) The reset is logged as an external change, and the restore as a write with its origin.
        assertThat(log.any { it.startsWith("FU_EXTERNAL_CHANGE") && it.contains("$sixDbDown → 0") }).isTrue()
        assertThat(log.any { it.startsWith("FU_WRITE") && it.contains("origin=reconfigure") }).isTrue()
    }

    @Test
    fun `the quietest of saved and ceiling wins, and the guard never raises`() {
        val dac = ResettingDac(twentyDbDown)
        val guard = DacLevelGuard {}
        guard.onRead("poll", twentyDbDown)
        assertThat(rateChange(dac, guard, saved = twentyDbDown, ceiling = sixDbDown)).isEqualTo(OpenLevelCheck.VERIFIED)
        assertThat(dac.levelAtFirstPacket).isEqualTo(twentyDbDown)

        // A DAC that kept its level (−20 dB) under a −6 dB ceiling is left alone: never raised.
        val quiet = object : FeatureUnitPort {
            var units = twentyDbDown
            override fun readRange() = UsbVolumeRange(-127 * 256, 0, 256)
            override fun readUnits() = units
            override fun writeUnits(units: Int): Int? { this.units = units; return units }
        }
        assertThat(guard.restoreAfterReconfigure(quiet, savedUnits = null, guardTargetUnits = sixDbDown, reason = "rate=44100 alt=3"))
            .isEqualTo(OpenLevelCheck.VERIFIED)
        assertThat(quiet.units).isEqualTo(twentyDbDown)
    }

    @Test
    fun `with nothing saved the level heard before the reconfigure is the one restored`() {
        val dac = ResettingDac(sixDbDown)
        val guard = DacLevelGuard {}
        guard.onRead("poll", sixDbDown)
        assertThat(rateChange(dac, guard, saved = null, ceiling = null)).isEqualTo(OpenLevelCheck.VERIFIED)
        assertThat(dac.levelAtFirstPacket).isEqualTo(sixDbDown)
    }

    @Test
    fun `a restore that does not take refuses the stream`() {
        val dac = ResettingDac(sixDbDown, refuseWrites = true)
        val guard = DacLevelGuard {}
        assertThat(rateChange(dac, guard, saved = sixDbDown, ceiling = null)).isEqualTo(OpenLevelCheck.UNVERIFIED)
        assertThat(dac.levelAtFirstPacket).isNull()
    }

    @Test
    fun `wiring - the sink restores between the alt and the first packet, and the device logs every FU write`() {
        val sink = File(
            "../../decent-player/libs/decent-usb-audio-wrapper-media3/src/main/kotlin/com/decent/usbaudio/media3/UsbAudioSink.kt"
        ).readText().replace("\r\n", "\n")
        val sequence = sink.substringAfter("// Step 5: setAlt(N)").substringBefore("if (!stream.start())")
        assertThat(sequence).contains("usbAudioDevice.restoreLevelAfterReconfigure(")
        assertThat(sequence).contains("OpenLevelCheck.UNVERIFIED")
        val device = File(
            "../../decent-player/libs/decent-usb-audio-driver/src/main/kotlin/com/decent/usbaudio/UsbAudioDevice.kt"
        ).readText().replace("\r\n", "\n")
        assertThat(device.substringAfter("fun setSampleRate(").substringBefore("\n    }\n")).contains("openLevelSettled = false")
        assertThat(device.substringAfter("fun setAltSetting(").substringBefore("\n    }\n")).contains("openLevelSettled = false")
        assertThat(device.substringAfter("fun writeHardwareVolume(").substringBefore("\n    }\n")).contains("levelGuard.onWrite(")
        assertThat(device.substringAfter("fun readHardwareVolume(").substringBefore("\n    }\n")).contains("levelGuard.onRead(")
    }
}
