package com.lostf1sh.pixelplayeross.presentation.utils

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.Album
import com.lostf1sh.pixelplayeross.data.model.Artist
import com.lostf1sh.pixelplayeross.data.model.Playlist
import com.lostf1sh.pixelplayeross.data.model.SearchFilterType
import com.lostf1sh.pixelplayeross.data.model.SearchResultItem
import com.lostf1sh.pixelplayeross.data.model.Song
import org.junit.jupiter.api.Test

/**
 * The order a search answers in.
 *
 * The search is broad on purpose — one query against the title *or* the artist — so the ranking is
 * what decides whether the thing you typed is the first row or the sixth. These pin that.
 */
class SearchRelevanceTest {

    private fun song(title: String, artist: String = "Someone") =
        SearchResultItem.SongItem(Song.emptySong().copy(title = title, artist = artist))

    private fun album(title: String, artist: String = "Someone") =
        SearchResultItem.AlbumItem(Album.empty().copy(title = title, artist = artist))

    private fun artist(name: String) =
        SearchResultItem.ArtistItem(Artist.empty().copy(name = name))

    private fun playlist(name: String) =
        SearchResultItem.PlaylistItem(Playlist(id = "p1", name = name, songIds = emptyList()))

    @Test
    fun `the exact name is the best answer`() {
        assertThat(SearchRelevance.rank("21", "21")).isEqualTo(SearchRelevance.EXACT)
        assertThat(SearchRelevance.rank("21", "21"))
            .isLessThan(SearchRelevance.rank("21 Guns", "21"))
    }

    @Test
    fun `a name that starts with the query beats one that merely contains it`() {
        val prefix = SearchRelevance.rank("21 Guns", "21")
        // "21" as a whole word is a word match, not a mere substring: it is where a word begins.
        val word = SearchRelevance.rank("Besame 21 Mucho", "21")
        // And inside a word is the weakest match of the three.
        val contains = SearchRelevance.rank("Twenty2021", "21")
        assertThat(prefix).isEqualTo(SearchRelevance.PREFIX)
        assertThat(word).isEqualTo(SearchRelevance.WORD_PREFIX)
        assertThat(contains).isEqualTo(SearchRelevance.CONTAINS)
        assertThat(prefix).isLessThan(word)
        assertThat(word).isLessThan(contains)
    }

    @Test
    fun `a word inside the name counts as its own match`() {
        assertThat(SearchRelevance.rank("Bank Account", "account"))
            .isEqualTo(SearchRelevance.WORD_PREFIX)
    }

    @Test
    fun `the name outranks the artist`() {
        val byTitle = song("21 Guns", "Green Day")
        val byArtist = song("Bank Account", "21 Savage")
        assertThat(byTitle.relevanceRank("21")).isLessThan(byArtist.relevanceRank("21"))
    }

    @Test
    fun `a row that matches nothing in its own name still ranks last, not out`() {
        val rank = song("Creepin'", "21 Savage").relevanceRank("21")
        assertThat(rank).isEqualTo(SearchRelevance.SECONDARY_OFFSET + SearchRelevance.PREFIX)
        assertThat(rank).isGreaterThan(SearchRelevance.CONTAINS)
    }

    @Test
    fun `matching ignores case and surrounding spaces`() {
        assertThat(SearchRelevance.rank("Zoe", "zoe")).isEqualTo(SearchRelevance.EXACT)
        assertThat(SearchRelevance.rank("Zoe", " ZOE ")).isEqualTo(SearchRelevance.EXACT)
    }

    @Test
    fun `albums, artists and playlists rank by their own name`() {
        assertThat(album("21", "Adele").relevanceRank("21")).isEqualTo(SearchRelevance.EXACT)
        assertThat(artist("21 Savage").relevanceRank("21")).isEqualTo(SearchRelevance.PREFIX)
        assertThat(playlist("21").relevanceRank("21")).isEqualTo(SearchRelevance.EXACT)
    }

    @Test
    fun `the sections keep their order, and relevance sorts inside them`() {
        val results = listOf(
            song("Bank Account", "21 Savage"),
            artist("21 Savage"),
            album("21", "Adele"),
            song("21 Guns", "Green Day"),
            song("21", "Someone"),
        )
        val sorted = results.sortedWith(
            compareBy<SearchResultItem> { it.typeOrder() }
                .thenBy { it.relevanceRank("21") }
                .thenBy { it.primaryText.lowercase() }
        )
        assertThat(sorted.map { it.primaryText })
            .containsExactly("21", "21 Guns", "Bank Account", "21", "21 Savage")
            .inOrder()
    }

    @Test
    fun `the section holding the best match reads first`() {
        // Searching "21": the album is named exactly that, the artist starts with it, and the songs
        // only credit him. The album should not be below twenty-three songs.
        val grouped = mapOf(
            SearchFilterType.SONGS to listOf(
                song("Creepin'", "21 Savage"),
                song("Bank Account", "21 Savage"),
            ),
            SearchFilterType.ARTISTS to listOf(artist("21 Savage")),
            SearchFilterType.ALBUMS to listOf(album("21", "Adele")),
        )
        assertThat(searchSectionOrder(grouped, "21"))
            .containsExactly(
                SearchFilterType.ALBUMS,
                SearchFilterType.ARTISTS,
                SearchFilterType.SONGS,
            )
            .inOrder()
    }

    @Test
    fun `sections whose best match is equally good keep their usual order`() {
        val grouped = mapOf(
            SearchFilterType.ARTISTS to listOf(artist("21 Savage")),
            SearchFilterType.SONGS to listOf(song("21 Guns", "Green Day")),
        )
        assertThat(searchSectionOrder(grouped, "21"))
            .containsExactly(SearchFilterType.SONGS, SearchFilterType.ARTISTS)
            .inOrder()
    }

    @Test
    fun `an exact song is not buried under an album that merely mentions it`() {
        val grouped = mapOf(
            SearchFilterType.ALBUMS to listOf(album("Live at 21", "Someone")),
            SearchFilterType.SONGS to listOf(song("21", "Someone")),
        )
        assertThat(searchSectionOrder(grouped, "21"))
            .containsExactly(SearchFilterType.SONGS, SearchFilterType.ALBUMS)
            .inOrder()
    }

    @Test
    fun `a section with nothing in it is not listed at all`() {
        val grouped = mapOf(SearchFilterType.PLAYLISTS to listOf(playlist("21")))
        assertThat(searchSectionOrder(grouped, "21"))
            .containsExactly(SearchFilterType.PLAYLISTS)
    }
}
