package com.lostf1sh.pixelplayeross.data.usb

import com.decent.usbaudio.media3.NaturalJoinPause
import com.decent.usbaudio.media3.NaturalJoinPause.Action
import com.decent.usbaudio.media3.PipelinePositionClock
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * AUD-144: the native engine did not preserve the samples at a natural track join. Measured with the
 * payload harness (B8a), same rate: right byte count, `27b6aa95…` instead of `a7482ec2…`, one 1 MiB
 * block different; 96 → 44.1 kHz the same. The block hash was reproduced on the host, to the bit, as the
 * engine's pause fade-out (8 ms) and resume fade-in (15 ms) starting at the second decoded block of the
 * new track. Media3 rebuffers the new item for ~35 ms right after the transition, stopping its renderers
 * (`sink.pause()`) and restarting them (`play()`) while the user still wants playback; the engine took
 * the stop for a user's pause. `tools/natural-join-test.cpp` shows the other half: the engine itself is
 * bit-exact across the join, and a pause still fades exactly as designed (AUD-123/140).
 */
class NaturalJoinPauseTest {

    private val justJoined = 40L

    @Test
    fun `a stop just after a natural join, with the user still playing, is held back from the engine`() {
        assertThat(NaturalJoinPause.onMedia3Stop(userWantsPlayback = true, msSinceNaturalJoin = justJoined))
            .isEqualTo(Action.HOLD)
        assertThat(NaturalJoinPause.onMedia3Stop(true, 0L)).isEqualTo(Action.HOLD)
        assertThat(NaturalJoinPause.onMedia3Stop(true, NaturalJoinPause.JOIN_WINDOW_MS)).isEqualTo(Action.HOLD)
    }

    @Test
    fun `a user's pause is never held, even at the join`() {
        assertThat(NaturalJoinPause.onMedia3Stop(userWantsPlayback = false, msSinceNaturalJoin = justJoined))
            .isEqualTo(Action.PAUSE_NOW)
        assertThat(NaturalJoinPause.onMedia3Stop(false, -1L)).isEqualTo(Action.PAUSE_NOW)
    }

    @Test
    fun `when the last item change was not a natural join, stops reach the engine exactly as before`() {
        // A manual skip, a seek, a first start: -1.
        assertThat(NaturalJoinPause.onMedia3Stop(true, -1L)).isEqualTo(Action.PAUSE_NOW)
    }

    /**
     * B10-4: `pause()` now says why a stop was not held. The reason must agree with the decision for every
     * input, and `no-join-mark` — Media3 stopped right after a join the sink never marked, the case of B9's
     * native `rate` run (`joinPausesHeld=0`) — must be what a missing mark reads as.
     */
    @Test
    fun `the logged reason always agrees with the decision`() {
        for (playing in listOf(true, false)) {
            for (ms in listOf(-1L, 0L, justJoined, NaturalJoinPause.JOIN_WINDOW_MS, NaturalJoinPause.JOIN_WINDOW_MS + 1, 60_000L)) {
                val held = NaturalJoinPause.onMedia3Stop(playing, ms) == Action.HOLD
                assertThat(NaturalJoinPause.reason(playing, ms) == "held").isEqualTo(held)
            }
        }
        assertThat(NaturalJoinPause.reason(userWantsPlayback = false, msSinceNaturalJoin = justJoined)).isEqualTo("user-paused")
        assertThat(NaturalJoinPause.reason(userWantsPlayback = true, msSinceNaturalJoin = -1L)).isEqualTo("no-join-mark")
        assertThat(NaturalJoinPause.reason(true, NaturalJoinPause.JOIN_WINDOW_MS + 1)).isEqualTo("outside-window")
        assertThat(NaturalJoinPause.reason(true, justJoined)).isEqualTo("held")
    }

    @Test
    fun `a stop long after the join is not the join's rebuffer`() {
        assertThat(NaturalJoinPause.onMedia3Stop(true, NaturalJoinPause.JOIN_WINDOW_MS + 1)).isEqualTo(Action.PAUSE_NOW)
        assertThat(NaturalJoinPause.onMedia3Stop(true, 60_000L)).isEqualTo(Action.PAUSE_NOW)
    }

