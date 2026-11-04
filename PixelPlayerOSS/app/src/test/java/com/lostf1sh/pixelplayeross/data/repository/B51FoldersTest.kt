package com.lostf1sh.pixelplayeross.data.repository

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.database.FolderSongRow
import com.lostf1sh.pixelplayeross.presentation.screens.flattenFolders
import com.lostf1sh.pixelplayeross.presentation.screens.folderBreadcrumb
import com.lostf1sh.pixelplayeross.presentation.screens.folderRelativePath
import com.lostf1sh.pixelplayeross.presentation.screens.sortMusicFoldersByOption
import com.lostf1sh.pixelplayeross.data.model.SortOption
import com.lostf1sh.pixelplayeross.utils.StorageUtils
import org.junit.jupiter.api.Test

class B51FoldersTest {
    private val root = "/storage/1234-5678"
    private val builder = FolderTreeBuilder()
    private fun row(id: Long, path: String) = FolderSongRow(id, path, "Song $id", null)

    @Test fun `included music roots collapse only empty chains and preserve direct songs`() {
        val songs = listOf(row(1, "$root/Music/Rock/A"), row(2, "$root/Music/Rock/B"), row(3, "$root/Other/Hidden"))
        val tree = builder.buildCompactTree(songs, setOf(root), setOf("$root/Music"))
        assertThat(tree.map { it.name }).containsExactly("Music / Rock")
        assertThat(tree.single().path).isEqualTo("$root/Music/Rock")
        assertThat(tree.single().subFolders.map { it.name }).containsExactly("A", "B")
        assertThat(tree.single().totalSongCount).isEqualTo(2)
        val withDirect = builder.buildCompactTree(songs + row(4, "$root/Music"), setOf(root), setOf("$root/Music"))
        assertThat(withDirect.single().name).isEqualTo("Music")
        assertThat(withDirect.single().songs).hasSize(1)
    }

    @Test fun `overlapping includes and songs in the root appear once`() {
        val tree = builder.buildCompactTree(listOf(row(1, "$root/Music"), row(2, "$root/Music/A")), setOf(root), setOf("$root/Music", "$root/Music/A"))
        assertThat(tree).hasSize(1)
        assertThat(tree.single().totalSongCount).isEqualTo(2)
        assertThat(folderBreadcrumb(tree, "$root/Music/A").map { it.name }).containsExactly("Music", "A").inOrder()
    }

    @Test fun `flat list contains only direct song folders with relative paths and path sorting`() {
        val tree = builder.buildCompactTree(listOf(row(1, "$root/Music/Z/A"), row(2, "$root/Music/A/Z")), setOf(root), setOf("$root/Music"))
        val flat = flattenFolders(tree)
        assertThat(flat).hasSize(2)
        assertThat(flat.map { it.songs.size }).containsExactly(1, 1)
        assertThat(sortMusicFoldersByOption(flat, SortOption.FolderPathAZ).map(::folderRelativePath)).containsExactly("A/Z", "Z/A").inOrder()
        assertThat(sortMusicFoldersByOption(flat, SortOption.FolderPathZA).map(::folderRelativePath)).containsExactly("Z/A", "A/Z").inOrder()
    }

    @Test fun `volume switch requires songs on both sources including platform lookup failure`() {
        val internal = "/storage/emulated/0"
        assertThat(FolderVolumes.resolve(listOf("$internal/Music"), internal, root).showSwitch).isFalse()
        assertThat(FolderVolumes.resolve(listOf("$root/Music"), internal, null).showSwitch).isFalse()
        val both = FolderVolumes.resolve(listOf("$internal/Music", "$root/Music"), internal, null)
        assertThat(both.showSwitch).isTrue()
        assertThat(both.sdRoots).containsExactly(root)
        assertThat(FolderVolumes.resolve(listOf("$root/Music", "/storage/OTHER/Music"), internal, null).showSwitch).isTrue()
    }

    @Test fun `Android 10 derives the SD root only from the matching volume and documented suffix`() {
        val path = "$root/Android/data/app.polylute/files"
        assertThat(StorageUtils.deriveLegacyVolumeRoot(path, "app.polylute", "1234-5678", false, "1234-5678", false)).isEqualTo(root)
        assertThat(StorageUtils.deriveLegacyVolumeRoot(path, "app.polylute", "1234-5678", false, "OTHER", false)).isNull()
        assertThat(StorageUtils.deriveLegacyVolumeRoot(path, "app.polylute", null, false, null, false)).isNull()
        assertThat(StorageUtils.deriveLegacyVolumeRoot("$path/extra", "app.polylute", "1234-5678", false, "1234-5678", false)).isNull()
    }
}
