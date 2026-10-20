package com.lostf1sh.pixelplayeross.data.identity

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lostf1sh.pixelplayeross.data.database.LibraryItemEntity
import com.lostf1sh.pixelplayeross.data.database.LibraryItemState
import com.lostf1sh.pixelplayeross.data.database.PixelPlayerDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * AUD-106: the manual Maintenance clean-up removes a dormant item only when nothing of the user's
 * hangs off it, and the count it shows is exactly what it removes. The listening history, which no SQL
 * condition can see, is checked by `DormantItemCleanup` (JVM test `DormantItemCleanupTest`).
 */
@RunWith(AndroidJUnit4::class)
class DormantPurgeRoomTest {

    private lateinit var db: PixelPlayerDatabase

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PixelPlayerDatabase::class.java)
            .addCallback(PixelPlayerDatabase.createRuntimeArtifactsCallback())
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun closeDb() = db.close()

    private fun item(id: String, state: String) = LibraryItemEntity(
        libraryItemId = id,
        state = state,
        stableKey = "fp-$id",
        fileSignature = "sig-$id",
        currentLocator = null,
        firstSeenAt = 1L,
        lastSeenAt = 2L,
    )

    private fun sql(statement: String) = db.openHelper.writableDatabase.execSQL(statement)

    @Test
    fun only_dormant_items_without_user_state_are_counted_and_removed() = runTest {
        val dormant = listOf("bare", "fav", "lyr", "edit", "play", "list", "mark")
        db.libraryItemDao().insertIgnore(
            dormant.map { item(it, LibraryItemState.DORMANT) } + item("active", LibraryItemState.ACTIVE)
        )
        sql("INSERT INTO favorites (songId, isFavorite, timestamp, library_item_id) VALUES (1, 1, 1, 'fav')")
        sql("INSERT INTO lyrics (songId, content, isSynced, library_item_id) VALUES (2, 'x', 0, 'lyr')")
        sql("INSERT INTO library_item_overrides (library_item_id, title) VALUES ('edit', 'Mine')")
        // Every NOT NULL column without a default is named: `total_play_duration_ms` and `last_played_timestamp`
        // have none, and a fixture that leaves them out fails here, before any assertion about the purge.
        sql(
            "INSERT INTO song_engagements (song_id, library_item_id, play_count, total_play_duration_ms, last_played_timestamp) " +
                "VALUES ('4', 'play', 3, 0, 0)"
        )
        sql("INSERT INTO playlist_songs (playlist_id, song_id, sort_order, library_item_id) VALUES ('p', '5', 0, 'list')")
        sql(
            "INSERT INTO audio_bookmarks (song_id, song_title, artist_name, title, timestamp_ms, created_time, library_item_id) " +
                "VALUES ('6', 't', 'a', 'b', 0, 0, 'mark')"
        )

        assertEquals(listOf("bare"), db.libraryItemDao().purgeableDormantIds())
        // The delete checks the condition again for the ids it is given: an item that carries state, or is active, is not removed
        // even when someone asks for it by id.
        assertEquals(0, db.libraryItemDao().deletePurgeableDormant(dormant - "bare" + "active"))
        assertEquals(1, db.libraryItemDao().deletePurgeableDormant(listOf("bare")))

        assertNull(db.libraryItemDao().getById("bare"))
        (dormant - "bare" + "active").forEach { assertNotNull(it, db.libraryItemDao().getById(it)) }
        assertEquals(emptyList<String>(), db.libraryItemDao().purgeableDormantIds())
    }
}
