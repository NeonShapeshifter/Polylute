package com.lostf1sh.pixelplayeross.data.service.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.audio.dsp.GraphicEqBands
import com.lostf1sh.pixelplayeross.data.audio.dsp.GraphicEqFilterBank
import com.lostf1sh.pixelplayeross.data.audio.dsp.GraphicEqSettings
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.jupiter.api.Test

/**
 * B14-2, audit A-01 and A-02, through the two real paths a boosted EQ plays on.
 *
 * The audit's probe: a bank with every band at +15 dB, a 1 kHz sine at half of full scale. Run through the
 * cascade alone it peaks at 7.55 (ten bands) and 8.85 (fifteen), so whatever is applied afterwards, the signal
 * has already been flattened by the clamp that follows the filter; the factor the player applied after the
 * processor (−15 dB) took the 7.55 to 1.34 on a signal that was already clipped. What is measured here is the
 * samples that leave the EQ: none may reach full scale, on the Android processor (PCM16 and float) and on the
 * Direct stage, and a full-scale sine of any frequency may not come out louder than it went in.
 */
class EqHeadroomPathsTest {

    private val rate = 48_000

    private fun settings(count: Int, gain: Float) = GraphicEqSettings(
        enabled = true,
        frequenciesHz = GraphicEqBands.frequenciesFor(count),
        gainsDb = FloatArray(count) { gain },
    )

    private fun sine(frames: Int, hz: Double, amplitude: Double, sampleRateHz: Int = rate) =
        FloatArray(frames) { (amplitude * sin(2.0 * PI * hz * it / sampleRateHz)).toFloat() }

    // ── The three paths, one sample array in, one out ──────────────────────────────────────────

