package com.lostf1sh.pixelplayeross.data.audio.dsp

import com.google.common.truth.Truth.assertThat
import kotlin.math.log10
import kotlin.random.Random
import org.junit.jupiter.api.Test

/**
 * B14-2, audit A-01: the headroom a Graphic EQ boost needs is the loudest point of the *cascade*, not the highest
 * slider.
 *
 * The test that stood here compared the estimate with itself (`boostDb(state) + db(factor(state)) <= 0`), which
 * holds by algebra whatever the estimate is, and so passed while the real bank, at +15 dB on every band, lifted
 * 1 kHz by +23.6 dB against a "−15 dB" compensation. Nothing in this file reads the estimator to judge it: the
 * oracle is the real filter bank, measured — the discrete-time Fourier transform of the impulse response its
 * recursion produces, and sines run through it — at the stream's own sample rate.
 */
class GraphicEqHeadroomTest {

    private fun bank(sampleRateHz: Int, frequencies: IntArray, gains: FloatArray): GraphicEqFilterBank =
        GraphicEqFilterBank().also {
            it.prepare(sampleRateHz, 1)
            it.apply(GraphicEqSettings(true, frequencies, gains))
        }

    private fun allBands(count: Int, gainDb: Float) = FloatArray(count) { gainDb }

    private fun sweepFor(sampleRateHz: Int, frequencies: IntArray): DoubleArray = DspMeasurement.sweepHz(
        fromHz = 10.0,
        toHz = sampleRateHz / 2.0 - 1.0,
        points = 160,
        extraHz = DoubleArray(frequencies.size) { frequencies[it].toDouble() },
    )

    /** The loudest point of the bank's real transfer, in dB. */
    private fun measuredLoudestDb(bank: GraphicEqFilterBank, sampleRateHz: Int, frequencies: IntArray): Double {
        val impulse = DspMeasurement.impulseResponse(bank, sampleRateHz)
        return DspMeasurement.loudestPoint(impulse, sampleRateHz, sweepFor(sampleRateHz, frequencies)).first
    }

    private fun db(gain: Float) = 20.0 * log10(gain.toDouble())

    // ── The audit's probe ──────────────────────────────────────────────────────────────────────

    @Test
    fun `all bands at +15 dB lift the real cascade by about 24 dB and the headroom takes that off`() {
        for (count in listOf(GraphicEqBands.TEN_BAND_COUNT, GraphicEqBands.FIFTEEN_BAND_COUNT)) {
            val frequencies = GraphicEqBands.frequenciesFor(count)
            val bank = bank(48_000, frequencies, allBands(count, 15f))

            val measured = measuredLoudestDb(bank, 48_000, frequencies)

            // The audit measured +23.6 dB (ten bands) and +25.0 dB (fifteen) at 1 kHz; the loudest point is a
            // little higher. Far above the 15 dB a highest-slider estimate would have taken off.
            assertThat(measured).isGreaterThan(23.0)
            // A bound, never an estimate below the truth, and not wasteful about it.
            assertThat(bank.headroomDb.toDouble()).isAtLeast(measured)
            assertThat(bank.headroomDb.toDouble()).isAtMost(measured + 0.2)
            assertThat(db(bank.headroomGain)).isWithin(1e-3).of(-bank.headroomDb.toDouble())
        }
    }

    // ── What the headroom has to be right about ────────────────────────────────────────────────

    @Test
    fun `the headroom is the loudest point at the stream's own sample rate`() {
        // At a low rate the top bands are pulled below Nyquist (the 0.45 Fs limit of the design), so the same
        // curve does not peak where it does at 48 kHz.
        val frequencies = GraphicEqBands.TEN_BAND_HZ
        val gains = FloatArray(10) { index -> if (index >= 6) 12f else 3f }

        val seen = mutableListOf<Double>()
        for (rate in listOf(22_050, 32_000, 44_100, 48_000, 96_000, 192_000)) {
            val bank = bank(rate, frequencies, gains)
            val measured = measuredLoudestDb(bank, rate, frequencies)
            seen += measured
            assertThat(bank.headroomDb.toDouble()).isAtLeast(measured)
            assertThat(bank.headroomDb.toDouble()).isAtMost(measured + 0.2)
        }
        // The control that makes the rate matter: the loudest point really moved between rates.
        assertThat(seen.max() - seen.min()).isGreaterThan(0.5)
    }

