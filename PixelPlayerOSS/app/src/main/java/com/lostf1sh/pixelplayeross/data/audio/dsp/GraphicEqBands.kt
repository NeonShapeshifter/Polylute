package com.lostf1sh.pixelplayeross.data.audio.dsp

import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Canonical band tables for the software headphone Graphic EQ (Phase 8.5).
 *
 * These are *product* band definitions: every entry is realised by its own dedicated peaking
 * filter, so a 10-band mode really runs ten independent filters and a 15-band mode really runs
 * fifteen. Nothing here is mapped onto, grouped into, or averaged against a platform effect's
 * band count.
 *
 * ## Why the filter Q is derived instead of hardcoded
 *
 * A constant-Q graphic EQ wants neighbouring bands to overlap by a fixed fraction of an octave.
 * The textbook constant-Q relation between the octave bandwidth `N` and the quality factor is
 *
 * ```
 * Q = sqrt(2^N) / (2^N - 1)
 * ```
 *
 * which yields the well-known `Q = sqrt(2) ≈ 1.4142` for one-octave spacing. Deriving `Q` from the
 * table's own geometric spacing keeps the design self-consistent: if a frequency is ever edited,
 * the bandwidth follows instead of silently drifting out of agreement with the labels.
 */
object GraphicEqBands {

    /** Ten log-spaced bands, one octave apart. Preserved from the pre-8.5 product frequencies. */
    val TEN_BAND_HZ = intArrayOf(
        31, 62, 125, 250, 500, 1_000, 2_000, 4_000, 8_000, 16_000,
    )

    /**
     * Fifteen log-spaced bands, two-thirds of an octave apart. The classic ISO-style
     * third-octave-adjacent set (25 Hz … 16 kHz); every frequency is a standard nominal
     * preferred value rather than an arbitrary invention.
     */
    val FIFTEEN_BAND_HZ = intArrayOf(
        25, 40, 63, 100, 160, 250, 400, 630, 1_000, 1_600, 2_500, 4_000, 6_300, 10_000, 16_000,
    )

    /** Slider labels for [TEN_BAND_HZ]; identical to the pre-8.5 labels. */
    val TEN_BAND_LABELS = listOf(
        "31Hz", "62Hz", "125Hz", "250Hz", "500Hz",
        "1kHz", "2kHz", "4kHz", "8kHz", "16kHz",
    )

    /** Slider labels for [FIFTEEN_BAND_HZ]. */
    val FIFTEEN_BAND_LABELS = listOf(
        "25Hz", "40Hz", "63Hz", "100Hz", "160Hz",
        "250Hz", "400Hz", "630Hz", "1kHz", "1.6kHz",
        "2.5kHz", "4kHz", "6.3kHz", "10kHz", "16kHz",
    )

    const val TEN_BAND_COUNT = 10
    const val FIFTEEN_BAND_COUNT = 15

    /**
     * Constant-Q quality factor for [frequenciesHz], derived from its mean geometric spacing.
     *
     * Returns `sqrt(2) ≈ 1.4142` for the octave-spaced 10-band table and `≈ 2.1488` for the
     * two-thirds-octave 15-band table.
     */
    fun qualityFactor(frequenciesHz: IntArray): Float {
        require(frequenciesHz.size >= 2) { "A graphic EQ band table needs at least two bands" }
        val totalRatio = frequenciesHz.last().toDouble() / frequenciesHz.first().toDouble()
        val octavesPerStep = ln(totalRatio) / ln(2.0) / (frequenciesHz.size - 1)
        val ratio = 2.0.pow(octavesPerStep)
        return (sqrt(ratio) / (ratio - 1.0)).toFloat()
    }

    fun labelsFor(bandCount: Int): List<String> = when (bandCount) {
        FIFTEEN_BAND_COUNT -> FIFTEEN_BAND_LABELS
        else -> TEN_BAND_LABELS
    }

    fun frequenciesFor(bandCount: Int): IntArray = when (bandCount) {
        FIFTEEN_BAND_COUNT -> FIFTEEN_BAND_HZ.copyOf()
        else -> TEN_BAND_HZ.copyOf()
    }
}
