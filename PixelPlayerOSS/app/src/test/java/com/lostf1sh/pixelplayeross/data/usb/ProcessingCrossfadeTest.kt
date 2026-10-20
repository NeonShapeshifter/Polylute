package com.lostf1sh.pixelplayeross.data.usb

import com.decent.usbaudio.media3.DirectPcmStage
import com.decent.usbaudio.media3.DirectProcessing
import com.decent.usbaudio.media3.PipelineThread
import com.decent.usbaudio.media3.ProcessingCrossfade
import com.google.common.truth.Truth.assertThat
import kotlin.math.abs
import kotlin.math.sin
import org.junit.jupiter.api.Test

/**
 * B9-3: a PRISTINE ↔ PROCESSED change with the pipeline as the producer on both sides keeps the thread and
 * its queue, and joins the processing to what is already queued by a short crossfade instead of a step.
 * The policy that says when that happens, and the blend itself.
 */
class ProcessingCrossfadeTest {

    /** A stage that is easy to reason about: every sample scaled, and a reading of how often it ran. */
    private class GainStage(private val gain: Float) : DirectPcmStage {
        var calls = 0
        override fun configure(sampleRate: Int, channelCount: Int) = true
        override fun process(samples: FloatArray, frameCount: Int) {
            calls++
            for (i in 0 until frameCount * 2) samples[i] *= gain
        }
        override fun activeProcessing() = setOf(DirectProcessing.EQUALIZER)
        override fun reset() {}
    }

    private val channels = 2

    /** 1 kHz at 48 kHz, starting at its peak (frame 12 of 48 per cycle) so the first sample is not a zero crossing. */
    private fun tone(frames: Int, from: Int = 12): FloatArray {
        val out = FloatArray(frames * channels)
        for (f in 0 until frames) {
            val v = (0.5 * sin(2.0 * Math.PI * 1000.0 * (from + f) / 48_000.0)).toFloat()
            out[f * channels] = v
            out[f * channels + 1] = -v
        }
        return out
    }

