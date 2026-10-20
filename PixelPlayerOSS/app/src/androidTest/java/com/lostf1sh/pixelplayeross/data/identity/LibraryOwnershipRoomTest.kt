package com.lostf1sh.pixelplayeross.data.identity

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lostf1sh.pixelplayeross.data.database.AlbumEntity
import com.lostf1sh.pixelplayeross.data.database.ArtistEntity
import com.lostf1sh.pixelplayeross.data.database.FavoritesEntity
import com.lostf1sh.pixelplayeross.data.database.LibraryItemEntity
import com.lostf1sh.pixelplayeross.data.database.LibraryItemState
import com.lostf1sh.pixelplayeross.data.database.PixelPlayerDatabase
import com.lostf1sh.pixelplayeross.data.database.SongEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * Logical ownership, against a real SQLite database.
 *
 * Everything claimed about the identity model until now was asserted against pure functions. These
 * run against actual Room — real schema, real constraints, real migrations — because the questions
 * that matter here are about what the *database* permits, not about what a decision procedure
 * computes.
 */
@RunWith(AndroidJUnit4::class)
class LibraryOwnershipRoomTest {

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

    // --- helpers ---------------------------------------------------------------------------------

    private fun song(
        locator: Long,
        identity: String,
        fingerprint: String = "fp-$identity",
        duration: Long = 240_000L,
        size: Long = 8_000_000L,
    ) = SongEntity(
        id = locator,
        libraryItemId = identity,
        stableKey = fingerprint,
        fileSize = size,
        title = "Title $identity",
        artistName = "Artist",
        artistId = 1L,
        albumArtist = "Artist",
        albumName = "Album",
        albumId = 1L,
        contentUriString = "content://media/audio/$locator",
        albumArtUriString = null,
        duration = duration,
        genre = "Genre",
        filePath = "/music/$locator.flac",
        parentDirectoryPath = "/music",
    )

    /**
     * Seeds the parent rows a song projection needs.
     *
     * `songs` declares foreign keys to `albums` and `artists`, so inserting a projection without
     * them fails with SQLITE_CONSTRAINT_FOREIGNKEY. Two Room tests were red for exactly this
     * reason — the failure looked like an identity defect and was a fixture defect.
     */
    private suspend fun seedParents() {
        db.musicDao().insertAlbumsIgnoreConflicts(
            listOf(
                AlbumEntity(
                    id = 1L,
                    title = "Album",
                    artistName = "Artist",
                    artistId = 1L,
                    albumArtUriString = null,
                    songCount = 0,
                    dateAdded = 1L,
                    year = 2024,
                )
            )
        )
        db.musicDao().insertArtistsIgnoreConflicts(
            listOf(ArtistEntity(id = 1L, name = "Artist", trackCount = 0))
        )
    }

    private suspend fun item(
        identity: String,
        locator: Long?,
        fingerprint: String = "fp-$identity",
    ) = LibraryItemEntity(
        libraryItemId = identity,
        state = LibraryItemState.ACTIVE,
        stableKey = fingerprint,
        fileSignature = "sig-$identity",
        currentLocator = locator,
        firstSeenAt = 1_000L,
        lastSeenAt = 2_000L,
    )

    // --- §9 / §10: lifecycle ---------------------------------------------------------------------

    @Test
    fun a_logical_item_can_exist_with_no_projection_at_all() = runTest {
        // The invariant the previous model could not express.
        db.libraryItemDao().insertIgnore(listOf(item("L1", locator = LibraryItemEntity.NO_LOCATOR)))

        val stored = db.libraryItemDao().getById("L1")
        assertEquals("L1", stored?.libraryItemId)
        assertNull(stored?.currentLocator)
    }

    @Test
    fun temporary_loss_makes_items_dormant_without_discarding_their_evidence() = runTest {
        val items = (1L..50L).map { item("L$it", locator = it) }
        db.libraryItemDao().insertIgnore(items)

        items.forEach { db.musicDao().markLibraryItemDormant(it.libraryItemId) }

        assertEquals(50, db.libraryItemDao().countByState(LibraryItemState.DORMANT))
        // Matching evidence survives: that is what makes a return recognisable. Discarding it here
        // would turn every ejected card into a permanent loss of identity.
        assertEquals("fp-L7", db.libraryItemDao().getById("L7")?.stableKey)
    }

    @Test
    fun a_returning_item_is_reactivated_under_the_same_identity() = runTest {
        db.libraryItemDao().insertIgnore(listOf(item("L1", locator = 11L)))
        db.musicDao().markLibraryItemDormant("L1")

        // Storage comes back; the scan sees the same logical item under a new locator.
        db.musicDao().markLibraryItemActive("L1", 22L)

        val reactivated = db.libraryItemDao().getById("L1")
        assertEquals(LibraryItemState.ACTIVE, reactivated?.state)
        assertEquals(22L, reactivated?.currentLocator)
    }

    // --- §8: locator reuse ------------------------------------------------------------------------

