package com.lostf1sh.pixelplayeross.data.dac.fiio

/**
 * B30-P2: the **write half** of FiiO's HID protocol, and the only place a SET frame exists.
 *
 * A write changes the DAC's persistent configuration, so nothing here can be called without a [WritePermit], which only
 * [DacEqWriteGate] issues, and a permit is a whitelist of the exact frames it covers: [send] refuses any other. The read half
 * (`FiioHidFrames`, `FiioHidSession.get`) has no SET anywhere in it, and `DacEqWriteGateTest` scans the sources to keep it so.
 *
 * Frame: `[AA 0A 00 00 CMD LEN DATA… CRC EE]`, behind the profile's report id, padded to the report length.
 * - Header and layout: WolfEQ `Services/FiioK13DeviceService.cs` (`BuildSetPacket`, `SetHead`/`SetStart`, MIT); DevicePEQ
 *   `fiioUsbHidHandler.js` (`SET_HEADER1/2`, 0BSD). Both send `00` as the check byte; see [FiioCrc8] for what this app sends.
 *
 * Commands used, each with where it comes from:
 *
 * | CMD | Data | What | Source |
 * |---|---|---|---|
 * | `16` | `[id]` | select preset / slot (also the pointer move that closes the EQ: the model's "Close EQ" id) | WolfEQ `SelectUserPresetAsync`, `CmdEqPreset`; DevicePEQ `setPresetPeq` / `enablePEQ` |
 * | `17` | `[hi lo]` | global gain (pre-gain), signed 16 bit big endian, tenths of a dB | WolfEQ `SetGlobalGainAsync`; DevicePEQ `setGlobalGain` |
 * | `15` | `[i, gain hi, gain lo, freq hi, freq lo, q hi, q lo, type]` | one band of the active preset's working set | WolfEQ `EncodeBand` / `SetBandAsync`; DevicePEQ `setPeqParams` |
 * | `19` (model's save command) | `[slot]` | persist the working set into the slot | DevicePEQ `saveToDevice` (`[slotId]`); WolfEQ `SaveToDeviceAsync` sends it with **no data** after selecting the slot |
 *
 * The two projects differ on the save's data. Selecting the slot first (as WolfEQ does) and then naming it in the save (as
 * DevicePEQ does) is consistent with both readings; [FiioSaveForm.EMPTY] is the other form, for the case the DAC proves to want it.
 * The band count (`SET 18`, DevicePEQ `setPeqCounter`) is **not** sent: the Melody's count is 10 and fixed, and every band is written.
 */
object FiioHidWrites {
    private const val SET_HEAD = 0xAA
    private const val SET_START = 0x0A
    private const val STOP = 0xEE

    const val CMD_BAND = 0x15
    const val CMD_PRESET = 0x16
    const val CMD_GAIN = 0x17

    fun setFrame(command: Int, data: ByteArray, crcMode: FiioCrcMode): ByteArray {
        val frame = ByteArray(8 + data.size)
        frame[0] = SET_HEAD.toByte()
        frame[1] = SET_START.toByte()
        frame[4] = command.toByte()
        frame[5] = data.size.toByte()
        data.copyInto(frame, 6)
        frame[6 + data.size] = (if (crcMode == FiioCrcMode.MAXIM) FiioCrc8.maxim(frame, 0, 6 + data.size) else 0).toByte()
        frame[7 + data.size] = STOP.toByte()
        return frame
    }

    /** `[i, gain hi, gain lo, freq hi, freq lo, q hi, q lo, type]`, the layout `GET 15` answers with. */
    fun encodeBand(band: DacEqBand): ByteArray = byteArrayOf(
        band.index.toByte(),
        (band.gainTenthsDb shr 8).toByte(), band.gainTenthsDb.toByte(),
        (band.frequencyHz shr 8).toByte(), band.frequencyHz.toByte(),
        (band.qHundredths shr 8).toByte(), band.qHundredths.toByte(),
        band.typeCode.toByte(),
    )

    fun encodeGain(tenths: Int): ByteArray = byteArrayOf((tenths shr 8).toByte(), tenths.toByte())

    fun encodePreset(id: Int): ByteArray = byteArrayOf(id.toByte())

    /**
     * Sends one SET the [permit] covers **now** (a pointer or save frame, or the next frame of its plan, which it uses up); throws, and
     * sends nothing, when it does not. Returns whether the **whole report** went out: a transfer that returns fewer bytes than the
     * report holds (B31 P1.1; the old test was `ret >= 0`) is a failure, not a success.
     *
     * Stale input is dropped first and logged, so the report that follows this SET is its acknowledgement and nothing older
     * ([FiioHidSession.awaitSetAck] reads it).
     */
    internal fun send(session: FiioHidSession, permit: WritePermit, command: Int, data: ByteArray, name: String): Boolean {
        check(permit.take(command, data)) {
            "SET 0x%02X [${FiioHidFrames.hex(data)}] is not covered by the ${permit.kind} permit".format(command)
        }
        val report = FiioHidFrames.outputReport(
            session.profile.reportId, setFrame(command, data, permit.crcMode), session.wire.reportLength,
        )
        session.drainStale()
        val sent = session.wire.write(report)
        val whole = sent == report.size
        session.logLine(
            "DAC_HID|TX SET $name ret=$sent of ${report.size}${if (whole || sent < 0) "" else " (SHORT WRITE)"} " +
                "[${FiioHidFrames.hex(report, FiioHidSession.TX_LOG_BYTES)}]"
        )
        return whole
    }
}

/**
 * Which form of the save command the DAC is sent: the slot named in its data, no data at all, or **none**.
 *
 * [NONE] exists for the reviewer's unplug step (B30 runbook): with no save frame the DAC's live state changes (WolfEQ's
 * `SetBandAsync` writes a band live and sends no save) and the read-back still compares equal, so replugging the DAC says whether
 * a write needs the separate save command to survive. The app's own writes never use it.
 */
enum class FiioSaveForm { WITH_SLOT, EMPTY, NONE }

/** What the SET frames carry that the DAC may or may not check, and how its save is spelled: the two things the sources disagree on. */
data class FiioWriteOptions(
    val crcMode: FiioCrcMode = FiioCrcMode.MAXIM,
    val saveForm: FiioSaveForm = FiioSaveForm.WITH_SLOT,
)
