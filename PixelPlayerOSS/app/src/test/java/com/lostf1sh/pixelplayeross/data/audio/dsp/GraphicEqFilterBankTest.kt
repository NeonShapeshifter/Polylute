package com.lostf1sh.pixelplayeross.data.audio.dsp

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The properties a Graphic EQ must have to be a Graphic EQ rather than a decorative label: one
 * filter per band, one filter memory per channel, and no way for any of them to influence another.
 *
 * Tolerances are stated where they are used. The recurring 0.05 dB figure is about 0.6 % in
 * amplitude — far tighter than any audible difference and far looser than float32 rounding over the
 * two seconds the measurement integrates.
 */
class GraphicEqFilterBankTest {

    private val sampleRates = listOf(44_100, 48_000, 96_000, 192_000)

    private fun settings(
        frequencies: IntArray,
        gains: FloatArray,
        enabled: Boolean = true,
    ) = GraphicEqSettings(enabled, frequencies, gains)

    private fun flatGains(frequencies: IntArray) = FloatArray(frequencies.size)

    private fun preparedBank(
        settings: GraphicEqSettings,
        sampleRateHz: Int = 48_000,
        channelCount: Int = 2,
    ): GraphicEqFilterBank = GraphicEqFilterBank().apply {
        prepare(sampleRateHz, channelCount)
        apply(settings)
    }

    // --- Band structure -------------------------------------------------------------------

    @Test
    fun `ten band settings build exactly ten filters per channel`() {
        val bank = preparedBank(settings(GraphicEqBands.TEN_BAND_HZ, flatGains(GraphicEqBands.TEN_BAND_HZ)))
        assertThat(bank.bandCountForTest).isEqualTo(10)
        assertThat(bank.channelCountForTest).isEqualTo(2)
    }

    @Test
    fun `fifteen band settings build exactly fifteen filters per channel`() {
        val bank = preparedBank(settings(GraphicEqBands.FIFTEEN_BAND_HZ, flatGains(GraphicEqBands.FIFTEEN_BAND_HZ)))
        assertThat(bank.bandCountForTest).isEqualTo(15)
    }

    @Test
    fun `each non-zero band is one independently processed filter`() {
        val gains = flatGains(GraphicEqBands.TEN_BAND_HZ)
        gains[3] = 12f
        val bank = preparedBank(settings(GraphicEqBands.TEN_BAND_HZ, gains))

        assertThat(bank.activeBandCountForTest).isEqualTo(1)
    }

    @Test
    fun `every non-zero band is processed, not just the first`() {
        val gains = flatGains(GraphicEqBands.FIFTEEN_BAND_HZ)
        listOf(0, 7, 14).forEach { gains[it] = 6f }
        val bank = preparedBank(settings(GraphicEqBands.FIFTEEN_BAND_HZ, gains))

        assertThat(bank.activeBandCountForTest).isEqualTo(3)
    }

    @Test
    fun `a flat bank processes nothing at all`() {
        val frequencies = GraphicEqBands.TEN_BAND_HZ
        val bank = preparedBank(settings(frequencies, flatGains(frequencies)))

        assertThat(bank.activeBandCountForTest).isEqualTo(0)
        // Every band at 0 dB is skipped, so the sample comes back bit-identical.
        assertThat(bank.processSample(0, 0.37f)).isEqualTo(0.37f)
        assertThat(bank.processSample(1, -1f)).isEqualTo(-1f)
    }

    @Test
    fun `each band's coefficients depend only on that band's gain`() {
        val frequencies = GraphicEqBands.TEN_BAND_HZ

        val firstOnly = flatGains(frequencies).also { it[2] = 12f }
        val firstAndAnother = firstOnly.copyOf().also { it[7] = -9f }

        val a = preparedBank(settings(frequencies, firstOnly))
        val b = preparedBank(settings(frequencies, firstAndAnother))

        // Band 2's coefficients are byte-identical whether or not band 7 is also boosted.
        (0 until 2).forEach { channel ->
            assertThat(b.coefficientsForTest(channel, 2))
                .isEqualTo(a.coefficientsForTest(channel, 2))
        }
        // And band 7 really did get designed, so the comparison is not vacuous.
        assertThat(b.coefficientsForTest(0, 7)).isNotEqualTo(a.coefficientsForTest(0, 7))
    }

