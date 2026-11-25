package com.lostf1sh.pixelplayeross.data.library

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.MusicFolder
import com.lostf1sh.pixelplayeross.data.model.SortOption
import com.lostf1sh.pixelplayeross.data.stats.Kit
import com.lostf1sh.pixelplayeross.utils.PlayOrder
import kotlinx.collections.immutable.toImmutableList
import org.junit.jupiter.api.Test

class B72FolderSongsOrderTest {

    private val root = "/storage/emulated/0/Music"

    private fun song(id: Long, title: String) = Kit.song(
        id = id,
        title = title,
        artist = "Artist",
        album = "Album",
    ).copy(path = "$root/$title.flac")

    private fun nestedTree(): MusicFolder {
        val emptyChild = MusicFolder(
            path = "$root/Root/00-Empty",
            name = "00-Empty",
            musicRootPath = root,
        )
        val deepChild = MusicFolder(
            path = "$root/Root/Bravo/Deep",
            name = "Deep",
            songs = listOf(song(6, "Delta")).toImmutableList(),
            musicRootPath = root,
        )
        val bravo = MusicFolder(
            path = "$root/Root/Bravo",
            name = "Bravo",
            songs = listOf(song(4, "Echo"), song(5, "Alpha")).toImmutableList(),
            subFolders = listOf(deepChild).toImmutableList(),
            musicRootPath = root,
        )
        val alpha = MusicFolder(
            path = "$root/Root/Alpha",
            name = "Alpha",
            songs = listOf(song(3, "Charlie")).toImmutableList(),
            musicRootPath = root,
        )
        return MusicFolder(
            path = "$root/Root",
            name = "Root",
            songs = listOf(song(2, "Zulu"), song(1, "Beta")).toImmutableList(),
            subFolders = listOf(bravo, emptyChild, alpha).toImmutableList(),
            musicRootPath = root,
        )
    }

    @Test
    fun `depth-first order puts subfolders first then own songs in each folder sort option`() {
        val folder = nestedTree()

        assertThat(folderSongsInOrder(folder, SortOption.FolderNameAZ).map { it.title })
            .containsExactly("Charlie", "Delta", "Alpha", "Echo", "Beta", "Zulu")
            .inOrder()

        assertThat(folderSongsInOrder(folder, SortOption.FolderNameZA).map { it.title })
            .containsExactly("Delta", "Echo", "Alpha", "Charlie", "Zulu", "Beta")
            .inOrder()

        assertThat(folderSongsInOrder(folder, SortOption.FolderPathAZ).map { it.title })
            .containsExactly("Charlie", "Delta", "Alpha", "Echo", "Beta", "Zulu")
            .inOrder()

        assertThat(folderSongsInOrder(folder, SortOption.FolderPathZA).map { it.title })
            .containsExactly("Delta", "Alpha", "Echo", "Charlie", "Beta", "Zulu")
            .inOrder()

        // Song count ascending: 00-Empty (0), Alpha (1), Bravo (3)
        assertThat(folderSongsInOrder(folder, SortOption.FolderSongCountAsc).map { it.title })
            .containsExactly("Charlie", "Delta", "Alpha", "Echo", "Beta", "Zulu")
            .inOrder()

        // Song count descending: Bravo (3), Alpha (1), 00-Empty (0)
        assertThat(folderSongsInOrder(folder, SortOption.FolderSongCountDesc).map { it.title })
            .containsExactly("Delta", "Alpha", "Echo", "Charlie", "Beta", "Zulu")
            .inOrder()

        // Subfolder count ascending: 00-Empty (0), Alpha (0), Bravo (1)
        assertThat(folderSongsInOrder(folder, SortOption.FolderSubdirCountAsc).map { it.title })
            .containsExactly("Charlie", "Delta", "Alpha", "Echo", "Beta", "Zulu")
            .inOrder()

        // Subfolder count descending: Bravo (1), 00-Empty (0), Alpha (0)
        assertThat(folderSongsInOrder(folder, SortOption.FolderSubdirCountDesc).map { it.title })
            .containsExactly("Delta", "Alpha", "Echo", "Charlie", "Beta", "Zulu")
            .inOrder()
    }

    @Test
    fun `folder with songs only in a subfolder returns them and deduplicates repeated ids`() {
        val dup = song(99, "Repeated")
        val sub = MusicFolder(
            path = "$root/Parent/Sub",
            name = "Sub",
            songs = listOf(dup, song(100, "Unique")).toImmutableList(),
            musicRootPath = root,
        )
        val sub2 = MusicFolder(
            path = "$root/Parent/Sub2",
            name = "Sub2",
            songs = listOf(dup).toImmutableList(),
            musicRootPath = root,
        )
        val parent = MusicFolder(
            path = "$root/Parent",
            name = "Parent",
            songs = kotlinx.collections.immutable.persistentListOf(),
            subFolders = listOf(sub, sub2).toImmutableList(),
            musicRootPath = root,
        )

        assertThat(folderHasPlayableSongs(parent)).isTrue()
        assertThat(folderSongsInOrder(parent, SortOption.FolderNameAZ).map { it.id })
            .containsExactly("99", "100")
            .inOrder()
    }

    @Test
    fun `empty folder tree with empty subfolders has no playable songs`() {
        val empty = MusicFolder(
            path = "$root/Empty",
            name = "Empty",
            subFolders = listOf(MusicFolder(path = "$root/Empty/Sub", name = "Sub")).toImmutableList(),
        )
        assertThat(folderHasPlayableSongs(empty)).isFalse()
        assertThat(folderSongsInOrder(empty, SortOption.FolderNameAZ)).isEmpty()
    }

    @Test
    fun `PlayOrder batch helpers preserve permutation and sequential batch placement`() {
        val base = intArrayOf(2, 0, 1) // current = 0 at position 1
        val appended = PlayOrder.appendBatch(base, firstNewIndex = 3, count = 2)
        assertThat(appended.toList()).containsExactly(2, 0, 1, 3, 4).inOrder()
        assertThat(PlayOrder.isPermutation(appended, 5)).isTrue()

        // Insert 3 items at insertionIndex = 1 (right after currentIndex = 0)
        val after = PlayOrder.placeBatchAfter(base, insertionIndex = 1, count = 3, afterTimelineIndex = 0)
        // Old indices >= 1 shifted by +3: 2->5, 1->4; new items 1,2,3 placed right after 0
        assertThat(after.toList()).containsExactly(5, 0, 1, 2, 3, 4).inOrder()
        assertThat(PlayOrder.isPermutation(after, 6)).isTrue()
    }
}
