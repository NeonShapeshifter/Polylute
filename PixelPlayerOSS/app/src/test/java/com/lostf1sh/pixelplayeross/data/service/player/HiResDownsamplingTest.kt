package com.lostf1sh.pixelplayeross.data.service.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import com.google.common.truth.Truth.assertThat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.jupiter.api.Test

/**
 * B40-F8: what the Android route's rate cap does to a signal, measured through the processor's own interface (configure, queueInput,
 * getOutput, queueEndOfStream), the way Media3 drives it.
 *
 * The first test is the one that fails against the averaging it replaced: a 180 kHz tone in a 384 kHz stream, folded to 12 kHz, was
 * 20 dB down. The requirement is 80; the design gives 100. Each tone below sits on a frequency whose whole number of cycles fits the
 * measuring window, so the amplitude read is exact (no window leakage between the tone and its neighbours).
 */
class HiResDownsamplingTest {

    private val nativeOrder = ByteOrder.nativeOrder()

    // -- driving the processor --

    private fun floatBuffer(samples: FloatArray, from: Int, count: Int): ByteBuffer {
        val b = ByteBuffer.allocateDirect(count * Float.SIZE_BYTES).order(nativeOrder)
        for (i in from until from + count) b.putFloat(samples[i])
        b.flip()
        return b
    }

    private fun shortBuffer(samples: ShortArray, from: Int, count: Int): ByteBuffer {
        val b = ByteBuffer.allocateDirect(count * Short.SIZE_BYTES).order(nativeOrder)
        for (i in from until from + count) b.putShort(samples[i])
        b.flip()
        return b
    }

    private fun drain(p: AudioProcessor, into: MutableList<Float>, float: Boolean) {
        val out = p.getOutput().duplicate().order(nativeOrder)
        if (float) while (out.remaining() >= 4) into += out.float else while (out.remaining() >= 2) into += out.short.toFloat()
    }

    /** The output of a float stream through a fresh processor, fed in pieces of [chunkSamples] (cycled) interleaved samples. */
    private fun runFloat(rate: Int, channels: Int, input: FloatArray, chunkSamples: IntArray = intArrayOf(input.size), end: Boolean = true): FloatArray {
        val p = HiResSampleRateCapAudioProcessor()
        p.configure(AudioFormat(rate, channels, C.ENCODING_PCM_FLOAT))
        val out = ArrayList<Float>()
        var at = 0
        var k = 0
        while (at < input.size) {
            val n = minOf(chunkSamples[k++ % chunkSamples.size], input.size - at)
            p.queueInput(floatBuffer(input, at, n))
            drain(p, out, true)
            at += n
        }
        if (end) {
            p.queueEndOfStream()
            drain(p, out, true)
            assertThat(p.isEnded()).isTrue()
        }
        return out.toFloatArray()
    }

    private fun run16(rate: Int, channels: Int, input: ShortArray, chunkSamples: IntArray = intArrayOf(input.size)): ShortArray {
        val p = HiResSampleRateCapAudioProcessor()
        p.configure(AudioFormat(rate, channels, C.ENCODING_PCM_16BIT))
        val out = ArrayList<Float>()
        var at = 0
        var k = 0
        while (at < input.size) {
            val n = minOf(chunkSamples[k++ % chunkSamples.size], input.size - at)
            p.queueInput(shortBuffer(input, at, n))
            drain(p, out, false)
            at += n
        }
        p.queueEndOfStream()
        drain(p, out, false)
        return ShortArray(out.size) { out[it].toInt().toShort() }
    }

    // -- measuring --

    private fun tone(rate: Int, hz: Double, amplitude: Double, frames: Int, channels: Int = 1): FloatArray =
        FloatArray(frames * channels) { i -> (amplitude * sin(2.0 * PI * hz * (i / channels) / rate)).toFloat() }

