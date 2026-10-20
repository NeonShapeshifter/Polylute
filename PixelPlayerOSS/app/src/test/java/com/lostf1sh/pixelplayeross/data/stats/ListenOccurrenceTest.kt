package com.lostf1sh.pixelplayeross.data.stats

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Test

/**
 * B28a: one id per occurrence of a track. A new item, a repeat-one loop and a replay from the start are new occurrences; a pause, a
 * focus or output loss, a seek inside the item and a player rebuild are the same one. Each case below is driven the way the service
 * reports it, and read back from the history file.
 */
class ListenOccurrenceTest {

    private val minute = TimeUnit.MINUTES.toMillis(1)

    @Test
    fun `a pause and the resume keep one occurrence and write one event`() {
        ListenHarness().use { h ->
            h.transition(songId = "1")
            h.advance(30_000L)
            // onIsPlayingChanged(false) syncs the tracker with the same song, not playing.
            h.sync("1", playing = false, positionMs = 30_000L)
            h.advance(10 * minute)
            h.sync("1", playing = true, positionMs = 30_000L)
            h.advance(30_000L)
            h.finish(TrackEndReason.USER_NEXT)

            val event = h.events().single()
            assertThat(event.occurrenceId).isEqualTo("o1")
            // The pause is not listening: a minute heard, ten not.
            assertThat(event.durationMs).isEqualTo(minute)
        }
    }

    @Test
    fun `a focus loss and an output loss are pauses and keep the occurrence`() {
        ListenHarness().use { h ->
            h.transition(songId = "1")
            h.advance(20_000L)
            // Focus lost: the engine pauses. The DAC unplugged: the guard pauses. The service sees isPlaying false both times.
            h.sync("1", playing = false, positionMs = 20_000L)
            h.advance(5 * minute)
            h.sync("1", playing = true, positionMs = 20_000L)
            h.advance(20_000L)
            h.sync("1", playing = false, positionMs = 40_000L)
            h.advance(2 * minute)
            h.sync("1", playing = true, positionMs = 40_000L)
            h.advance(20_000L)
            h.finish(TrackEndReason.USER_NEXT)

            val event = h.events().single()
            assertThat(event.occurrenceId).isEqualTo("o1")
            assertThat(event.durationMs).isEqualTo(60_000L)
        }
    }

    @Test
    fun `a seek inside the item keeps the occurrence, forward or back`() {
        ListenHarness().use { h ->
            h.transition(songId = "1")
            h.advance(20_000L)
            h.tracker.onSeekWithinItem(fromPositionMs = 20_000L, toPositionMs = 150_000L)
            h.advance(20_000L)
            // Back, but not to the start: the middle of the song again is the same listen.
            h.tracker.onSeekWithinItem(fromPositionMs = 170_000L, toPositionMs = 60_000L)
            h.advance(20_000L)
            h.finish(TrackEndReason.USER_NEXT)

            val event = h.events().single()
            assertThat(event.occurrenceId).isEqualTo("o1")
            assertThat(event.durationMs).isEqualTo(60_000L)
        }
    }

    @Test
    fun `a seek that nudges near the start is not a replay`() {
        ListenHarness().use { h ->
            h.transition(songId = "1")
            h.advance(30_000L)
            // 3.5 s back to 1.5 s: lands in the first two seconds, but did not come from far enough past them.
            h.tracker.onSeekWithinItem(fromPositionMs = 4_500L, toPositionMs = 1_500L)
            h.advance(30_000L)
            h.finish(TrackEndReason.USER_NEXT)

            assertThat(h.events().map { it.occurrenceId }).containsExactly("o1")
        }
    }

    @Test
    fun `a restart before anything was heard is the same occurrence`() {
        ListenHarness().use { h ->
            h.transition(songId = "1")
            h.advance(3_000L)
            h.tracker.onSeekWithinItem(fromPositionMs = 3_000L, toPositionMs = 0L)
            h.advance(40_000L)
            h.finish(TrackEndReason.USER_NEXT)

            val event = h.events().single()
            assertThat(event.occurrenceId).isEqualTo("o1")
            assertThat(event.durationMs).isEqualTo(43_000L)
        }
    }

