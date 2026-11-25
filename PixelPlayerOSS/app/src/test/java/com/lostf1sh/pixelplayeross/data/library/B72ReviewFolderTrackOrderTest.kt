package com.lostf1sh.pixelplayeross.data.library

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.MusicFolder
import com.lostf1sh.pixelplayeross.data.model.SortOption
import com.lostf1sh.pixelplayeross.data.stats.Kit
import kotlinx.collections.immutable.toImmutableList
import org.junit.jupiter.api.Test

/** Reviewer 11-24 (rc.4): songs inside a folder follow disc and track number, then title. */
class B72ReviewFolderTrackOrderTest {

    private val root = "/storage/emulated/0/Music"

    private fun song(id: Long, title: String, track: Int, disc: Int? = null, album: String = "Same Album") =
        Kit.song(id = id, title = title, album = album, track = track).copy(discNumber = disc)

    @Test
    fun `an album folder plays in track order, not title order`() {
        val songs = listOf(song(1, "Zebra", 1), song(2, "Apple", 3), song(3, "Mango", 2))

        assertThat(sortSongsForFolderView(songs, SortOption.FolderNameAZ).map { it.title })
            .containsExactly("Zebra", "Mango", "Apple").inOrder()
        assertThat(sortSongsForFolderView(songs, SortOption.FolderNameZA).map { it.title })
            .containsExactly("Apple", "Mango", "Zebra").inOrder()
    }

    @Test
    fun `discs come before tracks and untagged songs follow by title`() {
        val songs = listOf(
            song(1, "D2 T1", 1, disc = 2),
            song(2, "D1 T2", 2, disc = 1),
            song(3, "Untagged B", 0),
            song(4, "D1 T1", 1, disc = 1),
            song(5, "Untagged A", 0),
        )

        assertThat(sortSongsForFolderView(songs, SortOption.FolderNameAZ).map { it.title })
            .containsExactly("D1 T1", "D1 T2", "D2 T1", "Untagged A", "Untagged B").inOrder()
    }

    @Test
    fun `a folder with no track tags keeps the old title order`() {
        val songs = listOf(song(1, "Charlie", 0), song(2, "alpha", 0), song(3, "Bravo", 0))

        assertThat(sortSongsForFolderView(songs, SortOption.FolderNameAZ).map { it.title })
            .containsExactly("alpha", "Bravo", "Charlie").inOrder()
    }

    @Test
    fun `whole-folder play uses the same track order in every folder`() {
        val child = MusicFolder(
            path = "$root/Artist/Album 2",
            name = "Album 2",
            songs = listOf(song(4, "B-side", 2), song(5, "A-side", 1)).toImmutableList(),
            musicRootPath = root,
        )
        val parent = MusicFolder(
            path = "$root/Artist",
            name = "Artist",
            songs = listOf(song(1, "Third", 3), song(2, "First", 1), song(3, "Second", 2)).toImmutableList(),
            subFolders = listOf(child).toImmutableList(),
            musicRootPath = root,
        )

        assertThat(folderSongsInOrder(parent, SortOption.FolderNameAZ).map { it.title })
            .containsExactly("A-side", "B-side", "First", "Second", "Third").inOrder()
    }
}