    private fun androidFloat(settings: GraphicEqSettings, input: FloatArray, sampleRateHz: Int = rate): FloatArray {
        val processor = HeadphoneGraphicEqProcessor { settings }
        processor.configure(AudioFormat(sampleRateHz, 1, C.ENCODING_PCM_FLOAT))
        val output = FloatArray(input.size)
        var written = 0
        for (start in input.indices step CHUNK) {
            val frames = minOf(CHUNK, input.size - start)
            val buffer = ByteBuffer.allocateDirect(frames * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
            for (i in 0 until frames) buffer.putFloat(input[start + i])
            buffer.flip()
            processor.queueInput(buffer)
            val out = processor.getOutput().order(ByteOrder.nativeOrder())
            while (out.remaining() >= Float.SIZE_BYTES) output[written++] = out.getFloat()
        }
        assertThat(written).isEqualTo(input.size)
        return output
    }

    private fun androidPcm16(settings: GraphicEqSettings, input: ShortArray, sampleRateHz: Int = rate): ShortArray {
        val processor = HeadphoneGraphicEqProcessor { settings }
        processor.configure(AudioFormat(sampleRateHz, 1, C.ENCODING_PCM_16BIT))
        val output = ShortArray(input.size)
        var written = 0
        for (start in input.indices step CHUNK) {
            val frames = minOf(CHUNK, input.size - start)
            val buffer = ByteBuffer.allocateDirect(frames * Short.SIZE_BYTES).order(ByteOrder.nativeOrder())
            for (i in 0 until frames) buffer.putShort(input[start + i])
            buffer.flip()
            processor.queueInput(buffer)
            val out = processor.getOutput().order(ByteOrder.nativeOrder())
            while (out.remaining() >= Short.SIZE_BYTES) output[written++] = out.getShort()
        }
        assertThat(written).isEqualTo(input.size)
        return output
    }

    private fun direct(
        settings: GraphicEqSettings,
        input: FloatArray,
        volume: Float = 1f,
        sampleRateHz: Int = rate,
    ): FloatArray {
        val stage = DirectPcmStageImpl(
            requirements = { AudioProcessingRequirements(graphicEq = true) },
            graphicEq = { settings },
            masterVolume = { volume },
        )
        assertThat(stage.configure(sampleRateHz, 1)).isTrue()
        val output = input.copyOf()
        for (start in output.indices step CHUNK) {
            val frames = minOf(CHUNK, output.size - start)
            val block = output.copyOfRange(start, start + frames)
            stage.process(block, frames)
            block.copyInto(output, start)
        }
        return output
    }

    /** What the cascade alone does, with no headroom and no clamp: the behaviour this batch removed. */
    private fun rawCascade(settings: GraphicEqSettings, input: FloatArray): FloatArray {
        val bank = GraphicEqFilterBank().also {
            it.prepare(rate, 1)
            it.apply(settings)
        }
        return FloatArray(input.size) { bank.processSample(0, input[it]) }
    }

    private fun peakAfter(samples: FloatArray, from: Int) =
        (from until samples.size).maxOf { abs(samples[it].toDouble()) }

    private fun db(amplitude: Double) = 20.0 * log10(amplitude)

    // ── The audit's probe ──────────────────────────────────────────────────────────────────────

    @Test
    fun `the audit's probe is not vacuous - the bare cascade really peaks at 7_55 and 8_85`() {
        val probe = sine(192_000, 1_000.0, 0.5)

        // The numbers the audit measured on the compiled bank (peak after the first 48000 samples).
        assertThat(peakAfter(rawCascade(settings(10, 15f), probe), 48_000)).isWithin(0.02).of(7.554292)
        assertThat(peakAfter(rawCascade(settings(15, 15f), probe), 48_000)).isWithin(0.02).of(8.853197)
    }

    @Test
    fun `Android PCM16 - all bands at +15 dB, a half scale sine, not one sample reaches full scale`() {
        for (count in listOf(10, 15)) {
            val input = ShortArray(192_000) { (0.5 * sin(2.0 * PI * 1_000.0 * it / rate) * 32768.0).roundToInt().toShort() }

            val output = androidPcm16(settings(count, 15f), input)

            val saturated = (48_000 until output.size).count { output[it] >= Short.MAX_VALUE || output[it] <= Short.MIN_VALUE }
            assertThat(saturated).isEqualTo(0)
            // And it is the EQ's own boost that was held back, not the signal that was lost: the 1 kHz tone
            // leaves at about the level it came in, because that is the cascade's loudest region.
            val peak = (48_000 until output.size).maxOf { abs(output[it].toInt()) } / 32768.0
            assertThat(db(peak) - db(0.5)).isWithin(1.0).of(0.0)
        }
    }

    @Test
    fun `Android float - all bands at +15 dB, a half scale sine, not one sample reaches full scale`() {
        for (count in listOf(10, 15)) {
            val output = androidFloat(settings(count, 15f), sine(192_000, 1_000.0, 0.5))

            // The processor clamps float to full scale; a clipped sample would be exactly ±1.
            assertThat((48_000 until output.size).count { abs(output[it]) >= 1f }).isEqualTo(0)
            assertThat(peakAfter(output, 48_000)).isLessThan(0.5 * 1.15)
        }
    }

    @Test
    fun `Direct stage - all bands at +15 dB, a half scale sine, not one sample reaches full scale`() {
        for (count in listOf(10, 15)) {
            val output = direct(settings(count, 15f), sine(192_000, 1_000.0, 0.5))

            assertThat((48_000 until output.size).count { abs(output[it]) >= 1f }).isEqualTo(0)
            assertThat(peakAfter(output, 48_000)).isLessThan(0.5 * 1.15)
        }
    }

    // ── The final level against the flat reference ─────────────────────────────────────────────

    /** Band centres and a log grid, from 100 Hz (below it a boosted section needs seconds to settle). */
    private fun sweep(count: Int) = com.lostf1sh.pixelplayeross.data.audio.dsp.DspMeasurement.sweepHz(
        fromHz = 100.0,
        toHz = 0.45 * rate,
        points = 28,
        extraHz = GraphicEqBands.frequenciesFor(count).map { it.toDouble() }.toDoubleArray(),
    )

    private fun loudestOfSweep(
        count: Int,
        path: (FloatArray) -> FloatArray,
    ): Double {
        var loudest = 0.0
        for (hz in sweep(count)) {
            // Full scale in: the flat reference leaves at exactly 1.0.
            val output = path(sine(rate * 2 + rate / 4, hz, 1.0))
            loudest = maxOf(loudest, peakAfter(output, rate * 2))
        }
        return loudest
    }

    @Test
    fun `no sine of any frequency leaves the Android float path louder than it came in, and the loudest one is held at the flat level`() {
        for (count in listOf(10, 15)) {
            val gains = settings(count, 15f)

            val loudest = loudestOfSweep(count) { androidFloat(gains, it) }

            // Not above the flat reference (the clamp would hide it, but the clamp is ±1: so compare with a
            // hair under it, which a clipped sample cannot be)...
            assertThat(loudest).isLessThan(1.0)
            // ...and the headroom is not a blanket cut: the cascade's loudest frequency is held at about 0 dB.
            assertThat(db(loudest)).isWithin(0.5).of(0.0)
        }
    }

    @Test
    fun `no sine of any frequency leaves the Direct stage louder than it came in, and the loudest one is held at the flat level`() {
        for (count in listOf(10, 15)) {
            val gains = settings(count, 15f)

            val loudest = loudestOfSweep(count) { direct(gains, it) }

            assertThat(loudest).isLessThan(1.0)
            assertThat(db(loudest)).isWithin(0.5).of(0.0)
        }
    }

    @Test
    fun `a mixed curve is held the same way on both paths`() {
        // Boosts and cuts interleaved: the loudest point is not at a slider's own centre.
        val frequencies = GraphicEqBands.TEN_BAND_HZ
        val mixed = GraphicEqSettings(true, frequencies, floatArrayOf(-6f, 3f, 12f, 9f, 6f, 15f, 9f, -3f, 12f, 6f))

        for (path in listOf<(FloatArray) -> FloatArray>({ androidFloat(mixed, it) }, { direct(mixed, it) })) {
            val loudest = loudestOfSweep(10, path)
            assertThat(loudest).isLessThan(1.0)
            assertThat(db(loudest)).isWithin(0.5).of(0.0)
        }
    }

    // ── A-02: the take-off is ahead of the clamp ───────────────────────────────────────────────

    @Test
    fun `a loud tone on a boosted band is not flat-topped - the headroom comes off before the PCM16 clamp`() {
        // 0.9 of full scale on a +12 dB band: the cascade alone would lift it to ~3.6 and the clamp that follows
        // it would flatten the top; attenuating afterwards (what the player's volume did) cannot undo that.
        val boosted = GraphicEqSettings(
            true,
            GraphicEqBands.TEN_BAND_HZ,
            FloatArray(10) { if (it == 5) 12f else 0f },
        )
        val input = ShortArray(rate * 2) { (0.9 * sin(2.0 * PI * 1_000.0 * it / rate) * 32768.0).roundToInt().toShort() }
        assertThat(peakAfter(rawCascade(boosted, FloatArray(rate * 2) { input[it] / 32768f }), rate)).isGreaterThan(2.0)

        val output = androidPcm16(boosted, input)

        val settled = (rate until output.size)
        assertThat(settled.count { output[it] >= Short.MAX_VALUE || output[it] <= Short.MIN_VALUE }).isEqualTo(0)
        // A sine, not a trapezoid: its peak is the input's, and what is left after fitting a 1 kHz sine to it (the
        // distortion a clamp would have written into it) is negligible.
        val peak = settled.maxOf { abs(output[it].toInt()) }
        assertThat(peak / 32768.0).isWithin(0.02).of(0.9)
        assertThat(distortionAgainstFittedSine(DoubleArray(settled.count()) { output[rate + it] / 32768.0 }, 1_000.0))
            .isLessThan(0.005)

        // The control that gives the number meaning: the old order, clamp first, is far above it.
        val clampedFirst = rawCascade(boosted, FloatArray(rate * 2) { input[it] / 32768f })
            .map { it.coerceIn(-1f, 1f).toDouble() }.drop(rate).toDoubleArray()
        assertThat(distortionAgainstFittedSine(clampedFirst, 1_000.0)).isGreaterThan(0.1)
    }

    /** RMS of what is left after the best-fit sine at [hz] is taken out, relative to that sine's RMS. */
    private fun distortionAgainstFittedSine(samples: DoubleArray, hz: Double): Double {
        var inPhase = 0.0
        var quadrature = 0.0
        for (i in samples.indices) {
            val angle = 2.0 * PI * hz * (rate + i) / rate
            inPhase += samples[i] * sin(angle)
            quadrature += samples[i] * kotlin.math.cos(angle)
        }
        inPhase *= 2.0 / samples.size
        quadrature *= 2.0 / samples.size
        var residual = 0.0
        for (i in samples.indices) {
            val angle = 2.0 * PI * hz * (rate + i) / rate
            val error = samples[i] - (inPhase * sin(angle) + quadrature * kotlin.math.cos(angle))
            residual += error * error
        }
        val fundamentalRms = kotlin.math.hypot(inPhase, quadrature) / kotlin.math.sqrt(2.0)
        return kotlin.math.sqrt(residual / samples.size) / fundamentalRms
    }

    // ── The two paths do the same thing ────────────────────────────────────────────────────────

    @Test
    fun `the Android float path and the Direct stage produce the same samples`() {
        val gains = settings(15, 12f)
        val input = FloatArray(48_000) { index ->
            (0.2 * sin(2.0 * PI * 440.0 * index / rate) + 0.1 * sin(2.0 * PI * 3_100.0 * index / rate) +
                0.05 * sin(2.0 * PI * 40.0 * index / rate)).toFloat()
        }

        val android = androidFloat(gains, input)
        val stage = direct(gains, input)

        for (index in input.indices) assertThat(stage[index]).isWithin(1e-6f).of(android[index])
    }

    @Test
    fun `the listener's volume composes after the headroom and does not undo it`() {
        val gains = settings(10, 15f)
        val input = sine(rate, 1_000.0, 0.5)

        val full = direct(gains, input, volume = 1f)
        val half = direct(gains, input, volume = 0.5f)

        for (index in input.indices) assertThat(half[index]).isWithin(1e-6f).of(0.5f * full[index])
    }

    // ── What must not change ───────────────────────────────────────────────────────────────────

    @Test
    fun `a flat or cut-only EQ gets no headroom on either path`() {
        val cuts = GraphicEqSettings(true, GraphicEqBands.TEN_BAND_HZ, FloatArray(10) { -6f })
        val input = sine(rate, 5_000.0, 0.5)   // far from the cuts' centres: a cut only ever lowers it

        // 5 kHz sits between the 4 kHz and 8 kHz cuts, and every cut lowers it a little: never above the input.
        assertThat(peakAfter(androidFloat(cuts, input), rate / 2)).isAtMost(0.5 + 1e-4)
        assertThat(peakAfter(direct(cuts, input), rate / 2)).isAtMost(0.5 + 1e-4)

        val bank = GraphicEqFilterBank().also { it.prepare(rate, 1); it.apply(cuts) }
        assertThat(bank.headroomGain).isEqualTo(1f)
    }

    private companion object {
        const val CHUNK = 4_800
    }
}