    @Test
    fun `the hold is bounded, so a player that never resumes is not left with an engine under a paused UI`() {
        assertThat(NaturalJoinPause.MAX_HOLD_MS).isAtLeast(500L)
        assertThat(NaturalJoinPause.MAX_HOLD_MS).isAtMost(3_000L)
        // The window is a human's reaction time away from the rebuffer's own ~tens of ms.
        assertThat(NaturalJoinPause.JOIN_WINDOW_MS).isAtLeast(500L)
        assertThat(NaturalJoinPause.JOIN_WINDOW_MS).isAtMost(2_000L)
    }

    // ── the clock around the same boundary (AUD-143) ────────────────────────────────────────────

    @Test
    fun `a pipeline period latched while the previous item's tail is queued continues that item's clock`() {
        val rate = 96_000
        val tailFrames = 131_072L                                   // 16 buffers of 8192 still queued
        val aheadUs = tailFrames * 1_000_000L / rate
        val itemEndUs = 1_000_006_000_000L                          // renderer time at which B starts
        val framesWrittenNow = 441_600L                             // A is at ~4.6 s

        val clock = PipelinePositionClock()
        assertThat(clock.accept(itemEndUs, framesWrittenNow, aheadUs)).isTrue()

        // Now the clock is where A's audible position is — not a queue's length ahead of it.
        assertThat(clock.positionUs(framesWrittenNow, rate)).isEqualTo(itemEndUs - aheadUs)
        // It reaches B's start exactly when the thread has written the tail, i.e. when B's first frame goes out.
        assertThat(clock.positionUs(framesWrittenNow + tailFrames, rate)).isEqualTo(itemEndUs)
        // And it keeps going from there at the stream's rate.
        assertThat(clock.positionUs(framesWrittenNow + tailFrames + rate, rate)).isEqualTo(itemEndUs + 1_000_000L)
    }

    @Test
    fun `with nothing queued the clock latches as it always did`() {
        val clock = PipelinePositionClock()
        assertThat(clock.accept(1_000_000_000_000L, 0L)).isTrue()
        assertThat(clock.positionUs(96_000L, 96_000)).isEqualTo(1_000_001_000_000L)
        // A negative lead (never expected) is not allowed to move the start forward.
        val other = PipelinePositionClock()
        other.accept(5_000_000L, 0L, -123L)
        assertThat(other.positionUs(0L, 96_000)).isEqualTo(5_000_000L)
    }

    // ── B9-1: the pipeline's thread fades at a pause now, so the same hold covers its join ───────

    @Test
    fun `a join is marked for the pipeline's thread only when no engine marks it and the thread is the producer`() {
        assertThat(NaturalJoinPause.marksPipelineJoin(engineExists = false, threadRunning = true)).isTrue()
        assertThat(NaturalJoinPause.marksPipelineJoin(engineExists = true, threadRunning = false)).isFalse()
        assertThat(NaturalJoinPause.marksPipelineJoin(engineExists = false, threadRunning = false)).isFalse()
        // The engine's own join keeps being marked by the engine's hand-over (UsbAudioSink), not by this.
        assertThat(NaturalJoinPause.marksPipelineJoin(engineExists = true, threadRunning = true)).isFalse()
    }

    @Test
    fun `the rebuffer stop right after a pipeline join is held while the user plays, and a pause by the user is not`() {
        // Media3's own advance, then a stop 35 ms later with the user still playing (the B1-native log's shape).
        assertThat(NaturalJoinPause.onMedia3Stop(userWantsPlayback = true, msSinceNaturalJoin = 35)).isEqualTo(Action.HOLD)
        // A pause by the user a moment later is a pause: the thread fades it.
        assertThat(NaturalJoinPause.onMedia3Stop(userWantsPlayback = false, msSinceNaturalJoin = 35)).isEqualTo(Action.PAUSE_NOW)
        // Long after the join, or after a seek or a skip (no join marked): a stop reaches the thread.
        assertThat(NaturalJoinPause.onMedia3Stop(userWantsPlayback = true, msSinceNaturalJoin = NaturalJoinPause.JOIN_WINDOW_MS + 1))
            .isEqualTo(Action.PAUSE_NOW)
        assertThat(NaturalJoinPause.onMedia3Stop(userWantsPlayback = true, msSinceNaturalJoin = -1)).isEqualTo(Action.PAUSE_NOW)
    }
}
