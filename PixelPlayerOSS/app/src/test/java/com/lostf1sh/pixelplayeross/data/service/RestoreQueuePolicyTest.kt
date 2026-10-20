package com.lostf1sh.pixelplayeross.data.service

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.service.RestoreQueuePolicy.Presence
import org.junit.jupiter.api.Test

/**
 * F-09 (device, 2026-10-07): the restored queue pointed to files deleted from storage; the player sat
 * in ERROR, and came back in ERROR after a process restart. Only choosing a song from the Library fixed
 * it. On restore, the queue loses the songs whose file is gone and playback is parked on one that exists.
 */
class RestoreQueuePolicyTest {

    /** A probe that answers from [missing] / [unknown] and records what it was asked. */
    private class Probe(
        private val missing: Set<Int> = emptySet(),
        private val unknown: Set<Int> = emptySet(),
    ) {
        val asked = mutableListOf<Int>()
        fun answer(index: Int): Presence {
            asked += index
            return when (index) {
                in missing -> Presence.MISSING
                in unknown -> Presence.UNKNOWN
                else -> Presence.PRESENT
            }
        }
    }

    @Test
    fun `a queue whose files are all there comes back untouched and costs one probe`() {
        val probe = Probe()
        val selection = RestoreQueuePolicy.select(count = 100, savedIndex = 37, probe = probe::answer)
        assertThat(selection.keptPositions).isEqualTo((0 until 100).toList())
        assertThat(selection.startPosition).isEqualTo(37)
        assertThat(selection.droppedCount).isEqualTo(0)
        assertThat(selection.startWasReplaced).isFalse()
        // Thousands of songs are not opened one by one at start-up.
        assertThat(probe.asked).containsExactly(37)
    }

    @Test
    fun `the current song gone moves playback to the next one that exists, from the start`() {
        val probe = Probe(missing = setOf(2, 3))
        val selection = RestoreQueuePolicy.select(count = 6, savedIndex = 2, probe = probe::answer)
        // 2 and 3 are dropped; 0, 1 were not probed so they come back as saved.
        assertThat(selection.keptPositions).containsExactly(0, 1, 4, 5).inOrder()
        // Playback starts on what was song 4, now the third kept entry.
        assertThat(selection.startPosition).isEqualTo(2)
        assertThat(selection.droppedCount).isEqualTo(2)
        // The saved position belonged to a song that is gone.
        assertThat(selection.startWasReplaced).isTrue()
        assertThat(probe.asked).containsExactly(2, 3, 4).inOrder()
    }

    @Test
    fun `nothing from the current song on exists, so it falls back to the nearest earlier one`() {
        val probe = Probe(missing = setOf(3, 4, 5))
        val selection = RestoreQueuePolicy.select(count = 6, savedIndex = 3, probe = probe::answer)
        assertThat(selection.keptPositions).containsExactly(0, 1, 2).inOrder()
        assertThat(selection.startPosition).isEqualTo(2)
        assertThat(selection.droppedCount).isEqualTo(3)
        assertThat(selection.startWasReplaced).isTrue()
    }

    @Test
    fun `a queue with no file left is not restored at all`() {
        val probe = Probe(missing = (0 until 4).toSet())
        val selection = RestoreQueuePolicy.select(count = 4, savedIndex = 1, probe = probe::answer)
        assertThat(selection.keptPositions).isEmpty()
        assertThat(selection.startPosition).isNull()
        assertThat(selection.droppedCount).isEqualTo(4)
    }

    @Test
    fun `an unknown answer is never a reason to drop a song`() {
        // A volume that is not mounted, or a file the app cannot open, is not a deleted file.
        val probe = Probe(unknown = setOf(1))
        val selection = RestoreQueuePolicy.select(count = 3, savedIndex = 1, probe = probe::answer)
        assertThat(selection.keptPositions).containsExactly(0, 1, 2).inOrder()
        assertThat(selection.startPosition).isEqualTo(1)
        assertThat(selection.droppedCount).isEqualTo(0)
        assertThat(selection.startWasReplaced).isFalse()
    }

    @Test
    fun `songs after the start are kept as saved, even if they are gone`() {
        // Not probed, so not known; the player's own error handling skips a dead one when it gets there.
        val probe = Probe(missing = setOf(8, 9))
        val selection = RestoreQueuePolicy.select(count = 10, savedIndex = 0, probe = probe::answer)
        assertThat(selection.keptPositions).isEqualTo((0 until 10).toList())
        assertThat(selection.droppedCount).isEqualTo(0)
    }

    @Test
    fun `a saved index outside the queue is clamped, and an empty queue is empty`() {
        assertThat(RestoreQueuePolicy.select(3, savedIndex = 99, probe = Probe()::answer).startPosition).isEqualTo(2)
        assertThat(RestoreQueuePolicy.select(3, savedIndex = -5, probe = Probe()::answer).startPosition).isEqualTo(0)
        val empty = RestoreQueuePolicy.select(0, savedIndex = 0) { Presence.PRESENT }
        assertThat(empty.keptPositions).isEmpty()
        assertThat(empty.startPosition).isNull()
    }

    @Test
    fun `each item is probed at most once`() {
        val probe = Probe(missing = setOf(0, 1, 2, 3, 4))
        RestoreQueuePolicy.select(count = 8, savedIndex = 0, probe = probe::answer)
        assertThat(probe.asked).containsNoDuplicates()
    }

    @Test
    fun `a queue of thousands that is all gone costs a bounded number of opens`() {
        // FIX-1 (B11-fix): the restore must not cost seconds of opens at start-up on a big dead queue.
        val probe = Probe(missing = (0 until 5000).toSet())
        val selection = RestoreQueuePolicy.select(count = 5000, savedIndex = 0, probe = probe::answer)
        assertThat(probe.asked).hasSize(RestoreQueuePolicy.MAX_PROBES)
        assertThat(selection.probedCount).isEqualTo(RestoreQueuePolicy.MAX_PROBES)
        // Nothing playable within reach: the dead queue is not restored.
        assertThat(selection.startPosition).isNull()
        assertThat(selection.keptPositions).isEmpty()
    }

    @Test
    fun `a playable song within reach of a long dead run is still found`() {
        val probe = Probe(missing = (0 until 100).toSet())
        val selection = RestoreQueuePolicy.select(count = 5000, savedIndex = 0, probe = probe::answer)
        assertThat(selection.startPosition).isEqualTo(0)
        assertThat(selection.droppedCount).isEqualTo(100)
        assertThat(selection.probedCount).isEqualTo(101)
        assertThat(selection.keptPositions.first()).isEqualTo(100)
    }

    @Test
    fun `the common restore costs one open`() {
        val probe = Probe()
        val selection = RestoreQueuePolicy.select(count = 5000, savedIndex = 2500, probe = probe::answer)
        assertThat(selection.probedCount).isEqualTo(1)
        assertThat(probe.asked).containsExactly(2500)
    }
}
