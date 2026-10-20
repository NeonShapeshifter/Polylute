package com.lostf1sh.pixelplayeross.data.identity

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lostf1sh.pixelplayeross.data.database.AlbumEntity
import com.lostf1sh.pixelplayeross.data.database.ArtistEntity
import com.lostf1sh.pixelplayeross.data.database.FavoritesEntity
import com.lostf1sh.pixelplayeross.data.database.LyricsEntity
import com.lostf1sh.pixelplayeross.data.database.PixelPlayerDatabase
import com.lostf1sh.pixelplayeross.data.database.SongEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * AUD-009, against real SQLite: a REBUILD of the local projections — including one whose scan came
 * back empty (revoked permission, ejected card) — keeps every favourite and every lyric.
 *
 * R3-1 / AUD-109: surviving in `favorites` is not enough. `songs.is_favorite` is a cache the
 * favourites triggers only maintain on writes to `favorites`; a rebuild or an incremental sync that
 * rewrites a song from a scanned entity (always `isFavorite = false`) left it at 0, and everything
 * that reads the cache (smart mixes, `Song.isFavorite`) lost the favourite.
 */
@RunWith(AndroidJUnit4::class)
class RebuildPreservesUserStateRoomTest {

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

    private val album = AlbumEntity(
        id = 1L, title = "Album", artistName = "Artist", artistId = 1L,
        albumArtUriString = null, songCount = 0, dateAdded = 1L, year = 2024,
    )
    private val artist = ArtistEntity(id = 1L, name = "Artist", trackCount = 0)

    /** A song as the scanner produces it: never favourite, whatever the user did. */
    private fun scanned(locator: Long) = SongEntity(
        id = locator,
        libraryItemId = "L$locator",
        stableKey = "fp-$locator",
        fileSize = 8_000_000L,
        title = "Title $locator",
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

    @Test
    fun an_empty_rebuild_keeps_favourites_and_lyrics() = runTest {
        db.favoritesDao().insertAll(listOf(FavoritesEntity(songId = 7L, libraryItemId = "L7")))
        db.lyricsDao().insert(LyricsEntity(songId = 7L, content = "la la", libraryItemId = "L7"))

        db.musicDao().rebuildLocalMusicDataWithCrossRefs(emptyList(), emptyList(), emptyList(), emptyList())

        assertEquals(listOf("L7"), db.favoritesDao().getFavoriteLibraryItemIds())
        assertEquals("la la", db.lyricsDao().getLyricsForLibraryItem("L7")?.content)
    }

    @Test
    fun a_rebuild_keeps_the_favourite_cache_on_the_song() = runTest {
        db.musicDao().rebuildLocalMusicDataWithCrossRefs(listOf(scanned(7L)), listOf(album), listOf(artist), emptyList())
        db.favoritesDao().insertAll(listOf(FavoritesEntity(songId = 7L, libraryItemId = "L7")))
        assertEquals(true, db.musicDao().getFavoriteStatus(7L))

        db.musicDao().rebuildLocalMusicDataWithCrossRefs(listOf(scanned(7L)), listOf(album), listOf(artist), emptyList())

        assertEquals(true, db.musicDao().getFavoriteStatus(7L))
    }

    @Test
    fun an_incremental_sync_keeps_the_favourite_cache_on_the_song() = runTest {
        db.musicDao().incrementalSyncMusicData(listOf(scanned(7L)), listOf(album), listOf(artist), emptyList(), emptyList())
        db.favoritesDao().insertAll(listOf(FavoritesEntity(songId = 7L, libraryItemId = "L7")))

        // A tag edit rewrites the row from a freshly scanned entity.
        db.musicDao().incrementalSyncMusicData(listOf(scanned(7L)), listOf(album), listOf(artist), emptyList(), emptyList())

        assertEquals(true, db.musicDao().getFavoriteStatus(7L))
    }
}
