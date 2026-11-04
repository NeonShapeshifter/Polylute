package com.lostf1sh.pixelplayeross.presentation.components

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.database.PixelPlayerDatabase
import com.lostf1sh.pixelplayeross.data.offline.*
import com.lostf1sh.pixelplayeross.ui.ds.*
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], application=Application::class, qualifiers="es-w360dp-h805dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class B52Fix1RenderTest {
    @get:Rule val compose=createComposeRule()
    private val now=1_800_000_000_000L
    private val calls=mutableListOf<String>()
    private fun row(key: String,kind: String="PHOTO",selected: Boolean=false,saved: Boolean=false,state: String="IDLE",availability: String="AVAILABLE") =
        InfoDiscovery(key,kind,key,"a","Artista A",availability=availability,selected=selected,saved=saved,downloadState=state,checkedAt=now)
    private fun show(id: String) {
        val rows=when(id) {
            "owner","local","clear" -> (0 until 959).map { row("saved$it",saved=true,selected=it<156 && id!="clear") } +
                (0 until 31).map { row("new$it") } + (0 until 3).map { row("lyric$it",kind="LYRICS") } +
                (0 until 4899).map { row("local$it",kind="LYRICS",availability="LOCAL") } + (0 until 36).map { row("missing$it",kind="LYRICS",availability="MISSING") }
            "queued","legacy-queued" -> (0 until 3).map { row("pending$it",kind="LYRICS",selected=id=="queued",state="QUEUED") }
            "saved" -> (0 until 3).map { row("done$it",kind="LYRICS",saved=true,state="DONE") }
            "running" -> listOf(row("Sal y cobre",kind="LYRICS",state="DOWNLOADING"))
            "shared" -> listOf(row("own"),row("shared",selected=true))
            "wifi" -> listOf(row("new",selected=true).copy(sourceBytes=250_000))
            else -> listOf(row("saved",saved=true))
        }
        val db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),PixelPlayerDatabase::class.java).build()
        val choices: List<InfoChoice>
        val selection: InfoSelection
        runBlocking {
            val dao=db.musicInfoDiscoveryDao(); dao.insert(rows); dao.addScope(rows.map { InfoScope("library",it.key) })
            dao.addArtistMembers(rows.map { InfoArtistMember("library","a","Artista A",it.key,"a") } +
                if(id=="shared") listOf(InfoArtistMember("library","b","Artista B","shared","b")) else emptyList())
            choices=dao.choices("library","a").first()
            selection=dao.selection("library").first()
        }
        db.close()
        val pages=flowOf(PagingData.from(if(id in listOf("shared","running")) listOf(InfoArtist("a","Artista A")) else emptyList()))
        val types=infoTypeCounts(rows)
        compose.setContent {
            DapTheme(darkTheme=true,baseTheme=DapBaseTheme.Graphite) {
                CompositionLocalProvider(LocalDapAccent provides Color(0xFF9CC6A4)) {
                    MusicInfoDownloadsContent(pages.collectAsLazyPagingItems(),"",{},InfoQueue(currentArtist="Artista A"),
                        InfoProgress(rows.size,rows.size,0),
                        if(id in listOf("queued","legacy-queued","running")) InfoProgress(3,0,0,3) else InfoProgress(0,0,0),
                        selection,false,false,{flowOf(choices)},{},{},{},{},{},{_,_->calls+="row"},{_,chosen->calls+="kind:$chosen"},{},
                        types=types,scope="Toda la biblioteca · 4.938 canciones",waits=InfoWaitState(nowMillis=now),checkedAt=now,
                        initiallyAdvanced=id in listOf("shared","running"),
                        undo=if(id=="clear") InfoUndo("clear","CLEAR",now+5000,156,0) else null,
                        onUndo={calls+="undo"})
                }
            }
        }
        compose.waitForIdle()
        if(id=="running") compose.onAllNodesWithText("Artista A",useUnmergedTree=true)[0].performClick()
        save(id)
    }
    private fun save(id: String) {
        val bitmap=com.lostf1sh.pixelplayeross.ui.glass.B42Renders.screen(compose)
        assertThat(bitmap.width).isEqualTo(720); assertThat(bitmap.height).isEqualTo(1610)
        val dir=File(System.getenv("B52_FIX1_RENDERS") ?: "/home/ale/proyects/honor-dap-workspace/analysis/audit-2026-09-27/batches/B52-fix1-gates/png-after")
        dir.mkdirs(); File(dir,"$id.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
    }
    @Test fun `owner saved choices are visible in the type and clear action`(){
        show("owner")
        compose.onNodeWithContentDescription("Elegir todo de Fotos de artistas").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.ToggleableState, androidx.compose.ui.state.ToggleableState.Indeterminate))
        compose.onNodeWithText("156 guardadas elegidas",substring=true,useUnmergedTree=true).assertExists()
        compose.onNodeWithText("Quitar selección").assertIsDisplayed()
    }
    @Test fun `queued kinds keep a visible checked choice and say queued`(){
        show("queued")
        compose.onNodeWithContentDescription("Elegir todo de Letras").assertIsOn()
        compose.onAllNodesWithText("3 en cola",substring=true,useUnmergedTree=true).assertCountEquals(1)
        compose.onNodeWithContentDescription("Elegir todo de Letras").performClick()
        assertThat(calls).contains("kind:false")
    }
    @Test fun `all saved uses the existing saved glyph and an explicit label`(){
        show("saved")
        compose.onNodeWithContentDescription("Letras, Todo guardado",useUnmergedTree=true).assertExists()
        compose.onNodeWithText("Todo guardado",substring=true,useUnmergedTree=true).assertExists()
        compose.onNodeWithTag("info-bottom-panel").assertDoesNotExist()
    }
    @Test fun `clear has its own Undo message instead of a deletion message`(){
        show("clear")
        compose.onNodeWithText("Selección quitada",substring=true).assertIsDisplayed()
        compose.onNodeWithText("Deshacer").performClick()
        assertThat(calls).contains("undo")
    }
    @Test fun `artist explains shared membership alongside a partial checkbox`(){
        show("shared")
        compose.onNodeWithContentDescription("Elegir todo de Artista A").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.ToggleableState, androidx.compose.ui.state.ToggleableState.Indeterminate))
        compose.onNodeWithText("1 compartido con otros",substring=true,useUnmergedTree=true).assertExists()
    }
    @Test fun `lyrics counts downloadable and local files separately without an ambiguous denominator`(){
        show("local")
        compose.onNodeWithText("3 por guardar · 4.899 en el teléfono",substring=true,useUnmergedTree=true).assertExists()
        compose.onNodeWithText("3 de 4.938",substring=true,useUnmergedTree=true).assertDoesNotExist()
    }
    @Test fun `WiFi resource keeps its capitalization`(){
        show("wifi")
        compose.onNodeWithText("Wi-Fi",substring=true,useUnmergedTree=true).assertExists()
        compose.onNodeWithText("wi-fi",substring=true,useUnmergedTree=true).assertDoesNotExist()
    }
    @Test fun `a downloading row explains that it will finish`(){
        show("running")
        compose.onNodeWithText("Terminará esta descarga",substring=true,useUnmergedTree=true).assertExists()
    }
    @Test fun `a queue retained after clear offers an explicit remove action without a false choice`(){
        show("legacy-queued")
        compose.onNodeWithText("Quitar de la cola").assertIsDisplayed().performClick()
        assertThat(calls).contains("kind:false")
        compose.onNodeWithText("Descargar",useUnmergedTree=true).assertExists()
        compose.onNodeWithContentDescription("Elegir todo de Letras").assertDoesNotExist()
    }

}
