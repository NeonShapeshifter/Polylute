package com.lostf1sh.pixelplayeross.presentation.screens

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.database.FolderSongRow
import com.lostf1sh.pixelplayeross.data.model.FolderSource
import com.lostf1sh.pixelplayeross.data.repository.FolderTreeBuilder
import com.lostf1sh.pixelplayeross.presentation.viewmodel.StablePlayerState
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import com.lostf1sh.pixelplayeross.ui.glass.B42Renders
import com.lostf1sh.pixelplayeross.utils.*
import io.mockk.*
import java.io.File
import kotlinx.collections.immutable.toImmutableList
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Runs unchanged against HEAD. Its assertions describe the new observable behavior. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "es-w360dp-h805dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class B51ExistingBehaviorTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val root = "/storage/emulated/0"
    private fun tree(): List<com.lostf1sh.pixelplayeross.data.model.MusicFolder> {
        mockkObject(StorageUtils)
        every { StorageUtils.getAvailableStorages(any()) } returns listOf(StorageInfo(File(root), "Internal", StorageType.INTERNAL, false))
        every { StorageUtils.getSdCardStorage(any()) } returns null
        return FolderTreeBuilder().buildFolderTree(
            listOf(FolderSongRow(1, "$root/Music/Rock/Album A", "Uno", null), FolderSongRow(2, "$root/Music/Rock/Album B", "Dos", null)),
            setOf("$root/Music"), emptySet(), false, FolderSource.INTERNAL, context
        )
    }
    @After fun cleanup() { unmockkObject(StorageUtils) }
    @Test fun `tree begins at the included music root and collapses empty ancestors`() {
        val folders = tree()
        assertThat(folders.map { it.name }).containsExactly("Music / Rock")
        assertThat(folders.single().subFolders.map { it.name }).containsExactly("Album A", "Album B")
    }
    @Test fun `folder tab has a visible Tree List choice and folder search`() {
        val folders = tree().toImmutableList()
        compose.setContent {
            DapTheme(darkTheme = true) {
                Surface(Modifier.fillMaxSize(), color = Dap.background) {
                    LibraryFoldersTab(
                        folders, null, false, {}, {}, {}, { _, _ -> }, StablePlayerState(), 0.dp,
                        onMoreOptionsClick = {}, isRefreshing = false, onRefresh = {}
                    )
                }
            }
        }
        val bitmap = B42Renders.screen(compose)
        val dir = File(System.getenv("B51_RENDERS") ?: "../../../analysis/audit-2026-09-27/batches/B51-gates/png-after").apply { mkdirs() }
        File(dir, "tree.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        compose.onNodeWithText("Árbol").assertIsDisplayed()
        compose.onNodeWithText("Lista").assertIsDisplayed()
        compose.onNodeWithText("Buscar carpetas").assertIsDisplayed()
    }
}
