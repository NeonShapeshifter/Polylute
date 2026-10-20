package com.lostf1sh.pixelplayeross.data.usb

import com.decent.usbaudio.media3.PositionDriftMonitor
import com.decent.usbaudio.media3.PositionDriftMonitor.Action
import com.decent.usbaudio.media3.PositionDriftMonitor.Sample
import com.google.common.truth.Truth.assertThat
import kotlin.math.abs
import org.junit.jupiter.api.Test

/**
 * AUD-136 (b): the POSITION_DRIFT watchdog diverged on the device (`device-b7fix-selfskip`).
 *
 * Its first correction was taken from a player sample of the *previous* item (204 s, sampled before
 * the transition, while Media3 was buffering) and lowered the offset by 204 s. Media3's audio
 * renderer never lets its position go backwards unless the sink reports a discontinuity
 * (`currentPositionUs = max(current, sink)`, verified in the 1.10.1 bytecode), so Media3 froze;
 * the watchdog read the frozen position as "behind", raised the offset by the growing gap every
 * 0.75 s (-1.5 → -9.1 s) and Media3 threw StuckPlayerException after 10 s.
 *
 * Now: evidence is a fresh sample of a playing, READY Media3 taken after the current epoch began; a
 * drift must read the same twice (a frozen player's "drift" grows with time and is not an offset
 * error); a correction is followed by a verification; one that did not reduce the error is undone
 * and the watchdog switches itself off for the epoch; at most two corrections per epoch. It never
 * seeks.
 *
 * The world below is Media3's renderer clock with that monotonic rule, the sink's reported position
 * (offset + engine) and the 250 ms player mirror.
 */
class PositionDriftMonitorTest {

    private class World(initialErrorUs: Long, private val discontinuitySupported: Boolean = true) {
        private val rendererOffsetUs = 1_000_000_000_000L
        var sinkOffsetUs = rendererOffsetUs + initialErrorUs
        var engineUs = 10_000_000L
        private var media3RendererUs = sinkOffsetUs + engineUs
        private var allowDiscontinuity = false
        var nowMs = 0L
        val monitor = PositionDriftMonitor().also { it.onEpochStart(0L) }
        val actions = mutableListOf<Action>()
        var worstErrorAfterFirstActionUs = 0L
        var longestFrozenMs = 0L
        private var frozenSinceMs = 0L
        private var lastMedia3PositionUs = Long.MIN_VALUE
        var frozenByTest = false

        val media3PositionUs: Long get() = media3RendererUs - rendererOffsetUs
        val errorUs: Long get() = media3PositionUs - engineUs

        private fun sample() = Sample(positionMs = media3PositionUs / 1000, atMs = nowMs, playing = true, ready = true)

        fun run(seconds: Int) {
            repeat(seconds * 20) {
                nowMs += 50
                engineUs += 50_000
                val reported = sinkOffsetUs + engineUs
                if (!frozenByTest) {
                    media3RendererUs = if (allowDiscontinuity) reported else maxOf(media3RendererUs, reported)
                    allowDiscontinuity = false
                }
                // Stuck = not moving; a jump back after a discontinuity is movement.
                if (media3PositionUs != lastMedia3PositionUs) {
                    lastMedia3PositionUs = media3PositionUs
                    frozenSinceMs = nowMs
                } else {
                    longestFrozenMs = maxOf(longestFrozenMs, nowMs - frozenSinceMs)
                }
                if (nowMs % 250 == 0L) {
                    when (val action = monitor.check(nowMs, engineUs, sample(), offsetLatched = true)) {
                        Action.None -> Unit
                        else -> {
                            actions += action
                            apply(action)
                        }
                    }
                }
                if (actions.isNotEmpty()) worstErrorAfterFirstActionUs = maxOf(worstErrorAfterFirstActionUs, abs(errorUs))
            }
        }

        private fun apply(action: Action) {
            val before = sinkOffsetUs
            when (action) {
                is Action.Correct -> sinkOffsetUs -= action.driftUs
                is Action.Revert -> sinkOffsetUs += action.correctionUs
                else -> return
            }
            if (sinkOffsetUs < before && discontinuitySupported) allowDiscontinuity = true
        }

