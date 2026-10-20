package com.lostf1sh.pixelplayeross.data.dac.fiio

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * B30-P1: the protocol layer against what DACs actually sent.
 *
 * Two sets of real replies:
 * - [owner]: the SNOWSKY Melody under test (`trial-exo-2026-10-06/diagnostics/dac-hid.txt`), the DAC's own replies, not personal data;
 * - [other]: another Melody unit's replies, recorded in DevicePEQ's `tests/captures/fiio_snowsky_melody.json` (0BSD).
 */
class FiioProtocolReadTest {
    private val melody = FiioHidProfiles.all.single { it.id == "snowsky-melody" }

    // The six replies of the first probe, as received (report id 07 in front, zero padded to 64).
    private val owner = mapOf(
        "volume" to "07 BB 0B 00 00 02 01 2A 1B EE",
        "volume_limit" to "07 BB 0B 00 01 02 01 2A 94 EE",
        "eq_preset" to "07 BB 0B 00 02 16 01 F0 E1 EE",
        "eq_switch" to "07 00 EE 00 03 16 01 F0 E1 EE",
        "eq_count" to "07 BB 0B 00 04 18 01 0A 16 EE",
        "eq_global_gain" to "07 BB 0B 00 05 17 02 00 14 E8 EE",
    )

    // A later round of the same file: the same shapes with a stray stop byte after the frame, and the volume at 30.
    private val ownerLater = listOf(
        "07 BB 0B 00 06 02 01 1E CD EE EE",
        "07 BB 0B 00 07 02 01 1E 42 EE EE",
        "07 BB 0B 00 08 16 01 F0 FA EE EE",
        "07 BB 0B 00 0A 18 01 0A 03 EE EE",
        "07 BB 0B 00 0B 17 02 00 14 4A EE",
        "07 BB 0B 00 0C 02 01 1E D6 EE EE",
        "07 BB 0B 00 0E 16 01 F0 F3 EE EE",
        "07 BB 0B 00 10 18 01 0A 20 EE EE",
        "07 BB 0B 00 11 17 02 00 14 8B EE",
    )

    // DevicePEQ's capture of another Melody: preset, count, global gain, then the ten bands (reportId 7, 64 bytes).
    private val other = listOf(
        "07 BB 0B 00 2B 16 01 01 28 EE",
        "07 BB 0B 00 2D 18 01 0A F5 EE",
        "07 BB 0B 00 2E 17 02 FF D8 88 EE",
        "07 BB 0B 00 2F 15 08 00 00 1E 00 20 00 47 00 5D EE",
        "07 BB 0B 00 30 15 08 01 00 0A 00 40 00 47 00 0B EE",
        "07 BB 0B 00 31 15 08 02 00 00 00 7D 00 47 00 01 EE",
        "07 BB 0B 00 32 15 08 03 FF F1 00 FA 00 47 00 38 EE",
        "07 BB 0B 00 33 15 08 04 FF DD 01 F4 00 47 00 2E EE",
        "07 BB 0B 00 34 15 08 05 00 23 03 E8 00 47 00 BA EE",
        "07 BB 0B 00 35 15 08 06 00 0F 07 D0 00 47 00 2A EE",
        "07 BB 0B 00 36 15 08 07 00 00 0F A0 00 47 00 BD EE",
        "07 BB 0B 00 37 15 08 08 00 0A 1F 40 00 47 00 68 EE",
        "07 BB 0B 00 38 15 08 09 00 14 3E 80 00 47 00 88 EE",
    )

    private fun parse(hex: String) = FiioHidReplies.parse(report(hex), 0x07)

