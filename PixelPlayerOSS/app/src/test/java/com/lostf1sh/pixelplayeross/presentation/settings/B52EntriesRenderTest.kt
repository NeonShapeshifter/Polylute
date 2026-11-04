package com.lostf1sh.pixelplayeross.presentation.settings

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.musicinfo.*
import com.lostf1sh.pixelplayeross.data.offline.InfoUndo
import com.lostf1sh.pixelplayeross.data.worker.SyncProgress
import com.lostf1sh.pixelplayeross.presentation.components.*
import com.lostf1sh.pixelplayeross.presentation.viewmodel.*
import com.lostf1sh.pixelplayeross.ui.ds.*
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The production entries/About/notices in the same neutral album and Library context as the HTML. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "es-w360dp-h805dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class B52EntriesRenderTest {
    @get:Rule val compose = createComposeRule()
    private val state = mutableStateOf("A1")
    private val usedBytes = mutableStateOf(14_600_000L)
    private val calls = mutableListOf<String>()
    private var failure = false
    private val actions = object : LibrarySettingsActions {
        override fun onMinSongDurationChange(value:Int) {}
        override fun onMinTracksPerAlbumChange(value:Int) {}
        override fun onAlbumArtCacheLimitMbChange(value:Int) {}
        override fun onMusicInfoCoversChange(value:Boolean) { calls += "covers:$value" }
        override fun onMusicInfoArtistPhotosChange(value:Boolean) { calls += "photos:$value" }
        override fun onMusicInfoDescriptionsChange(value:Boolean) { calls += "texts:$value" }
        override fun onMusicInfoCacheLimitChange(megabytes:Int) {}
        override fun onDeleteMusicInfo(onDone:(Long)->Unit) { calls += "delete"; onDone(usedBytes.value); usedBytes.value=0 }
        override fun onDeleteMusicInfoResult(onDone:(Result<Long>)->Unit) {
            calls += "delete"
            if(failure) onDone(Result.failure(java.io.IOException("fake disk failure")))
            else { val bytes=usedBytes.value; usedBytes.value=0; onDone(Result.success(bytes)) }
        }
        override fun onOpenLibraryFolders() {}
        override fun onFindFolders() {}
        override fun onOpenArtistSettings() {}
        override fun onOpenDuplicates() {}
        override fun onRefreshLibrary() {}
        override fun onFullRescan() {}
    }
    private val text = "[Texto de ejemplo] Mareas es el segundo álbum del grupo. Se grabó en una antigua lonja junto al puerto durante un invierno, con la mayoría de las voces registradas en una sola toma. El disco alterna canciones de taberna con piezas lentas para coro y guitarra, y cierra con una versión instrumental de su primer sencillo. La portada original es una fotografía de redes de pesca secándose al sol, tomada por un miembro del grupo."
    private val about = MusicInfoAboutState(Description(text,"Mareas","https://es.wikipedia.org/wiki/Ejemplo","es"),ArtistPhoto("", "Autor de ejemplo","CC BY-SA 4.0","https://creativecommons.org/licenses/by-sa/4.0/","https://commons.wikimedia.org/wiki/File:Ejemplo.jpg"))
    @Composable private fun Toolbar(title:String="", menu:Boolean=false) {
        Row(Modifier.fillMaxWidth().padding(start=4.dp,end=8.dp,top=28.dp,bottom=4.dp),verticalAlignment=Alignment.CenterVertically) {
            IconButton({}) { InfoGlyph("back",modifier=Modifier.size(22.dp)) }
            Text(title,Modifier.weight(1f),style=DapType.Heading,color=Dap.primaryText)
            if(menu) MusicInfoPageMenu({calls += "prepare"},{calls += "refresh"},{calls += "delete-page"})
        }
    }
    @Composable private fun AlbumContext(id:String) {
        Column(Modifier.fillMaxSize()) {
            Toolbar(menu=id=="A3" || id=="A5" || id=="E5")
            Column(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                if(id.startsWith("F")) Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.size(88.dp).clip(RoundedCornerShape(12.dp)).background(Dap.artPlaceholderHigh))
                    Column { Text("Mareas",style=DapType.Heading,color=Dap.primaryText); Text("Coro del Puerto · 2019",style=DapType.Caption,color=Dap.secondaryText) }
                } else {
                    Box(Modifier.align(Alignment.CenterHorizontally).size(208.dp).clip(RoundedCornerShape(12.dp)).background(Dap.artPlaceholderHigh))
                    Column(Modifier.align(Alignment.CenterHorizontally),horizontalAlignment=Alignment.CenterHorizontally) { Text("Mareas",style=DapType.Heading,color=Dap.primaryText); Text("Coro del Puerto · 2019 · 11 canciones",style=DapType.Caption,color=Dap.secondaryText) }
                }
                if(id.startsWith("F")) MusicInfoAbout(about,mountInputBridge=false)
                Column {
                    listOf("Sal y cobre","Hilo","Antes del alba","Puerto viejo","Red y aguja").forEachIndexed { i,t ->
                        HorizontalDivider(color=Dap.divider)
                        Row(Modifier.fillMaxWidth().height(52.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                            Text("0${i+1}",Modifier.width(20.dp),style=DapType.ValueSmall,color=Dap.secondaryText)
                            Text(t,style=DapType.RowSmall,color=Dap.primaryText)
                        }
                    }
                }
            }
        }
    }
    private fun show() {
        compose.setContent {
            val id=state.value
            key(id) {
                DapTheme(darkTheme=true) { CompositionLocalProvider(LocalDapAccent provides Color(0xFF9CC6A4)) {
                    Surface(Modifier.fillMaxSize(),color=Dap.background) {
                        Box(Modifier.fillMaxSize()) {
                            if(id in listOf("A1","A2","E3","E4")) {
                                Column(Modifier.fillMaxSize()) {
                                    Toolbar("Biblioteca")
                                    Column(Modifier.verticalScroll(rememberScrollState())) {
                                        if(id.startsWith("A")) Row(Modifier.fillMaxWidth().padding(16.dp),horizontalArrangement=Arrangement.spacedBy(14.dp)) { InfoGlyph("folder",modifier=Modifier.size(22.dp),tint=Dap.disabledText); Column { Text("Pistas duplicadas",style=DapType.SettingLabel,color=Dap.disabledText); Text("Analiza tu biblioteca y muestra las pistas repetidas.",style=DapType.Note,color=Dap.disabledText) } }
                                        val on=id=="A2" || id.startsWith("E")
                                        val library=LibrarySettingsState.from(SettingsUiState(musicInfoCoversEnabled=on,musicInfoArtistPhotosEnabled=on,musicInfoDescriptionsEnabled=on,musicInfoUsedBytes=if(id=="E4") 0 else usedBytes.value),false,SyncProgress())
                                        MusicInfoSettingsSection(library,actions,{calls += "open"},mountInputBridge=false)
                                    }
                                }
                                if(id=="E4") InfoSnack("Se borraron 14,6 MB",Modifier.align(Alignment.BottomCenter))
                            } else if(id=="A4") {
                                Column { Toolbar("12 elegidas"); listOf("Sal y cobre","Hilo","Faro","Noches cortas").forEach { t -> Row(Modifier.padding(horizontal=16.dp).height(52.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) { InfoGlyph("saved",modifier=Modifier.size(24.dp),tint=LocalDapAccent.current); Text(t,style=DapType.RowSmall,color=Dap.primaryText) } } }
                                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha=.6f)))
                                Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth(),shape=RoundedCornerShape(topStart=24.dp,topEnd=24.dp),color=Dap.surface) {
                                    Column(Modifier.padding(top=10.dp,bottom=24.dp)) {
                                        Box(Modifier.align(Alignment.CenterHorizontally).size(32.dp,4.dp).background(Dap.disabledText,RoundedCornerShape(2.dp)))
                                        Text("12 canciones elegidas",Modifier.padding(start=20.dp,top=14.dp,bottom=10.dp),style=DapType.Row,color=Dap.primaryText)
                                        MusicInfoSelectionEntry { calls += "selection-entry" }
                                        listOf("Reproducir a continuación","Añadir a la cola","Añadir a una lista").forEach { Text(it,Modifier.padding(horizontal=50.dp).height(48.dp).wrapContentHeight(),style=DapType.RowSmall,color=Dap.primaryText) }
                                    }
                                }
                            } else {
                                AlbumContext(id)
                                if(id in listOf("A5","E5")) MusicInfoPageNotice(MusicInfoActionResult(if(id=="A5") MusicInfoPageAction.REFRESH else MusicInfoPageAction.DELETE,if(id=="A5") MusicInfoActionStatus.FAILURE else MusicInfoActionStatus.SUCCESS,"Mareas",if(id=="E5") InfoUndo("u","DELETE",Long.MAX_VALUE,2,0) else null),{calls += "retry"},{calls += "undo"},Modifier.align(Alignment.BottomCenter))
                            }
                        }
                    }
                } }
            }
        }
        compose.waitForIdle()
    }
    private fun save(id:String) {
        val b=B52Renders.screen(compose)
        assertThat(b.width).isEqualTo(720);assertThat(b.height).isEqualTo(1610)
        val dir=File("/home/ale/proyects/honor-dap-workspace/analysis/audit-2026-09-27/batches/B52-gates/png-after");dir.mkdirs()
        File(dir,"$id.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) };b.recycle()
    }
    @Test fun `entries settings about credits and page results render in Spanish`() {
        show()
        for(id in listOf("A1","A2","A3","A4","A5","E3","E4","E5","F1","F2","F3")) {
            compose.runOnIdle { state.value=id };compose.waitForIdle()
            if(id=="A3") compose.onNodeWithContentDescription("Más opciones").performClick()
            if(id=="E3") compose.onNodeWithText("Borrar toda la información descargada").performScrollTo().performClick()
            if(id=="F2") compose.onNodeWithText("Leer más").performClick()
            if(id=="F3") compose.onNodeWithContentDescription("Créditos y licencias").performClick()
            compose.waitForIdle();save(id)
        }
    }
    @Test fun `settings confirms before delete and failure can retry then updates its size`() {
        show(); failure=true
        compose.onNodeWithText("Borrar toda la información descargada").performScrollTo().performClick()
        assertThat(calls).isEmpty()
        compose.onNodeWithText("Borrar").performClick()
        compose.onNodeWithText("No se pudo borrar la información.").assertExists()
        assertThat(calls).containsExactly("delete")
        failure=false
        compose.onNodeWithText("Reintentar").performScrollTo().performClick()
        compose.onNodeWithText("Nada guardado").assertExists()
        assertThat(calls).containsExactly("delete","delete")
    }
    @Test fun `manual entry stays enabled when every automatic option is off`() {
        show(); compose.onNodeWithText("Descargar información musical").performClick()
        assertThat(calls).containsExactly("open")
        compose.onAllNodes(isToggleable()).assertCountEquals(3)
        compose.onAllNodes(isToggleable()).fetchSemanticsNodes().forEach { assertThat(it.config[androidx.compose.ui.semantics.SemanticsProperties.ToggleableState]).isEqualTo(androidx.compose.ui.state.ToggleableState.Off) }
    }
}
