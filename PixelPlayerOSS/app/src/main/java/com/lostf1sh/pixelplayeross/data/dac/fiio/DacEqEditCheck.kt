package com.lostf1sh.pixelplayeross.data.dac.fiio

/**
 * B61 item 4: **what the gate will say to a curve, asked before any write is tried.** The write gate refuses a curve that rises above 0 dB while the pre-gain sense is unconfirmed
 * ([GateRefusal.PRE_GAIN_SENSE_UNCONFIRMED]: no register value protects it under both readings). The filter editor asks the same question of the curve it is editing, with the same function the
 * gate uses ([DacEqCurve.preGainTenthsFor]), so a gain typed without its minus (the owner's 24 refusals in a row on 11-07: "the curve rises 3.5 dB above 0") is shown as refused in the editor and
 * never reaches the DAC. Pure.
 */
object DacEqEditCheck {
    sealed interface Verdict {
        /** The gate would let this curve through (as far as its level rule goes). */
        data object Ok : Verdict

        /** The gate would refuse it: the curve rises [peakBoostDb] above 0 and the sense is unconfirmed. */
        data class Refused(val peakBoostDb: Double) : Verdict
    }

    fun verdict(bands: List<DacEqBand>, sense: PreGainSense): Verdict =
        if (DacEqCurve.preGainTenthsFor(bands, sense) == null) Verdict.Refused(DacEqCurve.peakBoostDb(bands)) else Verdict.Ok
}
