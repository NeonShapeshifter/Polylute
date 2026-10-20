package com.lostf1sh.pixelplayeross.data.dac.fiio

/**
 * B30-P1: one claimed HID interface, as the protocol layer sees it. The Android implementation is [UsbFiioHidWire]
 * (interrupt endpoints through `UsbDeviceConnection`); the tests use a fake DAC.
 */
interface FiioHidWire {
    /** The OUT endpoint's report length (its max packet size). The report id is byte 0 of what is written. */
    val reportLength: Int

    /** Writes one output report; the byte count, or a negative number when the transfer failed. */
    fun write(report: ByteArray): Int

    /** One input report, or null when nothing arrives within [timeoutMs] (or the transfer failed). */
    fun read(timeoutMs: Int): ByteArray?
}

/**
 * B31 P1.1: what the DAC said after a SET. Every SET is answered by one report (the Melody's sequence counter skips a number for
 * each: 27 → 29 around a select, 43 → 45 around the next), which the app used to drain unread. A report is taken as the acknowledgement
 * when it is well formed (head, length, CRC, stop byte), names the SET's command and carries the data that was sent.
 */
sealed interface FiioSetAck {
    /** A well-formed report of this SET's command with the data that was sent. */
    data class Confirmed(val seq: Int, val kind: FiioFrameKind) : FiioSetAck

    /** A well-formed report of this SET's command that carries other data than was sent (a clamped, rounded or ignored value). */
    class Contrary(val seq: Int, val sent: ByteArray, val reported: ByteArray) : FiioSetAck

    /** Reports arrived and none could be read as this command's acknowledgement (garbled, or only replies to other commands). */
    data class Unusable(val reason: NoAnswerReason) : FiioSetAck

    /** Nothing arrived. */
    data object Missing : FiioSetAck
}

/** What a GET came to. */
sealed interface FiioGet {
    class Answer(val reply: FiioReplyParse.Reply) : FiioGet

    /** No usable answer: a timeout, or only reports that are not a reply to this command. */
    data class None(val reason: NoAnswerReason) : FiioGet

    /** The OUT transfer itself failed. */
    data class TransferFailed(val code: Int) : FiioGet
}

/**
 * B30-P1: talks to the DAC over one [FiioHidWire]: a GET, one reply, nothing else. It is the **read-only** half of the
 * transport: every report it sends passes [FiioHidFrames.requireReadOnly]. The write half is [FiioHidWrites.send], which only
 * takes a permit the write gate issues (B30-P2).
 *
 * Stale input is dropped before every GET (WolfEQ's `DrainStaleReportsAsync` does the same): the Melody's reply buffer can hold
 * the tail of an earlier reply, and the reply to the *next* command must not be confused with it. A report whose command is not
 * the one asked for is skipped as stale, never taken as the answer.
 *
 * Runs on whatever thread calls it and blocks for up to a few hundred ms per command: **never call it from the main thread or
 * from the audio path**.
 */
