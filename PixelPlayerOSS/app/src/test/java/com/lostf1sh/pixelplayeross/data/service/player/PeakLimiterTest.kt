package com.lostf1sh.pixelplayeross.data.service.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import com.decent.usbaudio.media3.DirectSignalMode
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.presentation.viewmodel.AudioStatus
import com.lostf1sh.pixelplayeross.presentation.viewmodel.FeatureStatus
import com.lostf1sh.pixelplayeross.presentation.viewmodel.limiterStatus
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.sin
import org.junit.jupiter.api.Test

/**
 * B40-F8: Resonance's peak limiter. The ceiling on a hard transient, the nothing-to-do case (bit for bit), the look-ahead, the way the
 * gain moves, and when the processor is in the chain at all.
 */
class PeakLimiterTest {
    private val nativeOrder = ByteOrder.nativeOrder()

    private fun limit(rate: Int, channels: Int, input: FloatArray, chunk: Int = input.size): FloatArray {
        val limiter = PeakLimiter(rate, channels)
        val out = ArrayList<Float>()
        val buffer = FloatArray(input.size + limiter.delayFrames * channels)
        var at = 0
        while (at < input.size) {
            val frames = minOf(chunk, input.size - at) / channels
            if (frames == 0) break
            val n = limiter.process(input, at, frames, buffer, 0)
            for (i in 0 until n * channels) out += buffer[i]
            at += frames * channels
        }
        val n = limiter.finish(buffer, 0)
        for (i in 0 until n * channels) out += buffer[i]
        return out.toFloatArray()
    }

    private fun peak(x: FloatArray) = x.maxOf { abs(it) }

    @Test
    fun `no sample leaves above the ceiling, whatever comes in - a burst, a single spike, a full-scale square`() {
        for (rate in listOf(44_100, 48_000, 96_000, 192_000)) {
            val limiter = PeakLimiter(rate, 2)
            val frames = rate // one second
            val x = FloatArray(frames * 2) { i -> (0.1 * sin(2.0 * PI * 440.0 * (i / 2) / rate)).toFloat() }
            // +12 dB over full scale for 200 frames on both channels, a +18 dB spike on one channel alone, a hard square wave
            for (f in 1_000 until 1_200) { x[f * 2] = 4f; x[f * 2 + 1] = -4f }
            x[5_000 * 2 + 1] = 8f
            for (f in 20_000 until 22_000) x[f * 2] = if ((f / 20) % 2 == 0) 3f else -3f
            val out = limit(rate, 2, x)
            assertThat(out.size).isEqualTo(x.size)
            println("MEASURED limiter $rate Hz: input peak ${"%.1f".format(peak(x))}, output peak ${"%.6f".format(peak(out))}, ceiling ${"%.6f".format(limiter.ceiling)}, look-ahead ${limiter.lookaheadFrames} frames")
            assertThat(peak(out)).isAtMost(limiter.ceiling + 1e-6f)
        }
    }

    @Test
    fun `a signal under the ceiling comes out bit for bit, and as long as it went in`() {
        val rng = java.util.Random(11)
        for (channels in listOf(1, 2, 6)) {
            val x = FloatArray(30_000 * channels) { (rng.nextGaussian() * 0.2).toFloat().coerceIn(-0.9f, 0.9f) }
            val out = limit(96_000, channels, x)
            assertThat(out.toList()).isEqualTo(x.toList())
        }
    }

    @Test
    fun `after it has worked, and given back its gain, the signal is the input's again bit for bit`() {
        val rate = 48_000
        val x = FloatArray(rate * 3) { (0.2 * sin(2.0 * PI * 330.0 * it / rate)).toFloat() }
        x[1_000] = 6f
        val out = limit(rate, 1, x)
        // far enough after the spike (a second and a half) the release has arrived
        for (i in rate + 20_000 until out.size) assertThat(out[i]).isEqualTo(x[i])
    }

