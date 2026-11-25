package com.lostf1sh.pixelplayeross.presentation.screens

import android.app.Application
import android.content.Context
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import androidx.paging.PagingData
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.model.LibraryTabId
import com.lostf1sh.pixelplayeross.data.model.Playlist
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.model.SortOption
import com.lostf1sh.pixelplayeross.data.preferences.PlaylistPreferencesRepository
import com.lostf1sh.pixelplayeross.data.repository.MusicRepository
import com.lostf1sh.pixelplayeross.data.worker.SyncProgress
import com.lostf1sh.pixelplayeross.presentation.navigation.DapBackAuthority
import com.lostf1sh.pixelplayeross.presentation.navigation.LocalDapBackNavigation
import com.lostf1sh.pixelplayeross.presentation.viewmodel.*
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import com.lostf1sh.pixelplayeross.ui.glass.B42HiltShim
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "en-w360dp-h805dp-xhdpi")
class B78PlaylistUiTest {
    @get:Rule(order = 0)
    val hilt = B42HiltShim()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun label(@StringRes id: Int) = context.getString(id)

    private val songs = (1..5).map { index ->
        Song.emptySong().copy(id = "song-$index", title = "Fixture $index", artist = "Synthetic artist", duration = 181_000L)
    }
    private val userPlaylist = Playlist("local-fixture", "Synthetic playlist", songs.map { it.id })

    private val player = mockk<PlayerViewModel>(relaxed = true).also {
        every { it.stablePlayerState } returns MutableStateFlow(StablePlayerState())
        every { it.favoriteSongIds } returns MutableStateFlow(emptySet())
        every { it.navBarCompactMode } returns MutableStateFlow(false)
        every { it.selectedSongForInfo } returns MutableStateFlow(null)
    }

    @Composable
    private fun Screen(playlist: Playlist, viewModel: PlaylistViewModel, navController: NavController, back: () -> Unit = {}) {
        PlaylistDetailScreen(playlist.id, back, {}, player, viewModel, navController)
    }

    @Composable
    private fun Stage(content: @Composable () -> Unit) {
        val dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            ?: remember { OnBackPressedDispatcher() }
        DapTheme {
            CompositionLocalProvider(LocalDapBackNavigation provides remember(dispatcher) { DapBackAuthority(dispatcher) }) {
                Box(Modifier.fillMaxSize()) { content() }
            }
        }
    }

    private fun mockPlaylists(state: MutableStateFlow<PlaylistUiState>) = mockk<PlaylistViewModel>(relaxed = true).also {
        every { it.uiState } returns state
        every { it.nlpPlaylistPreviewState } returns MutableStateFlow(NlpPlaylistPreviewState())
        every { it.playlistCreationEvent } returns MutableSharedFlow()
    }

    private fun uiState(playlist: Playlist) = PlaylistUiState(
        currentPlaylistDetails = playlist,
        currentPlaylistSongs = songs,
        playlistSongsOrderMode = PlaylistSongsOrderMode.Manual,
    )

    private fun openMenu() = compose.onNodeWithContentDescription(label(R.string.presentation_batch_b_more_options)).performClick()

    private fun assertMenu(vararg expected: Int) {
        val options = compose.onAllNodes(hasAnyAncestor(isPopup()) and hasClickAction()).fetchSemanticsNodes()
            .map { it.config[SemanticsProperties.Text].first().text }
        assertThat(options).containsExactlyElementsIn(expected.map(::label)).inOrder()
    }

    @Test
    fun `menu reflects the capabilities of user folder and smart playlists`() {
        val shown = mutableStateOf(userPlaylist)
        val state = MutableStateFlow(uiState(userPlaylist))
        val viewModel = mockPlaylists(state)
        compose.setContent { Stage { Screen(shown.value, viewModel, rememberNavController()) } }

        openMenu()
        assertMenu(
            R.string.presentation_batch_b_add_songs,
            R.string.playlist_sort,
            R.string.playlist_edit_songs,
            R.string.presentation_batch_b_edit_playlist,
            R.string.presentation_batch_b_set_default_transition,
            R.string.presentation_batch_b_export_playlist,
            R.string.presentation_batch_b_delete_playlist,
        )

        fun showPlaylist(playlist: Playlist) {
            compose.runOnIdle {
                shown.value = playlist
                state.value = uiState(playlist)
            }
            openMenu()
        }

        showPlaylist(userPlaylist.copy(id = "${PlaylistViewModel.FOLDER_PLAYLIST_PREFIX}fixture"))
        assertMenu(R.string.playlist_sort)
        showPlaylist(userPlaylist.copy(id = "smart-fixture", source = "SMART:fixture"))
        assertMenu(
            R.string.playlist_sort,
            R.string.presentation_batch_b_edit_playlist,
            R.string.presentation_batch_b_set_default_transition,
            R.string.presentation_batch_b_export_playlist,
            R.string.presentation_batch_b_delete_playlist,
        )
    }

