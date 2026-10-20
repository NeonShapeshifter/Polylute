package com.lostf1sh.pixelplayeross.presentation.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.model.SortOption
import com.lostf1sh.pixelplayeross.presentation.viewmodel.LibraryLocator
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.StablePlayerState
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * B32-fix1 R1 (the auditor's R32-01, seen on the phone: Stats, a song, the Library opened at the top and never scrolled to it).
 *
 * The real Songs tab ([LibrarySongsTab]) and the real [LibraryLocator] are joined as the app joins them: the Songs list takes a request,
 * asks where the song is (a query that can suspend, as the Room one does), scrolls, and the row is marked. Only the view model's three
 * seams are delegated through a mock, and the player is a mock that records every call, so "no playback" is checked, not assumed.
 *
 * Before the fix the list took the request, the locator cleared it before the query had answered, Compose saw the key go null and
 * cancelled the effect in the middle of the query, `runCatching` read the cancellation as "the song is not in the list", and the list
 * stayed at the top with a false "not found". Each test below ends on the song and the mark, with no false "not found".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xxhdpi")
class B32Fix1LocateIntegrationTest {
    @get:Rule
    val compose = createComposeRule()

    private fun song(i: Int) = Song(
        id = "id-$i", title = "Cancion $i", artist = "Mägo de Oz", artistId = 7L, album = "Gaia", albumId = 9L, path = "/m/$i.flac",
        contentUriString = "content://$i", albumArtUriString = null, duration = 280_000L, mimeType = "audio/flac", bitrate = 1003, sampleRate = 44100,
    )

    private val songs = (0 until 400).map(::song)
    private val player = MutableStateFlow(StablePlayerState(currentSong = songs[0], isPlaying = false))

    // the locator's own scope runs its mark timer in real time: 60 s is never reached inside a test
    private val locatorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val asked = CopyOnWriteArrayList<String>()
    private val notFound = AtomicInteger()

    /** What the library answers for a song id: set per test. `id-N` is at index N of the list as shown unless the test says otherwise. */
    private var resolver: suspend (String) -> Int = { id -> id.removePrefix("id-").toInt() }

    private val locator = LibraryLocator(
        scope = locatorScope,
        markMs = 60_000L,
        resolveIndex = { id -> asked += id; resolver(id) },
        onNotFound = { notFound.incrementAndGet() },
    )

    private val playerViewModel = mockk<PlayerViewModel>(relaxed = true).also { vm ->
        every { vm.stablePlayerState } returns player
        every { vm.scrollToIndexEvent } returns MutableSharedFlow()
        every { vm.libraryLocateRequest } returns locator.request
        every { vm.locatedSongId } returns locator.located
        coEvery { vm.takeLibraryLocate(any()) } coAnswers { locator.take(firstArg()) }
        every { vm.completeLibraryLocate(any()) } answers { locator.complete(firstArg()) } //AFTER-ONLY
        every { vm.clearLibraryLocate() } answers { locator.clear() }
    }

    private var pagingFlow by mutableStateOf<Flow<PagingData<Song>>>(flowOf(PagingData.from(songs)))

    @After
    fun stopTheTimer() {
        locatorScope.cancel()
    }

    @Composable
    private fun TheList() {
        val items = pagingFlow.collectAsLazyPagingItems()
        LibrarySongsTab(
            songs = items,
            isLoading = false,
            playerViewModel = playerViewModel,
            bottomBarHeight = 0.dp,
            onMoreOptionsClick = {},
            isRefreshing = false,
            onRefresh = {},
            sortOption = SortOption.SongTitleAZ,
            hasCurrentSong = true,
        )
    }

    private fun show() {
        compose.setContent { TheList() }
        compose.waitForIdle()
    }

    private fun ask(songId: String) {
        compose.runOnIdle { locator.request(songId) }
    }

    /**
     * Waits in real time (paging and the locator's query run on their own threads, which the compose clock does not wait for; its
     * `waitUntil` spends virtual milliseconds) until [condition] holds, idling the composition between looks.
     */
    private fun awaitRealTime(what: String, condition: () -> Boolean) {
        val limit = System.nanoTime() + 10_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < limit) { "still waiting after 10 s: $what" }
            Thread.sleep(25)
            compose.waitForIdle()
        }
    }

    private fun waitForTheQuery() = awaitRealTime("the list to ask the library") { asked.isNotEmpty() }

    private fun assertNothingPlayed() {
        verify(exactly = 0) { playerViewModel.requestLocateCurrentSong() }
        verify(exactly = 0) { playerViewModel.showAndPlaySongFromLibrary(any(), any(), any(), any()) }
        verify(exactly = 0) { playerViewModel.playSongs(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { playerViewModel.playSong(any()) }
        verify(exactly = 0) { playerViewModel.playPause() }
    }

    private fun assertEndedOn(index: Int) {
        awaitRealTime("the request to be spent") { locator.request.value == null }
        compose.waitForIdle()
        compose.onNodeWithText("Cancion $index").assertIsDisplayed()
        assertThat(locator.located.value).isEqualTo("id-$index")
        assertThat(locator.request.value).isNull()
        assertThat(notFound.get()).isEqualTo(0)
        assertNothingPlayed()
    }

    @Test
    fun `a query that is still suspended when the list has taken the request is not cancelled by it`() {
        val answer = CompletableDeferred<Int>()
        resolver = { answer.await() }
        show()
        ask("id-300")
        waitForTheQuery()
        compose.waitForIdle()
        // the list is in the middle of the query: nothing is marked yet, nothing was said, the list has not moved
        assertThat(locator.located.value).isNull()
        assertThat(notFound.get()).isEqualTo(0)
        compose.onNodeWithText("Cancion 0").assertIsDisplayed()

        compose.runOnIdle { answer.complete(300) }
        assertEndedOn(300)
    }

    @Test
    fun `a query that answers at once ends on the song`() {
        show()
        ask("id-250")
        assertEndedOn(250)
        assertThat(asked).containsExactly("id-250")
    }

    @Test
    fun `a list whose rows load later takes the request when it has rows, and ends on the song`() {
        // one paged flow, as the app has it: it emits the rows after the request was made
        val later = MutableSharedFlow<PagingData<Song>>(replay = 1)
        pagingFlow = later
        show()
        ask("id-120")
        compose.waitForIdle()
        // no rows yet: the request waits, the library has not been asked
        assertThat(asked).isEmpty()
        assertThat(locator.request.value?.songId).isEqualTo("id-120")
        assertThat(notFound.get()).isEqualTo(0)

        compose.runOnIdle { later.tryEmit(PagingData.from(songs)) }
        assertEndedOn(120)
    }

    @Test
    fun `a paged list replaced while the request waits for rows is followed, and ends on the song`() {
        pagingFlow = flowOf(PagingData.empty())
        show()
        ask("id-90")
        compose.waitForIdle()
        assertThat(asked).isEmpty()

        pagingFlow = flowOf(PagingData.from(songs))
        assertEndedOn(90)
    }

    @Test
    fun `Back during the query drops it quietly, with no mark and no notice`() {
        val answer = CompletableDeferred<Int>()
        resolver = { answer.await() }
        show()
        ask("id-300")
        waitForTheQuery()

        compose.runOnIdle { locator.clear() }
        compose.runOnIdle { answer.complete(300) }
        compose.waitForIdle()

        assertThat(locator.located.value).isNull()
        assertThat(locator.request.value).isNull()
        assertThat(notFound.get()).isEqualTo(0)
        compose.onNodeWithText("Cancion 0").assertIsDisplayed()
        assertNothingPlayed()
    }

    @Test
    fun `a new request during the query ends on the new song and never marks the old one`() {
        val firstAnswer = CompletableDeferred<Int>()
        resolver = { id -> if (id == "id-300") firstAnswer.await() else id.removePrefix("id-").toInt() }
        show()
        ask("id-300")
        waitForTheQuery()

        ask("id-40")
        compose.waitForIdle()
        compose.runOnIdle { firstAnswer.complete(300) }

        assertEndedOn(40)
        assertThat(asked).contains("id-40")
    }

    @Test
    fun `a song near the rows on screen is reached by the animated scroll and ends marked`() {
        show()
        ask("id-12")
        assertEndedOn(12)
    }

    @Test
    fun `a song that is not in the list is said once, marks nothing, and does not move the list`() {
        resolver = { -1 }
        show()
        ask("id-999")
        compose.waitForIdle()
        assertThat(notFound.get()).isEqualTo(1)
        assertThat(locator.located.value).isNull()
        assertThat(locator.request.value).isNull()
        compose.onNodeWithText("Cancion 0").assertIsDisplayed()
        assertNothingPlayed()
    }

    @Test
    fun `asking for the same song again after it was shown shows it again`() {
        show()
        ask("id-200")
        assertEndedOn(200)
        compose.runOnIdle { locator.clear() }
        ask("id-200")
        assertEndedOn(200)
        assertThat(asked).containsExactly("id-200", "id-200")
    }
}
