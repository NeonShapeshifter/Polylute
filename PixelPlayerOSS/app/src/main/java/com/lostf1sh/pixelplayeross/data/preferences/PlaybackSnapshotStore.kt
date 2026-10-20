package com.lostf1sh.pixelplayeross.data.preferences

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.lostf1sh.pixelplayeross.data.model.PlaybackQueueItemSnapshot
import com.lostf1sh.pixelplayeross.data.model.PlaybackQueueSnapshot
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Stores the playback snapshot as two keys: the queue items and the cursor (AUD-113).
 *
 * Every track change persisted the whole snapshot as one string — ~5k items encoded into a
 * multi-megabyte JSON, then copied into the settings proto — although a skip only moves the cursor.
 * Measured on the HONOR during skips: 16 concurrent GCs freeing 12-54 MB each. The items are now
 * encoded only when they differ from the last write (an equality walk allocates nothing); the cursor
 * is the snapshot without its items, a few hundred bytes. A snapshot saved by the previous version
 * under the single key is still read, and replaced on the next write.
 *
 * Not thread-safe: writes are serialized by DataStore's `edit`.
 */
internal class PlaybackSnapshotStore(private val json: Json) {
    private var lastItemsWritten: List<PlaybackQueueItemSnapshot>? = null
    private var lastOrderWritten: List<Int>? = null

    /** How many times the queue items were encoded; for tests and diagnostics. */
    var itemsEncodes = 0
        private set

    /** How many times the shuffle order was encoded; for tests and diagnostics. */
    var orderEncodes = 0
        private set

    fun write(preferences: MutablePreferences, snapshot: PlaybackQueueSnapshot?) {
        preferences.remove(LEGACY_KEY)
        if (snapshot == null || snapshot.items.isEmpty()) {
            preferences.remove(ITEMS_KEY)
            preferences.remove(CURSOR_KEY)
            preferences.remove(POSITION_KEY)
            preferences.remove(ORDER_KEY)
            lastItemsWritten = null
            lastOrderWritten = null
            return
        }
        // B-ux wrote the checkpoint here; it lives in PlaybackPositionStore now (AUD-113).
        preferences.remove(POSITION_KEY)
        if (snapshot.items != lastItemsWritten || !preferences.contains(ITEMS_KEY)) {
            preferences[ITEMS_KEY] = json.encodeToString(snapshot.items)
            lastItemsWritten = snapshot.items
            itemsEncodes++
        }
        // The shuffle order is as long as the queue and, like the items, changes only when the queue or the order does
        // (B17-H1), so it gets its own key and is encoded only then; the cursor stays a few hundred bytes.
        val order = snapshot.shuffleOrder
        if (order == null) {
            preferences.remove(ORDER_KEY)
            lastOrderWritten = null
        } else if (order != lastOrderWritten || !preferences.contains(ORDER_KEY)) {
            preferences[ORDER_KEY] = json.encodeToString(order)
            lastOrderWritten = order
            orderEncodes++
        }
        preferences[CURSOR_KEY] = json.encodeToString(snapshot.copy(items = emptyList(), shuffleOrder = null))
    }

    fun read(preferences: Preferences): PlaybackQueueSnapshot? = readSnapshot(preferences)

    private fun readSnapshot(preferences: Preferences): PlaybackQueueSnapshot? {
        val itemsRaw = preferences[ITEMS_KEY]
        val cursorRaw = preferences[CURSOR_KEY]
        if (itemsRaw != null && cursorRaw != null) {
            return runCatching {
                json.decodeFromString<PlaybackQueueSnapshot>(cursorRaw)
                    .copy(
                        items = json.decodeFromString<List<PlaybackQueueItemSnapshot>>(itemsRaw),
                        // A queue saved before the order had its own key, or one with shuffle off, has none.
                        shuffleOrder = preferences[ORDER_KEY]?.let {
                            runCatching { json.decodeFromString<List<Int>>(it) }.getOrNull()
                        },
                    )
            }.getOrNull()
        }
        return preferences[LEGACY_KEY]?.let {
            runCatching { json.decodeFromString<PlaybackQueueSnapshot>(it) }.getOrNull()
        }
    }

    companion object {
        /**
         * A checkpoint taken while playing, after the last state change, for the same item, is the
         * later truth about where playback was (AUD-121).
         */
        fun merge(snapshot: PlaybackQueueSnapshot?, mark: PlaybackPositionMark?): PlaybackQueueSnapshot? {
            if (snapshot == null || mark == null) return snapshot
            return if (mark.mediaId == snapshot.currentMediaId && mark.savedAtEpochMs >= snapshot.savedAtEpochMs) {
                snapshot.copy(currentPositionMs = mark.positionMs)
            } else {
                snapshot
            }
        }

        val LEGACY_KEY = stringPreferencesKey("playback_queue_snapshot_v1")
        val ITEMS_KEY = stringPreferencesKey("playback_queue_items_v2")
        val CURSOR_KEY = stringPreferencesKey("playback_queue_cursor_v2")
        val POSITION_KEY = stringPreferencesKey("playback_position_v1")
        val ORDER_KEY = stringPreferencesKey("playback_queue_order_v1")
    }
}

/** The playing item and position at [savedAtEpochMs] (AUD-121). */
@kotlinx.serialization.Serializable
data class PlaybackPositionMark(
    val mediaId: String,
    val positionMs: Long,
    val savedAtEpochMs: Long,
)
