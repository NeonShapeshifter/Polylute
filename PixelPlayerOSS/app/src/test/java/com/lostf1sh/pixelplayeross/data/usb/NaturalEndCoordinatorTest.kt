package com.lostf1sh.pixelplayeross.data.usb

import com.decent.usbaudio.media3.NaturalEndCoordinator
import com.decent.usbaudio.media3.NaturalEndCoordinator.BufferAction
import com.decent.usbaudio.media3.NaturalEndCoordinator.ConfigureAction
import com.decent.usbaudio.media3.NaturalEndCoordinator.NextOffset
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * AUD-128 / AUD-129 / AUD-133 / AUD-130 — the native engine's natural end, as Media3 sees it.
 *
 * B7 (measured): one AUTO and no Rate mismatch, but the next item's window offset was latched from
 * whichever buffer arrived after the transition. The read-ahead buffers of the next item had been
 * dropped, so it was latched X seconds into the track (the UI ran ahead, accumulating 195 → 502 s
 * across transitions: tracks never heard, EOFs too early), or — after a rate change, with no buffer
 * coming — never latched at all (window = -1, StuckPlayerException after 10 s). And a buffer
 * arriving between the cleanup and the new engine rebuilt the engine for the track that had just
 * ended (AUD-133).
 *
 * B7-fix made it "the ended item's offset plus its length", which carried any error in the ended
 * item's offset into the next one (AUD-136: a fresh track at +167 s after a stale latch). It is now
 * Media3's own renderer offset for the new period, applied when its engine is created; buffers stay
 * dropped until then. The arithmetic is only a fallback while Media3's report is late.
 */
class NaturalEndCoordinatorTest {

    private data class Track(val path: String, val rate: Int, val durationUs: Long)

    /**
     * Media3's side (renderer offsets are cumulative item lengths) and the sink's handlers reduced
     * to the coordinator's decisions, run through consecutive natural ends.
     */
    private class Simulation(
        private val tracks: List<Track>,
        private val repeatOne: Boolean = false,
        firstOffsetErrorUs: Long = 0L,
    ) {
        val natural = NaturalEndCoordinator()
        var index = 0
        var rendererOffsetUs = 1_000_000_000_000L       // Media3: start of the current period
        var windowOffsetUs = rendererOffsetUs + firstOffsetErrorUs  // sink: latched at the first item's start
        var streamRate = tracks[0].rate
        var enginePath: String? = tracks[0].path
        var engineRunning = true
        var enginePendingForItem = false
        var currentPath = tracks[0].path
        var deferredRate: Int? = null
        val enginesOpened = mutableListOf(tracks[0].path)
        var rateMismatches = 0
        var transitions = 0
        var ended = false
        var maxUiErrorUs = 0L

        fun uiPositionUs(engineUs: Long): Long = windowOffsetUs + engineUs - rendererOffsetUs

        /** Plays the current track to its end and lets Media3 resolve it. */
        fun playToNaturalEnd(nowMs: Long) {
            val track = tracks[index]
            // During playback the UI follows the engine (the first item's latch is the test's input).
            if (index > 0 || transitions > 0) {
                maxUiErrorUs = maxOf(maxUiErrorUs, kotlin.math.abs(uiPositionUs(track.durationUs / 2) - track.durationUs / 2))
            }
            engineRunning = false
            natural.onEngineFinished(track.durationUs, windowOffsetUs, nowMs)
            // Media3 read ahead: the next item's configure and buffers arrive while pending.
            val next = nextIndex()
            if (next != null && tracks[next].rate != streamRate) {
                if (natural.onConfigure(engineRunning = false, trackChanged = false) == ConfigureAction.DEFER) {
                    deferredRate = tracks[next].rate
                }
            }
            repeat(4) { handleBuffer() }
            // The clock ran past the boundary, but not far past it.
            if (index > 0 || transitions > 0) {
                val reported = windowOffsetUs + natural.positionUs(nowMs + 5_000)
                val boundary = rendererOffsetUs + track.durationUs
                assertThat(reported).isAtLeast(boundary)
                assertThat(reported - boundary).isAtMost(NaturalEndCoordinator.BOUNDARY_OVERSHOOT_US)
            }
            if (next == null) {
                ended = true   // Media3: STATE_ENDED (the renderer and the sink report ended)
                return
            }
            // Media3's own transition: one AUTO (or REPEAT), period offset advances by the length.
            transitions++
            rendererOffsetUs += track.durationUs
            index = next
            // Listener (main): the item's path first, then cleanup and creation queued in order.
            currentPath = tracks[index].path
            enginePendingForItem = true
            cleanup()
            handleBuffer()              // a buffer between cleanup and creation (AUD-133)
            createPendingEngine()
            maxUiErrorUs = maxOf(maxUiErrorUs, kotlin.math.abs(uiPositionUs(0)))
        }

        private fun nextIndex(): Int? = when {
            repeatOne -> index
            index + 1 < tracks.size -> index + 1
            else -> null
        }

        private fun handleBuffer() {
            when (natural.onHandleBuffer(engineExists = enginePath != null, streamingThreadExists = false)) {
                BufferAction.DROP -> Unit
                BufferAction.CREATE_ENGINE_IF_NEEDED -> if (enginePath == null && !enginePendingForItem) openEngine()
                BufferAction.NORMAL -> Unit
            }
        }

        private fun cleanup() {
            if (enginePath != null && !engineRunning) {
                enginePath = null
                deferredRate?.let { streamRate = it }
                deferredRate = null
            }
        }

        private fun createPendingEngine() {
            enginePendingForItem = false
            // Media3's renderer offset for the new period (what the sink's ledger holds).
            val next = natural.onItemTransition(media3OffsetUs = rendererOffsetUs)
            openEngine()
            if (next is NextOffset.FromMedia3) windowOffsetUs = next.offsetUs
        }

        private fun openEngine() {
            val track = tracks[index]
            check(currentPath == track.path)
            if (track.rate != streamRate) { rateMismatches++; return }
            enginesOpened += currentPath
            enginePath = currentPath
            engineRunning = true
        }
    }

