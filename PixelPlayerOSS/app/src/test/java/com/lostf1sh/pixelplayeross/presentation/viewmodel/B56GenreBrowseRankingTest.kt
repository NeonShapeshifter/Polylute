package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** B56 phase 5.1: the genre page's top artists and albums are ranked by what the genre holds of them. */
class B56GenreBrowseRankingTest {
    private fun artist(id: Long, name: String, songs: Int, plays: Int = 0) = GenreArtist(id, name, songs, albumCount = 1, plays = plays)
    private fun album(id: Long, title: String, songs: Int) = GenreAlbum(id, title, "Artist", null, null, songs)

    @Test
    fun `top artists have the most songs in the genre, then the most plays, then the name`() {
        val ranked = GenreBrowse.topArtists(
            listOf(artist(1, "Zeta", 5), artist(2, "Beta", 9, plays = 1), artist(3, "Alfa", 9, plays = 40), artist(4, "Gamma", 9, plays = 40), artist(5, "Omega", 1)),
        )

        assertThat(ranked.map { it.name }).containsExactly("Alfa", "Gamma", "Beta", "Zeta", "Omega").inOrder()
    }

    @Test
    fun `the row is capped`() {
        val many = (1..30).map { artist(it.toLong(), "A$it", songs = 30 - it) }

        assertThat(GenreBrowse.topArtists(many, limit = 12)).hasSize(12)
        assertThat(GenreBrowse.topArtists(many).first().name).isEqualTo("A1")
    }

    @Test
    fun `albums come by the number of songs in the genre, then by name`() {
        val ranked = GenreBrowse.albumsRanked(listOf(album(1, "Zulu", 2), album(2, "alfa", 2), album(3, "Mid", 7), album(4, "Solo", 1)))

        assertThat(ranked.map { it.title }).containsExactly("Mid", "alfa", "Zulu", "Solo").inOrder()
    }

    @Test
    fun `plays per artist are the plays of their songs in this genre`() {
        val songs = listOf(
            com.lostf1sh.pixelplayeross.data.model.Song.emptySong().copy(id = "1", artistId = 7, artist = "A", albumId = 1),
            com.lostf1sh.pixelplayeross.data.model.Song.emptySong().copy(id = "2", artistId = 7, artist = "A", albumId = 2),
            com.lostf1sh.pixelplayeross.data.model.Song.emptySong().copy(id = "3", artistId = 8, artist = "B", albumId = 3),
        )

        val artists = GenreBrowse.artists(songs, plays = mapOf("1" to 4, "2" to 6, "3" to 1, "99" to 500))

        assertThat(artists.single { it.artistId == 7L }.plays).isEqualTo(10)
        assertThat(artists.single { it.artistId == 8L }.plays).isEqualTo(1)
    }
}
