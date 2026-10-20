package com.lostf1sh.pixelplayeross.data.dac.fiio

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * B31 P1.1: **what the DAC says after a SET.** The first Melody run drained that report unread (`drained 1 stale report(s)`), so no one has
 * seen the Melody's. What is known, and where it comes from:
 *
 * - **Each SET is answered by one report.** The Melody's own log (`B30-fix1-reviewer-gates/runbook-step3b-discover.txt`) counts replies
 *   with a sequence number: the band read before the select is `seq=27` and the first read after it is `seq=29`; around the select back
 *   it is `43` and `45`. The number that is missing is the acknowledgement, which took one.
 * - **Its shape**, from the KA15 capture in DevicePEQ (`tests/captures/fiio_fiio_ka15.json`, 0BSD; the `exchanges` whose `send` is a SET
 *   and whose `responses` are what followed): the SET head `AA 0A`, `00`, the DAC's counter, the command, the length, the data of the SET
 *   echoed, a CRC-8/MAXIM over everything before it, `EE`. The save's is a bare `00 EE 00 SEQ`. WolfEQ's `SendSetOnlyAsync`
 *   (`Services/FiioK13DeviceService.cs`) drains stale input before a SET and reads nothing after it; DevicePEQ's `fiioUsbHidHandler.js`
 *   `oninputreport` dispatches on `BB 0B` only and handles a `PEQ_SAVE_TO_DEVICE` reply (`savedEQ`) by its first data byte. Neither says
 *   what a SET's reply is, so the frames below are the evidence.
 */
class FiioSetAckTest {
    private val melody = FiioHidProfiles.all.single { it.id == "snowsky-melody" }

    // KA15 acknowledgements, as recorded (report id 07 first).
    private val ackGain = report("07 AA 0A 00 5A 17 02 00 00 C4 EE")
    private val ackCount = report("07 AA 0A 00 5B 18 01 0A A5 EE")
    private val ackBand0 = report("07 AA 0A 00 5C 15 08 00 00 2A 00 14 00 3C 00 29 EE")
    private val ackBand1 = report("07 AA 0A 00 5D 15 08 01 00 0C 00 38 01 22 00 42 EE")
    private val ackSave = report("07 00 EE 00 66")

    @Test
    fun `the recorded acknowledgements are well formed, check byte included`() {
        for ((frame, command) in listOf(ackGain to 0x17, ackCount to 0x18, ackBand0 to 0x15, ackBand1 to 0x15)) {
            val parsed = FiioHidReplies.parseAck(frame, 0x07) as FiioReplyParse.Reply
            assertThat(parsed.kind).isEqualTo(FiioFrameKind.ACK)
            assertThat(parsed.command).isEqualTo(command)
        }
        val band0 = FiioHidReplies.parseAck(ackBand0, 0x07) as FiioReplyParse.Reply
        assertThat(band0.seq).isEqualTo(0x5C)
        // The data echoes the SET: index 0, +4.2 dB (0x002A), 20 Hz, Q 0.60, PEAK.
        val decoded = (FiioEqReader.decodeBand(0, band0.data) as EqRead.Read).value
        assertThat(decoded).isEqualTo(DacEqBand(0, 0, 20, 42, 60))
        // The CRC is CRC-8/MAXIM over the head through the data, as in a GET reply: recomputed here, not copied.
        assertThat(FiioCrc8.maxim(byteArrayOf(0xAA.toByte(), 0x0A, 0x00, 0x5B, 0x18, 0x01, 0x0A))).isEqualTo(0xA5)
        assertThat(FiioCrc8.maxim(byteArrayOf(0xAA.toByte(), 0x0A, 0x00, 0x5A, 0x17, 0x02, 0x00, 0x00))).isEqualTo(0xC4)
    }

    @Test
    fun `an acknowledgement is not a reply to a GET, and a reply is not an acknowledgement of a different head`() {
        // The GET path parses replies only: a late acknowledgement can never be taken for a value.
        assertThat(FiioHidReplies.parse(ackBand0, 0x07)).isEqualTo(FiioReplyParse.NoAnswer(NoAnswerReason.NOT_A_REPLY))
        // The acknowledgement reader accepts either head: the Melody's own has not been seen.
        val getShaped = report("07 BB 0B 00 1D 16 01 A2 24 EE")
        val parsed = FiioHidReplies.parseAck(getShaped, 0x07) as FiioReplyParse.Reply
        assertThat(parsed.kind).isEqualTo(FiioFrameKind.REPLY)
        assertThat(parsed.command).isEqualTo(0x16)
    }

    @Test
    fun `the save's acknowledgement is a bare counter and is nothing to read`() {
        assertThat(FiioHidReplies.parseAck(ackSave, 0x07)).isInstanceOf(FiioReplyParse.NoAnswer::class.java)
    }

    @Test
    fun `the Melody's sequence counter skips one number for each SET, which is the report that was drained`() {
        // The recorded RX lines of the Melody run around the select of USER 3 and around the select back.
        val beforeSelect = report("07 BB 0B 00 1B 15 08 09 00 00 00 00 00 00 00 B0 EE")
        val afterSelect = report("07 BB 0B 00 1D 16 01 A2 24 EE")
        val beforeBack = report("07 BB 0B 00 2B 15 08 09 00 14 31 9C 00 64 00 DA EE")
        val afterBack = report("07 BB 0B 00 2D 16 01 F0 0B EE")
        fun seq(r: ByteArray) = (FiioHidReplies.parse(r, 0x07) as FiioReplyParse.Reply).seq
        assertThat(seq(afterSelect) - seq(beforeSelect)).isEqualTo(2) // 27 → 29: one report in between
        assertThat(seq(afterBack) - seq(beforeBack)).isEqualTo(2) // 43 → 45
    }

