package com.lostf1sh.pixelplayeross.presentation.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.presentation.viewmodel.LibraryLocator
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B32-Q2: the Library showing a song for another page. A request that waits for the Songs list (answered once, marked for a few seconds,
 * dropped when the page that asked is left), a destination that draws nothing and puts the Library in front, and Back from it to the
 * page below.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xhdpi")
class B32LibraryLocateTest {
    @get:Rule
    val compose = createComposeRule()

    // ── the request ──────────────────────────────────────────────────────────────────────────────────

    private class Locator(scope: kotlinx.coroutines.CoroutineScope, val index: (String) -> Int = { if (it == "7") 40 else -1 }) {
        var notFound = 0
        val asked = mutableListOf<String>()
        val locator = LibraryLocator(
            scope = scope, markMs = 1_000L,
            resolveIndex = { id -> asked += id; index(id) },
            onNotFound = { notFound++ },
        )
    }

    @Test
    fun `a request waits until the Songs list takes it, and is spent when the list says it has scrolled`() = runTest {
        val l = Locator(this)
        l.locator.request("7")
        assertThat(l.locator.request.value?.songId).isEqualTo("7")
        // nothing has happened to the library, nothing is marked: the list has not asked
        assertThat(l.asked).isEmpty()
        assertThat(l.locator.located.value).isNull()

        val request = l.locator.request.value!!
        assertThat(l.locator.take(request)).isEqualTo(40)
        assertThat(l.locator.located.value).isEqualTo("7")
        // B32-fix1 R1: the request is still published (it is the key of the effect that is scrolling) until the list completes it
        assertThat(l.locator.request.value).isEqualTo(request)
        l.locator.complete(request)
        assertThat(l.locator.request.value).isNull()
        assertThat(l.locator.located.value).isEqualTo("7")
        // spent: the same request again is not answered
        assertThat(l.locator.take(request)).isEqualTo(-1)
        assertThat(l.asked).containsExactly("7")
        l.locator.clear()
    }

    @Test
    fun `the mark goes after a few seconds`() = runTest {
        val l = Locator(this)
        l.locator.request("7")
        l.locator.take(l.locator.request.value!!)
        assertThat(l.locator.located.value).isEqualTo("7")
        advanceTimeBy(999)
        runCurrent()
        assertThat(l.locator.located.value).isEqualTo("7")
        advanceTimeBy(2)
        runCurrent()
        assertThat(l.locator.located.value).isNull()
    }

    @Test
    fun `a song that is not in the list is said so, nothing is marked, and the request is spent`() = runTest {
        val l = Locator(this)
        l.locator.request("999")
        assertThat(l.locator.take(l.locator.request.value!!)).isEqualTo(-1)
        assertThat(l.notFound).isEqualTo(1)
        assertThat(l.locator.located.value).isNull()
        assertThat(l.locator.request.value).isNull()
    }

    @Test
    fun `an older request cannot be answered once a newer one is asked`() = runTest {
        val l = Locator(this)
        l.locator.request("7")
        val stale = l.locator.request.value!!
        l.locator.request("8")
        assertThat(l.locator.take(stale)).isEqualTo(-1)
        assertThat(l.asked).isEmpty()
        assertThat(l.locator.request.value?.songId).isEqualTo("8")
        l.locator.clear()
    }

    @Test
    fun `leaving the page that asked drops the request and the mark`() = runTest {
        val l = Locator(this)
        l.locator.request("7")
        l.locator.clear()
        assertThat(l.locator.request.value).isNull()
        l.locator.request("7")
        l.locator.take(l.locator.request.value!!)
        l.locator.clear()
        assertThat(l.locator.located.value).isNull()
    }

    @Test
    fun `completing an old request leaves a newer one alone`() = runTest {
        val l = Locator(this)
        l.locator.request("7")
        val old = l.locator.request.value!!
        l.locator.request("8")
        l.locator.complete(old)
        assertThat(l.locator.request.value?.songId).isEqualTo("8")
        l.locator.clear()
    }

