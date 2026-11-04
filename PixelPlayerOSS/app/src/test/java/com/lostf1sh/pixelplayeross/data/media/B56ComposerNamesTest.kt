package com.lostf1sh.pixelplayeross.data.media

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.database.ComposerCountRow
import org.junit.jupiter.api.Test

/** B56 phase 6 (Sherlock #36): composers in search. Searching "Beethoven" finds Beethoven. */
class B56ComposerNamesTest {
    private val delimiters = listOf(";", "/", "&")
    private val rows = listOf(
        ComposerCountRow("Ludwig van Beethoven", 14),
        ComposerCountRow("Beethoven; Wolfgang Amadeus Mozart", 3),
        ComposerCountRow("Wolfgang Amadeus Mozart", 9),
        ComposerCountRow("Johann Sebastian Bach", 20),
        ComposerCountRow("Béla Bartók", 2),
    )

    @Test
    fun `a query finds the composers whose name contains it, in any case`() {
        val found = ComposerNames.matching(rows, delimiters, "beethoven")

        assertThat(found.map { it.name }).containsExactly("Ludwig van Beethoven", "Beethoven").inOrder()
    }

    @Test
    fun `a tag that names two people is split like artists are, and each is counted over every tag that has them`() {
        val found = ComposerNames.matching(rows, delimiters, "mozart")

        assertThat(found).hasSize(1)
        assertThat(found.single().name).isEqualTo("Wolfgang Amadeus Mozart")
        assertThat(found.single().songCount).isEqualTo(12)                                  // 9 + 3
        assertThat(found.single().rawValues).containsExactly("Wolfgang Amadeus Mozart", "Beethoven; Wolfgang Amadeus Mozart")
    }

    @Test
    fun `accents and punctuation do not matter, and a blank query finds nothing`() {
        assertThat(ComposerNames.matching(rows, delimiters, "bela bartok").map { it.name }).containsExactly("Béla Bartók")
        assertThat(ComposerNames.matching(rows, delimiters, "  ")).isEmpty()
        assertThat(ComposerNames.matching(rows, delimiters, "xyz")).isEmpty()
    }

    @Test
    fun `the most songs come first, then the name`() {
        val found = ComposerNames.matching(rows, delimiters, "a")

        assertThat(found.first().name).isEqualTo("Johann Sebastian Bach")
        assertThat(found.map { it.songCount }).isInOrder(Comparator.reverseOrder<Int>())
    }

    @Test
    fun `the tag values that name one composer are those with that person as a part`() {
        assertThat(ComposerNames.valuesFor(rows, delimiters, "Wolfgang Amadeus Mozart"))
            .containsExactly("Wolfgang Amadeus Mozart", "Beethoven; Wolfgang Amadeus Mozart")
        assertThat(ComposerNames.valuesFor(rows, delimiters, "Beethoven")).containsExactly("Beethoven; Wolfgang Amadeus Mozart")
        assertThat(ComposerNames.valuesFor(rows, delimiters, "Nobody")).isEmpty()
    }
}
