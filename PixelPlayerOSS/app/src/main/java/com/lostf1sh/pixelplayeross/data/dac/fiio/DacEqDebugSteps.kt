package com.lostf1sh.pixelplayeross.data.dac.fiio

/**
 * B30-P2: what the reviewer's `DAC_EQ_SET_BAND` may ask. One band, small: the first write on a DAC is an experiment, so the step is
 * capped at 3 dB (the gate's own limits are the DAC's, ±12), and the band is numbered the way the DAC's own UI numbers it, 1 up.
 * The debug receiver is the only caller; it lives here so the rule has a test in the JVM.
 */
object DacEqDebugSteps {
    const val MAX_STEP_DB = 3.0

    sealed interface SetBand {
        /** [bandIndex] is 0-based (what the wire carries). */
        data class Ok(val bandIndex: Int, val gainDb: Double) : SetBand

        data class Refused(val why: String) : SetBand
    }

    fun parseSetBand(band: Int?, gainDb: Float?, bandCount: Int): SetBand = when {
        band == null -> SetBand.Refused("needs --ei band N (1..$bandCount)")
        gainDb == null || gainDb.isNaN() || gainDb.isInfinite() -> SetBand.Refused("needs --ef gain G (dB, |G| <= ${MAX_STEP_DB.toInt()})")
        band !in 1..bandCount -> SetBand.Refused("band $band does not exist (1..$bandCount)")
        kotlin.math.abs(gainDb) > MAX_STEP_DB -> SetBand.Refused("|gain| ${gainDb} dB is beyond the ${MAX_STEP_DB.toInt()} dB a verification step may take")
        else -> SetBand.Ok(band - 1, Math.round(gainDb * 10) / 10.0)
    }

    /** `--es crc maxim|zero`; null (and the default) when absent, `Err` when it is something else: a typo must not become a mode. */
    fun parseCrc(value: String?): Result<FiioCrcMode?> = when (value?.lowercase()) {
        null -> Result.success(null)
        "maxim" -> Result.success(FiioCrcMode.MAXIM)
        "zero" -> Result.success(FiioCrcMode.ZERO)
        else -> Result.failure(IllegalArgumentException("--es crc maxim|zero"))
    }

    /** `--es save slot|empty|none`. */
    fun parseSave(value: String?): Result<FiioSaveForm?> = when (value?.lowercase()) {
        null -> Result.success(null)
        "slot" -> Result.success(FiioSaveForm.WITH_SLOT)
        "empty" -> Result.success(FiioSaveForm.EMPTY)
        "none" -> Result.success(FiioSaveForm.NONE)
        else -> Result.failure(IllegalArgumentException("--es save slot|empty|none"))
    }
}