    @Test
    fun `one band's boost does not move a distant band's response`() {
        val frequencies = GraphicEqBands.TEN_BAND_HZ
        val gains = flatGains(frequencies)
        gains[0] = 15f // 31 Hz
        val bank = preparedBank(settings(frequencies, gains), sampleRateHz = 48_000)

        val atTarget = DspMeasurement.steadyStateGainDb(bank, 48_000, 31.0)
        val atDistantBand = DspMeasurement.steadyStateGainDb(bank, 48_000, 16_000.0)

        assertThat(atTarget).isWithin(0.05).of(15.0)
        // Nine octaves away the filter is transparent to well under a tenth of a decibel.
        assertThat(atDistantBand).isWithin(0.1).of(0.0)
    }

    @Test
    fun `a single band's slider is honoured essentially exactly at its own centre`() {
        // The strongest statement the design can make: with every other band flat, a band centre
        // measures the requested gain. Everything else in this file is about what happens when the
        // neighbours are not flat.
        listOf(GraphicEqBands.TEN_BAND_HZ, GraphicEqBands.FIFTEEN_BAND_HZ).forEach { frequencies ->
            listOf(12f, -12f).forEach { gain ->
                frequencies.forEachIndexed { index, frequency ->
                    val gains = flatGains(frequencies).also { it[index] = gain }
                    val bank = preparedBank(settings(frequencies, gains), sampleRateHz = 48_000)
                    val measured = DspMeasurement.steadyStateGainDb(bank, 48_000, frequency.toDouble())
                    assertThat(measured).isWithin(0.05).of(gain.toDouble())
                }
            }
        }
    }

    @Test
    fun `adjacent bands overlap by a bounded, documented amount`() {
        // A band centre is not its band's gain when the neighbours are also moved: overlapping
        // filters are what turns separate sections into a smooth curve rather than a comb, and this
        // is the price. For one-octave spacing at Q = sqrt(2)/(2 - 1) the nearest neighbour leaks
        // about 20 % of its setting, so an alternating pattern peaks around 3.3 dB away from the
        // request. Measured, not assumed: 3.25 dB over the ten-band table, 3.37 dB over the fifteen.
        listOf(GraphicEqBands.TEN_BAND_HZ to 3.5, GraphicEqBands.FIFTEEN_BAND_HZ to 3.5)
            .forEach { (frequencies, tolerance) ->
                val gains = FloatArray(frequencies.size) { index -> if (index % 2 == 0) 9f else -9f }
                val bank = preparedBank(settings(frequencies, gains), sampleRateHz = 48_000)

                frequencies.forEachIndexed { index, frequency ->
                    val measured = DspMeasurement.steadyStateGainDb(bank, 48_000, frequency.toDouble())
                    assertThat(measured).isWithin(tolerance).of(gains[index].toDouble())
                    // The request's sign survives, which is the property a user actually reads.
                    if (gains[index] > 0f) {
                        assertThat(measured).isGreaterThan(0.5)
                    } else {
                        assertThat(measured).isLessThan(-0.5)
                    }
                }
            }
    }

    @Test
    fun `the interference figure at maximum boost is the documented one`() {
        // The headroom statement in the architecture note depends on this number, so it is pinned
        // here rather than quoted from memory. Every band at +15 dB does not give a flat +15 dB
        // curve: the sections sum, and the curve peaks about 8.7 dB higher. That is the worst case
        // a user can ask for, and it is why the report quotes a maximum at all.
        val frequencies = GraphicEqBands.TEN_BAND_HZ
        val bank = preparedBank(settings(frequencies, FloatArray(frequencies.size) { 15f }))

        val peak = frequencies.maxOf { DspMeasurement.steadyStateGainDb(bank, 48_000, it.toDouble()) }
        assertThat(peak).isWithin(0.1).of(23.7)
    }

