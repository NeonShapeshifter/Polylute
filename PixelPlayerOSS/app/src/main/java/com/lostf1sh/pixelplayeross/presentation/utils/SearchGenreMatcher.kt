package com.lostf1sh.pixelplayeross.presentation.utils

import com.lostf1sh.pixelplayeross.data.model.Genre
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.presentation.viewmodel.GenreAlbum
import com.lostf1sh.pixelplayeross.presentation.viewmodel.GenreArtist
import com.lostf1sh.pixelplayeross.presentation.viewmodel.GenreBrowse
import com.lostf1sh.pixelplayeross.presentation.viewmodel.SourceOrder
import java.text.Normalizer
import java.util.Locale

/**
 * Which genres a search query names (B33-U5), for the folded "Genre · X" section at the foot of the search results.
 *
 * It runs on every settled query and so touches **only the genre list already in memory** (the names the Genre browser resolves, which
 * already split a multi-genre tag into its genres, B29-L6): no database, no repository. The ordinary search is not consulted and not changed.
 *
 * Matching is accent- and case-insensitive ("bachata", "Bachata", "BACHATA" and "Bachatá" are the same query), and ranked by the rule the
 * search results already use ([SearchRelevance]: the name itself, then a name that starts with it, then a word that does, then one that
 * merely contains it). A query shorter than [MIN_QUERY_LENGTH] only matches a genre that is exactly that, so typing "r" does not offer every
 * genre with an R. The best [MAX_SECTIONS] come back, best first.
 */
internal object SearchGenreMatcher {

    const val MAX_SECTIONS = 3
    const val MIN_QUERY_LENGTH = 3

    private val COMBINING_MARKS = Regex("\\p{Mn}+")
    private val WHITESPACE = Regex("\\s+")

    /** The text as the matcher compares it: no accents, lower case, single spaces. */
    fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
            .lowercase(Locale.ROOT)
            .replace(WHITESPACE, " ")
            .trim()

    fun match(query: String, genres: List<Genre>, limit: Int = MAX_SECTIONS): List<Genre> {
        val needle = fold(query)
        if (needle.isEmpty() || limit <= 0) return emptyList()
        return genres
            .mapNotNull { genre ->
                val name = fold(genre.name)
                val rank = SearchRelevance.rank(name, needle)
                if (rank == SearchRelevance.NO_MATCH) return@mapNotNull null
                if (rank != SearchRelevance.EXACT && needle.length < MIN_QUERY_LENGTH) return@mapNotNull null
                Ranked(genre, rank, name)
            }
            .sortedWith(compareBy<Ranked> { it.rank }.thenBy { it.name.length }.thenBy { it.name }.thenBy { it.genre.id })
            .take(limit)
            .map { it.genre }
    }

    private class Ranked(val genre: Genre, val rank: Int, val name: String)
}

/**
 * What the unfolded section shows for one genre (B33-U5): a few songs, albums and artists, each group with how many there are in all.
 * [songs] is the genre's whole list in the genre page's order ([SourceOrder.genrePage]): a row plays from its own place in it, and "See all"
 * opens that page, so what is shown is the head of what plays.
 */
internal data class GenreSectionContent(
    val songs: List<Song>,
    val albums: List<GenreAlbum>,
    val artists: List<GenreArtist>,
) {
    val songRows: List<Song> get() = songs.take(ROWS)
    val albumRows: List<GenreAlbum> get() = albums.take(ROWS)
    val artistRows: List<GenreArtist> get() = artists.take(ROWS)

    companion object {
        /** How many rows each group shows. */
        const val ROWS = 3

        /** Builds the content from what the library returned for the genre: one row per library item, albums and artists by how many songs they hold. */
        fun from(rawSongs: List<Song>): GenreSectionContent {
            val files = GenreBrowse.distinctFiles(rawSongs)
            return GenreSectionContent(
                songs = SourceOrder.genrePage(files),
                albums = GenreBrowse.albums(files).sortedWith(compareByDescending<GenreAlbum> { it.songCount }.thenBy { it.title.lowercase() }.thenBy { it.albumId }),
                artists = GenreBrowse.artists(files).sortedWith(compareByDescending<GenreArtist> { it.songCount }.thenBy { it.name.lowercase() }.thenBy { it.artistId }),
            )
        }
    }
}
