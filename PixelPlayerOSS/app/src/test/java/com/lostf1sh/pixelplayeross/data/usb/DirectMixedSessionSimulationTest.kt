package com.lostf1sh.pixelplayeross.data.usb

import com.decent.usbaudio.media3.AnchorSource
import com.decent.usbaudio.media3.NaturalEndCoordinator
import com.decent.usbaudio.media3.NaturalEndCoordinator.NextOffset
import com.decent.usbaudio.media3.OffsetAuthority
import com.decent.usbaudio.media3.PositionDriftMonitor
import com.decent.usbaudio.media3.RendererOffsetLedger
import com.google.common.truth.Truth.assertThat
import kotlin.math.abs
import org.junit.jupiter.api.Test

/**
 * AUD-136: five simulated minutes of natural ends mixed with manual seeks, NEXT/PREV, a PREV whose
 * flush meets a stale host position, a seek past the end, and two injected offset errors — the
 * session that skipped tracks by itself on the device (`device-b7fix-selfskip`).
 *
 * Media3 is modelled on the rules the sink depends on (verified in the 1.10.1 bytecode where it
 * matters): period renderer offsets are previous + duration and restart at 1e12 on a queue reset,
 * each reaches the sink through `setOutputStreamOffsetUs`, the renderer clock never goes backwards
 * without a discontinuity, a position reset allows one, and the playing period advances when the
 * clock reaches its end. The sink side is the decision classes it uses — [RendererOffsetLedger],
 * [NaturalEndCoordinator], [OffsetAuthority], [PositionDriftMonitor] — wired as UsbAudioSink wires
 * them. What this does not cover is UsbAudioSink's own threading; that is for the device.
 */
class DirectMixedSessionSimulationTest {

    private companion object {
        const val START = 1_000_000_000_000L
        const val STEP_MS = 50L
        const val PRELOAD_US = 15_000_000L
        const val FRAME_US = 92_880L   // a 4096-sample FLAC frame at 44.1 kHz
    }

    private class Session(private val durationsUs: List<Long>) {
        // Media3
        private data class Holder(val item: Int, val offsetUs: Long)
        private val holders = ArrayList<Holder>()
        private var playingHolder = 0
        private var clockUs = START
        private var allowDiscontinuity = true
        var ended = false
        val playingItem: Int get() = holders[playingHolder].item
        private val playingOffsetUs: Long get() = holders[playingHolder].offsetUs
        val media3PositionUs: Long get() = clockUs - playingOffsetUs

        // Sink
        val ledger = RendererOffsetLedger()
        val natural = NaturalEndCoordinator()
        val monitor = PositionDriftMonitor()
        private var sinkOffsetUs = -1L
        private var engineItem = -1
        private var enginePosUs = 0L
        private var engineRunning = false
        private var positionResetCount = 0L

        // Findings
        var nowMs = 0L
        var autoTransitions = 0
        var naturalEnds = 0
        val spontaneousSkips = mutableListOf<String>()
        val wrongFile = mutableListOf<String>()
        var longestFrozenMs = 0L
        var maxSettledUiErrorUs = 0L
        var maxGapMs = 0L
        private var frozenSinceMs = 0L
        private var lastPositionUs = Long.MIN_VALUE
        private var lastPlayingItem = -1
        private var settledAfterMs = 0L
        private var engineEndedAtMs = -1L

        private fun duration(item: Int) = durationsUs[item % durationsUs.size]
        private fun nextItem(item: Int): Int? = (item + 1).takeIf { it < 1_000 }

        private fun report(offsetUs: Long) = ledger.onStreamOffsetReported(offsetUs)

        // --- user actions -------------------------------------------------------------------------

        fun startAt(item: Int) = seekToItem(item, reason = null)

        /** NEXT / PREV to another item: a queue reset. */
        fun seekToItem(item: Int, reason: Int? = 2) {
            holders.clear(); holders += Holder(item, START); playingHolder = 0
            report(START)
            clockUs = START; allowDiscontinuity = true
            if (reason != null) ledger.onItemTransition(reason)
            natural.onUserSeek()
            openEngine(item, 0L)
            latchFromFirstBuffer(anchorUs = 0L, mediaPositionUs = 0L)
        }