    @Test
    fun `a replay from the start of the same item is a new occurrence`() {
        ListenHarness().use { h ->
            h.transition(songId = "1")
            h.advance(minute)
            // The seekbar dragged to the start, or Previous after three seconds: a seek inside the same item, landing on 0.
            h.tracker.onSeekWithinItem(fromPositionMs = minute, toPositionMs = 0L)
            awaitCount(h, 1)
            h.advance(30_000L)
            h.finish(TrackEndReason.USER_NEXT)

            val (first, second) = h.events()
            assertThat(first.occurrenceId).isEqualTo("o1")
            assertThat(first.durationMs).isEqualTo(minute)
            // Going back to the start is the listener's own action, but not leaving the song: the first pass ends as a replay.
            assertThat(first.endReason).isEqualTo(TrackEndReason.USER_REPLAY)
            assertThat(second.occurrenceId).isEqualTo("o2")
            assertThat(second.durationMs).isEqualTo(30_000L)
            assertThat(second.endReason).isEqualTo(TrackEndReason.USER_NEXT)
            // Both passes are of the same track.
            assertThat(second.songId).isEqualTo(first.songId)
        }
    }

    @Test
    fun `a replay keeps the identity the first pass had and plays on`() {
        ListenHarness(identityOfLocator = { "item-1" }).use { h ->
            h.transition(songId = "1")
            h.advance(minute)
            h.tracker.onSeekWithinItem(fromPositionMs = minute, toPositionMs = 500L)
            awaitCount(h, 1)
            h.advance(minute)
            h.finish(TrackEndReason.NATURAL_END)

            assertThat(h.events().map { it.libraryItemId }).containsExactly("item-1", "item-1")
        }
    }

    @Test
    fun `every loop of repeat-one is a new occurrence`() {
        ListenHarness().use { h ->
            h.transition(songId = "7", durationMs = 3 * minute)
            // Media3 reports each wrap as a media item transition with the repeat reason, which the service maps to a natural end.
            h.advance(3 * minute)
            h.transition(songId = "7", reason = TrackEndReason.NATURAL_END, durationMs = 3 * minute)
            awaitCount(h, 1)
            h.advance(3 * minute)
            h.transition(songId = "7", reason = TrackEndReason.NATURAL_END, durationMs = 3 * minute)
            awaitCount(h, 2)
            h.advance(3 * minute)
            h.finish(TrackEndReason.NATURAL_END)

            val events = h.events()
            assertThat(events.map { it.occurrenceId }).containsExactly("o1", "o2", "o3").inOrder()
            assertThat(events.map { it.songId }.toSet()).containsExactly("7")
            assertThat(events.map { it.outcome }.toSet()).containsExactly(PlaybackOutcome.COMPLETED)
        }
    }

    @Test
    fun `next and previous give each song a new occurrence, a song heard again included`() {
        ListenHarness().use { h ->
            h.transition(songId = "1")
            h.advance(minute)
            h.transition(songId = "2", reason = TrackEndReason.USER_NEXT)
            awaitCount(h, 1)
            h.advance(minute)
            h.transition(songId = "1", reason = TrackEndReason.USER_NEXT)
            awaitCount(h, 2)
            h.advance(minute)
            h.finish(TrackEndReason.USER_PREVIOUS)

            val events = h.events()
            assertThat(events.map { it.songId }).containsExactly("1", "2", "1").inOrder()
            assertThat(events.map { it.occurrenceId }).containsExactly("o1", "o2", "o3").inOrder()
        }
    }

    @Test
    fun `a crossfade to another song is a new occurrence`() {
        ListenHarness().use { h ->
            h.transition(songId = "1")
            h.advance(3 * minute)
            // The crossfade swaps the session player: the service syncs, and the incoming player's current item is the next song.
            h.sync("2", playing = true)
            awaitCount(h, 1)
            h.advance(minute)
            h.finish(TrackEndReason.USER_NEXT)

            assertThat(h.events().map { it.occurrenceId }).containsExactly("o1", "o2").inOrder()
        }
    }

