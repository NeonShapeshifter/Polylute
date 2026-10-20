package com.lostf1sh.pixelplayeross.presentation.viewmodel

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.media.SongMetadataEditor
import com.lostf1sh.pixelplayeross.data.model.Lyrics
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.repository.MusicRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import com.lostf1sh.pixelplayeross.data.model.LyricsSourcePreference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LyricsStateHolderTest {

    @Test
    fun withPersistedLyrics_replacesAlbumArtUriWhenMetadataWriteRefreshesArtworkPath() {
        val originalSong = testSong(albumArtUriString = "file:///cache/song_art_1_old.jpg")

        val updatedSong = originalSong.withPersistedLyrics(
            rawLyrics = "New lyrics",
            refreshedAlbumArtUri = "file:///cache/song_art_1_new.jpg"
        )

        assertThat(updatedSong.lyrics).isEqualTo("New lyrics")
        assertThat(updatedSong.albumArtUriString).isEqualTo("file:///cache/song_art_1_new.jpg")
    }

    @Test
    fun withPersistedLyrics_keepsExistingAlbumArtUriWhenMetadataWriteDoesNotReturnOne() {
        val originalSong = testSong(albumArtUriString = "content://art/song_art_1.jpg")

        val updatedSong = originalSong.withPersistedLyrics(
            rawLyrics = "Imported lyrics",
            refreshedAlbumArtUri = null
        )

        assertThat(updatedSong.lyrics).isEqualTo("Imported lyrics")
        assertThat(updatedSong.albumArtUriString).isEqualTo("content://art/song_art_1.jpg")
    }

    @Test
    fun fetchLyricsForSong_usesStoredLyricsWithoutRemoteFetch() = runTest {
        val musicRepository = mockk<MusicRepository>(relaxed = true)
        val userPreferencesRepository = mockk<UserPreferencesRepository>(relaxed = true)
        val songMetadataEditor = mockk<SongMetadataEditor>(relaxed = true)
        val holder = LyricsStateHolder(
            musicRepository = musicRepository,
            userPreferencesRepository = userPreferencesRepository,
            songMetadataEditor = songMetadataEditor,
            appScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            dispatchers = TestDispatcherProvider(UnconfinedTestDispatcher(testScheduler))
        )
        val callback = RecordingLyricsLoadCallback()
        val state = MutableStateFlow(StablePlayerState())
        val song = testSong(albumArtUriString = "content://art/song_art_1.jpg").copy(
            lyrics = "Stored lyrics"
        )
        val storedLyrics = Lyrics(plain = listOf("Stored lyrics"), areFromRemote = false)

        holder.initialize(callback, state)
        coEvery { musicRepository.getStoredLyrics(song) } returns (storedLyrics to "Stored lyrics")

        holder.searchUiState.test {
            assertThat(awaitItem()).isEqualTo(LyricsSearchUiState.Idle)

            holder.fetchLyricsForSong(
                song = song,
                forcePickResults = false,
                sourcePreference = com.lostf1sh.pixelplayeross.data.model.LyricsSourcePreference.API_FIRST
            ) { "Lyrics already available" }

            assertThat(awaitItem()).isEqualTo(LyricsSearchUiState.Loading)
            assertThat(awaitItem()).isEqualTo(LyricsSearchUiState.Success(storedLyrics))
            cancelAndIgnoreRemainingEvents()
        }

        assertThat(holder.searchUiState.value).isEqualTo(LyricsSearchUiState.Success(storedLyrics))
        coVerify(exactly = 1) { musicRepository.getStoredLyrics(song) }
        coVerify(exactly = 0) { musicRepository.getLyricsFromRemote(any()) }
        coVerify(exactly = 0) { musicRepository.searchRemoteLyrics(any()) }
    }

    private fun testSong(albumArtUriString: String?): Song {
        return Song(
            id = "1",
            title = "Indian Summer",
            artist = "Blood Cultures",
            album = "Happy Birthday",
            path = "/music/indian-summer.mp3",
            contentUriString = "content://media/external/audio/media/1",
            albumArtUriString = albumArtUriString,
            duration = 295_000L,
            mimeType = "audio/mpeg",
            bitrate = 320_000,
            sampleRate = 44_100,
            artistId = 1L,
            albumId = 1L
        )
    }

    private class RecordingLyricsLoadCallback : LyricsLoadCallback {
        override fun onLoadingStarted(requestId: Long, songId: String) = Unit

        override fun onLyricsSettled(requestId: Long, songId: String, outcome: LyricsOutcome) = Unit
    }

    // --- the holder outlives every screen ------------------------------------------------------

    /**
     * The defect this guards against: lyrics resolution ran on a scope borrowed from a
     * `PlayerViewModel`, so a NavHost destination being popped could cancel in-flight work and
     * leave the UI on a loading state that nothing would ever clear. The holder now runs on an
     * injected application scope, so a transient owner dying is simply irrelevant to it.
     */
    @Test
    fun `resolution completes even though the owner that asked for it was destroyed`() = runTest {
        val musicRepository = mockk<MusicRepository>(relaxed = true)
        val holder = LyricsStateHolder(
            musicRepository = musicRepository,
            userPreferencesRepository = mockk(relaxed = true),
            songMetadataEditor = mockk(relaxed = true),
            appScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            dispatchers = TestDispatcherProvider(UnconfinedTestDispatcher(testScheduler))
        )
        val loadedSongIds = mutableListOf<String>()
        holder.initialize(
            callback = object : LyricsLoadCallback {
                override fun onLoadingStarted(requestId: Long, songId: String) = Unit
                override fun onLyricsSettled(requestId: Long, songId: String, outcome: LyricsOutcome) {
                    loadedSongIds += songId
                }
            },
            stablePlayerState = MutableStateFlow(StablePlayerState())
        )
        val song = testSong(albumArtUriString = null)
        coEvery { musicRepository.getStoredLyrics(song) } returns (
            Lyrics(plain = listOf("Stored lyrics"), areFromRemote = false) to "Stored lyrics"
            )

        // A screen asks for lyrics, then goes away while the work is still running.
        val transientOwner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        holder.loadLyricsForSong(song, LyricsSourcePreference.API_FIRST)
        transientOwner.cancel()
        advanceUntilIdle()

        assertThat(loadedSongIds).containsExactly("1")
    }

    /**
     * Navigation constructs and destroys owners repeatedly. Initialising the holder from a new one
     * must not disturb work already in flight, and must not require a new scope.
     */
    @Test
    fun `a later UI owner initialising again leaves in flight work alone`() = runTest {
        val musicRepository = mockk<MusicRepository>(relaxed = true)
        val holder = LyricsStateHolder(
            musicRepository = musicRepository,
            userPreferencesRepository = mockk(relaxed = true),
            songMetadataEditor = mockk(relaxed = true),
            appScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            dispatchers = TestDispatcherProvider(UnconfinedTestDispatcher(testScheduler))
        )
        val loadedSongIds = mutableListOf<String>()
        val callback = object : LyricsLoadCallback {
            override fun onLoadingStarted(requestId: Long, songId: String) = Unit
            override fun onLyricsSettled(requestId: Long, songId: String, outcome: LyricsOutcome) {
                loadedSongIds += songId
            }
        }
        val state = MutableStateFlow(StablePlayerState())
        val song = testSong(albumArtUriString = null)
        coEvery { musicRepository.getStoredLyrics(song) } returns (
            Lyrics(plain = listOf("Stored lyrics"), areFromRemote = false) to "Stored lyrics"
            )

        holder.initialize(callback, state)
        holder.loadLyricsForSong(song, LyricsSourcePreference.API_FIRST)

        // A second owner arrives while the first request is still running.
        holder.initialize(callback, state)
        advanceUntilIdle()

        assertThat(loadedSongIds).containsExactly("1")
    }

    /**
     * Determinism: the work is driven by the test scheduler, not by a real dispatcher, so the
     * result is observable exactly after `advanceUntilIdle` and not before. If the holder ever
     * goes back to a hard-coded dispatcher this stops being true.
     */
    @Test
    fun `resolution is driven by the injected scope, so virtual time still governs it`() = runTest {
        val musicRepository = mockk<MusicRepository>(relaxed = true)
        val holder = LyricsStateHolder(
            musicRepository = musicRepository,
            userPreferencesRepository = mockk(relaxed = true),
            songMetadataEditor = mockk(relaxed = true),
            appScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            dispatchers = TestDispatcherProvider(UnconfinedTestDispatcher(testScheduler))
        )
        val loadedSongIds = mutableListOf<String>()
        holder.initialize(
            callback = object : LyricsLoadCallback {
                override fun onLoadingStarted(requestId: Long, songId: String) = Unit
                override fun onLyricsSettled(requestId: Long, songId: String, outcome: LyricsOutcome) {
                    loadedSongIds += songId
                }
            },
            stablePlayerState = MutableStateFlow(StablePlayerState())
        )
        val song = testSong(albumArtUriString = null)
        coEvery { musicRepository.getStoredLyrics(song) } returns (
            Lyrics(plain = listOf("Stored lyrics"), areFromRemote = false) to "Stored lyrics"
            )

        holder.loadLyricsForSong(song, LyricsSourcePreference.API_FIRST)
        advanceUntilIdle()

        assertThat(loadedSongIds).containsExactly("1")
    }

    // --- every request reaches a terminal outcome ----------------------------------------------
    //
    // The defect these guard: `onLyricsLoaded` used to be the last statement of the resolve
    // coroutine, so any cancellation between "Loading" and that line left the surface loading
    // with nothing alive that could ever finish it. These tests assert the contract that
    // replaced it — one terminal outcome per request that is not superseded, and no path that
    // ends without one.

    private class Journal : LyricsLoadCallback {
        val events = mutableListOf<String>()

        override fun onLoadingStarted(requestId: Long, songId: String) {
            events += "loading($requestId,$songId)"
        }

        override fun onLyricsSettled(requestId: Long, songId: String, outcome: LyricsOutcome) {
            val kind = when (outcome) {
                is LyricsOutcome.Resolved -> "resolved($requestId,${outcome.lyrics?.plain?.firstOrNull()})"
                is LyricsOutcome.Failed -> "failed($requestId)"
                LyricsOutcome.Cancelled -> "cancelled($requestId)"
            }
            events += kind
        }

        val terminalCount: Int
            get() = events.count { it.startsWith("resolved") || it.startsWith("failed") || it.startsWith("cancelled") }
    }

    private fun holderWith(
        musicRepository: MusicRepository,
        testScheduler: kotlinx.coroutines.test.TestCoroutineScheduler,
    ) = LyricsStateHolder(
        musicRepository = musicRepository,
        userPreferencesRepository = mockk(relaxed = true),
        songMetadataEditor = mockk(relaxed = true),
        appScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        dispatchers = TestDispatcherProvider(UnconfinedTestDispatcher(testScheduler))
    )

    @Test
    fun `a resolve that finds lyrics settles as resolved`() = runTest {
        val musicRepository = mockk<MusicRepository>(relaxed = true)
        val found = Lyrics(plain = listOf("Found"), areFromRemote = false)
        coEvery { musicRepository.getLyrics(any(), any()) } returns found
        val holder = holderWith(musicRepository, testScheduler)
        val journal = Journal()
        holder.initialize(journal, MutableStateFlow(StablePlayerState()))

        holder.loadLyricsForSong(testSong(null), LyricsSourcePreference.EMBEDDED_FIRST)
        advanceUntilIdle()

        assertThat(journal.events).containsExactly("loading(1,1)", "resolved(1,Found)")
    }

    @Test
    fun `a resolve that finds nothing still settles, as a resolved null`() = runTest {
        val musicRepository = mockk<MusicRepository>(relaxed = true)
        coEvery { musicRepository.getLyrics(any(), any()) } returns null
        val holder = holderWith(musicRepository, testScheduler)
        val journal = Journal()
        holder.initialize(journal, MutableStateFlow(StablePlayerState()))

        holder.loadLyricsForSong(testSong(null), LyricsSourcePreference.EMBEDDED_FIRST)
        advanceUntilIdle()

        // "No lyrics" is a result, not a failure to produce one — the surface says so differently.
        assertThat(journal.events).containsExactly("loading(1,1)", "resolved(1,null)")
    }

    @Test
    fun `a resolve that throws settles as failed`() = runTest {
        val musicRepository = mockk<MusicRepository>(relaxed = true)
        coEvery { musicRepository.getLyrics(any(), any()) } throws IllegalStateException("boom")
        val holder = holderWith(musicRepository, testScheduler)
        val journal = Journal()
        holder.initialize(journal, MutableStateFlow(StablePlayerState()))

        holder.loadLyricsForSong(testSong(null), LyricsSourcePreference.EMBEDDED_FIRST)
        advanceUntilIdle()

        assertThat(journal.events).containsExactly("loading(1,1)", "failed(1)")
    }

    @Test
    fun `cancelling a resolve publishes a terminal outcome rather than leaving it loading`() = runTest {
        val musicRepository = mockk<MusicRepository>(relaxed = true)
        val gate = CompletableDeferred<Lyrics?>()
        coEvery { musicRepository.getLyrics(any(), any()) } coAnswers { gate.await() }
        val holder = holderWith(musicRepository, testScheduler)
        val journal = Journal()
        holder.initialize(journal, MutableStateFlow(StablePlayerState()))

        holder.loadLyricsForSong(testSong(null), LyricsSourcePreference.EMBEDDED_FIRST)
        advanceUntilIdle()
        assertThat(journal.events).containsExactly("loading(1,1)")

        holder.cancelLoading()
        advanceUntilIdle()

        // The whole point: Loading is never the last word.
        assertThat(journal.events).containsExactly("loading(1,1)", "cancelled(1)")
    }

    @Test
    fun `an explicit fetch ends the in flight resolve instead of orphaning it`() = runTest {
        val musicRepository = mockk<MusicRepository>(relaxed = true)
        val gate = CompletableDeferred<Lyrics?>()
        coEvery { musicRepository.getLyrics(any(), any()) } coAnswers { gate.await() }
        coEvery { musicRepository.getStoredLyrics(any()) } returns null
        // The fetch itself is not what this test is about; it only has to run far enough that the
        // superseded resolve would have had its chance to publish.
        coEvery { musicRepository.getLyricsFromRemote(any()) } returns
            Result.failure(IllegalStateException("offline"))
        val holder = holderWith(musicRepository, testScheduler)
        val journal = Journal()
        holder.initialize(journal, MutableStateFlow(StablePlayerState()))

        holder.loadLyricsForSong(testSong(null), LyricsSourcePreference.EMBEDDED_FIRST)
        advanceUntilIdle()

        // This used to be a bare `loadingJob?.cancel()`, which left the resolve's Loading state
        // with no owner — the permanent "Cargando letras".
        holder.fetchLyricsForSong(
            song = testSong(null),
            forcePickResults = false,
            sourcePreference = LyricsSourcePreference.EMBEDDED_FIRST,
        ) { "already available" }
        advanceUntilIdle()

        assertThat(journal.events).contains("cancelled(1)")
        assertThat(journal.terminalCount).isAtLeast(1)
    }

    @Test
    fun `replacing request A with request B leaves exactly one terminal outcome for A`() = runTest {
        val musicRepository = mockk<MusicRepository>(relaxed = true)
        val first = CompletableDeferred<Lyrics?>()
        coEvery { musicRepository.getLyrics(match { it.id == "1" }, any()) } coAnswers { first.await() }
        coEvery { musicRepository.getLyrics(match { it.id == "2" }, any()) } returns
            Lyrics(plain = listOf("Second"), areFromRemote = false)
        val holder = holderWith(musicRepository, testScheduler)
        val journal = Journal()
        holder.initialize(journal, MutableStateFlow(StablePlayerState()))

        holder.loadLyricsForSong(testSong(null), LyricsSourcePreference.EMBEDDED_FIRST)
        advanceUntilIdle()
        holder.loadLyricsForSong(testSong(null).copy(id = "2"), LyricsSourcePreference.EMBEDDED_FIRST)
        advanceUntilIdle()

        // A was superseded, not cancelled — B owns the surface and A must not write to it.
        assertThat(journal.events).containsExactly("loading(1,1)", "loading(2,2)", "resolved(2,Second)")
    }

    @Test
    fun `a stale completion from A cannot overwrite B's result`() = runTest {
        val musicRepository = mockk<MusicRepository>(relaxed = true)
        val first = CompletableDeferred<Lyrics?>()
        coEvery { musicRepository.getLyrics(match { it.id == "1" }, any()) } coAnswers { first.await() }
        coEvery { musicRepository.getLyrics(match { it.id == "2" }, any()) } returns
            Lyrics(plain = listOf("Second"), areFromRemote = false)
        val holder = holderWith(musicRepository, testScheduler)
        val journal = Journal()
        holder.initialize(journal, MutableStateFlow(StablePlayerState()))

        holder.loadLyricsForSong(testSong(null), LyricsSourcePreference.EMBEDDED_FIRST)
        advanceUntilIdle()
        holder.loadLyricsForSong(testSong(null).copy(id = "2"), LyricsSourcePreference.EMBEDDED_FIRST)
        advanceUntilIdle()

        // A finally produces its answer, long after B already won. It must be ignored.
        first.complete(Lyrics(plain = listOf("First"), areFromRemote = false))
        advanceUntilIdle()

        assertThat(journal.events).doesNotContain("resolved(1,First)")
        assertThat(journal.events.last()).isEqualTo("resolved(2,Second)")
    }

    @Test
    fun `no sequence of requests can leave the surface loading`() = runTest {
        val musicRepository = mockk<MusicRepository>(relaxed = true)
        val gate = CompletableDeferred<Lyrics?>()
        coEvery { musicRepository.getLyrics(any(), any()) } coAnswers { gate.await() }
        val holder = holderWith(musicRepository, testScheduler)
        val journal = Journal()
        holder.initialize(journal, MutableStateFlow(StablePlayerState()))

        holder.loadLyricsForSong(testSong(null), LyricsSourcePreference.EMBEDDED_FIRST)
        advanceUntilIdle()
        holder.cancelLoading()
        advanceUntilIdle()

        val lastLoadingIndex = journal.events.indexOfLast { it.startsWith("loading") }
        val terminalAfterLastLoading = journal.events
            .drop(lastLoadingIndex + 1)
            .any { it.startsWith("resolved") || it.startsWith("failed") || it.startsWith("cancelled") }

        assertThat(terminalAfterLastLoading).isTrue()
    }
}