    // ── the session ─────────────────────────────────────────────────────────────────────────────────────

    private fun session(vararg reports: ByteArray, log: MutableList<String> = mutableListOf()): Pair<FiioHidSession, MutableList<String>> =
        FiioHidSession(ReplayWire(listOf(reports.toList())), melody, { log += it }) to log

    private fun afterSet(wire: ReplayWire, session: FiioHidSession) {
        // Write anything to start the scripted group, as a SET would.
        wire.write(ByteArray(64))
    }

    @Test
    fun `an echo is confirmed, and the report is logged with its bytes`() {
        val log = mutableListOf<String>()
        val wire = ReplayWire(listOf(listOf(ackBand0)))
        val session = FiioHidSession(wire, melody) { log += it }
        afterSet(wire, session)
        val band = byteArrayOf(0, 0, 0x2A, 0, 0x14, 0, 0x3C, 0)
        val ack = session.awaitSetAck(0x15, band, "band 1")
        assertThat(ack).isEqualTo(FiioSetAck.Confirmed(0x5C, FiioFrameKind.ACK))
        assertThat(log.any { it.contains("ACK band 1 #0") && it.contains("AA 0A 00 5C 15 08") }).isTrue()
        assertThat(log.any { it.contains("ACK band 1 ok seq=92") }).isTrue()
    }

    @Test
    fun `a report that names the command with other data is contrary`() {
        val wire = ReplayWire(listOf(listOf(ackGain)))
        val session = FiioHidSession(wire, melody)
        afterSet(wire, session)
        // The KA15 answered a write of +0.2 dB with 00 00: a value the DAC did not take.
        val ack = session.awaitSetAck(0x17, byteArrayOf(0x00, 0x02), "pre-gain")
        assertThat(ack).isInstanceOf(FiioSetAck.Contrary::class.java)
        ack as FiioSetAck.Contrary
        assertThat(ack.reported.toList()).containsExactly(0.toByte(), 0.toByte()).inOrder()
        assertThat(ack.sent.toList()).containsExactly(0.toByte(), 2.toByte()).inOrder()
    }

    @Test
    fun `a bare counter, a reply to another command and silence are not confirmations`() {
        run {
            val wire = ReplayWire(listOf(listOf(ackSave)))
            val session = FiioHidSession(wire, melody)
            afterSet(wire, session)
            assertThat(session.awaitSetAck(0x19, byteArrayOf(0xA2.toByte()), "save")).isInstanceOf(FiioSetAck.Unusable::class.java)
        }
        run {
            val wire = ReplayWire(listOf(listOf(ackCount)))
            val session = FiioHidSession(wire, melody)
            afterSet(wire, session)
            // Only the acknowledgement of another command arrived: stale, skipped, and so nothing for this one.
            assertThat(session.awaitSetAck(0x17, byteArrayOf(0, 0), "pre-gain")).isInstanceOf(FiioSetAck.Unusable::class.java)
        }
        run {
            val wire = ReplayWire(listOf(emptyList()))
            val session = FiioHidSession(wire, melody)
            afterSet(wire, session)
            assertThat(session.awaitSetAck(0x17, byteArrayOf(0, 0), "pre-gain")).isEqualTo(FiioSetAck.Missing)
        }
    }

    @Test
    fun `a stale report of another command is skipped and the acknowledgement behind it is found`() {
        val wire = ReplayWire(listOf(listOf(ackCount, ackBand0)))
        val session = FiioHidSession(wire, melody)
        afterSet(wire, session)
        val band = byteArrayOf(0, 0, 0x2A, 0, 0x14, 0, 0x3C, 0)
        assertThat(session.awaitSetAck(0x15, band, "band 1")).isInstanceOf(FiioSetAck.Confirmed::class.java)
    }

    @Test
    fun `what the drain throws away is logged, no longer silent`() {
        val log = mutableListOf<String>()
        val wire = ReplayWire(listOf(listOf(ackBand0)))
        val session = FiioHidSession(wire, melody) { log += it }
        afterSet(wire, session)
        session.drainStale()
        assertThat(log.any { it.contains("drained stale #1") && it.contains("AA 0A 00 5C 15 08") }).isTrue()
        assertThat(log).contains("DAC_HID|drained 1 stale report(s)")
    }

    // ── the transfer length ──────────────────────────────────────────────────────────────────────────────

    private class ShortWire(private val returns: Int) : FiioHidWire {
        override val reportLength = 64
        override fun write(report: ByteArray) = returns
        override fun read(timeoutMs: Int): ByteArray? = null
    }

    private fun permit(): WritePermit {
        val request = WriteRequest(WriteKind.CLOSE_EQ, WriteInitiator.USER_ACTION)
        val state = FiioEqReader.read(FiioHidSession(FakeFiioDac(melody, activePreset = 0xA2), melody))
        val backup = DacEqBackup.of(AttachedFiio("p", "i", "k", "n", "p", melody), state, 1L)!!
        val decision = DacEqWriteGate().authorise(request, WriteContext(melody, "k", state, 0, backup, true))
        return (decision as GateDecision.Authorised).permit
    }

    @Test
    fun `a transfer that returns fewer bytes than the report is a failure, not a success`() {
        val data = FiioHidWrites.encodePreset(0xF0)
        for ((returned, expected) in listOf(64 to true, 63 to false, 10 to false, 0 to false, -1 to false)) {
            val log = mutableListOf<String>()
            val session = FiioHidSession(ShortWire(returned), melody) { log += it }
            val sent = FiioHidWrites.send(session, permit(), FiioHidWrites.CMD_PRESET, data, "select 0xF0")
            assertThat(sent).isEqualTo(expected)
            if (returned in 0..63) assertThat(log.any { it.contains("SHORT WRITE") }).isTrue()
        }
    }
}
