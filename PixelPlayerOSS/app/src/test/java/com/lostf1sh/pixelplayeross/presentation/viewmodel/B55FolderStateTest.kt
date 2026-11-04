package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.MusicFolder
import org.junit.jupiter.api.Test

class B55FolderStateTest {
    @Test fun `F01 an unrelated preference emission keeps the open folder and only a real view change resets it`():Unit {
        val folder=MusicFolder("/synthetic/Music/Album","Album")
        var state=PlayerUiState(currentFolder=folder,currentFolderPath=folder.path,foldersFlatList=false)
        val holder=FolderNavigationStateHolder()
        val method=holder.javaClass.methods.firstOrNull { it.name=="setFoldersFlatListState" }
        fun emit(flat:Boolean) {
            val update:((PlayerUiState)->PlayerUiState)->Unit={ state=it(state) }
            if(method==null) state=state.copy(foldersFlatList=flat,currentFolder=null,currentFolderPath=null)
            else method.invoke(holder,flat,update)
        }
        repeat(10) { emit(false) }
        assertThat(state.currentFolder).isSameInstanceAs(folder)
        assertThat(state.currentFolderPath).isEqualTo(folder.path)
        emit(true)
        assertThat(state.currentFolder).isNull()
        assertThat(state.currentFolderPath).isNull()
    }
}
