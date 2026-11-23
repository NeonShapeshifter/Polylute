package com.lostf1sh.pixelplayeross.presentation.utils

import com.lostf1sh.pixelplayeross.data.model.SearchFilterType
import com.lostf1sh.pixelplayeross.data.model.SearchResultItem

/**
 * Ordering a search result by how well it answers what was typed.
 *
 * The search itself is deliberately broad: one query matched against the title *or* the artist in
 * every tab, which is what makes "21" find Adele's album and 21 Savage in the same breath. That
 * breadth is useful. What was missing was order — the rows were grouped by type and, inside a type,
 * left in whatever order the queries happened to return, so an exact title match could sit below
 * five rows that merely mentioned the word.
 *
 * The ranks answer one question each: is this *the* thing, does it start with it as a whole word, is it a whole word somewhere in
 * it, does it start with it, does a word in it start with it, or does it merely contain it. A whole word beats part of a word (owner
 * 11-23: "danz" is Danz CM before Danza Kuduro). A match on the thing's own name outranks a match on whoever made it, because you
 * search for a record before you search for its artist. Equal ranks fall back to the alphabetical order the list already had.
 */
object SearchRelevance {

    /** The text is the query. */
    const val EXACT = 0

    /** The text starts with the query as a whole word ("Danz CM" for "danz", "21 Guns" for "21"). */
    const val LEADING_WORD = 1

    /** The query is a whole word later in the text ("Crazy in Love" for "love", "Besame 21 Mucho" for "21"). */
    const val WHOLE_WORD = 2

    /** The text starts with the query, inside its first word ("Danza Kuduro" for "danz"). */
    const val PREFIX = 3

    /** A later word starts with the query ("Bank Account" for "acc"). */
    const val WORD_PREFIX = 4

    /** The query appears somewhere inside the text. */
    const val CONTAINS = 5

    /** No match at all — a row kept only because another field matched. */
    const val NO_MATCH = 6

    /**
     * Ranks a match on the result's *secondary* text (who made it) below every match on the primary
     * text, so that the two scales cannot interleave.
     */
    const val SECONDARY_OFFSET = 7

    private fun Char.isWordChar() = isLetterOrDigit()

    /** Whether [needle] occurs in [haystack] at an index > 0 that starts a word, and (when [wholeWord]) also ends one. */
    private fun occursAtWordStart(haystack: String, needle: String, wholeWord: Boolean): Boolean {
        var from = 1
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) return false
            val startsWord = !haystack[at - 1].isWordChar()
            val end = at + needle.length
            val endsWord = end == haystack.length || !haystack[end].isWordChar()
            if (startsWord && (!wholeWord || endsWord)) return true
            from = at + 1
        }
    }

    /** How well [text] answers [query]. See the rank constants. */
    fun rank(text: String?, query: String): Int {
        val haystack = text?.trim()?.lowercase().orEmpty()
        val needle = query.trim().lowercase()
        if (haystack.isEmpty() || needle.isEmpty()) return NO_MATCH
        return when {
            haystack == needle -> EXACT
            haystack.startsWith(needle) && !haystack[needle.length].isWordChar() -> LEADING_WORD
            occursAtWordStart(haystack, needle, wholeWord = true) -> WHOLE_WORD
            haystack.startsWith(needle) -> PREFIX
            occursAtWordStart(haystack, needle, wholeWord = false) -> WORD_PREFIX
            haystack.contains(needle) -> CONTAINS
            else -> NO_MATCH
        }
    }
}

/** The order of the related results and the tie-break order for best matches. */
val DEFAULT_SECTION_ORDER = listOf(
    SearchFilterType.SONGS,
    SearchFilterType.ALBUMS,
    SearchFilterType.ARTISTS,
    SearchFilterType.COMPOSERS,
    SearchFilterType.PLAYLISTS
)

internal const val BEST_MATCHES_LIMIT = 5

internal data class SearchResultsLayout(
    val best: List<SearchResultItem>,
    val sections: List<Pair<SearchFilterType, List<SearchResultItem>>>
)

