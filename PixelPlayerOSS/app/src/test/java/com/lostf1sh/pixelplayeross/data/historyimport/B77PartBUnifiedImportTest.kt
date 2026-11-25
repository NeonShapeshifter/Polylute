package com.lostf1sh.pixelplayeross.data.historyimport

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.database.LocalPlaylistDao
import com.lostf1sh.pixelplayeross.data.database.PlaylistEntity
import com.lostf1sh.pixelplayeross.data.database.PlaylistSongEntity
import com.lostf1sh.pixelplayeross.data.database.PlaylistWithSongsEntity
import com.lostf1sh.pixelplayeross.data.model.ArtistRef
import com.lostf1sh.pixelplayeross.data.preferences.PlaylistPreferencesRepository
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.repository.MusicRepository
import com.lostf1sh.pixelplayeross.data.stats.CatalogSource
import com.lostf1sh.pixelplayeross.data.stats.Kit
import com.lostf1sh.pixelplayeross.data.stats.LibraryChangeSignal
import com.lostf1sh.pixelplayeross.di.DispatcherProvider
import com.lostf1sh.pixelplayeross.presentation.viewmodel.HistoryImportStep
import com.lostf1sh.pixelplayeross.presentation.viewmodel.HistoryImportViewModel
import io.mockk.coEvery
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class B77PartBUnifiedImportTest {

    private val tempDirs = mutableListOf<File>()
    private val dispatcher = StandardTestDispatcher()
    private val dispatchers = object : DispatcherProvider {
        override val main: CoroutineDispatcher get() = dispatcher
        override val io: CoroutineDispatcher get() = dispatcher
        override val default: CoroutineDispatcher get() = dispatcher
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEachCleanup
    @After
    fun tearDown() {
        Dispatchers.resetMain()
        tempDirs.forEach { it.deleteRecursively() }
    }

    private fun testContext(): Context {
        val dir = Files.createTempDirectory("b77-partb").toFile().also { tempDirs += it }
        return object : ContextWrapper(ApplicationProvider.getApplicationContext()) {
            override fun getFilesDir(): File = dir
        }
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            for ((name, bytes) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    private class InMemoryLocalPlaylistDao : LocalPlaylistDao {
        private val playlists = LinkedHashMap<String, PlaylistEntity>()
        private val playlistSongs = LinkedHashMap<String, List<PlaylistSongEntity>>()
        private val flow = MutableStateFlow<List<PlaylistWithSongsEntity>>(emptyList())

        private fun emit() {
            flow.value = playlists.values.map { p ->
                PlaylistWithSongsEntity(playlist = p, songs = playlistSongs[p.id].orEmpty())
            }
        }

        override fun observePlaylistsWithSongs(): Flow<List<PlaylistWithSongsEntity>> = flow
        override fun observePlaylistWithSongs(playlistId: String): Flow<PlaylistWithSongsEntity?> =
            flow.map { list -> list.firstOrNull { it.playlist.id == playlistId } }
        override fun observePlaylistSongs(playlistId: String): Flow<List<PlaylistSongEntity>> =
            flow.map { list -> list.firstOrNull { it.playlist.id == playlistId }?.songs.orEmpty() }
        override suspend fun getPlaylistById(playlistId: String): PlaylistEntity? = playlists[playlistId]
        override suspend fun getPlaylistCount(): Int = playlists.size
        override suspend fun getPlaylistsPage(limit: Int, offset: Int): List<PlaylistEntity> =
            playlists.values.drop(offset).take(limit)
        override suspend fun getPlaylistLocatorsResolvedPage(playlistId: String, limit: Int, offset: Int): List<Long> = emptyList()
        override suspend fun upsertPlaylist(entity: PlaylistEntity) {
            playlists[entity.id] = entity
            emit()
        }
        override suspend fun updatePlaylist(entity: PlaylistEntity) {
            playlists[entity.id] = entity
            emit()
        }
        override suspend fun deletePlaylist(playlistId: String) {
            playlists.remove(playlistId)
            playlistSongs.remove(playlistId)
            emit()
        }
        override suspend fun upsertPlaylistSongs(entities: List<PlaylistSongEntity>) {
            for (e in entities) {
                val cur = playlistSongs[e.playlistId].orEmpty().toMutableList()
                cur.removeAll { it.sortOrder == e.sortOrder }
                cur += e
                playlistSongs[e.playlistId] = cur.sortedBy { it.sortOrder }
            }
            emit()
        }
        override suspend fun clearPlaylistSongs(playlistId: String) {
            playlistSongs.remove(playlistId)
            emit()
        }
        override suspend fun clearAllPlaylistSongs() {
            playlistSongs.clear()
            emit()
        }
        override suspend fun clearAllPlaylists() {
            playlists.clear()
            emit()
        }
        override suspend fun upsertPlaylistSongResolvingIdentity(playlistId: String, songId: String, sortOrder: Int) {
            val cur = playlistSongs[playlistId].orEmpty().toMutableList()
            cur.removeAll { it.sortOrder == sortOrder }
            cur += PlaylistSongEntity(playlistId = playlistId, songId = songId, sortOrder = sortOrder, libraryItemId = "item-$songId")
            playlistSongs[playlistId] = cur.sortedBy { it.sortOrder }
            emit()
        }
        override suspend fun getPlaylistLocatorsResolved(playlistId: String): List<Long> =
            playlistSongs[playlistId].orEmpty().mapNotNull { it.songId.toLongOrNull() }
        override suspend fun bindPlaylistMembershipIdentities() = Unit
        override suspend fun replaceAllPlaylistsTransactional(playlists: List<Pair<PlaylistEntity, List<String>>>) {
            this.playlists.clear()
            this.playlistSongs.clear()
            for ((entity, ids) in playlists) {
                this.playlists[entity.id] = entity
                this.playlistSongs[entity.id] = ids.mapIndexed { idx, sid ->
                    PlaylistSongEntity(entity.id, sid, idx, "item-$sid")
                }
            }
            emit()
        }
    }

    private fun buildEnvironment(
        songCount: Int = 50,
        initialFavorites: Set<String> = emptySet(),
        initialPlaylists: List<Pair<String, List<String>>> = emptyList(),
    ): Triple<ImportedHistoryStore, UnifiedImportApplier, Pair<MutableSet<String>, PlaylistPreferencesRepository>> {
        val songs = (1..songCount).map { i ->
            Kit.song(
                id = i.toLong(),
                title = "Track $i",
                artist = "Artist $i",
                album = "Album $i",
                durationMs = 200_000L,
            ).copy(
                artists = listOf(ArtistRef(i.toLong(), "Artist $i", true)),
                path = "/storage/music/Artist $i/Track $i.flac",
            )
        }
        val catalog = Kit.catalog(songs)
        val ctx = testContext()
        val store = ImportedHistoryStore(
            context = ctx,
            catalogSource = object : CatalogSource { override suspend fun load() = catalog },
            librarySignal = LibraryChangeSignal(),
            preferences = mockk<UserPreferencesRepository>(relaxed = true),
        )
        val favorites = initialFavorites.toMutableSet()
        val musicRepo = mockk<MusicRepository>(relaxed = true)
        coEvery { musicRepo.getFavoriteSongIdsOnce() } answers { favorites.toSet() }
        coEvery { musicRepo.setFavoriteStatus(any(), any()) } answers {
            val id = firstArg<String>()
            val fav = secondArg<Boolean>()
            if (fav) favorites += id else favorites -= id
        }
        val userPrefs = mockk<UserPreferencesRepository>(relaxed = true)
        coEvery { userPrefs.getLegacyUserPlaylistsOnce() } returns emptyList()
        coEvery { userPrefs.playlistSongOrderModesFlow } returns flowOf(emptyMap())
        coEvery { userPrefs.playlistsSortOptionFlow } returns flowOf("name_az")

        val playlistDao = InMemoryLocalPlaylistDao()
        val playlistRepo = PlaylistPreferencesRepository(playlistDao, userPrefs)
        runBlocking {
            for ((name, ids) in initialPlaylists) {
                playlistRepo.createPlaylist(name = name, songIds = ids)
            }
        }
        val applier = UnifiedImportApplier(store, musicRepo, playlistRepo)
        return Triple(store, applier, favorites to playlistRepo)
    }

    @Test
    fun `spotify account ZIP extracts history, likes and playlists while skipping episodes, local tracks and metadata`() {
        runBlocking {
        val (store, _, _) = buildEnvironment(songCount = 10)
        val catalog = Kit.catalog(
            (1..10).map { i ->
                Kit.song(
                    id = i.toLong(),
                    title = "Track $i",
                    artist = "Artist $i",
                    album = "Album $i",
                    durationMs = 200_000L,
                ).copy(artists = listOf(ArtistRef(i.toLong(), "Artist $i", true)))
            },
        )

        val historyJson = """
            [
              {"endTime":"2024-02-10 14:30","artistName":"Artist 1","trackName":"Track 1","msPlayed":185000},
              {"endTime":"2024-02-10 14:35","artistName":"Artist 2","trackName":"Track 2","msPlayed":190000}
            ]
        """.trimIndent().toByteArray()

        val yourLibraryJson = """
            {
              "tracks": [
                {"artist":"Artist 1","album":"Album 1","track":"Track 1","uri":"spotify:track:1"},
                {"artist":"Artist 3","album":"Album 3","track":"Track 3","uri":"spotify:track:3"},
                {"artist":"Ghost","album":"None","track":"Missing Like","uri":"spotify:track:999"}
              ],
              "albums": []
            }
        """.trimIndent().toByteArray()

        val playlistJson = """
            {
              "playlists": [
                {
                  "name": "Morning Mix",
                  "items": [
                    {"track":{"trackName":"Track 5","artistName":"Artist 5","albumName":"Album 5"},"episode":null,"localTrack":null},
                    {"track":null,"episode":{"episodeName":"Podcast 1","showName":"Show"},"localTrack":null},
                    {"track":null,"episode":null,"localTrack":{"trackName":"Local.mp3","artistName":"Local","albumName":""}},
                    {"track":{"trackName":"Track 2","artistName":"Artist 2","albumName":"Album 2"},"episode":null,"localTrack":null}
                  ]
                }
              ]
            }
        """.trimIndent().toByteArray()

        val userdataJson = """{"username":"synth","email":"synth@example.invalid"}""".toByteArray()

        val zipBytes = zipOf(
            "MyData/StreamingHistory_music_0.json" to historyJson,
            "MyData/YourLibrary.json" to yourLibraryJson,
            "MyData/Playlist1.json" to playlistJson,
            "MyData/Userdata.json" to userdataJson,
        )

        val preview = store.prepareImportWithCatalog(
            sources = listOf(ImportInputSource("spotify_account.zip") { ByteArrayInputStream(zipBytes) }),
            catalog = catalog,
            existingFavoriteSongIds = setOf("1"),
        )

        assertThat(preview.counts.plays).isEqualTo(2)
        assertThat(preview.likes.matchedCount).isEqualTo(2)
        assertThat(preview.likes.alreadyFavoriteCount).isEqualTo(1)
        assertThat(preview.likes.newCount).isEqualTo(1)
        assertThat(preview.likes.unmatchedCount).isEqualTo(1)

        assertThat(preview.playlists).hasSize(1)
        val pl = preview.playlists.single()
        assertThat(pl.name).isEqualTo("Morning Mix")
        assertThat(pl.matchedTracks.map { it.songId }).containsExactly("5", "2").inOrder()
        assertThat(pl.episodesSkipped).isEqualTo(1)
        assertThat(pl.localTracksSkipped).isEqualTo(1)
        assertThat(preview.playlistEpisodesSkipped).isEqualTo(1)
        assertThat(preview.playlistLocalTracksSkipped).isEqualTo(1)
        assertThat(preview.fileSummaries.map { it.dataKind }).containsExactly(
            ImportFileDataKind.SPOTIFY_ACCOUNT_HISTORY,
            ImportFileDataKind.SPOTIFY_LIKES,
            ImportFileDataKind.SPOTIFY_PLAYLISTS,
            ImportFileDataKind.SKIPPED,
        )
        }
    }

    @Test
    fun `0-match playlist is hidden and 32-of-40 playlist keeps exact order`() = runBlocking {
        val songs = (1..32).map { i ->
            Kit.song(
                id = i.toLong(),
                title = "Song $i",
                artist = "Band $i",
                album = "Alb $i",
                durationMs = 200_000L,
            ).copy(artists = listOf(ArtistRef(i.toLong(), "Band $i", true)))
        }
        val catalog = Kit.catalog(songs)
        val (store, _, _) = buildEnvironment(songCount = 32)

        val thirtyTwoItems = (32 downTo 1).joinToString(",") { i ->
            """{"track":{"trackName":"Song $i","artistName":"Band $i","albumName":"Alb $i"}}"""
        }
        val eightMissing = (1..8).joinToString(",") { i ->
            """{"track":{"trackName":"Missing $i","artistName":"Ghost $i","albumName":"Void"}}"""
        }
        val zeroMatchItems = (1..3).joinToString(",") { i ->
            """{"track":{"trackName":"Absent $i","artistName":"NoOne $i","albumName":"None"}}"""
        }
        val playlistJson = """
            {
              "playlists": [
                {"name":"Empty Local Match","items":[$zeroMatchItems]},
                {"name":"Big Mix","items":[$thirtyTwoItems,$eightMissing]}
              ]
            }
        """.trimIndent().toByteArray()

        val preview = store.prepareImportWithCatalog(
            sources = listOf(ImportInputSource("Playlist1.json") { ByteArrayInputStream(playlistJson) }),
            catalog = catalog,
        )

        assertThat(preview.hiddenZeroMatchPlaylists).isEqualTo(1)
        assertThat(preview.playlists).hasSize(1)
        val mix = preview.playlists.single()
        assertThat(mix.name).isEqualTo("Big Mix")
        assertThat(mix.matchedCount).isEqualTo(32)
        assertThat(mix.totalItems).isEqualTo(40)
        assertThat(mix.unmatchedCount).isEqualTo(8)
        assertThat(mix.matchedTracks.map { it.songId }).isEqualTo((32 downTo 1).map { it.toString() })
    }

    @Test
    fun `excluded playlist is not created, cancel writes nothing, Done twice adds no duplicates, and name collision gets (2)`() = runTest(dispatcher) {
        val (store, applier, targets) = buildEnvironment(
            songCount = 10,
            initialFavorites = setOf("1", "9"), // "9" is unrelated existing favorite that must never be removed
            initialPlaylists = listOf("Road Trip" to listOf("10")),
        )
        val (_, playlistRepo) = targets
        val (favorites, _) = targets

        val historyJson = """
            [{"endTime":"2024-03-01 12:00","artistName":"Artist 1","trackName":"Track 1","msPlayed":180000}]
        """.trimIndent().toByteArray()
        val libraryJson = """
            {"tracks":[
              {"artist":"Artist 1","album":"Album 1","track":"Track 1"},
              {"artist":"Artist 2","album":"Album 2","track":"Track 2"}
            ]}
        """.trimIndent().toByteArray()
        val playlistJson = """
            {"playlists":[
              {"name":"Road Trip","items":[{"track":{"trackName":"Track 3","artistName":"Artist 3"}},{"track":{"trackName":"Track 4","artistName":"Artist 4"}}]},
              {"name":"Excluded Mix","items":[{"track":{"trackName":"Track 5","artistName":"Artist 5"}}]}
            ]}
        """.trimIndent().toByteArray()

        val sources = listOf(
            ImportInputSource("StreamingHistory_music_0.json") { ByteArrayInputStream(historyJson) },
            ImportInputSource("YourLibrary.json") { ByteArrayInputStream(libraryJson) },
            ImportInputSource("Playlist1.json") { ByteArrayInputStream(playlistJson) },
        )

        val vm = HistoryImportViewModel(store, dispatchers, testContext(), null, applier)
        vm.onSourcesPicked(sources)
        advanceUntilIdle()
        val previewStep = kotlinx.coroutines.withTimeout(10_000) {
            while (vm.uiState.value.step is HistoryImportStep.Reading) {
                Thread.sleep(10)
                advanceUntilIdle()
            }
            vm.uiState.value.step as HistoryImportStep.Preview
        }

        // Cancel writes nothing
        vm.cancelActiveFlow()
        advanceUntilIdle()
        assertThat(store.records.value).isEmpty()
        assertThat(favorites).containsExactly("1", "9")
        assertThat(playlistRepo.getPlaylistsOnce().map { it.name }).containsExactly("Road Trip")

        // Pick again, exclude "Excluded Mix", then Done
        vm.onSourcesPicked(sources)
        advanceUntilIdle()
        val previewStep2 = kotlinx.coroutines.withTimeout(10_000) {
            while (vm.uiState.value.step is HistoryImportStep.Reading) {
                Thread.sleep(10)
                advanceUntilIdle()
            }
            vm.uiState.value.step as HistoryImportStep.Preview
        }
        val excluded = previewStep2.preview.playlists.first { it.name == "Excluded Mix" }
        vm.setPlaylistIncluded(excluded.targetId, false)
        vm.confirmImport()
        advanceUntilIdle()
        val done = kotlinx.coroutines.withTimeout(10_000) {
            while (vm.uiState.value.isWriting || vm.uiState.value.step is HistoryImportStep.Preview) {
                Thread.sleep(10)
                advanceUntilIdle()
            }
            vm.uiState.value.step as HistoryImportStep.Done
        }
        assertThat(done.plays).isEqualTo(1)
        assertThat(done.likesAdded).isEqualTo(1)
        assertThat(done.playlistsCreated).isEqualTo(1)

        assertThat(store.records.value).hasSize(1)
        assertThat(favorites).containsExactly("1", "2", "9")
        val allPlaylists = playlistRepo.getPlaylistsOnce()
        assertThat(allPlaylists.map { it.name }).containsExactly("Road Trip", "Road Trip (2)")
        assertThat(allPlaylists.first { it.name == "Road Trip (2)" }.songIds).containsExactly("3", "4").inOrder()

        // Calling applier.apply twice on the SAME preview (simulating double Done or crash-recovery retry) produces 0 duplicates
        val secondApply = applier.apply(
            previewStep2.preview,
            includedPlaylistIds = previewStep2.preview.playlists.filter { it.name == "Road Trip" }.map { it.targetId }.toSet(),
        )
        assertThat(secondApply.playsAdded).isEqualTo(0)
        assertThat(secondApply.likesAdded).isEqualTo(0)
        assertThat(secondApply.playlistsCreated).isEqualTo(0)
        assertThat(store.records.value).hasSize(1)
        assertThat(playlistRepo.getPlaylistsOnce()).hasSize(2)
        assertThat(previewStep).isNotNull()
    }

    @Test
    fun `crash mid-apply followed by Done again completes remaining work without duplicating anything`() {
        runBlocking {
            val songs = (1..4).map { i ->
                Kit.song(
                    id = i.toLong(),
                    title = "Track $i",
                    artist = "Artist $i",
                    album = "Album $i",
                    durationMs = 200_000L,
                ).copy(artists = listOf(ArtistRef(i.toLong(), "Artist $i", true)))
            }
            val catalog = Kit.catalog(songs)
            val store = ImportedHistoryStore(
                context = testContext(),
                catalogSource = object : CatalogSource { override suspend fun load() = catalog },
                librarySignal = LibraryChangeSignal(),
                preferences = mockk<UserPreferencesRepository>(relaxed = true),
            )
            val favorites = mutableSetOf<String>()
            val musicRepo = mockk<MusicRepository>(relaxed = true)
            coEvery { musicRepo.getFavoriteSongIdsOnce() } answers { favorites.toSet() }
            coEvery { musicRepo.setFavoriteStatus(any(), any()) } answers {
                if (secondArg<Boolean>()) favorites += firstArg<String>()
            }
            val userPrefs = mockk<UserPreferencesRepository>(relaxed = true)
            coEvery { userPrefs.getLegacyUserPlaylistsOnce() } returns emptyList()
            coEvery { userPrefs.playlistSongOrderModesFlow } returns flowOf(emptyMap())
            coEvery { userPrefs.playlistsSortOptionFlow } returns flowOf("name_az")

            val baseDao = InMemoryLocalPlaylistDao()
            var failOnSecondPlaylist = true
            var createdCalls = 0
            val flakyDao = object : LocalPlaylistDao by baseDao {
                override suspend fun upsertPlaylist(entity: PlaylistEntity) {
                    createdCalls++
                    if (failOnSecondPlaylist && createdCalls == 2) {
                        error("Simulated mid-apply crash")
                    }
                    baseDao.upsertPlaylist(entity)
                }
            }
            val playlistRepo = PlaylistPreferencesRepository(flakyDao, userPrefs)
            val applier = UnifiedImportApplier(store, musicRepo, playlistRepo)

            val historyJson = """[{"endTime":"2024-03-01 12:00","artistName":"Artist 1","trackName":"Track 1","msPlayed":180000}]""".toByteArray()
            val libraryJson = """{"tracks":[{"artist":"Artist 2","album":"Album 2","track":"Track 2"}]}""".toByteArray()
            val playlistJson = """
                {"playlists":[
                  {"name":"First List","items":[{"track":{"trackName":"Track 3","artistName":"Artist 3"}}]},
                  {"name":"Second List","items":[{"track":{"trackName":"Track 4","artistName":"Artist 4"}}]}
                ]}
            """.trimIndent().toByteArray()

            val preview = store.prepareImportWithCatalog(
                sources = listOf(
                    ImportInputSource("StreamingHistory_music_0.json") { ByteArrayInputStream(historyJson) },
                    ImportInputSource("YourLibrary.json") { ByteArrayInputStream(libraryJson) },
                    ImportInputSource("Playlist1.json") { ByteArrayInputStream(playlistJson) },
                ),
                catalog = catalog,
            )

            // First attempt crashes on Second List after committing history, likes, and First List
            val firstFailure = runCatching { applier.apply(preview) }.exceptionOrNull()
            assertThat(firstFailure).isNotNull()
            assertThat(store.records.value).hasSize(1)
            assertThat(favorites).containsExactly("2")
            assertThat(playlistRepo.getPlaylistsOnce().map { it.name }).containsExactly("First List")

            // Retry Done after recovery: finishes Second List without duplicating history, likes, or First List
            failOnSecondPlaylist = false
            val retryResult = applier.apply(preview)
            assertThat(retryResult.playsAdded).isEqualTo(0)
            assertThat(retryResult.likesAdded).isEqualTo(0)
            assertThat(retryResult.playlistsCreated).isEqualTo(1)
            assertThat(store.records.value).hasSize(1)
            assertThat(favorites).containsExactly("2")
            assertThat(playlistRepo.getPlaylistsOnce().map { it.name }).containsExactly("First List", "Second List")
        }
    }

    /**
     * Review 11-25: a real crash kills the process and the in-memory preview with it. Picking the same file again gives a
     * fresh preview; its playlist ids come from the file content, so Done finds the playlist already created (no " (2)").
     */
    @Test
    fun `process death mid-apply then the same file picked again creates no duplicate playlist`() {
        runBlocking {
            val songs = (1..4).map { i ->
                Kit.song(id = i.toLong(), title = "Track $i", artist = "Artist $i", album = "Album $i", durationMs = 200_000L)
                    .copy(artists = listOf(ArtistRef(i.toLong(), "Artist $i", true)))
            }
            val catalog = Kit.catalog(songs)
            val store = ImportedHistoryStore(
                context = testContext(),
                catalogSource = object : CatalogSource { override suspend fun load() = catalog },
                librarySignal = LibraryChangeSignal(),
                preferences = mockk<UserPreferencesRepository>(relaxed = true),
            )
            val musicRepo = mockk<MusicRepository>(relaxed = true)
            coEvery { musicRepo.getFavoriteSongIdsOnce() } returns emptySet()
            val userPrefs = mockk<UserPreferencesRepository>(relaxed = true)
            coEvery { userPrefs.getLegacyUserPlaylistsOnce() } returns emptyList()
            coEvery { userPrefs.playlistSongOrderModesFlow } returns flowOf(emptyMap())
            coEvery { userPrefs.playlistsSortOptionFlow } returns flowOf("name_az")
            val baseDao = InMemoryLocalPlaylistDao()
            var fail = true
            var calls = 0
            val flakyDao = object : LocalPlaylistDao by baseDao {
                override suspend fun upsertPlaylist(entity: PlaylistEntity) {
                    calls++
                    if (fail && calls == 2) error("Simulated process death")
                    baseDao.upsertPlaylist(entity)
                }
            }
            val playlistRepo = PlaylistPreferencesRepository(flakyDao, userPrefs)
            val applier = UnifiedImportApplier(store, musicRepo, playlistRepo)
            val playlistJson = """
                {"playlists":[
                  {"name":"First List","items":[{"track":{"trackName":"Track 3","artistName":"Artist 3"}}]},
                  {"name":"Second List","items":[{"track":{"trackName":"Track 4","artistName":"Artist 4"}}]}
                ]}
            """.trimIndent().toByteArray()
            suspend fun freshPreview() = store.prepareImportWithCatalog(
                sources = listOf(ImportInputSource("Playlist1.json") { ByteArrayInputStream(playlistJson) }),
                catalog = catalog,
            )

            val first = freshPreview()
            assertThat(runCatching { applier.apply(first) }.exceptionOrNull()).isNotNull()
            assertThat(playlistRepo.getPlaylistsOnce().map { it.name }).containsExactly("First List")

            fail = false
            val second = freshPreview()
            assertThat(second.playlists.map { it.targetId }).isEqualTo(first.playlists.map { it.targetId })
            val result = applier.apply(second)
            assertThat(result.playlistsCreated).isEqualTo(1)
            assertThat(playlistRepo.getPlaylistsOnce().map { it.name }).containsExactly("First List", "Second List")
            assertThat(playlistRepo.getPlaylistsOnce().first { it.name == "First List" }.songIds).containsExactly("3")
        }
    }

    @Test
    fun `youtube playlist CSV with only video IDs is skipped with reason and makes zero network calls`() {
        runBlocking {
        var networkCalls = 0
        val networkGuard: (URL) -> HttpURLConnection = {
            networkCalls++
            error("Network calls forbidden")
        }
        val (store, _, _) = buildEnvironment(songCount = 5)
        val ytmPlaylistCsv = """
            Video ID,Playlist video creation timestamp
            dQw4w9WgXcQ,2024-01-15 10:00:00 UTC
            9bZkp7q19f0,2024-01-16 12:30:00 UTC
        """.trimIndent().toByteArray()

        val mapping = store.inspectInput(
            listOf(ImportInputSource("Liked Music.csv") { ByteArrayInputStream(ytmPlaylistCsv) }),
        )
        assertThat(mapping).isNull()

        val preview = store.prepareImport(
            sources = listOf(ImportInputSource("Liked Music.csv") { ByteArrayInputStream(ytmPlaylistCsv) }),
        )
        assertThat(networkCalls).isEqualTo(0)
        assertThat(networkGuard).isNotNull()
        assertThat(preview.playlists).isEmpty()
        assertThat(preview.counts.plays).isEqualTo(0)
        assertThat(preview.hasAnythingToApply).isFalse()
        assertThat(preview.fileSummaries).containsExactly(
            ImportFileSummary(
                fileName = "Liked Music.csv",
                dataKind = ImportFileDataKind.SKIPPED,
                skipReason = ImportFileSkipReason.YOUTUBE_VIDEO_ID_ONLY,
            ),
        )
        }
    }

    @Test
    fun `M3U with paths and M3U with only EXTINF both match in source order`() = runBlocking {
        val songs = listOf(
            Kit.song(
                id = 1,
                title = "Northern Lights",
                artist = "Klara Sol",
                album = "Winter Drift",
                durationMs = 210_000L,
            ).copy(path = "/storage/Music/Klara/NorthernLights.flac", artists = listOf(ArtistRef(1, "Klara Sol", true))),
            Kit.song(
                id = 2,
                title = "Paper Boat",
                artist = "Vega Trio",
                album = "Coast",
                durationMs = 185_000L,
            ).copy(path = "/storage/Music/Vega/PaperBoat.flac", artists = listOf(ArtistRef(2, "Vega Trio", true))),
        )
        val catalog = Kit.catalog(songs)
        val (store, _, _) = buildEnvironment(songCount = 2)

        val pathOnlyM3u = """
            #EXTM3U
            /storage/Music/Vega/PaperBoat.flac
            /storage/Music/Klara/NorthernLights.flac
        """.trimIndent().toByteArray()

        val extinfOnlyM3u = """
            #EXTM3U
            #EXTINF:210,Klara Sol - Northern Lights
            C:\Users\OldPC\Music\01.mp3
            #EXTINF:185,Vega Trio - Paper Boat (Remastered)
            https://example.invalid/track2
            #EXTINF:200,Ghost Band - Missing Track
            /nonexistent/ghost.mp3
        """.trimIndent().toByteArray()

        val preview = store.prepareImportWithCatalog(
            sources = listOf(
                ImportInputSource("by_paths.m3u") { ByteArrayInputStream(pathOnlyM3u) },
                ImportInputSource("by_extinf.m3u8") { ByteArrayInputStream(extinfOnlyM3u) },
            ),
            catalog = catalog,
        )

        assertThat(preview.playlists).hasSize(2)
        val p1 = preview.playlists[0]
        assertThat(p1.name).isEqualTo("by_paths")
        assertThat(p1.matchedTracks.map { it.songId }).containsExactly("2", "1").inOrder()

        val p2 = preview.playlists[1]
        assertThat(p2.name).isEqualTo("by_extinf")
        assertThat(p2.matchedTracks.map { it.songId }).containsExactly("1", "2").inOrder()
        assertThat(p2.unmatchedCount).isEqualTo(1)
        assertThat(p2.unmatchedTracks.single().title).isEqualTo("Missing Track")
    }
}

private annotation class AfterEachCleanup
