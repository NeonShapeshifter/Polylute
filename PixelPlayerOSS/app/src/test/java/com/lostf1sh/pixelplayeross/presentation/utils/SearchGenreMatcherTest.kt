package com.lostf1sh.pixelplayeross.presentation.utils

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.ArtistRef
import com.lostf1sh.pixelplayeross.data.model.Genre
import com.lostf1sh.pixelplayeross.data.model.Song
import org.junit.jupiter.api.Test

/**
 * B33-U5: which genres a query names, and what an unfolded genre section holds. Matching runs on the genre list in memory and nothing else
 * (the function takes no repository), is accent- and case-insensitive, ranks like the search results do, and offers at most three.
 */
class SearchGenreMatcherTest {

    private fun genre(name: String) = Genre(id = Genre.idFor(name), name = name)

    private val genres = listOf(
        genre("Bachata"), genre("Hip Hop"), genre("Rock"), genre("Hard Rock"), genre("Rock & Roll"), genre("Punk Rock"), genre("Pop Rock"),
        genre("Salsa"), genre("Música Clásica"), genre("Reggaetón"), genre("Unknown"), genre("R&B"), genre("Ska"),
    )

    private fun names(query: String, list: List<Genre> = genres) = SearchGenreMatcher.match(query, list).map { it.name }

    @Test
    fun `case and accents do not matter`() {
        for (query in listOf("bachata", "Bachata", "BACHATA", "Bachatá", "bachatÁ", "  bachata  ")) {
            assertThat(names(query)).containsExactly("Bachata")
        }
        assertThat(names("musica clasica")).containsExactly("Música Clásica")
        assertThat(names("MÚSICA CLÁSICA")).containsExactly("Música Clásica")
        assertThat(names("reggaeton")).containsExactly("Reggaetón")
    }

    @Test
    fun `fold removes accents, lower-cases and collapses spaces`() {
        assertThat(SearchGenreMatcher.fold("  Bachatá   Dominicana ")).isEqualTo("bachata dominicana")
        assertThat(SearchGenreMatcher.fold("ÑANDÚ")).isEqualTo("nandu")
    }

    @Test
    fun `the best match comes first, as the search ranks it, and no more than three come back`() {
        // "rock": the genre that is exactly that, then those that start with it, then those with a word that does, shorter first
        assertThat(names("rock")).containsExactly("Rock", "Rock & Roll", "Pop Rock").inOrder()
        assertThat(SearchGenreMatcher.match("rock", genres)).hasSize(3)
        assertThat(SearchGenreMatcher.match("rock", genres, limit = 5).map { it.name })
            .containsExactly("Rock", "Rock & Roll", "Pop Rock", "Hard Rock", "Punk Rock").inOrder()
    }

    @Test
    fun `a word inside a name and a name that merely contains the query both match, the word first`() {
        val list = listOf(genre("Afrobeat"), genre("Beat Music"), genre("Big Beat"))
        assertThat(names("beat", list)).containsExactly("Beat Music", "Big Beat", "Afrobeat").inOrder()
    }

    @Test
    fun `a short query only matches a genre that is exactly that`() {
        assertThat(names("r")).isEmpty()
        assertThat(names("ro")).isEmpty()
        assertThat(names("ska")).containsExactly("Ska")
        // two letters that are a whole name
        assertThat(names("pu", listOf(genre("Pu")))).containsExactly("Pu")
    }

    @Test
    fun `a query that names no genre, and a blank one, match nothing`() {
        assertThat(names("zzzz")).isEmpty()
        assertThat(names("")).isEmpty()
        assertThat(names("   ")).isEmpty()
        assertThat(SearchGenreMatcher.match("rock", emptyList())).isEmpty()
        assertThat(SearchGenreMatcher.match("rock", genres, limit = 0)).isEmpty()
    }

    @Test
    fun `ties end on the name and then the id, so the order never depends on the list's`() {
        val a = listOf(genre("Rock Steady"), genre("Rock Alt"), genre("Rock Pop"))
        val expected = names("rock", a)
        repeat(20) { seed -> assertThat(names("rock", a.shuffled(java.util.Random(seed.toLong())))).isEqualTo(expected) }
        assertThat(expected).containsExactly("Rock Alt", "Rock Pop", "Rock Steady").inOrder()
    }

    @Test
    fun `the unknown genre is a genre like the others for the matcher`() {
        assertThat(names("unknown")).containsExactly("Unknown")
    }

    // ── what the unfolded section holds ──────────────────────────────────────────────────────────────

    private fun song(id: Int, title: String, artist: String, artistId: Long, album: String, albumId: Long, item: String = "item-$id") = Song.emptySong().copy(
        id = "$id", libraryItemId = item, title = title, artist = artist, artistId = artistId,
        artists = listOf(ArtistRef(artistId, artist, true)), album = album, albumId = albumId, genre = "Bachata",
    )

    @Test
    fun `the content lists the genre's songs once each in the genre page's order, with albums and artists by how many songs they hold`() {
        val raw = listOf(
            song(1, "Obsesión", "Aventura", 1, "Generation Next", 10),
            song(2, "Amorcito Enfermito", "Aventura", 1, "Generation Next", 10),
            song(3, "Propuesta Indecente", "Romeo Santos", 2, "Golden", 20),
            song(4, "Hilito", "Xtreme", 3, "Chosen Few", 30),
            song(5, "Dile al Amor", "Aventura", 1, "Kings of Bachata", 11),
            song(1, "Obsesión", "Aventura", 1, "Generation Next", 10),
        )
        val content = GenreSectionContent.from(raw)
        assertThat(content.songs.map { it.id }).hasSize(5)
        // the genre page's order: by title
        assertThat(content.songs.map { it.title }).containsExactly("Amorcito Enfermito", "Dile al Amor", "Hilito", "Obsesión", "Propuesta Indecente").inOrder()
        assertThat(content.songs).isEqualTo(com.lostf1sh.pixelplayeross.presentation.viewmodel.SourceOrder.genrePage(content.songs))
        // artists: the one with most songs first
        assertThat(content.artists.map { it.name }).containsExactly("Aventura", "Romeo Santos", "Xtreme").inOrder()
        assertThat(content.artists.first().songCount).isEqualTo(3)
        assertThat(content.albums.first().title).isEqualTo("Generation Next")
        assertThat(content.albums.first().songCount).isEqualTo(2)
    }

    @Test
    fun `each group shows at most three rows and knows how many there are in all`() {
        val raw = (1..9).map { song(it, "Song $it", "Artist $it", it.toLong(), "Album $it", (100 + it).toLong()) }
        val content = GenreSectionContent.from(raw)
        assertThat(content.songRows).hasSize(3)
        assertThat(content.albumRows).hasSize(3)
        assertThat(content.artistRows).hasSize(3)
        assertThat(content.songs).hasSize(9)
        assertThat(content.albums).hasSize(9)
        assertThat(content.artists).hasSize(9)
        assertThat(GenreSectionContent.from(emptyList()).songRows).isEmpty()
    }
}