class FiioHidSession(
    internal val wire: FiioHidWire,
    val profile: FiioHidProfile,
    private val log: (String) -> Unit = {},
) {
    /** Every TX and RX of this session, as logged (also what the debug commands print). */
    fun logLine(line: String) = log(line)

    fun get(command: Int, data: ByteArray = ByteArray(0), name: String = "cmd%02X".format(command)): FiioGet {
        drainStale()
        val report = FiioHidFrames.requireReadOnly(
            FiioHidFrames.outputReport(profile.reportId, FiioHidFrames.getFrame(command, data), wire.reportLength)
        )
        val sent = wire.write(report)
        log("DAC_HID|TX $name ret=$sent [${FiioHidFrames.hex(report, TX_LOG_BYTES)}]")
        if (sent < 0) return FiioGet.TransferFailed(sent)
        return awaitReply(command, name)
    }

    /** Reads until the reply to [command] arrives, skipping stale or garbled reports, or until nothing more comes. */
    internal fun awaitReply(command: Int, name: String): FiioGet {
        var worst = NoAnswerReason.TIMEOUT
        repeat(MAX_READS) { attempt ->
            val raw = wire.read(RX_TIMEOUT_MS)
            if (raw == null) {
                if (attempt == 0) log("DAC_HID|RX $name none")
                return FiioGet.None(worst)
            }
            log("DAC_HID|RX $name #$attempt len=${raw.size} [${FiioHidFrames.hex(raw, trimmed(raw))}]")
            when (val parsed = FiioHidReplies.parse(raw, profile.reportId)) {
                is FiioReplyParse.Reply -> {
                    if (parsed.command == command) {
                        log("DAC_HID|RX $name ok seq=${parsed.seq} data=[${FiioHidFrames.hex(parsed.data)}]")
                        return FiioGet.Answer(parsed)
                    }
                    log("DAC_HID|RX $name skipped a reply to cmd 0x%02X (stale)".format(parsed.command))
                }
                is FiioReplyParse.NoAnswer -> {
                    log("DAC_HID|RX $name not a reply: ${parsed.reason}")
                    worst = worse(worst, parsed.reason)
                }
            }
        }
        return FiioGet.None(worst)
    }

    /**
     * Reads what the DAC sent after a SET of [command] with [expected] as its data, and says what it came to. **Every report that
     * arrives is logged** with its bytes (B31 P1.1: the first acknowledgement of the Melody was drained unread, so nobody has seen one).
     * A report of another command is skipped as stale; a garbled one is remembered and the read goes on.
     */
    fun awaitSetAck(command: Int, expected: ByteArray, name: String, timeoutMs: Int = ACK_TIMEOUT_MS): FiioSetAck {
        var worst: NoAnswerReason? = null
        var arrived = false
        repeat(MAX_READS) { attempt ->
            val raw = wire.read(timeoutMs)
            if (raw == null) {
                if (!arrived) log("DAC_HID|ACK $name none within $timeoutMs ms")
                return if (arrived) FiioSetAck.Unusable(worst ?: NoAnswerReason.NOT_A_REPLY) else FiioSetAck.Missing
            }
            arrived = true
            log("DAC_HID|ACK $name #$attempt len=${raw.size} [${FiioHidFrames.hex(raw, trimmed(raw))}]")
            when (val parsed = FiioHidReplies.parseAck(raw, profile.reportId)) {
                is FiioReplyParse.Reply -> {
                    if (parsed.command != command) {
                        log("DAC_HID|ACK $name skipped a ${parsed.kind.name.lowercase()} of cmd 0x%02X (stale)".format(parsed.command))
                        worst = worse(worst ?: NoAnswerReason.TIMEOUT, NoAnswerReason.NOT_A_REPLY)
                    } else if (parsed.data.contentEquals(expected)) {
                        log("DAC_HID|ACK $name ok seq=${parsed.seq} ${parsed.kind.name.lowercase()} data=[${FiioHidFrames.hex(parsed.data)}]")
                        return FiioSetAck.Confirmed(parsed.seq, parsed.kind)
                    } else {
                        log(
                            "DAC_HID|ACK $name CONTRARY seq=${parsed.seq} sent=[${FiioHidFrames.hex(expected)}] " +
                                "reported=[${FiioHidFrames.hex(parsed.data)}]"
                        )
                        return FiioSetAck.Contrary(parsed.seq, expected, parsed.data)
                    }
                }
                is FiioReplyParse.NoAnswer -> {
                    log("DAC_HID|ACK $name not an acknowledgement: ${parsed.reason}")
                    worst = worse(worst ?: NoAnswerReason.TIMEOUT, parsed.reason)
                }
            }
        }
        return FiioSetAck.Unusable(worst ?: NoAnswerReason.NOT_A_REPLY)
    }

    internal fun drainStale() {
        var drained = 0
        while (drained < MAX_DRAIN) {
            val raw = wire.read(DRAIN_TIMEOUT_MS) ?: break
            drained++
            // B31 P1.1: say what was thrown away. The Melody's SET acknowledgement went here unread, and so no one knew its shape.
            log("DAC_HID|drained stale #$drained len=${raw.size} [${FiioHidFrames.hex(raw, trimmed(raw))}]")
        }
        if (drained > 0) log("DAC_HID|drained $drained stale report(s)")
    }

    private fun worse(a: NoAnswerReason, b: NoAnswerReason): NoAnswerReason =
        if (severity(b) > severity(a)) b else a

    private fun severity(reason: NoAnswerReason) = when (reason) {
        NoAnswerReason.TIMEOUT, NoAnswerReason.EMPTY -> 0
        NoAnswerReason.TOO_SHORT, NoAnswerReason.NOT_A_REPLY -> 1
        NoAnswerReason.BAD_LENGTH, NoAnswerReason.NO_STOP_BYTE, NoAnswerReason.BAD_CRC -> 2
    }

    /** Reports are zero padded to 64 bytes; log up to the last non-zero byte, plus a little, so a log line stays one line. */
    private fun trimmed(report: ByteArray): Int {
        var end = report.size
        while (end > 0 && report[end - 1].toInt() == 0) end--
        return minOf(report.size, maxOf(end, MIN_RX_LOG_BYTES))
    }

    companion object {
        const val RX_TIMEOUT_MS = 300

        /** How long the acknowledgement of a SET is waited for: the DAC answers a GET in 15-30 ms; the Melody's ack came inside 190 ms. */
        const val ACK_TIMEOUT_MS = 250
        const val DRAIN_TIMEOUT_MS = 30
        const val MAX_DRAIN = 16
        const val MAX_READS = 3
        const val TX_LOG_BYTES = 24
        const val MIN_RX_LOG_BYTES = 12
    }
}
