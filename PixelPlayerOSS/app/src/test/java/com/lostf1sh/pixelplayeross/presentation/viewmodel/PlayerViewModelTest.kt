package com.lostf1sh.pixelplayeross.presentation.viewmodel

import android.content.Context
import app.cash.turbine.test
import com.lostf1sh.pixelplayeross.data.database.AlbumArtThemeDao
import com.google.common.util.concurrent.ListenableFuture
import com.lostf1sh.pixelplayeross.data.model.SearchFilterType
import com.lostf1sh.pixelplayeross.data.model.SearchHistoryItem
import com.lostf1sh.pixelplayeross.data.model.SearchResultItem
import com.lostf1sh.pixelplayeross.data.model.Album
import com.lostf1sh.pixelplayeross.data.model.Artist
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.model.SortOption
import com.lostf1sh.pixelplayeross.data.model.StorageFilter
import com.lostf1sh.pixelplayeross.data.preferences.ThemePreferencesRepository
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.repository.MusicRepository
import io.mockk.*
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import com.lostf1sh.pixelplayeross.MainCoroutineExtension
import com.lostf1sh.pixelplayeross.data.service.player.DualPlayerEngine
import com.lostf1sh.pixelplayeross.data.worker.SyncManager
import com.lostf1sh.pixelplayeross.utils.AppShortcutManager
import com.lostf1sh.pixelplayeross.utils.MediaItemBuilder
import com.lostf1sh.pixelplayeross.presentation.viewmodel.*
import app.cash.turbine.Turbine

import androidx.core.content.ContextCompat

@ExperimentalCoroutinesApi
@ExtendWith(MainCoroutineExtension::class)
class PlayerViewModelTest {

    private lateinit var playerViewModel: PlayerViewModel
    private val mockMusicRepository: MusicRepository = mockk()
    private val mockUserPreferencesRepository: UserPreferencesRepository = mockk(relaxed = true)
    private val mockThemePreferencesRepository: ThemePreferencesRepository = mockk(relaxed = true)
    private val mockAlbumArtThemeDao: AlbumArtThemeDao = mockk(relaxed = true)
    private val mockContext: Context = mockk(relaxed = true)

    private val mockSyncManager: SyncManager = mockk(relaxed = true)
    private val mockDualPlayerEngine: DualPlayerEngine = mockk(relaxed = true)
    private val mockAppShortcutManager: AppShortcutManager = mockk(relaxed = true)
    private val mockListeningStatsTracker: ListeningStatsTracker = mockk(relaxed = true)
    private val mockDailyMixStateHolder: DailyMixStateHolder = mockk(relaxed = true)
    private val mockLyricsStateHolder: LyricsStateHolder = mockk(relaxed = true)
    private val mockQueueStateHolder: QueueStateHolder = mockk(relaxed = true)
    private val mockQueueUndoStateHolder: QueueUndoStateHolder = mockk(relaxed = true)
    private val mockPlaybackStateHolder: PlaybackStateHolder = mockk(relaxed = true)
    private val mockConnectivityStateHolder: ConnectivityStateHolder = mockk(relaxed = true)
    private val mockSleepTimerStateHolder: SleepTimerStateHolder = mockk(relaxed = true)
    private val mockSearchStateHolder: SearchStateHolder = mockk(relaxed = true)
    private val mockLibraryStateHolder: LibraryStateHolder = mockk(relaxed = true)
    private val mockFolderNavigationStateHolder: FolderNavigationStateHolder = mockk(relaxed = true)
    private val mockLibraryTabsStateHolder: LibraryTabsStateHolder = mockk(relaxed = true)
    private val mockMetadataEditStateHolder: MetadataEditStateHolder = mockk(relaxed = true)
    private val mockSongRemovalStateHolder: SongRemovalStateHolder = mockk(relaxed = true)
    private val mockExternalMediaStateHolder: ExternalMediaStateHolder = mockk(relaxed = true)
    private val mockThemeStateHolder: ThemeStateHolder = mockk(relaxed = true)
    private val mockMultiSelectionStateHolder: MultiSelectionStateHolder = mockk(relaxed = true)
    private val mockPlaylistSelectionStateHolder: PlaylistSelectionStateHolder = mockk(relaxed = true)
    private lateinit var mockMediaControllerFactory: com.lostf1sh.pixelplayeross.data.media.MediaControllerFactory

    private val testDispatcher = StandardTestDispatcher()

