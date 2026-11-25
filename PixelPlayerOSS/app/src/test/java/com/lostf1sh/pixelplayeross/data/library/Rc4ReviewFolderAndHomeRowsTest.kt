package com.lostf1sh.pixelplayeross.data.library

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.MusicFolder
import com.lostf1sh.pixelplayeross.data.model.SortOption
import com.lostf1sh.pixelplayeross.data.stats.Kit
import com.lostf1sh.pixelplayeross.presentation.components.newestFirstRowShouldReturnToStart
import kotlinx.collections.immutable.toImmutableList
import org.junit.jupiter.api.Test

/** Reviewer 11-24 (rc.4): the cheap folder-disc check agrees with the real list; the Home rows go back to the start on a new item. */
class Rc4ReviewFolderAndHomeRowsTest {

    private val root = "/storage/emulated/0/Music"

    private fun song(id: Long, title: String, dir: String) =
        Kit.song(id = id, title = title, artist = "Artist $id", album = "Album $id", albumId = id, track = 1)
            .copy(path = "$dir/$title.flac")

    private fun folder(path: String, songs: List<com.lostf1sh.pixelplayeross.data.model.Song> = emptyList(), subs: List<MusicFolder> = emptyList()) =
        MusicFolder(
            path = path,
            name = path.substringAfterLast('/'),
            songs = songs.toImmutableList(),
            subFolders = subs.toImmutableList(),
            musicRootPath = root,
        )

    @Test
    fun `folderDiscHasSongs agrees with folderDiscSongsInOrder for every query`() {
        val cd1 = folder("$root/Album/CD1", listOf(song(1, "Opening", "$root/Album/CD1")))
        val cd2 = folder("$root/Album/CD2", listOf(song(2, "Closing", "$root/Album/CD2")))
        val empty = folder("$root/Album/Scans")
        val onlySubs = folder("$root/Album", subs = listOf(cd1, cd2, empty))
        val loose = folder(root, listOf(song(3, "Árbol", root), song(4, "Bridge", root)), subs = listOf(onlySubs))
        val nothing = folder("$root/Empty", subs = listOf(folder("$root/Empty/Inner")))

        for (f in listOf(onlySubs, loose, nothing, cd1, empty)) {
            for (q in listOf("", "cd2", "arbol", "ÁRBOL", "zzz", "closing", "scans")) {
                assertThat(folderDiscHasSongs(f, q))
                    .isEqualTo(folderDiscSongsInOrder(f, SortOption.FolderNameAZ, q).isNotEmpty())
            }
        }
        assertThat(folderDiscHasSongs(nothing)).isFalse()
        assertThat(folderDiscHasSongs(onlySubs, "cd2")).isTrue()
    }

    @Test
    fun `newest-first row returns to start only when the first item changes and nobody is dragging`() {
        assertThat(newestFirstRowShouldReturnToStart(shownFirstKey = "a", firstKey = "b", dragging = false)).isTrue()
        assertThat(newestFirstRowShouldReturnToStart(shownFirstKey = "a", firstKey = "a", dragging = false)).isFalse()
        assertThat(newestFirstRowShouldReturnToStart(shownFirstKey = "a", firstKey = "b", dragging = true)).isFalse()
        assertThat(newestFirstRowShouldReturnToStart(shownFirstKey = "a", firstKey = null, dragging = false)).isFalse()
        // First composition: nothing shown yet, the row already starts at index 0.
        assertThat(newestFirstRowShouldReturnToStart(shownFirstKey = null, firstKey = "a", dragging = false)).isTrue()
    }
}