    /** The amplitude of the component at [hz] in [x] (mono samples [from] until [to]), exact when a whole number of cycles fits. */
    private fun amplitudeAt(x: FloatArray, hz: Double, rate: Int, from: Int, to: Int, channels: Int = 1, channel: Int = 0): Double {
        var re = 0.0
        var im = 0.0
        val n = to - from
        for (i in from until to) {
            val phase = 2.0 * PI * hz * i / rate
            val v = x[i * channels + channel].toDouble()
            re += v * cos(phase)
            im += v * sin(phase)
        }
        return 2.0 * sqrt(re * re + im * im) / n
    }

    private fun db(ratio: Double) = 20.0 * log10(ratio)

    /** Output frames used for a measurement: 19,200 at the output rate = 100 ms, so every multiple of 10 Hz is bin-exact. */
    private val windowFrames = 19_200

    private fun settleFrames(rate: Int, factor: Int) = DecimationFilter.taps(rate, factor).size / factor + 4

    /** Rejection in dB (negative) of an [inputHz] tone, read at the frequency it folds to. */
    private fun rejectionDb(inputRate: Int, factor: Int, inputHz: Double): Double {
        val outRate = inputRate / factor
        val settle = settleFrames(inputRate, factor)
        val outFrames = windowFrames + 2 * settle
        val input = tone(inputRate, inputHz, 0.25, outFrames * factor)
        val out = runFloat(inputRate, 1, input)
        assertThat(out.size).isEqualTo(outFrames)
        // where an input frequency above the output Nyquist frequency lands in the output stream
        var alias = inputHz % outRate
        if (alias > outRate / 2.0) alias = outRate - alias
        return db(amplitudeAt(out, alias, outRate, settle, settle + windowFrames) / 0.25)
    }

    @Test
    fun `a 180 kHz tone in a 384 kHz stream is rejected by more than 80 dB, where averaging let it through at 20`() {
        val rejection = rejectionDb(384_000, 2, 180_000.0)
        println("MEASURED 384k->192k 180 kHz tone folds to 12 kHz at ${"%.1f".format(rejection)} dB")
        assertThat(rejection).isLessThan(-80.0)
    }

    @Test
    fun `everything above the new Nyquist frequency is rejected by more than 80 dB, 384 to 192`() {
        val worst = listOf(96_500.0, 100_000.0, 120_000.0, 150_000.0, 170_000.0, 180_000.0, 190_000.0)
            .map { it to rejectionDb(384_000, 2, it) }
        println("MEASURED 384k->192k rejection: " + worst.joinToString { "${it.first / 1000} kHz ${"%.1f".format(it.second)} dB" })
        worst.forEach { (hz, r) -> assertThat(r).isLessThan(-80.0) }
    }

    @Test
    fun `and by a factor of four, 768 to 192 and 705_6 to 176_4`() {
        val a = listOf(96_500.0, 130_000.0, 180_000.0, 250_000.0, 300_000.0, 380_000.0).map { it to rejectionDb(768_000, 4, it) }
        println("MEASURED 768k->192k rejection: " + a.joinToString { "${it.first / 1000} kHz ${"%.1f".format(it.second)} dB" })
        a.forEach { (_, r) -> assertThat(r).isLessThan(-80.0) }
        val b = listOf(90_000.0, 120_000.0, 160_000.0, 340_000.0).map { it to rejectionDb(705_600, 4, it) }
        println("MEASURED 705.6k->176.4k rejection: " + b.joinToString { "${it.first / 1000} kHz ${"%.1f".format(it.second)} dB" })
        b.forEach { (_, r) -> assertThat(r).isLessThan(-80.0) }
    }

    @Test
    fun `352_8 to 176_4`() {
        val r = listOf(89_000.0, 120_000.0, 170_000.0).map { it to rejectionDb(352_800, 2, it) }
        println("MEASURED 352.8k->176.4k rejection: " + r.joinToString { "${it.first / 1000} kHz ${"%.1f".format(it.second)} dB" })
        r.forEach { (_, d) -> assertThat(d).isLessThan(-80.0) }
    }

