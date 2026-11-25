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
import com.lostf1sh.pixelplayeross.data.stats.PlaybackStatsRepository
import io.mockk.coEvery
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.time.ZoneOffset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class B77PartCUniversalExportTest {

    private val tempDirs = mutableListOf<File>()

    @After
    fun tearDown() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    private fun testContext(): Context {
        val dir = Files.createTempDirectory("b77-partc").toFile().also { tempDirs += it }
        return object : ContextWrapper(ApplicationProvider.getApplicationContext()) {
            override fun getFilesDir(): File = dir
        }
    }

    private class MemoryPlaylistDao : LocalPlaylistDao {
        private val playlists = LinkedHashMap<String, PlaylistEntity>()
        private val songs = LinkedHashMap<String, List<PlaylistSongEntity>>()
        private val flow = MutableStateFlow<List<PlaylistWithSongsEntity>>(emptyList())

        private fun emit() {
            flow.value = playlists.values.map { p -> PlaylistWithSongsEntity(p, songs[p.id].orEmpty()) }
        }

        override fun observePlaylistsWithSongs(): Flow<List<PlaylistWithSongsEntity>> = flow
        override fun observePlaylistWithSongs(playlistId: String): Flow<PlaylistWithSongsEntity?> =
            flow.map { it.firstOrNull { p -> p.playlist.id == playlistId } }
        override fun observePlaylistSongs(playlistId: String): Flow<List<PlaylistSongEntity>> =
            flow.map { it.firstOrNull { p -> p.playlist.id == playlistId }?.songs.orEmpty() }
        override suspend fun getPlaylistById(playlistId: String): PlaylistEntity? = playlists[playlistId]
        override suspend fun getPlaylistCount(): Int = playlists.size
        override suspend fun getPlaylistsPage(limit: Int, offset: Int): List<PlaylistEntity> =
            playlists.values.drop(offset).take(limit)
        override suspend fun getPlaylistLocatorsResolvedPage(playlistId: String, limit: Int, offset: Int): List<Long> = emptyList()
        override suspend fun upsertPlaylist(entity: PlaylistEntity) { playlists[entity.id] = entity; emit() }
        override suspend fun updatePlaylist(entity: PlaylistEntity) { playlists[entity.id] = entity; emit() }
        override suspend fun deletePlaylist(playlistId: String) { playlists.remove(playlistId); songs.remove(playlistId); emit() }
        override suspend fun upsertPlaylistSongs(entities: List<PlaylistSongEntity>) {
            for (e in entities) {
                val cur = songs[e.playlistId].orEmpty().toMutableList()
                cur.removeAll { it.sortOrder == e.sortOrder }
                cur += e
                songs[e.playlistId] = cur.sortedBy { it.sortOrder }
            }
            emit()
        }
        override suspend fun clearPlaylistSongs(playlistId: String) { songs.remove(playlistId); emit() }
        override suspend fun clearAllPlaylistSongs() { songs.clear(); emit() }
        override suspend fun clearAllPlaylists() { playlists.clear(); emit() }
        override suspend fun upsertPlaylistSongResolvingIdentity(playlistId: String, songId: String, sortOrder: Int) {
            val cur = songs[playlistId].orEmpty().toMutableList()
            cur.removeAll { it.sortOrder == sortOrder }
            cur += PlaylistSongEntity(playlistId, songId, sortOrder, "item-$songId")
            songs[playlistId] = cur.sortedBy { it.sortOrder }
            emit()
        }
        override suspend fun getPlaylistLocatorsResolved(playlistId: String): List<Long> =
            songs[playlistId].orEmpty().mapNotNull { it.songId.toLongOrNull() }
        override suspend fun bindPlaylistMembershipIdentities() = Unit
        override suspend fun replaceAllPlaylistsTransactional(playlists: List<Pair<PlaylistEntity, List<String>>>) {
            this.playlists.clear()
            this.songs.clear()
            for ((e, ids) in playlists) {
                this.playlists[e.id] = e
                this.songs[e.id] = ids.mapIndexed { idx, sid -> PlaylistSongEntity(e.id, sid, idx, "item-$sid") }
            }
            emit()
        }
    }

    @Test
    fun `universal export of history, likes and playlists re-imports into an empty store unchanged`() {
        runBlocking {
            val songs = listOf(
                Kit.song(id = 1L, title = "Northern Lights", artist = "Klara Sol", album = "Winter Drift", durationMs = 210_000L)
                    .copy(path = "/music/Klara Sol/Northern Lights.flac", artists = listOf(ArtistRef(1L, "Klara Sol", true))),
                Kit.song(id = 2L, title = "Paper Boat", artist = "Vega Trio", album = "Coast", durationMs = 185_000L)
                    .copy(path = "/music/Vega Trio/Paper Boat.flac", artists = listOf(ArtistRef(2L, "Vega Trio", true))),
                Kit.song(id = 3L, title = "Harbor Mist", artist = "Sora Linn", album = "Harbor", durationMs = 240_000L)
                    .copy(path = "/music/Sora Linn/Harbor Mist.flac", artists = listOf(ArtistRef(3L, "Sora Linn", true))),
            )
            val catalog = Kit.catalog(songs)

            // Source app state
            val srcCtx = testContext()
            val srcPlayback = PlaybackStatsRepository(srcCtx)
            val t1 = Kit.at(2026, 5, 10, 14, 0)
            val t2 = Kit.at(2026, 5, 11, 18, 30)
            srcPlayback.recordPlayback(songId = "1", durationMs = 200_000L, timestamp = t1, libraryItemId = "item-1", occurrenceId = "o1")
            srcPlayback.recordPlayback(songId = "2", durationMs = 180_000L, timestamp = t2, libraryItemId = "item-2", occurrenceId = "o2")

            val srcStore = ImportedHistoryStore(
                context = srcCtx,
                catalogSource = object : CatalogSource { override suspend fun load() = catalog },
                librarySignal = LibraryChangeSignal(),
                preferences = mockk(relaxed = true),
            )

            val srcFavorites = setOf("2", "3")
            val srcMusicRepo = mockk<MusicRepository>(relaxed = true)
            coEvery { srcMusicRepo.getFavoriteSongIdsOnce() } returns srcFavorites

            val srcUserPrefs = mockk<UserPreferencesRepository>(relaxed = true)
            coEvery { srcUserPrefs.getLegacyUserPlaylistsOnce() } returns emptyList()
            coEvery { srcUserPrefs.playlistSongOrderModesFlow } returns flowOf(emptyMap())
            coEvery { srcUserPrefs.playlistsSortOptionFlow } returns flowOf("name_az")
            val srcPlaylistRepo = PlaylistPreferencesRepository(MemoryPlaylistDao(), srcUserPrefs)
            srcPlaylistRepo.createPlaylist("Drive Mix", songIds = listOf("3", "1", "2"))
            srcPlaylistRepo.createPlaylist("Chill, Night", songIds = listOf("2", "1"))

            val exporter = UniversalExporter(
                context = srcCtx,
                catalogSource = object : CatalogSource { override suspend fun load() = catalog },
                playbackStatsRepository = srcPlayback,
                importedHistoryStore = srcStore,
                musicRepository = srcMusicRepo,
                playlistPreferencesRepository = srcPlaylistRepo,
            )

            val plays = exporter.collectHistoryPlays(catalog)
            val likes = exporter.collectLikes(catalog)
            val playlists = exporter.collectPlaylists(catalog)

            val historyCsv = ByteArrayOutputStream().also { UniversalExporter.writeHistoryCsv(it, plays, ZoneOffset.UTC) }.toByteArray()
            val historyJson = ByteArrayOutputStream().also { UniversalExporter.writeHistoryJson(it, plays, ZoneOffset.UTC) }.toByteArray()
            val likesCsv = ByteArrayOutputStream().also { UniversalExporter.writeLikesCsv(it, likes) }.toByteArray()
            val playlistsCsv = ByteArrayOutputStream().also { UniversalExporter.writePlaylistsCsv(it, playlists) }.toByteArray()
            val playlistsM3uZip = ByteArrayOutputStream().also { UniversalExporter.writePlaylistsM3u8(it, playlists) }.toByteArray()
            val singlePlaylistM3u = ByteArrayOutputStream().also { UniversalExporter.writePlaylistsM3u8(it, listOf(playlists.first())) }.toByteArray()

            // Target empty stores
            fun newTarget(): Triple<ImportedHistoryStore, MutableSet<String>, PlaylistPreferencesRepository> {
                val dstCtx = testContext()
                val dstStore = ImportedHistoryStore(
                    context = dstCtx,
                    catalogSource = object : CatalogSource { override suspend fun load() = catalog },
                    librarySignal = LibraryChangeSignal(),
                    preferences = mockk(relaxed = true),
                )
                val dstFavs = mutableSetOf<String>()
                val dstMusicRepo = mockk<MusicRepository>(relaxed = true)
                coEvery { dstMusicRepo.getFavoriteSongIdsOnce() } answers { dstFavs.toSet() }
                coEvery { dstMusicRepo.setFavoriteStatus(any(), any()) } answers {
                    if (secondArg<Boolean>()) dstFavs += firstArg<String>()
                }
                val dstUserPrefs = mockk<UserPreferencesRepository>(relaxed = true)
                coEvery { dstUserPrefs.getLegacyUserPlaylistsOnce() } returns emptyList()
                coEvery { dstUserPrefs.playlistSongOrderModesFlow } returns flowOf(emptyMap())
                coEvery { dstUserPrefs.playlistsSortOptionFlow } returns flowOf("name_az")
                val dstPlaylistRepo = PlaylistPreferencesRepository(MemoryPlaylistDao(), dstUserPrefs)
                return Triple(dstStore, dstFavs, dstPlaylistRepo)
            }

            // 1. Round-trip History CSV + Likes CSV + Playlists CSV
            val (dstStoreCsv, dstFavsCsv, dstPlRepoCsv) = newTarget()
            val dstMusicCsv = mockk<MusicRepository>(relaxed = true)
            coEvery { dstMusicCsv.getFavoriteSongIdsOnce() } answers { dstFavsCsv.toSet() }
            coEvery { dstMusicCsv.setFavoriteStatus(any(), any()) } answers {
                if (secondArg<Boolean>()) dstFavsCsv += firstArg<String>()
            }
            val applierCsv = UnifiedImportApplier(dstStoreCsv, dstMusicCsv, dstPlRepoCsv)

            val csvSources = listOf(
                ImportInputSource("polylute-history.csv") { ByteArrayInputStream(historyCsv) },
                ImportInputSource("polylute-likes.csv") { ByteArrayInputStream(likesCsv) },
                ImportInputSource("polylute-playlists.csv") { ByteArrayInputStream(playlistsCsv) },
            )
            // Must auto-detect without CSV column mapping prompt
            assertThat(dstStoreCsv.inspectInput(csvSources)).isNull()

            val previewCsv = dstStoreCsv.prepareImport(csvSources)
            val resCsv = applierCsv.apply(previewCsv)

            assertThat(resCsv.playsAdded).isEqualTo(2)
            assertThat(resCsv.likesAdded).isEqualTo(2)
            assertThat(resCsv.playlistsCreated).isEqualTo(2)
            assertThat(dstFavsCsv).containsExactlyElementsIn(srcFavorites)

            val importedStatsCsv = dstStoreCsv.statsInput(catalog)
            assertThat(importedStatsCsv.timestampsMs.toList()).containsExactly(t1 - 200_000L, t2 - 180_000L).inOrder()
            assertThat(importedStatsCsv.listenedMs.toList()).containsExactly(200_000L, 180_000L).inOrder()

            val restoredPlaylistsCsv = dstPlRepoCsv.getPlaylistsOnce()
            assertThat(restoredPlaylistsCsv.map { it.name }).containsExactly("Drive Mix", "Chill, Night")
            assertThat(restoredPlaylistsCsv.first { it.name == "Drive Mix" }.songIds).containsExactly("3", "1", "2").inOrder()
            assertThat(restoredPlaylistsCsv.first { it.name == "Chill, Night" }.songIds).containsExactly("2", "1").inOrder()

            // 2. Round-trip History JSON + Playlists M3U8 ZIP
            val (dstStoreJson, dstFavsJson, dstPlRepoZip) = newTarget()
            val dstMusicJson = mockk<MusicRepository>(relaxed = true)
            coEvery { dstMusicJson.getFavoriteSongIdsOnce() } answers { dstFavsJson.toSet() }
            val applierZip = UnifiedImportApplier(dstStoreJson, dstMusicJson, dstPlRepoZip)

            val zipSources = listOf(
                ImportInputSource("polylute-history.json") { ByteArrayInputStream(historyJson) },
                ImportInputSource("polylute-playlists.zip") { ByteArrayInputStream(playlistsM3uZip) },
            )
            assertThat(dstStoreJson.inspectInput(zipSources)).isNull()
            val previewZip = dstStoreJson.prepareImport(zipSources)
            val resZip = applierZip.apply(previewZip)

            assertThat(resZip.playsAdded).isEqualTo(2)
            assertThat(resZip.playlistsCreated).isEqualTo(2)
            val importedStatsJson = dstStoreJson.statsInput(catalog)
            assertThat(importedStatsJson.timestampsMs.toList()).containsExactly(t1 - 200_000L, t2 - 180_000L).inOrder()
            val restoredPlaylistsZip = dstPlRepoZip.getPlaylistsOnce()
            assertThat(restoredPlaylistsZip.first { it.name == "Drive Mix" }.songIds).containsExactly("3", "1", "2").inOrder()
            assertThat(restoredPlaylistsZip.first { it.name == "Chill, Night" }.songIds).containsExactly("2", "1").inOrder()

            // 3. Round-trip single M3U8
            val (dstStoreSingle, _, dstPlRepoSingle) = newTarget()
            val applierSingle = UnifiedImportApplier(dstStoreSingle, mockk(relaxed = true), dstPlRepoSingle)
            val previewSingle = dstStoreSingle.prepareImport(
                listOf(ImportInputSource("Drive Mix.m3u8") { ByteArrayInputStream(singlePlaylistM3u) }),
            )
            applierSingle.apply(previewSingle)
            val singleRestored = dstPlRepoSingle.getPlaylistsOnce().single()
            assertThat(singleRestored.name).isEqualTo("Drive Mix")
            assertThat(singleRestored.songIds).containsExactly("3", "1", "2").inOrder()
        }
    }
}