    // ── CRC ──────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the CRC-8 MAXIM matches the five well-formed replies the Melody sent`() {
        for ((name, hex) in owner.filterKeys { it != "eq_switch" }) {
            val bytes = report(hex)
            val dataLength = bytes[6].toInt()
            val crcIndex = 7 + dataLength
            assertThat(FiioCrc8.maxim(bytes, 1, crcIndex)).isEqualTo(bytes[crcIndex].toInt() and 0xFF)
            assertThat(parse(hex)).isInstanceOf(FiioReplyParse.Reply::class.java)
            assertThat(name).isNotEmpty()
        }
    }

    @Test
    fun `the CRC also matches the later replies and the thirteen replies of another Melody`() {
        for (hex in ownerLater + other) {
            val bytes = report(hex)
            val crcIndex = 7 + bytes[6].toInt()
            assertThat(FiioCrc8.maxim(bytes, 1, crcIndex)).isEqualTo(bytes[crcIndex].toInt() and 0xFF)
        }
    }

    @Test
    fun `the algorithm is MAXIM, not a sum or an xor, and it is known on a published check value`() {
        // The catalogue check value of CRC-8/MAXIM-DOW over "123456789" is 0xA1.
        assertThat(FiioCrc8.maxim("123456789".toByteArray())).isEqualTo(0xA1)
        assertThat(FiioCrc8.maxim(ByteArray(0))).isEqualTo(0)
    }

    // ── Parser ───────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the parser reads every well-formed recorded reply and returns seq command and data`() {
        val volume = parse(owner.getValue("volume")) as FiioReplyParse.Reply
        assertThat(volume.seq).isEqualTo(0)
        assertThat(volume.command).isEqualTo(0x02)
        assertThat(volume.data).isEqualTo(byteArrayOf(0x2A))

        val gain = parse(owner.getValue("eq_global_gain")) as FiioReplyParse.Reply
        assertThat(gain.seq).isEqualTo(5)
        assertThat(gain.command).isEqualTo(0x17)
        assertThat(gain.data).isEqualTo(byteArrayOf(0x00, 0x14))

        val count = parse(owner.getValue("eq_count")) as FiioReplyParse.Reply
        assertThat(count.data).isEqualTo(byteArrayOf(0x0A))

        // A stray stop byte after the frame (the later rounds) changes nothing.
        val later = parse(ownerLater[0]) as FiioReplyParse.Reply
        assertThat(later.seq).isEqualTo(6)
        assertThat(later.data).isEqualTo(byteArrayOf(0x1E))
    }

    @Test
    fun `the eq_switch reply is no answer, because it is stale bytes and not a frame`() {
        val parsed = parse(owner.getValue("eq_switch"))
        assertThat(parsed).isEqualTo(FiioReplyParse.NoAnswer(NoAnswerReason.NOT_A_REPLY))
    }

    @Test
    fun `timeouts and garbled reports all parse as no answer`() {
        assertThat(FiioHidReplies.parse(null, 0x07)).isEqualTo(FiioReplyParse.NoAnswer(NoAnswerReason.TIMEOUT))
        assertThat(FiioHidReplies.parse(ByteArray(0), 0x07)).isEqualTo(FiioReplyParse.NoAnswer(NoAnswerReason.EMPTY))
        assertThat(FiioHidReplies.parse(byteArrayOf(0x07, 0xBB.toByte(), 0x0B), 0x07))
            .isEqualTo(FiioReplyParse.NoAnswer(NoAnswerReason.TOO_SHORT))
        // A data byte flipped: the CRC no longer matches.
        assertThat(parse("07 BB 0B 00 04 18 01 0B 16 EE")).isEqualTo(FiioReplyParse.NoAnswer(NoAnswerReason.BAD_CRC))
        // A length that runs past the report.
        assertThat(FiioHidReplies.parse(byteArrayOf(0x07, 0xBB.toByte(), 0x0B, 0, 4, 0x18, 0x40, 0x0A, 0x16, 0xEE.toByte()), 0x07))
            .isEqualTo(FiioReplyParse.NoAnswer(NoAnswerReason.BAD_LENGTH))
        // The CRC is right but the stop byte is not there.
        assertThat(parse("07 BB 0B 00 04 18 01 0A 16 EF")).isEqualTo(FiioReplyParse.NoAnswer(NoAnswerReason.NO_STOP_BYTE))
    }

    @Test
    fun `a stack that strips the report id is read the same way`() {
        val withoutId = report("BB 0B 00 04 18 01 0A 16 EE")
        val parsed = FiioHidReplies.parse(withoutId, 0x07) as FiioReplyParse.Reply
        assertThat(parsed.command).isEqualTo(0x18)
        assertThat(parsed.data).isEqualTo(byteArrayOf(0x0A))
    }

    // ── Scales ───────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `bands decode from the replies recorded on another Melody`() {
        fun band(i: Int): DacEqBand {
            val reply = parse(other[3 + i]) as FiioReplyParse.Reply
            return (FiioEqReader.decodeBand(i, reply.data) as EqRead.Read).value
        }
        val first = band(0)
        assertThat(first.type).isEqualTo(FiioFilterType.PEAK)
        assertThat(first.frequencyHz).isEqualTo(32)
        assertThat(first.gainDb).isEqualTo(3.0)
        assertThat(first.q).isEqualTo(0.71)

        val fourth = band(3) // FF F1 = -15 tenths
        assertThat(fourth.frequencyHz).isEqualTo(250)
        assertThat(fourth.gainTenthsDb).isEqualTo(-15)
        val fifth = band(4)
        assertThat(fifth.frequencyHz).isEqualTo(500)
        assertThat(fifth.gainDb).isEqualTo(-3.5)
        assertThat(band(9).frequencyHz).isEqualTo(16_000)
        assertThat((0..9).map { band(it).frequencyHz })
            .containsExactly(32, 64, 125, 250, 500, 1000, 2000, 4000, 8000, 16000).inOrder()
    }

    @Test
    fun `the global gain is signed tenths of a dB, two bytes big endian`() {
        val negative = parse(other[2]) as FiioReplyParse.Reply // FF D8
        assertThat(FiioEqReader.signed16(negative.data[0], negative.data[1])).isEqualTo(-40)
        val ours = parse(owner.getValue("eq_global_gain")) as FiioReplyParse.Reply // 00 14
        assertThat(FiioEqReader.signed16(ours.data[0], ours.data[1])).isEqualTo(20)
    }

    @Test
    fun `a band reply for another index or too short is a failed read, never a value`() {
        val data = (parse(other[3]) as FiioReplyParse.Reply).data
        assertThat(FiioEqReader.decodeBand(0, data)).isInstanceOf(EqRead.Read::class.java)
        assertThat(FiioEqReader.decodeBand(1, data)).isInstanceOf(EqRead.Failed::class.java)
        assertThat(FiioEqReader.decodeBand(0, data.copyOf(5))).isInstanceOf(EqRead.Failed::class.java)
    }

    // ── The full read ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the Melody as the owner has it reads as closed, with the switch unknown`() {
        val dac = FakeFiioDac(melody, activePreset = 0xF0)
        val session = FiioHidSession(dac, melody)
        val state = FiioEqReader.read(session)

        assertThat(state.activePreset).isEqualTo(EqRead.Read(0xF0))
        assertThat(state.bandCount).isEqualTo(EqRead.Read(10))
        assertThat(state.preGainTenthsDb).isEqualTo(EqRead.Read(20))
        assertThat(state.eqSwitch).isInstanceOf(EqRead.Unknown::class.java)
        assertThat(state.bands).hasSize(10)
        assertThat(state.isComplete).isTrue()
        assertThat(state.verdict).isEqualTo(DacEqVerdict.OFF)
    }

    @Test
    fun `the stale report after eq_switch does not spoil the commands that follow`() {
        val dac = FakeFiioDac(melody, activePreset = 0xA0)
        dac.setSlot(0xA0, 0, (0 until 10).map { DacEqBand.of(it, FiioFilterType.PEAK, FakeFiioDac.FREQUENCIES[it], 0.0, 1.0) })
        val state = FiioEqReader.read(FiioHidSession(dac, melody))
        assertThat(state.eqSwitch).isInstanceOf(EqRead.Unknown::class.java)
        assertThat(state.bands.all { it is EqRead.Read }).isTrue()
        assertThat(state.readBands.map { it.frequencyHz }).containsExactly(*FakeFiioDac.FREQUENCIES.toTypedArray()).inOrder()
    }

    @Test
    fun `a DAC that answers the switch gives a value`() {
        val dac = FakeFiioDac(melody, activePreset = 0x01, answersSwitch = true)
        val state = FiioEqReader.read(FiioHidSession(dac, melody))
        assertThat(state.eqSwitch).isEqualTo(EqRead.Read(true))
    }

    @Test
    fun `every report the reader sends is a GET`() {
        val dac = FakeFiioDac(melody, activePreset = 0xA0)
        FiioEqReader.read(FiioHidSession(dac, melody))
        assertThat(dac.written).isNotEmpty()
        assertThat(dac.written.all { (it[1].toInt() and 0xFF) == 0xBB }).isTrue()
        assertThat(dac.sets).isEmpty()
    }

    @Test
    fun `a DAC that says nothing leaves every value unknown and the state incomplete`() {
        val dac = FakeFiioDac(melody)
        dac.dropReplies = true
        val state = FiioEqReader.read(FiioHidSession(dac, melody))
        assertThat(state.activePreset).isInstanceOf(EqRead.Unknown::class.java)
        assertThat(state.preGainTenthsDb).isInstanceOf(EqRead.Unknown::class.java)
        assertThat(state.bands.all { it is EqRead.Unknown }).isTrue()
        assertThat(state.isComplete).isFalse()
        assertThat(state.verdict).isEqualTo(DacEqVerdict.UNKNOWN)
    }

    @Test
    fun `a transfer that fails is a failed read`() {
        val dac = FakeFiioDac(melody)
        dac.failWrites = true
        val state = FiioEqReader.read(FiioHidSession(dac, melody))
        assertThat(state.activePreset).isInstanceOf(EqRead.Failed::class.java)
        assertThat(state.verdict).isEqualTo(DacEqVerdict.UNKNOWN)
    }

    @Test
    fun `the recorded replies in order read as the owner's DAC with the switch not answered`() {
        // The eq_switch junk sits between the count/preset/gain replies and the bands in the reader's order, so replay what a
        // real run would see: count, preset, gain, the junk, then silence (the bands were never asked in the trial).
        val wire = ReplayWire(
            listOf(
                listOf(report(owner.getValue("eq_count"))), listOf(report(owner.getValue("eq_preset"))),
                listOf(report(owner.getValue("eq_global_gain"))), listOf(report(owner.getValue("eq_switch"))),
            ),
        )
        val state = FiioEqReader.read(FiioHidSession(wire, melody))
        assertThat(state.bandCount).isEqualTo(EqRead.Read(10))
        assertThat(state.activePreset).isEqualTo(EqRead.Read(0xF0))
        assertThat(state.preGainTenthsDb).isEqualTo(EqRead.Read(20))
        assertThat(state.eqSwitch).isInstanceOf(EqRead.Unknown::class.java)
        assertThat(state.verdict).isEqualTo(DacEqVerdict.OFF)
    }

    // ── Verdict table (B30-P1.4) ─────────────────────────────────────────────────────────────────────────

    private fun flatBands(): List<EqRead<DacEqBand>> = (0 until 10).map { EqRead.Read(DacEqBand.of(it, FiioFilterType.PEAK, FakeFiioDac.FREQUENCIES[it], 0.0, 1.0)) }

    private fun state(
        preset: EqRead<Int> = EqRead.Read(0xA2),
        gain: EqRead<Int> = EqRead.Read(0),
        switch: EqRead<Boolean> = EqRead.Unknown("no answer"),
        bands: List<EqRead<DacEqBand>> = flatBands(),
    ) = DacEqState(DacEqCapability.of(melody), preset, EqRead.Read(10), gain, switch, bands)

    @Test
    fun `verdict - the close preset is off, a flat user slot is flat, anything shaped is on`() {
        assertThat(state(preset = EqRead.Read(0xF0)).verdict).isEqualTo(DacEqVerdict.OFF)
        assertThat(state().verdict).isEqualTo(DacEqVerdict.FLAT)
        val shaped = flatBands().toMutableList()
        shaped[3] = EqRead.Read(DacEqBand.of(3, FiioFilterType.PEAK, 250, 2.5, 1.0))
        assertThat(state(bands = shaped).verdict).isEqualTo(DacEqVerdict.ON)
        assertThat(state(gain = EqRead.Read(-30)).verdict).isEqualTo(DacEqVerdict.ON)
        assertThat(state(preset = EqRead.Read(0x01), bands = shaped).verdict).isEqualTo(DacEqVerdict.ON)
    }

    @Test
    fun `verdict - what cannot be read is never flat and never off`() {
        assertThat(state(preset = EqRead.Unknown("none")).verdict).isEqualTo(DacEqVerdict.UNKNOWN)
        assertThat(state(preset = EqRead.Failed("garbled")).verdict).isEqualTo(DacEqVerdict.UNKNOWN)
        val oneMissing = flatBands().toMutableList().also { it[4] = EqRead.Unknown("none") }
        assertThat(state(bands = oneMissing).verdict).isEqualTo(DacEqVerdict.UNKNOWN)
        assertThat(state(gain = EqRead.Unknown("none")).verdict).isEqualTo(DacEqVerdict.UNKNOWN)
        // …but one shaped band already proves the EQ is on, whatever else is missing.
        val shapedAndMissing = oneMissing.toMutableList().also { it[0] = EqRead.Read(DacEqBand.of(0, FiioFilterType.LOW_SHELF, 32, 3.0, 0.7)) }
        assertThat(state(bands = shapedAndMissing).verdict).isEqualTo(DacEqVerdict.ON)
    }

    @Test
    fun `verdict - a pass filter at 0 dB is not flat, and a switch the DAC answers is trusted`() {
        val lowPass = flatBands().toMutableList().also { it[9] = EqRead.Read(DacEqBand.of(9, FiioFilterType.LOW_PASS, 16000, 0.0, 0.7)) }
        assertThat(state(bands = lowPass).verdict).isEqualTo(DacEqVerdict.ON)
        assertThat(state(preset = EqRead.Read(0xA2), switch = EqRead.Read(false)).verdict).isEqualTo(DacEqVerdict.OFF)
        assertThat(state(preset = EqRead.Read(0xF0), switch = EqRead.Read(true)).verdict).isEqualTo(DacEqVerdict.UNKNOWN)
        assertThat(state(preset = EqRead.Unknown("none"), switch = EqRead.Read(true)).verdict).isEqualTo(DacEqVerdict.ON)
    }

    @Test
    fun `capabilities come from the table and are kept apart from what was read`() {
        val capability = DacEqCapability.of(melody)
        assertThat(capability.bandCount).isEqualTo(10)
        assertThat(capability.verified).isFalse()
        assertThat(capability.disabledPresetId).isEqualTo(0xF0)
        val state = FiioEqReader.read(FiioHidSession(FakeFiioDac(melody), melody))
        // The state carries the claim and the reading as two things: the read count is a value of its own.
        assertThat(state.capability).isEqualTo(capability)
        assertThat(state.bandCount).isInstanceOf(EqRead.Read::class.java)
    }

    @Test
    fun `no row of the table is verified on this app's hardware`() {
        assertThat(FiioHidProfiles.all.filter { it.verified }).isEmpty()
        assertThat(FiioHidProfiles.all.single { it.id == "fiio-k13-r2r" }.upstreamVerified).isTrue()
        // Only the Melody carries a slot table and a slot of ours, so only it could ever be written.
        assertThat(FiioHidProfiles.all.filter { it.canBeWritten }.map { it.id }).containsExactly("snowsky-melody")
    }
}