    @Test
    fun `the passband is flat to a thousandth of a decibel up to 80 percent of the new Nyquist frequency`() {
        val checks = listOf(384_000 to 2, 768_000 to 4)
        val report = ArrayList<String>()
        for ((rate, factor) in checks) {
            val outRate = rate / factor
            val settle = settleFrames(rate, factor)
            val outFrames = windowFrames + 2 * settle
            for (hz in listOf(100.0, 1_000.0, 10_000.0, 20_000.0, 40_000.0, 60_000.0, 70_000.0, 76_000.0)) {
                if (hz > 0.8 * outRate / 2.0) continue
                val out = runFloat(rate, 1, tone(rate, hz, 0.25, outFrames * factor))
                val gain = db(amplitudeAt(out, hz, outRate, settle, settle + windowFrames) / 0.25)
                report += "$rate/$factor ${hz / 1000} kHz ${"%.5f".format(gain)} dB"
                assertThat(abs(gain)).isLessThan(0.001)
            }
        }
        println("MEASURED passband: " + report.joinToString("; "))
    }

    @Test
    fun `the design itself - symmetric, odd, unity at DC, 100 dB down from the Nyquist frequency on, flat in the passband`() {
        for ((rate, factor) in listOf(384_000 to 2, 352_800 to 2, 768_000 to 4, 705_600 to 4, 576_000 to 3)) {
            val taps = DecimationFilter.taps(rate, factor)
            assertThat(taps.size % 2).isEqualTo(1)
            for (i in taps.indices) assertThat(taps[i]).isEqualTo(taps[taps.size - 1 - i])
            assertThat(taps.sumOf { it.toDouble() }).isWithin(1e-6).of(1.0)
            val nyquist = rate / (2.0 * factor)
            var worstStop = -1000.0
            var f = nyquist
            while (f <= rate / 2.0) {
                worstStop = maxOf(worstStop, db(DecimationFilter.gainAt(taps, f, rate)))
                f += rate / 4000.0
            }
            var worstRipple = 0.0
            f = 10.0
            while (f <= 0.8 * nyquist) {
                worstRipple = maxOf(worstRipple, abs(db(DecimationFilter.gainAt(taps, f, rate))))
                f += rate / 4000.0
            }
            println("DESIGN $rate/$factor: ${taps.size} taps, stopband worst ${"%.1f".format(worstStop)} dB, passband ripple ${"%.6f".format(worstRipple)} dB")
            assertThat(worstStop).isLessThan(-95.0)
            assertThat(worstRipple).isLessThan(0.0005)
        }
    }

    @Test
    fun `a constant stays that constant`() {
        val rate = 384_000
        val out = runFloat(rate, 1, FloatArray(20_000) { 0.5f })
        val settle = settleFrames(rate, 2)
        for (i in settle until out.size - settle) assertThat(out[i]).isWithin(2e-6f).of(0.5f)
    }

    @Test
    fun `the output is in step with the input, the filter's delay is taken out`() {
        val rate = 384_000
        val factor = 2
        val hz = 1_000.0
        val out = runFloat(rate, 1, tone(rate, hz, 0.5, 40_000))
        val settle = settleFrames(rate, factor)
        var worst = 0.0
        for (k in settle until out.size - settle) {
            val expected = 0.5 * sin(2.0 * PI * hz * (k * factor) / rate)
            worst = maxOf(worst, abs(out[k] - expected))
        }
        println("MEASURED alignment: worst difference from the ideal 1 kHz sine ${"%.2e".format(worst)}")
        assertThat(worst).isLessThan(2e-5)
    }

