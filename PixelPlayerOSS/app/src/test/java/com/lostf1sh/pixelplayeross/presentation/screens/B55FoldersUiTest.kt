package com.lostf1sh.pixelplayeross.presentation.screens

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.*
import com.lostf1sh.pixelplayeross.data.stats.Kit
import com.lostf1sh.pixelplayeross.presentation.viewmodel.StablePlayerState
import com.lostf1sh.pixelplayeross.ui.ds.*
import kotlinx.collections.immutable.toImmutableList
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Config

// Match the existing Library paging fixtures' Compose rule and native renderer.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[35],application=Application::class,qualifiers="en-w360dp-h805dp-xhdpi")
class B55FoldersUiTest {
    @get:Rule val compose=createComposeRule()
    private val songs=listOf(Kit.song(1,title="Find this track"),Kit.song(2,title="Other track")).toImmutableList()
    private val folders=(1..80).map { MusicFolder("/synthetic/Folder$it","Folder%02d".format(it),songs) }.toImmutableList()
    private var tree=folders
    private val current=mutableStateOf<MusicFolder?>(null)
    private val taps=mutableListOf<List<String>>()
    @Composable private fun Page() {
        DapTheme { LibraryFoldersTab(tree,current.value,false,{current.value=null},
            {path ->current.value=(tree + tree.flatMap { it.subFolders }).first { it.path==path }},{},{_,queue -> taps+=queue.map { it.id }},
            StablePlayerState(),0.dp,onMoreOptionsClick={},isRefreshing=false,onRefresh={}) }
    }
    @Test fun `F03 folder search filters songs and the played queue follows the filtered list`() {
        current.value=folders.first()
        compose.setContent { Page() }
        compose.onNode(hasSetTextAction()).performTextInput("Find")
        compose.onNodeWithText("Other track").assertDoesNotExist()
        compose.onNodeWithText("Find this track").performClick()
        assertThat(taps.single()).containsExactly(songs.first().id)
    }
    @Test fun `F03 folder query survives saved state restoration`() {
        val restoration=StateRestorationTester(compose)
        restoration.setContent { Page() }
        compose.onNode(hasSetTextAction()).performTextInput("Folder60")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNode(hasSetTextAction()).assertTextContains("Folder60")
        compose.onNodeWithText("Folder01").assertDoesNotExist()
    }
    @Test fun `F04 returning to the parent restores the same scroll position`() {
        compose.setContent { Page() }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Folder60"))
        val before=compose.onNodeWithText("Folder60").fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithText("Folder60").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Find this track").assertIsDisplayed()
        compose.runOnIdle { current.value=null }
        compose.waitForIdle()
        val after=compose.onNodeWithText("Folder60").fetchSemanticsNode().boundsInRoot.top
        assertThat(after).isWithin(1f).of(before)
    }
    @Test fun `F04 returning to a nested parent restores scroll during the outgoing animation`() {
        val parent=MusicFolder("/synthetic","Parent",subFolders=folders)
        tree=listOf(parent).toImmutableList()
        current.value=parent
        compose.setContent { Page() }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Folder60"))
        val before=compose.onNodeWithText("Folder60").fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithText("Folder60").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Find this track").assertIsDisplayed()
        compose.runOnIdle { current.value=parent }
        compose.waitForIdle()
        val after=compose.onNodeWithText("Folder60").fetchSemanticsNode().boundsInRoot.top
        assertThat(after).isWithin(1f).of(before)
    }
    @OptIn(InternalComposeApi::class)
    @Composable private fun ActionRow(onSort:()->Unit) {
        // Invoke the existing private composable without changing visibility in the before tree.
        val method=Class.forName("com.lostf1sh.pixelplayeross.presentation.screens.LibraryScreenKt").declaredMethods
            .single { it.name=="DapLibraryActionRow" }.apply { isAccessible=true }
        val noop:()->Unit={}
        val folderClick:(String)->Unit={}
        val args=mutableListOf<Any?>(noop,true,null,false,true,Modifier,onSort,noop,false,false,noop,null,
            "/synthetic","Music",emptyList<MusicFolder>(),folderClick,noop,false,StorageFilter.ALL,noop,currentComposer)
        repeat(method.parameterCount-args.size) { args+=0 }
        method.invoke(null,*args.toTypedArray())
    }
    @Test fun `F02 Sort is reachable alongside breadcrumbs in default Tree view`() {
        var opened=0
        compose.setContent { DapTheme { ActionRow { opened++ } } }
        val context=androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        compose.onNodeWithText(context.getString(com.lostf1sh.pixelplayeross.R.string.presentation_batch_d_sort_by)).performClick()
        assertThat(opened).isEqualTo(1)
    }
}
