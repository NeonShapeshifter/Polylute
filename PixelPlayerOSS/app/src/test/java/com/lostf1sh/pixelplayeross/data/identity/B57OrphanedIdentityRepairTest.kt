package com.lostf1sh.pixelplayeross.data.identity

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lostf1sh.pixelplayeross.data.database.AlbumEntity
import com.lostf1sh.pixelplayeross.data.database.ArtistEntity
import com.lostf1sh.pixelplayeross.data.database.LibraryItemEntity
import com.lostf1sh.pixelplayeross.data.database.PixelPlayerDatabase
import com.lostf1sh.pixelplayeross.data.database.SongEntity
import com.lostf1sh.pixelplayeross.data.stats.PlaybackStatsRepository
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B57 1.2: the one-time repair of items an old edit orphaned. Synthetic rows only: a dormant item that carries a library's worth of user state, a live item the rescan minted at
 * the same locator, and the evidence between them. A clear pair merges; anything doubtful merges nothing and is counted; a second run changes nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class B57OrphanedIdentityRepairTest {
    private lateinit var context: Context
    private lateinit var db: PixelPlayerDatabase
    private lateinit var history: PlaybackStatsRepository
    private lateinit var repair: OrphanedIdentityRepair
    private val dao get() = db.libraryItemDao()
    private val duration = 245_000L
    private val sizeBefore = 31_457_280L
    private val sizeAfter = sizeBefore + 700_000L // a cover was added

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, OrphanedIdentityRepair.MARKER_NAME).delete()
        File(context.filesDir, "playback_history.json").delete()
        db = Room.inMemoryDatabaseBuilder(context, PixelPlayerDatabase::class.java)
            .addCallback(PixelPlayerDatabase.createRuntimeArtifactsCallback()).allowMainThreadQueries().build()
        runBlocking {
            db.musicDao().insertArtistsIgnoreConflicts(listOf(ArtistEntity(id = 1, name = "Gemini Sound", trackCount = 0, imageUrl = null, customImageUri = null)))
            db.musicDao().insertAlbumsIgnoreConflicts(listOf(AlbumEntity(id = 1, title = "Noche", artistName = "Gemini Sound", artistId = 1, albumArtUriString = null, songCount = 0, dateAdded = 0, year = 0)))
        }
        history = PlaybackStatsRepository(context)
        repair = OrphanedIdentityRepair(context, dao, history).also {
            it.sizeWindow = -(1L shl 20)..(64L shl 10) // the old file was within a MiB of the new one; the default window is tested once, below
            it.isPlaying = { false }
        }
    }

    @After fun tearDown() { db.close() }

    private fun sql(statement: String, vararg args: Any) = db.openHelper.writableDatabase.execSQL(statement, arrayOf(*args))
    private fun <T> query(statement: String, vararg args: String, read: (android.database.Cursor) -> T): T =
        db.openHelper.readableDatabase.query(statement, args).use { it.moveToFirst(); read(it) }

    private fun item(id: String, state: String, signature: String?, locator: Long?, firstSeen: Long, lastSeen: Long) = runBlocking {
        dao.insertIgnore(listOf(LibraryItemEntity(libraryItemId = id, state = state, stableKey = "key-$id", fileSignature = signature ?: "", currentLocator = locator, firstSeenAt = firstSeen, lastSeenAt = lastSeen)))
    }

    private fun projection(locator: Long, itemId: String, size: Long = sizeAfter, durationMs: Long = duration) = runBlocking {
        db.musicDao().insertSongs(
            listOf(
                SongEntity(
                    id = locator, title = "Cine", artistName = "Gemini Sound", artistId = 1, albumName = "Noche", albumId = 1,
                    contentUriString = "content://media/external/audio/media/$locator", albumArtUriString = null, duration = durationMs, genre = null,
                    filePath = "/m/$locator.flac", parentDirectoryPath = "/m", libraryItemId = itemId, fileSize = size,
                )
            )
        )
    }

    /** The state a listener builds on a track, keyed by [itemId] and the locator it had. */
    private fun giveState(itemId: String, locator: Long) {
        sql("INSERT INTO favorites (songId, isFavorite, timestamp, library_item_id) VALUES (?, 1, 1, ?)", locator, itemId)
        sql("INSERT INTO lyrics (songId, content, isSynced, source, library_item_id) VALUES (?, 'words', 0, 'manual', ?)", locator, itemId)
        sql("INSERT INTO song_engagements (song_id, stable_key, library_item_id, play_count, total_play_duration_ms, completion_count, skip_count, first_played_at, last_completed_at, last_skipped_at, last_played_timestamp) VALUES (?, '', ?, 7, 1, 7, 0, 1, 1, 0, 1)", locator.toString(), itemId)
        sql("INSERT OR IGNORE INTO playlists (id, name, created_at, last_modified, is_queue_generated, source) VALUES ('p1', 'Mine', 1, 1, 0, 'USER')")
        sql("INSERT INTO playlist_songs (playlist_id, song_id, sort_order, library_item_id) VALUES ('p1', ?, 0, ?)", locator.toString(), itemId)
        sql("INSERT INTO audio_bookmarks (id, song_id, song_title, artist_name, title, timestamp_ms, created_time, library_item_id) VALUES (?, ?, 't', 'a', 'mark', 500, 1, ?)", locator, locator.toString(), itemId)
        sql("INSERT INTO library_item_overrides (library_item_id, title, title_user_edited, artist_user_edited, album_user_edited, genre_user_edited) VALUES (?, 'User title', 1, 0, 0, 0)", itemId)
    }

    private fun stateOf(itemId: String) = listOf("favorites", "lyrics", "song_engagements", "playlist_songs", "audio_bookmarks", "library_item_overrides")
        .associateWith { table -> query("SELECT COUNT(*) FROM $table WHERE library_item_id = ?", itemId) { it.getInt(0) } }

    private val fullState = mapOf("favorites" to 1, "lyrics" to 1, "song_engagements" to 1, "playlist_songs" to 1, "audio_bookmarks" to 1, "library_item_overrides" to 1)
    private val noState = fullState.mapValues { 0 }

    /** The pair a past edit left: D last seen at t=100, dormant; N minted at t=200 on the same locator by the rescan after the write. */
    private fun clearPair(d: String = "D", n: String = "N", locator: Long = 7L) {
        item(d, "DORMANT", LibraryIdentity.computeFileSignature(duration, sizeBefore), locator = null, firstSeen = 10, lastSeen = 100)
        giveState(d, locator)
        item(n, "ACTIVE", LibraryIdentity.computeFileSignature(duration, sizeAfter), locator = locator, firstSeen = 200, lastSeen = 200)
        projection(locator, n)
    }

    @Test fun a_clear_pair_gets_its_favourite_plays_playlists_lyrics_and_bookmarks_back() = runBlocking {
        clearPair()
        history.recordPlayback(songId = "7", durationMs = 1000, timestamp = 10_000, libraryItemId = "D")
        history.recordPlayback(songId = "7", durationMs = 1000, timestamp = 20_000) // an old event that names nothing

        val outcome = repair.runOnce()!!

        assertEquals(1, outcome.merged)
        assertEquals(0, outcome.unmerged)
        assertEquals(1, outcome.eventsMoved)
        assertEquals(fullState, stateOf("N"))
        assertEquals(noState, stateOf("D"))
        assertEquals("the emptied dormant item is gone", 0, query("SELECT COUNT(*) FROM library_items WHERE library_item_id = 'D'") { it.getInt(0) })
        assertEquals("the live item shows the favourite", 1, query("SELECT is_favorite FROM songs WHERE id = 7") { it.getInt(0) })
        assertEquals(1, query("SELECT COUNT(*) FROM library_items WHERE library_item_id = 'N' AND state = 'ACTIVE'") { it.getInt(0) })
        val named = history.exportEventsForBackup().map { it.libraryItemId }
        assertEquals(listOf("N", null), named)
    }

    @Test fun running_it_again_changes_nothing() = runBlocking {
        clearPair()
        assertEquals(1, repair.runOnce()!!.merged)
        val snapshot = listOf("library_items", "favorites", "lyrics", "playlist_songs", "audio_bookmarks", "song_engagements", "library_item_overrides", "songs")
            .map { table -> query("SELECT COUNT(*), COALESCE(SUM(LENGTH(library_item_id)), 0) FROM $table") { it.getInt(0) to it.getInt(1) } }

        assertNull("the marker says it has run", repair.runOnce())
        File(context.filesDir, OrphanedIdentityRepair.MARKER_NAME).delete()
        val second = repair.runOnce()!!
        assertEquals(0, second.merged)
        assertEquals(0, second.unmerged)
        val after = listOf("library_items", "favorites", "lyrics", "playlist_songs", "audio_bookmarks", "song_engagements", "library_item_overrides", "songs")
            .map { table -> query("SELECT COUNT(*), COALESCE(SUM(LENGTH(library_item_id)), 0) FROM $table") { it.getInt(0) to it.getInt(1) } }
        assertEquals(snapshot, after)
    }

    @Test fun two_dormant_items_that_could_each_be_the_live_one_merge_nothing() = runBlocking {
        clearPair("D1", "N")
        item("D2", "DORMANT", LibraryIdentity.computeFileSignature(duration, sizeBefore + 1_000), locator = null, firstSeen = 10, lastSeen = 100)
        // the favourite and the lyrics are keyed by the locator, so only one item can hold them; D2 holds a bookmark and an edit at the same locator
        sql("INSERT INTO audio_bookmarks (id, song_id, song_title, artist_name, title, timestamp_ms, created_time, library_item_id) VALUES (99, '7', 't', 'a', 'mark', 500, 1, 'D2')")
        sql("INSERT INTO library_item_overrides (library_item_id, title, title_user_edited, artist_user_edited, album_user_edited, genre_user_edited) VALUES ('D2', 'Other', 1, 0, 0, 0)")
        val outcome = repair.runOnce()!!

        assertEquals(0, outcome.merged)
        assertEquals(2, outcome.ambiguous)
        assertEquals(noState, stateOf("N"))
        assertEquals(fullState, stateOf("D1"))
        assertEquals(mapOf("favorites" to 0, "lyrics" to 0, "song_engagements" to 0, "playlist_songs" to 0, "audio_bookmarks" to 1, "library_item_overrides" to 1), stateOf("D2"))
        assertEquals(2, query("SELECT COUNT(*) FROM library_items WHERE state = 'DORMANT'") { it.getInt(0) })
    }

    @Test fun the_doubtful_cases_merge_nothing_and_are_counted() = runBlocking {
        // 1: the live item was first seen BEFORE the dormant one was last seen: not created by a rescan after it
        item("D-early", "DORMANT", LibraryIdentity.computeFileSignature(duration, sizeBefore), null, 10, 500)
        giveState("D-early", 11L)
        item("N-early", "ACTIVE", "s", 11, 200, 200); projection(11L, "N-early")
        // 2: a different duration: the signature cannot be reproduced from the live item's duration
        item("D-dur", "DORMANT", LibraryIdentity.computeFileSignature(duration + 4_000, sizeBefore), null, 10, 100)
        giveState("D-dur", 12L)
        item("N-dur", "ACTIVE", "s", 12, 200, 200); projection(12L, "N-dur")
        // 3: the live item already has user state of its own
        item("D-own", "DORMANT", LibraryIdentity.computeFileSignature(duration, sizeBefore), null, 10, 100)
        giveState("D-own", 13L)
        item("N-own", "ACTIVE", "s", 13, 200, 200); projection(13L, "N-own")
        sql("INSERT INTO library_item_overrides (library_item_id, title, title_user_edited, artist_user_edited, album_user_edited, genre_user_edited) VALUES ('N-own', 'Mine too', 1, 0, 0, 0)")
        // 4: the dormant item's rows name two locators
        item("D-two", "DORMANT", LibraryIdentity.computeFileSignature(duration, sizeBefore), null, 10, 100)
        sql("INSERT INTO favorites (songId, isFavorite, timestamp, library_item_id) VALUES (14, 1, 1, 'D-two')")
        sql("INSERT INTO lyrics (songId, content, isSynced, source, library_item_id) VALUES (15, 'x', 0, 'manual', 'D-two')")
        // 5: no live item at the locator
        item("D-gone", "DORMANT", LibraryIdentity.computeFileSignature(duration, sizeBefore), null, 10, 100)
        giveState("D-gone", 16L)
        // 6: no signature to prove the duration with
        item("D-nosig", "DORMANT", null, null, 10, 100)
        giveState("D-nosig", 17L)
        item("N-nosig", "ACTIVE", "s", 17, 200, 200); projection(17L, "N-nosig")
        // 7: a dormant item with no user state is nothing to give back (not counted at all)
        item("D-empty", "DORMANT", LibraryIdentity.computeFileSignature(duration, sizeBefore), null, 10, 100)

        val outcome = repair.runOnce()!!

        assertEquals(0, outcome.merged)
        assertEquals(1, outcome.notCreatedAfter)
        assertEquals(2, outcome.durationUnproven) // D-dur, D-nosig
        assertEquals(1, outcome.liveHasState)
        assertEquals(1, outcome.locatorsDisagree)
        assertEquals(1, outcome.noLiveItem)
        assertEquals(6, outcome.unmerged)
        for (id in listOf("D-early", "D-dur", "D-own", "D-gone", "D-nosig")) assertEquals("$id keeps its state", fullState, stateOf(id))
        assertEquals(noState, stateOf("N-early"))
        assertEquals(noState, stateOf("N-dur"))
        assertEquals(7, query("SELECT COUNT(*) FROM library_items WHERE state = 'DORMANT'") { it.getInt(0) })
    }

    @Test fun a_live_item_the_history_names_is_not_merged_into() = runBlocking {
        clearPair()
        history.recordPlayback(songId = "7", durationMs = 1000, timestamp = 10_000, libraryItemId = "N")
        val outcome = repair.runOnce()!!
        assertEquals(0, outcome.merged)
        assertEquals(1, outcome.liveHasState)
        assertEquals(fullState, stateOf("D"))
    }

    @Test fun nothing_runs_while_music_plays_and_it_runs_when_it_stops() = runBlocking {
        clearPair()
        repair.isPlaying = { true }
        val waiting = repair.runOnce()!!
        assertTrue(waiting.skippedBecausePlaying)
        assertFalse("no marker: it is not done", File(context.filesDir, OrphanedIdentityRepair.MARKER_NAME).exists())
        assertEquals(fullState, stateOf("D"))
        repair.isPlaying = { false }
        assertEquals(1, repair.runOnce()!!.merged)
        assertEquals(fullState, stateOf("N"))
    }

    @Test fun the_default_window_finds_a_cover_of_several_megabytes_and_not_one_of_forty() = runBlocking {
        repair.sizeWindow = OrphanedIdentityRepair.SIZE_WINDOW
        item("D", "DORMANT", LibraryIdentity.computeFileSignature(duration, sizeBefore), null, 10, 100)
        giveState("D", 7L)
        item("N", "ACTIVE", "s", 7, 200, 200)
        projection(7L, "N", size = sizeBefore + (4L shl 20)) // a 4 MiB cover
        val started = System.nanoTime()
        assertEquals(1, repair.runOnce()!!.merged)
        println("B57 repair default window, one pair: ${(System.nanoTime() - started) / 1_000_000} ms")

        File(context.filesDir, OrphanedIdentityRepair.MARKER_NAME).delete()
        item("D2", "DORMANT", LibraryIdentity.computeFileSignature(duration, sizeBefore), null, 10, 100)
        giveState("D2", 8L)
        item("N2", "ACTIVE", "s", 8, 200, 200)
        projection(8L, "N2", size = sizeBefore + (40L shl 20)) // beyond the window: not proved, so not merged
        val beyond = repair.runOnce()!!
        assertEquals(0, beyond.merged)
        assertEquals(1, beyond.durationUnproven)
    }

    @Test fun the_signature_search_agrees_with_the_real_function() = runBlocking {
        val random = java.util.Random(57)
        repeat(40) {
            val dur = random.nextInt(900_000).toLong()
            val size = 1L + random.nextInt(2_000_000_000)
            val signature = LibraryIdentity.computeFileSignature(dur, size)
            assertEquals(size, FileSignatureSearch.findSize(dur, signature, (size - 300)..(size + 300)))
            assertNull(FileSignatureSearch.findSize(dur + 1, signature, (size - 300)..(size + 300)))
        }
        assertEquals(1_234_567L, FileSignatureSearch.findSize(1_000L, LibraryIdentity.computeFileSignature(1_000L, 1_234_567L), 1L..2_000_000L))
        assertNotNull(OrphanedIdentityRepair.MARKER_NAME)
    }
}
