package com.lostf1sh.pixelplayeross.presentation.screens

import androidx.activity.OnBackPressedDispatcher
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.collections.auto.AutoCollection
import com.lostf1sh.pixelplayeross.data.collections.auto.AutoCollectionId
import com.lostf1sh.pixelplayeross.data.collections.auto.AutoCollectionsState
import com.lostf1sh.pixelplayeross.data.model.ArtistRef
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.stats.Kit
import com.lostf1sh.pixelplayeross.data.stats.ListenSource
import com.lostf1sh.pixelplayeross.data.stats.ListenSourceKind
import com.lostf1sh.pixelplayeross.presentation.collections.AutoCollectionsViewModel
import com.lostf1sh.pixelplayeross.presentation.navigation.DapBackAuthority
import com.lostf1sh.pixelplayeross.presentation.navigation.LocalDapBackNavigation
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlaylistViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.StablePlayerState
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B42-fix1, item 7: "the lists, at least the daily mix, take you to the Library as if it were Stats, but we're not in Stats: it's literally a
 * playlist. If I tap a song inside the playlist it shouldn't take me to the Library."
 *
 * The real [AutoCollectionDetailScreen] (the Daily mix and the other collections are this one page), over a real navigation graph with a
 * "Library" destination at the route Stats' rows use, a view model that holds the collection, and a mocked [PlayerViewModel] that records what
 * it is asked to play. A tap on a row plays that song with the collection as the queue, in the collection's order, with the collection's own
 * listen source; and navigates nowhere. Play and Shuffle at the top are as they were. Stats' rows still open the song in the Library
 * (`B32StatsOpenTest`, whose two row tests are unchanged).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h1600dp-xhdpi")
