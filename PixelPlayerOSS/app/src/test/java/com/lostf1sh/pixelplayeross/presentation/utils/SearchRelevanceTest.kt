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
        SearchResultItem.SongItem(Song.emptySong().copy(id = title, title = title, artist = artist))

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
        val leading = SearchRelevance.rank("21 Guns", "21")
        // "21" as a whole word later in the name: where a word begins and ends.
        val word = SearchRelevance.rank("Besame 21 Mucho", "21")
        // And inside a word is the weakest match of the three.
        val contains = SearchRelevance.rank("Twenty2021", "21")
        assertThat(leading).isEqualTo(SearchRelevance.LEADING_WORD)
        assertThat(word).isEqualTo(SearchRelevance.WHOLE_WORD)
        assertThat(contains).isEqualTo(SearchRelevance.CONTAINS)
        assertThat(leading).isLessThan(word)
        assertThat(word).isLessThan(contains)
    }

    @Test
    fun `a word inside the name counts as its own match`() {
        assertThat(SearchRelevance.rank("Bank Account", "account")).isEqualTo(SearchRelevance.WHOLE_WORD)
        assertThat(SearchRelevance.rank("Bank Account", "acc")).isEqualTo(SearchRelevance.WORD_PREFIX)
    }

    @Test
    fun `a whole word beats part of a word - danz is Danz CM before Danza Kuduro (owner 11-23)`() {
        val danzCm = SearchRelevance.rank("Danz CM", "danz")
        val danzaKuduro = SearchRelevance.rank("Danza Kuduro", "danz")
        assertThat(danzCm).isEqualTo(SearchRelevance.LEADING_WORD)
        assertThat(danzaKuduro).isEqualTo(SearchRelevance.PREFIX)
        assertThat(danzCm).isLessThan(danzaKuduro)
        // a whole word later in a name also beats a first word that only starts with the query
        assertThat(SearchRelevance.rank("Crazy in Love", "love")).isLessThan(SearchRelevance.rank("Lovely", "love"))
        // several words typed: the same rule on the whole query
        assertThat(SearchRelevance.rank("Danz CM Live", "danz cm")).isEqualTo(SearchRelevance.LEADING_WORD)
        assertThat(SearchRelevance.rank("The Danz CM Hour", "danz cm")).isEqualTo(SearchRelevance.WHOLE_WORD)
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
        assertThat(rank).isEqualTo(SearchRelevance.SECONDARY_OFFSET + SearchRelevance.LEADING_WORD)
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
        assertThat(artist("21 Savage").relevanceRank("21")).isEqualTo(SearchRelevance.LEADING_WORD)
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
    fun `All puts own names before the songs and album that merely credit the artist`() {
        val namedArtist = artist("Danz Artist")
        val namedSong = song("Danza Song")
        val relatedSongs = listOf("Amber", "Birch", "Cedar", "Dune", "Elm", "Fern").map {
            song(it, "Danz Artist")
        }
        val relatedAlbum = album("Northern Lights", "Danz Artist")
        val layout = searchResultsLayout(
            relatedSongs.reversed() + listOf(namedSong, relatedAlbum, namedArtist, namedSong, namedArtist),
            "danz",
            SearchFilterType.ALL
        )

        assertThat(layout.best).containsExactly(namedArtist, namedSong).inOrder()
        assertThat(layout.sections).containsExactly(
            SearchFilterType.SONGS to relatedSongs,
            SearchFilterType.ALBUMS to listOf(relatedAlbum)
        ).inOrder()
        val rows = layout.best + layout.sections.flatMap { it.second }
        assertThat(rows.map { it.searchRowKey }).containsNoDuplicates()
    }

    @Test
    fun `the five best use rank then type then title and the overflow leads its sections`() {
        val exactPlaylist = playlist("Wave")
        val firstSong = song("Wave Alpha")
        val secondSong = song("Wave Beta")
        val titleAlbum = album("Wave Album")
        val titleArtist = artist("Wave Artist")
        val titleComposer = SearchResultItem.ComposerItem("Wave Composer", 2)
        val overflowSong = song("A Wave Ago")
        val relatedSong = song("Harbor", "Wave Artist")
        val layout = searchResultsLayout(
            listOf(relatedSong, titleArtist, secondSong, exactPlaylist, titleComposer, titleAlbum, overflowSong, firstSong),
            "wave",
            SearchFilterType.ALL
        )

        assertThat(layout.best).containsExactly(exactPlaylist, firstSong, secondSong, titleAlbum, titleArtist).inOrder()
        assertThat(layout.sections).containsExactly(
            SearchFilterType.SONGS to listOf(overflowSong, relatedSong),
            SearchFilterType.COMPOSERS to listOf(titleComposer)
        ).inOrder()
    }

    @Test
    fun `without an own name match the nonempty sections keep their fixed order`() {
        val relatedSong = song("Harbor", "Wave Artist")
        val relatedAlbum = album("Seascapes", "Wave Artist")
        val relatedArtist = artist("Someone")
        val relatedComposer = SearchResultItem.ComposerItem("Composer", 2)
        val relatedPlaylist = playlist("Mix")
        val layout = searchResultsLayout(
            listOf(relatedPlaylist, relatedComposer, relatedArtist, relatedAlbum, relatedSong),
            "wave",
            SearchFilterType.ALL
        )

        assertThat(layout.best).isEmpty()
        assertThat(layout.sections).containsExactly(
            SearchFilterType.SONGS to listOf(relatedSong),
            SearchFilterType.ALBUMS to listOf(relatedAlbum),
            SearchFilterType.ARTISTS to listOf(relatedArtist),
            SearchFilterType.COMPOSERS to listOf(relatedComposer),
            SearchFilterType.PLAYLISTS to listOf(relatedPlaylist)
        ).inOrder()
    }

    @Test
    fun `a single filter keeps that type sorted by relevance and title without a best block`() {
        val exact = song("Wave")
        val first = song("Wave Alpha")
        val second = song("Wave Beta")
        val layout = searchResultsLayout(
            listOf(second, artist("Wave Artist"), first, exact),
            "wave",
            SearchFilterType.SONGS
        )

        assertThat(layout.best).isEmpty()
        assertThat(layout.sections).containsExactly(SearchFilterType.SONGS to listOf(exact, first, second))
    }
}