    // ── the policy ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a mode change keeps the pipeline only when the pipeline produces before and after`() {
        val keep = PipelineThread.RealignAction.KEEP_PIPELINE
        val move = PipelineThread.RealignAction.MOVE_PRODUCER
        // The debug switch on (or a file the engine does not play): no engine now, none wanted, a thread running.
        assertThat(PipelineThread.realignFor(engineExists = false, threadRunning = true, wantsEngine = false)).isEqualTo(keep)
        // An engine plays it now (PRISTINE FLAC, switch off), or is about to take over: producers really change.
        assertThat(PipelineThread.realignFor(engineExists = true, threadRunning = false, wantsEngine = false)).isEqualTo(move)
        assertThat(PipelineThread.realignFor(engineExists = false, threadRunning = true, wantsEngine = true)).isEqualTo(move)
        // No pipeline is running: there is no queue to keep.
        assertThat(PipelineThread.realignFor(engineExists = false, threadRunning = false, wantsEngine = false)).isEqualTo(move)
        assertThat(PipelineThread.realignFor(engineExists = true, threadRunning = true, wantsEngine = false)).isEqualTo(move)
    }

    @Test
    fun `the crossfade is 20 ms of the stream's rate`() {
        assertThat(PipelineThread.PROCESSING_CROSSFADE_MS).isEqualTo(20)
        assertThat(PipelineThread.crossfadeFrames(48_000)).isEqualTo(960)
        assertThat(PipelineThread.crossfadeFrames(96_000)).isEqualTo(1_920)
        assertThat(PipelineThread.crossfadeFrames(44_100)).isEqualTo(882)
        assertThat(PipelineThread.crossfadeFrames(0)).isEqualTo(1)
    }

    // ── the blend ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `towards processed the first sample is the buffer's own and the last is the stage's`() {
        val stage = GainStage(0.25f)
        val total = 960
        val xf = ProcessingCrossfade(stage, towardsProcessed = true, totalFrames = total)
        val own = tone(2048)
        val data = own.copyOf()
        xf.blend(data, 2048, channels, rampStart = xf.claim(2048))

        // Frame 0: weight 1/960 on the stage: continuous with the audio queued before it (which is the buffer's own kind).
        assertThat(abs(data[0] - own[0])).isAtMost(abs(own[0]) / total + 1e-6f)
        // From the end of the ramp on, exactly the stage's output.
        for (f in total until 2048) {
            assertThat(data[f * channels]).isWithin(1e-7f).of(own[f * channels] * 0.25f)
        }
        // In between it moves monotonically from one to the other (no overshoot, no dip).
        var previousWeight = 0f
        for (k in 0 until total) {
            val w = xf.wetWeight(k)
            assertThat(w).isAtLeast(previousWeight)
            assertThat(w).isAtMost(1f)
            previousWeight = w
        }
        assertThat(xf.wetWeight(total - 1)).isEqualTo(1f)
        assertThat(stage.calls).isEqualTo(1)
    }

    @Test
    fun `towards pristine it starts as the stage's output and ends as the buffer's own samples, exactly`() {
        val stage = GainStage(0.25f)
        val total = 960
        val xf = ProcessingCrossfade(stage, towardsProcessed = false, totalFrames = total)
        val own = tone(2048)
        val data = own.copyOf()
        xf.blend(data, 2048, channels, rampStart = xf.claim(2048))

        // Frame 0 is (almost) what the processed stream was just playing: the stage's output.
        assertThat(abs(data[0] - own[0] * 0.25f)).isAtMost(abs(own[0]) * 0.75f / total + 1e-6f)
        // From the end of the ramp on, bit-exact the buffer's own samples: the pipeline carries them as before.
        for (f in total - 1 until 2048) {
            assertThat(data[f * channels]).isEqualTo(own[f * channels])
            assertThat(data[f * channels + 1]).isEqualTo(own[f * channels + 1])
        }
        assertThat(xf.wetWeight(total - 1)).isEqualTo(0f)
    }

    @Test
    fun `a ramp split over several buffers is the same ramp as one buffer`() {
        for (towardsProcessed in listOf(true, false)) {
            val whole = ProcessingCrossfade(GainStage(0.4f), towardsProcessed, 960)
            val split = ProcessingCrossfade(GainStage(0.4f), towardsProcessed, 960)
            val own = tone(1500)
            val one = own.copyOf()
            whole.blend(one, 1500, channels, whole.claim(1500))

            val pieces = own.copyOf()
            var at = 0
            for (size in listOf(100, 400, 333, 667)) {
                val chunk = pieces.copyOfRange(at * channels, (at + size) * channels)
                split.blend(chunk, size, channels, split.claim(size))
                System.arraycopy(chunk, 0, pieces, at * channels, size * channels)
                at += size
            }
            for (i in one.indices) assertThat(pieces[i]).isWithin(1e-7f).of(one[i])
        }
    }

    @Test
    fun `the ramp is spoken for once enough frames are claimed, so later buffers take the plain path`() {
        val xf = ProcessingCrossfade(GainStage(0.5f), towardsProcessed = true, totalFrames = 960)
        assertThat(xf.isDone).isFalse()
        assertThat(xf.claim(500)).isEqualTo(0)
        assertThat(xf.isDone).isFalse()
        assertThat(xf.claim(500)).isEqualTo(500)
        assertThat(xf.isDone).isTrue()
        assertThat(xf.claim(4096)).isEqualTo(1000)
        assertThat(xf.isDone).isTrue()
    }

    @Test
    fun `no sample steps across the join, in either direction`() {
        // The audio before the change: the source, unprocessed (towards processed) or processed (the other way).
        val gain = 0.5f
        val step = (0.5f * 2f * Math.PI.toFloat() * 1000f / 48_000f)   // the largest step of the tone itself
        for (towardsProcessed in listOf(true, false)) {
            val xf = ProcessingCrossfade(GainStage(gain), towardsProcessed, PipelineThread.crossfadeFrames(48_000))
            val before = tone(4096, from = 12)
            val after = tone(4096, from = 12 + 4096)
            val out = after.copyOf()
            xf.blend(out, 4096, channels, xf.claim(4096))
            val last = before[4095 * channels] * (if (towardsProcessed) 1f else gain)
            // The first sample after the join differs from the one before it by the signal's own slope, not by the EQ.
            assertThat(abs(out[0] - last)).isAtMost(step * 1.5f)
            var worst = 0f
            for (f in 1 until 4096) worst = maxOf(worst, abs(out[f * channels] - out[(f - 1) * channels]))
            assertThat(worst).isAtMost(step * 1.5f)
        }
    }
}
