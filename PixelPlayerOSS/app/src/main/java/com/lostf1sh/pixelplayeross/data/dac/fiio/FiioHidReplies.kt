package com.lostf1sh.pixelplayeross.data.dac.fiio

/** Why a report is not an answer. A timeout and a garbled report are the same thing to the caller: no answer. */
enum class NoAnswerReason {
    TIMEOUT,
    EMPTY,
    TOO_SHORT,

    /** The report does not start with the reply head (`BB 0B`): the Melody's eq_switch reply is stale bytes of the one before. */
    NOT_A_REPLY,

    /** The declared data length does not fit in the report. */
    BAD_LENGTH,
    BAD_CRC,
    NO_STOP_BYTE,
}

/**
 * Which head a well-formed report carries (B31 P1.1): the reply to a GET (`BB 0B`), or the acknowledgement of a SET, which the KA15
 * capture in DevicePEQ (`tests/captures/fiio_fiio_ka15.json`, 0BSD) shows with the SET head (`AA 0A`): the same layout, the command
 * and the data echoed, the DAC's counter in byte 3, a CRC-8/MAXIM over the head through the data.
 */
enum class FiioFrameKind { REPLY, ACK }

/** What reading one input report gave. */
sealed interface FiioReplyParse {
    /**
     * A well-formed reply: `[reportId] BB 0B 00 SEQ CMD LEN DATA… CRC EE`; or, read as an acknowledgement, the same with `AA 0A`.
     *
     * @property seq the DAC's own reply counter (not an echo of anything we sent).
     */
    class Reply(val seq: Int, val command: Int, val data: ByteArray, val kind: FiioFrameKind = FiioFrameKind.REPLY) : FiioReplyParse {
        override fun toString() = "${kind.name.lowercase().replaceFirstChar { it.uppercase() }}(seq=$seq, cmd=0x%02X, data=[${FiioHidFrames.hex(data)}])".format(command)
    }

    data class NoAnswer(val reason: NoAnswerReason) : FiioReplyParse
}

/**
 * B30-P1: reads a reply report the way the DAC writes it. Head, length, CRC and stop byte are all checked, so a report that
 * is the start of a short frame over the previous reply's leftovers (what the Melody sends for eq_switch,
 * `07 00 EE 00 03 16 01 F0 E1 EE…`) is "no answer" and never a value.
 *
 * The recorded replies carry the report id in front (`07 BB 0B …`); a stack that strips it is read the same way.
 */
object FiioHidReplies {
    private const val HEAD = 0xBB
    private const val START = 0x0B
    private const val SET_HEAD = 0xAA
    private const val SET_START = 0x0A
    private const val STOP = 0xEE
    private const val HEADER = 6 // BB 0B 00 SEQ CMD LEN

    /** A reply to a GET: only the reply head counts. An acknowledgement that arrives late is "not a reply" and never a value. */
    fun parse(report: ByteArray?, reportId: Int?): FiioReplyParse = parse(report, reportId, acceptSetHead = false)

    /**
     * B31 P1.1: what the DAC sends after a SET. Reads the reply head as well as the SET head, because the Melody's own acknowledgement
     * has never been recorded (the app drained it unread until B31) and only the KA15's (head `AA 0A`) is known.
     */
    fun parseAck(report: ByteArray?, reportId: Int?): FiioReplyParse = parse(report, reportId, acceptSetHead = true)

    private fun parse(report: ByteArray?, reportId: Int?, acceptSetHead: Boolean): FiioReplyParse {
        if (report == null) return FiioReplyParse.NoAnswer(NoAnswerReason.TIMEOUT)
        if (report.isEmpty()) return FiioReplyParse.NoAnswer(NoAnswerReason.EMPTY)
        fun isHead(first: Int) = first == HEAD || (acceptSetHead && first == SET_HEAD)
        val offset = if (reportId != null && report.size > 1 && report[0].u() == reportId && isHead(report[1].u())) 1 else 0
        if (report.size < offset + HEADER + 2) return FiioReplyParse.NoAnswer(NoAnswerReason.TOO_SHORT)
        val isReply = report[offset].u() == HEAD && report[offset + 1].u() == START
        val isAck = acceptSetHead && report[offset].u() == SET_HEAD && report[offset + 1].u() == SET_START
        if (!isReply && !isAck) {
            return FiioReplyParse.NoAnswer(NoAnswerReason.NOT_A_REPLY)
        }
        val length = report[offset + 5].u()
        val crcIndex = offset + HEADER + length
        if (crcIndex + 1 >= report.size) return FiioReplyParse.NoAnswer(NoAnswerReason.BAD_LENGTH)
        if (report[crcIndex + 1].u() != STOP) return FiioReplyParse.NoAnswer(NoAnswerReason.NO_STOP_BYTE)
        if (FiioCrc8.maxim(report, offset, crcIndex) != report[crcIndex].u()) {
            return FiioReplyParse.NoAnswer(NoAnswerReason.BAD_CRC)
        }
        return FiioReplyParse.Reply(
            seq = report[offset + 3].u(),
            command = report[offset + 4].u(),
            data = report.copyOfRange(offset + HEADER, crcIndex),
            kind = if (isAck) FiioFrameKind.ACK else FiioFrameKind.REPLY,
        )
    }

    private fun Byte.u() = toInt() and 0xFF
}
