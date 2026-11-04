package com.lostf1sh.pixelplayeross.data.database

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.stats.Kit
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B57.5 2.1: the folders an album's songs are in, from the library's own paths: one grouped count per album (what the animated-cover finder asks once per album). Album One's shape: 11 songs in
 * `Artist One/[2023] Album One`, 4 loose in `DAP music`; another album and a streamed song are not counted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class B575AlbumFolderCountsTest {
    @Test fun the_folders_of_an_album_with_how_many_of_its_songs_each_holds() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PixelPlayerDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = db.musicDao()
            dao.insertArtists(listOf(ArtistEntity(1, "Artist One", 3)))
            dao.insertAlbums(listOf(AlbumEntity(7, "Album One", "Artist One", 1, null, 15, 0, 2023), AlbumEntity(8, "Other", "Artist One", 1, null, 2, 0, 2022)))
            val albumOne = "/storage/emulated/0/DAP music/Artist One/[2023] Album One"
            val loose = "/storage/emulated/0/DAP music"
            val songs = (1..11).map { Kit.song(it.toLong()).copy(artistId = 1, albumId = 7, libraryItemId = "e-$it").toEntity("$albumOne/$it.flac", albumOne) } +
                (12..15).map { Kit.song(it.toLong()).copy(artistId = 1, albumId = 7, libraryItemId = "e-$it").toEntity("$loose/$it.flac", loose) } +
                (16..17).map { Kit.song(it.toLong()).copy(artistId = 1, albumId = 8, libraryItemId = "o-$it").toEntity("$loose/$it.flac", loose) } +
                Kit.song(18).copy(artistId = 1, albumId = 7, libraryItemId = "s-18").toEntity("/stream/18.flac", "/stream").copy(sourceType = 1)
            dao.insertSongs(songs)
            val counts = dao.getAlbumFolderCounts(7).associate { it.folder to it.songCount }
            assertThat(counts).containsExactly(albumOne, 11, loose, 4)
            assertThat(dao.getAlbumFolderCounts(8).associate { it.folder to it.songCount }).containsExactly(loose, 2)
            assertThat(dao.getAlbumFolderCounts(999)).isEmpty()
        } finally {
            db.close()
        }
    }
}