    @Test
    fun `the gain starts falling before the peak, never at it, and is untouched a look-ahead earlier`() {
        val rate = 48_000
        val limiter = PeakLimiter(rate, 1)
        val l = limiter.lookaheadFrames
        val x = FloatArray(4_000) { 0.1f }
        val spike = 2_000
        x[spike] = 3f
        val out = limit(rate, 1, x)
        val gain = FloatArray(out.size) { out[it] / x[it] }
        // untouched until a window before the spike
        for (i in 0 until spike - l) assertThat(gain[i]).isEqualTo(1f)
        // falling inside the window, already down by half way
        assertThat(gain[spike - l + 1]).isLessThan(1f)
        assertThat(gain[spike - l / 2]).isLessThan(0.99f)
        // and the peak itself lands on the ceiling
        assertThat(abs(out[spike])).isWithin(1e-5f).of(limiter.ceiling)
    }

    @Test
    fun `the gain never jumps - no step between two samples is bigger than the fall over the look-ahead`() {
        val rate = 96_000
        val limiter = PeakLimiter(rate, 1)
        val x = FloatArray(30_000) { 0.1f }
        x[10_000] = 5f
        val out = limit(rate, 1, x)
        val gain = FloatArray(out.size) { out[it] / x[it] }
        val deepest = gain.min()
        var worst = 0f
        for (i in 1 until gain.size) worst = maxOf(worst, abs(gain[i] - gain[i - 1]))
        println("MEASURED limiter gain: deepest ${"%.4f".format(deepest)} (${"%.1f".format(20 * log10(deepest.toDouble()))} dB), largest step ${"%.6f".format(worst)}, look-ahead ${limiter.lookaheadFrames}")
        assertThat(worst).isAtMost((1f - deepest) / limiter.lookaheadFrames * 1.5f + 1e-4f)
    }

    @Test
    fun `it lets go slowly - ten milliseconds after a spike most of the gain reduction is still there, and it is gone after half a second`() {
        val rate = 48_000
        val x = FloatArray(rate) { 0.1f }
        x[2_000] = 4f
        val out = limit(rate, 1, x)
        val gain = FloatArray(out.size) { out[it] / x[it] }
        val deepest = gain.min()
        val tenMs = gain[2_000 + rate / 100]
        // recovery is exponential with an 80 ms time constant: after 10 ms about 12 % of the reduction has been given back
        val given = (tenMs - deepest) / (1f - deepest)
        assertThat(given).isLessThan(0.25f)
        assertThat(gain[2_000 + rate / 2]).isGreaterThan(0.995f)
        assertThat(exp(-10.0 / 80.0)).isGreaterThan(0.85)
    }

    @Test
    fun `the channels share one gain, so the image does not move`() {
        val rate = 48_000
        val x = FloatArray(6_000 * 2)
        for (f in 0 until 6_000) { x[f * 2] = 0.1f; x[f * 2 + 1] = 0.05f }
        x[3_000 * 2] = 5f // the left channel only
        val out = limit(rate, 2, x)
        for (f in 0 until 6_000) {
            val left = out[f * 2] / x[f * 2]
            val right = out[f * 2 + 1] / x[f * 2 + 1]
            assertThat(right).isWithin(1e-5f).of(left)
        }
        assertThat(out[3_000 * 2 + 1] / 0.05f).isLessThan(1f)
    }

    @Test
    fun `block sizes do not matter, bit for bit`() {
        val rng = java.util.Random(5)
        val x = FloatArray(2 * 20_000) { (rng.nextGaussian() * 0.5).toFloat() }
        val whole = limit(48_000, 2, x)
        for (chunk in listOf(2, 6, 14, 1_000, 4_096)) assertThat(limit(48_000, 2, x, chunk).toList()).isEqualTo(whole.toList())
    }

