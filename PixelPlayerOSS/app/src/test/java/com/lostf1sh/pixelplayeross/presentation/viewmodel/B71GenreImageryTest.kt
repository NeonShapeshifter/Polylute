package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.Song
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.Test

class B71GenreImageryTest {
    private fun tracks(album: Long, name: String, count: Int = 1, cover: String? = "$album.png"): List<Song> =
        (1..count).map {
            Song.emptySong().copy(id = "$album-$it", album = name, albumId = album, albumArtUriString = cover,
                artist = name, artistId = album, genre = "Rock")
        }

    @Test fun coversRankByTimeThenFallbackAndAlphabeticallySkippingAlbumsWithoutArt() = runTest {
        val songs = tracks(1, "Cedar", 3) + tracks(2, "Amber", 2) + tracks(3, "Birch", 2) +
            tracks(4, "Elm") + tracks(5, "Dune", 4) + tracks(6, "Ash", cover = null) + tracks(7, "Fir")
        val time = mapOf("4-1" to 800L, "2-1" to 500L, "2-2" to 300L, "6-1" to 900L)
        assertThat(topGenreCovers(songs, { time[it.id] ?: 0L })).containsExactly(
            "2.png", "4.png", "5.png", "1.png", "3.png", "7.png",
        ).inOrder()
        assertThat(topGenreCovers(songs, { 0L })).containsExactly(
            "5.png", "1.png", "2.png", "3.png", "4.png", "7.png",
        ).inOrder()
        assertThat(topGenreCovers(songs, { 0L }) { album ->
            album.artUri.takeUnless { album.albumId == 5L }
        }).doesNotContain("5.png")
    }

    @Test fun candidatesCountOnlyTheirGenreSongsAndKeepFive() {
        val songs = tracks(1, "Cedar", 3) + tracks(2, "Amber", 2) + tracks(3, "Birch", 2) +
            tracks(4, "Elm") + tracks(5, "Dune", 4) + tracks(6, "", 9) + tracks(7, "Fir")
        val time = mapOf("4-1" to 1_200L, "2-1" to 1_200L, "2-2" to 1L)
        assertThat(genreFeaturedCandidates(songs) { time[it.id] ?: 0L }.map(GenreArtist::name))
            .containsExactly("Amber", "Elm", "Dune", "Cedar", "Birch").inOrder()
        assertThat(genreFeaturedCandidates(songs) { 0L }.map(GenreArtist::name))
            .containsExactly("Dune", "Cedar", "Amber", "Birch", "Elm").inOrder()
    }

    @Test fun rotationUsesIsoWeeksIncludingThePreviousWeekYear() {
        val artists = (1L..5L).map { GenreArtist(it, "Artist $it", 1, 1) }
        assertThat(weeklyFeaturedArtist(artists, LocalDate.parse("2021-01-01"))).isEqualTo(artists[3]) // ISO 2020-W53
        assertThat(weeklyFeaturedArtist(artists, LocalDate.parse("2021-01-03"))).isEqualTo(artists[3])
        assertThat(weeklyFeaturedArtist(artists, LocalDate.parse("2021-01-04"))).isEqualTo(artists[1]) // ISO 2021-W01
        assertThat(weeklyFeaturedArtist(artists, LocalDate.parse("2021-01-11"))).isEqualTo(artists[2])
        assertThat(weeklyFeaturedArtist(artists.take(2), LocalDate.parse("2021-01-11"))).isEqualTo(artists[0])
        assertThat(weeklyFeaturedArtist(artists.take(1), LocalDate.parse("2021-01-11"))).isEqualTo(artists[0])
        assertThat(weeklyFeaturedArtist(emptyList(), LocalDate.parse("2021-01-11"))).isNull()
    }
}