    private val a = Track("A.flac", 44_100, 193_574_000L)
    private val b = Track("B.flac", 44_100, 256_800_000L)
    private val c = Track("C.flac", 48_000, 218_146_000L)
    private val d = Track("D.flac", 96_000, 206_066_000L)

    @Test
    fun `three natural ends in a row, same rate and rate changes - right files, UI = engine, one AUTO each, ENDED`() {
        val sim = Simulation(listOf(a, b, c, d))
        repeat(4) { sim.playToNaturalEnd(nowMs = it * 1_000L) }
        assertThat(sim.enginesOpened).containsExactly("A.flac", "B.flac", "C.flac", "D.flac").inOrder()
        assertThat(sim.transitions).isEqualTo(3)
        assertThat(sim.rateMismatches).isEqualTo(0)
        assertThat(sim.maxUiErrorUs).isAtMost(200_000L)
        assertThat(sim.ended).isTrue()
        // No accumulation: the offset of the last item is exactly the sum of the lengths before it.
        assertThat(sim.windowOffsetUs).isEqualTo(1_000_000_000_000L + a.durationUs + b.durationUs + c.durationUs)
    }

    @Test
    fun `repeat-one reopens the same track with the offset advanced by its length`() {
        val sim = Simulation(listOf(a), repeatOne = true)
        repeat(3) { sim.playToNaturalEnd(nowMs = it * 1_000L) }
        assertThat(sim.enginesOpened).containsExactly("A.flac", "A.flac", "A.flac", "A.flac").inOrder()
        assertThat(sim.maxUiErrorUs).isAtMost(200_000L)
    }

    @Test
    fun `a wrong offset on the ended item does not carry into the next one`() {
        // The device: a latch against a stale anchor left the item 167 s off before its natural end.
        val sim = Simulation(listOf(a, b, c), firstOffsetErrorUs = 167_443_000L)
        repeat(3) { sim.playToNaturalEnd(nowMs = it * 1_000L) }
        assertThat(sim.windowOffsetUs).isEqualTo(1_000_000_000_000L + a.durationUs + b.durationUs)
        assertThat(sim.maxUiErrorUs).isAtMost(200_000L)
    }

    @Test
    fun `a late Media3 report is awaited, with the arithmetic only as a fallback`() {
        val natural = NaturalEndCoordinator()
        natural.onEngineFinished(finalPositionUs = 10_000_000L, windowOffsetUs = 5_000L, nowMs = 0L)
        assertThat(natural.onItemTransition(media3OffsetUs = null)).isEqualTo(NextOffset.AwaitMedia3(10_005_000L))
        natural.onEngineFinished(finalPositionUs = 10_000_000L, windowOffsetUs = -1L, nowMs = 0L)
        assertThat(natural.onItemTransition(media3OffsetUs = null)).isEqualTo(NextOffset.AwaitMedia3(-1L))
    }

    @Test
    fun `a user seek cancels the natural end`() {
        val natural = NaturalEndCoordinator()
        natural.onEngineFinished(1_000L, 5_000L, 0L)
        natural.onUserSeek()
        assertThat(natural.pending).isFalse()
        assertThat(natural.onItemTransition(media3OffsetUs = 7_000L)).isEqualTo(NextOffset.None)
        assertThat(natural.onHandleBuffer(engineExists = false, streamingThreadExists = false))
            .isEqualTo(BufferAction.CREATE_ENGINE_IF_NEEDED)
    }

    @Test
    fun `the watchdog fires only if Media3 never moved on`() {
        val natural = NaturalEndCoordinator()
        natural.onEngineFinished(1_000L, 5_000L, nowMs = 10_000L)
        assertThat(natural.overdue(10_000L + NaturalEndCoordinator.WATCHDOG_MS - 1, itemChanged = false)).isFalse()
        assertThat(natural.overdue(10_000L + NaturalEndCoordinator.WATCHDOG_MS, itemChanged = false)).isTrue()
        natural.onItemTransition(media3OffsetUs = 6_000L)
        assertThat(natural.overdue(20_000L, itemChanged = false)).isFalse()
    }

    @Test
    fun `outside a natural end the old rules stand`() {
        val natural = NaturalEndCoordinator()
        assertThat(natural.onConfigure(engineRunning = true, trackChanged = false)).isEqualTo(ConfigureAction.DEFER)
        assertThat(natural.onConfigure(engineRunning = true, trackChanged = true)).isEqualTo(ConfigureAction.RECONFIGURE)
        assertThat(natural.onConfigure(engineRunning = false, trackChanged = false)).isEqualTo(ConfigureAction.RECONFIGURE)
        assertThat(natural.onHandleBuffer(engineExists = true, streamingThreadExists = false)).isEqualTo(BufferAction.NORMAL)
    }
}
