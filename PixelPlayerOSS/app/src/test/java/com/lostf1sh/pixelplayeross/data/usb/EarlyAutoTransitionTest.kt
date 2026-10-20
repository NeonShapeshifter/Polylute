package com.lostf1sh.pixelplayeross.data.usb

import com.decent.usbaudio.media3.NaturalEndCoordinator
import com.decent.usbaudio.media3.NaturalEndCoordinator.NextOffset
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * AUD-137 (`device-b7fix2-mixed`, 19:33:20): Media3's AUTO arrived 35 ms *before* the sink saw the
 * engine's end of stream. The transition found the old engine still running, so no engine was made
 * for the new item; the natural end marked 35 ms later waited for a transition that had already
 * happened, and 4 s on the watchdog "fell back to advancing" — a spurious skip of the track that had
 * just started, which also applied that track's deferred 192 kHz config to the one after it (48 kHz):
 * two "Rate mismatch … falling back to ExoPlayer pipeline".
 *
 * Now an AUTO that comes while the ended item's engine still plays is recorded, and the end of stream
 * within [NaturalEndCoordinator.EARLY_TRANSITION_WINDOW_MS] resolves it at once; the watchdog never
 * advances once the item has changed; and a deferred config applies only to the transition it was
 * deferred for.
 */
class EarlyAutoTransitionTest {

    private data class Track(val path: String, val rate: Int, val durationUs: Long)

    /** The sink's handlers as the coordinator drives them, with Media3's transitions scripted. */
    private class Sink(private val tracks: List<Track>) {
        val natural = NaturalEndCoordinator()
        var transitionSeq = 0L               // every onMediaItemTransition
        var mediaItem = 0                    // Media3's current item
        private var engineItem = 0
        private var engineSeq = 0L           // transitionSeq when the running engine was created
        var engineRunning = true
        private var enginePending = false
        var streamRate = tracks[0].rate
        private var deferredRate: Int? = null
        private var deferredForSeq = -1L
        val opened = mutableListOf(tracks[0].path)
        var rateMismatches = 0
        var extraAdvances = 0
        var offsetFromMedia3 = 0

        /** Media3 reads ahead: the next item's configure, deferred while the engine plays. */
        fun readAheadConfigure() {
            val next = tracks[mediaItem + 1]
            if (next.rate != streamRate) {
                deferredRate = next.rate
                deferredForSeq = transitionSeq + 1
            }
        }

        /** onMediaItemTransition (main thread), then the two posts to the playback thread. */
        fun transition(reason: Int, nowMs: Long) {
            transitionSeq++
            mediaItem += 1   // AUTO, or the fallback's seekToNextMediaItem
            enginePending = true
            cleanup(reason)
            createPendingEngine(reason, nowMs)
        }

        fun engineEndOfStream(nowMs: Long) {
            engineRunning = false
            natural.onEngineFinished(tracks[engineItem].durationUs, 1_000_000_000_000L, nowMs)
            if (natural.consumeEarlyTransition(nowMs)) {
                cleanup(1)
                createPendingEngine(1, nowMs)
            }
        }

        /** The natural-end watchdog, [NaturalEndCoordinator.WATCHDOG_MS] after the end of stream. */
        fun watchdog(nowMs: Long, endedSeq: Long) {
            if (natural.overdue(nowMs, itemChanged = transitionSeq != endedSeq)) {
                extraAdvances++
                natural.onUserSeek()
                transition(reason = 2, nowMs = nowMs)   // seekToNextMediaItem
            }
        }

        fun earlyWindowElapsed(nowMs: Long) {
            if (!natural.earlyTransitionExpired(nowMs)) return
            natural.clearEarlyTransition()
            if (!enginePending) return
            engineRunning = false                      // stop the old engine, then open the current item
            cleanup(1)
            createPendingEngine(1, nowMs)
        }

        val endedEngineSeq: Long get() = engineSeq

        private fun cleanup(reason: Int) {
            if (engineRunning) return
            val rate = deferredRate
            if (rate != null) {
                if (NaturalEndCoordinator.deferredConfigApplies(deferredForSeq, transitionSeq)) streamRate = rate
                deferredRate = null
            }
            // A seek re-enables the renderers: Media3 configures the item it lands on.
            if (reason == 2) streamRate = tracks[mediaItem].rate
        }

        private fun createPendingEngine(reason: Int, nowMs: Long) {
            if (!enginePending) return
            if (engineRunning && engineSeq != transitionSeq && !natural.pending &&
                NaturalEndCoordinator.isSequential(reason)
            ) {
                natural.onEarlyTransition(nowMs)
                return
            }
            enginePending = false
            if (natural.onItemTransition(media3OffsetUs = 7L) is NextOffset.FromMedia3) offsetFromMedia3++
            val track = tracks[mediaItem]
            if (track.rate != streamRate) {
                rateMismatches++
                return
            }
            engineItem = mediaItem
            engineSeq = transitionSeq
            engineRunning = true
            opened += track.path
        }
    }

