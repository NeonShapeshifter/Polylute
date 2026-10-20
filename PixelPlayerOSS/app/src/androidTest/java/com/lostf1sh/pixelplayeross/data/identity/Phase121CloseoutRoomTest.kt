package com.lostf1sh.pixelplayeross.data.identity

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lostf1sh.pixelplayeross.data.database.AlbumEntity
import com.lostf1sh.pixelplayeross.data.database.ArtistEntity
import com.lostf1sh.pixelplayeross.data.database.AudioBookmarkEntity
import com.lostf1sh.pixelplayeross.data.database.LibraryItemEntity
import com.lostf1sh.pixelplayeross.data.database.LibraryItemState
import com.lostf1sh.pixelplayeross.data.database.LyricsEntity
import com.lostf1sh.pixelplayeross.data.database.PixelPlayerDatabase
import com.lostf1sh.pixelplayeross.data.database.PlaylistEntity
import com.lostf1sh.pixelplayeross.data.database.SongArtistCrossRef
import com.lostf1sh.pixelplayeross.data.database.SongEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * The end-to-end identity transitions, against a real SQLite database.
 *
 * Every claim this phase makes about renumbering, locator reuse, temporary loss, return, ambiguity,
 * user-state ownership and phantom tracks is executed here against actual Room. A green suite of
 * pure-function tests proves the decision procedure; it does not prove that the database keeps the
 * user's data, and that difference is exactly what this file exists to close.
 */
@RunWith(AndroidJUnit4::class)
class Phase121CloseoutRoomTest {

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
    @Throws(IOException::class)
    fun closeDb() {
        db.close()
    }

    // --- fixtures ----------------------------------------------------------------------------------

    private fun song(
        locator: Long,
        identity: String,
        fingerprint: String = "fp-$identity",
        title: String = "Title $identity",
    ) = SongEntity(
        id = locator,
        libraryItemId = identity,
        stableKey = fingerprint,
        fileSize = 8_000_000L,
        title = title,
        artistName = "Artist",
        artistId = 1L,
        albumArtist = "Artist",
        albumName = "Album",
        albumId = 1L,
        contentUriString = "content://media/audio/$locator",
        albumArtUriString = null,
        duration = 240_000L,
        genre = "Genre",
        filePath = "/music/$locator.flac",
        parentDirectoryPath = "/music",
    )

    private fun item(identity: String, locator: Long?, fingerprint: String = "fp-$identity") =
        LibraryItemEntity(
            libraryItemId = identity,
            state = LibraryItemState.ACTIVE,
            stableKey = fingerprint,
            fileSignature = "sig-$identity",
            currentLocator = locator,
            firstSeenAt = 1_000L,
            lastSeenAt = 2_000L,
        )

    private suspend fun seedParents() {
        db.musicDao().insertAlbumsIgnoreConflicts(
            listOf(
                AlbumEntity(
                    id = 1L, title = "Album", artistName = "Artist", artistId = 1L,
                    albumArtUriString = null, songCount = 0, dateAdded = 1L, year = 2024,
                )
            )
        )
        db.musicDao().insertArtistsIgnoreConflicts(listOf(ArtistEntity(id = 1L, name = "Artist", trackCount = 0)))
    }

