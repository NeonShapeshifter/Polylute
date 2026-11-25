package com.lostf1sh.pixelplayeross.data.library

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.database.FolderSongRow
import com.lostf1sh.pixelplayeross.data.model.MusicFolder
import com.lostf1sh.pixelplayeross.data.model.SortOption
import com.lostf1sh.pixelplayeross.data.repository.FolderTreeBuilder
import com.lostf1sh.pixelplayeross.data.stats.Kit
import com.lostf1sh.pixelplayeross.presentation.viewmodel.LibraryTabSearch
import kotlinx.collections.immutable.toImmutableList
import org.junit.jupiter.api.Test

class B74FolderPlaybackTest {

    private val root = "/storage/emulated/0/Music"

    private fun song(
        id: Long,
        title: String,
        album: String = "",
        albumId: Long = 0L,
        track: Int = 0,
        disc: Int? = null,
        path: String = "$root/$title.flac",
    ) = Kit.song(
        id = id,
        title = title,
        artist = "Artist $id",
        album = album,
        albumId = albumId,
        track = track,
    ).copy(discNumber = disc, path = path)

    @Test
    fun `mixed root of loose singles sorts by file name case-insensitive instead of track 1`() {
        val baby = song(
            id = 1,
            title = "...Baby One More Time",
            album = "Singles A",
            albumId = 1L,
            track = 1,
            path = "$root/Zzz - Baby One More Time.mp3",
        )
        val firstFile = song(
            id = 2,
            title = "Zebra",
            album = "Singles B",
            albumId = 2L,
            track = 7,
            path = "$root/aaa - First Single.flac",
        )
        val middleFile = song(
            id = 3,
            title = "...Dots Track One",
            album = "Singles C",
            albumId = 3L,
            track = 1,
            path = "$root/Mid - Track.flac",
        )
        val folder = MusicFolder(
            path = root,
            name = "Music",
            songs = listOf(baby, firstFile, middleFile).toImmutableList(),
            musicRootPath = root,
        )

        val shownIds = sortSongsForFolderView(folder.songs, SortOption.FolderNameAZ).map { it.id }
        val playIds = folderDiscSongsInOrder(folder, SortOption.FolderNameAZ).map { it.id }

        assertThat(shownIds).containsExactly("2", "3", "1").inOrder()
        assertThat(playIds).isEqualTo(shownIds)
        assertThat(folderDiscSongsInOrder(folder, SortOption.FolderNameZA).map { it.id })
            .containsExactly("1", "3", "2")
            .inOrder()
    }

    @Test
    fun `2-disc one-album folder plays in disc and track order with untagged after and ZA exact reverse`() {
        val songs = listOf(
            song(1, "Disc 2 Track 1", album = "Double Album", albumId = 42L, track = 1, disc = 2, path = "$root/z.flac"),
            song(2, "Disc 1 Track 2", album = "Double Album", albumId = 42L, track = 2, disc = 1, path = "$root/a.flac"),
            song(3, "Untagged B", album = "Double Album", albumId = 42L, track = 0, disc = null, path = "$root/b.flac"),
            song(4, "Disc 1 Track 1", album = "Double Album", albumId = 42L, track = 1, disc = 1, path = "$root/d.flac"),
            song(5, "Untagged A", album = "Double Album", albumId = 42L, track = 0, disc = null, path = "$root/c.flac"),
        )
        val folder = MusicFolder(
            path = "$root/Double Album",
            name = "Double Album",
            songs = songs.toImmutableList(),
            musicRootPath = root,
        )

        val az = sortSongsForFolderView(folder.songs, SortOption.FolderNameAZ)
        val za = sortSongsForFolderView(folder.songs, SortOption.FolderNameZA)
        assertThat(az.map { it.title }).containsExactly(
            "Disc 1 Track 1",
            "Disc 1 Track 2",
            "Disc 2 Track 1",
            "Untagged A",
            "Untagged B",
        ).inOrder()
        assertThat(za.map { it.id }).isEqualTo(az.map { it.id }.reversed())
        assertThat(folderDiscSongsInOrder(folder, SortOption.FolderNameAZ).map { it.id })
            .isEqualTo(az.map { it.id })
    }