    @Test
    fun `a stream shorter than the look-ahead still comes out whole`() {
        val out = limit(48_000, 1, FloatArray(10) { 2f })
        assertThat(out).hasLength(10)
        assertThat(peak(out)).isAtMost(PeakLimiter(48_000, 1).ceiling + 1e-6f)
    }

    // -- the processor --

    private fun bytes16(s: ShortArray): ByteBuffer {
        val b = ByteBuffer.allocateDirect(s.size * 2).order(nativeOrder)
        s.forEach { b.putShort(it) }
        b.flip()
        return b
    }

    private fun shorts(b: ByteBuffer): List<Int> {
        val d = b.duplicate().order(nativeOrder)
        val out = ArrayList<Int>()
        while (d.remaining() >= 2) out += d.short.toInt()
        return out
    }

    @Test
    fun `off, the processor is not in the chain, adds nothing and says so`() {
        var running: Boolean? = null
        val p = PeakLimiterAudioProcessor(enabled = { false }, onRunning = { running = it })
        val format = AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT)
        assertThat(p.configure(format)).isEqualTo(format)
        assertThat(p.isActive()).isFalse()
        assertThat(running).isFalse()
    }

    @Test
    fun `on, it is in the chain with the format unchanged and says so`() {
        var running: Boolean? = null
        val p = PeakLimiterAudioProcessor(enabled = { true }, onRunning = { running = it })
        val format = AudioFormat(96_000, 2, C.ENCODING_PCM_16BIT)
        assertThat(p.configure(format)).isEqualTo(format)
        assertThat(p.isActive()).isTrue()
        assertThat(running).isTrue()
    }

    @Test
    fun `a stream it cannot limit is left out, and so is a switch that is on for nothing`() {
        val p = PeakLimiterAudioProcessor(enabled = { true })
        p.configure(AudioFormat(48_000, 2, C.ENCODING_PCM_24BIT))
        assertThat(p.isActive()).isFalse()
        p.configure(AudioFormat(48_000, 12, C.ENCODING_PCM_16BIT))
        assertThat(p.isActive()).isFalse()
    }

    @Test
    fun `the switch is asked again at a flush, so a seek or a new song picks it up, and a switch turned off leaves the chain`() {
        var on = false
        val seen = ArrayList<Boolean>()
        val p = PeakLimiterAudioProcessor(enabled = { on }, onRunning = { seen += it })
        p.configure(AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))
        assertThat(p.isActive()).isFalse()
        on = true
        assertThat(p.isActive()).isFalse() // not until the stream is flushed
        @Suppress("DEPRECATION")
        p.flush()
        assertThat(p.isActive()).isTrue()
        on = false
        @Suppress("DEPRECATION")
        p.flush()
        assertThat(p.isActive()).isFalse()
        assertThat(seen).containsExactly(false, true, false).inOrder()
    }

    @Test
    fun `a 16-bit stream under the ceiling is bit for bit the same, and one over it is held to it`() {
        val p = PeakLimiterAudioProcessor(enabled = { true })
        p.configure(AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))
        val rng = java.util.Random(2)
        val quiet = ShortArray(20_000) { (rng.nextGaussian() * 4_000).toInt().coerceIn(-20_000, 20_000).toShort() }
        val out = ArrayList<Int>()
        p.queueInput(bytes16(quiet))
        out += shorts(p.getOutput())
        p.queueEndOfStream()
        out += shorts(p.getOutput())
        assertThat(p.isEnded()).isTrue()
        assertThat(out).isEqualTo(quiet.map { it.toInt() })

        p.configure(AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))
        val loud = ShortArray(20_000) { if ((it / 40) % 2 == 0) 32767 else -32768 }
        val hot = ArrayList<Int>()
        p.queueInput(bytes16(loud))
        hot += shorts(p.getOutput())
        p.queueEndOfStream()
        hot += shorts(p.getOutput())
        assertThat(hot).hasSize(loud.size)
        val ceiling16 = PeakLimiter(48_000, 2).ceiling * 32768f
        assertThat(hot.maxOf { abs(it) }.toFloat()).isAtMost(ceiling16 + 1f)
    }

    // -- what the page says --

    private val android = AudioStatus()
    private val direct = AudioStatus(route = AudioRouteStates.directProcessed())

    @Test
    fun `the status line is the truth about the limiter`() {
        assertThat(android.limiterStatus(false)).isEqualTo(FeatureStatus.Off)
        assertThat(android.limiterStatus(true)).isEqualTo(FeatureStatus.LimiterNextSong)
        assertThat(android.copy(limiterRunning = true).limiterStatus(true)).isEqualTo(FeatureStatus.Applied)
        assertThat(direct.limiterStatus(true)).isEqualTo(FeatureStatus.LimiterAndroidOnly)
        assertThat(android.copy(maintenanceDspBypass = true, limiterRunning = true).limiterStatus(true))
            .isEqualTo(FeatureStatus.BypassedByMaintenance)
        // the lock freezes it, and off still wins over frozen
        val locked = AudioStatus(purityPreference = true, route = AudioRouteStates.directPristine(), sinkDirectActive = true)
        assertThat(locked.limiterStatus(true)).isEqualTo(FeatureStatus.Frozen)
        assertThat(locked.limiterStatus(false)).isEqualTo(FeatureStatus.Off)
    }

    // -- where it is, and where it is not --

    private val sources = listOf("src/main/java/com/lostf1sh/pixelplayeross", "app/src/main/java/com/lostf1sh/pixelplayeross")
        .map(::File).first { it.isDirectory }

    @Test
    fun `it is the last processor of the Android route's chain, after the equalizer, and nothing on the Direct path mentions it`() {
        val engine = File(sources, "data/service/player/DualPlayerEngine.kt").readText()
        val chain = engine.substringAfter("DefaultAudioSink.DefaultAudioProcessorChain(").substringBefore(".build()")
        val order = listOf("HiResSampleRateCapAudioProcessor", "SurroundDownmixProcessor", "HeadphoneGraphicEqProcessor", "PeakLimiterAudioProcessor")
            .map { chain.indexOf(it) }
        assertThat(order.none { it < 0 }).isTrue()
        assertThat(order).isInStrictOrder()

        // the Direct path: the stage that processes a Resonance stream on USB Direct, the signal source, and the driver library
        val direct = File(sources, "data/service/player/DirectSignalController.kt").readText()
        assertThat(direct).doesNotContain("PeakLimiter")
        val driver = File(sources, "../../../../../../../../decent-player").takeIf { it.isDirectory }
            ?: File("../../decent-player").takeIf { it.isDirectory }
            ?: File("../decent-player")
        if (driver.isDirectory) {
            val mentions = driver.walkTopDown().filter { it.extension == "kt" }.filter { "PeakLimiter" in it.readText() }.toList()
            assertThat(mentions).isEmpty()
        }
    }
}

/** The routes the status tests need, built once. */
private object AudioRouteStates {
    fun directProcessed() = com.lostf1sh.pixelplayeross.data.service.player.AudioRouteState(
        actualBackend = com.lostf1sh.pixelplayeross.data.service.player.AudioBackend.USB_DIRECT,
        transition = com.lostf1sh.pixelplayeross.data.service.player.AudioRouteTransition.DIRECT_ACTIVE,
        signalMode = DirectSignalMode.PROCESSED, usbDacPresent = true,
        reason = com.lostf1sh.pixelplayeross.data.service.player.AudioRouteReason.DIRECT_PROCESSED,
    )

    fun directPristine() = directProcessed().copy(
        signalMode = DirectSignalMode.PRISTINE,
        reason = com.lostf1sh.pixelplayeross.data.service.player.AudioRouteReason.DIRECT_PRISTINE,
    )
}
