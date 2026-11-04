package com.lostf1sh.pixelplayeross.presentation.components

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.network.SourceWait
import com.lostf1sh.pixelplayeross.data.network.SourceWaitKind
import com.lostf1sh.pixelplayeross.data.offline.*
import com.lostf1sh.pixelplayeross.presentation.viewmodel.InfoAction
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "es-w360dp-h805dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class B52DownloaderRenderTest {
    @get:Rule val compose = createComposeRule()
    private val state = mutableStateOf("C1")
    private val now = 1_800_000_000_000L
    private val artists = listOf("Coro del Puerto", "Hålla Norr", "Lumen Vale", "Okra Sun", "Tiza Azul", "Vela Norte")
    private val calls = mutableListOf<String>()
    private fun type(kind: InfoKind, total: Int, available: Int, chosen: Int = available, saved: Int = 0, queued: Int = 0, retry: Int = 0, bytes: Long = 0, failed: Int = 0, unchecked: Int = 0, local: Int = 0) = InfoTypeCounts(kind, total, available, saved, queued, retry, chosen, 0, bytes, chosen, InfoGroupCounts(if(available > 0) available else saved, chosen), local, total - available - saved - queued - retry - failed - unchecked - local, failed, unchecked, bytes, available, bytes)
    private fun summary(id: String): List<InfoTypeCounts> {
        if (id == "B2") return listOf(type(InfoKind.LYRICS,11,9,0),type(InfoKind.PHOTO,1,0,0,saved=1),type(InfoKind.BIO,1,1,0),type(InfoKind.COVER,1,0,0,local=1),type(InfoKind.DESCRIPTION,1,0,0))
        if (id == "D6") return listOf(type(InfoKind.LYRICS,1204,0,0,saved=902,queued=118),type(InfoKind.PHOTO,86,0,0,saved=59,retry=12),type(InfoKind.BIO,86,0,0,saved=64),type(InfoKind.COVER,19,0,0,saved=15),type(InfoKind.DESCRIPTION,112,0,0,saved=66))
        val before = id == "B1"
        val finding = id in listOf("B3", "B4", "B5")
        val partial = id == "C2"
        val downloading = id.startsWith("D") && id != "D8" || id == "G2" || id == "C5"
        val done = id == "D7"
        return listOf(
            type(InfoKind.LYRICS, 1204, if(before || downloading) 0 else if(finding) 402 else 1020, if(partial || finding || downloading || before) 0 else 1020, if(downloading) if(done) 1020 else 350 else 0, if(downloading && !done) 670 else 0, unchecked = if(before) 1204 else if(finding) 682 else 0, local = if(id=="B6") 45 else 0, failed = if(id=="B6") 12 else 0),
            type(InfoKind.PHOTO, 86, if(before || downloading) 0 else if(finding) 54 else 71, if(partial) 38 else if(before || downloading || finding) 0 else 71, if(downloading) if(done) 71 else 38 else 0, if(downloading && !done) 33 else 0, bytes = if(partial) 5_100_000 else if(done) 10_400_000 else 9_800_000, unchecked = if(before) 86 else if(finding) 12 else 0),
            type(InfoKind.BIO, 86, if(before || downloading) 0 else 64, if(before || downloading || finding) 0 else 64, if(downloading) if(done) 64 else 24 else 0, if(downloading && !done) 40 else 0, unchecked = if(before) 86 else 0),
            type(InfoKind.COVER, 19, if(before || downloading) 0 else if(partial) 11 else 15, if(before || downloading || finding) 0 else if(partial) 11 else 15, if(partial) 4 else if(downloading && done) 15 else 0, if(downloading && !done) 15 else 0, unchecked = if(before || finding) 19 else 0),
            type(InfoKind.DESCRIPTION, 112, if(before || downloading) 0 else 66, if(before || downloading || finding) 0 else 66, if(downloading && done) 66 else 0, if(downloading && !done) 66 else 0, unchecked = if(before || finding) 112 else 0),
        )
    }
    private fun choice(key: String, kind: String, title: String = "", album: String = "", status: String = "AVAILABLE", saved: Boolean = false, selected: Boolean = false, download: String = "IDLE", deleted: Boolean = false) = InfoChoice(key,kind,title,"Lumen Vale",album,if(album.isEmpty()) "" else "Noches cortas",status,if(kind == "PHOTO") 240_000 else null,saved,selected,download,now+60_000,deletedByUser=deleted)
    private fun details(id: String): List<InfoChoice> {
        val mixed = id in listOf("C7", "E1")
        val deleted = id == "E2"
        val queue = id == "C5"
        return listOf(
            choice("photo","PHOTO",selected=id=="C7"),
            choice("bio","BIO",saved=!deleted,selected=mixed,deleted=deleted),
            choice("cover","COVER",album="album",status=if(mixed || deleted || queue) "AVAILABLE" else "LOCAL",saved=mixed||queue,selected=mixed,deleted=deleted),
            choice("description","DESCRIPTION",album="album",status=if(queue) "AVAILABLE" else "MISSING",download=if(queue) "DOWNLOADING" else "IDLE"),
            choice("sal","LYRICS","Sal y cobre","album",selected=!queue && !deleted && id!="E1",download=if(queue) "QUEUED" else "IDLE"),
            choice("hilo","LYRICS","Hilo","album",status=if(mixed||deleted||queue) "AVAILABLE" else "FAILED",selected=id=="C7",download=if(queue) "RETRY" else "IDLE"),
            choice("alba","LYRICS","Antes del alba","album",status=if(queue) "AVAILABLE" else "UNCHECKED",saved=queue),
        )
    }
    private fun show() {
        compose.setContent {
            val id = state.value
            key(id) {
                val advanced = id in listOf("C3","C4","C5","C6","C7","E1","E2")
                val list = if(id=="C6" || id=="B7") emptyList() else if(advanced && id!="C3") listOf(InfoArtist("Lumen Vale","Lumen Vale")) else artists.map { InfoArtist(it,it) }
                val pages = remember(id) { flowOf(PagingData.from(list)) }
                val rows = remember(id) { MutableStateFlow(details(id)) }
                val initial = id in listOf("B1", "B2", "B7")
                val finding = id in listOf("B3","B4","B5")
                val downloading = id.startsWith("D") && id!="D8" || id=="C5" || id=="G2"
                val pending = id in listOf("B1","B3","B4","B5","B2")
                val selection = when { id=="C7" -> InfoSelection(3,2,240_000,2); id=="E1" -> InfoSelection(0,2,null,0); id in listOf("C4") -> InfoSelection(1,0,null,1); id in listOf("C1","B6","D8","G1") -> InfoSelection(1236,0,9_800_000,1165); id in listOf("C2","C3") -> InfoSelection(179,0,5_100_000,141); else -> InfoSelection(0,0,null,0) }
                val network = when(id) { "B4","D3" -> InfoNetworkState.WIFI; "B5","D4" -> InfoNetworkState.INTERNET; else -> InfoNetworkState.READY }
                val sources = when(id) { "D5" -> listOf(SourceWait("LRCLIB",SourceWaitKind.RATE_LIMIT,now+40_000)); "D6" -> listOf(SourceWait("Wikimedia Commons",SourceWaitKind.UNAVAILABLE,now+120_000)); else -> emptyList() }
                DapTheme(darkTheme = !id.startsWith("G"), baseTheme = if(id.startsWith("G")) com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme.Light else com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme.Graphite) {
                    CompositionLocalProvider(com.lostf1sh.pixelplayeross.ui.ds.LocalDapAccent provides androidx.compose.ui.graphics.Color(if(id.startsWith("G")) 0xFF516755 else 0xFF9CC6A4)) {
                    MusicInfoDownloadsContent(pages.collectAsLazyPagingItems(),if(id=="C6") "Nocturno" else "",{calls += "search:$it"},
                        InfoQueue(paused=id=="D2",currentArtist="Coro del Puerto",findMeasuredMs=280,findMeasuredCount=1),
                        InfoProgress(if(initial) 0 else 1507,if(finding) 642 else if(initial) 0 else 1507,if(id=="B6") 12 else 0,if(finding) 865 else 0),
                        if(downloading) InfoProgress(1236,if(id=="D7") 1236 else if(id=="D6") 1106 else 412,if(id=="D6") 12 else 0,if(id=="D7") 0 else 824) else InfoProgress(0,0,0),
                        selection,id=="B2",false,{ artist -> if(id=="C3") {
                            val index=artists.indexOf(artist)
                            val total=listOf(24,31,12,9,14,0).getOrElse(index){0}
                            val chosen=listOf(6,31,0,3,14,0).getOrElse(index){0}
                            flowOf((0 until total).map { choice("$artist-$it","PHOTO",selected=it<chosen) })
                        } else rows },{calls += "find"},{calls += "download"},{calls += "delete"},{calls += "pause"},{calls += "resume"},{_,_->calls += "select"},{_,_->calls += "kind"},{calls += "close"},{_,_,_->calls += "group"},
                        summary(id),if(id=="B7") "Toda la biblioteca · 0 canciones" else if(id in listOf("C7","E1","E2")) "Artista · Lumen Vale" else if(id=="B2") "Álbum · Mareas · 11 canciones" else if(id=="C5") "Álbum · Mareas" else "Toda la biblioteca · 1.204 canciones",
                        InfoWaitState(network,sources,now),if(id=="D8") setOf(InfoAction.DOWNLOAD) else emptySet(), if(id=="E1") setOf(InfoAction.DELETE) else emptySet(),
                        if(id=="E2" || id=="Cancel") InfoUndo("undo",if(id=="Cancel") "CANCEL" else "DELETE",now+5000,if(id=="Cancel") 824 else 2,0) else null,
                        {calls += "undo:$it"},{calls += "cancel"},{calls += "done"},{calls += "retry:$it"},id=="B7",if(pending) 0 else if(id=="B6" || id.startsWith("D") || id=="G2" || id=="Cancel") now else now-3*86_400_000,advanced,findQueued=id=="B2",albumScopeName=if(id=="C5") "Mareas" else null,artistScoped=id in listOf("C7","E1","E2"))
                }
            }
            }
        }
        compose.waitForIdle()
    }
    private fun save(id: String) {
        val bitmap = com.lostf1sh.pixelplayeross.ui.glass.B42Renders.screen(compose)
        if(id.startsWith("G")) assertThat(bitmap.getPixel(1,160)).isEqualTo(android.graphics.Color.rgb(247,247,248))
        assertThat(bitmap.width).isEqualTo(720)
        assertThat(bitmap.height).isEqualTo(1610)
        val dir = File(System.getenv("B52_RENDERS") ?: "/home/ale/proyects/honor-dap-workspace/analysis/audit-2026-09-27/batches/B52-gates/png-after")
        dir.mkdirs(); File(dir,"$id.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        bitmap.recycle()
    }
    @Test fun `all downloader design states render at the specified Spanish viewport`() {
        show()
        val ids = (1..7).map { "B$it" } + (1..7).map { "C$it" } + (1..8).map { "D$it" } + listOf("E1","E2","G1","G2","Cancel")
        for(id in ids) {
            compose.runOnIdle { state.value=id }; compose.waitForIdle()
            if(id=="C5") {
                compose.onNodeWithText("Mareas",useUnmergedTree=true).performClick()
            } else if(id in listOf("C4","C7","E1","E2")) {
                compose.onNodeWithText("Lumen Vale",useUnmergedTree=true).performClick()
                compose.onNodeWithText("Noches cortas",useUnmergedTree=true).performClick()
                compose.waitForIdle()
                if(id=="C4") compose.onNodeWithTag("info-artists").performScrollToIndex(2)
            }
            save(id)
        }
    }
    @Test fun `a kind never changes its persisted checkbox until Room emits`() {
        show()
        compose.onNodeWithContentDescription("Elegir todo de Letras").assertIsOn().performClick().assertIsOn()
        assertThat(calls).contains("kind")
    }
    @Test fun `download cancel done retry and undo call their own actions`() {
        show()
        compose.runOnIdle { state.value="D1" }; compose.waitForIdle()
        compose.onNodeWithText("Cancelar").performClick(); assertThat(calls).contains("cancel")
        compose.runOnIdle { state.value="D7" }; compose.waitForIdle()
        compose.onNodeWithText("Aceptar").performClick(); assertThat(calls).contains("done")
        compose.onNodeWithText("Pausar").assertDoesNotExist()
        compose.runOnIdle { state.value="D8" }; compose.waitForIdle()
        compose.onNodeWithText("Reintentar").performClick(); assertThat(calls).contains("retry:DOWNLOAD")
        compose.runOnIdle { state.value="Cancel" }; compose.waitForIdle()
        compose.onNodeWithText("Deshacer").performClick(); assertThat(calls).contains("undo:undo")
    }
    @Test fun `no selection means no dock and an empty library has no unusable steps`() {
        show()
        compose.runOnIdle { state.value="B1" }; compose.waitForIdle()
        compose.onNodeWithTag("info-bottom-panel").assertDoesNotExist()
        compose.onNodeWithText("0 elegidos").assertDoesNotExist()
        compose.runOnIdle { state.value="B7" }; compose.waitForIdle()
        compose.onNodeWithTag("info-steps").assertDoesNotExist()
        compose.onNodeWithText("No hay canciones aquí").assertIsDisplayed()
    }
}
