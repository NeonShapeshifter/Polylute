package com.lostf1sh.pixelplayeross.data.dac.fiio

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * F-01 phase 1 (owner request): a read-only probe of the FiiO/Snowsky HID protocol. The profile table
 * is ported from WolfEQ (MIT; its community entries come from Pragmatic Audio's DevicePEQ, 0BSD), the
 * code is our own. This file is F-01's read-only half: the probe and the GET frames. (B30 added the write half behind a gate:
 * `DacEqWriteGateTest`.)
 */
class FiioHidReadOnlyTest {

    @Test
    fun `profiles are found by product id, or by name when the table has none`() {
        val melody = FiioHidProfiles.match(0x2972, 0x0126, "SNOWSKY Melody")!!
        assertThat(melody.id).isEqualTo("snowsky-melody")
        assertThat(melody.reportId).isEqualTo(0x07)
        assertThat(melody.bandCount).isEqualTo(10)
        assertThat(FiioHidProfiles.match(0x2972, 0x0093, null)!!.reportId).isEqualTo(0x01)  // KA17
        assertThat(FiioHidProfiles.match(0x2972, 0x0102, null)!!.bandCount).isEqualTo(5)    // JA11
        assertThat(FiioHidProfiles.match(0x2972, 0x7777, "FiiO SNOWSKY RETRO NANO")!!.id).isEqualTo("snowsky-retro-nano")
        assertThat(FiioHidProfiles.match(0x2972, 0x7777, "Unknown FiiO")).isNull()
        assertThat(FiioHidProfiles.match(0x1234, 0x0126, "SNOWSKY Melody")).isNull()  // not FiiO's vendor id
        assertThat(FiioHidProfiles.all.map { it.id }).containsAtLeast(
            "fiio-k13-r2r", "fiio-br15-r2r", "fiio-qx13", "fiio-btr17", "fiio-btr13",
            "fiio-ka15", "fiio-ka17", "fiio-ja11", "snowsky-melody", "snowsky-retro-nano",
        )
    }

    @Test
    fun `GET frames follow the FiiO layout, behind the profile's report id, padded`() {
        val volume = FiioHidFrames.probeCommands.single { it.name == "volume" }
        val frame = FiioHidFrames.getFrame(volume.command, volume.data)
        assertThat(frame.map { it.toInt() and 0xFF })
            .containsExactly(0xBB, 0x0B, 0x00, 0x00, 0x02, 0x02, 0x01, 0x01, 0x00, 0xEE).inOrder()
        val report = FiioHidFrames.outputReport(0x07, frame, 64)
        assertThat(report).hasLength(64)
        assertThat(report[0].toInt()).isEqualTo(0x07)
        assertThat(report.copyOfRange(1, 11)).isEqualTo(frame)
        assertThat(FiioHidFrames.probeCommands.map { it.name })
            .containsAtLeast("volume", "volume_limit", "eq_preset", "eq_switch", "eq_count", "eq_global_gain")
    }

    @Test
    fun `every probe frame is a GET, and the transport guard refuses a SET`() {
        for (command in FiioHidFrames.probeCommands) {
            val report = FiioHidFrames.outputReport(0x07, FiioHidFrames.getFrame(command.command, command.data), 64)
            assertThat(FiioHidFrames.requireReadOnly(report)).isSameInstanceAs(report)
        }
        val set = byteArrayOf(0x07, 0xAA.toByte(), 0x0A, 0, 0, 0x12, 0x03, 0x01, 0x01, 0x40, 0x00, 0xEE.toByte())
        assertThrows<IllegalArgumentException> { FiioHidFrames.requireReadOnly(set) }
        assertThrows<IllegalArgumentException> { FiioHidFrames.requireReadOnly(ByteArray(0)) }
    }

    @Test
    fun `the read path still ends in the read-only guard, and the probe is read-only`() {
        // B30 replaced F-01's "no 0xAA anywhere" rule: a SET now exists, in FiioHidWrites.kt only, and only the gate's permit
        // lets one out (DacEqWriteGateTest scans the sources for both). What stays true of the read path is below.
        val probe = File("src/main/java/com/lostf1sh/pixelplayeross/data/dac/fiio/FiioHidProbe.kt").readText()
        assertThat(probe).contains("FiioHidFrames.requireReadOnly(")
        assertThat(probe).doesNotContain("FiioHidWrites")
        val session = File("src/main/java/com/lostf1sh/pixelplayeross/data/dac/fiio/FiioHidSession.kt").readText()
        assertThat(session).contains("FiioHidFrames.requireReadOnly(")
        val frames = File("src/main/java/com/lostf1sh/pixelplayeross/data/dac/fiio/FiioHidFrames.kt").readText()
        assertThat(frames).doesNotContain("0xAA")
        assertThat(frames).doesNotContain("fun setFrame")
    }
}
