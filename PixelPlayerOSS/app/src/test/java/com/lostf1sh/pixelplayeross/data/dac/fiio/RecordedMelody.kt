package com.lostf1sh.pixelplayeross.data.dac.fiio

/**
 * What the owner's Melody answered on 2026-10-19 (`device-test-2026-10-19-melody.md`, raw log `B30-fix1-reviewer-gates/runbook-step3b-discover.txt`),
 * as the bytes it sent, decoded by the app's own decoder.
 */
internal object RecordedMelody {
    /** `RX eq_band0..9` with USER 3 (0xA2) selected: the data of each reply (index, gain hi lo, freq hi lo, q hi lo, type). */
    private val user3BandReplies = listOf(
        "00 FF DC 00 14 00 64 00",
        "01 00 14 00 3C 00 64 00",
        "02 FF FA 01 04 00 4B 00",
        "03 FF EC 01 1E 00 64 00",
        "04 00 18 03 8C 00 64 00",
        "05 FF BE 04 D8 00 96 00",
        "06 FF DE 08 F2 00 64 00",
        "07 00 36 0B EA 00 64 00",
        "08 FF CE 1D 7E 01 2D 00",
        "09 00 14 31 9C 00 64 00",
    )

    /** `RX eq_global_gain ok seq=32 data=[00 50]`: +8.0 dB. */
    const val USER3_PRE_GAIN_TENTHS = 0x50

    /** `RX eq_global_gain ok seq=16 data=[00 14]` with Close EQ selected: +2.0 dB. */
    const val CLOSE_EQ_PRE_GAIN_TENTHS = 0x14

    private fun bytes(hex: String) = hex.split(" ").map { it.toInt(16).toByte() }.toByteArray()

    val user3Bands: List<DacEqBand> = user3BandReplies.mapIndexed { i, hex ->
        (FiioEqReader.decodeBand(i, bytes(hex)) as EqRead.Read).value
    }

    /** The slot as the Melody held it before the app touched it: the curve and the +8.0 dB register. */
    val user3Factory = DacEqSlotSnapshot(0xA2, USER3_PRE_GAIN_TENTHS, user3Bands)

    /** Close EQ: ten bands that read all zeros (index, 0 Hz, 0 dB, Q 0), and a register of +2.0 dB. */
    val closeEq = DacEqSlotSnapshot(0xF0, CLOSE_EQ_PRE_GAIN_TENTHS, (0 until 10).map { DacEqBand(it, 0, 0, 0, 0) })

    /** A fake Melody that holds USER 3's factory curve in its slot and has "Close EQ" selected, as the owner's did. */
    fun fakeDac(activePreset: Int = 0xF0, profile: FiioHidProfile = FiioHidProfiles.all.single { it.id == "snowsky-melody" }): FakeFiioDac =
        FakeFiioDac(profile, activePreset = activePreset).also { dac ->
            dac.setSlot(0xA2, USER3_PRE_GAIN_TENTHS, user3Bands)
            dac.setSlot(0xF0, CLOSE_EQ_PRE_GAIN_TENTHS, closeEq.bands)
        }
}