    private val _allSongsFlow = MutableStateFlow<ImmutableList<Song>>(persistentListOf())
    private val _searchHistoryFlow = MutableStateFlow<ImmutableList<SearchHistoryItem>>(persistentListOf())
    private val _searchResultsFlow = MutableStateFlow<ImmutableList<SearchResultItem>>(persistentListOf())
    private val _selectedSearchFilterFlow = MutableStateFlow(SearchFilterType.ALL)
    private val _repeatModeFlow = MutableStateFlow(Player.REPEAT_MODE_OFF)
    private val _favoriteIdsFlow = MutableStateFlow<Set<String>>(emptySet())
    private lateinit var stablePlayerStateFlow: MutableStateFlow<StablePlayerState>
    private lateinit var mockController: MediaController
    private val controllerRepeatModeWrites = mutableListOf<Int>()
    private var controllerRepeatMode = Player.REPEAT_MODE_OFF

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        MockKAnnotations.init(this)

        mockkStatic(ContextCompat::class)
        val directExecutor = java.util.concurrent.Executor { it.run() }
        every { ContextCompat.getMainExecutor(any()) } returns directExecutor
        coEvery { mockUserPreferencesRepository.favoriteSongIdsFlow } returns flowOf(emptySet())
        coEvery { mockUserPreferencesRepository.songsSortOptionFlow } returns flowOf("SongTitleAZ")
        coEvery { mockUserPreferencesRepository.albumsSortOptionFlow } returns flowOf("AlbumTitleAZ")
        coEvery { mockUserPreferencesRepository.artistsSortOptionFlow } returns flowOf("ArtistNameAZ")
        coEvery { mockUserPreferencesRepository.likedSongsSortOptionFlow } returns flowOf("LikedSongTitleAZ")
        coEvery { mockUserPreferencesRepository.navBarCornerRadiusFlow } returns flowOf(32)
        coEvery { mockUserPreferencesRepository.navBarStyleFlow } returns flowOf("Default")
        coEvery { mockUserPreferencesRepository.libraryNavigationModeFlow } returns flowOf("TabRow")
        coEvery { mockUserPreferencesRepository.carouselStyleFlow } returns flowOf("NoPeek")
        coEvery { mockUserPreferencesRepository.fullPlayerLoadingTweaksFlow } returns flowOf(com.lostf1sh.pixelplayeross.data.preferences.FullPlayerLoadingTweaks())
        coEvery { mockUserPreferencesRepository.tapBackgroundClosesPlayerFlow } returns flowOf(true)
        coEvery { mockUserPreferencesRepository.hapticsEnabledFlow } returns flowOf(true)
        coEvery { mockUserPreferencesRepository.foldersSortOptionFlow } returns flowOf("FolderNameAZ")
        coEvery { mockUserPreferencesRepository.persistentShuffleEnabledFlow } returns flowOf(false)
        coEvery { mockUserPreferencesRepository.isShuffleOnFlow } returns flowOf(false)
        every { mockUserPreferencesRepository.repeatModeFlow } returns _repeatModeFlow
        coEvery { mockThemePreferencesRepository.playerThemePreferenceFlow } returns flowOf("Global")
        every { mockLibraryStateHolder.allSongs } returns _allSongsFlow
        every { mockLibraryStateHolder.isLoadingLibrary } returns MutableStateFlow(false)
        every { mockLibraryStateHolder.isLoadingCategories } returns MutableStateFlow(false)
        every { mockLibraryStateHolder.genres } returns MutableStateFlow(persistentListOf())
        every { mockLibraryStateHolder.albums } returns MutableStateFlow(persistentListOf())
        every { mockLibraryStateHolder.artists } returns MutableStateFlow(persistentListOf())
        every { mockLibraryStateHolder.musicFolders } returns MutableStateFlow(persistentListOf())
        every { mockLibraryStateHolder.currentSongSortOption } returns MutableStateFlow<SortOption>(SortOption.SongTitleAZ)
        every { mockLibraryStateHolder.currentAlbumSortOption } returns MutableStateFlow<SortOption>(SortOption.AlbumTitleAZ)
        every { mockLibraryStateHolder.currentArtistSortOption } returns MutableStateFlow<SortOption>(SortOption.ArtistNameAZ)
        every { mockLibraryStateHolder.currentFolderSortOption } returns MutableStateFlow<SortOption>(SortOption.FolderNameAZ)
        every { mockLibraryStateHolder.currentFavoriteSortOption } returns MutableStateFlow<SortOption>(SortOption.LikedSongTitleAZ)
        every { mockLibraryStateHolder.currentStorageFilter } returns MutableStateFlow(StorageFilter.ALL)

        every { mockSearchStateHolder.searchHistory } returns _searchHistoryFlow
        every { mockSearchStateHolder.searchResults } returns _searchResultsFlow
        every { mockSearchStateHolder.selectedSearchFilter } returns _selectedSearchFilterFlow
        every { mockSearchStateHolder.loadSearchHistory(any()) } just runs
        every { mockSearchStateHolder.clearSearchHistory() } just runs
        every { mockSearchStateHolder.deleteSearchHistoryItem(any()) } just runs
        every { mockSearchStateHolder.updateSearchFilter(any()) } just runs
        every { mockSearchStateHolder.initialize() } just runs