    @Test
    fun `the sign of a gain is the sign of the effect`() {
        val frequencies = GraphicEqBands.TEN_BAND_HZ
        val boost = flatGains(frequencies).also { it[5] = 12f }
        val cut = flatGains(frequencies).also { it[5] = -12f }

        val boostDb = DspMeasurement.steadyStateGainDb(
            preparedBank(settings(frequencies, boost)), 48_000, frequencies[5].toDouble()
        )
        val cutDb = DspMeasurement.steadyStateGainDb(
            preparedBank(settings(frequencies, cut)), 48_000, frequencies[5].toDouble()
        )

        assertThat(boostDb).isGreaterThan(0.0)
        assertThat(cutDb).isLessThan(0.0)
    }

    @Test
    fun `fifteen band gains reach their own filters and not the ten band ones`() {
        val frequencies = GraphicEqBands.FIFTEEN_BAND_HZ
        val gains = flatGains(frequencies)
        gains[1] = 12f // 40 Hz, a frequency the ten-band table does not have
        val bank = preparedBank(settings(frequencies, gains), sampleRateHz = 48_000)

        val measured = DspMeasurement.steadyStateGainDb(bank, 48_000, 40.0)
        assertThat(measured).isWithin(0.05).of(12.0)
        assertThat(bank.bandCountForTest).isEqualTo(15)
    }

    // --- Channels -------------------------------------------------------------------------

    @Test
    fun `channels have separate filter memory`() {
        val frequencies = GraphicEqBands.TEN_BAND_HZ
        val gains = flatGains(frequencies).also { it[4] = 12f }
        val bank = preparedBank(settings(frequencies, gains))

        // Drive the left channel only.
        val left = (0 until 512).map { if (it == 0) 1f else 0f }
            .map { bank.processSample(0, it) }
        // The right channel sees pure silence and must stay exactly silent.
        val right = (0 until 512).map { bank.processSample(1, 0f) }

        assertThat(left.any { it != 0f }).isTrue()
        assertThat(right).containsExactlyElementsIn(List(512) { 0f })
    }

    @Test
    fun `a sample on one channel cannot change what another channel produces`() {
        val frequencies = GraphicEqBands.TEN_BAND_HZ
        val gains = flatGains(frequencies).also { it[2] = 12f; it[6] = -6f }

        val reference = preparedBank(settings(frequencies, gains))
        val contaminated = preparedBank(settings(frequencies, gains))

        val referenceRight = (0 until 256).map {
            reference.processSample(1, if (it == 0) 0.5f else 0f)
        }
        val contaminatedRight = (0 until 256).map { index ->
            // Loud, unrelated left-channel activity in between.
            contaminated.processSample(0, if (index % 3 == 0) 1f else -1f)
            contaminated.processSample(1, if (index == 0) 0.5f else 0f)
        }

        assertThat(contaminatedRight).isEqualTo(referenceRight)
    }

    @Test
    fun `channel ordering is preserved sample for sample`() {
        val frequencies = GraphicEqBands.TEN_BAND_HZ
        val gains = flatGains(frequencies).also { it[1] = 9f }
        val bank = preparedBank(settings(frequencies, gains))
        val scaled = preparedBank(settings(frequencies, gains))

        // A linear filter scales: half the input must give exactly half the output, which also
        // proves the two channels are processed with the same coefficients and not swapped.
        val full = (0 until 400).map { bank.processSample(0, if (it == 0) 1f else 0f) }
        val half = (0 until 400).map { scaled.processSample(1, if (it == 0) 0.5f else 0f) }

        full.zip(half).forEach { (a, b) ->
            assertThat(b).isEqualTo(a * 0.5f)
        }
    }

    // --- Shape, state and hostiles --------------------------------------------------------

    @Test
    fun `silence in gives exactly silence out`() {
        val frequencies = GraphicEqBands.FIFTEEN_BAND_HZ
        val gains = FloatArray(frequencies.size) { 15f }
        val bank = preparedBank(settings(frequencies, gains))

        assertThat((0 until 1_024).map { bank.processSample(0, 0f) })
            .containsExactlyElementsIn(List(1_024) { 0f })
    }

    @Test
    fun `preparing the same shape twice keeps the loaded coefficients`() {
        val frequencies = GraphicEqBands.TEN_BAND_HZ
        val gains = flatGains(frequencies).also { it[0] = 12f }
        val bank = preparedBank(settings(frequencies, gains))
        val sectionsBefore = bank.sectionsIdentityForTest

        bank.prepare(48_000, 2)

        assertThat(bank.sectionsIdentityForTest).isSameInstanceAs(sectionsBefore)
    }

