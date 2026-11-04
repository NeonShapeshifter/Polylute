package com.lostf1sh.pixelplayeross.presentation.components.player

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.preferences.FullPlayerLoadingTweaks
import com.lostf1sh.pixelplayeross.presentation.components.scoped.PlayerLocateNavigationEffect
import com.lostf1sh.pixelplayeross.presentation.components.scoped.SheetMotionController
import com.lostf1sh.pixelplayeross.presentation.navigation.Screen
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerSheetState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B56 phase 4: tapping the song's title in the full player closes the player and opens Library > Songs scrolled to that song, with the locate the app already has
 * (`LibraryLocator`, route `Screen.LibraryLocate`, B32-fix1): no second path. The Songs tab's side (it takes the request and scrolls) is B32-fix1's own, unchanged
 * (`B32Fix1LocateIntegrationTest`); here: the tap asks once for the playing song, and the request becomes that one destination.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h805dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class B56TitleTapLocateTest {
    @get:Rule val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private val actions = object : FullPlayerActions by NoActions() {
        override fun onLocateSongInLibrary(songId: String) { calls += "locate:$songId" }
        override fun onOpenAlbum(albumId: Long) { calls += "album:$albumId" }
        override fun onOpenArtist(artistId: Long) { calls += "artist:$artistId" }
    }

    @Test
    fun `tapping the title asks once for the song that is playing, and the artist and the album still open theirs`() {
        val song = Song.emptySong().copy(id = "4242", title = "Fiesta Pagana", artist = "Mägo de Oz", album = "Gaia", artistId = 7, albumId = 9)
        compose.setContent {
            CompositionLocalProvider(com.lostf1sh.pixelplayeross.presentation.components.LocalMaterialTheme provides androidx.compose.material3.darkColorScheme()) {
                DapTheme(darkTheme = true) {
                    Surface(Modifier.fillMaxSize(), color = Dap.background) {
                        Box(Modifier.fillMaxSize().padding(top = 160.dp, start = 24.dp, end = 24.dp)) {
                            PlayerTrackInfoSurface(
                                PlayerTrackInfoState(song, persistentListOf(), false, false, { true }, true),
                                PlayerSheetEnv(FullPlayerLoadingTweaks(delayAll = false), false, PlayerSheetState.EXPANDED, true, { 1f }),
                                actions, false, { calls += "artist" }, { calls += "bookmark" },
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("Fiesta Pagana").performClick()
        compose.waitForIdle()

        assertThat(calls).containsExactly("locate:4242")
        calls.clear()
        compose.onNodeWithText("Gaia").performClick()
        assertThat(calls).containsExactly("album:9")                    // untouched: the album opens its page
    }

    // B57 2.4 changes this test on purpose: the title tap now opens the song's album page (see B57TitleTapAlbumTest); only a song with no album id still goes to Library > Songs.
    @Test
    fun `a song with no album falls back to the one library locate destination`() {
        val requests = MutableSharedFlow<com.lostf1sh.pixelplayeross.presentation.viewmodel.SongLocateRequest>(extraBufferCapacity = 1)
        val player = mockk<PlayerViewModel>(relaxed = true) { every { songLocateNavigationRequests } returns requests }
        val motion = mockk<SheetMotionController>(relaxed = true)
        lateinit var nav: NavHostController
        compose.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = "home") {
                composable("home") { Box(Modifier.fillMaxSize()) }
                composable(Screen.LibraryLocate.route) { Box(Modifier.fillMaxSize()) }
            }
            PlayerLocateNavigationEffect(nav, sheetCollapsedTargetY = 1_000f, sheetMotionController = motion, playerViewModel = player)
        }
        compose.waitForIdle()

        runBlocking { requests.emit(com.lostf1sh.pixelplayeross.presentation.viewmodel.SongLocateRequest("4242", albumId = 0L)) }
        compose.waitForIdle()

        assertThat(nav.currentBackStackEntry?.destination?.route).isEqualTo(Screen.LibraryLocate.route)
        assertThat(nav.currentBackStackEntry?.arguments?.getString("songId")).isEqualTo("4242")
        verify(exactly = 1) { player.collapsePlayerSheet() }
    }
}