    // ── the query takes time (B32-fix1 R1) ───────────────────────────────────────────────────────────

    /** A locator whose query answers when the test says so: the Room query that suspends. */
    private class SlowLocator(scope: kotlinx.coroutines.CoroutineScope) {
        val answers = mutableMapOf<String, kotlinx.coroutines.CompletableDeferred<Int>>()
        var notFound = 0
        val locator = LibraryLocator(
            scope = scope, markMs = 1_000L,
            resolveIndex = { id -> answers.getOrPut(id) { kotlinx.coroutines.CompletableDeferred() }.await() },
            onNotFound = { notFound++ },
        )
        fun answer(id: String, index: Int) { answers.getOrPut(id) { kotlinx.coroutines.CompletableDeferred() }.complete(index) }
    }

    @Test
    fun `the request is still published while its query is suspended, so nothing cancels the effect that serves it`() = runTest {
        val l = SlowLocator(this)
        l.locator.request("7")
        val request = l.locator.request.value!!
        val taking = async { l.locator.take(request) }
        runCurrent()
        // in flight: still the current request, nothing marked, nothing said
        assertThat(l.locator.request.value).isEqualTo(request)
        assertThat(l.locator.located.value).isNull()
        assertThat(l.notFound).isEqualTo(0)

        l.answer("7", 40)
        assertThat(taking.await()).isEqualTo(40)
        assertThat(l.locator.located.value).isEqualTo("7")
        l.locator.complete(request)
        l.locator.clear()
    }

    @Test
    fun `a clear during the query publishes no mark and says nothing`() = runTest {
        val l = SlowLocator(this)
        l.locator.request("7")
        val taking = async { l.locator.take(l.locator.request.value!!) }
        runCurrent()

        l.locator.clear()
        l.answer("7", 40)
        assertThat(taking.await()).isEqualTo(-1)
        assertThat(l.locator.located.value).isNull()
        assertThat(l.locator.request.value).isNull()
        assertThat(l.notFound).isEqualTo(0)
    }

    @Test
    fun `a newer request during the query is not overwritten by the old answer, and the old song is never marked`() = runTest {
        val l = SlowLocator(this)
        l.locator.request("7")
        val oldRequest = l.locator.request.value!!
        val taking = async { l.locator.take(oldRequest) }
        runCurrent()

        l.locator.request("8")
        l.answer("7", 40)
        assertThat(taking.await()).isEqualTo(-1)
        assertThat(l.locator.located.value).isNull()
        assertThat(l.locator.request.value?.songId).isEqualTo("8")
        assertThat(l.notFound).isEqualTo(0)

        l.answer("8", 41)
        assertThat(l.locator.take(l.locator.request.value!!)).isEqualTo(41)
        assertThat(l.locator.located.value).isEqualTo("8")
        l.locator.clear()
    }

    @Test
    fun `a new request takes the old mark away at once`() = runTest {
        val l = Locator(this)
        l.locator.request("7")
        l.locator.take(l.locator.request.value!!)
        assertThat(l.locator.located.value).isEqualTo("7")
        l.locator.request("8")
        assertThat(l.locator.located.value).isNull()
        l.locator.clear()
    }

    @Test
    fun `a cancelled caller is not a song that is not found, and the request stays for whoever serves it next`() = runTest {
        val l = SlowLocator(this)
        l.locator.request("7")
        val request = l.locator.request.value!!
        val taking = async { l.locator.take(request) }
        runCurrent()

        taking.cancel()
        runCurrent()
        assertThat(l.notFound).isEqualTo(0)
        assertThat(l.locator.located.value).isNull()
        assertThat(l.locator.request.value).isEqualTo(request)

        // the next caller (the Songs list back on screen) is served: the answer was waiting
        l.answer("7", 40)
        assertThat(l.locator.take(request)).isEqualTo(40)
        assertThat(l.locator.located.value).isEqualTo("7")
        l.locator.clear()
    }