    /** Brings one item up as a live library entry with all of the user's state attached to it. */
    private suspend fun seedLiveLibrary(locator: Long = 11L, identity: String = "L1") {
        seedParents()
        db.musicDao().insertSongs(listOf(song(locator, identity)))
        db.libraryItemDao().insertIgnore(listOf(item(identity, locator)))

        db.favoritesDao().setFavoriteForLibraryItem(identity, true, 500L, locator)
        db.lyricsDao().insert(LyricsEntity(songId = locator, libraryItemId = identity, content = "line one", isSynced = true))
        db.audioBookmarkDao().insertBookmarkWithIdentity(
            AudioBookmarkEntity(
                songId = locator.toString(), songTitle = "T", artistName = "A",
                albumArtUri = null, title = "Chapter", timestampMs = 1000L, libraryItemId = identity,
            )
        )
        db.localPlaylistDao().upsertPlaylist(
            PlaylistEntity(id = "p1", name = "P", createdAt = 1L, lastModified = 1L, source = "LOCAL")
        )
        db.localPlaylistDao().replacePlaylistSongs("p1", listOf(locator.toString()))
        db.libraryItemOverrideDao().setTitle(identity, "The User's Title")
        db.engagementDao().seedLegacy(locator.toString(), identity, 5, 4, 1, 240_000L, 900L)

        // The projection's display values are derived from the durable overrides, and the scan is
        // what recomputes them. Doing it here keeps the fixture in the same order production uses,
        // rather than asserting against a sequence that never happens.
        db.musicDao().reapplyLibraryItemOverridesToProjections()
    }

    // --- 1. renumber -------------------------------------------------------------------------------

    @Test
    fun a_renumber_moves_the_projection_keeps_the_identity_and_removes_the_stale_row() = runTest {
        seedLiveLibrary(locator = 11L, identity = "L1")

        db.musicDao().reattachProjection(
            libraryItemId = "L1",
            oldLocator = 11L,
            incoming = song(locator = 22L, identity = "L1"),
            stableKey = "fp-L1",
            fileSignature = "sig-L1",
        )

        assertEquals(LibraryItemState.ACTIVE, db.libraryItemDao().getById("L1")?.state)
        assertEquals(22L, db.libraryItemDao().getById("L1")?.currentLocator)
        assertEquals("L1", db.musicDao().getSongByLocatorOnce(22L)?.libraryItemId)
        // The old row is gone: a retained one is a phantom track, playable and searchable.
        assertNull(db.musicDao().getSongByLocatorOnce(11L))
        assertEquals(1, db.musicDao().countProjectionsForLibraryItem("L1"))
        assertNull(db.libraryItemDao().getById("L2"))
        // And no phantom in the *listing* path either.
        assertTrue(db.musicDao().getActiveSongByLocator(11L) == null)
        assertNotNull(db.musicDao().getActiveSongByLocator(22L))
    }

    @Test
    fun every_piece_of_user_state_survives_a_renumber_without_being_moved_by_hand() = runTest {
        seedLiveLibrary(locator = 11L, identity = "L1")

        db.musicDao().reattachProjection(
            libraryItemId = "L1",
            oldLocator = 11L,
            incoming = song(locator = 22L, identity = "L1"),
            stableKey = "fp-L1",
            fileSignature = "sig-L1",
        )

        // None of this was copied. It belongs to L1, and L1 did not change — the *projection* did.
        assertEquals(true, db.favoritesDao().isFavoriteForLibraryItem("L1"))
        assertEquals("The User's Title", db.libraryItemOverrideDao().getForLibraryItem("L1")?.title)
        assertEquals(5, db.engagementDao().getEngagementByLibraryItem("L1")?.playCount)
        assertEquals(4, db.engagementDao().getEngagementByLibraryItem("L1")?.completionCount)

        // Lyrics, membership and bookmarks are resolved through the *current* locator to the
        // identity that owns them, so a caller holding B finds what was written under A.
        assertEquals("line one", db.lyricsDao().getLyricsResolvedByIdentity(22L)?.content)
        assertEquals(listOf(22L), db.localPlaylistDao().getPlaylistLocatorsResolved("p1"))
        assertEquals(1, db.audioBookmarkDao().getBookmarksResolvedByIdentity("22").size)

        // The projection the UI reads derives the cached favourite flag from identity.
        assertEquals(true, db.musicDao().getSongByLocatorOnce(22L)?.isFavorite)
    }

    // --- 2. locator reuse --------------------------------------------------------------------------

