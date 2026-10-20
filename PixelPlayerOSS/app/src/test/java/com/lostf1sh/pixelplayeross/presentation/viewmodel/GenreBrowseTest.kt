package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.ArtistRef
import com.lostf1sh.pixelplayeross.data.model.Song
import org.junit.jupiter.api.Test

/**
 * B29-L6 (N-03): what a genre page lists is each song once, the albums and artists they belong to, and one definition of the
 * order. The query reads one table with no join, so the repeats the owner saw were repeated headers and credits (an album under
 * one header per credit string) and a queue in another order than the page; the tests pin the page's own model against that.
 */
class GenreBrowseTest {

    private fun song(
        id: Int,
        title: String = "Song $id",
        artist: String = "Artist A",
        artistId: Long = 1L,
        credits: List<ArtistRef> = listOf(ArtistRef(artistId, artist, isPrimary = true)),
        album: String = "Album 1",
        albumId: Long = 10L,
        item: String = "item-$id",
        track: Int = 0,
        year: Int = 0,
        albumArtist: String? = null,
    ) = Song.emptySong().copy(
        id = "$id", libraryItemId = item, title = title, artist = artist, artistId = artistId, artists = credits,
        album = album, albumId = albumId, trackNumber = track, year = year, albumArtist = albumArtist, genre = "Rock",
    )

    @Test
    fun `a song with two genres or several artists is one row on each page it belongs to`() {
        // "Rock, Pop" is returned by the Rock query and by the Pop query: once each, never twice on one page
        val twoGenres = song(1).copy(genre = "Rock, Pop")
        val collaboration = song(2, artist = "Artist A; Artist B", credits = listOf(ArtistRef(1, "Artist A", true), ArtistRef(2, "Artist B", false)))
        val rockPage = GenreBrowse.distinctFiles(listOf(twoGenres, collaboration, song(3)))
        assertThat(rockPage.map { it.id }).containsExactly("1", "2", "3").inOrder()
        assertThat(SourceOrder.genrePage(rockPage).map { it.id }).containsExactly("1", "2", "3")
    }

    @Test
    fun `a row the result names twice is shown once, two files that merely look alike are both kept`() {
        val listedTwice = listOf(song(1, item = "same-item"), song(1, item = "same-item"))
        assertThat(GenreBrowse.distinctFiles(listedTwice)).hasSize(1)

        // two files with the same title, artist and album: different library items, different locators (the owner has five such
        // pairs under Hip Hop, with different sizes and lengths)
        val lookAlike = listOf(song(1, title = "Same", item = "a"), song(2, title = "Same", item = "b"))
        assertThat(GenreBrowse.distinctFiles(lookAlike)).hasSize(2)

        // and an item with no durable identity yet is told apart by its locator
        val untagged = listOf(song(5, item = ""), song(6, item = ""), song(5, item = ""))
        assertThat(GenreBrowse.distinctFiles(untagged).map { it.id }).containsExactly("5", "6").inOrder()
    }

    @Test
    fun `artists are by primary artist, so a collaboration is not an artist of its own and an album is not repeated`() {
        val songs = listOf(
            song(1, artist = "Artist A", artistId = 1, album = "The Show", albumId = 20),
            song(2, artist = "Artist A; Artist B", artistId = 1, credits = listOf(ArtistRef(1, "Artist A", true), ArtistRef(2, "Artist B", false)), album = "The Show", albumId = 20),
            song(3, artist = "Artist A; Artist C", artistId = 1, credits = listOf(ArtistRef(1, "Artist A", true), ArtistRef(3, "Artist C", false)), album = "The Show", albumId = 20),
            song(4, artist = "Artist D", artistId = 4, album = "Other", albumId = 21),
        )
        val artists = GenreBrowse.artists(songs)
        assertThat(artists.map { it.name }).containsExactly("Artist A", "Artist D").inOrder()
        assertThat(artists.first { it.name == "Artist A" }.songCount).isEqualTo(3)
        assertThat(artists.first { it.name == "Artist A" }.albumCount).isEqualTo(1)

        val albums = GenreBrowse.albums(songs)
        assertThat(albums.map { it.title }).containsExactly("Other", "The Show").inOrder()
        assertThat(albums.first { it.title == "The Show" }.songCount).isEqualTo(3)
    }

    @Test
    fun `an album names its album artist when the songs carry one, else the primary artist`() {
        val withAlbumArtist = listOf(song(1, artist = "Guest", artistId = 9, album = "Compilation", albumId = 30, albumArtist = "Various Artists"))
        assertThat(GenreBrowse.albums(withAlbumArtist).single().artist).isEqualTo("Various Artists")
        val without = listOf(song(2, artist = "Solo", artistId = 3, album = "LP", albumId = 31))
        assertThat(GenreBrowse.albums(without).single().artist).isEqualTo("Solo")
    }

    @Test
    fun `the Songs list is one definition and does not depend on the order the database returned`() {
        val songs = listOf(
            song(1, title = "Beta", artist = "Zed", artistId = 3, album = "Z Album", albumId = 3, track = 2),
            song(2, title = "alpha", artist = "Amy", artistId = 1, album = "B Album", albumId = 1, track = 1),
            song(3, title = "Gamma", artist = "Amy", artistId = 1, album = "B Album", albumId = 1, track = 2),
            song(4, title = "Delta", artist = "Bob", artistId = 2, album = "A Album", albumId = 2, track = 1),
        )
        val expected = SourceOrder.genrePage(songs).map { it.id }
        repeat(20) { seed ->
            assertThat(SourceOrder.genrePage(songs.shuffled(java.util.Random(seed.toLong()))).map { it.id }).isEqualTo(expected)
        }
        assertThat(SourceOrder.genrePage(songs).map { it.title }).containsExactly("alpha", "Beta", "Delta", "Gamma").inOrder()
    }

    @Test
    fun `songs with the same title are ordered by artist, then album, then the album's own order`() {
        val songs = listOf(
            song(1, title = "Intro", artist = "Zed", artistId = 3, album = "A Album", albumId = 3),
            song(2, title = "Intro", artist = "Amy", artistId = 1, album = "Z Album", albumId = 1),
            song(3, title = "Intro", artist = "Amy", artistId = 1, album = "B Album", albumId = 2, track = 2),
            song(4, title = "Intro", artist = "Amy", artistId = 1, album = "B Album", albumId = 2, track = 1),
        )
        assertThat(SourceOrder.genrePage(songs).map { it.id }).containsExactly("4", "3", "2", "1").inOrder()
    }
}
