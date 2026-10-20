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
 * The ranks answer one question each: is this *the* thing, does it start with it, does a word in it
 * start with it, or does it merely contain it. A match on the thing's own name outranks a match on
 * whoever made it, because you search for a record before you search for its artist. Equal ranks
 * fall back to the alphabetical order the list already had.
 */
object SearchRelevance {

    /** The text is the query. */
    const val EXACT = 0

    /** The text starts with the query. */
    const val PREFIX = 1

    /** A word inside the text starts with the query ("Bank Account" for "account"). */
    const val WORD_PREFIX = 2

    /** The query appears somewhere inside the text. */
    const val CONTAINS = 3

    /** No match at all — a row kept only because another field matched. */
    const val NO_MATCH = 4

    /**
     * Ranks a match on the result's *secondary* text (who made it) below every match on the primary
     * text, so that the two scales cannot interleave.
     */
    const val SECONDARY_OFFSET = 5

    private val WORD_SEPARATOR = Regex("[^\\p{L}\\p{N}]+")

    /** How well [text] answers [query]. See the rank constants. */
    fun rank(text: String?, query: String): Int {
        val haystack = text?.trim()?.lowercase().orEmpty()
        val needle = query.trim().lowercase()
        if (haystack.isEmpty() || needle.isEmpty()) return NO_MATCH
        return when {
            haystack == needle -> EXACT
            haystack.startsWith(needle) -> PREFIX
            haystack.split(WORD_SEPARATOR).any { it.startsWith(needle) } -> WORD_PREFIX
            haystack.contains(needle) -> CONTAINS
            else -> NO_MATCH
        }
    }
}

/** The order the sections read in when nothing distinguishes them: songs, albums, artists, lists. */
val DEFAULT_SECTION_ORDER = listOf(
    SearchFilterType.SONGS,
    SearchFilterType.ALBUMS,
    SearchFilterType.ARTISTS,
    SearchFilterType.PLAYLISTS
)

/**
 * The sections of a search answer, best first.
 *
 * The section holding the strongest match leads, so a query that names an album exactly reads
 * before a pile of songs that merely credit one of its artists — which no fixed order can do, since
 * the same query can be an album for one person and an artist for the next. Sections that tie keep
 * [DEFAULT_SECTION_ORDER]; a section with nothing in it is not listed at all.
 */
fun searchSectionOrder(
    grouped: Map<SearchFilterType, List<SearchResultItem>>,
    query: String
): List<SearchFilterType> = DEFAULT_SECTION_ORDER
    .mapNotNull { filterType ->
        val items = grouped[filterType]
        if (items.isNullOrEmpty()) null
        else filterType to items.minOf { item -> item.relevanceRank(query) }
    }
    .sortedBy { (_, bestRank) -> bestRank }
    .map { (filterType, _) -> filterType }

/**
 * Which block of the list a row belongs to. Rows are grouped by this so that each type is
 * contiguous; the sections themselves are then ordered by [searchSectionOrder], and this same order
 * decides between two sections whose best match is equally good.
 */
fun SearchResultItem.typeOrder(): Int = when (this) {
    is SearchResultItem.SongItem -> 0
    is SearchResultItem.AlbumItem -> 1
    is SearchResultItem.ArtistItem -> 2
    is SearchResultItem.PlaylistItem -> 3
}

/** The text the row leads with — what the user is actually looking for. */
val SearchResultItem.primaryText: String
    get() = when (this) {
        is SearchResultItem.SongItem -> song.title
        is SearchResultItem.AlbumItem -> album.title
        is SearchResultItem.ArtistItem -> artist.name
        is SearchResultItem.PlaylistItem -> playlist.name
    }

/** Who made it, when the row says so. */
private val SearchResultItem.secondaryText: String?
    get() = when (this) {
        is SearchResultItem.SongItem -> song.artist
        is SearchResultItem.AlbumItem -> album.artist
        is SearchResultItem.ArtistItem -> null
        is SearchResultItem.PlaylistItem -> null
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
