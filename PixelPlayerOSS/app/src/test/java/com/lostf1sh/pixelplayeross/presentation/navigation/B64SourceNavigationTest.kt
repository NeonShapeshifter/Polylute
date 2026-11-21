package com.lostf1sh.pixelplayeross.presentation.navigation

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.stats.*
import com.lostf1sh.pixelplayeross.presentation.components.scoped.*
import com.lostf1sh.pixelplayeross.presentation.viewmodel.*
import io.mockk.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class B64SourceNavigationTest {
    @get:Rule val compose = createComposeRule()
    private val requests = MutableSharedFlow<SongLocateRequest>(extraBufferCapacity = 1)
    private lateinit var nav: NavHostController
    private fun show() {
        val player = mockk<PlayerViewModel>(relaxed = true)
        every { player.songLocateNavigationRequests } returns requests
        coEvery { player.listeningDestination(any()) } coAnswers {
            val request = firstArg<SongLocateRequest>()
            listeningDestination(request.source, request.albumId, request.songId) { it.ref != "deleted" }
        }
        val motion = mockk<SheetMotionController>(relaxed = true)
        compose.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = "home") {
                listOf("home", Screen.AlbumDetail.route, Screen.ArtistDetail.route, Screen.PlaylistDetail.route,
                    Screen.AutoCollection.route, Screen.GenreDetail.route, Screen.RecentlyPlayed.route,
                    Screen.Stats.route, Screen.Suggestions.route, Screen.ComposerDetail.route, Screen.SmartCollections.route).forEach { route ->
                    composable(route) { Box(Modifier.fillMaxSize()) }
                }
                composable(Screen.LibraryLocate.route, arguments = listOf(androidx.navigation.navArgument("liked") {
                    type = androidx.navigation.NavType.BoolType; defaultValue = false
                })) { Box(Modifier.fillMaxSize()) }
            }
            PlayerLocateNavigationEffect(nav, 1000f, motion, player)
        }
        compose.waitForIdle()
    }
    private fun check(kind: ListenSourceKind, ref: String?, expected: String, argument: Pair<String, String>? = null) {
        show()
        repeat(2) {
            runBlocking { requests.emit(SongLocateRequest("42", 9, ListenSource(kind, ref))) }
            compose.waitForIdle()
            assertThat(nav.currentBackStackEntry?.destination?.route).isEqualTo(expected)
            argument?.let { (key, value) -> assertThat(nav.currentBackStackEntry?.arguments?.get(key).toString()).isEqualTo(value) }
            if (expected == Screen.AlbumDetail.route) {
                assertThat(AlbumLocateRequests.request.value?.songId).isEqualTo("42")
            } else if (expected != Screen.LibraryLocate.route) {
                assertThat(SourceLocateRequests.request.value?.songId).isEqualTo("42")
                assertThat(SourceLocateRequests.request.value?.route).isEqualTo(nav.currentBackStackEntry?.let { entry ->
                    when (kind) {
                        ListenSourceKind.ARTIST -> Screen.ArtistDetail.createRoute(7)
                        ListenSourceKind.PLAYLIST -> Screen.PlaylistDetail.createRoute("list")
                        ListenSourceKind.COLLECTION -> Screen.AutoCollection.createRoute("forgotten_favorites")
                        ListenSourceKind.GENRE -> Screen.GenreDetail.createRoute("rock")
                        ListenSourceKind.HOME_MIX -> Screen.AutoCollection.createRoute("daily_mix")
                        ListenSourceKind.RECENT -> Screen.RecentlyPlayed.route
                        ListenSourceKind.TOP_LIST -> Screen.Stats.route
                        else -> entry.destination.route
                    }
                })
            }
            nav.popBackStack(); compose.waitForIdle()
        }
    }
    @Test fun album() = check(ListenSourceKind.ALBUM, "7", Screen.AlbumDetail.route, "albumId" to "7")
    @Test fun artist() = check(ListenSourceKind.ARTIST, "7", Screen.ArtistDetail.route, "artistId" to "7")
    @Test fun playlist() = check(ListenSourceKind.PLAYLIST, "list", Screen.PlaylistDetail.route, "playlistId" to "list")
    @Test fun collection() = check(ListenSourceKind.COLLECTION, "forgotten_favorites", Screen.AutoCollection.route)
    @Test fun genre() = check(ListenSourceKind.GENRE, "rock", Screen.GenreDetail.route)
    @Test fun library() = check(ListenSourceKind.LIBRARY, null, Screen.LibraryLocate.route, "songId" to "42")
    @Test fun liked() = check(ListenSourceKind.LIBRARY, "liked", Screen.LibraryLocate.route, "liked" to "true")
    @Test fun dailyMix() = check(ListenSourceKind.HOME_MIX, "daily_mix", Screen.AutoCollection.route)
    @Test fun recent() = check(ListenSourceKind.RECENT, null, Screen.RecentlyPlayed.route)
    @Test fun topList() = check(ListenSourceKind.TOP_LIST, "2026-01..2026-11", Screen.Stats.route)
    @Test fun searchFallsBackToAlbum() = check(ListenSourceKind.SEARCH, null, Screen.AlbumDetail.route)
    @Test fun externalFallsBackToAlbum() = check(ListenSourceKind.EXTERNAL_VIEW, null, Screen.AlbumDetail.route)
    @Test fun otherFallsBackToAlbum() = check(ListenSourceKind.OTHER, null, Screen.AlbumDetail.route)
    @Test fun deletedPlaylistFallsBackToAlbum() = check(ListenSourceKind.PLAYLIST, "deleted", Screen.AlbumDetail.route)
    @Test fun changedCollectionFallsBackToAlbum() = check(ListenSourceKind.COLLECTION, "retired", Screen.AlbumDetail.route)
    @Test fun malformedAndMissingSourcesFallBack() = runBlocking {
        for (source in listOf(null, ListenSource(ListenSourceKind.ALBUM, "bad"), ListenSource(ListenSourceKind.HOME_MIX, "missing"))) {
            assertThat(listeningDestination(source, 9, "42") { true }.route).isEqualTo("album_detail/9")
        }
        assertThat(listeningDestination(null, 0, "42") { true }.route).isEqualTo(Screen.LibraryLocate.createRoute("42"))
        assertThat(listeningDestination(ListenSource(ListenSourceKind.HOME_MIX, "your_mix"), 9, "42") { true }.route).isEqualTo(Screen.Suggestions.route)
        assertThat(listeningDestination(ListenSource(ListenSourceKind.GENRE, "composer:A"), 9, "42") { true }.route).isEqualTo(Screen.ComposerDetail.createRoute("A"))
        assertThat(listeningDestination(ListenSource(ListenSourceKind.COLLECTION, "rule:5"), 9, "42") { true }.route).isEqualTo(Screen.SmartCollections.route)
    }
}