        /**
         * A seek inside the playing item. [explicitDoneBeforeFlush] with [staleHostUs]: the device's
         * PREV, whose explicit seek finished before Media3's flush met a stale host position.
         */
        fun seekWithin(targetUs: Long, explicitDoneBeforeFlush: Boolean = false, staleHostUs: Long = -1L) {
            val hadReadAhead = holders.size > playingHolder + 1
            while (holders.size > playingHolder + 1) holders.removeAt(holders.size - 1)
            if (hadReadAhead) report(playingOffsetUs)
            val d = duration(playingItem)
            val landedUs = minOf(targetUs, d - FRAME_US)
            clockUs = playingOffsetUs + minOf(targetUs, d); allowDiscontinuity = true
            natural.onUserSeek()
            val issuedAt = positionResetCount
            positionResetCount++
            // The flush: an unlatched explicit epoch this reset belongs to is kept (AUD-136 a).
            val keep = OffsetAuthority.keepExplicitEpochAtFlush(false, AnchorSource.EXPLICIT_SEEK_TARGET, issuedAt, positionResetCount)
            val anchorUs = if (explicitDoneBeforeFlush && !keep && staleHostUs >= 0) staleHostUs else targetUs
            engineItem = playingItem; enginePosUs = landedUs.coerceAtMost(d); engineRunning = targetUs < d
            latchFromFirstBuffer(anchorUs, mediaPositionUs = frameStart(landedUs))
            if (!engineRunning) engineFinished()   // a seek past the end: EOF at once (AUD-134)
        }

        /**
         * An error the sink might still make; Media3 takes it, as after a position reset. Behind only:
         * a position *ahead* of the engine near the end is Media3 ending the item, which no watchdog
         * can undo — that is why the offsets themselves now come from Media3.
         */
        fun injectOffsetError(errorUs: Long) {
            require(errorUs < 0)
            sinkOffsetUs += errorUs
            allowDiscontinuity = true
            settledAfterMs = nowMs + 3_000   // the watchdog's confirm + correct + verify
        }

        private fun frameStart(us: Long) = us - us % FRAME_US

        private fun latchFromFirstBuffer(anchorUs: Long, mediaPositionUs: Long) {
            val pts = playingOffsetUs + mediaPositionUs
            val fromMedia3 = OffsetAuthority.media3Anchor(pts, ledger.latestOffsetUs(), anchorUs, duration(playingItem))
            sinkOffsetUs = pts - (fromMedia3 ?: anchorUs)
            // The bootstrap seek places the engine at `pts - offset`: the anchor the latch used.
            enginePosUs = (fromMedia3 ?: anchorUs).coerceAtMost(duration(playingItem))
            monitor.onEpochStart(nowMs)
            settledAfterMs = nowMs + 1_000
        }

        private fun openEngine(item: Int, atUs: Long) {
            engineItem = item; enginePosUs = atUs; engineRunning = true
            if (engineEndedAtMs >= 0) {
                maxGapMs = maxOf(maxGapMs, nowMs - engineEndedAtMs)
                engineEndedAtMs = -1
            }
        }

        private fun engineFinished() {
            engineRunning = false
            naturalEnds++
            engineEndedAtMs = nowMs
            natural.onEngineFinished(enginePosUs, sinkOffsetUs, nowMs)
        }

        // --- time -----------------------------------------------------------------------------------

        fun run(untilMs: Long) {
            while (nowMs < untilMs && !ended) step()
        }

        private fun reportedUs(): Long? = when {
            natural.pending && sinkOffsetUs >= 0 -> sinkOffsetUs + natural.positionUs(nowMs)
            engineRunning && sinkOffsetUs >= 0 -> sinkOffsetUs + enginePosUs
            else -> null
        }

