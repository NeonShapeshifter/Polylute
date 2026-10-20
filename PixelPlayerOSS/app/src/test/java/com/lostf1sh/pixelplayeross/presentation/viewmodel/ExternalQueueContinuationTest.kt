package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.service.RestoreQueuePolicy.Presence
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * FIX-1 (B11-fix, device 2026-10-07): an external VIEW of a file in a 2855-file folder took 10-50 s to
 * start, because the queue was built (an open per sibling) before the song that was opened could play.
 * The song starts alone; the tail is built from the index and attached behind it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExternalQueueContinuationTest {

    private fun siblings(n: Int) = (1..n).map { "song-$it" }

    @Test
    fun `the opened song starts at once, whatever the size of the folder`() {
        runTest {
            val probes = mutableListOf<String>()
            var startedAt = -1L
            var attached: List<String>? = null
            val playback = ExternalPlayback<String>(
                start = { startedAt = currentTime },
                // The index read for 3000 siblings, and a probe that costs 10 ms an open.
                loadSiblings = { delay(200); siblings(3000) },
                probe = { probes += it; delay(10); Presence.PRESENT },
                attach = { attached = it },
            )

            val tail = playback.run(this)

            // `run` has returned and the song was started with no virtual time spent: neither the
            // listing nor a single probe is on the path to play().
            assertThat(startedAt).isEqualTo(0L)
            assertThat(currentTime).isEqualTo(0L)
            assertThat(attached).isNull()

            tail.join()
            assertThat(attached).hasSize(3000)
            // Checking the whole folder would have been 3000 x 10 ms = 30 s after the listing.
            assertThat(currentTime).isLessThan(1_000L)
            assertThat(probes.size).isAtMost(ExternalQueueContinuation.MAX_LOOKAHEAD_PROBES)
        }
    }

    @Test
    fun `a next sibling that no longer exists is skipped without an error`() {
        runTest {
            var attached: List<String>? = null
            val playback = ExternalPlayback<String>(
                start = {},
                loadSiblings = { siblings(10) },
                probe = { if (it == "song-1" || it == "song-2") Presence.MISSING else Presence.PRESENT },
                attach = { attached = it },
            )
            playback.run(this).join()
            assertThat(attached).isNotNull()
            assertThat(attached!!.first()).isEqualTo("song-3")
            assertThat(attached).doesNotContain("song-1")
            assertThat(attached).doesNotContain("song-2")
            assertThat(attached).hasSize(8)
        }
    }

    @Test
    fun `only the head is checked, and never more than the cap, however many are dead`() {
        runTest {
            val probes = mutableListOf<String>()
            val kept = ExternalQueueContinuation.pruneHead(siblings(3000)) {
                probes += it
                Presence.MISSING
            }
            assertThat(probes).hasSize(ExternalQueueContinuation.MAX_LOOKAHEAD_PROBES)
            // The first 8 are dropped; everything further out is left as indexed, dead or not.
            assertThat(kept).hasSize(3000 - ExternalQueueContinuation.MAX_LOOKAHEAD_PROBES)
            assertThat(kept.first()).isEqualTo("song-9")
        }
    }

    @Test
    fun `checking stops once a few in a row are known to exist`() {
        runTest {
            val probes = mutableListOf<String>()
            val kept = ExternalQueueContinuation.pruneHead(siblings(100)) {
                probes += it
                Presence.PRESENT
            }
            assertThat(probes).hasSize(ExternalQueueContinuation.PLAYABLE_LOOKAHEAD)
            assertThat(kept).hasSize(100)
        }
    }

    @Test
    fun `an unknown answer keeps the entry`() {
        runTest {
            val kept = ExternalQueueContinuation.pruneHead(siblings(5)) { Presence.UNKNOWN }
            assertThat(kept).hasSize(5)
        }
    }

    @Test
    fun `nothing is attached when there is nothing behind the song`() {
        runTest {
            var attachCalls = 0
            ExternalPlayback<String>(
                start = {},
                loadSiblings = { emptyList() },
                probe = { Presence.PRESENT },
                attach = { attachCalls++ },
            ).run(this).join()
            assertThat(attachCalls).isEqualTo(0)
        }
    }

    @Test
    fun `the listing is not asked for until the song has been started`() {
        runTest {
            val order = mutableListOf<String>()
            ExternalPlayback<String>(
                start = { order += "start" },
                loadSiblings = { order += "list"; siblings(2) },
                probe = { order += "probe"; Presence.PRESENT },
                attach = { order += "attach" },
            ).run(this).join()
            assertThat(order.first()).isEqualTo("start")
            assertThat(order.last()).isEqualTo("attach")
        }
    }
}