    @Test
    fun `floating Play disc in folder with songs and subfolders plays only own songs and matches list and filter`() {
        val sub = MusicFolder(
            path = "$root/Mix/AlphaSub",
            name = "AlphaSub",
            songs = listOf(song(10, "Sub Song", album = "Sub", albumId = 10L, path = "$root/Mix/AlphaSub/01.flac")).toImmutableList(),
            musicRootPath = root,
        )
        val own1 = song(1, "...Intro", album = "Album A", albumId = 1L, track = 1, path = "$root/Mix/zeta.flac")
        val own2 = song(2, "Canción Uno", album = "Album B", albumId = 2L, track = 3, path = "$root/Mix/alpha.flac")
        val own3 = song(3, "Canción Dos", album = "Album C", albumId = 3L, track = 2, path = "$root/Mix/beta.flac")
        val folder = MusicFolder(
            path = "$root/Mix",
            name = "Mix",
            songs = listOf(own1, own2, own3).toImmutableList(),
            subFolders = listOf(sub).toImmutableList(),
            musicRootPath = root,
        )

        val listIds = sortSongsForFolderView(folder.songs, SortOption.FolderNameAZ).map { it.id }
        val discIds = folderDiscSongsInOrder(folder, SortOption.FolderNameAZ).map { it.id }
        assertThat(discIds).containsExactly("2", "3", "1").inOrder()
        assertThat(discIds).isEqualTo(listIds)

        // Active filter on screen: floating discs play only the filtered list in shown order
        val filteredListIds = sortSongsForFolderView(folder.songs, SortOption.FolderNameAZ)
            .filter { LibraryTabSearch.songMatches("cancion", it) }
            .map { it.id }
        val filteredDiscIds = folderDiscSongsInOrder(folder, SortOption.FolderNameAZ, query = "cancion").map { it.id }
        assertThat(filteredDiscIds).containsExactly("2", "3").inOrder()
        assertThat(filteredDiscIds).isEqualTo(filteredListIds)

        // Long-press sheet plays whole tree with subfolders first
        assertThat(folderSongsInOrder(folder, SortOption.FolderNameAZ).map { it.id })
            .containsExactly("10", "2", "3", "1")
            .inOrder()
    }

    @Test
    fun `folder with only CD1 and CD2 subfolders plays CD1 then CD2 on floating discs`() {
        val cd1 = MusicFolder(
            path = "$root/Live/CD1",
            name = "CD1",
            songs = listOf(
                song(12, "CD1 Track 2", album = "Live", albumId = 99L, track = 2, path = "$root/Live/CD1/02.flac"),
                song(11, "CD1 Track 1", album = "Live", albumId = 99L, track = 1, path = "$root/Live/CD1/01.flac"),
            ).toImmutableList(),
            musicRootPath = root,
        )
        val cd2 = MusicFolder(
            path = "$root/Live/CD2",
            name = "CD2",
            songs = listOf(
                song(21, "CD2 Track 1", album = "Live", albumId = 99L, track = 1, path = "$root/Live/CD2/01.flac"),
            ).toImmutableList(),
            musicRootPath = root,
        )
        val parent = MusicFolder(
            path = "$root/Live",
            name = "Live",
            songs = kotlinx.collections.immutable.persistentListOf(),
            subFolders = listOf(cd2, cd1).toImmutableList(),
            musicRootPath = root,
        )

        assertThat(folderDiscSongsInOrder(parent, SortOption.FolderNameAZ).map { it.id })
            .containsExactly("11", "12", "21")
            .inOrder()
    }

    @Test
    fun `FolderTreeBuilder preserves album and file_path on stubs for one-album vs mixed sorting`() {
        val rows = listOf(
            FolderSongRow(1L, "$root/Singles", "...Baby", null, trackNumber = 1, discNumber = null, albumName = "A", albumId = 1L, filePath = "$root/Singles/zzz.mp3", artistName = "Britney"),
            FolderSongRow(2L, "$root/Singles", "Zebra", null, trackNumber = 5, discNumber = null, albumName = "B", albumId = 2L, filePath = "$root/Singles/aaa.mp3", artistName = "Other"),
        )
        val tree = FolderTreeBuilder().buildFolderTreeForRoots(rows, setOf(root))
        val singles = tree.single()
        assertThat(sortSongsForFolderView(singles.songs, SortOption.FolderNameAZ).map { it.id })
            .containsExactly("2", "1")
            .inOrder()
    }
}