        val corrections: Int get() = actions.count { it is Action.Correct }
    }

    @Test
    fun `an error of plus or minus 200 s or 2 s converges in at most two corrections and never grows`() {
        for (initial in listOf(200_000_000L, -200_000_000L, 2_000_000L, -2_000_000L)) {
            val w = World(initial)
            w.run(seconds = 30)
            assertThat(abs(w.errorUs)).isAtMost(PositionDriftMonitor.DRIFT_LIMIT_US)
            assertThat(w.corrections).isIn(1..2)
            assertThat(w.worstErrorAfterFirstActionUs).isAtMost(abs(initial))
            assertThat(w.longestFrozenMs).isLessThan(10_000L)
            assertThat(w.actions.none { it is Action.Revert }).isTrue()
        }
    }

    @Test
    fun `if Media3 does not follow a correction it is undone and the watchdog stops, before any stuck`() {
        // No discontinuity reaches Media3: lowering the position freezes it (the device's failure).
        val w = World(200_000_000L, discontinuitySupported = false)
        w.run(seconds = 60)
        assertThat(w.corrections).isEqualTo(1)
        assertThat(w.actions.filterIsInstance<Action.Revert>()).hasSize(1)
        assertThat(abs(w.errorUs)).isAtMost(200_000_000L + 100_000L)  // back where it was, not worse
        assertThat(w.longestFrozenMs).isLessThan(10_000L)
        assertThat(w.actions.last()).isInstanceOf(Action.Revert::class.java)
    }

    @Test
    fun `a frozen player is not chased`() {
        val w = World(0L)
        w.run(seconds = 2)
        w.frozenByTest = true
        w.run(seconds = 8)
        assertThat(w.actions.filterIsInstance<Action.Correct>()).isEmpty()
    }

    @Test
    fun `samples from before the epoch, while buffering, stale or paused are not evidence`() {
        val m = PositionDriftMonitor()
        m.onEpochStart(nowMs = 22_598L)
        // The previous item's last sample (204 s, playing) — taken before the new engine started.
        val previousItem = Sample(positionMs = 204_414L, atMs = 22_500L, playing = true, ready = true)
        val buffering = Sample(positionMs = 204_414L, atMs = 23_000L, playing = true, ready = false)
        for (t in listOf(22_700L, 23_120L, 23_700L)) {
            assertThat(m.check(t, 500_000L, previousItem, offsetLatched = true)).isEqualTo(Action.None)
            assertThat(m.check(t, 500_000L, buffering, offsetLatched = true)).isEqualTo(Action.None)
        }
        val stale = Sample(positionMs = 204_414L, atMs = 23_000L, playing = true, ready = true)
        assertThat(m.check(25_000L, 2_000_000L, stale, offsetLatched = true)).isEqualTo(Action.None)
        val paused = Sample(positionMs = 204_414L, atMs = 25_000L, playing = false, ready = true)
        assertThat(m.check(25_100L, 2_000_000L, paused, offsetLatched = true)).isEqualTo(Action.None)
    }

    @Test
    fun `five minutes of ordinary jitter produce no action at all`() {
        val m = PositionDriftMonitor()
        m.onEpochStart(0L)
        var engineUs = 0L
        for (t in 250L..300_000L step 250L) {
            engineUs += 250_000L
            val jitterMs = ((t / 250) % 7 - 3) * 20   // ±60 ms
            val s = Sample(positionMs = engineUs / 1000 + jitterMs, atMs = t - 100, playing = true, ready = true)
            assertThat(m.check(t, engineUs, s, offsetLatched = true)).isEqualTo(Action.None)
        }
    }

    @Test
    fun `an offset never latched while the engine plays is reported once, and never as a seek`() {
        val m = PositionDriftMonitor()
        m.onEpochStart(0L)
        assertThat(m.check(1_000L, 1_000_000L, null, offsetLatched = false)).isEqualTo(Action.None)
        assertThat(m.check(3_000L, 3_000_000L, null, offsetLatched = false)).isEqualTo(Action.Unlatched(3_000_000L))
        assertThat(m.check(9_000L, 9_000_000L, null, offsetLatched = false)).isEqualTo(Action.None)
    }
}
