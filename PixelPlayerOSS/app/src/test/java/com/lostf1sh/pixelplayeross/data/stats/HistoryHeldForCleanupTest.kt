package com.lostf1sh.pixelplayeross.data.stats

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test

/**
 * What the clean-up of items without a file can rely on while it acts (R28-01): the identities the history names do not change
 * under it, and a listen that has not reached the file yet is answered for by the tracker. Real tracker, real history file.
 */
class HistoryHeldForCleanupTest {

    @Test
    fun `the identities are those of the events in the file, and an event without an identity names none`() {
        ListenHarness(identityOfLocator = { "item-$it" }).use { h ->
            runBlocking {
                h.repository.recordPlayback(songId = "1", durationMs = 30_000L, libraryItemId = "item-1")
                h.repository.recordPlayback(songId = "2", durationMs = 30_000L, libraryItemId = "item-2")
                h.repository.recordPlayback(songId = "3", durationMs = 30_000L, libraryItemId = "item-1")
                h.repository.recordPlayback(songId = "4", durationMs = 30_000L, libraryItemId = null)

                val named = h.repository.withHistoryIdentities { it }

                assertThat(named).containsExactly("item-1", "item-2")
            }
        }
    }

    @Test
    fun `a listen is not written while the history is held, and is written when it is let go`() {
        ListenHarness().use { h ->
            runBlocking {
                val writer = withTimeout(5_000) {
                    h.repository.withHistoryIdentities { named ->
                        assertThat(named).isEmpty()
                        val pending = async(Dispatchers.IO) {
                            h.repository.recordPlayback(songId = "1", durationMs = 30_000L, libraryItemId = "item-1")
                        }
                        // Long enough for a write that was not held back to have finished.
                        delay(300)
                        assertThat(pending.isCompleted).isFalse()
                        assertThat(h.events()).isEmpty()
                        pending
                    }
                }
                withTimeout(5_000) { writer.await() }

                assertThat(h.events().map { it.libraryItemId }).containsExactly("item-1")
            }
        }
    }

    @Test
    fun `a listen that ended and is still being written is answered for by the tracker until it is on disk`() {
        ListenHarness(identityOfLocator = { "item-1" }).use { h ->
            runBlocking {
                h.transition("1")
                h.awaitLookups(1)
                // In progress. The lookup answered a moment ago (it is awaited by identitiesInFlight itself).
                assertThat(h.tracker.identitiesInFlight().identities).containsExactly("item-1")
                h.advance(60_000L)

                // Ended while the history is held: the write waits, the session is gone, the identity is still answered for.
                h.repository.withHistoryIdentities { named ->
                    assertThat(named).isEmpty()
                    h.tracker.finalizeCurrentSession(endReason = TrackEndReason.USER_NEXT)
                    delay(300)
                    assertThat(h.events()).isEmpty()
                    assertThat(h.tracker.sessionSnapshot()).isNull()
                    assertThat(h.tracker.identitiesInFlight().identities).containsExactly("item-1")
                }

                h.awaitEvents(1)
                val deadline = System.nanoTime() + 5_000_000_000L
                while (h.tracker.identitiesInFlight().identities.isNotEmpty() && System.nanoTime() < deadline) delay(10)
                // On disk now: the history names it, and the tracker no longer needs to.
                assertThat(h.tracker.identitiesInFlight().identities).isEmpty()
                assertThat(h.repository.withHistoryIdentities { it }).containsExactly("item-1")
            }
        }
    }

    @Test
    fun `a session whose lookup found no identity is not an identity in flight, and the wait ends when the lookup does`() {
        ListenHarness().use { h ->
            runBlocking {
                h.transition("1")
                h.awaitLookups(1)

                val inFlight = h.tracker.identitiesInFlight()

                assertThat(inFlight.identities).isEmpty()
                assertThat(inFlight.settled).isTrue()
            }
        }
    }
}
