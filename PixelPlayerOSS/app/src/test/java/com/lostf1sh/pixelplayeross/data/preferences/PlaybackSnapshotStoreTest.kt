package com.lostf1sh.pixelplayeross.data.preferences

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.PlaybackQueueItemSnapshot
import com.lostf1sh.pixelplayeross.data.model.PlaybackQueueSnapshot
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

/**
 * AUD-113 (measured on the HONOR: 16 concurrent GCs of 12-54 MB in ~13 s of skipping). The largest
 * allocator provable from the code: every track change persisted the *whole* queue snapshot — ~5k
 * items encoded into one multi-megabyte JSON string, then copied into the settings proto — although a
 * skip only moves the cursor. The queue items are now written only when they change; the cursor
 * (index, position, state) is a separate, small key.
 */
class PlaybackSnapshotStoreTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val items = List(5_000) { i ->
        PlaybackQueueItemSnapshot(
            mediaId = "id-$i",
            uri = "content://media/external/audio/media/$i",
            title = "Title $i",
            artist = "Artist",
            albumTitle = "Album",
        )
    }

    private fun snapshot(index: Int) = PlaybackQueueSnapshot(
        items = items,
        currentMediaId = "id-$index",
        currentIndex = index,
        currentPositionMs = 1_000L * index,
    )

    @Test
    fun `a skip rewrites the cursor, not the five thousand queue items`() {
        val store = PlaybackSnapshotStore(json)
        val prefs = mutablePreferencesOf()

        store.write(prefs, snapshot(0))
        repeat(5) { skip -> store.write(prefs, snapshot(skip + 1)) }

        assertThat(store.itemsEncodes).isEqualTo(1)
        val restored = store.read(prefs)!!
        assertThat(restored.currentIndex).isEqualTo(5)
        assertThat(restored.currentPositionMs).isEqualTo(5_000L)
        assertThat(restored.items).hasSize(5_000)
    }

    @Test
    fun `a changed queue is written again`() {
        val store = PlaybackSnapshotStore(json)
        val prefs = mutablePreferencesOf()
        store.write(prefs, snapshot(0))
        store.write(prefs, snapshot(0).copy(items = items.drop(1)))
        assertThat(store.itemsEncodes).isEqualTo(2)
        assertThat(store.read(prefs)!!.items).hasSize(4_999)
    }

    @Test
    fun `a snapshot saved by the previous version is still restored, and replaced on the next write`() {
        val prefs = mutablePreferencesOf()
        prefs[PlaybackSnapshotStore.LEGACY_KEY] = json.encodeToString(snapshot(7))
        val store = PlaybackSnapshotStore(json)

        assertThat(store.read(prefs)!!.currentIndex).isEqualTo(7)
        store.write(prefs, snapshot(8))
        assertThat(prefs.contains(PlaybackSnapshotStore.LEGACY_KEY)).isFalse()
        assertThat(store.read(prefs)!!.currentIndex).isEqualTo(8)
    }

    @Test
    fun `clearing removes every key, and a fresh process writes the items once`() {
        val prefs = mutablePreferencesOf()
        PlaybackSnapshotStore(json).write(prefs, snapshot(1))
        val restarted = PlaybackSnapshotStore(json)       // no memory of the last write
        restarted.write(prefs, snapshot(2))
        assertThat(restarted.itemsEncodes).isEqualTo(1)
        restarted.write(prefs, null)
        assertThat(restarted.read(prefs)).isNull()
        assertThat(prefs.asMap()).isEmpty()
    }

    // --- B17-H1: the shuffle order survives a restart ------------------------------------------------

    private fun shuffled(seed: Long): List<Int> =
        (0 until 5_000).toMutableList().also { it.shuffle(java.util.Random(seed)) }

    private fun shuffledSnapshot(index: Int, order: List<Int> = shuffled(1L)) =
        snapshot(index).copy(shuffleEnabled = true, shuffleOrder = order)

    @Test
    fun `the shuffle order comes back with the queue`() {
        val store = PlaybackSnapshotStore(json)
        val prefs = mutablePreferencesOf()
        val order = shuffled(1L)

        store.write(prefs, shuffledSnapshot(10, order))

        val restored = store.read(prefs)!!
        assertThat(restored.shuffleEnabled).isTrue()
        assertThat(restored.shuffleOrder).isEqualTo(order)
        assertThat(restored.items).hasSize(5_000)
    }

    @Test
    fun `a skip rewrites neither the items nor the order, and the cursor stays small`() {
        val store = PlaybackSnapshotStore(json)
        val prefs = mutablePreferencesOf()

        store.write(prefs, shuffledSnapshot(0))
        repeat(5) { skip -> store.write(prefs, shuffledSnapshot(skip + 1)) }

        assertThat(store.itemsEncodes).isEqualTo(1)
        assertThat(store.orderEncodes).isEqualTo(1)
        // The order is as long as the queue; it must not ride in the key that a track change rewrites (AUD-113).
        assertThat(prefs[PlaybackSnapshotStore.CURSOR_KEY]!!.length).isLessThan(600)
        assertThat(store.read(prefs)!!.currentIndex).isEqualTo(5)
    }

    @Test
    fun `a new order is written again`() {
        val store = PlaybackSnapshotStore(json)
        val prefs = mutablePreferencesOf()
        store.write(prefs, shuffledSnapshot(0, shuffled(1L)))
        store.write(prefs, shuffledSnapshot(0, shuffled(2L)))
        assertThat(store.orderEncodes).isEqualTo(2)
        assertThat(store.read(prefs)!!.shuffleOrder).isEqualTo(shuffled(2L))
    }

    @Test
    fun `turning shuffle off leaves no order behind`() {
        val store = PlaybackSnapshotStore(json)
        val prefs = mutablePreferencesOf()
        store.write(prefs, shuffledSnapshot(0))

        store.write(prefs, snapshot(0))

        assertThat(prefs.contains(PlaybackSnapshotStore.ORDER_KEY)).isFalse()
        assertThat(store.read(prefs)!!.shuffleOrder).isNull()
        assertThat(store.read(prefs)!!.shuffleEnabled).isFalse()
    }

    @Test
    fun `a snapshot saved before the order existed reads as having none, not as an error`() {
        val prefs = mutablePreferencesOf()
        // What B16 and earlier wrote: items and cursor, shuffle flag set, no order key.
        prefs[PlaybackSnapshotStore.ITEMS_KEY] = json.encodeToString(items)
        prefs[PlaybackSnapshotStore.CURSOR_KEY] =
            json.encodeToString(snapshot(3).copy(items = emptyList(), shuffleEnabled = true))

        val restored = PlaybackSnapshotStore(json).read(prefs)!!

        assertThat(restored.shuffleEnabled).isTrue()
        assertThat(restored.shuffleOrder).isNull()
        assertThat(restored.currentIndex).isEqualTo(3)
    }

    @Test
    fun `a damaged order is dropped and the queue is still restored`() {
        val prefs = mutablePreferencesOf()
        PlaybackSnapshotStore(json).write(prefs, shuffledSnapshot(4))
        prefs[PlaybackSnapshotStore.ORDER_KEY] = "{not json"

        val restored = PlaybackSnapshotStore(json).read(prefs)!!

        assertThat(restored.shuffleOrder).isNull()
        assertThat(restored.items).hasSize(5_000)
    }

    @Test
    fun `clearing removes the order key too`() {
        val prefs = mutablePreferencesOf()
        val store = PlaybackSnapshotStore(json)
        store.write(prefs, shuffledSnapshot(1))
        store.write(prefs, null)
        assertThat(prefs.asMap()).isEmpty()
    }
}
