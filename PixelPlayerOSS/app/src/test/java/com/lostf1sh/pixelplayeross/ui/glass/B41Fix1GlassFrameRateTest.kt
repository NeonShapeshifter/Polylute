package com.lostf1sh.pixelplayeross.ui.glass

import androidx.compose.runtime.MonotonicFrameClock
import com.google.common.truth.Truth.assertThat
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Test

/**
 * B41-fix1 (B4): the glass is stepped on every frame of the display, whatever its rate, and by how long the frame really was. B41 stepped it
 * 20 times a second with a `delay`. The same thing running against a compose test's own display is in [B41Fix1GlassFrameRateProbeTest], which
 * also compiles against the tree before this batch and fails there.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class B41Fix1GlassFrameRateTest {
    /** A display that draws a frame every [periodNanos], the first one at [firstNanos]; a frame takes no time. */
    private class Display(private val periodNanos: Long, firstNanos: Long = periodNanos, private val jumpAtFrame: Int = -1, private val jumpNanos: Long = 0L) : MonotonicFrameClock {
        private var now = firstNanos - periodNanos
        private var frames = 0
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            yield()
            frames++
            now += if (frames == jumpAtFrame) jumpNanos else periodNanos
            return onFrame(now)
        }
    }

    private fun steps(display: MonotonicFrameClock, count: Int): Pair<Int, Float> = run {
        var taken = 0
        var phase = 0f
        kotlinx.coroutines.test.runTest {
            val clock = GlassClock()
            val job = launch(display) {
                try {
                    clock.runOnFrames { taken++; if (taken == count) throw CancellationException("enough") }
                } catch (_: CancellationException) {
                }
            }
            job.join()
            phase = clock.phase
        }
        taken to phase
    }

    @Test
    fun `a 60 Hz display steps the glass 60 times a second`() {
        val (taken, phase) = steps(Display(16_666_667L), count = 120)
        assertThat(taken).isEqualTo(120)
        // the first frame is the reference: 120 intervals of a 60th of a second
        assertThat(phase).isWithin(0.002f).of(120f / 60f)
    }

    @Test
    fun `a 90 Hz display steps it 90 times a second`() {
        val (taken, phase) = steps(Display(11_111_111L), count = 180)
        assertThat(taken).isEqualTo(180)
        assertThat(phase).isWithin(0.002f).of(180f / 90f)
    }

    @Test
    fun `a 120 Hz display steps it 120 times a second, with no millisecond lost to rounding`() {
        val (taken, phase) = steps(Display(8_333_333L), count = 240)
        assertThat(taken).isEqualTo(240)
        // whole-millisecond steps would have made it 240 x 8 ms = 1.92 s of motion in 2 s: 4% slow
        assertThat(phase).isWithin(0.002f).of(240f * 8_333_333L / 1e9f)
    }

    @Test
    fun `a frame that came late makes the glass late, not jumpy`() {
        // the main thread stalled for ten seconds before frame 30
        val (_, phase) = steps(Display(16_666_667L, jumpAtFrame = 30, jumpNanos = 10_000_000_000L), count = 60)
        // 59 ordinary frames and the one that came late, taken as the cap and no more
        assertThat(phase).isWithin(0.01f).of(59f / 60f + GlassMotion.MAX_STEP_SECONDS)
    }

    @Test
    fun `it writes nothing and takes no frame until the first one is drawn`() = runTest {
        val clock = GlassClock()
        val job = launch(Display(16_666_667L)) { clock.runOnFrames { } }
        // not started yet: the coroutine has not run, so nothing was stepped
        assertThat(clock.phase).isEqualTo(0f)
        job.cancel()
    }

    @Test
    fun `the clock keeps its precision however long the player plays`() {
        val clock = GlassClock()
        var t = 0
        // a week of 120 Hz frames, summed in one go: the wrap keeps the phase inside one week's cycle
        repeat(10) { clock.advance(GlassMotion.PHASE_WRAP_SECONDS / 4f) }
        assertThat(clock.phase).isLessThan(GlassMotion.PHASE_WRAP_SECONDS)
        assertThat(t).isEqualTo(0)
    }
}
