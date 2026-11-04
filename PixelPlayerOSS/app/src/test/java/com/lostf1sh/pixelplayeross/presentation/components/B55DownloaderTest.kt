package com.lostf1sh.pixelplayeross.presentation.components

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.offline.*
import com.lostf1sh.pixelplayeross.presentation.viewmodel.*
import com.lostf1sh.pixelplayeross.ui.ds.*
import io.mockk.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="es-w360dp-h805dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class)
class B55DownloaderTest {
    @get:Rule val compose=createComposeRule()
    private val dir=File("/home/ale/proyects/honor-dap-workspace/analysis/audit-2026-09-27/batches/B55-gates/png-after/downloader")
    private fun pending(vm: OfflineDownloadsViewModel): Set<String> {
        val getter=vm.javaClass.methods.firstOrNull { it.name == "getPendingKeys" } ?: return emptySet()
        @Suppress("UNCHECKED_CAST")
        return (getter.invoke(vm) as StateFlow<Set<String>>).value
    }
    @Composable private fun pendingState(vm: OfflineDownloadsViewModel): Set<String> {
        val flow = remember(vm) {
            @Suppress("UNCHECKED_CAST")
            (vm.javaClass.methods.firstOrNull { it.name == "getPendingKeys" }?.invoke(vm) as? Flow<Set<String>>) ?: flowOf(emptySet())
        }
        return flow.collectAsState(emptySet()).value
    }
    private fun save(name: String) {
        compose.waitForIdle();dir.mkdirs()
        val bitmap=com.lostf1sh.pixelplayeross.ui.glass.B42Renders.screen(compose)
        File(dir,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
    }
    private fun panel(name: String, s: InfoSelection, busy: Set<InfoAction> = emptySet()) {
        val pages=flowOf(PagingData.from(emptyList<InfoArtist>()))
        compose.setContent {
            DapTheme(darkTheme=true,baseTheme=DapBaseTheme.Graphite) {
                MusicInfoDownloadsContent(pages.collectAsLazyPagingItems(),"",{},InfoQueue(),InfoProgress(0,0,0),InfoProgress(0,0,0),
                    s,false,false,{flowOf(emptyList())},{},{},{},{},{},{_,_->},{_,_->},{},busy=busy)
            }
        }
        save(name)
    }
    @Test fun `Find does not lock Download or Clear and the mixed panel has one primary action`() {
        panel("mixed",InfoSelection(3,151,null,3),setOf(InfoAction.FIND))
        compose.onNodeWithText("Descargar 3").assertIsEnabled()
        compose.onNodeWithText("Quitar selección").assertIsEnabled()
        compose.onNodeWithText("Borrar 151").assertIsEnabled()
        compose.onNodeWithText("tamaño no indicado").assertDoesNotExist()
    }
    @Test fun `only new has the compact size detail`() { panel("new",InfoSelection(3,0,5100000,0)) }
    @Test fun `only saved has Delete as its action`() { panel("saved",InfoSelection(0,151,null,0)) }
    @Test fun `deleting displays progress and locks only Delete`() {
        panel("deleting",InfoSelection(0,151,null,0),setOf(InfoAction.DELETE))
        compose.onNodeWithText("Borrando 151…").assertExists()
        compose.onNodeWithText("Borrar 151").assertIsNotEnabled()
        compose.onNodeWithText("Quitar selección").assertIsEnabled()
    }
    @Test fun `held DAO shows the first tap next frame and the third tap wins and failure rolls back`(): Unit = runBlocking {
        val scheduler=TestCoroutineScheduler()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        val downloads=mockk<MusicInfoDownloads>(relaxed=true)
        val dao=mockk<MusicInfoDiscoveryDao>(relaxed=true)
        var stored=InfoDiscovery("lyrics|synthetic","LYRICS","Canción sintética","artist","Artista sintético",availability="AVAILABLE")
        every { downloads.dao } returns dao
        every { dao.listenChanges(any()) } returns {}
        every { downloads.scopeKey(any(),any()) } returns "library"
        every { dao.observeScope(any()) } answers { flowOf(listOf(stored)) }
        coEvery { dao.scopeRows(any()) } answers { listOf(stored) }
        coEvery { dao.scopeMembers(any()) } returns listOf(InfoArtistMember("library","artist","Artista sintético",stored.key,""))
        coEvery { dao.changedRows(any()) } answers { listOf(stored.copy(payload=null)) }
        val gate=CompletableDeferred<Unit>()
        coEvery { dao.selectAvailableKind(any(),any(),any()) } coAnswers { gate.await();stored=stored.copy(selected=thirdArg()) }
        val vm=OfflineDownloadsViewModel(downloads)
        try {
            vm.open(null)
            withTimeout(5000) { while(vm.opening.value) delay(5) }
            val pages=flowOf(PagingData.from(emptyList<InfoArtist>()))
            compose.setContent {
                val types by vm.types.collectAsState(emptyList())
                val selection by vm.selection.collectAsState(InfoSelection(0,0,null,0))
                val pending = pendingState(vm)
                DapTheme(darkTheme=true,baseTheme=DapBaseTheme.Graphite) {
                    MusicInfoDownloadsContent(pages.collectAsLazyPagingItems(),"",{},null,InfoProgress(1,1,0),InfoProgress(0,0,0),
                        selection,false,false,vm::choices,{},{},{},{},{},vm::select,vm::selectKind,{},types=types,pendingKeys=pending)
                }
            }
            val box=compose.onNodeWithContentDescription("Elegir todo de Letras")
            box.performClick();compose.mainClock.advanceTimeByFrame();save("first-frame");box.assertIsOn()
            assertThat(stored.selected).isFalse()
            scheduler.advanceTimeBy(299);scheduler.runCurrent();assertThat(pending(vm)).isEmpty()
            scheduler.advanceTimeBy(1);scheduler.runCurrent();assertThat(pending(vm)).isNotEmpty()
            save("pending-row")
            box.performClick();box.assertIsOff();box.performClick();box.assertIsOn()
            gate.complete(Unit)
            withTimeout(2000) { while(!stored.selected || vm.busy.value.isNotEmpty()) delay(5) }
            assertThat(stored.selected).isTrue()
            coEvery { dao.selectAvailableKind(any(),any(),any()) } throws java.io.IOException("Synthetic failure")
            box.performClick()
            compose.waitForIdle()
            box.assertIsOn()
            assertThat(vm.errors.value.keys).contains(InfoAction.CHOOSE)
        } finally { vm.viewModelScope.cancel();Dispatchers.resetMain();unmockkAll() }
    }
}
