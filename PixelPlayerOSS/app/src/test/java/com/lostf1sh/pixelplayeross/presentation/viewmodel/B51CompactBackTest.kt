package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.MusicFolder
import kotlinx.collections.immutable.persistentListOf
import org.junit.jupiter.api.Test

class B51CompactBackTest {
    @Test fun `back visits visible compact ancestors and then the folder list`() {
        val child = MusicFolder("/storage/SD/Music/Rock/Album", "Album")
        val root = MusicFolder("/storage/SD/Music/Rock", "Music / Rock", subFolders = persistentListOf(child))
        var state = PlayerUiState(musicFolders = persistentListOf(root), currentFolder = child, currentFolderPath = child.path, folderSourceRootPath = "/storage/SD")
        val navigation = FolderNavigationStateHolder()
        navigation.navigateBackFolder({ state }, { mutate -> state = mutate(state) }, {})
        assertThat(state.currentFolder).isEqualTo(root)
        navigation.navigateBackFolder({ state }, { mutate -> state = mutate(state) }, {})
        assertThat(state.currentFolder).isNull()
        assertThat(state.currentFolderPath).isNull()
    }
    @Test fun `a music folder at the volume root still opens its own songs`() {
        val root = MusicFolder("/storage/SD", "SD")
        var state = PlayerUiState(musicFolders = persistentListOf(root), folderSourceRootPath = root.path)
        FolderNavigationStateHolder().navigateToFolder(root.path, { state }, { mutate -> state = mutate(state) }, {})
        assertThat(state.currentFolder).isEqualTo(root)
    }

}
