package com.lostf1sh.pixelplayeross.data.dac.fiio

/**
 * B30-P1.4: what the labels (Details, the lock line, the Audio page) may say about the DAC's EQ.
 *
 * Purity is "nothing shapes the sound": no app DSP **and** the DAC's EQ off or flat. The USB stream is bit-perfect either way,
 * but with the DAC's EQ on the analogue output is shaped inside the DAC, so the stream's own verdict is not the label's.
 */
enum class DacEqLabel {
    /**
     * The connected DAC has no EQ the app can ask about: no DAC, or one outside the FiiO/Snowsky table. The labels say what they
     * said before B30; the app cannot see inside a DAC it cannot ask, and does not pretend to.
     */
    NOT_APPLICABLE,

    /** A DAC the app can ask, whose EQ has not been read yet or could not be: Purity is not claimed. */
    UNKNOWN,

    /**
     * B41: the DAC's EQ is **off**: its own "Close EQ" preset is selected (or its switch says off). Read on the owner's Melody twice and
     * consistently (preset 0xF0, a global gain of +2.0 dB, ten empty bands): the DAC's real state, not a failed read.
     */
    OFF,

    /** B41: a preset is selected, every band is a 0 dB filter and the global gain is 0: the EQ is on and does nothing. */
    FLAT,

    /** The DAC's EQ is shaping the sound. */
    ON,
    ;

    /** The DAC's EQ does not shape the sound: closed, or on and flat. */
    val isQuiet: Boolean get() = this == OFF || this == FLAT

    /** Purity may be shown. */
    val allowsPurity: Boolean get() = this == NOT_APPLICABLE || isQuiet

    companion object {
        fun of(verdict: DacEqVerdict): DacEqLabel = when (verdict) {
            DacEqVerdict.OFF -> OFF
            DacEqVerdict.FLAT -> FLAT
            DacEqVerdict.ON -> ON
            DacEqVerdict.UNKNOWN -> UNKNOWN
        }
    }
}

/** Where a Resonance stream gets its shaping from (B30): the DAC's own EQ, the app's DSP, or both at once. */
enum class ResonanceSource { DAC_EQ, APP_DSP, BOTH }
