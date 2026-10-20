package com.lostf1sh.pixelplayeross.presentation.components

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.Album
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.model.SortOption
import org.junit.jupiter.api.Test

/**
 * B29-L8 (N-10): the letter the Library's scrollbar shows while the thumb is dragged, for the list as it is ordered now.
 *
 * The lists come ordered by SQLite's `COLLATE NOCASE`: symbols, digits, A to Z, then whatever is not ASCII. The label of a row is
 * where that row is, and a sort that is not alphabetical says something true for it or nothing.
 */
class FastScrollLabelsTest {

    private fun song(title: String = "x", artist: String = "x", album: String = "x", dateAdded: Long = 0L) =
        Song.emptySong().copy(title = title, artist = artist, album = album, dateAdded = dateAdded)

    @Test
    fun `titles that start with symbols or digits are the hash section, not the letter after the symbol`() {
        for (title in listOf("!!!!!!!", "\$AD BOYZ II", "#Ysya2020", "\"The Take Over\"", "...album", "1999", "007", "(Intro)", "¿Qué?", "🎵 Song", "-", "'Cause")) {
            assertThat(extractFastScrollGlyph(title)).isEqualTo("#")
        }
    }

    @Test
    fun `an accented letter goes under its base letter, in either case`() {
        assertThat(extractFastScrollGlyph("Álvaro")).isEqualTo("A")
        assertThat(extractFastScrollGlyph("ñandú")).isEqualTo("N")
        assertThat(extractFastScrollGlyph("Éxodo")).isEqualTo("E")
        assertThat(extractFastScrollGlyph("Ünder")).isEqualTo("U")
        assertThat(extractFastScrollGlyph("öl")).isEqualTo("O")
        assertThat(extractFastScrollGlyph("ábaco")).isEqualTo("A")
    }

    @Test
    fun `plain letters are their capital, a non-Latin letter is itself, blank is nothing`() {
        assertThat(extractFastScrollGlyph("zeta")).isEqualTo("Z")
        assertThat(extractFastScrollGlyph("  Beta")).isEqualTo("B")
        assertThat(extractFastScrollGlyph("Мир")).isEqualTo("М")
        assertThat(extractFastScrollGlyph("")).isNull()
        assertThat(extractFastScrollGlyph("   ")).isNull()
        assertThat(extractFastScrollGlyph(null)).isNull()
    }

    @Test
    fun `along a list in the order the library has it the labels never go back, so there is no second A to Z`() {
        val titles = listOf("!!!!!!!", "\"The Take Over\"", "#Ysya", "\$AD", "007", "1999", "alpha", "Beta", "beta two", "Gamma", "zed", "Zulu")
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
        val labels = titles.map { extractFastScrollGlyph(it)!! }
        // symbols and digits come first and are "#", then the letters rise
        assertThat(labels.take(6)).containsExactly("#", "#", "#", "#", "#", "#")
        assertThat(labels.drop(6)).isInOrder()
        assertThat(labels).isInOrder(compareBy<String> { if (it == "#") "" else it })
    }

    @Test
    fun `a title sort labels by the title, an artist sort by the artist, an album sort by the album`() {
        val s = song(title = "Beta", artist = "Álvaro", album = "Zeta")
        assertThat(songFastScrollLabel(s, SortOption.SongTitleAZ)).isEqualTo("B")
        assertThat(songFastScrollLabel(s, SortOption.SongTitleZA)).isEqualTo("B")
        assertThat(songFastScrollLabel(s, SortOption.SongArtist)).isEqualTo("A")
        assertThat(songFastScrollLabel(s, SortOption.SongAlbum)).isEqualTo("Z")
        assertThat(songFastScrollLabel(s, SortOption.LikedSongTitleAZ)).isEqualTo("B")
    }

    @Test
    fun `a sort by date says the year, and the sorts that have no true label say nothing, never a letter`() {
        val added = song(title = "Beta", dateAdded = 1_760_000_000_000L) // October 2025, as the library stores it (milliseconds)
        assertThat(songFastScrollLabel(added, SortOption.SongDateAdded)).isEqualTo("2025")
        assertThat(songFastScrollLabel(added, SortOption.SongDateAddedAsc)).isEqualTo("2025")
        // seconds, as MediaStore gives it
        assertThat(songFastScrollLabel(song(dateAdded = 1_760_000_000L), SortOption.SongDateAdded)).isEqualTo("2025")
        assertThat(songFastScrollLabel(added, SortOption.SongDuration)).isNull()
        assertThat(songFastScrollLabel(added, SortOption.SongDurationAsc)).isNull()
        assertThat(songFastScrollLabel(added, SortOption.SongDefaultOrder)).isNull()
        assertThat(songFastScrollLabel(song(dateAdded = 0L), SortOption.SongDateAdded)).isNull()
    }

    @Test
    fun `albums are labelled by title, artist or release year, and by the year added`() {
        val album = Album(id = 1, title = "Éxitos", artist = "Zed", year = 1994, dateAdded = 1_760_000_000_000L, albumArtUriString = null, songCount = 3)
        assertThat(albumFastScrollLabel(album, SortOption.AlbumTitleAZ)).isEqualTo("E")
        assertThat(albumFastScrollLabel(album, SortOption.AlbumArtist)).isEqualTo("Z")
        assertThat(albumFastScrollLabel(album, SortOption.AlbumReleaseYear)).isEqualTo("1994")
        assertThat(albumFastScrollLabel(album, SortOption.AlbumReleaseYearAsc)).isEqualTo("1994")
        assertThat(albumFastScrollLabel(album, SortOption.AlbumDateAdded)).isEqualTo("2025")
        assertThat(albumFastScrollLabel(album, SortOption.AlbumSizeDesc)).isNull()
        assertThat(albumFastScrollLabel(album.copy(year = 0), SortOption.AlbumReleaseYear)).isNull()
    }

    @Test
    fun `artists are labelled by name, and by nothing when they are ordered by how many songs they have`() {
        val artist = com.lostf1sh.pixelplayeross.data.model.Artist(id = 1, name = "Ñengo Flow", songCount = 40)
        assertThat(artistFastScrollLabel(artist, SortOption.ArtistNameAZ)).isEqualTo("N")
        assertThat(artistFastScrollLabel(artist, SortOption.ArtistNumSongsDesc)).isNull()
    }

    @Test
    fun `a position with no row yet, an empty list and a one-row list have no label to invent`() {
        assertThat(songFastScrollLabel(null, SortOption.SongTitleAZ)).isNull()
        assertThat(albumFastScrollLabel(null, SortOption.AlbumTitleAZ)).isNull()
        assertThat(artistFastScrollLabel(null, SortOption.ArtistNameAZ)).isNull()
        val oneRow = listOf(song(title = "Solo"))
        val label = { index: Int -> songFastScrollLabel(oneRow.getOrNull(index), SortOption.SongTitleAZ) }
        assertThat(label(0)).isEqualTo("S")
        assertThat(label(5)).isNull()
    }
}
