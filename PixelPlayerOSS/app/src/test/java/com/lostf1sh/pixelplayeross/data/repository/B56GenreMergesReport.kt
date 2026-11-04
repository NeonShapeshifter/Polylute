package com.lostf1sh.pixelplayeross.data.repository

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.GenreGrouping
import org.junit.jupiter.api.Test

/** B56 phase 5.2 report (after the batch only): the ten biggest merges of the synthetic library's genres, and a few rules the numbers must keep. */
class B56GenreMergesReport {
    @Test
    fun `the ten biggest merges of the synthetic library`() {
        val tags = B56GenreCountProbe.syntheticTags()
        val groups = GenreGrouping.of(tags)

        println("GENRES_MERGES shown | songs | tag values merged into it")
        groups.filter { it.rawValues.size > 1 }
            .sortedByDescending { it.rawValues.size }
            .take(10)
            .forEach { g ->
                val spellings = g.spellings.entries.sortedByDescending { it.value }.joinToString("; ") { "${it.key} (${it.value})" }
                println("GENRES_MERGES ${g.displayName} | ${g.songCount} | ${g.rawValues.size} values: $spellings")
            }

        // rules the numbers rest on
        assertThat(groups.single { it.displayName == "Hip Hop" }.rawValues).containsAtLeast("Hip Hop", "Hip-Hop", "hip hop", "HIP HOP")
        assertThat(groups.single { it.displayName == "R&B" }.rawValues).containsAtLeast("R&B", "R & B", "R and B", "r&b")
        assertThat(groups.map { it.displayName }).contains("R&B/Soul")                       // kept whole
        assertThat(groups.sumOf { it.songCount }).isAtLeast(tags.values.sum())               // a song with two genres counts in both
    }
}