internal fun searchResultsLayout(
    results: List<SearchResultItem>,
    query: String,
    selectedFilter: SearchFilterType
): SearchResultsLayout {
    val uniqueResults = results.distinctBy(SearchResultItem::searchRowKey)
    val grouped = uniqueResults.groupBy(SearchResultItem::filterType)
    val best = if (selectedFilter == SearchFilterType.ALL) {
        uniqueResults
            .mapNotNull { item ->
                val rank = SearchRelevance.rank(item.primaryText, query)
                if (rank < SearchRelevance.NO_MATCH) item to rank else null
            }
            .sortedWith(
                compareBy<Pair<SearchResultItem, Int>> { (_, rank) -> rank }
                    .thenBy { (item, _) -> DEFAULT_SECTION_ORDER.indexOf(item.filterType) }
                    .thenBy { (item, _) -> item.primaryText.lowercase() }
            )
            .take(BEST_MATCHES_LIMIT)
            .map { (item, _) -> item }
    } else {
        emptyList()
    }

    val sectionOrder = if (selectedFilter == SearchFilterType.ALL) DEFAULT_SECTION_ORDER else listOf(selectedFilter)
    val featured = best.toSet()
    val sections = sectionOrder.mapNotNull { type ->
        val rows = grouped[type].orEmpty().filterNot { it in featured }
            .sortedWith(compareBy<SearchResultItem> { it.relevanceRank(query) }.thenBy { it.primaryText.lowercase() })
        if (rows.isEmpty()) null else type to rows
    }
    return SearchResultsLayout(best, sections)
}

internal val SearchResultItem.filterType: SearchFilterType
    get() = when (this) {
        is SearchResultItem.SongItem -> SearchFilterType.SONGS
        is SearchResultItem.AlbumItem -> SearchFilterType.ALBUMS
        is SearchResultItem.ArtistItem -> SearchFilterType.ARTISTS
        is SearchResultItem.ComposerItem -> SearchFilterType.COMPOSERS
        is SearchResultItem.PlaylistItem -> SearchFilterType.PLAYLISTS
    }

/** A row keeps its key when it moves between the best matches and its section. */
internal val SearchResultItem.searchRowKey: String
    get() = when (this) {
        is SearchResultItem.SongItem -> "song_${song.id}"
        is SearchResultItem.AlbumItem -> "album_${album.id}"
        is SearchResultItem.ArtistItem -> "artist_${artist.id}"
        is SearchResultItem.ComposerItem -> "composer_$name"
        is SearchResultItem.PlaylistItem -> "playlist_${playlist.id}"
    }

/** Contiguous groups in the search state published by SearchStateHolder. */
fun SearchResultItem.typeOrder(): Int = when (this) {
    is SearchResultItem.SongItem -> 0
    is SearchResultItem.AlbumItem -> 1
    is SearchResultItem.ArtistItem -> 2
    is SearchResultItem.PlaylistItem -> 3
    is SearchResultItem.ComposerItem -> 4
}

/** The text the row leads with — what the user is actually looking for. */
val SearchResultItem.primaryText: String
    get() = when (this) {
        is SearchResultItem.SongItem -> song.title
        is SearchResultItem.AlbumItem -> album.title
        is SearchResultItem.ArtistItem -> artist.name
        is SearchResultItem.PlaylistItem -> playlist.name
        is SearchResultItem.ComposerItem -> name
    }

/** Who made it, when the row says so. */
private val SearchResultItem.secondaryText: String?
    get() = when (this) {
        is SearchResultItem.SongItem -> song.artist
        is SearchResultItem.AlbumItem -> album.artist
        is SearchResultItem.ArtistItem -> null
        is SearchResultItem.PlaylistItem -> null
        is SearchResultItem.ComposerItem -> null
    }

/**
 * How well one result answers [query]: its own name first, then its artist. A miss on both is
 * [SearchRelevance.NO_MATCH], which keeps the row — the query matched some other field the row
 * does not lead with — but puts it last.
 */
fun SearchResultItem.relevanceRank(query: String): Int {
    val primary = SearchRelevance.rank(primaryText, query)
    if (primary != SearchRelevance.NO_MATCH) return primary
    val secondary = SearchRelevance.rank(secondaryText, query)
    return if (secondary == SearchRelevance.NO_MATCH) SearchRelevance.NO_MATCH
    else secondary + SearchRelevance.SECONDARY_OFFSET
}
