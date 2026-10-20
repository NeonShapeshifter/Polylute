package com.lostf1sh.pixelplayeross.data.dac.fiio

/**
 * F-01 phase 1: FiiO HID frames, **read-only**. Layout as documented by WolfEQ
 * (`Services/FiioK13DeviceService.cs`, MIT): `[0xBB, 0x0B, 0x00, 0x00, CMD, LEN, …DATA, 0x00, 0xEE]`,
 * sent as an output report behind the profile's report id and padded to the report length.
 *
 * Only the GET header exists here. There is deliberately no builder for the write form in this file: a write
 * changes the DAC's persistent configuration. B30 added the write half in [FiioHidWrites], which nothing can call
 * without the permit [DacEqWriteGate] issues; every report sent through [FiioHidSession.get] and the probe still passes
 * [requireReadOnly], the read path's last gate.
 */
object FiioHidFrames {
    private const val GET_HEAD = 0xBB
    private const val GET_START = 0x0B
    private const val STOP = 0xEE

    data class ProbeCommand(val name: String, val command: Int, val data: ByteArray)

    /** The reads the probe issues. Device settings are (category, command, target) → CMD, [command, target]. */
    val probeCommands: List<ProbeCommand> = listOf(
        ProbeCommand("volume", 0x02, byteArrayOf(0x01, 0x01)),
        ProbeCommand("volume_limit", 0x02, byteArrayOf(0x03, 0x01)),
        ProbeCommand("eq_preset", 0x16, ByteArray(0)),      // current preset / slot
        ProbeCommand("eq_switch", 0x1A, ByteArray(0)),      // EQ on/off
        ProbeCommand("eq_count", 0x18, ByteArray(0)),       // band count
        ProbeCommand("eq_global_gain", 0x17, ByteArray(0)), // pre-gain
    )

    fun getFrame(command: Int, data: ByteArray): ByteArray {
        val frame = ByteArray(8 + data.size)
        frame[0] = GET_HEAD.toByte()
        frame[1] = GET_START.toByte()
        frame[4] = command.toByte()
        frame[5] = data.size.toByte()
        data.copyInto(frame, 6)
        frame[frame.size - 2] = 0x00
        frame[frame.size - 1] = STOP.toByte()
        return frame
    }

    fun outputReport(reportId: Int, frame: ByteArray, reportLength: Int): ByteArray {
        val report = ByteArray(maxOf(reportLength, frame.size + 1))
        report[0] = reportId.toByte()
        frame.copyInto(report, 1)
        return report
    }

    /** Throws unless [report] carries a GET frame; every transfer to the DAC passes through here. */
    fun requireReadOnly(report: ByteArray): ByteArray {
        require(report.size >= 3 && (report[1].toInt() and 0xFF) == GET_HEAD && (report[2].toInt() and 0xFF) == GET_START) {
            "Only FiiO GET frames may be sent in phase 1"
        }
        return report
    }

    fun hex(bytes: ByteArray, length: Int = bytes.size): String =
        (0 until length.coerceAtMost(bytes.size)).joinToString(" ") { "%02X".format(bytes[it].toInt() and 0xFF) }
}