    @Test
    fun `a gains-only change reuses the existing filter objects`() {
        val frequencies = GraphicEqBands.TEN_BAND_HZ
        val bank = preparedBank(settings(frequencies, flatGains(frequencies).also { it[0] = 6f }))
        val sectionsBefore = bank.sectionsIdentityForTest

        bank.apply(settings(frequencies, flatGains(frequencies).also { it[0] = 12f }))

        // Dragging a slider must not rebuild the bank: only the coefficients change.
        assertThat(bank.sectionsIdentityForTest).isSameInstanceAs(sectionsBefore)
        assertThat(bank.processSample(0, 0.5f)).isFinite()
    }

    @Test
    fun `re-applying the identical snapshot does not restart the cascade`() {
        val frequencies = GraphicEqBands.TEN_BAND_HZ
        val snapshot = settings(frequencies, flatGains(frequencies).also { it[4] = 12f })

        val interrupted = preparedBank(snapshot)
        val before = (0 until 64).map { interrupted.processSample(0, if (it == 0) 1f else 0f) }
        // A redundant state emission carrying the same snapshot must not reset a ringing filter.
        interrupted.apply(snapshot)
        val after = (0 until 64).map { interrupted.processSample(0, 0f) }

        val uninterrupted = preparedBank(snapshot)
        val whole = (0 until 128).map { uninterrupted.processSample(0, if (it == 0) 1f else 0f) }

        assertThat(before + after).isEqualTo(whole)
        // The tail is still audible here, so the equality above compares two live cascades rather
        // than two runs of silence.
        assertThat(after.any { it != 0f }).isTrue()
    }

    @Test
    fun `switching between ten and fifteen bands reshapes the bank and clears memory`() {
        val ten = settings(
            GraphicEqBands.TEN_BAND_HZ,
            flatGains(GraphicEqBands.TEN_BAND_HZ).also { it[0] = 12f }
        )
        val fifteen = settings(
            GraphicEqBands.FIFTEEN_BAND_HZ,
            flatGains(GraphicEqBands.FIFTEEN_BAND_HZ).also { it[0] = 12f }
        )

        val bank = preparedBank(ten)
        bank.processSample(0, 1f)

        bank.apply(fifteen)
        assertThat(bank.bandCountForTest).isEqualTo(15)
        assertThat(bank.activeBandCountForTest).isEqualTo(1)

        bank.apply(ten)
        assertThat(bank.bandCountForTest).isEqualTo(10)
    }

    @Test
    fun `switching back to ten bands does not resurrect the fifteen band delay lines`() {
        val ten = settings(
            GraphicEqBands.TEN_BAND_HZ,
            flatGains(GraphicEqBands.TEN_BAND_HZ).also { it[4] = 12f }
        )
        val fifteen = settings(
            GraphicEqBands.FIFTEEN_BAND_HZ,
            flatGains(GraphicEqBands.FIFTEEN_BAND_HZ).also { it[6] = 12f }
        )

        val fresh = preparedBank(ten)
        val roundTripped = preparedBank(ten)
        roundTripped.apply(fifteen)
        roundTripped.processSample(0, 1f)
        roundTripped.apply(ten)

        val expected = (0 until 128).map { fresh.processSample(0, if (it == 0) 1f else 0f) }
        val actual = (0 until 128).map { roundTripped.processSample(0, if (it == 0) 1f else 0f) }
        assertThat(actual).isEqualTo(expected)
    }

    @Test
    fun `a non-finite intermediate cannot escape or persist`() {
        val frequencies = GraphicEqBands.TEN_BAND_HZ
        val bank = preparedBank(settings(frequencies, FloatArray(frequencies.size) { 15f }))

        val poisoned = (0 until 64).map { bank.processSample(0, Float.NaN) }
        assertThat(poisoned).containsExactlyElementsIn(List(64) { 0f })

        // The channel's memory was cleared, so the cascade restarts from a known state.
        val after = (0 until 4_096).map { bank.processSample(0, 0f) }
        after.forEach { assertThat(it).isFinite() }
        assertThat(after).containsExactlyElementsIn(List(4_096) { 0f })
    }

