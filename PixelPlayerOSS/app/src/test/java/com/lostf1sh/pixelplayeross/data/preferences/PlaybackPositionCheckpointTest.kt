package com.lostf1sh.pixelplayeross.data.preferences

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.PlaybackQueueItemSnapshot
import com.lostf1sh.pixelplayeross.data.model.PlaybackQueueSnapshot
import com.lostf1sh.pixelplayeross.data.service.PlaybackPositionCheckpointer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

/**
 * AUD-121 (measured: kill -9 while playing at 39.9 s restored at 2.4 s). The position was persisted
 * only with a state change, so a process death lost everything played since. It is now checkpointed
 * on its own every 5 s while playing, and the restore takes it when it is newer than the snapshot.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackPositionCheckpointTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun item(id: String) = PlaybackQueueItemSnapshot(mediaId = id, uri = "content://$id")

    @Test
    fun `12 s of playback with no events, then a kill, restores at least 10 s`() = runTest {
        val store = PlaybackSnapshotStore(json)
        val prefs = mutablePreferencesOf()
        // The last state change: play pressed at 0 s on track "b".
        store.write(prefs, PlaybackQueueSnapshot(items = listOf(item("a"), item("b")), currentMediaId = "b",
            currentIndex = 1, currentPositionMs = 0L, playWhenReady = true, savedAtEpochMs = 1_000L))

        val positions = InMemoryPlaybackPositionStore()
        val checkpointer = PlaybackPositionCheckpointer { mediaId, positionMs ->
            positions.write(PlaybackPositionMark(mediaId, positionMs, 1_000L + currentTime))
        }
        val job = launch { checkpointer.run { "b" to currentTime } }
        advanceTimeBy(12_000L)
        job.cancel()  // kill -9: nothing more is written

        val restored = PlaybackSnapshotStore.merge(store.read(prefs), positions.read())!!
        assertThat(restored.currentMediaId).isEqualTo("b")
        assertThat(restored.currentPositionMs).isAtLeast(10_000L)
        assertThat(restored.items).hasSize(2)
    }
}