    @Test
    fun `a resolver that throws is a song not found, not a crash`() = runTest {
        val l = Locator(this, index = { error("the library failed") })
        l.locator.request("7")
        assertThat(l.locator.take(l.locator.request.value!!)).isEqualTo(-1)
        assertThat(l.notFound).isEqualTo(1)
    }

    // ── the route and the shell ──────────────────────────────────────────────────────────────────────

    @Test
    fun `the route names the song and survives an id with odd characters`() {
        assertThat(Screen.LibraryLocate.createRoute("42")).isEqualTo("library_locate/42")
        assertThat(Screen.LibraryLocate.createRoute("a b/c")).isEqualTo("library_locate/a%20b%2Fc")
        assertThat(isLibraryLocateRoute("library_locate/42")).isTrue()
        assertThat(isLibraryLocateRoute(Screen.LibraryLocate.route)).isTrue()
        assertThat(isLibraryLocateRoute("library")).isFalse()
        assertThat(isLibraryLocateRoute(null)).isFalse()
    }

    @Test
    fun `while the Library shows a song for a page it is the root in front, and the listener's own choice is not overwritten`() {
        assertThat(shownRootRoute("library_locate/42", Screen.Home.route)).isEqualTo(Screen.Library.route)
        assertThat(shownRootRoute("stats", Screen.Home.route)).isEqualTo(Screen.Home.route)
        assertThat(shownRootRoute(DAP_DETAIL_HOST_ROUTE, Screen.Search.route)).isEqualTo(Screen.Search.route)
        assertThat(shownRootRoute(null, null)).isNull()
    }

    @Test
    fun `the Library showing a song carries the Library's chrome, its shelf and its bottom bar`() {
        assertThat(DapSurfacePolicy.kindFor("library_locate/42")).isEqualTo(DapSurfaceKind.Root)
        assertThat(DapSurfacePolicy.showsPlayerShelf("library_locate/42")).isTrue()
        assertThat(DapSurfacePolicy.showsBottomNavigation("library_locate/42")).isTrue()
        // and Stats under it is still a music page that keeps the shelf, with no bottom bar
        assertThat(DapSurfacePolicy.showsBottomNavigation(Screen.Stats.route)).isFalse()
    }

    // ── the destination, on a real stack ─────────────────────────────────────────────────────────────

    @Test
    fun `the destination asks the Library for the song, and Back leaves Stats where it was and takes the request back`() {
        val player = mockk<PlayerViewModel>(relaxed = true)
        lateinit var nav: NavHostController
        compose.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = Screen.Stats.route) {
                composable(Screen.Stats.route) { Box(Modifier.fillMaxSize()) { Text("stats page") } }
                composable(Screen.LibraryLocate.route, arguments = listOf(navArgument("songId") { type = NavType.StringType })) { entry ->
                    LibraryLocateEffect(songId = entry.arguments?.getString("songId").orEmpty(), playerViewModel = player)
                }
            }
        }
        compose.waitForIdle()
        compose.runOnUiThread { nav.navigateSafely(Screen.LibraryLocate.createRoute("42")) }
        compose.waitForIdle()

        assertThat(isLibraryLocateRoute(nav.currentBackStackEntry?.destination?.route)).isTrue()
        verify(exactly = 1) { player.requestLibraryLocate("42") }
        verify(exactly = 0) { player.clearLibraryLocate() }

        compose.runOnUiThread { nav.popBackStack() }
        compose.waitForIdle()
        assertThat(nav.currentBackStackEntry?.destination?.route).isEqualTo(Screen.Stats.route)
        compose.onNodeWithText("stats page").assertExists()
        verify(exactly = 1) { player.clearLibraryLocate() }
    }

    @Test
    fun `a destination with no song asks for nothing`() {
        val player = mockk<PlayerViewModel>(relaxed = true)
        compose.setContent { LibraryLocateEffect(songId = "", playerViewModel = player) }
        compose.waitForIdle()
        verify(exactly = 0) { player.requestLibraryLocate(any()) }
    }
}