        every { mockConnectivityStateHolder.initialize() } just runs

        _favoriteIdsFlow.value = emptySet()
        stablePlayerStateFlow = MutableStateFlow(StablePlayerState(currentSong = null))
        every { mockPlaybackStateHolder.stablePlayerState } returns stablePlayerStateFlow
        every { mockPlaybackStateHolder.setMediaController(any()) } just runs

        every { mockSleepTimerStateHolder.initialize(any(), any(), any(), any(), any()) } just runs
        every { mockLibraryStateHolder.initialize(any()) } just runs

        every { mockMusicRepository.getPaginatedSongs(any(), any()) } returns flowOf(androidx.paging.PagingData.empty())
        every { mockMusicRepository.getPaginatedFavoriteSongs(any(), any()) } returns flowOf(androidx.paging.PagingData.empty())
        every { mockMusicRepository.getAudioFiles() } returns flowOf(emptyList())
        every { mockMusicRepository.getDistinctAlbumArtSongs() } returns flowOf(emptyList())
        every { mockMusicRepository.getHomeMixPreviewSongs(any()) } returns flowOf(emptyList())
        every { mockMusicRepository.getSongCountFlow() } returns flowOf(0)
        every { mockMusicRepository.searchSongs(any(), any()) } returns flowOf(emptyList())
        every { mockMusicRepository.getMusicByGenre(any()) } returns flowOf(emptyList())
        coEvery { mockMusicRepository.getFavoriteSongIdsOnce() } returns emptySet()
        every { mockMusicRepository.getFavoriteSongIdsFlow() } returns _favoriteIdsFlow
        every { mockMusicRepository.getSong(any()) } returns flowOf(null)
        coEvery { mockMusicRepository.getSongIdByContentUri(any()) } returns null
        coEvery { mockMusicRepository.getSongByPath(any()) } returns null
        coEvery { mockMusicRepository.setFavoriteStatus(any(), any()) } just Runs
        coEvery { mockMusicRepository.getAllSongsOnce() } returns emptyList()
        coEvery { mockMusicRepository.getFavoriteSongsOnce(any()) } returns emptyList()
        coEvery { mockMusicRepository.getFirstPlayableSong() } returns null
        coEvery { mockMusicRepository.getSongIdsSorted(any(), any()) } returns emptyList()
        coEvery { mockMusicRepository.getFavoriteSongIdsSorted(any(), any()) } returns emptyList()
        every { mockLyricsStateHolder.songUpdates } returns MutableSharedFlow()

        val sessionToken = mockk<SessionToken>(relaxed = true)
        mockMediaControllerFactory = mockk(relaxed = true)
        
        mockController = mockk(relaxed = true)
        every { mockController.repeatMode } answers { controllerRepeatMode }
        every { mockController.repeatMode = any() } answers {
            controllerRepeatMode = firstArg()
            controllerRepeatModeWrites += controllerRepeatMode
        }
        val mockFuture = mockk<ListenableFuture<MediaController>>(relaxed = true)
        every { mockFuture.get() } returns mockController
        every { mockFuture.addListener(any(), any()) } answers {
            val runnable = firstArg<Runnable>()
            runnable.run()
        }
        every { mockMediaControllerFactory.create(any(), any(), any()) } returns mockFuture
        