    @Test
    fun a_reused_locator_hands_the_new_item_nothing_of_the_old_one() = runTest {
        seedLiveLibrary(locator = 11L, identity = "L1")

        // L1's file is gone; Android hands 11 to something else.
        db.musicDao().markLibraryItemsDormantAndDropProjections(listOf("L1"))
        // Dormancy prunes the album that no longer has any song. The next scan re-creates parents
        // before children, which is the order this models.
        seedParents()

        db.libraryItemDao().insertIgnore(listOf(item("L2", locator = 11L, fingerprint = "fp-L2")))
        db.musicDao().insertSongs(listOf(song(locator = 11L, identity = "L2", fingerprint = "fp-L2")))

        assertEquals(LibraryItemState.DORMANT, db.libraryItemDao().getById("L1")?.state)
        assertNull(db.libraryItemDao().getById("L1")?.currentLocator)
        assertEquals("L2", db.musicDao().getSongByLocatorOnce(11L)?.libraryItemId)

        // Nothing was inherited: L2 has no favourites, no lyrics, no history.
        assertNull(db.favoritesDao().isFavoriteForLibraryItem("L2"))
        assertNull(db.engagementDao().getEngagementByLibraryItem("L2"))
        assertNull(db.lyricsDao().getLyricsResolvedByIdentity(11L))

        // And L1 still owns everything it owned before.
        assertEquals(true, db.favoritesDao().isFavoriteForLibraryItem("L1"))
        assertEquals(5, db.engagementDao().getEngagementByLibraryItem("L1")?.playCount)
        assertEquals("The User's Title", db.libraryItemOverrideDao().getForLibraryItem("L1")?.title)
    }

    // --- 3. temporary complete loss ----------------------------------------------------------------

    @Test
    fun fifty_items_can_go_dormant_at_once_and_keep_all_of_their_state() = runTest {
        seedParents()
        val identities = (1L..50L).map { "L$it" }
        db.musicDao().insertSongs(identities.mapIndexed { i, id -> song(locator = i + 1L, identity = id) })
        db.libraryItemDao().insertIgnore(identities.mapIndexed { i, id -> item(id, locator = i + 1L) })
        identities.forEachIndexed { i, id ->
            val locator = i + 1L
            db.favoritesDao().setFavoriteForLibraryItem(id, true, 1L, locator)
            db.engagementDao().seedLegacy(locator.toString(), id, 1, 1, 0, 1000L, 1L)
            db.libraryItemOverrideDao().setTitle(id, "Title $id")
        }

        db.musicDao().markLibraryItemsDormantAndDropProjections(identities)

        assertEquals(50, db.libraryItemDao().countByState(LibraryItemState.DORMANT))
        // No projection survives, so nothing is listed, searchable or playable.
        assertEquals(0, db.musicDao().countActiveProjections())
        assertEquals(0, db.musicDao().getSongCount().first())
        // Nothing is deleted: every favourite, every edit, every play count is still there.
        assertEquals(50, db.favoritesDao().getFavoriteLibraryItemIds().size)
        identities.forEach { assertEquals("Title $it", db.libraryItemOverrideDao().getForLibraryItem(it)?.title) }
        assertEquals(50, identities.count { db.engagementDao().getEngagementByLibraryItem(it)?.playCount == 1 })
        // Matching evidence is retained, which is what makes a return recognisable.
        assertEquals("fp-L7", db.libraryItemDao().getById("L7")?.stableKey)
    }

    // --- 4. return ---------------------------------------------------------------------------------

