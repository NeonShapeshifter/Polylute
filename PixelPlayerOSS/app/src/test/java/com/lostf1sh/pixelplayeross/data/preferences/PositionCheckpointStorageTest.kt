package com.lostf1sh.pixelplayeross.data.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.PlaybackQueueItemSnapshot
import com.lostf1sh.pixelplayeross.data.model.PlaybackQueueSnapshot
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

/**
 * AUD-113: the AUD-121 position checkpoint was written every 5 s, while playing, into the Preferences
 * DataStore that also holds the queue snapshot (megabytes with a 5k queue) and every setting.
 * DataStore rewrites the whole file on every edit and re-emits to every `data.map` reader — a
 * continuous allocator. The checkpoint now lives in its own small store; the restore still uses it.
 */
class PositionCheckpointStorageTest {

    @Test
    fun `checkpoints never touch the settings DataStore, and the restore still takes the position`() = runTest {
        val tempDir = Files.createTempDirectory("position-checkpoint-test")
        try {
            val dataStore = PreferenceDataStoreFactory.create(
                scope = backgroundScope,
                produceFile = { tempDir.resolve("settings.preferences_pb").toFile() },
            )
            val positions = InMemoryPlaybackPositionStore()
            val repository = UserPreferencesRepository(dataStore = dataStore, json = Json, positionStore = positions)
            val items = listOf(PlaybackQueueItemSnapshot("a", "content://a"), PlaybackQueueItemSnapshot("b", "content://b"))
            repository.setPlaybackQueueSnapshot(
                PlaybackQueueSnapshot(items = items, currentMediaId = "b", currentIndex = 1, currentPositionMs = 0L, savedAtEpochMs = 1L)
            )
            val emissions = mutableListOf<Any>()
            backgroundScope.launch { dataStore.data.collect { emissions += it } }
            testScheduler.advanceUntilIdle()
            val before = emissions.size

            repeat(6) { repository.setPlaybackPosition("b", 5_000L * (it + 1)) }
            testScheduler.advanceUntilIdle()

            assertThat(emissions.size).isEqualTo(before)
            assertThat(repository.getPlaybackQueueSnapshotOnce()!!.currentPositionMs).isEqualTo(30_000L)

            repository.setPlaybackQueueSnapshot(null)
            assertThat(positions.read()).isNull()
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `the merge takes a newer checkpoint for the same item only`() {
        val snapshot = PlaybackQueueSnapshot(items = emptyList(), currentMediaId = "b", currentPositionMs = 30_000L, savedAtEpochMs = 5_000L)
        assertThat(PlaybackSnapshotStore.merge(snapshot, PlaybackPositionMark("b", 41_000L, 6_000L))!!.currentPositionMs).isEqualTo(41_000L)
        assertThat(PlaybackSnapshotStore.merge(snapshot, PlaybackPositionMark("a", 90_000L, 9_000L))!!.currentPositionMs).isEqualTo(30_000L)
        assertThat(PlaybackSnapshotStore.merge(snapshot, PlaybackPositionMark("b", 10_000L, 4_000L))!!.currentPositionMs).isEqualTo(30_000L)
        assertThat(PlaybackSnapshotStore.merge(null, PlaybackPositionMark("b", 1L, 1L))).isNull()
    }
}
