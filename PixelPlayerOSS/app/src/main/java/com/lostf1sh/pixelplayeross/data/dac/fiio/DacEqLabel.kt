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

    /** The DAC's EQ is closed or flat. */
    OFF_OR_FLAT,

    /** The DAC's EQ is shaping the sound. */
    ON,
    ;

    /** Purity may be shown. */
    val allowsPurity: Boolean get() = this == NOT_APPLICABLE || this == OFF_OR_FLAT

    companion object {
        fun of(verdict: DacEqVerdict): DacEqLabel = when (verdict) {
            DacEqVerdict.OFF, DacEqVerdict.FLAT -> OFF_OR_FLAT
            DacEqVerdict.ON -> ON
            DacEqVerdict.UNKNOWN -> UNKNOWN
        }
    }
}

/** Where a Resonance stream gets its shaping from (B30): the DAC's own EQ, the app's DSP, or both at once. */
enum class ResonanceSource { DAC_EQ, APP_DSP, BOTH }
