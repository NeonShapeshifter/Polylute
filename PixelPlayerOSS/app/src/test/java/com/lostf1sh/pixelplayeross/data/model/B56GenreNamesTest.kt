package com.lostf1sh.pixelplayeross.data.model

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** B56 phase 5.2: how genre tags are read into genres, inside the app only. */
class B56GenreNamesTest {
    @Test
    fun `a tag value with several genres splits on semicolon slash comma and bar`() {
        assertThat(GenreNames.parts("Pop; Latin")).containsExactly("Pop", "Latin").inOrder()
        assertThat(GenreNames.parts("Hip Hop/Rap")).containsExactly("Hip Hop", "Rap").inOrder()
        assertThat(GenreNames.parts("Rock, Pop")).containsExactly("Rock", "Pop").inOrder()
        assertThat(GenreNames.parts("Jazz|Blues")).containsExactly("Jazz", "Blues").inOrder()
        assertThat(GenreNames.parts(" Rock ;; Pop /  ")).containsExactly("Rock", "Pop").inOrder()
    }

    @Test
    fun `names that contain a separator on purpose stay whole, in any case`() {
        assertThat(GenreNames.parts("R&B/Soul")).containsExactly("R&B/Soul")
        assertThat(GenreNames.parts("r&b/soul; Pop")).containsExactly("r&b/soul", "Pop").inOrder()
        assertThat(GenreNames.parts("Singer/Songwriter, Folk")).containsExactly("Singer/Songwriter", "Folk").inOrder()
    }

    @Test
    fun `a blank value names no genre`() {
        assertThat(GenreNames.parts(null)).isEmpty()
        assertThat(GenreNames.parts("   ")).isEmpty()
    }

    @Test
    fun `spellings that differ only in case spaces hyphens accents or ampersand are one key`() {
        val one = listOf("Hip-Hop", "Hip Hop", "hip hop", "HIP HOP", "HipHop", "hip_hop")
        assertThat(one.map(GenreNames::key).toSet()).hasSize(1)
        assertThat(listOf("R&B", "R & B", "r and b", "R and B").map(GenreNames::key).toSet()).hasSize(1)
        assertThat(listOf("Drum & Bass", "Drum and Bass", "drum n bass").map(GenreNames::key).toSet()).hasSize(2)   // "n" is not "and"
        assertThat(GenreNames.key("Música Latina")).isEqualTo(GenreNames.key("Musica latina"))
        assertThat(GenreNames.key("Rock")).isNotEqualTo(GenreNames.key("Rock Pop"))
    }

    @Test
    fun `the shown name is the most common spelling, and on a tie the first in plain order`() {
        val groups = GenreGrouping.of(mapOf("Hip-Hop" to 3, "Hip Hop" to 10, "hip hop" to 2, "Rock" to 4, "rock" to 4))

        val hipHop = groups.single { it.key == GenreNames.key("Hip Hop") }
        assertThat(hipHop.displayName).isEqualTo("Hip Hop")
        assertThat(hipHop.songCount).isEqualTo(15)
        assertThat(hipHop.rawValues).containsExactly("Hip-Hop", "Hip Hop", "hip hop")
        assertThat(groups.single { it.key == GenreNames.key("Rock") }.displayName).isEqualTo("Rock")        // 4 and 4: "Rock" sorts before "rock"
    }

    @Test
    fun `a song with several genres counts in each, and one that names a genre twice counts once`() {
        val groups = GenreGrouping.of(mapOf("Pop; Latin" to 5, "Pop" to 2, "Rock; rock" to 7))

        assertThat(groups.single { it.displayName == "Pop" }.songCount).isEqualTo(7)
        assertThat(groups.single { it.displayName == "Latin" }.songCount).isEqualTo(5)
        assertThat(groups.single { it.key == GenreNames.key("rock") }.songCount).isEqualTo(7)
    }

    @Test
    fun `no genre tag is the unknown genre, last in the order`() {
        val groups = GenreGrouping.of(mapOf("" to 3, "Zydeco" to 1, "Ambient" to 2, "Unknown" to 1))

        assertThat(groups.map { it.displayName }).containsExactly("Ambient", "Zydeco", "Unknown").inOrder()
        assertThat(groups.last().songCount).isEqualTo(4)
        assertThat(groups.last().id).isEqualTo("unknown")
    }

    @Test
    fun `a route by an old spelling finds the merged genre`() {
        val groups = GenreGrouping.of(mapOf("Hip Hop" to 10, "Hip-Hop" to 3))

        assertThat(GenreGrouping.find(groups, "hip-hop")!!.displayName).isEqualTo("Hip Hop")
        assertThat(GenreGrouping.find(groups, "hip_hop")!!.displayName).isEqualTo("Hip Hop")
        assertThat(GenreGrouping.find(groups, "Jazz")).isNull()
    }

    @Test
    fun `genres with fewer than three songs are the other ones`() {
        assertThat(GenreGrouping.isOther(Genre("a", "A", songCount = 2))).isTrue()
        assertThat(GenreGrouping.isOther(Genre("b", "B", songCount = 3))).isFalse()
    }

    @Test
    fun `a stored image follows the merged genre to its current id and is never moved over another`() {
        val groups = GenreGrouping.of(mapOf("Hip Hop" to 10, "Hip-Hop" to 3, "Rock" to 4, "rock" to 1))

        // the image was chosen on "Hip-Hop" (its id was hip-hop): the genre is Hip Hop now
        assertThat(GenreKeyMigration.plan(groups, setOf("hip-hop"))).containsExactly(GenreKeyMove("hip-hop", "hip_hop"))
        // the current id already has one: nothing moves
        assertThat(GenreKeyMigration.plan(groups, setOf("hip-hop", "hip_hop"))).isEmpty()
        // an image under any spelling whose id is the current id is already there; one under only an older id moves
        assertThat(GenreKeyMigration.plan(GenreGrouping.of(mapOf("Hip-Hop" to 9, "Hip Hop" to 2)), setOf("hip_hop"))).containsExactly(GenreKeyMove("hip_hop", "hip-hop"))
        // an image of a genre that is not in the library is left alone
        assertThat(GenreKeyMigration.plan(groups, setOf("polka"))).isEmpty()
        // nothing to do when the id did not change
        assertThat(GenreKeyMigration.plan(groups, setOf("rock"))).isEmpty()
    }
}
