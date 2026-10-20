package com.lostf1sh.pixelplayeross.data.stats

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Test

/**
 * B28 D0: a skip is told by what the listener did and not by how the player happened to move, and every listen carries where its
 * queue came from. Each case is driven the way the service reports it and read back from the history file.
 */
class ListenDepartureAndSourceTest {

    private val minute = TimeUnit.MINUTES.toMillis(1)

    // --- the listener's departure --------------------------------------------------------------------------

    @Test
    fun `a Previous that the player reports as a seek is recorded as the listener's Previous`() {
        ListenHarness().use { h ->
            h.transition(songId = "1")
            h.advance(10_000L)
            h.tracker.noteUserDeparture(TrackEndReason.USER_PREVIOUS)
            h.advance(50L)
            // The player moved to the previous item as a seek transition.
            h.finish(TrackEndReason.UNKNOWN)

            assertThat(h.events().single().endReason).isEqualTo(TrackEndReason.USER_PREVIOUS)
        }
    }

    @Test
    fun `a pick in a new queue that the player reports as a replaced playlist is the listener's own pick`() {
        ListenHarness().use { h ->
            h.transition(songId = "1")
            h.advance(8_000L)
            h.tracker.noteUserDeparture(TrackEndReason.USER_SELECTED_OTHER)
            h.advance(20L)
            h.finish(TrackEndReason.QUEUE_REPLACED)

            assertThat(h.events().single().endReason).isEqualTo(TrackEndReason.USER_SELECTED_OTHER)
        }
    }

    @Test
    fun `a track that ran to its end stays a natural end even when Next was pressed a moment before`() {
        ListenHarness().use { h ->
            h.transition(songId = "1")
            h.advance(minute)
            h.tracker.noteUserDeparture(TrackEndReason.USER_NEXT)
            h.advance(100L)
            h.finish(TrackEndReason.NATURAL_END)

            assertThat(h.events().single().endReason).isEqualTo(TrackEndReason.NATURAL_END)
        }
    }

    @Test
    fun `an interruption and a replay keep the reason they were reported with`() {
        ListenHarness().use { h ->
            h.transition(songId = "1")
            h.advance(minute)
            h.tracker.noteUserDeparture(TrackEndReason.USER_NEXT)
            h.finish(TrackEndReason.INTERRUPTED)
            h.transition(songId = "2")
            h.advance(minute)
            h.tracker.noteUserDeparture(TrackEndReason.USER_NEXT)
            h.finish(TrackEndReason.USER_REPLAY)

            assertThat(h.events().map { it.endReason }).containsExactly(TrackEndReason.INTERRUPTED, TrackEndReason.USER_REPLAY).inOrder()
        }
    }

    @Test
    fun `a departure noted long ago is not the reason of a later close`() {
        ListenHarness().use { h ->
            h.transition(songId = "1")
            h.advance(10_000L)
            h.tracker.noteUserDeparture(TrackEndReason.USER_NEXT)
            // Nothing closed the track for a minute: the note is stale, the player's own reason stands.
            h.advance(minute)
            h.finish(TrackEndReason.UNKNOWN)

            assertThat(h.events().single().endReason).isEqualTo(TrackEndReason.UNKNOWN)
        }
    }

    @Test
    fun `a departure is used once`() {
        ListenHarness().use { h ->
            h.transition(songId = "1")
            h.advance(minute)
            h.tracker.noteUserDeparture(TrackEndReason.USER_NEXT)
            h.advance(50L)
            h.finish(TrackEndReason.UNKNOWN)
            h.transition(songId = "2")
            h.advance(minute)
            h.advance(50L)
            h.finish(TrackEndReason.UNKNOWN)

            assertThat(h.events().map { it.endReason }).containsExactly(TrackEndReason.USER_NEXT, TrackEndReason.UNKNOWN).inOrder()
        }
    }

    // --- where the queue came from --------------------------------------------------------------------------

    @Test
    fun `every listen of a queue records the source it was loaded from`() {
        ListenHarness().use { h ->
            h.tracker.setQueueSource(ListenSource(ListenSourceKind.ALBUM, "42"))
            h.transition(songId = "1", context = ListenContext(shuffle = true))
            h.advance(minute)
            h.finish(TrackEndReason.NATURAL_END)
            h.transition(songId = "2", context = ListenContext(shuffle = true))
            h.advance(minute)
            h.finish(TrackEndReason.USER_NEXT)

            val contexts = h.events().map { it.context }
            assertThat(contexts.map { it?.kind }).containsExactly(ListenSourceKind.ALBUM, ListenSourceKind.ALBUM)
            assertThat(contexts.map { it?.ref }).containsExactly("42", "42")
            // What the player knows about the queue is kept next to the source.
            assertThat(contexts.map { it?.shuffle }).containsExactly(true, true)
        }
    }

    @Test
    fun `a queue loaded by a caller that does not say is recorded without the previous queue's source`() {
        ListenHarness().use { h ->
            h.tracker.setQueueSource(ListenSource(ListenSourceKind.PLAYLIST, "7"))
            h.transition(songId = "1")
            h.advance(minute)
            h.finish(TrackEndReason.NATURAL_END)

            h.tracker.setQueueSource(null)
            h.transition(songId = "2")
            h.advance(minute)
            h.finish(TrackEndReason.USER_NEXT)

            val (first, second) = h.events().map { it.context }
            assertThat(first?.kind).isEqualTo(ListenSourceKind.PLAYLIST)
            assertThat(first?.ref).isEqualTo("7")
            assertThat(second?.ref).isNull()
            assertThat(second?.kind).isNotEqualTo(ListenSourceKind.PLAYLIST)
        }
    }

    @Test
    fun `a file opened from outside keeps being an external view whatever queue was loaded before`() {
        ListenHarness().use { h ->
            h.tracker.setQueueSource(ListenSource(ListenSourceKind.LIBRARY))
            h.transition(songId = "1", context = ListenContext(kind = ListenSourceKind.EXTERNAL_VIEW))
            h.advance(minute)
            h.finish(TrackEndReason.USER_NEXT)

            val context = h.events().single().context
            assertThat(context?.kind).isEqualTo(ListenSourceKind.EXTERNAL_VIEW)
            assertThat(context?.ref).isNull()
        }
    }
}
