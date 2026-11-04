package com.lostf1sh.pixelplayeross.presentation.components.player

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.presentation.components.scoped.PlayerLocateNavigationEffect
import com.lostf1sh.pixelplayeross.presentation.components.scoped.SheetMotionController
import com.lostf1sh.pixelplayeross.presentation.navigation.Screen
import com.lostf1sh.pixelplayeross.presentation.viewmodel.AlbumLocateRequests
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.SongLocateRequest
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B57 2.4: the title tap opens the song's album page with the song asked for. Tap, come back, tap again (the same song, then another): each tap opens the album with its own fresh
 * request. The second tap used to be lost because the old Library destination cleared the request on its way out; the request is now taken by token and never cleared by leaving.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h805dp-xhdpi")
class B57TitleTapAlbumTest {
    @get:Rule val compose = createComposeRule()

    private val requests = MutableSharedFlow<SongLocateRequest>(extraBufferCapacity = 1)
    private lateinit var nav: NavHostController

    private fun host() {
        val player = mockk<PlayerViewModel>(relaxed = true) { every { songLocateNavigationRequests } returns requests }
        val motion = mockk<SheetMotionController>(relaxed = true)
        compose.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = "home") {
                composable("home") { Box(Modifier.fillMaxSize()) }
                composable(Screen.AlbumDetail.route) { Box(Modifier.fillMaxSize()) }
            }
            PlayerLocateNavigationEffect(nav, sheetCollapsedTargetY = 1_000f, sheetMotionController = motion, playerViewModel = player)
        }
        compose.waitForIdle()
    }

    private fun tap(songId: String, albumId: Long) {
        runBlocking { requests.emit(SongLocateRequest(songId, albumId)) }
        compose.waitForIdle()
    }

    @Test
    fun `each tap opens the album with its own request, the same song twice and then another song`() {
        host()
        tap("4242", 9)
        assertThat(nav.currentBackStackEntry?.destination?.route).isEqualTo(Screen.AlbumDetail.route)
        assertThat(nav.currentBackStackEntry?.arguments?.getString("albumId")).isEqualTo("9")
        val first = AlbumLocateRequests.request.value!!
        assertThat(first.songId).isEqualTo("4242")
        AlbumLocateRequests.consume(first.token) // the page took it

        nav.popBackStack(); compose.waitForIdle() // come back to the player's screen
        tap("4242", 9) // the same song again
        assertThat(nav.currentBackStackEntry?.destination?.route).isEqualTo(Screen.AlbumDetail.route)
        val second = AlbumLocateRequests.request.value!!
        assertThat(second.songId).isEqualTo("4242")
        assertThat(second.token).isGreaterThan(first.token)
        AlbumLocateRequests.consume(second.token)

        nav.popBackStack(); compose.waitForIdle()
        tap("77", 12) // another song, another album
        assertThat(nav.currentBackStackEntry?.arguments?.getString("albumId")).isEqualTo("12")
        assertThat(AlbumLocateRequests.request.value).isEqualTo(AlbumLocateRequests.Request(12, "77", AlbumLocateRequests.request.value!!.token, AlbumLocateRequests.request.value!!.atMs))
        assertThat(AlbumLocateRequests.request.value!!.token).isGreaterThan(second.token)
    }

    @Test
    fun `a tap while the album page is already showing replaces it and still carries its request`() {
        host()
        tap("4242", 9)
        tap("4242", 9) // the album page is the current destination: popped and pushed again
        assertThat(nav.currentBackStackEntry?.destination?.route).isEqualTo(Screen.AlbumDetail.route)
        assertThat(AlbumLocateRequests.request.value).isNotNull()
    }

    @Test
    fun `a page leaving does not erase a newer request, a stale one is ignored and the list index counts the headers`() {
        AlbumLocateRequests.request(9, "a", nowMs = 1_000)
        val old = AlbumLocateRequests.request.value!!
        AlbumLocateRequests.request(9, "a", nowMs = 2_000)
        val newer = AlbumLocateRequests.request.value!!
        AlbumLocateRequests.consume(old.token) // the old destination handled (or left): the newer request stays
        assertThat(AlbumLocateRequests.request.value).isEqualTo(newer)
        assertThat(AlbumLocateRequests.isFresh(newer, nowMs = 2_000 + AlbumLocateRequests.TTL_MS)).isTrue()
        assertThat(AlbumLocateRequests.isFresh(newer, nowMs = 2_001 + AlbumLocateRequests.TTL_MS)).isFalse()
        AlbumLocateRequests.consume(newer.token)
        assertThat(AlbumLocateRequests.request.value).isNull()

        val discs = listOf(listOf("a", "b", "c"), listOf("d", "e"))
        assertThat(AlbumLocateRequests.listIndexOf("b", discs, hasAbout = false)).isEqualTo(2)   // header, a, b
        assertThat(AlbumLocateRequests.listIndexOf("e", discs, hasAbout = true)).isEqualTo(1 + 1 + 3 + 1 + 1) // about, header, a b c, header, d, e
        assertThat(AlbumLocateRequests.listIndexOf("b", listOf(listOf("a", "b")), hasAbout = false)).isEqualTo(1) // one disc: no header
        assertThat(AlbumLocateRequests.listIndexOf("zzz", discs, hasAbout = false)).isEqualTo(-1)
    }

    @Test
    fun `the library destination no longer clears a request on its way out`() {
        val source = java.io.File("src/main/java/com/lostf1sh/pixelplayeross/presentation/components/scoped/PlayerLocateNavigationEffect.kt").readText()
        assertThat(source).contains("AlbumLocateRequests.request(")
        assertThat(source).doesNotContain("clearLibraryLocate")
        val album = java.io.File("src/main/java/com/lostf1sh/pixelplayeross/presentation/screens/AlbumDetailScreen.kt").readText()
        assertThat(album).contains("AlbumLocateRequests.consume(request.token)")
        assertThat(album).doesNotContain("onDispose { com.lostf1sh.pixelplayeross.presentation.viewmodel.AlbumLocateRequests")
    }
}