        private fun step() {
            nowMs += STEP_MS
            if (engineRunning) {
                enginePosUs += STEP_MS * 1000
                if (enginePosUs >= duration(engineItem)) {
                    enginePosUs = duration(engineItem)
                    engineFinished()
                }
            }
            // Media3 reads into the next item in the last seconds; its offset reaches the sink.
            val last = holders.last()
            if (holders.size == playingHolder + 1 && media3PositionUs >= duration(playingItem) - PRELOAD_US) {
                nextItem(last.item)?.let { holders += Holder(it, last.offsetUs + duration(last.item)); report(holders.last().offsetUs) }
            }
            // Renderer clock: monotonic unless a discontinuity was allowed.
            reportedUs()?.let { r ->
                clockUs = if (allowDiscontinuity) r else maxOf(clockUs, r)
                allowDiscontinuity = false
            }
            // Media3's own transition at the end of the playing period.
            if (holders.size > playingHolder + 1 && clockUs >= playingOffsetUs + duration(playingItem)) {
                if (engineRunning && engineItem == playingItem) {
                    spontaneousSkips += "t=${nowMs}ms item=$playingItem engine at ${enginePosUs / 1000}ms of ${duration(playingItem) / 1000}ms"
                }
                playingHolder++
                autoTransitions++
                ledger.onItemTransition(1)
                val before = reportedUs()
                val next = natural.onItemTransition(ledger.playingOffsetUs())
                openEngine(playingItem, 0L)
                if (next is NextOffset.FromMedia3) {
                    sinkOffsetUs = next.offsetUs
                    // The sink reports a discontinuity when it lowers its position.
                    if (before != null && next.offsetUs < before) allowDiscontinuity = true
                } else {
                    latchFromFirstBuffer(anchorUs = 0L, mediaPositionUs = 0L)
                }
                monitor.onEpochStart(nowMs)
                settledAfterMs = nowMs + 1_000
            }
            observe()
            if (nowMs % 250 == 0L) watchdog()
        }

        private fun watchdog() {
            if (!engineRunning) return
            val sample = PositionDriftMonitor.Sample(media3PositionUs / 1000, nowMs, playing = true, ready = true)
            when (val a = monitor.check(nowMs, enginePosUs, sample, offsetLatched = sinkOffsetUs >= 0)) {
                is PositionDriftMonitor.Action.Correct -> {
                    sinkOffsetUs -= a.driftUs
                    if (a.driftUs > 0) allowDiscontinuity = true
                }
                is PositionDriftMonitor.Action.Revert -> {
                    sinkOffsetUs += a.correctionUs
                    if (a.correctionUs < 0) allowDiscontinuity = true
                }
                else -> Unit
            }
        }

        private fun observe() {
            if (playingItem != lastPlayingItem) {
                lastPlayingItem = playingItem
                lastPositionUs = Long.MIN_VALUE
            }
            // Stuck = not moving; a jump back after a discontinuity is movement.
            if (media3PositionUs != lastPositionUs) {
                lastPositionUs = media3PositionUs
                frozenSinceMs = nowMs
            } else {
                longestFrozenMs = maxOf(longestFrozenMs, nowMs - frozenSinceMs)
            }
            if (engineRunning && engineItem != playingItem) wrongFile += "t=${nowMs}ms engine=$engineItem media3=$playingItem"
            if (engineRunning && nowMs >= settledAfterMs) {
                maxSettledUiErrorUs = maxOf(maxSettledUiErrorUs, abs(media3PositionUs - enginePosUs))
            }
        }
    }

    private val durations = listOf(45, 38, 52, 30, 41, 60, 35, 48, 33, 55, 40, 44).map { it * 1_000_000L + 373_333L }

    @Test
    fun `five mixed minutes - no spontaneous skip, no stuck player, right file, UI = engine`() {
        val s = Session(durations)
        s.startAt(0)
        s.run(20_000); s.seekWithin(durations[0] - 5_000_000L)             // seek near the end, then natural end
        s.run(40_000); s.seekToItem(4)                                       // NEXT (skip ahead)
        s.run(50_000); s.seekWithin(0L, explicitDoneBeforeFlush = true, staleHostUs = 167_443_000L)  // PREV → 0
        s.run(60_000); s.seekWithin(durations[4] + 3_000_000L)               // seek past the end (AUD-134)
        s.run(150_000); s.seekWithin(s.media3PositionUs + 20_000_000L)       // a manual forward seek
        s.run(200_000); s.seekToItem(s.playingItem + 1)                      // NEXT
        s.run(205_000); s.seekToItem(s.playingItem - 1)                      // PREV to the previous item
        s.run(230_000); s.injectOffsetError(-2_000_000L)
        s.run(260_000); s.injectOffsetError(-200_000_000L)
        s.run(300_000)

        assertThat(s.spontaneousSkips).isEmpty()
        assertThat(s.wrongFile).isEmpty()
        assertThat(s.longestFrozenMs).isLessThan(10_000L)          // StuckPlayerDetector's limit
        assertThat(s.maxSettledUiErrorUs).isAtMost(200_000L)
        assertThat(s.autoTransitions).isEqualTo(s.naturalEnds)      // one AUTO per end, no extra
        assertThat(s.naturalEnds).isAtLeast(5)
        assertThat(s.maxGapMs).isAtMost(250L)
    }
}
