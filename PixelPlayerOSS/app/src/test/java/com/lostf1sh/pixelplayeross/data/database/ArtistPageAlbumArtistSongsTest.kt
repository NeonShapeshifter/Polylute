package com.lostf1sh.pixelplayeross.data.database

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.stats.Kit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Owner 11-23: an artist page lists the songs credited to the artist **and** every song of the albums they are the album artist of.
 * The shape of the report, with synthetic names: a 24-song retrospective whose album artist is "Artist New", 8 songs credited to
 * "Artist New" and 16 to "Artist Old" (the same person's former name). The page showed that album with 8 songs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ArtistPageAlbumArtistSongsTest {
    @Test fun the_artist_page_has_the_whole_album_they_are_album_artist_of_and_their_guest_songs_once_each(): Unit = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PixelPlayerDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = db.musicDao()
            val new = 1L
            val old = 2L
            val other = 3L
            dao.insertArtists(listOf(ArtistEntity(new, "Artist New", 9), ArtistEntity(old, "Artist Old", 16), ArtistEntity(other, "Artist Other", 2)))
            dao.insertAlbums(listOf(AlbumEntity(7, "Retrospective", "Artist New", new, null, 24, 0, 2026), AlbumEntity(8, "Their Album", "Artist Other", other, null, 2, 0, 2020)))
            val dir = "/storage/emulated/0/DAP music/Artist New/Retrospective"
            val retro = (1L..24L).map { id ->
                val credited = if (id <= 8) new else old
                Kit.song(id, title = "Retro %02d".format(id), artist = if (credited == new) "Artist New" else "Artist Old", album = "Retrospective", albumId = 7)
                    .copy(artistId = credited, libraryItemId = "r-$id").toEntity("$dir/$id.flac", dir).copy(albumArtistId = new)
            }
            // another artist's album, where Artist New is a guest on one song
            val otherDir = "/storage/emulated/0/DAP music/Artist Other/Their Album"
            val theirs = (25L..26L).map { id ->
                Kit.song(id, title = "Theirs $id", artist = "Artist Other", album = "Their Album", albumId = 8)
                    .copy(artistId = other, libraryItemId = "o-$id").toEntity("$otherDir/$id.flac", otherDir).copy(albumArtistId = other)
            }
            dao.insertSongs(retro + theirs)
            dao.insertSongArtistCrossRefs(
                retro.map { SongArtistCrossRef(it.id, it.artistId, isPrimary = true) } +
                    theirs.map { SongArtistCrossRef(it.id, other, isPrimary = true) } +
                    SongArtistCrossRef(26L, new, isPrimary = false),
            )

            val newPage = dao.getSongsForArtist(new).first().map { it.id }
            // the whole retrospective (24) plus the guest song on the other album, each once
            assertThat(newPage).containsExactlyElementsIn((1L..24L) + 26L)
            assertThat(newPage).containsNoDuplicates()
            assertThat(dao.getSongsForArtistList(new).map { it.id }).containsExactlyElementsIn(newPage)

            // the old name keeps exactly the songs credited to it; it is album artist of nothing
            assertThat(dao.getSongsForArtist(old).first().map { it.id }).containsExactlyElementsIn(9L..24L)
            // the other artist: their album, not the guest's retrospective
            assertThat(dao.getSongsForArtist(other).first().map { it.id }).containsExactly(25L, 26L)
        } finally {
            db.close()
        }
    }
}