    @Test
    fun a_returning_library_recovers_the_same_identities_with_no_duplicates() = runTest {
        seedLiveLibrary(locator = 11L, identity = "L1")
        db.musicDao().markLibraryItemsDormantAndDropProjections(listOf("L1"))

        // Storage comes back. The scan sees the same evidence under a new locator, and the plan
        // recognises it — the reattachment the previous passes decided on but never executed.
        val known = db.musicDao().getAllLibraryItems().map {
            KnownItem(
                libraryItemId = LibraryItemId(it.libraryItemId),
                mediaStoreId = it.currentLocator ?: -1L,
                fingerprint = it.stableKey,
                fileSignature = it.fileSignature,
            )
        }
        val scanned = listOf(ScannedItem(mediaStoreId = 44L, fingerprint = "fp-L1", fileSignature = "sig-L1"))
        val plan = SyncIdentityPlan.build(known, scanned) { "should-not-be-used" }

        assertEquals(1, plan.reattachmentCount)
        assertEquals(0, plan.newIdentityCount)
        assertEquals("L1", plan.reattached.single().libraryItemId.value)

        seedParents()
        db.musicDao().insertSongs(listOf(song(locator = 44L, identity = "pending")))
        db.musicDao().reattachProjection(
            libraryItemId = "L1",
            oldLocator = db.musicDao().currentLocatorFor("L1"),
            incoming = db.musicDao().getSongByLocatorOnce(44L)!!,
            stableKey = "fp-L1",
            fileSignature = "sig-L1",
        )

        // One identity, one projection, and none of the user's state was rebuilt or lost.
        assertEquals(1L, db.libraryItemDao().getAll().size.toLong())
        assertEquals(LibraryItemState.ACTIVE, db.libraryItemDao().getById("L1")?.state)
        assertEquals(44L, db.libraryItemDao().getById("L1")?.currentLocator)
        assertEquals(1, db.musicDao().countProjectionsForLibraryItem("L1"))
        assertEquals(true, db.favoritesDao().isFavoriteForLibraryItem("L1"))
        assertEquals("The User's Title", db.libraryItemOverrideDao().getForLibraryItem("L1")?.title)
        assertEquals(5, db.engagementDao().getEngagementByLibraryItem("L1")?.playCount)
    }

    @Test
    fun a_dormant_item_with_the_same_evidence_is_never_minted_as_a_second_identity() = runTest {
        seedLiveLibrary(locator = 11L, identity = "L1")
        db.musicDao().markLibraryItemsDormantAndDropProjections(listOf("L1"))

        val known = db.musicDao().getAllLibraryItems().map {
            KnownItem(
                libraryItemId = LibraryItemId(it.libraryItemId),
                mediaStoreId = it.currentLocator ?: -1L,
                fingerprint = it.stableKey,
                fileSignature = it.fileSignature,
            )
        }
        // Two rows that look identical: exactly the ambiguity case. The resolver must refuse.
        val scanned = listOf(
            ScannedItem(1L, "fp-L1", "sig-L1"),
            ScannedItem(2L, "fp-L1", "sig-L1"),
        )
        val first = LibraryIdentityResolver.resolve(known, scanned, { "minted" })
            .filterIsInstance<IdentityResolution.Reattached>()
        assertEquals(1, first.size)
        assertEquals("L1", first.single().libraryItemId.value)

        val second = LibraryIdentityResolver.resolve(known, listOf(scanned[1]), { "minted" })
        assertEquals(1, second.size)
        assertEquals("L1", second.single().libraryItemId.value)
    }

    // --- 5. phantom tracks -------------------------------------------------------------------------

