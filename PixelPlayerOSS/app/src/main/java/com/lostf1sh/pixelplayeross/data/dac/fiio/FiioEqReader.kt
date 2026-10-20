package com.lostf1sh.pixelplayeross.data.dac.fiio

/**
 * B30-P1: the read half of the EQ protocol, with the scales spelled out.
 *
 * | Value | GET | Reply data | Scale | Source | Seen on our Melody |
 * |---|---|---|---|---|---|
 * | current preset / slot | `16` | `[id]` | the slot id (0xA0.. user, 0xF0 close) | WolfEQ `ReadCurrentEqAsync`, DevicePEQ `getPresetPeq`/`handleEqPreset` | `F0` |
 * | band count | `18` | `[n]` | whole bands | WolfEQ `CmdEqCount`, DevicePEQ `getPeqCounter` | `0A` = 10 |
 * | global gain | `17` | `[hi lo]` | signed 16 bit big endian, **tenths of a dB** | WolfEQ `ParseSignedTenths`, DevicePEQ `handleGain` | `00 14` = +2.0 dB (the scale is the sources', not confirmed here) |
 * | EQ on/off | `1A` | `[0/1]` | boolean | WolfEQ `CmdEqSwitch` (K13) | no well-formed reply |
 * | band i | `15 [i]` | `[i, gain hi, gain lo, freq hi, freq lo, q hi, q lo, type]` | gain tenths of a dB (signed), frequency Hz (u16), Q **hundredths** (u16), type code | WolfEQ `ReadCurrentEqAsync` (`response[6..13]`), DevicePEQ `handlePeqParams` | not asked before B30 |
 *
 * What our own replies confirm: the frame layout and the CRC of `16`, `18`, `17`; the count (10). What they do not: any band
 * (the probe never asked), the sign and tenths scale of the gain, and the meaning of `F0`. Another Melody's recorded replies
 * (DevicePEQ `tests/captures/fiio_snowsky_melody.json`, 0BSD) show the band layout with sensible values (32, 64, 125 … 16000 Hz,
 * Q 0x47 = 0.71, gains of ±0.5…3.5 dB, a preset id of 1 and a gain of `FF D8` = −4.0 dB), which is what the decoder is tested
 * against until the reviewer's device run says the same of this unit.
 *
 * `GET 15` answers for the **active** preset's working set (the capture was made with preset 1 active, and read bands that look
 * like a curve), so reading another slot means selecting it first (see the writer's discoverSlot).
 *
 * Read-only: every report goes through [FiioHidSession.get].
 */
object FiioEqReader {
    private const val CMD_BAND = 0x15
    private const val CMD_PRESET = 0x16
    private const val CMD_GAIN = 0x17
    private const val CMD_COUNT = 0x18
    private const val CMD_SWITCH = 0x1A

    /** A global gain beyond this (tenths) is not a gain, it is garbage in the field. */
    private const val MAX_PLAUSIBLE_GAIN_TENTHS = 300

    fun read(session: FiioHidSession): DacEqState {
        val profile = session.profile
        val count = readByte(session, CMD_COUNT, "eq_count")
        val preset = readByte(session, CMD_PRESET, "eq_preset")
        val gain = readGain(session)
        val switch = when (val raw = readByte(session, CMD_SWITCH, "eq_switch")) {
            is EqRead.Read -> EqRead.Read(raw.value != 0)
            is EqRead.Unknown -> raw
            is EqRead.Failed -> raw
        }
        val bandsToRead = when {
            count is EqRead.Read && count.value in 1..profile.bandCount -> count.value
            else -> profile.bandCount
        }
        val bands = (0 until bandsToRead).map { readBand(session, it) }
        return DacEqState(
            capability = DacEqCapability.of(profile),
            activePreset = preset,
            bandCount = count,
            preGainTenthsDb = gain,
            eqSwitch = switch,
            bands = bands,
        )
    }

    /** One band of the active preset (`GET 15 [index]`). */
    fun readBand(session: FiioHidSession, index: Int): EqRead<DacEqBand> =
        fromGet(session.get(CMD_BAND, byteArrayOf(index.toByte()), "eq_band$index"), "band $index") { data ->
            decodeBand(index, data)
        }

    fun readPreset(session: FiioHidSession): EqRead<Int> = readByte(session, CMD_PRESET, "eq_preset")

    fun readGain(session: FiioHidSession): EqRead<Int> =
        fromGet(session.get(CMD_GAIN, name = "eq_global_gain"), "global gain") { data ->
            if (data.size < 2) {
                EqRead.Failed("global gain reply has ${data.size} data byte(s), expected 2")
            } else {
                val tenths = signed16(data[0], data[1])
                if (kotlin.math.abs(tenths) > MAX_PLAUSIBLE_GAIN_TENTHS) {
                    EqRead.Failed("global gain $tenths tenths of a dB is not plausible")
                } else {
                    EqRead.Read(tenths)
                }
            }
        }

    private fun readByte(session: FiioHidSession, command: Int, name: String): EqRead<Int> =
        fromGet(session.get(command, name = name), name) { data ->
            if (data.isEmpty()) EqRead.Failed("$name reply has no data") else EqRead.Read(data[0].toInt() and 0xFF)
        }

    /** The band reply: `[i, gain hi, gain lo, freq hi, freq lo, q hi, q lo, type]`. */
    internal fun decodeBand(index: Int, data: ByteArray): EqRead<DacEqBand> {
        if (data.size < BAND_DATA_BYTES) return EqRead.Failed("band $index reply has ${data.size} data byte(s), expected $BAND_DATA_BYTES")
        val echoed = data[0].toInt() and 0xFF
        if (echoed != index) return EqRead.Failed("asked for band $index and got band $echoed")
        return EqRead.Read(
            DacEqBand(
                index = index,
                typeCode = data[7].toInt() and 0xFF,
                frequencyHz = unsigned16(data[3], data[4]),
                gainTenthsDb = signed16(data[1], data[2]),
                qHundredths = unsigned16(data[5], data[6]),
            )
        )
    }

    const val BAND_DATA_BYTES = 8

    internal fun signed16(high: Byte, low: Byte): Int = ((high.toInt() shl 8) or (low.toInt() and 0xFF)).toShort().toInt()

    internal fun unsigned16(high: Byte, low: Byte): Int = ((high.toInt() and 0xFF) shl 8) or (low.toInt() and 0xFF)

    private inline fun <T> fromGet(get: FiioGet, what: String, decode: (ByteArray) -> EqRead<T>): EqRead<T> = when (get) {
        is FiioGet.Answer -> decode(get.reply.data)
        is FiioGet.TransferFailed -> EqRead.Failed("$what: the transfer to the DAC failed (${get.code})")
        is FiioGet.None -> when (get.reason) {
            NoAnswerReason.BAD_CRC, NoAnswerReason.BAD_LENGTH, NoAnswerReason.NO_STOP_BYTE ->
                EqRead.Failed("$what: garbled reply (${get.reason})")
            else -> EqRead.Unknown("$what: no answer (${get.reason})")
        }
    }
}
