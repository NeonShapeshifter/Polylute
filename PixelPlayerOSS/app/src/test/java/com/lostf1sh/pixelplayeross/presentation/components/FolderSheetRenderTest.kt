package com.lostf1sh.pixelplayeross.presentation.components

import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DirectoryEntry
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import java.io.File
import kotlinx.collections.immutable.persistentListOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B39-A2: the folders sheet is in the listener's language (title, hint, "Scanning…", the song counts with their plurals, the in/out state),
 * and its back chevron and "Actualizar" sit below the status bar, as the picker of the folders that are in the library (B39-A9).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xhdpi")
class FolderSheetRenderTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun s(id: Int) = context.getString(id)
    private var hostView: android.view.View? = null
    private val toggled = mutableListOf<String>()
    private val statusBarDp = 24
    private val px = 2f

    private val root = File("/storage/emulated/0")

    private val children = persistentListOf(
        DirectoryEntry(File(root, "Scanning"), -1, -1, "/storage/emulated/0/Scanning"),
        DirectoryEntry(File(root, "One"), 1, 1, "/storage/emulated/0/One"),
        DirectoryEntry(File(root, "Five"), 5, 5, "/storage/emulated/0/Five", isBlocked = true),
        DirectoryEntry(File(root, "Many"), 150, 150, "/storage/emulated/0/Many"),
    )

    private fun show(entries: List<DirectoryEntry> = children, insets: Boolean = false) {
        compose.setContent {
            val view = LocalView.current
            SideEffect { hostView = view }
            DapTheme(baseTheme = DapBaseTheme.Graphite, accent = DapAccent.Sage) {
                FileExplorerContent(
                    currentPath = root,
                    directoryChildren = kotlinx.collections.immutable.persistentListOf<DirectoryEntry>().addAll(entries),
                    availableStorages = persistentListOf(),
                    selectedStorageIndex = 0,
                    isLoading = false,
                    isPriming = false,
                    isReady = true,
                    isCurrentDirectoryResolved = true,
                    isAtRoot = true,
                    rootDirectory = root,
                    onNavigateTo = {},
                    onNavigateUp = {},
                    onNavigateHome = {},
                    onToggleAllowed = { toggled += it.name },
                    onRefresh = {},
                    onStorageSelected = {},
                    onDone = {},
                    onDismiss = {},
                )
            }
        }
        compose.waitForIdle()
        if (insets) {
            val statusPx = (statusBarDp * px).toInt()
            compose.runOnUiThread {
                ViewCompat.dispatchApplyWindowInsets(
                    hostView!!,
                    WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, statusPx, 0, 0)).build(),
                )
            }
            compose.waitForIdle()
        }
    }

    @Test
    fun `the sheet reads in English as the picker of the folders in the library`() {
        show()
        compose.onNodeWithText("Folders in the library").assertIsDisplayed()
        compose.onNodeWithText(s(R.string.library_folders_hint)).assertIsDisplayed()
        compose.onNodeWithText("Scanning…").assertIsDisplayed()
        compose.onNodeWithText("1 song").assertIsDisplayed()
        compose.onNodeWithText("5 songs").assertIsDisplayed()
        compose.onNodeWithText("99+ songs").assertIsDisplayed()
        assertThat(compose.onAllNodesWithText("In library").fetchSemanticsNodes()).hasSize(3)
        assertThat(compose.onAllNodesWithText("Not in library").fetchSemanticsNodes()).hasSize(1)
    }

    @Test
    @Config(sdk = [35], application = android.app.Application::class, qualifiers = "es-w360dp-h805dp-xhdpi")
    fun `the sheet reads in Spanish, every word of it`() {
        show()
        compose.onNodeWithText("Carpetas en la biblioteca").assertIsDisplayed()
        compose.onNodeWithText(s(R.string.library_folders_hint)).assertIsDisplayed()
        compose.onNodeWithText("Escaneando…").assertIsDisplayed()
        compose.onNodeWithText("1 canción").assertIsDisplayed()
        compose.onNodeWithText("5 canciones").assertIsDisplayed()
        compose.onNodeWithText("Más de 99 canciones").assertIsDisplayed()
        assertThat(compose.onAllNodesWithText("En la biblioteca").fetchSemanticsNodes()).hasSize(3)
        // nothing of it is left in English
        listOf("Folders in the library", "Scanning…", "1 song", "5 songs", "99+ songs", "Everything is included by default", "Refresh").forEach {
            compose.onNodeWithText(it, substring = true).assertDoesNotExist()
        }
        compose.onNodeWithText("Actualizar").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [35], application = android.app.Application::class, qualifiers = "sv-w360dp-h805dp-xhdpi")
    fun `the sheet reads in Swedish`() {
        show()
        compose.onNodeWithText("Mappar i biblioteket").assertIsDisplayed()
        compose.onNodeWithText("Söker igenom…").assertIsDisplayed()
        compose.onNodeWithText("1 låt").assertIsDisplayed()
        compose.onNodeWithText("5 låtar").assertIsDisplayed()
        compose.onNodeWithText("Över 99 låtar").assertIsDisplayed()
        compose.onNodeWithText("Folders in the library").assertDoesNotExist()
    }

    @Test
    fun `the back chevron and the refresh action sit below the status bar`() {
        show(insets = true)
        val statusPx = statusBarDp * px
        val back = compose.onNodeWithContentDescription(s(R.string.auth_cd_back)).fetchSemanticsNode().boundsInRoot
        val refresh = compose.onNodeWithText(s(R.string.presentation_batch_g_file_explorer_cd_refresh)).fetchSemanticsNode().boundsInRoot
        assertThat(back.top).isAtLeast(statusPx)
        assertThat(refresh.top).isAtLeast(statusPx)
    }

    @Test
    fun `ticking a row asks for that folder to be included or left out`() {
        show()
        // each row has one box; the third row is "Five", the folder that is out of the library
        val boxes = compose.onAllNodes(androidx.compose.ui.test.isToggleable())
        assertThat(boxes.fetchSemanticsNodes()).hasSize(4)
        boxes[2].performClick()
        assertThat(toggled).containsExactly("Five")
    }
}