    @Test
    fun a_dormant_item_is_absent_from_the_library_list_from_search_and_from_playback() = runTest {
        seedLiveLibrary(locator = 11L, identity = "L1")
        seedParents()
        db.musicDao().insertSongs(listOf(song(locator = 77L, identity = "L9", fingerprint = "fp-L9", title = "Survivor")))
        db.libraryItemDao().insertIgnore(listOf(item("L9", locator = 77L, fingerprint = "fp-L9")))

        db.musicDao().markLibraryItemsDormantAndDropProjections(listOf("L1"))

        // The projection itself is gone — which is what makes every consumer correct by
        // construction, rather than each of twenty queries having to remember a lifecycle check.
        assertNull(db.musicDao().getSongByLocatorOnce(11L))

        // The main library list.
        assertFalse(db.musicDao().getAllSongsList().any { it.id == 11L })
        // Direct lookup and the playback guard.
        assertNull(db.musicDao().getSongByIdOnce(11L))
        assertNull(db.musicDao().getActiveSongByLocator(11L))
        // Search, over both arms: the FTS index and the LIKE fallback.
        assertTrue(db.musicDao().searchSongsMatch("Title*", emptyList(), false).first().none { it.id == 11L })
        assertTrue(db.musicDao().searchSongsLike("Title", emptyList(), false).first().none { it.id == 11L })

        // FTS derived state followed the row out: nothing is indexed under 11 any more.
        assertFalse(db.musicDao().searchSongsMatch("Title*", emptyList(), false).first().any { it.id == 11L })

        // The surviving item is untouched by any of it.
        assertTrue(db.musicDao().getAllSongsList().any { it.id == 77L })
        assertTrue(db.musicDao().searchSongsMatch("Survivor*", emptyList(), false).first().any { it.id == 77L })

        // And the user's state is still owned by L1.
        assertEquals(true, db.favoritesDao().isFavoriteForLibraryItem("L1"))
    }

    @Test
    fun the_fts_index_and_artist_cross_refs_follow_a_renumber() = runTest {
        seedParents()
        db.musicDao().insertSongs(listOf(song(locator = 11L, identity = "L1", title = "Original Title")))
        db.libraryItemDao().insertIgnore(listOf(item("L1", locator = 11L)))
        db.musicDao().insertSongArtistCrossRefs(
            listOf(SongArtistCrossRef(songId = 11L, artistId = 1L, isPrimary = true))
        )
        assertTrue(db.musicDao().searchSongsMatch("Original*", emptyList(), false).first().any { it.id == 11L })

        db.musicDao().reattachProjection(
            libraryItemId = "L1",
            oldLocator = 11L,
            incoming = song(locator = 22L, identity = "L1", title = "Renumbered Title"),
            stableKey = "fp-L1",
            fileSignature = "sig-L1",
        )
        db.musicDao().insertSongArtistCrossRefs(
            listOf(SongArtistCrossRef(songId = 22L, artistId = 1L, isPrimary = true))
        )

        // The index moved with the row rather than keeping the dead locator alive under the old text.
        assertFalse(db.musicDao().searchSongsMatch("Original*", emptyList(), false).first().any { it.id == 11L })
        assertTrue(db.musicDao().searchSongsMatch("Renumbered*", emptyList(), false).first().any { it.id == 22L })

        // Cross-refs cascade on the old row and exist for the new one.
        assertEquals(0, db.musicDao().getCrossRefsForSong(11L).size)
        assertEquals(1, db.musicDao().getCrossRefsForSong(22L).size)
        assertTrue(db.musicDao().getSongsForArtistList(1L).any { it.id == 22L })
        assertFalse(db.musicDao().getSongsForArtistList(1L).any { it.id == 11L })
    }

    // --- 6. rollback -------------------------------------------------------------------------------

    @Test
    fun a_failed_reattachment_leaves_the_original_projection_exactly_as_it_was() = runTest {
        seedLiveLibrary(locator = 11L, identity = "L1")

        // `songs.album_id` is a foreign key to `albums`. Pointing the incoming row at an album that
        // does not exist aborts the insert, which aborts the whole `@Transaction`.
        val doomed = song(locator = 22L, identity = "L1").copy(albumId = 999L)
        val threw = try {
            db.musicDao().reattachProjection(
                libraryItemId = "L1",
                oldLocator = 11L,
                incoming = doomed,
                stableKey = "fp-L1",
                fileSignature = "sig-L1",
            )
            false
        } catch (_: Exception) {
            true
        }
        assertTrue("the fixture must actually violate a constraint", threw)

        // Nothing was half-applied: A is still there, still owned by L1, and no B was left behind.
        assertNotNull(db.musicDao().getSongByLocatorOnce(11L))
        assertEquals("L1", db.musicDao().getSongByLocatorOnce(11L)?.libraryItemId)
        assertEquals(11L, db.libraryItemDao().getById("L1")?.currentLocator)
        assertEquals(LibraryItemState.ACTIVE, db.libraryItemDao().getById("L1")?.state)
        assertNull(db.musicDao().getSongByLocatorOnce(22L))
        assertEquals(true, db.favoritesDao().isFavoriteForLibraryItem("L1"))
        assertEquals(5, db.engagementDao().getEngagementByLibraryItem("L1")?.playCount)
    }