    @Test
    fun `an impulse comes out centred where it went in`() {
        val rate = 384_000
        val input = FloatArray(6_000)
        input[3_000] = 1f
        val out = runFloat(rate, 1, input)
        assertThat(out.size).isEqualTo(3_000)
        val peak = out.indices.maxByOrNull { abs(out[it]) }!!
        assertThat(peak).isEqualTo(1_500)
        // and the response is the design's own, symmetric about it
        val taps = DecimationFilter.taps(rate, 2)
        assertThat(out[1_500]).isWithin(1e-6f).of(taps[(taps.size - 1) / 2])
        for (d in 1..40) assertThat(out[1_500 - d]).isWithin(1e-7f).of(out[1_500 + d])
    }

    @Test
    fun `the length is the input divided by the factor, a partial group at the end dropped`() {
        for ((frames, expected) in listOf(10_000 to 5_000, 10_001 to 5_000, 9_999 to 4_999, 1 to 0, 2 to 1)) {
            val out = runFloat(384_000, 1, FloatArray(frames) { 0.1f })
            assertThat(out.size).isEqualTo(expected)
        }
    }

    @Test
    fun `block sizes do not matter, float and 16-bit, stereo, bit for bit`() {
        val rate = 384_000
        val rng = java.util.Random(7)
        val f = FloatArray(2 * 30_001) { (rng.nextGaussian() * 0.2).toFloat().coerceIn(-1f, 1f) }
        val whole = runFloat(rate, 2, f)
        for (chunks in listOf(intArrayOf(2), intArrayOf(2, 4, 6), intArrayOf(1, 3, 7, 13), intArrayOf(999, 1, 4096), intArrayOf(65_536))) {
            assertThat(runFloat(rate, 2, f, chunks).toList()).isEqualTo(whole.toList())
        }
        val s = ShortArray(2 * 30_001) { (rng.nextGaussian() * 6_000).toInt().coerceIn(-32768, 32767).toShort() }
        val whole16 = run16(rate, 2, s)
        for (chunks in listOf(intArrayOf(1), intArrayOf(3, 5), intArrayOf(2, 4, 6), intArrayOf(777, 31), intArrayOf(100_000))) {
            assertThat(run16(rate, 2, s, chunks).toList()).isEqualTo(whole16.toList())
        }
    }

    @Test
    fun `a flush starts a new stream, the same as a fresh processor`() {
        val rate = 384_000
        val rng = java.util.Random(3)
        val a = FloatArray(8_000) { (rng.nextGaussian() * 0.2).toFloat() }
        val b = FloatArray(8_000) { (rng.nextGaussian() * 0.2).toFloat() }
        val p = HiResSampleRateCapAudioProcessor()
        p.configure(AudioFormat(rate, 1, C.ENCODING_PCM_FLOAT))
        p.queueInput(floatBuffer(a, 0, a.size))
        p.getOutput()
        @Suppress("DEPRECATION")
        p.flush()
        p.queueInput(floatBuffer(b, 0, b.size))
        val out = ArrayList<Float>()
        drain(p, out, true)
        p.queueEndOfStream()
        drain(p, out, true)
        assertThat(out.toList()).isEqualTo(runFloat(rate, 1, b).toList())
    }

    @Test
    fun `16-bit stays 16-bit - the same tone, the same rejection, and overshoot is clamped, never wrapped`() {
        val rate = 384_000
        val settle = settleFrames(rate, 2)
        val outFrames = windowFrames + 2 * settle
        val src = tone(rate, 180_000.0, 0.9, outFrames * 2)
        val out = run16(rate, 1, ShortArray(src.size) { Math.round(src[it] * 32768f).coerceIn(-32768, 32767).toShort() })
        val asFloat = FloatArray(out.size) { out[it] / 32768f }
        assertThat(db(amplitudeAt(asFloat, 12_000.0, 192_000, settle, settle + windowFrames) / 0.9)).isLessThan(-80.0)

        // a full-scale 8 kHz square wave overshoots after the low-pass; it must saturate, not wrap around
        val square = ShortArray(40_000) { if ((it / 24) % 2 == 0) 32767 else -32768 }
        val squareOut = run16(rate, 1, square)
        assertThat(squareOut.max().toInt()).isEqualTo(32767)
        assertThat(squareOut.min().toInt()).isEqualTo(-32768)
        // in the middle of each half period the sign is the square wave's own and the level is high: a wrapped overshoot would flip it
        for (k in 200 until 1_800) {
            val phase = (k * 2) % 48
            if (phase in 8..15) assertThat(squareOut[k].toInt()).isGreaterThan(20_000)
            if (phase in 32..39) assertThat(squareOut[k].toInt()).isLessThan(-20_000)
        }
    }

