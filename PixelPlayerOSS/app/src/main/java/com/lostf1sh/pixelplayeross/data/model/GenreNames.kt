package com.lostf1sh.pixelplayeross.data.model

import java.text.Normalizer
import java.util.Locale

/**
 * B56 phase 5.2: how the genre tags of a library are read into genres. Inside the app only: no file is written, no tag is changed.
 *
 *  - A tag value with several genres is split on `;` `/` `,` and `|`, and the song counts in each part ("Pop; Latin" is Pop and Latin; "Hip Hop/Rap" is Hip Hop and Rap).
 *    Names that contain one of those characters on purpose stay whole ([Exceptions]).
 *  - Spellings that differ only in case, accents, spaces, hyphens, other punctuation or `&` against `and` are one genre ([key]): "Hip-Hop", "Hip Hop", "hip hop" and "HipHop";
 *    "R&B", "R & B" and "R and B"; "Drum & Bass" and "Drum and Bass".
 *  - The name shown is the spelling most songs carry ([GenreGroup.displayName]); on a tie the first in plain string order, so it never depends on the order rows come in.
 */
object GenreNames {
    /** Genre names that contain a separator on purpose. They are protected before the split and match case-insensitively. */
    val Exceptions: List<String> = listOf("R&B/Soul", "Singer/Songwriter")

    private val separators = Regex("[;/,|]")
    private val nonAlphanumeric = Regex("[^\\p{L}\\p{N}]+")
    private val combiningMarks = Regex("\\p{M}+")

    /** The genres a tag value names, each as the tag spells it (trimmed); nothing for a blank value. */
    fun parts(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        var text = raw
        val kept = ArrayList<String>()
        for (name in Exceptions) {
            val pattern = Regex(Regex.escape(name), RegexOption.IGNORE_CASE)
            text = pattern.replace(text!!) { match -> kept += match.value; "\u0000${kept.size - 1}\u0000" }
        }
        return text!!.split(separators)
            .map { part ->
                var restored = part
                kept.forEachIndexed { index, original -> restored = restored.replace("\u0000$index\u0000", original) }
                restored.trim()
            }
            .filter { it.isNotEmpty() }
    }

    /** What makes two spellings one genre: lower case without accents, `&` as "and", and no spaces or punctuation at all. */
    fun key(spelling: String): String {
        val folded = Normalizer.normalize(spelling.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(combiningMarks, "")
        return folded.replace("&", " and ").replace(nonAlphanumeric, "")
    }

    /** The key of the unknown genre: songs with no genre tag. */
    val UnknownKey: String = key(Genre.UNKNOWN_NAME)
}

/** One genre as the library reads it: every spelling and every tag value that makes it up. */
data class GenreGroup(
    val key: String,
    /** The spelling most songs carry. */
    val displayName: String,
    /** Songs that name this genre, a song with several genres counted in each. */
    val songCount: Int,
    /** Every spelling and how many songs carry it. */
    val spellings: Map<String, Int>,
    /** The tag values (as stored) that name this genre: what a query for its songs matches. */
    val rawValues: Set<String>,
) {
    /** The id the genre has: the one [Genre.idFor] gives its shown name. */
    val id: String get() = Genre.idFor(displayName)

    /** The ids every spelling of it ever had: where a stored image or setting may be found under an older key. */
    val legacyIds: Set<String> get() = spellings.keys.map(Genre::idFor).toSet()
}

object GenreGrouping {
    /**
     * Groups [tagValues] (a tag value as stored, and how many songs carry it) into genres. A blank value is the unknown genre; an empty [tagValues] is no genres.
     * Sorted by shown name, the unknown genre last.
     */
    fun of(tagValues: Map<String, Int>): List<GenreGroup> {
        class Acc { val spellings = HashMap<String, Int>(); val raw = LinkedHashSet<String>(); var songs = 0 }
        val byKey = LinkedHashMap<String, Acc>()
        for ((raw, count) in tagValues) {
            val parts = GenreNames.parts(raw).ifEmpty { listOf(Genre.UNKNOWN_NAME) }
            val seenKeys = HashSet<String>()
            for (part in parts) {
                val key = GenreNames.key(part)
                if (key.isEmpty()) continue
                val acc = byKey.getOrPut(key) { Acc() }
                acc.spellings.merge(part, count, Int::plus)
                // a song that names the same genre twice ("Rock; rock") counts once
                if (seenKeys.add(key)) { acc.songs += count; acc.raw += raw }
            }
        }
        return byKey.map { (key, acc) ->
            val display = acc.spellings.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).first().key
            GenreGroup(key = key, displayName = display, songCount = acc.songs, spellings = acc.spellings.toMap(), rawValues = acc.raw)
        }.sortedWith(compareBy<GenreGroup> { it.key == GenreNames.UnknownKey }.thenBy { it.displayName.lowercase(Locale.ROOT) }.thenBy { it.displayName })
    }

    /** The group a genre route or a stats row names (by its id, its name, or any spelling), or null. */
    fun find(groups: List<GenreGroup>, idOrName: String): GenreGroup? {
        val key = GenreNames.key(idOrName)
        return groups.firstOrNull { it.key == key }
    }

    /** The genres with fewer songs than this go under "Other" at the end of the Genres tab. */
    const val OTHER_BELOW = 3

    fun isOther(genre: Genre): Boolean = genre.songCount < OTHER_BELOW
}

/** Where a stored per-genre image moves when the genre's id changes by merging. */
data class GenreKeyMove(val from: String, val to: String)

object GenreKeyMigration {
    /**
     * The moves that make every genre's stored image sit under its current id. [stored] are the keys that have an image; the current id's own image is never replaced, and
     * one older key is chosen when several have one (the one with the most songs, then the first in plain order), the others left where they are.
     */
    fun plan(groups: List<GenreGroup>, stored: Set<String>): List<GenreKeyMove> = groups.mapNotNull { group ->
        val current = group.id
        if (current in stored) return@mapNotNull null
        val source = group.legacyIds.filter { it in stored }.sorted()
            .maxByOrNull { id -> group.spellings.filterKeys { Genre.idFor(it) == id }.values.sum() }
            ?: return@mapNotNull null
        GenreKeyMove(from = source, to = current)
    }
}
