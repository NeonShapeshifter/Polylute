package com.lostf1sh.pixelplayeross.presentation.screens

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.database.FolderSongRow
import com.lostf1sh.pixelplayeross.data.model.*
import com.lostf1sh.pixelplayeross.data.repository.FolderTreeBuilder
import com.lostf1sh.pixelplayeross.presentation.viewmodel.StablePlayerState
import com.lostf1sh.pixelplayeross.ui.ds.*
import com.lostf1sh.pixelplayeross.ui.glass.B42Renders
import java.io.File
import kotlinx.collections.immutable.toImmutableList
import org.junit.Before
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "es-w360dp-h805dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class B51FoldersRenderTest {
    @get:Rule val compose = createComposeRule()
    @Before fun provideModalAuthority() {
        val prefs = io.mockk.mockk<com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository>()
        io.mockk.every { prefs.backNavigationModeFlow } returns kotlinx.coroutines.flow.flowOf(com.lostf1sh.pixelplayeross.data.preferences.BackNavigationMode.DEFAULT)
        val point = object : com.lostf1sh.pixelplayeross.di.ModalInputEntryPoint {
            override fun safetyVolumeController() = io.mockk.mockk<com.lostf1sh.pixelplayeross.data.volume.SafetyVolumeController>(relaxed = true)
            override fun userPreferencesRepository() = prefs
        }
        io.mockk.mockkStatic(dagger.hilt.android.EntryPointAccessors::class)
        io.mockk.every {
            dagger.hilt.android.EntryPointAccessors.fromApplication(
                androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>(),
                com.lostf1sh.pixelplayeross.di.ModalInputEntryPoint::class.java)
        } returns point
    }
    @After fun restoreModalAuthority() = io.mockk.unmockkStatic(dagger.hilt.android.EntryPointAccessors::class)
    private val root = "/storage/1234-5678"
    private val rows = listOf(
        FolderSongRow(1, "$root/Music/Rock/Álbum A", "Primera canción", null),
        FolderSongRow(2, "$root/Music/Rock/Álbum A", "Segunda canción", null),
        FolderSongRow(3, "$root/Music/Rock/Álbum B", "Otra canción", null)
    )
    private val folders = FolderTreeBuilder().buildCompactTree(rows, setOf(root), setOf("$root/Music")).toImmutableList()
    private val flat = mutableStateOf(false)
    private val current = mutableStateOf<MusicFolder?>(null)
    private var longPressed: String? = null
    private fun show(sd: Boolean = false) {
        compose.setContent {
            DapTheme(darkTheme = true) {
                Surface(Modifier.fillMaxSize(), color = Dap.background) {
                    Column(Modifier.fillMaxSize().padding(top = 24.dp)) {
                        Text("Biblioteca", style = DapType.Title, color = Dap.primaryText, modifier = Modifier.padding(16.dp))
                        LibraryFoldersTab(
                            folders = folders, currentFolder = current.value, isLoading = false,
                            onNavigateBack = { current.value = null },
                            onFolderClick = { path -> current.value = folderBreadcrumb(folders, path).last() },
                            onFolderAsPlaylistClick = { longPressed = it.path },
                            onPlaySong = { _, _ -> }, stablePlayerState = StablePlayerState(), bottomBarHeight = 0.dp,
                            onMoreOptionsClick = {}, isRefreshing = false, onRefresh = {},
                            flatList = flat.value, onFlatListChange = { flat.value = it },
                            showSourceSwitch = sd, source = FolderSource.SD_CARD
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }
    private fun export(name: String) {
        val image = B42Renders.screen(compose)
        assertThat(image.width).isEqualTo(720)
        assertThat(image.height).isEqualTo(1610)
        val dir = File(System.getenv("B51_RENDERS") ?: "../../../analysis/audit-2026-09-27/batches/B51-gates/png-after").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun `compact tree starts at included music root and one volume hides source switch`() {
        show()
        export("compact-tree")
        compose.onNodeWithText("Music / Rock").assertIsDisplayed()
        compose.onNodeWithText("Árbol").assertIsDisplayed()
        compose.onAllNodesWithText("Tarjeta SD").assertCountEquals(0)
    }
    @Test fun `two volume switch is visible directly in the tab`() {
        show(sd = true)
        export("sd-switch")
        compose.onNodeWithText("Tarjeta SD").assertIsDisplayed()
    }
    @Test fun `list shows direct song counts relative paths search and opens songs`() {
        flat.value = true
        show()
        export("flat-list")
        compose.onNodeWithText("Rock/Álbum A").assertIsDisplayed()
        compose.onNodeWithText("Rock/Álbum B").assertIsDisplayed()
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("Álbum A")
        compose.onNodeWithText("Álbum B").assertDoesNotExist()
        compose.onAllNodes(hasSetTextAction())[0].performTextClearance()
        compose.onNodeWithText("Álbum A").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Primera canción").assertIsDisplayed()
        export("folder-songs")
    }
    @Test fun `tree and list expose the same folder long press action`() {
        show()
        compose.onNodeWithText("Music / Rock").performTouchInput { longClick() }
        compose.onNodeWithText("Abrir carpeta").assertIsDisplayed()
        compose.onNodeWithText("Acciones de la carpeta").performClick()
        assertThat(longPressed).isEqualTo("$root/Music/Rock")
        compose.onNodeWithText("Lista").performClick()
        compose.onNodeWithText("Álbum A").performTouchInput { longClick() }
        compose.onNodeWithText("Abrir carpeta").assertIsDisplayed()
        export("folder-actions")
        compose.onNodeWithText("Acciones de la carpeta").performClick()
        assertThat(longPressed).isEqualTo("$root/Music/Rock/Álbum A")
    }
}