    @Test
    fun `float streams are held to full scale`() {
        val rate = 384_000
        val out = runFloat(rate, 1, FloatArray(40_000) { if ((it / 24) % 2 == 0) 1f else -1f })
        assertThat(out.max()).isAtMost(1f)
        assertThat(out.min()).isAtLeast(-1f)
        assertThat(out.max()).isEqualTo(1f)
    }

    @Test
    fun `channels stay apart - a tone on one channel of five-point-one does not leak into the others`() {
        val rate = 384_000
        val channels = 6
        val frames = 16_000
        val input = FloatArray(frames * channels)
        val t = tone(rate, 10_000.0, 0.4, frames)
        for (i in 0 until frames) input[i * channels + 3] = t[i]
        val out = runFloat(rate, channels, input)
        assertThat(out.size).isEqualTo(frames / 2 * channels)
        for (ch in 0 until channels) {
            if (ch == 3) continue
            for (i in ch until out.size step channels) assertThat(out[i]).isEqualTo(0f)
        }
        assertThat(out.slice(0 until out.size).maxOrNull()!!).isWithin(0.01f).of(0.4f)
    }

    // -- when it does nothing --

    @Test
    fun `a stream at or under 192 kHz is left alone, as is anything that is not 16-bit or float`() {
        val p = HiResSampleRateCapAudioProcessor()
        val ok = AudioFormat(192_000, 2, C.ENCODING_PCM_16BIT)
        assertThat(p.configure(ok)).isEqualTo(ok)
        assertThat(p.isActive()).isFalse()
        val twentyFour = AudioFormat(384_000, 2, C.ENCODING_PCM_24BIT)
        assertThat(p.configure(twentyFour)).isEqualTo(twentyFour)
        assertThat(p.isActive()).isFalse()
        val out = p.configure(AudioFormat(705_600, 2, C.ENCODING_PCM_16BIT))
        assertThat(p.isActive()).isTrue()
        assertThat(out.sampleRate).isEqualTo(176_400)
        assertThat(out.channelCount).isEqualTo(2)
        assertThat(out.encoding).isEqualTo(C.ENCODING_PCM_16BIT)
        assertThat(p.configure(AudioFormat(384_000, 2, C.ENCODING_PCM_FLOAT)).sampleRate).isEqualTo(192_000)
        assertThat(p.configure(AudioFormat(768_000, 2, C.ENCODING_PCM_FLOAT)).sampleRate).isEqualTo(192_000)
        assertThat(p.configure(AudioFormat(352_800, 2, C.ENCODING_PCM_16BIT)).sampleRate).isEqualTo(176_400)
    }

    @Test
    fun `how much it costs - taps per output frame and the host's time for ten seconds of 384 kHz stereo`() {
        val taps2 = DecimationFilter.taps(384_000, 2).size
        val taps4 = DecimationFilter.taps(768_000, 4).size
        val input = FloatArray(384_000 * 2 * 10) { (sin(it * 0.01) * 0.3).toFloat() }
        val chunk = intArrayOf(4096)
        runFloat(384_000, 2, input.copyOf(384_000 * 2), chunk) // warm up
        val t0 = System.nanoTime()
        runFloat(384_000, 2, input, chunk)
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("COST 384k stereo: $taps2 taps (${taps2 / 2 + 1} multiplications per output sample with the symmetry), 10 s of audio in $ms ms on the host; 768k: $taps4 taps")
        assertThat(taps2).isLessThan(140)
        assertThat(taps4).isLessThan(270)
    }
}
