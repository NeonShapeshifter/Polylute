package com.lostf1sh.pixelplayeross.presentation.components

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * AUD-146: the rules for when the wavy seek bar's wave may scroll. The scroll is a draw-phase
 * animation, so every frame it runs is a frame the whole window is redrawn.
 */
class WaveScrollPolicyTest {

    private val cycleMs = 2_000L
    private val cycleNanos = cycleMs * 1_000_000L
    private val windowNanos = WaveScrollPolicy.windowNanos()
    private val frameNanos = 16_666_667L

    @Test
    fun `one cycle is one wavelength at the wave speed, never under Materials floor`() {
        // The external player: wavelength 30 dp at the default speed (wavelength / 2) -> 2 s.
        assertThat(WaveScrollPolicy.cycleMillis(wavelengthDp = 30f, waveSpeedDp = 15f)).isEqualTo(2_000L)
        // The default Material wavelength is 40 dp -> still 2 s at half speed.
        assertThat(WaveScrollPolicy.cycleMillis(wavelengthDp = 40f, waveSpeedDp = 20f)).isEqualTo(2_000L)
        assertThat(WaveScrollPolicy.cycleMillis(wavelengthDp = 40f, waveSpeedDp = 10f)).isEqualTo(4_000L)
        // Material coerces to at least 50 ms; the boundary maths has to agree with it.
        assertThat(WaveScrollPolicy.cycleMillis(wavelengthDp = 1f, waveSpeedDp = 1000f))
            .isEqualTo(WaveScrollPolicy.MIN_CYCLE_MS)
    }

    @Test
    fun `a wave that does not move has no cycle`() {
        assertThat(WaveScrollPolicy.cycleMillis(wavelengthDp = 30f, waveSpeedDp = 0f)).isEqualTo(0L)
        assertThat(WaveScrollPolicy.cycleMillis(wavelengthDp = 0f, waveSpeedDp = 15f)).isEqualTo(0L)
        assertThat(WaveScrollPolicy.cycleMillis(wavelengthDp = -1f, waveSpeedDp = 15f)).isEqualTo(0L)
    }

    @Test
    fun `the wave only scrolls when it is on screen and not flat`() {
        assertThat(WaveScrollPolicy.mayScroll(visible = true, amplitudeShown = true)).isTrue()
        // The full player's bar: visible, but drawn flat. This used to scroll at refresh rate anyway.
        assertThat(WaveScrollPolicy.mayScroll(visible = true, amplitudeShown = false)).isFalse()
        // The mini player state: the bar is not composed as a wave at all.
        assertThat(WaveScrollPolicy.mayScroll(visible = false, amplitudeShown = true)).isFalse()
        assertThat(WaveScrollPolicy.mayScroll(visible = false, amplitudeShown = false)).isFalse()
    }

    @Test
    fun `inside the window the scroll never stops, even on a cycle boundary`() {
        val origin = 0L
        // Frames straddling the 1st and 3rd boundaries, well inside the 8 s window.
        assertThat(freezes(now = cycleNanos + 1, previous = cycleNanos - frameNanos, origin = origin)).isFalse()
        assertThat(freezes(now = 3 * cycleNanos + 5, previous = 3 * cycleNanos - frameNanos, origin = origin)).isFalse()
    }

    @Test
    fun `after the window it keeps going until the next cycle boundary`() {
        // The scroll began 700 ms into the clock, so the 8 s window ends mid-cycle.
        val origin = 700L * 1_000_000L
        val midCycle = origin + 5 * cycleNanos + cycleNanos / 2           // 11.7 s: past the window
        assertThat(midCycle).isGreaterThan(windowNanos)
        assertThat(freezes(now = midCycle, previous = midCycle - frameNanos, origin = origin)).isFalse()
        // The first frame past the next boundary (12.7 s): now.
        val boundary = origin + 6 * cycleNanos
        assertThat(freezes(now = boundary + 3_000_000L, previous = boundary - frameNanos + 3_000_000L, origin = origin))
            .isTrue()
    }

    @Test
    fun `the freeze lands within a frame of the boundary, so the picture does not move`() {
        // Simulate the frame loop: a scroll that began at t0, a window re-armed at t0, 60 Hz frames.
        val origin = 123_456_789L
        val windowStart = origin
        var previous = windowStart
        var now = windowStart
        var stoppedAt: Long? = null
        while (stoppedAt == null && now - windowStart < 60_000_000_000L) {
            now += frameNanos
            if (WaveScrollPolicy.shouldFreeze(now, previous, origin, cycleNanos, windowStart, windowNanos)) {
                stoppedAt = now
            }
            previous = now
        }
        assertThat(stoppedAt).isNotNull()
        val phaseNanos = (stoppedAt!! - origin) % cycleNanos
        // Stopped at most one frame past a boundary: the phase Material resets to 0 is the phase it was at.
        assertThat(phaseNanos).isAtMost(frameNanos)
        // And not before the window ran out.
        assertThat(stoppedAt - windowStart).isAtLeast(windowNanos)
        // It scrolled the window and at most one cycle more.
        assertThat(stoppedAt - windowStart).isAtMost(windowNanos + cycleNanos + frameNanos)
    }

    @Test
    fun `a long frame gap over a boundary after the window still freezes`() {
        val origin = 0L
        // The activity came back after a pause: the first frame is far past the window and the
        // boundary in one step. The caller opens a fresh window on resume, so this is the tail case.
        assertThat(
            WaveScrollPolicy.shouldFreeze(
                frameNanos = 30L * cycleNanos + 10,
                previousFrameNanos = 3L * cycleNanos,
                originNanos = origin,
                cycleNanos = cycleNanos,
                windowStartNanos = 0L,
                windowNanos = windowNanos,
            )
        ).isTrue()
    }

    @Test
    fun `a cycle of zero means nothing to wait for`() {
        assertThat(WaveScrollPolicy.shouldFreeze(10L, 5L, 0L, 0L, 0L, windowNanos)).isTrue()
    }

    @Test
    fun `the active window is short enough to rest and long enough to read as alive`() {
        assertThat(WaveScrollPolicy.ACTIVE_WINDOW_MS).isAtLeast(2_000L)
        assertThat(WaveScrollPolicy.ACTIVE_WINDOW_MS).isAtMost(15_000L)
    }

    private fun freezes(now: Long, previous: Long, origin: Long): Boolean =
        WaveScrollPolicy.shouldFreeze(
            frameNanos = now,
            previousFrameNanos = previous,
            originNanos = origin,
            cycleNanos = cycleNanos,
            windowStartNanos = 0L,
            windowNanos = windowNanos,
        )
}