        playerViewModel = PlayerViewModel(
            mockContext,
            mockMusicRepository,
            mockUserPreferencesRepository,
            mockThemePreferencesRepository,
            mockAlbumArtThemeDao,
            mockSyncManager,
            mockDualPlayerEngine,
            mockAppShortcutManager,
            mockListeningStatsTracker,
            mockDailyMixStateHolder,
            mockLyricsStateHolder,
            mockQueueStateHolder,
            mockQueueUndoStateHolder,
            mockPlaybackStateHolder,
            mockConnectivityStateHolder,
            mockSleepTimerStateHolder,
            mockSearchStateHolder,
            mockLibraryStateHolder,
            mockFolderNavigationStateHolder,
            mockLibraryTabsStateHolder,
            mockMetadataEditStateHolder,
            mockSongRemovalStateHolder,
            mockExternalMediaStateHolder,
            mockThemeStateHolder,
            mockMultiSelectionStateHolder,
            mockPlaylistSelectionStateHolder,
            sessionToken,
            mockMediaControllerFactory
        )
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Nested
    @DisplayName("GetSongUrisForGenre Tests")
    inner class GetSongUrisForGenreTests {

        private val song1 = Song(id = "1", title = "Song 1", artist = "Artist A", genre = "Rock", albumArtUriString = "rock_cover1.png", artistId = 1L, albumId = 1L, contentUriString = "content://dummy/1", duration = 180000L, bitrate = null, sampleRate = null, album = "Album", path = "path", mimeType = "audio/mpeg")
        private val song2 = Song(id = "2", title = "Song 2", artist = "Artist B", genre = "Pop", albumArtUriString = "pop_cover1.png", artistId = 2L, albumId = 2L, contentUriString = "content://dummy/2", duration = 200000L, bitrate = null, sampleRate = null, album = "Album", path = "path", mimeType = "audio/mpeg")
        private val song3 = Song(id = "3", title = "Song 3", artist = "Artist A", genre = "Rock", albumArtUriString = "rock_cover2.png", artistId = 3L, albumId = 3L, contentUriString = "content://dummy/3", duration = 190000L, bitrate = null, sampleRate = null, album = "Album", path = "path", mimeType = "audio/mpeg")
        private val song4 = Song(id = "4", title = "Song 4", artist = "Artist C", genre = "Jazz", albumArtUriString = "jazz_cover1.png", artistId = 4L, albumId = 4L, contentUriString = "content://dummy/4", duration = 210000L, bitrate = null, sampleRate = null, album = "Album", path = "path", mimeType = "audio/mpeg")
        private val song5 = Song(id = "5", title = "Song 5", artist = "Artist A", genre = "Rock", albumArtUriString = "rock_cover3.png", artistId = 5L, albumId = 5L, contentUriString = "content://dummy/5", duration = 220000L, bitrate = null, sampleRate = null, album = "Album", path = "path", mimeType = "audio/mpeg")
        private val song6 = Song(id = "6", title = "Song 6", artist = "Artist A", genre = "Rock", albumArtUriString = "rock_cover4.png", artistId = 6L, albumId = 6L, contentUriString = "content://dummy/6", duration = 230000L, bitrate = null, sampleRate = null, album = "Album", path = "path", mimeType = "audio/mpeg")
        private val song7 = Song(id = "7", title = "Song 7", artist = "Artist A", genre = "Rock", albumArtUriString = "rock_cover5.png", artistId = 7L, albumId = 7L, contentUriString = "content://dummy/7", duration = 240000L, bitrate = null, sampleRate = null, album = "Album", path = "path", mimeType = "audio/mpeg")
        private val song8 = Song(id = "8", title = "Song 8", artist = "Artist A", genre = "Rock", albumArtUriString = "", artistId = 8L, albumId = 8L, contentUriString = "content://dummy/8", duration = 250000L, bitrate = null, sampleRate = null, album = "Album", path = "path", mimeType = "audio/mpeg")
        private val song9 = Song(id = "9", title = "Song 9", artist = "Artist D", genre = null, albumArtUriString = "null_genre_cover.png", artistId = 9L, albumId = 9L, contentUriString = "content://dummy/9", duration = 260000L, bitrate = null, sampleRate = null, album = "Album", path = "path", mimeType = "audio/mpeg")

        private fun setupViewModelWithSongs(songs: List<Song>) {
            _allSongsFlow.value = songs.toImmutableList()
            testDispatcher.scheduler.advanceUntilIdle()
            
            val genreSlot = slot<String>()
            every { mockMusicRepository.getMusicByGenre(capture(genreSlot)) } answers {
                val genre = genreSlot.captured
                val filtered = songs.filter { it.genre.equals(genre, ignoreCase = true) }
                flowOf(filtered)
            }
        }

        @Test
        fun `single song with genre returns its cover`() = runTest {
            val testSongs = listOf(song1)
            setupViewModelWithSongs(testSongs)

            val uris = playerViewModel.getSongUrisForGenre("Rock").first()
            assertEquals(listOf("rock_cover1.png"), uris)
        }

        @Test
        fun `multiple songs with same genre return all covers`() = runTest {
            val testSongs = listOf(song1, song3)
            setupViewModelWithSongs(testSongs)

            val uris = playerViewModel.getSongUrisForGenre("Rock").first()
            assertEquals(listOf("rock_cover1.png", "rock_cover2.png"), uris)
        }

        @Test
        fun `no songs with genre returns empty list`() = runTest {
            val testSongs = listOf(song2, song4)
            setupViewModelWithSongs(testSongs)

            val uris = playerViewModel.getSongUrisForGenre("Rock").first()
            assertTrue(uris.isEmpty())
        }
        
        @Test
        fun `genre with more than 3 songs returns first 4 valid covers`() = runTest {
            val testSongs = listOf(song1, song3, song5, song6, song7)
            setupViewModelWithSongs(testSongs)

            val uris = playerViewModel.getSongUrisForGenre("Rock").first()
            assertEquals(4, uris.size)
            assertEquals(listOf("rock_cover1.png", "rock_cover2.png", "rock_cover3.png", "rock_cover4.png"), uris)
        }

        @Test
        fun `songs with blank album art are ignored`() = runTest {
            val testSongs = listOf(song1, song8)
            setupViewModelWithSongs(testSongs)

            val uris = playerViewModel.getSongUrisForGenre("Rock").first()
            assertEquals(listOf("rock_cover1.png"), uris)
        }

        @Test
        fun `empty allSongs list returns empty list for any genre`() = runTest {
            setupViewModelWithSongs(emptyList())

            val uris = playerViewModel.getSongUrisForGenre("Rock").first()
            assertTrue(uris.isEmpty())
        }

        @Test
        fun `case insensitive genre matching`() = runTest {
            val testSongs = listOf(song1, song3)
            setupViewModelWithSongs(testSongs)

            val uris = playerViewModel.getSongUrisForGenre("rOcK").first()
            assertEquals(listOf("rock_cover1.png", "rock_cover2.png"), uris)
        }

        @Test
        fun `genre not found among songs with null genres`() = runTest {
            val testSongs = listOf(song9)
            setupViewModelWithSongs(testSongs)

            val uris = playerViewModel.getSongUrisForGenre("Rock").first()
            assertTrue(uris.isEmpty())
        }
         @Test
        fun `songs with null genre do not match specific genre query`() = runTest {
            val testSongs = listOf(song1, song9)
            setupViewModelWithSongs(testSongs)

            val uris = playerViewModel.getSongUrisForGenre("Rock").first()
            assertEquals(listOf("rock_cover1.png"), uris)
        }
    }