    @Test
    fun `each screen opens one sort sheet and sorts its own songs`() {
        val playlistViewModel = mockPlaylists(MutableStateFlow(uiState(userPlaylist)))
        val libraryPlaylists = mockPlaylists(MutableStateFlow(PlaylistUiState()))
        val library = mockk<LibraryViewModel>().also {
            every { it.tabSearch } returns LibraryTabSearch()
            every { it.songsPagingFlow } returns flowOf(PagingData.from(songs))
            every { it.albumsPagingFlow } returns flowOf(PagingData.empty())
            every { it.artistsPagingFlow } returns flowOf(PagingData.empty())
            every { it.favoritesPagingFlow } returns flowOf(PagingData.empty())
            every { it.isLoadingLibrary } returns MutableStateFlow(false)
        }
        every { player.lastLibraryTabIndexFlow } returns MutableStateFlow(0)
        every { player.libraryTabsFlow } returns MutableStateFlow(persistentListOf(LibraryTabId.SONGS.storageKey))
        every { player.libraryAllTabsFlow } returns player.libraryTabsFlow
        every { player.libraryHiddenTabsFlow } returns MutableStateFlow(persistentListOf())
        every { player.currentLibraryTabId } returns MutableStateFlow(LibraryTabId.SONGS)
        every { player.libraryNavigationMode } returns MutableStateFlow("")
        every { player.libraryLocateRequest } returns MutableStateFlow(null)
        every { player.locatedSongId } returns MutableStateFlow(null)
        every { player.scrollToIndexEvent } returns MutableSharedFlow()
        every { player.multiSelectionStateHolder } returns MultiSelectionStateHolder()
        every { player.playlistSelectionStateHolder } returns PlaylistSelectionStateHolder()
        every { player.syncManager } returns mockk {
            every { isFetchingChanges } returns flowOf(false)
            every { syncProgress } returns flowOf(SyncProgress())
            every { isSyncing } returns flowOf(false)
        }
        every { player.playerUiState } returns MutableStateFlow(PlayerUiState(isLoadingInitialSongs = false, isLoadingLibraryCategories = false))
        every { player.songCountFlow } returns MutableStateFlow(songs.size)
        every { player.albumsFlow } returns MutableStateFlow(persistentListOf())
        every { player.artistsFlow } returns MutableStateFlow(persistentListOf())
        every { player.availableSortOptions } returns MutableStateFlow(listOf(SortOption.SongTitleAZ, SortOption.SongArtist))

        val showDetail = mutableStateOf(true)
        compose.setContent {
            Stage {
                val nav = rememberNavController()
                LibraryScreen(nav, player, libraryPlaylists, library, mockk(relaxed = true))
                if (showDetail.value) Screen(userPlaylist, playlistViewModel, nav)
            }
        }
        val playlistHeading = label(R.string.presentation_batch_b_sort_songs)
        val libraryHeading = label(R.string.presentation_batch_d_sort_by)
        val sheets = hasText(playlistHeading) or hasText(libraryHeading)
        openMenu()
        compose.onNodeWithText(label(R.string.playlist_sort)).performClick()
        compose.onAllNodes(sheets, useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithText(playlistHeading).assertIsDisplayed()
        compose.onNode(hasText(label(R.string.sort_method_title)) and hasAnyAncestor(isDialog())).performClick()
        verify(exactly = 1) { playlistViewModel.sortPlaylistSongs(SortOption.SongTitleAZ) }
        verify(exactly = 0) { player.sortSongs(any()) }

        compose.runOnIdle { showDetail.value = false }
        compose.onNodeWithText(label(R.string.sort_method_title)).performClick()
        compose.onAllNodes(sheets, useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithText(libraryHeading).assertIsDisplayed()
        compose.onNode(hasText(label(R.string.sort_method_artist)) and hasAnyAncestor(isDialog())).performClick()
        verify(exactly = 1) { player.sortSongs(SortOption.SongArtist) }
        verify(exactly = 1) { playlistViewModel.sortPlaylistSongs(any()) }
    }

    @Test
    fun `remove drag and Done persist the order that Play and Shuffle use`() {
        val persisted = MutableStateFlow(listOf(userPlaylist))
        val orderModes = MutableStateFlow(mapOf(userPlaylist.id to "manual"))
        val preferences = mockk<PlaylistPreferencesRepository>().also { repo ->
            every { repo.userPlaylistsFlow } returns persisted
            every { repo.playlistSongOrderModesFlow } returns orderModes
            every { repo.playlistsSortOptionFlow } returns flowOf(SortOption.PlaylistNameAZ.storageKey)
            coEvery { repo.getPlaylistsOnce() } answers { persisted.value }
            coEvery { repo.removeSongFromPlaylist(userPlaylist.id, any()) } answers {
                persisted.value = listOf(persisted.value.single().let { it.copy(songIds = it.songIds - secondArg<String>()) })
            }
            coEvery { repo.reorderSongsInPlaylist(userPlaylist.id, any()) } answers {
                persisted.value = listOf(persisted.value.single().copy(songIds = secondArg()))
            }
            coEvery { repo.setPlaylistSongOrderMode(userPlaylist.id, any()) } answers {
                orderModes.value = mapOf(userPlaylist.id to secondArg())
            }
        }
        val music = mockk<MusicRepository>().also {
            every { it.getSongsByIds(any()) } answers {
                flowOf(firstArg<List<String>>().map { id -> songs.first { it.id == id } })
            }
        }
        val viewModel = PlaylistViewModel(preferences, mockk(), music, mockk(), mockk(), mockk(), context)
        var dispatcher: OnBackPressedDispatcher? = null
        var leftPage = false
        compose.setContent {
            Stage {
                val activeDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
                SideEffect { dispatcher = activeDispatcher }
                Screen(userPlaylist, viewModel, rememberNavController()) { leftPage = true }
            }
        }
        compose.waitUntil(5_000) { viewModel.uiState.value.currentPlaylistSongs.size == songs.size }
        openMenu()
        compose.onNodeWithText(label(R.string.playlist_edit_songs)).performClick()
        compose.onAllNodesWithContentDescription(label(R.string.cd_remove))[1].performClick()
        val afterRemove = listOf("song-1", "song-3", "song-4", "song-5")
        compose.waitUntil(5_000) { persisted.value.single().songIds == afterRemove }
        compose.onNodeWithText("Fixture 2").assertDoesNotExist()

        val handles = compose.onAllNodesWithContentDescription(label(R.string.presentation_batch_b_reorder_song))
        val first = handles[0].fetchSemanticsNode().boundsInRoot
        val third = handles[2].fetchSemanticsNode().boundsInRoot
        handles[0].performTouchInput {
            swipe(center, center + Offset(0f, third.center.y - first.center.y + 8f), durationMillis = 900)
        }
        val expected = listOf("song-3", "song-4", "song-1", "song-5")
        compose.waitUntil(5_000) { persisted.value.single().songIds == expected }
        compose.onNodeWithText(label(R.string.action_done)).performClick()
        compose.onNodeWithContentDescription(label(R.string.presentation_batch_b_play_it)).performClick()
        compose.onNodeWithContentDescription(label(R.string.shortcut_shuffle_short)).performClick()
        verify {
            player.playSongs(match { it.map(Song::id) == expected }, match { it.id == "song-3" }, userPlaylist.name, null, any())
        }
        verify {
            player.playSongsShuffled(match { it.map(Song::id) == expected }, userPlaylist.name, userPlaylist.id, true, any(), null)
        }
        val row = compose.onNodeWithText("Fixture 4")
        row.assert(hasText("3:01"))
        row.performClick()
        verify {
            player.playSongs(match { it.map(Song::id) == expected }, match { it.id == "song-4" }, userPlaylist.name, userPlaylist.id, any())
        }

        openMenu()
        compose.onNodeWithText(label(R.string.playlist_edit_songs)).performClick()
        compose.runOnIdle { checkNotNull(dispatcher).onBackPressed() }
        compose.onNodeWithText(label(R.string.action_done)).assertDoesNotExist()
        assertThat(leftPage).isFalse()
    }

    @Test
    fun `playlist and Songs tab mount the same library row`() {
        val screens = File("src/main/java/com/lostf1sh/pixelplayeross/presentation/screens")
        assertThat(screens.resolve("LibrarySongsTab.kt").readText()).contains("LibraryPlaybackAwareSongItem(")
        assertThat(screens.resolve("PlaylistDetailScreen.kt").readText()).contains("LibraryPlaybackAwareSongItem(")
        assertThat(screens.resolve("LibraryPlaybackAwareSongItem.kt").readText()).contains("EnhancedSongListItem(")
    }
}
