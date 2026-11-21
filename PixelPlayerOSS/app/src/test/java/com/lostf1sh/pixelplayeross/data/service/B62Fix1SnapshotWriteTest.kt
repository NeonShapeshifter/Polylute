package com.lostf1sh.pixelplayeross.data.service

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.PlaybackQueueItemSnapshot
import com.lostf1sh.pixelplayeross.data.model.PlaybackQueueSnapshot
import com.lostf1sh.pixelplayeross.data.preferences.PlaybackSnapshotStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class B62Fix1SnapshotWriteTest {
    private val saved = PlaybackQueueSnapshot(
        items = List(5_000) { PlaybackQueueItemSnapshot("id-$it", "content://synthetic/songs/$it", "Track $it") },
        currentIndex = 4_990, currentMediaId = "id-4990", currentPositionMs = 45_678, playWhenReady = true,
        savedAtEpochMs = 1,
    )

    @Test fun `snapshot bytes survive persists before and after a waiter timeout`() = runTest {
        val store = PlaybackSnapshotStore(Json)
        val prefs = mutablePreferencesOf()
        store.write(prefs, saved)
        fun bytes() = prefs.asMap().entries.sortedBy { it.key.name }
            .joinToString("\n") { "${it.key.name}=${it.value}" }.toByteArray()
        val before = bytes()
        var live: PlaybackQueueSnapshot? = null
        var writes = 0
        val restore = PlaybackRestoreBarrier(this) { delay(6_000); live = saved.copy(playWhenReady = false) }
        fun persist(value: PlaybackQueueSnapshot?) {
            if (!restore.mayPersistSnapshot(value?.items?.isNotEmpty() == true)) return
            writes++; store.write(prefs, value)
        }
        val startup = async { restore.awaitCompletion() }
        val widget = async { restore.await() }
        runCurrent()
        persist(live)
        advanceTimeBy(4_001); runCurrent()
        assertThat(widget.await()).isFalse()
        assertThat(startup.isCompleted).isFalse()
        persist(live) // Both the debounce and unload can capture null here.
        assertThat(writes).isEqualTo(0)
        assertThat(bytes()).isEqualTo(before)
        assertThat(store.read(prefs)).isEqualTo(saved)
        advanceTimeBy(2_000); runCurrent()
        assertThat(startup.await()).isTrue()
        assertThat(live).isEqualTo(saved.copy(playWhenReady = false))
        persist(live)
        assertThat(writes).isEqualTo(1)
        assertThat(store.read(prefs)).isEqualTo(live)
    }

    @Test fun `a user queue is persisted during restore and is never replaced`() = runTest {
        val store = PlaybackSnapshotStore(Json)
        val prefs = mutablePreferencesOf()
        store.write(prefs, saved)
        var live: PlaybackQueueSnapshot? = null
        val userQueue = saved.copy(items = saved.items.take(3), currentIndex = 2, currentMediaId = "id-2")
        val restore = PlaybackRestoreBarrier(this) {
            delay(6_000)
            if (RestoreQueuePolicy.mayApply(live?.items?.size ?: 0)) live = saved.copy(playWhenReady = false)
        }
        val startup = async { restore.awaitCompletion() }
        runCurrent(); advanceTimeBy(1_000); runCurrent()
        live = userQueue
        assertThat(restore.isComplete).isFalse()
        assertThat(restore.mayPersistSnapshot(hasQueue = true)).isTrue()
        assertThat(restore.mayPersistSnapshot(hasQueue = true, applyingRestore = true)).isFalse()
        if (restore.mayPersistSnapshot(live?.items?.isNotEmpty() == true)) store.write(prefs, live)
        assertThat(store.read(prefs)).isEqualTo(userQueue)
        advanceTimeBy(6_000); runCurrent()
        assertThat(startup.await()).isTrue()
        assertThat(live).isEqualTo(userQueue)
        assertThat(store.read(prefs)).isEqualTo(userQueue)
    }

    @Test fun `an empty write is allowed only after the restore completes`() = runTest {
        val restore = PlaybackRestoreBarrier(this) { delay(6_000) }
        assertThat(restore.mayPersistSnapshot(hasQueue = false)).isFalse()
        val startup = async { restore.awaitCompletion() }
        runCurrent(); advanceTimeBy(4_001); runCurrent()
        assertThat(restore.mayPersistSnapshot(hasQueue = false)).isFalse()
        advanceTimeBy(2_000); runCurrent(); startup.await()
        assertThat(restore.mayPersistSnapshot(hasQueue = false)).isTrue()
    }
}
