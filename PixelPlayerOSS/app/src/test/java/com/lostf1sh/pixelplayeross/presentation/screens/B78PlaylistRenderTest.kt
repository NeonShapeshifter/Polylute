package com.lostf1sh.pixelplayeross.presentation.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.isRoot
import androidx.activity.OnBackPressedDispatcher
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.core.app.ApplicationProvider
import com.lostf1sh.pixelplayeross.R
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.rememberNavController
import com.lostf1sh.pixelplayeross.data.model.Playlist
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.presentation.navigation.DapBackAuthority
import com.lostf1sh.pixelplayeross.presentation.navigation.LocalDapBackNavigation
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlaylistUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlaylistSongsOrderMode
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlaylistViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.StablePlayerState
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.glass.B41Renders
import com.lostf1sh.pixelplayeross.ui.glass.B42GlassApp
import com.lostf1sh.pixelplayeross.ui.glass.B42HiltShim
import com.lostf1sh.pixelplayeross.ui.glass.B42PlainApp
import com.lostf1sh.pixelplayeross.ui.glass.B42Renders
import com.lostf1sh.pixelplayeross.ui.glass.B42Setup
import com.lostf1sh.pixelplayeross.ui.glass.rememberB42Scene
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "en-w360dp-h805dp-xhdpi")
open class B78PlaylistRenderTest {
    @get:Rule(order = 0)
    val hilt = B42HiltShim()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    protected open val width = 360
    private val songs = (1..50).map { n ->
        Song.emptySong().copy(
            id = "fixture-$n",
            title = "Signal " + n.toString().padStart(2, '0'),
            artist = "Studio Fixture",
            album = "Synthetic Sessions",
            duration = 203_000L,
        )
    }

    private val player = mockk<PlayerViewModel>(relaxed = true).also {
        every { it.stablePlayerState } returns MutableStateFlow(StablePlayerState())
        every { it.favoriteSongIds } returns MutableStateFlow(emptySet())
        every { it.navBarCompactMode } returns MutableStateFlow(false)
        every { it.selectedSongForInfo } returns MutableStateFlow(null)
    }
    private val playlists = mockk<PlaylistViewModel>(relaxed = true).also {
        every { it.uiState } returns MutableStateFlow(PlaylistUiState(
            currentPlaylistDetails = Playlist(id = "fixture", name = "Evening Signals", songIds = songs.map { it.id }),
            currentPlaylistSongs = songs,
            playlistSongsOrderMode = PlaylistSongsOrderMode.Manual,
        ))
    }

    @Composable
    private fun Stage(glass: Boolean, content: @Composable () -> Unit) {
        val back = remember { DapBackAuthority(OnBackPressedDispatcher()) }
        val theme = DapBaseTheme.Graphite
        CompositionLocalProvider(LocalDapBackNavigation provides back) {
            if (glass) {
                val setup = remember { B42Setup(B41Renders.veryDark(), theme) }
                B42GlassApp(setup, rememberB42Scene(setup, 1f), content = content)
            } else {
                B42PlainApp(theme, theme.palette, content = content)
            }
        }
    }

    private fun captures(glass: Boolean) {
        val target = File(System.getenv("B78_RENDERS") ?: "build/test-artifacts/B78", "after").apply { mkdirs() }
        val prefix = "${width}dp-${if (glass) "cover-glass" else "graphite"}"
        fun save(page: String, includeMenu: Boolean = false) {
            val bitmap = B42Renders.screen(compose)
            if (includeMenu) {
                val popup = compose.onNode(isPopup(), useUnmergedTree = true).fetchSemanticsNode()
                val popupView = (popup.root as ViewRootForTest).view
                val activity = compose.onAllNodes(isRoot()).fetchSemanticsNodes()
                    .first { it.root !== popup.root }
                val activityView = (activity.root as ViewRootForTest).view
                compose.runOnIdle {
                    val popupLocation = IntArray(2).also(popupView::getLocationOnScreen)
                    val activityLocation = IntArray(2).also(activityView::getLocationOnScreen)
                    Canvas(bitmap).apply {
                        translate(
                            (popupLocation[0] - activityLocation[0]).toFloat(),
                            (popupLocation[1] - activityLocation[1]).toFloat(),
                        )
                        popupView.draw(this)
                    }
                }
            }
            File(target, "$prefix-$page.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        }

        compose.setContent {
            Stage(glass) {
                PlaylistDetailScreen(
                    playlistId = "fixture", onBackClick = {}, onDeletePlayListClick = {},
                    playerViewModel = player, playlistViewModel = playlists, navController = rememberNavController(),
                )
            }
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        fun openMenu() = compose.onNodeWithContentDescription(context.getString(R.string.presentation_batch_b_more_options)).performClick()

        save("page")
        openMenu()
        save("menu", includeMenu = true)
        compose.onNodeWithText("Edit songs").performClick()
        save("edit")
        compose.onNodeWithText("Done").performClick()
        openMenu()
        compose.onNodeWithText("Sort").performClick()
        save("sort")
    }

    @Test
    fun graphite() = captures(glass = false)

    @Test
    fun coverGlass() = captures(glass = true)
}

@Config(sdk = [35], application = android.app.Application::class, qualifiers = "en-w411dp-h891dp-xhdpi")
class B78PlaylistRender411Test : B78PlaylistRenderTest() {
    override val width = 411
}