    @Test
    fun `infinite and huge inputs never produce a non-finite output`() {
        val frequencies = GraphicEqBands.FIFTEEN_BAND_HZ
        val bank = preparedBank(settings(frequencies, FloatArray(frequencies.size) { 15f }))

        val candidates = listOf(
            Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY,
            Float.MAX_VALUE,
            -Float.MAX_VALUE,
            1e30f,
        )
        candidates.forEach { candidate ->
            repeat(512) {
                val output = bank.processSample(0, candidate)
                assertThat(output).isFinite()
            }
        }
        // And the bank is still usable afterwards.
        assertThat(bank.processSample(0, 0f)).isFinite()
    }

    @Test
    fun `resetState clears history without discarding coefficients`() {
        val frequencies = GraphicEqBands.TEN_BAND_HZ
        val gains = flatGains(frequencies).also { it[3] = 12f }
        val bank = preparedBank(settings(frequencies, gains))
        bank.processSample(0, 1f)
        val coefficients = bank.coefficientsForTest(0, 3)

        bank.resetState()

        assertThat(bank.processSample(0, 0f)).isEqualTo(0f)
        assertThat(bank.coefficientsForTest(0, 3)).isEqualTo(coefficients)
        assertThat(bank.activeBandCountForTest).isEqualTo(1)
    }

    @Test
    fun `every band stays stable at every sample rate`() {
        sampleRates.forEach { sampleRate ->
            listOf(
                GraphicEqBands.TEN_BAND_HZ to 15f,
                GraphicEqBands.TEN_BAND_HZ to -15f,
                GraphicEqBands.FIFTEEN_BAND_HZ to 15f,
                GraphicEqBands.FIFTEEN_BAND_HZ to -15f,
            ).forEach { (frequencies, gain) ->
                val bank = preparedBank(
                    settings(frequencies, FloatArray(frequencies.size) { gain }),
                    sampleRateHz = sampleRate,
                )
                assertThat(bank.sampleRateForTest).isEqualTo(sampleRate)
                frequencies.indices.forEach { band ->
                    val (_, _, _, a1, a2) = bank.coefficientsForTest(0, band).toList()
                    assertThat(DspMeasurement.isStable(a1, a2)).isTrue()
                }
            }
        }
    }

    @Test
    fun `coefficients are designed for the stream's sample rate`() {
        val frequencies = GraphicEqBands.TEN_BAND_HZ
        val gains = flatGains(frequencies).also { it[5] = 12f }

        val perRate = sampleRates.map { sampleRate ->
            preparedBank(settings(frequencies, gains), sampleRateHz = sampleRate)
                .coefficientsForTest(0, 5).toList()
        }

        assertThat(perRate.toSet()).hasSize(sampleRates.size)
        // But the measured gain is the same at every rate — the filter is retuned, not changed.
        sampleRates.forEach { sampleRate ->
            val bank = preparedBank(settings(frequencies, gains), sampleRateHz = sampleRate)
            val measured = DspMeasurement.steadyStateGainDb(bank, sampleRate, frequencies[5].toDouble())
            assertThat(measured).isWithin(0.05).of(12.0)
        }
    }

    @Test
    fun `quality factor follows the applied band table`() {
        val ten = preparedBank(
            settings(GraphicEqBands.TEN_BAND_HZ, FloatArray(10) { 1f })
        )
        val fifteen = preparedBank(
            settings(GraphicEqBands.FIFTEEN_BAND_HZ, FloatArray(15) { 1f })
        )

        assertThat(ten.qualityFactorForTest)
            .isWithin(1e-6f)
            .of(GraphicEqBands.qualityFactor(GraphicEqBands.TEN_BAND_HZ))
        assertThat(fifteen.qualityFactorForTest)
            .isWithin(1e-6f)
            .of(GraphicEqBands.qualityFactor(GraphicEqBands.FIFTEEN_BAND_HZ))
    }

    @Test
    fun `a short gains array is treated as flat rather than crashing`() {
        val frequencies = GraphicEqBands.TEN_BAND_HZ
        val bank = preparedBank(settings(frequencies, floatArrayOf(12f)))

        assertThat(bank.activeBandCountForTest).isEqualTo(1)
        assertThat(bank.processSample(0, 0.25f)).isFinite()
    }
}