    @Test
    fun `random curves never exceed the headroom and never waste more than two tenths of a dB`() {
        val random = Random(142)
        repeat(18) { round ->
            val count = if (round % 2 == 0) GraphicEqBands.TEN_BAND_COUNT else GraphicEqBands.FIFTEEN_BAND_COUNT
            val rate = listOf(44_100, 48_000, 96_000)[round % 3]
            val frequencies = GraphicEqBands.frequenciesFor(count)
            // Boost-heavy, so the curves that matter are the common case in this property.
            val gains = FloatArray(count) { random.nextInt(-6, 16).toFloat() }
            val bank = bank(rate, frequencies, gains)

            val measured = measuredLoudestDb(bank, rate, frequencies)

            if (measured <= 0.0) {
                assertThat(bank.headroomDb).isEqualTo(0f)
            } else {
                assertThat(bank.headroomDb.toDouble()).isAtLeast(measured)
                assertThat(bank.headroomDb.toDouble()).isAtMost(measured + 0.2)
            }
        }
    }

    @Test
    fun `a curve that only cuts needs no headroom`() {
        val frequencies = GraphicEqBands.TEN_BAND_HZ
        val bank = bank(48_000, frequencies, FloatArray(10) { if (it % 2 == 0) -9f else -3f })

        assertThat(bank.headroomDb).isEqualTo(0f)
        assertThat(bank.headroomGain).isEqualTo(1f)
    }

    @Test
    fun `a flat or bypassed bank has unity headroom and an unprepared one cannot fail`() {
        val flat = bank(48_000, GraphicEqBands.TEN_BAND_HZ, allBands(10, 0f))
        assertThat(flat.headroomGain).isEqualTo(1f)

        val unprepared = GraphicEqFilterBank()
        assertThat(unprepared.headroomGain).isEqualTo(1f)
        unprepared.prepare(0, 0)
        unprepared.apply(GraphicEqSettings(true, GraphicEqBands.TEN_BAND_HZ, allBands(10, 15f)))
        assertThat(unprepared.headroomGain).isEqualTo(1f)
    }

    // ── The headroom belongs to the snapshot being rendered ────────────────────────────────────

    @Test
    fun `the headroom is a function of the applied snapshot alone, so A to B to A does not ratchet`() {
        val frequencies = GraphicEqBands.TEN_BAND_HZ
        val a = GraphicEqSettings(true, frequencies, FloatArray(10) { if (it < 3) 12f else 0f })
        val b = GraphicEqSettings(true, frequencies, allBands(10, 15f))
        val flat = GraphicEqSettings(true, frequencies, allBands(10, 0f))

        val bank = GraphicEqFilterBank().also { it.prepare(48_000, 2) }
        bank.apply(a)
        val first = bank.headroomGain

        repeat(5) {
            bank.apply(b)
            assertThat(bank.headroomGain).isLessThan(first)
            bank.apply(flat)
            assertThat(bank.headroomGain).isEqualTo(1f)
            bank.apply(a)
            assertThat(bank.headroomGain).isEqualTo(first)
        }
    }

    @Test
    fun `a ten and a fifteen band curve each get their own headroom when the mode switches`() {
        val bank = GraphicEqFilterBank().also { it.prepare(48_000, 1) }
        bank.apply(GraphicEqSettings(true, GraphicEqBands.TEN_BAND_HZ, allBands(10, 15f)))
        val ten = bank.headroomDb
        bank.apply(GraphicEqSettings(true, GraphicEqBands.FIFTEEN_BAND_HZ, allBands(15, 15f)))
        val fifteen = bank.headroomDb
        bank.apply(GraphicEqSettings(true, GraphicEqBands.TEN_BAND_HZ, allBands(10, 15f)))

        assertThat(fifteen).isGreaterThan(ten)
        assertThat(bank.headroomDb).isEqualTo(ten)
    }

    @Test
    fun `every channel runs the same cascade so one headroom serves all of them`() {
        val bank = GraphicEqFilterBank().also {
            it.prepare(48_000, 2)
            it.apply(GraphicEqSettings(true, GraphicEqBands.TEN_BAND_HZ, allBands(10, 9f)))
        }
        val left = (0 until 5).map { bank.coefficientsForTest(0, it).toList() }
        val right = (0 until 5).map { bank.coefficientsForTest(1, it).toList() }

        assertThat(right).isEqualTo(left)
        assertThat(bank.headroomDb).isGreaterThan(0f)
    }

    // ── The estimator itself, against the analytic answer ──────────────────────────────────────

    @Test
    fun `a single band's headroom is its own boost, to the precision of the grid`() {
        for (gain in listOf(1f, 6f, 15f)) {
            val bank = bank(
                48_000,
                GraphicEqBands.TEN_BAND_HZ,
                FloatArray(10) { if (it == 5) gain else 0f },
            )
            // A peaking section reaches exactly its gain at its centre, and nowhere else.
            assertThat(bank.headroomDb.toDouble()).isAtLeast(gain.toDouble())
            assertThat(bank.headroomDb.toDouble()).isAtMost(gain + 0.12)
        }
    }
}
