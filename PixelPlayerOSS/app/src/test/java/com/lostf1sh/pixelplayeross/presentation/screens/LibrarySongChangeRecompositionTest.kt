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
import com.lostf1sh.pixelplayeross.presentation.components.CompositionCounter
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.StablePlayerState
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B24-Q2: what a song change costs the Library's Songs list, for a tap on a row and for a media-next, counted per composable.
 *
 * The tap on a row in a 4,870-song Library was one frame of 116-150 ms on the HONOR after B23 took the parked player and the closed
 * queue out of it, against <= 48 ms for a media-next. Both end in the same state write (the controller reports the new song), so the
 * list's own part of a tap and of a next must be the same: the playing row and the row that was playing, and nothing else. The
 * composable counts come from the Compose runtime's tracing hook ([CompositionCounter]), not from anything added to the list.
 *
 * What B23's list did on every song change, tap or next: re-ran the whole `LibrarySongsTab` body (it read the song's id there),
 * scanned its loaded rows for the song's index, and registered a **new** "locate current song" lambda with the Library screen,
 * which keeps it in state it reads in its own body: the entire Library screen was invalidated by every song change.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xxhdpi")
class LibrarySongChangeRecompositionTest {

    @get:Rule
    val compose = createComposeRule()

    private val counter = CompositionCounter()

    private fun song(i: Int) = Song(
        id = "id-$i", title = "Cancion $i", artist = "Mägo de Oz", artistId = 7L, album = "Gaia", albumId = 9L, path = "/m/$i.flac",
        contentUriString = "content://$i", albumArtUriString = null, duration = 280_000L, mimeType = "audio/flac", bitrate = 1003, sampleRate = 44100,
    )

    private val songs = (0 until 400).map(::song)

    private val player = MutableStateFlow(StablePlayerState(currentSong = songs[3], isPlaying = true))
    private val playerViewModel = mockk<PlayerViewModel>(relaxed = true).also {
        every { it.stablePlayerState } returns player
        every { it.scrollToIndexEvent } returns MutableSharedFlow()
        every { it.libraryLocateRequest } returns MutableStateFlow(null)
        every { it.locatedSongId } returns MutableStateFlow(null)
    }

    /** What the Library screen would hold: the state the list writes into. */
    private var locateShown by mutableStateOf<Boolean?>(null)
    private val registeredActions = ArrayList<(() -> Unit)?>()
    private var locateRequests = 0

    @Before
    fun install() {
        counter.install()
        every { playerViewModel.requestLocateCurrentSong() } answers { locateRequests++ }
    }

    @After
    fun uninstall() {
        counter.uninstall()
    }

    @Composable
    private fun TheList() {
        val items = flowOf(PagingData.from(songs)).collectAsLazyPagingItems()
        LibrarySongsTab(
            songs = items,
            isLoading = false,
            playerViewModel = playerViewModel,
            bottomBarHeight = 0.dp,
            onMoreOptionsClick = {},
            isRefreshing = false,
            onRefresh = {},
            sortOption = SortOption.SongTitleAZ,
            onLocateCurrentSongVisibilityChanged = { locateShown = it },
            onRegisterLocateCurrentSongAction = { registeredActions += it },
            hasCurrentSong = true,
        )
    }

    private fun show() {
        compose.setContent { TheList() }
        compose.waitForIdle()
    }

    /** The song changes to [index] (whatever started it) and the composables that ran for it. */
    private fun change(index: Int): Map<String, Int> {
        compose.waitForIdle()
        val before = counter.counts()
        player.value = player.value.copy(currentSong = songs[index], currentMediaItemIndex = index)
        compose.waitForIdle()
        return counter.since(before)
    }

    @Test
    fun `a song change does not re-run the list, whether it is a next or a tap on a row`() {
        show()
        // the counter sees the list's own composition and its rows, or the zeros below would mean nothing
        assertThat(counter.count("LibrarySongsTab")).isAtLeast(1)
        assertThat(counter.count("LibraryPlaybackAwareSongItem")).isAtLeast(5)

        val next = change(4)        // media-next: the row below the one that was playing
        val tap = change(7)         // a tap on a visible row further down

        assertThat(next["LibraryPlaybackAwareSongItem"] ?: 0).isAtLeast(1)
        assertThat(tap["LibraryPlaybackAwareSongItem"] ?: 0).isAtLeast(1)
        assertThat(next["LibrarySongsTab"] ?: 0).isEqualTo(0)
        assertThat(tap["LibrarySongsTab"] ?: 0).isEqualTo(0)
        // the rows: the one that was playing and the one that plays now, once each
        assertThat(next["LibraryPlaybackAwareSongItem"] ?: 0).isAtMost(2)
        assertThat(tap["LibraryPlaybackAwareSongItem"] ?: 0).isAtMost(2)
    }

    @Test
    fun `a tap does not recompose more than a next does, beyond the tapped row`() {
        show()

        val next = change(4)
        val tap = change(8)

        // Every composable a tap ran, a next ran at least as often (each changes exactly two rows)...
        for ((name, ran) in tap) {
            assertThat(ran).isAtMost((next[name] ?: 0))
        }
        // ...and a song change is cheap in absolute terms too: the two rows' own composables and nothing at list level.
        assertThat(tap.values.sum()).isAtMost(next.values.sum())
        assertThat(tap.keys.filter { it.startsWith("Library") }).containsExactly("LibraryPlaybackAwareSongItem")
    }

    @Test
    fun `the locate action registered with the Library screen is one lambda for the whole session, not one per song`() {
        show()
        val registeredAtStart = registeredActions.size
        val first = registeredActions.lastOrNull()
        assertThat(first).isNotNull()

        for (index in listOf(4, 9, 2, 6, 5)) change(index)

        // B23 handed the Library screen a fresh lambda for every song change, and the screen re-ran for each one.
        assertThat(registeredActions.size).isEqualTo(registeredAtStart)
        assertThat(registeredActions.last()).isSameInstanceAs(first)
        first!!.invoke()
        assertThat(locateRequests).isEqualTo(1)
    }

    @Test
    fun `the locate button follows the playing row on and off the screen, and only then`() {
        show()
        assertThat(locateShown).isFalse()                 // the playing row (3) is on screen

        change(7)                                         // a tap on another visible row
        assertThat(locateShown).isFalse()

        change(300)                                       // the playing song is nowhere near the rows shown
        assertThat(locateShown).isTrue()

        change(5)
        assertThat(locateShown).isFalse()
    }
}
