package com.lostf1sh.pixelplayeross.data.collections.auto

import com.lostf1sh.pixelplayeross.data.stats.CatalogItem

/**
 * The automatic collections: lists the app builds from what you play, in the Playlists list above your own.
 *
 * They are code, not rows: they cannot be deleted or renamed, only hidden, and a build that does not know one simply does not list it.
 * [key] is what is stored (the hidden set, the day's mix); it never changes.
 */
enum class AutoCollectionId(val key: String) {
    DAILY_MIX("daily_mix"),
    ON_REPEAT("on_repeat"),
    REDISCOVER("rediscover"),
    FORGOTTEN_FAVORITES("forgotten_favorites"),
    DEEP_CUTS("deep_cuts"),
    NEW_UNEXPLORED("new_unexplored"),
    NIGHT("night"),
    HIGH_RESOLUTION("high_resolution"),
    NEVER_PLAYED("never_played"),
    A_YEAR_AGO("a_year_ago");

    companion object {
        fun fromKey(key: String?): AutoCollectionId? = entries.firstOrNull { it.key == key }
    }
}

/** Why a collection is empty, so the screen can say the true thing instead of "nothing here". */
enum class EmptyReason {
    /** Nothing has been listened to yet, or none of it can be tied to a track. */
    NO_HISTORY,

    /** The history is shorter than the rule needs: [AutoCollection.needDays] against [AutoCollection.haveDays]. */
    TOO_LITTLE_HISTORY,

    /** There is history and the rule matches nothing in it. */
    NOTHING_MATCHES,

    /** No track is marked as a favourite. */
    NO_FAVORITES,

    /** Neither the library nor anything listened to says which tracks are above CD quality. */
    NO_RESOLUTION_DATA,

    /** Every track of the library has a play. */
    EVERYTHING_PLAYED,

    /** The library has no tracks. */
    EMPTY_LIBRARY,
}

/**
 * One collection as the list and its page show it.
 *
 * [items] is the list in the order it is shown and played, and nothing else is: the list that opens is the list that plays
 * (shuffle only on top). [days] and [days2] are the numbers of the one-line reason ("what you played most in [days] days"); what
 * they mean is the collection's, written next to its rule in [AutoCollectionEngine].
 */
class AutoCollection(
    val id: AutoCollectionId,
    val items: List<CatalogItem>,
    val days: Int = 0,
    val days2: Int = 0,
    val emptyReason: EmptyReason? = null,
    /** For [EmptyReason.TOO_LITTLE_HISTORY]. */
    val needDays: Int = 0,
    val haveDays: Int = 0,
    /** For the high-resolution collection: when the audio facts it learns from begin (ms), and how many of its tracks come from the library's own metadata. */
    val sinceMs: Long? = null,
    val fromLibrary: Int = 0,
) {
    val isEmpty: Boolean get() = items.isEmpty()
}
