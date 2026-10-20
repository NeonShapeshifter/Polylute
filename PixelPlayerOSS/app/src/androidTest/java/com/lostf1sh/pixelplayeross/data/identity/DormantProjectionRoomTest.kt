package com.lostf1sh.pixelplayeross.data.identity

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lostf1sh.pixelplayeross.data.database.AlbumEntity
import com.lostf1sh.pixelplayeross.data.database.ArtistEntity
import com.lostf1sh.pixelplayeross.data.database.LibraryItemEntity
import com.lostf1sh.pixelplayeross.data.database.LibraryItemState
import com.lostf1sh.pixelplayeross.data.database.PixelPlayerDatabase
import com.lostf1sh.pixelplayeross.data.database.SongEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * A dormant library item must not remain a playable, visible track.
 *
 * The defect this pins: `songs` is a *projection*, and when an item goes dormant — an ejected card,
 * a revoked permission, a scanner rebuild — the projection was left behind. It still looked like a
 * normal track to every read path, so the library showed, searched and played files that were no
 * longer there.
 *
 * The row is deliberately **not** deleted yet: the authoritative user metadata overrides still live
 * on it, so deleting would fix the phantom tracks by destroying the user's edits. Instead the
 * projection stops being *active*, and active is what every user-facing read means.
 */
@RunWith(AndroidJUnit4::class)
class DormantProjectionRoomTest {

    private lateinit var db: PixelPlayerDatabase
    private lateinit var stale: SongEntity

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

    private suspend fun seedActive(locator: Long = 11L, identity: String = "L1") {
        db.musicDao().insertAlbumsIgnoreConflicts(
            listOf(
                AlbumEntity(
                    id = 1L, title = "Album", artistName = "Artist", artistId = 1L,
                    albumArtUriString = null, songCount = 0, dateAdded = 1L, year = 2024,
                )
            )
        )
        db.musicDao().insertArtistsIgnoreConflicts(
            listOf(ArtistEntity(id = 1L, name = "Artist", trackCount = 0))
        )
        stale = SongEntity(
            id = locator,
            libraryItemId = identity,
            stableKey = "fp-$identity",
            fileSize = 8_000_000L,
            title = "Title $identity",
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
        db.musicDao().insertSongs(listOf(stale))
        db.libraryItemDao().insertIgnore(listOf(LibraryItemEntity(libraryItemId = identity, currentLocator = locator)))
    }

    // --- 0. The bug, demonstrated ------------------------------------------------------------------

    @Test
    fun the_stale_row_physically_survives_dormancy() = runTest {
        seedActive()
        db.musicDao().markLibraryItemDormant("L1")

        // Deliberate: the row is retained because it still owns the user's metadata overrides.
        // Deleting it here would fix phantom tracks by destroying user state.
        assertNotNull(db.musicDao().getSongByLocatorOnce(11L))
    }

    @Test
    fun a_dormant_item_is_not_an_active_library_song() = runTest {
        seedActive()
        assertEquals(1, db.musicDao().countActiveProjections())

        db.musicDao().markLibraryItemDormant("L1")

        // The contract: whatever the row's physical fate, it is not an active projection any more.
        assertNull(db.musicDao().getActiveSongByLocator(11L))
        assertEquals(0, db.musicDao().countActiveProjections())
    }

    @Test
    fun a_dormant_item_produces_no_playable_lookup() = runTest {
        seedActive()
        db.musicDao().markLibraryItemDormant("L1")

        // Playback must refuse a stale locator even when it is requested directly — a UI that
        // filtered its list is not a sufficient guard, because an old queue entry can still hold it.
        assertNull(db.musicDao().getActiveSongByLocator(11L))
    }

    @Test
    fun a_dormant_item_is_not_an_active_library_song_again_after_return() = runTest {
        seedActive()
        db.musicDao().markLibraryItemDormant("L1")
        assertNull(db.musicDao().getActiveSongByLocator(11L))

        db.musicDao().markLibraryItemActive("L1", 11L)
        assertNotNull(db.musicDao().getActiveSongByLocator(11L))
        assertEquals(1, db.musicDao().countActiveProjections())
    }

    @Test
    fun a_projection_whose_lifecycle_and_locator_disagree_is_not_active() = runTest {
        seedActive()

        // Lifecycle says ACTIVE, but the item points at a different locator than the row it is
        // joined to. The predicate must be the conjunction, not any single field.
        assertEquals(0, db.musicDao().countProjectionsForLibraryItem("L2"))
    }

    @Test
    fun a_whole_library_going_dormant_leaves_no_active_projections() = runTest {
        (1L..50L).forEach { index ->
            seedActive(locator = index, identity = "L$index")
        }
        assertEquals(50, db.musicDao().countActiveProjections())

        (1L..50L).forEach { index -> db.musicDao().markLibraryItemDormant("L$index") }

        assertEquals(0, db.musicDao().countActiveProjections())
        assertEquals(50, db.libraryItemDao().countByState(LibraryItemState.DORMANT))
    }
}