class B42Fix1CollectionTapTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun song(id: Long, title: String): Song =
        Kit.song(id, title = title, artist = "Artista", album = "Disco", albumId = 5)
            .copy(artists = listOf(ArtistRef(11, "Artista", true)), artistId = 11)

    private val songs = listOf(song(1, "Cancion Uno"), song(2, "Cancion Dos"), song(3, "Cancion Tres"))
    private val catalog = Kit.catalog(songs)
    private val items = songs.map { catalog.item(it.libraryItemId)!! }

    private val playerViewModel = mockk<PlayerViewModel>(relaxed = true).also {
        every { it.stablePlayerState } returns MutableStateFlow(StablePlayerState())
        every { it.favoriteSongIds } returns MutableStateFlow(emptySet())
        every { it.selectedSongForInfo } returns MutableStateFlow(null)
    }
    private val playlistViewModel = mockk<PlaylistViewModel>(relaxed = true)

    private var navigation: NavHostController? = null

    private fun show(id: AutoCollectionId) {
        val collection = AutoCollection(id, items, days = 30)
        val viewModel = mockk<AutoCollectionsViewModel>(relaxed = true).also {
            every { it.state } returns MutableStateFlow(AutoCollectionsState(isReady = true, collections = listOf(collection), hidden = emptySet()))
        }
        compose.setContent {
            DapTheme(baseTheme = DapBaseTheme.Graphite, accent = DapAccent.Sage) {
                CompositionLocalProvider(LocalDapBackNavigation provides DapBackAuthority(OnBackPressedDispatcher())) {
                    Box(Modifier.fillMaxSize().background(Dap.background)) {
                        val nav = rememberNavController().also { navigation = it }
                        NavHost(nav, startDestination = "collection") {
                            composable("collection") {
                                AutoCollectionDetailScreen(
                                    collectionKey = id.key, navController = nav, playerViewModel = playerViewModel,
                                    viewModel = viewModel, playlistViewModel = playlistViewModel,
                                )
                            }
                            // where Stats' rows (B32-Q2) send a tap: the Library, located and marked
                            composable("library_locate/{songId}") { Text("THE LIBRARY") }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun name(id: AutoCollectionId) = context.getString(
        when (id) {
            AutoCollectionId.DAILY_MIX -> R.string.collection_name_daily_mix
            else -> R.string.collection_name_on_repeat
        }
    )

    private fun route() = navigation!!.currentDestination?.route

    @Test
    fun a_tap_in_the_daily_mix_plays_that_song_with_the_collection_as_the_queue_and_navigates_nowhere() {
        show(AutoCollectionId.DAILY_MIX)
        compose.onNodeWithText("Cancion Dos").performClick()
        compose.waitForIdle()
        verify(exactly = 1) {
            playerViewModel.showAndPlaySong(
                match { it.id == "2" },
                match { queue -> queue.map { it.id } == listOf("1", "2", "3") },
                name(AutoCollectionId.DAILY_MIX),
                updatesLastPlaylistShortcut = false,
                source = ListenSource(ListenSourceKind.COLLECTION, "daily_mix"),
            )
        }
        // the page stays: it is not Stats, and it is not the Library
        assertThat(route()).isEqualTo("collection")
        compose.onAllNodesWithText("THE LIBRARY").fetchSemanticsNodes().let { assertThat(it).isEmpty() }
    }

    @Test
    fun the_queue_is_the_collection_in_its_own_order_whichever_row_is_tapped() {
        show(AutoCollectionId.DAILY_MIX)
        compose.onNodeWithText("Cancion Tres").performClick()
        compose.waitForIdle()
        verify(exactly = 1) {
            playerViewModel.showAndPlaySong(
                match { it.id == "3" },
                match { queue -> queue.map { it.id } == listOf("1", "2", "3") },
                any(),
                updatesLastPlaylistShortcut = false,
                source = any(),
            )
        }
        compose.onNodeWithText("Cancion Uno").performClick()
        compose.waitForIdle()
        verify(exactly = 1) {
            playerViewModel.showAndPlaySong(
                match { it.id == "1" },
                match { queue -> queue.map { it.id } == listOf("1", "2", "3") },
                any(),
                updatesLastPlaylistShortcut = false,
                source = any(),
            )
        }
        assertThat(route()).isEqualTo("collection")
    }

    @Test
    fun the_listen_source_is_the_collections_own_for_every_collection() {
        show(AutoCollectionId.ON_REPEAT)
        compose.onNodeWithText("Cancion Uno").performClick()
        compose.waitForIdle()
        verify(exactly = 1) {
            playerViewModel.showAndPlaySong(
                any(), any(), name(AutoCollectionId.ON_REPEAT), updatesLastPlaylistShortcut = false,
                source = ListenSource(ListenSourceKind.COLLECTION, "on_repeat"),
            )
        }
    }

    @Test
    fun play_and_shuffle_at_the_top_are_as_they_were() {
        show(AutoCollectionId.DAILY_MIX)
        compose.onNodeWithContentDescription(context.getString(R.string.presentation_batch_b_play_it)).performClick()
        verify(exactly = 1) {
            playerViewModel.playSongs(
                match { it.map { s -> s.id } == listOf("1", "2", "3") }, match { it.id == "1" }, name(AutoCollectionId.DAILY_MIX),
                source = ListenSource(ListenSourceKind.COLLECTION, "daily_mix"),
            )
        }
        compose.onNodeWithContentDescription(context.getString(R.string.shortcut_shuffle_short)).performClick()
        verify(exactly = 1) {
            playerViewModel.playSongsShuffled(
                match { it.map { s -> s.id } == listOf("1", "2", "3") }, name(AutoCollectionId.DAILY_MIX), startAtZero = true,
                source = ListenSource(ListenSourceKind.COLLECTION, "daily_mix"),
            )
        }
        // and neither of them navigates
        assertThat(route()).isEqualTo("collection")
    }

    @Test
    fun a_collection_no_longer_reaches_for_the_route_stats_uses_and_stats_still_does() {
        val main = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "src/main/AndroidManifest.xml").isFile }.resolve("src/main/java/com/lostf1sh/pixelplayeross")
        val collection = main.resolve("presentation/screens/AutoCollectionDetailScreen.kt").readText()
        assertThat(collection).doesNotContain("StatsTarget")
        assertThat(collection).doesNotContain("LibraryLocate")
        // Stats' rows: still the Library (B32-Q2 / R32-01)
        val stats = main.resolve("presentation/stats/StatsOpen.kt").readText()
        assertThat(stats).contains("Screen.LibraryLocate.createRoute(songId)")
        assertThat(main.resolve("presentation/screens/StatsScreen.kt").readText()).contains("navController.navigateSafely(target.route())")
    }
}