    private val tracks = listOf(
        Track("Previous.flac", 44_100, 191_013_000L),
        Track("Royals.flac", 192_000, 190_009_000L),
        Track("RUBICON.flac", 48_000, 238_447_000L),
    )

    @Test
    fun `an AUTO 35 ms before the natural end - no extra advance, no rate mismatch, the right track`() {
        val s = Sink(tracks)
        s.readAheadConfigure()
        s.transition(reason = 1, nowMs = 20_503L)          // Media3 crosses first
        val endedSeq = s.endedEngineSeq
        s.engineEndOfStream(nowMs = 20_538L)               // the sink sees the EOF 35 ms later
        // Opened at the end of stream itself, not half a second later by the window's expiry.
        assertThat(s.opened).containsExactly("Previous.flac", "Royals.flac").inOrder()
        s.earlyWindowElapsed(nowMs = 21_003L)
        s.watchdog(nowMs = 20_538L + NaturalEndCoordinator.WATCHDOG_MS, endedSeq = endedSeq)

        assertThat(s.extraAdvances).isEqualTo(0)
        assertThat(s.rateMismatches).isEqualTo(0)
        assertThat(s.opened).containsExactly("Previous.flac", "Royals.flac").inOrder()
        assertThat(s.mediaItem).isEqualTo(1)
        assertThat(s.streamRate).isEqualTo(192_000)
        assertThat(s.offsetFromMedia3).isEqualTo(1)
    }

    @Test
    fun `an AUTO that never comes - the fallback still advances once, at the deferred rate`() {
        val s = Sink(tracks)
        s.readAheadConfigure()
        val endedSeq = s.endedEngineSeq
        s.engineEndOfStream(nowMs = 20_538L)
        s.watchdog(nowMs = 20_538L + NaturalEndCoordinator.WATCHDOG_MS, endedSeq = endedSeq)

        assertThat(s.extraAdvances).isEqualTo(1)
        assertThat(s.rateMismatches).isEqualTo(0)
        assertThat(s.opened).containsExactly("Previous.flac", "Royals.flac").inOrder()
        assertThat(s.streamRate).isEqualTo(192_000)
    }

    @Test
    fun `an AUTO long before the end - the old engine is switched off after the window, not left playing`() {
        val s = Sink(tracks)
        s.readAheadConfigure()
        s.transition(reason = 1, nowMs = 10_000L)
        assertThat(s.opened).hasSize(1)
        s.earlyWindowElapsed(nowMs = 10_000L + NaturalEndCoordinator.EARLY_TRANSITION_WINDOW_MS)
        assertThat(s.opened).containsExactly("Previous.flac", "Royals.flac").inOrder()
        assertThat(s.rateMismatches).isEqualTo(0)
    }

    @Test
    fun `the watchdog never advances once the item has changed`() {
        val natural = NaturalEndCoordinator()
        natural.onEngineFinished(1_000L, 5_000L, nowMs = 0L)
        assertThat(natural.overdue(NaturalEndCoordinator.WATCHDOG_MS, itemChanged = true)).isFalse()
        assertThat(natural.overdue(NaturalEndCoordinator.WATCHDOG_MS, itemChanged = false)).isTrue()
    }

    @Test
    fun `the early window is 500 ms, and a seek clears it`() {
        val natural = NaturalEndCoordinator()
        natural.onEarlyTransition(nowMs = 1_000L)
        assertThat(natural.earlyTransitionExpired(1_499L)).isFalse()
        assertThat(natural.earlyTransitionExpired(1_500L)).isTrue()
        natural.onEngineFinished(1_000L, 5_000L, nowMs = 1_600L)
        assertThat(natural.consumeEarlyTransition(1_600L)).isFalse()   // outside the window
        natural.onEarlyTransition(nowMs = 2_000L)
        natural.onUserSeek()
        assertThat(natural.consumeEarlyTransition(2_010L)).isFalse()
    }

    @Test
    fun `a deferred config belongs to the transition it was deferred for`() {
        assertThat(NaturalEndCoordinator.deferredConfigApplies(deferredForSeq = 8L, transitionSeq = 8L)).isTrue()
        // The device: Royals' 192 kHz applied when the spurious skip opened RUBICON (one later).
        assertThat(NaturalEndCoordinator.deferredConfigApplies(deferredForSeq = 8L, transitionSeq = 9L)).isFalse()
        // A seek inside the current item, before its transition: not yet.
        assertThat(NaturalEndCoordinator.deferredConfigApplies(deferredForSeq = 8L, transitionSeq = 7L)).isFalse()
        assertThat(NaturalEndCoordinator.isSequential(1)).isTrue()
        assertThat(NaturalEndCoordinator.isSequential(0)).isTrue()
        assertThat(NaturalEndCoordinator.isSequential(2)).isFalse()
    }
}