    @Test
    fun a_reused_locator_cannot_be_held_by_two_logical_items_at_once() = runTest {
        // The unique index is the structural guarantee. If it were absent, both L1 and L2 could
        // claim locator 11 and the ambiguity would be resolved by whoever queried last.
        db.libraryItemDao().insertIgnore(listOf(item("L1", locator = 11L)))

        // L1 gives up the locator before the new item takes it.
        db.musicDao().markLibraryItemDormant("L1")
        db.libraryItemDao().insertIgnore(listOf(item("L2", locator = 11L)))

        assertEquals(LibraryItemState.DORMANT, db.libraryItemDao().getById("L1")?.state)
        assertEquals(11L, db.libraryItemDao().getById("L2")?.currentLocator)
        assertNull(db.libraryItemDao().getById("L1")?.currentLocator)
    }

    @Test
    fun two_active_items_may_not_share_a_locator() = runTest {
        db.libraryItemDao().insertIgnore(listOf(item("L1", locator = 11L)))
        // The second insert must lose to the unique index rather than silently duplicating.
        db.libraryItemDao().insertIgnore(listOf(item("L2", locator = 11L)))

        assertNull(db.libraryItemDao().getById("L2"))
        assertEquals(1, db.libraryItemDao().countByState(LibraryItemState.ACTIVE))
    }

    // --- §10: renumber ---------------------------------------------------------------------------

    @Test
    fun a_renumber_moves_the_projection_and_leaves_the_identity_untouched() = runTest {
        seedParents()
        db.musicDao().insertSongs(listOf(song(locator = 11L, identity = "L1")))
        db.libraryItemDao().insertIgnore(listOf(item("L1", locator = 11L)))

        // The same logical item is now visible at locator 22. This is the *desired* contract, not
        // the current one: the old projection must be gone, not merely superseded.
        db.musicDao().reattachProjection(
            libraryItemId = "L1",
            oldLocator = 11L,
            incoming = song(locator = 22L, identity = "L1"),
            stableKey = "fp-L1",
            fileSignature = "sig-L1",
        )

        // LibraryItem: still L1, now pointing at B.
        val l1 = db.libraryItemDao().getById("L1")
        assertEquals(LibraryItemState.ACTIVE, l1?.state)
        assertEquals(22L, l1?.currentLocator)

        // Projection: B exists and belongs to L1...
        val b = db.musicDao().getSongByLocatorOnce(22L)
        assertEquals("L1", b?.libraryItemId)

        // ...and A is GONE. A stale projection is a phantom track: still visible, still
        // searchable, still playable. Its absence is the point of the test.
        assertNull(db.musicDao().getSongByLocatorOnce(11L))

        // Exactly one projection for this identity, and no second logical item was invented.
        assertEquals(1, db.musicDao().countProjectionsForLibraryItem("L1"))
        assertNull(db.libraryItemDao().getById("L2"))
    }

    // --- §3/§12: the favourites projection ---------------------------------------------------------

    @Test
    fun the_favourite_projection_flag_tracks_the_logical_item_across_a_locator_change() = runTest {
        seedParents()
        db.musicDao().insertSongs(listOf(song(locator = 11L, identity = "L1")))
        db.libraryItemDao().insertIgnore(listOf(item("L1", locator = 11L)))

        // The user's favourite belongs to the logical item, not to a locator.
        db.favoritesDao().setFavoriteForLibraryItem("L1", isFavorite = true, timestamp = 1L, currentLocator = 11L)

        // The item moves to a new locator.
        db.musicDao().reattachProjection(
            libraryItemId = "L1",
            oldLocator = 11L,
            incoming = song(locator = 22L, identity = "L1"),
            stableKey = "fp-L1",
            fileSignature = "sig-L1",
        )

        // The favourite still belongs to L1...
        assertEquals(true, db.favoritesDao().isFavoriteForLibraryItem("L1"))

        // ...and the new projection reflects it. A projection created after the fact must derive
        // its cached flag from identity, or the UI shows an unfavourited track that is favourited.
        assertEquals(true, db.musicDao().getSongByLocatorOnce(22L)?.isFavorite)
    }

    // --- §14: query authority ----------------------------------------------------------------------

    @Test
    fun engagement_is_found_by_identity_not_by_the_locator_that_produced_it() = runTest {
        db.engagementDao().seedLegacy(
            songId = "11",
            libraryItemId = "L1",
            plays = 1,
            completions = 1,
            skips = 0,
            durationMs = 240_000L,
            timestamp = 500L,
        )

        // Readable by identity alone — no locator involved.
        val engagement = db.engagementDao().getEngagementByLibraryItem("L1")
        assertEquals(1, engagement?.playCount)
        assertEquals(1, engagement?.completionCount)
        assertEquals("L1", engagement?.libraryItemId)
    }

    @Test
    fun two_items_with_different_locators_keep_separate_engagement() = runTest {
        db.engagementDao().seedLegacy("11", "L1", 1, 0, 0, 1_000L, 1L)
        db.engagementDao().seedLegacy("22", "L2", 5, 0, 0, 2_000L, 2L)

        assertEquals(1, db.engagementDao().getEngagementByLibraryItem("L1")?.playCount)
        assertEquals(5, db.engagementDao().getEngagementByLibraryItem("L2")?.playCount)
    }
}