    @Test
    fun `repeat preference changes after startup are not pushed back into controller`() = runTest {
        advanceUntilIdle()
        controllerRepeatModeWrites.clear()

        _repeatModeFlow.value = Player.REPEAT_MODE_ONE
        advanceUntilIdle()

        assertTrue(controllerRepeatModeWrites.isEmpty())
        assertEquals(Player.REPEAT_MODE_OFF, controllerRepeatMode)
    }

    @Test
    fun `album navigation from player accepts synthetic negative album ids`() = runTest {
        playerViewModel.albumNavigationRequests.test {
            playerViewModel.triggerAlbumNavigationFromPlayer(-42L)
            advanceUntilIdle()

            assertEquals(-42L, awaitItem())
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `album navigation from player still ignores sentinel album id`() = runTest {
        playerViewModel.albumNavigationRequests.test {
            playerViewModel.triggerAlbumNavigationFromPlayer(-1L)
            advanceUntilIdle()

            expectNoEvents()
        }
    }

    @Test
    fun `toggleFavorite resolves external current song to MediaStore favorite id`() = runTest {
        val externalSong = Song(
            id = "external:content://media/external/audio/media/42",
            title = "External Song",
            artist = "Artist",
            artistId = -1L,
            album = "Album",
            albumId = -1L,
            path = "/storage/emulated/0/Music/external-song.mp3",
            contentUriString = "file:///data/user/0/com.lostf1sh.pixelplayeross/cache/external_audio/audio_42.mp3",
            albumArtUriString = null,
            duration = 180000L,
            mimeType = "audio/mpeg",
            bitrate = null,
            sampleRate = null
        )
        stablePlayerStateFlow.value = StablePlayerState(currentSong = externalSong)

        playerViewModel.toggleFavorite()
        advanceUntilIdle()

        coVerify { mockMusicRepository.setFavoriteStatus("42", true) }
    }

    @Test
    fun `isCurrentSongFavorite resolves external current song before reading favorite ids`() = runTest {
        _favoriteIdsFlow.value = setOf("42")
        val externalSong = Song(
            id = "external:content://media/external/audio/media/42",
            title = "External Song",
            artist = "Artist",
            artistId = -1L,
            album = "Album",
            albumId = -1L,
            path = "/storage/emulated/0/Music/external-song.mp3",
            contentUriString = "file:///data/user/0/com.lostf1sh.pixelplayeross/cache/external_audio/audio_42.mp3",
            albumArtUriString = null,
            duration = 180000L,
            mimeType = "audio/mpeg",
            bitrate = null,
            sampleRate = null
        )

        playerViewModel.isCurrentSongFavorite.test {
            assertEquals(false, awaitItem())

            stablePlayerStateFlow.value = StablePlayerState(currentSong = externalSong)
            advanceUntilIdle()

            assertEquals(true, awaitItem())
            cancelAndConsumeRemainingEvents()
        }
    }

    @Nested
    @DisplayName("Shuffle Functionality")
    inner class ShuffleFunctionalityTests {

        // ---- B24-Q1: a queue is always its source list ------------------------------------------------------

        /** A library of [count] songs whose id is the position in the list the Library shows (`"1"` is the second row). */
        private fun libraryOf(count: Int): List<Song> = (0 until count).map { index ->
            Song(
                id = index.toString(), title = "Song %04d".format(index), artist = "Artist", artistId = 1L, album = "Album",
                albumId = 1L, path = "path/$index", contentUriString = "content://dummy/$index", albumArtUriString = null,
                duration = 180_000L, mimeType = "audio/mpeg", bitrate = null, sampleRate = null,
            )
        }

        /**
         * The repository's answer for a list: [rows] are the ids the Library's own query returns, in the order it returns them
         * (not the order of the in-memory map), and every song is in the library's cache.
         */
        private fun theLibraryListsThese(rows: List<Song>, everySong: List<Song> = rows) {
            every { mockLibraryStateHolder.allSongsById } returns MutableStateFlow(everySong.associateBy { it.id })
            val ids = rows.map { it.id.toLong() }
            coEvery { mockMusicRepository.getSongIdsSorted(any(), any()) } returns ids
            coEvery { mockMusicRepository.getFavoriteSongIdsSorted(any(), any()) } returns ids
            // B31 P2.2: the draw is dealt as list indices (the view model no longer builds a second list from it).
            coEvery { mockQueueStateHolder.dealShuffledQueue(any(), any()) } answers {
                val songs = firstArg<List<Song>>()
                QueueStateHolder.ShuffleDeal(songs.first(), 0, IntArray(songs.size) { it })
            }
            mockkObject(MediaItemBuilder)
            every { MediaItemBuilder.build(any()) } returns MediaItem.Builder().setMediaId("test").setUri("file:///tmp/test.mp3").build()
            val mockedPlaybackUri = mockk<android.net.Uri>(relaxed = true)
            every { mockedPlaybackUri.scheme } returns "file"
            every { MediaItemBuilder.playbackUri(any<Song>()) } returns mockedPlaybackUri
            mockkStatic(android.net.Uri::class)
            every { android.net.Uri.parse(any()) } returns mockk(relaxed = true)
        }

        /**
         * Resolving a list is work on `Dispatchers.Default` (real threads), which the test scheduler cannot wait for: run the
         * scheduler, and give the threads a moment, until the shuffle has reached the player's load.
         */
        private fun TestScope.untilShuffled() {
            val deadline = System.nanoTime() + 10_000_000_000L
            while (System.nanoTime() < deadline) {
                testScheduler.advanceUntilIdle()
                val reached = runCatching {
                    coVerify(atLeast = 1) { mockQueueStateHolder.dealShuffledQueue(any(), any()) }
                }.isSuccess
                if (reached) {
                    // The load that follows hops to Default and back too: let it finish before the test (and its Main) ends.
                    letItSettle()
                    return
                }
                Thread.sleep(5)
            }
        }

        /** The other way round: nothing is to happen, so give the threads a fixed moment instead of waiting for it. */
        private fun TestScope.letItSettle() {
            repeat(10) {
                testScheduler.advanceUntilIdle()
                Thread.sleep(20)
            }
        }

        private fun capturedQueue(): List<Song> {
            val queue = slot<List<Song>>()
            coVerify { mockQueueStateHolder.dealShuffledQueue(capture(queue), true) }
            return queue.captured
        }

        @Test
        fun `shuffleAllSongs on the whole library hands the player the full list in the order the Library lists it`() = runTest {
            val library = libraryOf(4_870)
            // The Library is sorted by title descending here: the order is the query's, not the id order and not a random one.
            val listed = library.reversed()
            theLibraryListsThese(listed, everySong = library)
            every { mockLibraryStateHolder.currentSongSortOption } returns MutableStateFlow<SortOption>(SortOption.SongTitleZA)

            playerViewModel.shuffleAllSongs()
            untilShuffled()

            coVerify(exactly = 1) { mockMusicRepository.getSongIdsSorted(SortOption.SongTitleZA, StorageFilter.ALL) }
            val queue = capturedQueue()
            assertEquals(4_870, queue.size)
            assertEquals(listed.map { it.id }, queue.map { it.id })
        }

        @Test
        fun `shuffleAllSongs names the library's current sort and storage filter, so a filtered Library shuffles what it shows`() = runTest {
            val library = libraryOf(300)
            val offlineOnly = library.filter { it.id.toInt() % 3 == 0 }
            theLibraryListsThese(offlineOnly, everySong = library)
            every { mockLibraryStateHolder.currentSongSortOption } returns MutableStateFlow<SortOption>(SortOption.SongArtist)
            every { mockLibraryStateHolder.currentStorageFilter } returns MutableStateFlow(StorageFilter.OFFLINE)

            playerViewModel.shuffleAllSongs()
            untilShuffled()

            coVerify(exactly = 1) { mockMusicRepository.getSongIdsSorted(SortOption.SongArtist, StorageFilter.OFFLINE) }
            assertEquals(offlineOnly.map { it.id }, capturedQueue().map { it.id })
        }

        @Test
        fun `shuffleAllSongs takes the same list as a tap on a row and as Play from the top`() = runTest {
            val library = libraryOf(50)
            theLibraryListsThese(library)

            playerViewModel.shuffleAllSongs()
            untilShuffled()
            playerViewModel.playLibraryFromStart()
            letItSettle()
            playerViewModel.showAndPlaySongFromLibrary(library[7])
            letItSettle()

            // Three entry points, one question to the repository: the same sort and the same filter each time.
            coVerify(exactly = 3) { mockMusicRepository.getSongIdsSorted(SortOption.SongTitleAZ, StorageFilter.ALL) }
            coVerify(exactly = 0) { mockMusicRepository.getFavoriteSongsOnce(any()) }
        }

        @Test
        fun `shuffleAllSongs shuffles an empty library as nothing and does not play`() = runTest {
            coEvery { mockMusicRepository.getSongIdsSorted(any(), any()) } returns emptyList()

            playerViewModel.shuffleAllSongs()
            letItSettle()

            coVerify(exactly = 0) { mockQueueStateHolder.dealShuffledQueue(any(), any()) }
        }

        @Test
        fun `shuffleFavoriteSongs hands the player the liked songs in the order the Liked list shows them`() = runTest {
            val library = libraryOf(40)
            // Date liked, newest first: neither the title order nor the id order.
            val liked = listOf(library[31], library[4], library[12], library[39])
            theLibraryListsThese(liked, everySong = library)
            every { mockLibraryStateHolder.currentFavoriteSortOption } returns MutableStateFlow<SortOption>(SortOption.LikedSongDateLiked)

            playerViewModel.shuffleFavoriteSongs()
            untilShuffled()

            coVerify(exactly = 1) { mockMusicRepository.getFavoriteSongIdsSorted(SortOption.LikedSongDateLiked, StorageFilter.ALL) }
            // B23 loaded `getFavoriteSongsOnce`, which sorts by title whatever the Liked list's own sort is.
            coVerify(exactly = 0) { mockMusicRepository.getFavoriteSongsOnce(any()) }
            assertEquals(liked.map { it.id }, capturedQueue().map { it.id })
        }

        @Test
        fun `shuffleRandomAlbum shuffles the album in the order its page lists it`() = runTest {
            val album = Album(
                id = 77L, title = "Album Roulette", artist = "Artist A", year = 2024, dateAdded = 0L, albumArtUriString = null, songCount = 4,
            )
            // Disc 2 track 1 comes last on the page; the repository's own order is not the page's.
            val d2t1 = libraryOf(4)[0].copy(id = "0", title = "Disc two", discNumber = 2, trackNumber = 1)
            val d1t2 = libraryOf(4)[1].copy(id = "1", title = "B side", discNumber = 1, trackNumber = 2)
            val d1t1 = libraryOf(4)[2].copy(id = "2", title = "A side", discNumber = 1, trackNumber = 1)
            val loose = libraryOf(4)[3].copy(id = "3", title = "No track", discNumber = null, trackNumber = 0)
            every { mockLibraryStateHolder.albums } returns MutableStateFlow(persistentListOf(album))
            every { mockMusicRepository.getSongsForAlbum(album.id) } returns flowOf(listOf(d2t1, loose, d1t2, d1t1))
            theLibraryListsThese(listOf(d1t1), everySong = listOf(d1t1))

            playerViewModel.shuffleRandomAlbum()
            untilShuffled()

            assertEquals(listOf("2", "1", "3", "0"), capturedQueue().map { it.id })
        }

        @Test
        fun `shuffleRandomArtist shuffles the artist in the order its page lists it, album by album`() = runTest {
            val artist = Artist(id = 88L, name = "Artist Roulette", songCount = 4)
            val base = libraryOf(4)
            // Two albums: 2019 (newer) and 2001. The query returns them by title; the page puts the newer album first.
            val oldB = base[0].copy(id = "0", title = "Aaa", album = "Old", albumId = 1L, year = 2001, trackNumber = 2)
            val oldA = base[1].copy(id = "1", title = "Bbb", album = "Old", albumId = 1L, year = 2001, trackNumber = 1)
            val newA = base[2].copy(id = "2", title = "Ccc", album = "New", albumId = 2L, year = 2019, trackNumber = 1)
            val newB = base[3].copy(id = "3", title = "Ddd", album = "New", albumId = 2L, year = 2019, trackNumber = 2)
            every { mockLibraryStateHolder.artists } returns MutableStateFlow(persistentListOf(artist))
            every { mockMusicRepository.getSongsForArtist(artist.id) } returns flowOf(listOf(oldB, oldA, newA, newB))
            theLibraryListsThese(listOf(oldB), everySong = listOf(oldB))

            playerViewModel.shuffleRandomArtist()
            untilShuffled()

            assertEquals(listOf("2", "3", "1", "0"), capturedQueue().map { it.id })
        }

        @Test
        fun `the Quick Settings tile waits for the saved sort and takes the Library's list`() = runTest {
            val library = libraryOf(120)
            val listed = library.sortedByDescending { it.id }
            theLibraryListsThese(listed, everySong = library)
            coEvery { mockLibraryStateHolder.awaitSelectionLoaded() } just Runs

            playerViewModel.triggerShuffleAllFromTile()
            untilShuffled()

            coVerify { mockLibraryStateHolder.awaitSelectionLoaded() }
            coVerify { mockMusicRepository.getSongIdsSorted(SortOption.SongTitleAZ, StorageFilter.ALL) }
            assertEquals(listed.map { it.id }, capturedQueue().map { it.id })
        }

        @Test
        fun `the tile on an empty library syncs once and tells the listener when there is still nothing`() = runTest {
            coEvery { mockMusicRepository.getSongIdsSorted(any(), any()) } returns emptyList()
            coEvery { mockLibraryStateHolder.awaitSelectionLoaded() } just Runs

            playerViewModel.triggerShuffleAllFromTile()
            letItSettle()

            verify(exactly = 1) { mockSyncManager.sync() }
            coVerify(exactly = 0) { mockQueueStateHolder.dealShuffledQueue(any(), any()) }
        }
    }

    @Nested
    @DisplayName("Search History")
    inner class SearchHistoryTests {

        @Test
        fun `test_loadSearchHistory_updatesUiState`() = runTest {
            val historyItems = listOf(SearchHistoryItem(query = "q1", timestamp = 1L))
            every { mockSearchStateHolder.loadSearchHistory(any()) } answers {
                _searchHistoryFlow.value = historyItems.toImmutableList()
            }

            playerViewModel.playerUiState.test {
                val initialState = awaitItem()
                if (initialState.searchHistory == historyItems) {
                    cancelAndConsumeRemainingEvents()
                    return@test
                }

                playerViewModel.loadSearchHistory()
                
                val currentItem = awaitItem()
                assertEquals(historyItems, currentItem.searchHistory)
                cancelAndConsumeRemainingEvents()
            }
        }


        @Test
        fun `test_clearSearchHistory_callsRepository_andUpdatesUiState`() = runTest {
            val initialHistory = listOf(SearchHistoryItem(query = "q1", timestamp = 1L))
            _searchHistoryFlow.value = initialHistory.toImmutableList()
            
            every { mockSearchStateHolder.clearSearchHistory() } answers {
                _searchHistoryFlow.value = persistentListOf()
            }

            playerViewModel.playerUiState.test {
               var state = awaitItem()
               while (state.searchHistory != initialHistory) {
                   state = awaitItem()
               }

                playerViewModel.clearSearchHistory()

                var emitted = awaitItem()
                while (emitted.searchHistory.isNotEmpty()) {
                    emitted = awaitItem()
                }
                assertTrue(emitted.searchHistory.isEmpty())
                verify { mockSearchStateHolder.clearSearchHistory() }
                cancelAndConsumeRemainingEvents()
            }
        }

        @Test
        fun `test_deleteSearchHistoryItem_callsRepository_andRefreshesHistory`() = runTest {
            val queryToDelete = "delete me"
            val keepQuery = "keep me"
            val initialHistory = listOf(SearchHistoryItem(query = queryToDelete, timestamp = 1L), SearchHistoryItem(query = keepQuery, timestamp = 2L))
            val finalHistory = listOf(SearchHistoryItem(query = keepQuery, timestamp = 2L))

            _searchHistoryFlow.value = initialHistory.toImmutableList()
            
            every { mockSearchStateHolder.deleteSearchHistoryItem(queryToDelete) } answers {
                _searchHistoryFlow.value = finalHistory.toImmutableList()
            }

            playerViewModel.playerUiState.test {
               var state = awaitItem()
               while (state.searchHistory != initialHistory) {
                   state = awaitItem()
               }

                playerViewModel.deleteSearchHistoryItem(queryToDelete)

                var emitted = awaitItem()
                while (emitted.searchHistory != finalHistory) {
                    emitted = awaitItem()
                }
                assertEquals(finalHistory, emitted.searchHistory)
                
                cancelAndConsumeRemainingEvents()
            }
        }
        
        @Test
        fun `test_updateSearchFilter_updatesUiState`() = runTest {
             val newFilter = SearchFilterType.ARTISTS
             
             coEvery { mockSearchStateHolder.updateSearchFilter(newFilter) } answers {
                 _selectedSearchFilterFlow.value = newFilter
             }

            playerViewModel.playerUiState.test {
                skipItems(1)

                playerViewModel.updateSearchFilter(newFilter)
                
                val emittedItem = awaitItem()
                assertEquals(newFilter, emittedItem.selectedSearchFilter)
                cancelAndConsumeRemainingEvents()
            }
        }
    }

    private fun PlayerViewModel.updateAllSongs(songs: List<Song>) {
        _allSongsFlow.value = songs.toImmutableList()
    }
}
