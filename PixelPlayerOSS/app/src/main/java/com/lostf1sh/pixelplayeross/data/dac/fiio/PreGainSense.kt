package com.lostf1sh.pixelplayeross.data.dac.fiio

import kotlin.math.abs

/**
 * B31 P1.2: **what the DAC's global gain (`GET/SET 17`) does to the level.** The sign is the one fact the first Melody run could not
 * settle, and the sources do not agree:
 *
 * - **[BOOST]**: the register is a signed gain in dB; positive amplifies, negative attenuates. WolfEQ writes the AutoEQ preamp as it
 *   is (`Services/FiioK13DeviceService.cs`, `SaveToDeviceAsync` → `SetGlobalGain` payload `Math.Round(requestedPreamp * 10.0)`; its test
 *   `OptimizeHeadroom_MatchesStrongestEnabledBoost` expects "Peak +4.0 dB → preamp -4.0 dB", written live to a K13 R2R, the project's
 *   verified target), and DevicePEQ since 2026-07-21 (`fiioUsbHidHandler.js` `pushToDevice` → `clampGlobalGain`, its tests
 *   `test_pushToDevice_writesRequestedNegativePreamp`). The other Melody recorded in `tests/captures/fiio_snowsky_melody.json` reads
 *   −4.0 dB under a curve that peaks at +3.5.
 * - **[ATTENUATION]**: the register is the attenuation, stored as a positive number. Nothing in the sources says so, and nothing
 *   excludes it.
 * - DevicePEQ **before 2026-07-21** wrote `maxGain + preamp` (a −5 dB preamp as `+7.0`), believing "FiiO devices will automatically cut
 *   the max SPL by the maxGain": the register would then be an offset from a 12 dB ceiling. That reading is never louder than
 *   [BOOST] (it subtracts 12), so judging under [BOOST] covers it. The owner's USER 3 (`+8.0` under ten AutoEQ-shaped bands) reads
 *   like that convention, not like a signed preamp: AutoEQ's preamp is never positive.
 *
 * So the app does not guess. While the sense is **[UNCONFIRMED]** every level is judged under *both* readings and the worse one
 * counts: a register value of `r` tenths is `|r|` louder than neutral (the worse of `+r` and `−r`). Only a register of 0 is neutral
 * under both, so only a curve that never rises above 0 dB can be written until the reviewer's listening check (a flat curve with
 * the pre-gain at −6 and +6 dB) says which reading is true and the debug override [DacEqPreGainSenseSwitch] records it.
 */
enum class PreGainSense {
    /** Positive register = louder, negative = quieter (WolfEQ, DevicePEQ). */
    BOOST,

    /** Positive register = quieter (attenuation stored as a positive number). */
    ATTENUATION,

    /** Not known: judged under both readings. */
    UNCONFIRMED,
    ;

    /** The single readings a level is judged under: this one, or both while it is not known. */
    val readings: List<PreGainSense> get() = if (this == UNCONFIRMED) listOf(BOOST, ATTENUATION) else listOf(this)

    /**
     * What a register of [tenths] adds to the level, in dB, under this reading. For [UNCONFIRMED] it is the worse of the two readings,
     * `|tenths|`: a sound level that is at most that much above neutral whichever sense is true.
     */
    fun offsetDb(tenths: Int): Double = when (this) {
        BOOST -> tenths / 10.0
        ATTENUATION -> -tenths / 10.0
        UNCONFIRMED -> abs(tenths) / 10.0
    }

    /**
     * The register that attenuates by [attenuationTenths] (≥ 0), or null when this reading cannot say which register that is: while the
     * sense is unknown only "no attenuation" (a register of 0) is the same under both readings.
     */
    fun registerForAttenuation(attenuationTenths: Int): Int? = when (this) {
        BOOST -> -attenuationTenths
        ATTENUATION -> attenuationTenths
        UNCONFIRMED -> if (attenuationTenths == 0) 0 else null
    }

    /** Of two registers, the one that protects more under this reading (the larger attenuation; unknown: the one nearer 0). */
    fun moreProtective(a: Int, b: Int): Int = when (this) {
        BOOST -> minOf(a, b)
        ATTENUATION -> maxOf(a, b)
        UNCONFIRMED -> if (abs(a) <= abs(b)) a else b
    }

    companion object {
        fun parse(text: String?): PreGainSense? = when (text?.trim()?.lowercase()) {
            "boost", "signed" -> BOOST
            "attenuation", "attenuate" -> ATTENUATION
            "unconfirmed", "unknown", "none", "off" -> UNCONFIRMED
            else -> null
        }
    }
}
