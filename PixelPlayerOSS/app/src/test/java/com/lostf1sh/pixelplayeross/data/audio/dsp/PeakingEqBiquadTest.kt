package com.lostf1sh.pixelplayeross.data.audio.dsp

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Coefficients and numerics of a single peaking-EQ section.
 *
 * The tolerances here are chosen to be tight enough to catch a wrong formula and loose enough not
 * to depend on the platform's transcendental functions. The measured-gain tolerance of 0.05 dB is
 * roughly 0.6 % in amplitude; a sign error, a missing `/a0`, or an A-versus-A² confusion moves the
 * answer by whole decibels, so the tests discriminate without being brittle.
 */
class PeakingEqBiquadTest {

    private val sampleRates = listOf(44_100, 48_000, 96_000, 192_000)
    private val gains = listOf(-15f, -12f, -6f, -3f, 3f, 6f, 12f, 15f)

    @Test
    fun `a zero dB section is an exact identity`() {
        val section = PeakingEqBiquad()
        section.design(gainDb = 0f, frequencyHz = 1_000, sampleRateHz = 44_100, q = 1.4125f)

        assertThat(section.isIdentity).isTrue()
        assertThat(section.coefficientsForTest()).isEqualTo(floatArrayOf(1f, 0f, 0f, 0f, 0f))
    }

    @Test
    fun `a zero dB section passes samples through unchanged`() {
        val section = PeakingEqBiquad()
        section.design(gainDb = 0f, frequencyHz = 1_000, sampleRateHz = 48_000, q = 1.4125f)

        listOf(0f, 0.5f, -0.5f, 1f, -1f, 0.123456f).forEach { sample ->
            assertThat(section.process(sample)).isEqualTo(sample)
        }
    }

    @Test
    fun `measured gain at the centre frequency equals the requested gain`() {
        sampleRates.forEach { sampleRate ->
            gains.forEach { gain ->
                val section = PeakingEqBiquad()
                section.design(gainDb = gain, frequencyHz = 1_000, sampleRateHz = sampleRate, q = 1.4125f)
                val measured = DspMeasurement.steadyStateGainDb(sampleRate, 1_000.0, section::process)
                assertThat(measured).isWithin(0.05).of(gain.toDouble())
            }
        }
    }

    @Test
    fun `a boost and an equal cut are mirror images in dB`() {
        gains.forEach { gain ->
            val boost = PeakingEqBiquad().apply {
                design(gainDb = gain, frequencyHz = 250, sampleRateHz = 48_000, q = 1.4125f)
            }
            val cut = PeakingEqBiquad().apply {
                design(gainDb = -gain, frequencyHz = 250, sampleRateHz = 48_000, q = 1.4125f)
            }
            val boostDb = DspMeasurement.steadyStateGainDb(48_000, 250.0, boost::process)
            val cutDb = DspMeasurement.steadyStateGainDb(48_000, 250.0, cut::process)
            assertThat(boostDb + cutDb).isWithin(0.02).of(0.0)
        }
    }

    @Test
    fun `the section approaches unity far from its centre frequency`() {
        val section = PeakingEqBiquad()
        section.design(gainDb = 15f, frequencyHz = 31, sampleRateHz = 44_100, q = 1.4125f)

        // Nine octaves above the centre: a peaking filter must be transparent there.
        val far = DspMeasurement.steadyStateGainDb(44_100, 16_000.0, section::process)
        assertThat(far).isWithin(0.1).of(0.0)
    }

    @Test
    fun `every section is stable across all band gains and sample rates`() {
        val tables = listOf(GraphicEqBands.TEN_BAND_HZ, GraphicEqBands.FIFTEEN_BAND_HZ)
        tables.forEach { table ->
            val q = GraphicEqBands.qualityFactor(table)
            sampleRates.forEach { sampleRate ->
                gains.forEach { gain ->
                    table.forEach { frequency ->
                        val section = PeakingEqBiquad()
                        section.design(gain, frequency, sampleRate, q)
                        val (b0, b1, b2, a1, a2) = section.coefficientsForTest().toList()
                        assertThat(DspMeasurement.isStable(a1, a2)).isTrue()
                        // Every coefficient stays a finite, usable number at every extreme.
                        listOf(b0, b1, b2, a1, a2).forEach { assertThat(it).isFinite() }
                    }
                }
            }
        }
    }

    @Test
    fun `coefficients are recomputed for each sample rate rather than reused`() {
        val section = PeakingEqBiquad()
        val perRate = sampleRates.map { sampleRate ->
            section.design(gainDb = 12f, frequencyHz = 1_000, sampleRateHz = sampleRate, q = 1.4125f)
            section.coefficientsForTest().toList()
        }
        assertThat(perRate.toSet()).hasSize(sampleRates.size)
    }

    @Test
    fun `a frequency above the usable band is clamped instead of collapsing the section`() {
        // 40 kHz is far past Nyquist at 44.1 kHz. Clamping to 45 % of the rate keeps a real filter
        // rather than an identity that silently does nothing.
        val section = PeakingEqBiquad()
        section.design(gainDb = 12f, frequencyHz = 40_000, sampleRateHz = 44_100, q = 1.4125f)

        assertThat(section.isIdentity).isFalse()
        val (_, _, _, a1, a2) = section.coefficientsForTest().toList()
        assertThat(DspMeasurement.isStable(a1, a2)).isTrue()
    }

    @Test
    fun `state decays to exactly zero rather than lingering as a denormal`() {
        val section = PeakingEqBiquad()
        section.design(gainDb = 15f, frequencyHz = 1_000, sampleRateHz = 48_000, q = 1.4125f)
        section.process(1f)

        var tail = 0f
        repeat(200_000) { tail = section.process(0f) }

        assertThat(tail).isEqualTo(0f)
    }

    @Test
    fun `clearState returns the section to a fresh condition`() {
        val section = PeakingEqBiquad()
        section.design(gainDb = 12f, frequencyHz = 500, sampleRateHz = 48_000, q = 1.4125f)
        section.process(1f)

        section.clearState()

        assertThat(section.process(0f)).isEqualTo(0f)
    }
}
