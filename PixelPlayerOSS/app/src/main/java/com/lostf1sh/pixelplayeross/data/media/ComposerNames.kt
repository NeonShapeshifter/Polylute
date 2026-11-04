package com.lostf1sh.pixelplayeross.data.media

import com.lostf1sh.pixelplayeross.data.database.ComposerCountRow
import com.lostf1sh.pixelplayeross.data.model.GenreNames
import com.lostf1sh.pixelplayeross.utils.splitArtistsByDelimiters

/** A composer as search lists it: a name (one part of a COMPOSER tag), the songs that credit them, and the tag values that name them. */
data class ComposerResult(val name: String, val songCount: Int, val rawValues: Set<String>)

/**
 * B56 phase 6: composers in search. A COMPOSER tag can name several people ("Beethoven; Mozart"): it is split with the same delimiters the artists use, a part matches a query when
 * it contains it (case, accents and punctuation ignored), and a composer is a part, counted over every tag value that has it. Composers are not artists.
 */
object ComposerNames {
    /** The people one COMPOSER tag value names. */
    fun parts(raw: String, delimiters: List<String>): List<String> = raw.splitArtistsByDelimiters(delimiters).map { it.trim() }.filter { it.isNotEmpty() }

    private fun fold(text: String) = GenreNames.key(text)

    /** The composers among [rows] (tag values with their song counts) whose name contains [query]; the most songs first, then by name. */
    fun matching(rows: List<ComposerCountRow>, delimiters: List<String>, query: String): List<ComposerResult> {
        val needle = fold(query)
        if (needle.isEmpty()) return emptyList()
        val byName = LinkedHashMap<String, Triple<String, Int, MutableSet<String>>>()
        for (row in rows) {
            for (part in parts(row.composer, delimiters)) {
                val key = fold(part)
                if (!key.contains(needle)) continue
                val entry = byName.getOrPut(key) { Triple(part, 0, LinkedHashSet()) }
                byName[key] = Triple(entry.first, entry.second + row.songCount, entry.third.also { it += row.composer })
            }
        }
        return byName.values.map { (name, songs, raws) -> ComposerResult(name, songs, raws) }
            .sortedWith(compareByDescending<ComposerResult> { it.songCount }.thenBy { it.name.lowercase() })
    }

    /** The tag values that name exactly [name] (one of their parts): what a query for this composer's songs matches. */
    fun valuesFor(rows: List<ComposerCountRow>, delimiters: List<String>, name: String): Set<String> {
        val key = fold(name)
        return rows.filter { row -> parts(row.composer, delimiters).any { fold(it) == key } }.map { it.composer }.toCollection(LinkedHashSet())
    }
}