    @Test
    fun `a player rebuild in the middle of the song keeps the occurrence`() {
        ListenHarness().use { h ->
            h.tracker.onAudioFacts(
                ListenAudioFactsAssembler.assemble(androidRoute(), com.lostf1sh.pixelplayeross.data.service.player.UsbDirectAudioDiagnostics(), AndroidOutput(ListenRoute.SPEAKER, null), null),
            )
            h.transition(songId = "1")
            h.advance(minute)
            // A DAC attaches and the engine rebuilds the master player on the same item at the same position: publishMediaSessionPlayer
            // syncs the tracker with the new player, which holds the same song, and the route is now Direct.
            h.tracker.onAudioFacts(ListenAudioFactsAssembler.assemble(directRoute(), directDiagnostics(), null, null))
            h.sync("1", playing = true, positionMs = minute)
            h.advance(minute)
            h.finish(TrackEndReason.USER_NEXT)

            val event = h.events().single()
            assertThat(event.occurrenceId).isEqualTo("o1")
            assertThat(event.durationMs).isEqualTo(2 * minute)
            // Half on the speaker and half on the DAC: a tie goes to the facts that carried the time first.
            assertThat(event.audio!!.route).isEqualTo(ListenRoute.SPEAKER)
            assertThat(event.audio!!.share).isEqualTo(50)
        }
    }

    @Test
    fun `a song that played to its end and is started again is a new occurrence`() {
        ListenHarness().use { h ->
            h.transition(songId = "1", durationMs = 3 * minute)
            h.advance(3 * minute)
            // STATE_ENDED closes the session; the player goes on reporting the ended song as current, so the next sync reopens it.
            h.tracker.finalizeCurrentSession(endReason = TrackEndReason.NATURAL_END)
            awaitCount(h, 1)
            h.sync("1", playing = false, positionMs = 3 * minute, durationMs = 3 * minute)
            h.sync("1", playing = true, positionMs = 0L, durationMs = 3 * minute)
            h.advance(minute)
            h.finish(TrackEndReason.USER_NEXT)

            // o2 is the session reopened on the ended song, which the replay then goes on in.
            assertThat(h.events().map { it.occurrenceId }).containsExactly("o1", "o2").inOrder()
        }
    }

    @Test
    fun `occurrence ids are short and differ`() {
        val ids = (1..2000).map { ListenOccurrence.newId() }
        assertThat(ids.toSet()).hasSize(ids.size)
        assertThat(ids.maxOf { it.length }).isAtMost(13)
        assertThat(ids.all { id -> id.all { it.isDigit() || it in 'a'..'z' } }).isTrue()
    }

    @Test
    fun `the replay rule needs a listen a landing near the start and a real jump`() {
        // Heard enough, lands at the start, comes from far past it.
        assertThat(ListenOccurrence.isReplayFromStart(60_000L, 60_000L, 0L)).isTrue()
        assertThat(ListenOccurrence.isReplayFromStart(60_000L, 60_000L, 2_000L)).isTrue()
        // Lands just past the window.
        assertThat(ListenOccurrence.isReplayFromStart(60_000L, 60_000L, 2_001L)).isFalse()
        // Not enough heard to have written anything.
        assertThat(ListenOccurrence.isReplayFromStart(4_999L, 60_000L, 0L)).isFalse()
        // Too small a jump.
        assertThat(ListenOccurrence.isReplayFromStart(60_000L, 6_999L, 2_000L)).isFalse()
        assertThat(ListenOccurrence.isReplayFromStart(60_000L, 7_000L, 2_000L)).isTrue()
        // A seek forward is never a replay.
        assertThat(ListenOccurrence.isReplayFromStart(60_000L, 0L, 90_000L)).isFalse()
    }

    private fun awaitCount(h: ListenHarness, count: Int) {
        h.awaitEvents(count)
    }
}
