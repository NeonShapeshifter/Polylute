package com.lostf1sh.pixelplayeross.data.service

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class B63RestoreFailureTest {
    @Test fun `failed restore leaves saved snapshot byte equal on an empty persist`() = runTest {
        val store = com.lostf1sh.pixelplayeross.data.preferences.PlaybackSnapshotStore(kotlinx.serialization.json.Json)
        val prefs = androidx.datastore.preferences.core.mutablePreferencesOf()
        val snapshot = com.lostf1sh.pixelplayeross.data.model.PlaybackQueueSnapshot(
            items = List(5000) { com.lostf1sh.pixelplayeross.data.model.PlaybackQueueItemSnapshot("$it", "content://synthetic/$it", "Synthetic $it") },
            currentIndex = 3823, currentMediaId = "3823", currentPositionMs = 45678, playWhenReady = false, savedAtEpochMs = 1,
        )
        store.write(prefs,snapshot)
        fun bytes() = prefs.asMap().entries.sortedBy { it.key.name }.joinToString("\n") { "${it.key.name}=${it.value}" }.toByteArray()
        val before = bytes()
        val barrier = PlaybackRestoreBarrier(this) { throw java.io.IOException("storage unavailable") }
        assertFalse(barrier.awaitCompletion())
        repeat(2) { if (barrier.mayPersistSnapshot(false)) store.write(prefs,null) }
        assertArrayEquals(before, bytes())
        assertEquals(snapshot,store.read(prefs))
        assertFalse(barrier.mayPersistSnapshot(false, applyingRestore = false))
    }
    @Test fun `a real user queue is saved after restore failure`() = runTest {
        var saved = "old queue"
        val barrier = PlaybackRestoreBarrier(this) { throw java.io.IOException("storage unavailable") }
        assertFalse(barrier.awaitCompletion())
        if (barrier.mayPersistSnapshot(true)) saved = "user queue"
        assertEquals("user queue", saved)
        assertFalse(barrier.mayPersistSnapshot(false))
    }
}
