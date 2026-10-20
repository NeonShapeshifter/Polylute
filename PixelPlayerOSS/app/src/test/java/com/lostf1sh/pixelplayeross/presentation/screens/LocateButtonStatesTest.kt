package com.lostf1sh.pixelplayeross.presentation.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.model.SortOption
import com.lostf1sh.pixelplayeross.presentation.lockscreen.FreezeWhileCovered
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.StablePlayerState
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B31 P2.1: **the Library's "locate current song" button comes and goes under the same conditions** (missing on 10-18 at 19:24 and
 * 19:27 with the music playing and the playing song well above the rows on screen; present on 10-19 at 01:44).
 *
 * The real Songs tab ([LibrarySongsTab]) is driven through every state its inputs can be in: the id of the playing song (null, late, a
 * song that is not in the list), the loading flag, the paging list (empty, loaded later, replaced), the lifecycle the screen is composed
 * under (the lock screen's cap holds it at CREATED), and a round trip through another tab. In each, what the screen holds in state
 * for the floating button must end as "shown" once the playing song is off screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xxhdpi")
class LocateButtonStatesTest {
    @get:Rule
    val compose = createComposeRule()

    private fun song(i: Int) = Song(
        id = "id-$i", title = "Cancion $i", artist = "Mägo de Oz", artistId = 7L, album = "Gaia", albumId = 9L, path = "/m/$i.flac",
        contentUriString = "content://$i", albumArtUriString = null, duration = 280_000L, mimeType = "audio/flac", bitrate = 1003, sampleRate = 44100,
    )

    private val songs = (0 until 400).map(::song)
    private val player = MutableStateFlow(StablePlayerState(currentSong = songs[300], isPlaying = true))
    private val playerViewModel = mockk<PlayerViewModel>(relaxed = true).also {
        every { it.stablePlayerState } returns player
        every { it.scrollToIndexEvent } returns MutableSharedFlow()
        every { it.libraryLocateRequest } returns MutableStateFlow(null)
        every { it.locatedSongId } returns MutableStateFlow(null)
    }

    private var locateShown by mutableStateOf<Boolean?>(null)
    private var registered by mutableStateOf<(() -> Unit)?>(null)

    private var loading by mutableStateOf(false)
    private var pagingFlow by mutableStateOf<Flow<PagingData<Song>>>(flowOf(PagingData.from(songs)))
    private var sort by mutableStateOf<SortOption>(SortOption.SongTitleAZ)
    private var bottomChrome by mutableStateOf(0.dp)

    @Before
    fun noRequests() {
        every { playerViewModel.requestLocateCurrentSong() } returns Unit
    }

    @Composable
    private fun TheList() {
        val items = pagingFlow.collectAsLazyPagingItems()
        LibrarySongsTab(
            songs = items,
            isLoading = loading,
            playerViewModel = playerViewModel,
            bottomBarHeight = bottomChrome,
            onMoreOptionsClick = {},
            isRefreshing = false,
            onRefresh = {},
            sortOption = sort,
            onLocateCurrentSongVisibilityChanged = { locateShown = it },
            onRegisterLocateCurrentSongAction = { registered = it },
            hasCurrentSong = true,
        )
    }

    private fun show(content: @Composable () -> Unit = { TheList() }) {
        compose.setContent { content() }
        compose.waitForIdle()
    }

    @Test
    fun `the playing song is far above the rows on screen - the button is shown`() {
        show()
        assertThat(locateShown).isTrue()
        assertThat(registered).isNotNull()
    }

    @Test
    fun `the id of the playing song arrives late`() {
        player.value = StablePlayerState(currentSong = null, isPlaying = false)
        show()
        assertThat(locateShown).isFalse()
        player.value = StablePlayerState(currentSong = songs[300], isPlaying = true)
        compose.waitForIdle()
        assertThat(locateShown).isTrue()
    }

    @Test
    fun `the library's loading flag does not hide the button of a list that has rows on screen`() {
        // B31 P2.1: the flag is the library flow's own and says nothing about the rows shown. It used to hide the button while it was set.
        loading = true
        show()
        assertThat(locateShown).isTrue()
        loading = false
        compose.waitForIdle()
        assertThat(locateShown).isTrue()
    }

    @Test
    fun `a row behind the mini player and the nav bar is not on screen, so the button is shown for it`() {
        // The Library's list runs under its bottom chrome (mini player, nav bar, floating buttons); rows laid out there are drawn behind
        // it. 560 dp of chrome in an 805 dp window leaves about 245 dp of rows to see.
        bottomChrome = 560.dp
        player.value = StablePlayerState(currentSong = songs[8], isPlaying = true)
        show()
        assertThat(locateShown).isTrue()
        // A song among the rows that can be seen is on screen, chrome or not.
        player.value = StablePlayerState(currentSong = songs[1], isPlaying = true)
        compose.waitForIdle()
        assertThat(locateShown).isFalse()
    }

    @Test
    fun `the paging list is empty first and loaded later`() {
        pagingFlow = flowOf(PagingData.empty())
        show()
        assertThat(locateShown).isFalse()
        pagingFlow = flowOf(PagingData.from(songs))
        compose.waitForIdle()
        assertThat(locateShown).isTrue()
    }

    @Test
    fun `the paging list is replaced by a new one after a sort change`() {
        show()
        assertThat(locateShown).isTrue()
        sort = SortOption.SongTitleZA
        pagingFlow = flowOf(PagingData.from(songs.reversed()))
        compose.waitForIdle()
        assertThat(locateShown).isTrue()
    }

    @Test
    fun `the screen is composed under the lock screen's cap and released afterwards`() {
        var frozen by mutableStateOf(true)
        show { FreezeWhileCovered(frozen = frozen, hidden = false) { TheList() } }
        // While the app is held at CREATED nothing it collects reaches it: the id of the playing song has not been heard yet.
        frozen = false
        compose.waitForIdle()
        assertThat(locateShown).isTrue()
    }

    @Test
    fun `the cap is applied and lifted while the list is up and the song changes under it`() {
        var frozen by mutableStateOf(false)
        show { FreezeWhileCovered(frozen = frozen, hidden = false) { TheList() } }
        assertThat(locateShown).isTrue()
        frozen = true
        compose.waitForIdle()
        player.value = StablePlayerState(currentSong = songs[5], isPlaying = true) // visible row: heard only when released
        frozen = false
        compose.waitForIdle()
        assertThat(locateShown).isFalse()
        frozen = true
        compose.waitForIdle()
        player.value = StablePlayerState(currentSong = songs[310], isPlaying = true)
        frozen = false
        compose.waitForIdle()
        assertThat(locateShown).isTrue()
    }

    @Test
    fun `a round trip through another tab`() {
        var onSongs by mutableStateOf(true)
        show { if (onSongs) TheList() }
        assertThat(locateShown).isTrue()
        onSongs = false
        compose.waitForIdle()
        assertThat(locateShown).isFalse() // gone with the tab
        onSongs = true
        compose.waitForIdle()
        assertThat(locateShown).isTrue()
    }

    @Test
    fun `the playing song changes to one that is not in the list at all`() {
        show()
        assertThat(locateShown).isTrue()
        player.value = StablePlayerState(currentSong = song(9999), isPlaying = true)
        compose.waitForIdle()
        assertThat(locateShown).isTrue()
    }
}
