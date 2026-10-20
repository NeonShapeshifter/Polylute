package com.lostf1sh.pixelplayeross.presentation

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.worker.SyncProgress
import com.lostf1sh.pixelplayeross.presentation.components.QueuePlaylistSongItem
import com.lostf1sh.pixelplayeross.presentation.screens.RefreshLibraryItem
import com.lostf1sh.pixelplayeross.ui.ds.*
import java.io.File
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "es-w360dp-h805dp-xxhdpi")
open class B26VisualRenderTest {
    @get:Rule val compose = createComposeRule()
    protected open val baseTheme = DapBaseTheme.Graphite
    protected open val scale = 1f
    private var accent by mutableStateOf(DapAccent.Sage)
    private fun content(body: @Composable () -> Unit) = compose.setContent {
        DapTheme(baseTheme = baseTheme, accent = accent) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                Column(Modifier.fillMaxWidth().background(Dap.background).padding(20.dp)) { body() }
            }
        }
    }
    private fun save(name: String) {
        val root = File("/tmp/b26-work/renders-after").apply { mkdirs() }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(root, "${javaClass.simpleName}-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun brand_keeps_proportions_and_updates_with_the_selected_accent() {
        content { DapBrandHeader(trailing = { DapIconButton(DapIconKind.Gear, "Settings", {}) }) }
        val bounds = compose.onNodeWithContentDescription("POLYLUTE").fetchSemanticsNode().boundsInRoot
        assertEquals(1048f/240f, bounds.width/bounds.height, 0.02f)
        // B32-Q1: 170 dp (15 % under the 200 dp of B26); xxhdpi is 3 px per dp
        assertTrue(bounds.width / 3f in 160f..171f)
        val before = compose.onNodeWithContentDescription("POLYLUTE").captureToImage().asAndroidBitmap()
        compose.runOnIdle { accent = DapAccent.Red }
        compose.waitForIdle()
        val after = compose.onNodeWithContentDescription("POLYLUTE").captureToImage().asAndroidBitmap()
        assertFalse("selected accent must tint the vector", before.sameAs(after))
        RenderChecks.assertInsideWindow(compose, "brand")
        save("brand")
    }
    @Test fun queue_has_left_handle_adjacent_art_and_aligned_active_and_inactive_titles() {
        var played = 0
        var removed = 0
        fun song(title: String) = Song.emptySong().copy(id = title, title = title, artist = "Un artista con nombre largo")
        content {
            listOf(true, false).forEach { current ->
                QueuePlaylistSongItem(onClick = { played++ }, song = song(if (current) "Activa" else "Título largo que debe truncarse sin desplazar las acciones"),
                    isCurrentSong = current, isPlaying = false, isDragging = false,
                    onRemoveClick = { removed++ },
                    dragHandle = { IconButton(onClick = {}, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.DragIndicator, "Reordenar", tint = Dap.disabledText) } },
                    isReorderModeEnabled = false, onMoreOptionsClick = {}, isDragHandleVisible = !current,
                    isRemoveButtonVisible = false, isFromPlaylist = true)
            }
        }
        val handle = compose.onNodeWithContentDescription("Reordenar").fetchSemanticsNode().boundsInRoot
        val active = compose.onNodeWithText("Activa", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val inactive = compose.onNodeWithText("Título largo", substring = true, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(active.left, inactive.left, 1f)
        assertTrue("handle must lead the row", handle.left < inactive.left)
        assertTrue("no central gap after handle/art", inactive.left - handle.right <= 68 * 3f)
        compose.onNodeWithText("Activa").performClick()
        assertEquals(1, played)
        assertEquals(0, removed)
        RenderChecks.assertInsideWindow(compose, "queue")
        save("queue")
    }
    @Test fun collection_rows_keep_their_existing_actions_and_fit() {
        val collection = com.lostf1sh.pixelplayeross.data.collections.SmartCollection(id = 7, name = "Mi colección")
        val vm = io.mockk.mockk<com.lostf1sh.pixelplayeross.presentation.viewmodel.SmartCollectionsViewModel>(relaxed = true)
        io.mockk.every { vm.uiState } returns kotlinx.coroutines.flow.MutableStateFlow(
            com.lostf1sh.pixelplayeross.presentation.viewmodel.SmartCollectionsViewModel.UiState(
                collections = listOf(collection), isLoading = false))
        val player = io.mockk.mockk<com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel>(relaxed = true)
        io.mockk.every { player.stablePlayerState } returns kotlinx.coroutines.flow.MutableStateFlow(
            com.lostf1sh.pixelplayeross.presentation.viewmodel.StablePlayerState())
        content {
            com.lostf1sh.pixelplayeross.presentation.screens.SmartCollectionsContent(
                player, vm, modifier = Modifier.height(600.dp), bottomContentPadding = 0.dp)
        }
        compose.onNodeWithText("Mi colección").performClick()
        io.mockk.verify(exactly = 1) { vm.open(7) }
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        compose.onNodeWithContentDescription(context.getString(com.lostf1sh.pixelplayeross.R.string.smart_collections_cd_duplicate)).performClick()
        compose.onNodeWithContentDescription(context.getString(com.lostf1sh.pixelplayeross.R.string.delete_action)).performClick()
        io.mockk.verify(exactly = 1) { vm.duplicate(7) }
        io.mockk.verify(exactly = 1) { vm.delete(collection) }
        RenderChecks.assertInsideWindow(compose, "collections")
        RenderChecks.assertNoCutText(compose, "collections")
        save("collections")
    }

    @Test fun completion_states_exact_catalog_scope() {
        content {
            RefreshLibraryItem(false, SyncProgress(currentCount = 4915, totalCount = 4915, isCompleted = true),
                onUpdateLibrary = {}, onFullRescan = {})
        }
        compose.onNodeWithText("Total de la biblioteca: 4915 canciones").assertExists()
        RenderChecks.assertInsideWindow(compose, "genre and catalog")
        save("genre-catalog")
    }
}
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "es-w360dp-h805dp-xxhdpi")
class B26VisualLightLargeRenderTest : B26VisualRenderTest() {
    override val baseTheme = DapBaseTheme.Light
    override val scale = 1.5f
}
