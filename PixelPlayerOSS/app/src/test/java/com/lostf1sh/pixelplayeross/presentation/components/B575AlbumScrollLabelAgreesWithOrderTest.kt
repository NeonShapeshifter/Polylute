package com.lostf1sh.pixelplayeross.presentation.components

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.lostf1sh.pixelplayeross.data.database.AlbumEntity
import com.lostf1sh.pixelplayeross.data.database.ArtistEntity
import com.lostf1sh.pixelplayeross.data.database.PixelPlayerDatabase
import com.lostf1sh.pixelplayeross.data.database.toAlbum
import com.lostf1sh.pixelplayeross.data.database.toEntity
import com.lostf1sh.pixelplayeross.data.model.SortOption
import com.lostf1sh.pixelplayeross.data.stats.Kit
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B57.5 1. The A-Z strip is gone and Albums scrolls with the scroll bar's floating letter again. The list keeps `COLLATE UNICODE` ("Á" sorts with "A"), so the letter the bar shows has to be the
 * one the list puts the row under: run through the **real list query** (Room, the same SQLite collation), the labels along the list must form one run per letter, in the list's order.
 *
 * Before B57.5 the label folded accents (NFD) but not the letters the collation also files under a base letter ("Ø" with "O", "ß" with "S", "Æ" with "A", "Œ" with "O"), and the album-artist
 * sort labelled by the track artist while the list sorts by the album artist.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class B575AlbumScrollLabelAgreesWithOrderTest {

    /** Each case is (a letter the owner's library can have as an initial, the letter the bar must show for it). */
    private val folded = listOf(
        "Á" to "A", "À" to "A", "Ä" to "A", "Å" to "A", "Æ" to "A", "Ç" to "C", "Č" to "C", "É" to "E", "Ë" to "E", "Í" to "I", "Ñ" to "N",
        "Ó" to "O", "Ö" to "O", "Ø" to "O", "Œ" to "O", "Ú" to "U", "Ü" to "U", "Ý" to "Y", "ß" to "S", "Š" to "S", "Ž" to "Z",
        // filed with their base letter by the collation, though Unicode does not decompose them (stroke letters) or only in the compatibility form (ligatures)
        "Ł" to "L", "Đ" to "D", "Ð" to "D", "Ħ" to "H", "Ĳ" to "I", "Ǳ" to "D",
    )

    private fun titles(): List<String> {
        val out = mutableListOf<String>()
        // around each folded initial: the base letter before and after it, so a label that leaves its run shows up as a second run of that letter
        for ((c, base) in folded) {
            out += "${base}a $c"; out += "${c}m"; out += "${base}z $c"
        }
        out += listOf("[2023] Album One", "1999", "_x", "#hash", "Zebra", "Bruma", "Nada", "Orbit", "Sol", "Ωmega", "Ябло", "Мир")
        return out.distinct()
    }

    private fun <T> withAlbums(titles: List<String>, albumArtists: List<String?> = titles.map { null }, artists: List<String> = titles.map { "Artist" }, block: suspend (List<AlbumEntity>, com.lostf1sh.pixelplayeross.data.database.MusicDao) -> T): T = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PixelPlayerDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = db.musicDao()
            dao.insertArtists(listOf(ArtistEntity(1, "Artist", titles.size)))
            val albums = titles.mapIndexed { i, t -> AlbumEntity((i + 1).toLong(), t, artists[i], 1, null, 1, i.toLong(), 2020, albumArtists[i]) }
            dao.insertAlbums(albums)
            dao.insertSongs(titles.indices.map { i -> Kit.song(i + 1L).copy(artistId = 1, albumId = i + 1L, libraryItemId = "p-$i").toEntity("/m/$i.flac", "/m") })
            block(albums, dao)
        } finally {
            db.close()
        }
    }

    private fun runs(labels: List<String?>): List<String?> = labels.fold(mutableListOf<String?>()) { acc, l -> if (acc.lastOrNull() != l || acc.isEmpty()) acc.add(l); acc }

    private fun assertOneRunPerLetter(order: List<String>, labels: List<String?>, descending: Boolean) {
        val r = runs(labels)
        assertWithMessage("a letter came back after another one began: runs $r over $order").that(r).containsNoDuplicates()
        val latin = r.filterNotNull().filter { it.length == 1 && it[0] in 'A'..'Z' }
        assertWithMessage("A to Z must run in the list's order: $r").that(latin).isInOrder(if (descending) compareByDescending<String> { it } else compareBy<String> { it })
        // "#" is where the symbols and digits are: before A going up, after A going down; other scripts are after Z going up
        val hash = r.indexOf("#")
        val firstLatin = r.indexOfFirst { it != null && it != "#" && it[0] in 'A'..'Z' }
        val lastLatin = r.indexOfLast { it != null && it != "#" && it[0] in 'A'..'Z' }
        val others = r.withIndex().filter { (_, l) -> l != null && l != "#" && l[0] !in 'A'..'Z' }.map { it.index }
        if (!descending) {
            if (hash >= 0) assertThat(hash).isEqualTo(0)
            if (others.isNotEmpty()) assertThat(others.min()).isGreaterThan(lastLatin)
        } else {
            if (hash >= 0) assertThat(hash).isEqualTo(r.lastIndex)
            if (others.isNotEmpty()) assertThat(others.max()).isLessThan(firstLatin)
        }
    }

    @Test
    fun `the floating letter along the Albums list by title is one run per letter in the list order`() = withAlbums(titles()) { albums, dao ->
        for ((sort, descending) in listOf(SortOption.AlbumTitleAZ to false, SortOption.AlbumTitleZA to true)) {
            val page = dao.getAlbumsPage(emptyList(), false, 0, sort.storageKey, 1, 1000, 0)
            val labels = page.map { albumFastScrollLabel(it.toAlbum(), sort) }
            assertOneRunPerLetter(page.map { it.title }, labels, descending)
        }
    }

    @Test
    fun `an accented or special initial shows the letter it is filed under`() = withAlbums(titles()) { _, dao ->
        val page = dao.getAlbumsPage(emptyList(), false, 0, SortOption.AlbumTitleAZ.storageKey, 1, 1000, 0)
        for ((c, base) in folded) {
            val title = "${c}m"
            val row = page.first { it.title == title }
            assertWithMessage("$title in the list").that(albumFastScrollLabel(row.toAlbum(), SortOption.AlbumTitleAZ)).isEqualTo(base)
        }
        // and the list itself files the accented initial inside its letter, between that letter's own albums
        val order = page.map { it.title }
        assertThat(order.indexOf("Áa Á")).isLessThan(order.indexOf("Ám"))
        assertThat(order.indexOf("Ám")).isLessThan(order.indexOf("Az Á"))
    }

    @Test
    fun `the album artist sort labels by the album artist the list sorts by, not by the track artist`() {
        val titles = listOf("One", "Two", "Three", "Four")
        val albumArtists = listOf("Various Artists", null, "  ", "Álvaro Soler")
        val artists = listOf("Zed", "Mike", "Quentin", "Beto")
        withAlbums(titles, albumArtists, artists) { _, dao ->
            for ((sort, descending) in listOf(SortOption.AlbumArtist to false, SortOption.AlbumArtistDesc to true)) {
                val page = dao.getAlbumsPage(emptyList(), false, 0, sort.storageKey, 1, 100, 0)
                val labels = page.map { albumFastScrollLabel(it.toAlbum(), sort) }
                val expected = page.map { (it.albumArtist?.trim()?.takeIf { a -> a.isNotEmpty() } ?: it.artistName).first().let { ch -> if (ch == 'Á') "A" else ch.uppercase() } }
                assertWithMessage("sort ${sort.storageKey}, rows ${page.map { it.title }}").that(labels).isEqualTo(expected)
                assertOneRunPerLetter(page.map { it.title }, labels, descending)
            }
        }
    }

}