    // --- 7. overrides ------------------------------------------------------------------------------

    @Test
    fun a_user_edit_outlives_the_projection_that_used_to_hold_it() = runTest {
        seedLiveLibrary(locator = 11L, identity = "L1")
        assertEquals("The User's Title", db.musicDao().getSongByLocatorOnce(11L)?.title)

        // The card comes out. Under the old model the edit died here, because it lived on the row
        // that gets deleted.
        db.musicDao().markLibraryItemsDormantAndDropProjections(listOf("L1"))
        assertNull(db.musicDao().getSongByLocatorOnce(11L))
        assertEquals("The User's Title", db.libraryItemOverrideDao().getForLibraryItem("L1")?.title)

        // It comes back at a different locator. Dormancy pruned the album, so the scan re-creates
        // the parents before the projection, exactly as it does on a real rescan.
        seedParents()
        db.musicDao().insertSongs(listOf(song(locator = 33L, identity = "pending")))
        val override = db.libraryItemOverrideDao().getForLibraryItem("L1")
        db.musicDao().reattachProjection(
            libraryItemId = "L1",
            oldLocator = null,
            incoming = db.musicDao().getSongByLocatorOnce(33L)!!.copy(title = "Title from the file"),
            stableKey = "fp-L1",
            fileSignature = "sig-L1",
        )
        db.musicDao().reapplyLibraryItemOverridesToProjections()

        // The edit is back on the projection, taken from the durable owner rather than from the
        // deleted row — and the file's own title never overwrote it.
        assertEquals("The User's Title", db.musicDao().getSongByLocatorOnce(33L)?.title)
        assertEquals(true, db.musicDao().getSongByLocatorOnce(33L)?.titleUserEdited)
        assertEquals("The User's Title", override?.title)
    }

    @Test
    fun clearing_an_override_lets_the_file_title_come_back() = runTest {
        seedLiveLibrary(locator = 11L, identity = "L1")
        assertEquals("The User's Title", db.musicDao().getSongByLocatorOnce(11L)?.title)

        db.libraryItemOverrideDao().setTitle("L1", value = null, edited = false)
        db.musicDao().insertSongs(listOf(song(locator = 11L, identity = "L1", title = "The File's Title")))
        db.musicDao().reapplyLibraryItemOverridesToProjections()

        // "Not edited" reads as *no opinion*, so the file's own title wins — an override the user
        // cleared must not keep pinning a title they no longer want.
        assertEquals("The File's Title", db.musicDao().getSongByLocatorOnce(11L)?.title)
        assertFalse(db.musicDao().getSongByLocatorOnce(11L)?.titleUserEdited == true)
    }

    // --- 8. identity is enough ---------------------------------------------------------------------

    @Test
    fun every_piece_of_user_state_is_reachable_by_identity_alone() = runTest {
        seedLiveLibrary(locator = 11L, identity = "L1")

        assertEquals(true, db.favoritesDao().isFavoriteForLibraryItem("L1"))
        assertEquals("line one", db.lyricsDao().getLyricsForLibraryItem("L1")?.content)
        assertEquals(1, db.audioBookmarkDao().getBookmarksForLibraryItem("L1").size)
        assertEquals("The User's Title", db.libraryItemOverrideDao().getForLibraryItem("L1")?.title)
        assertEquals(5, db.engagementDao().getEngagementByLibraryItem("L1")?.playCount)
        assertEquals(listOf("L1"), db.favoritesDao().getFavoriteLibraryItemIds())
    }
}
