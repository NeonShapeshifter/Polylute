package com.lostf1sh.pixelplayeross.data.backup

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.GsonBuilder
import com.lostf1sh.pixelplayeross.data.backup.format.BackupFormatDetector
import com.lostf1sh.pixelplayeross.data.backup.format.BackupReader
import com.lostf1sh.pixelplayeross.data.backup.format.BackupWriter
import com.lostf1sh.pixelplayeross.data.backup.format.LegacyPayloadAdapter
import com.lostf1sh.pixelplayeross.data.backup.model.BackupManifest
import com.lostf1sh.pixelplayeross.data.backup.model.BackupModuleInfo
import com.lostf1sh.pixelplayeross.data.backup.model.BackupSection
import com.lostf1sh.pixelplayeross.data.backup.model.RestorePlan
import com.lostf1sh.pixelplayeross.data.backup.model.RestoreResult
import com.lostf1sh.pixelplayeross.data.backup.module.BackupModuleHandler
import com.lostf1sh.pixelplayeross.data.backup.module.EngagementStatsModuleHandler
import com.lostf1sh.pixelplayeross.data.backup.module.FavoritesModuleHandler
import com.lostf1sh.pixelplayeross.data.backup.module.LyricsModuleHandler
import com.lostf1sh.pixelplayeross.data.backup.module.PlaybackHistoryModuleHandler
import com.lostf1sh.pixelplayeross.data.backup.module.PlaylistsModuleHandler
import com.lostf1sh.pixelplayeross.data.backup.module.TransitionsModuleHandler
import com.lostf1sh.pixelplayeross.data.backup.restore.BackupRestoreMode
import com.lostf1sh.pixelplayeross.data.backup.restore.RestoreExecutor
import com.lostf1sh.pixelplayeross.data.backup.restore.RestorePlanner
import com.lostf1sh.pixelplayeross.data.backup.validation.BackupFileValidator
import com.lostf1sh.pixelplayeross.data.backup.validation.ManifestValidator
import com.lostf1sh.pixelplayeross.data.backup.validation.ModuleSchemaValidator
import com.lostf1sh.pixelplayeross.data.backup.validation.ValidationPipeline
import com.lostf1sh.pixelplayeross.data.database.FavoritesEntity
import com.lostf1sh.pixelplayeross.data.database.LyricsEntity
import com.lostf1sh.pixelplayeross.data.database.MusicDao
import com.lostf1sh.pixelplayeross.data.database.PixelPlayerDatabase
import com.lostf1sh.pixelplayeross.data.database.SongEngagementEntity
import com.lostf1sh.pixelplayeross.data.database.SongSummary
import com.lostf1sh.pixelplayeross.data.database.TransitionRuleEntity
import com.lostf1sh.pixelplayeross.data.model.Playlist
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.model.TransitionSettings
import com.lostf1sh.pixelplayeross.data.preferences.PlaylistPreferencesRepository
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.stats.CatalogSource
import com.lostf1sh.pixelplayeross.data.stats.LibraryCatalog
import com.lostf1sh.pixelplayeross.data.stats.PlaybackStatsRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class B77PartDPortableAndMergeTest {

    private lateinit var context: Context
    private lateinit var db: PixelPlayerDatabase
    private lateinit var tempDir: File
    private val gson = GsonBuilder().serializeNulls().create()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, PixelPlayerDatabase::class.java)
            .addCallback(PixelPlayerDatabase.createRuntimeArtifactsCallback())
            .allowMainThreadQueries()
            .build()
        tempDir = File(context.cacheDir, "b77-partd-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        db.close()
        tempDir.deleteRecursively()
    }

    private fun song(
        id: String,
        title: String,
        artist: String,
        album: String = "Synthetic Album",
        durationMs: Long = 200_000L,
        path: String = "/storage/emulated/0/Music/$artist/$title.flac",
        libId: String = "lib-$id",
    ) = Song(
        id = id,
        title = title,
        artist = artist,
        artistId = 1L,
        album = album,
        albumId = 1L,
        path = path,
        contentUriString = "content://media/external/audio/media/$id",
        albumArtUriString = null,
        duration = durationMs,
        dateModified = 0L,
        dateAdded = 0L,
        mimeType = "audio/flac",
        bitrate = 1411,
        sampleRate = 44100,
        year = 2024,
        trackNumber = 1,
        libraryItemId = libId,
    )

    private class MutableCatalogSource(var catalog: LibraryCatalog) : CatalogSource {
        override suspend fun load(): LibraryCatalog = catalog
    }

    private fun newPlaybackStatsRepo(tag: String): PlaybackStatsRepository {
        val dir = File(tempDir, "stats-$tag").apply { mkdirs() }
        val wrappedContext = object : android.content.ContextWrapper(context) {
            override fun getFilesDir(): File = dir
        }
        return PlaybackStatsRepository(context = wrappedContext)
    }

    @Test
    fun `portable keys resolve across libraries with new songIds and report exact counts`() {
        runBlocking {
            val libraryA = LibraryCatalog.of(
                songs = listOf(
                    song("101", "Fjord Drift", "Klara Sol", "Winter", 210_000L, "/sdcard/Music/Klara/Fjord Drift.flac", "lib-a1"),
                    song("102", "Paper Lantern (Remastered 2020)", "Vega Trio", "Harbor", 185_000L, "/sdcard/Music/Vega/Paper.flac", "lib-a2"),
                    song("103", "Missing On Target", "Ghost Band", "Lost", 199_000L, "/sdcard/Music/Ghost/Missing.flac", "lib-a3"),
                ),
                firstSeenAtMs = emptyMap(),
            )
            // Library B: completely renumbered IDs (even reusing 101 for a different song!).
            val libraryB = LibraryCatalog.of(
                songs = listOf(
                    song("101", "Totally Different Song", "Unrelated Artist", "Other", 120_000L, "/sdcard/Music/Other/Diff.flac", "lib-other"),
                    song("9001", "Fjord Drift", "Klara Sol", "Winter", 210_000L, "/storage/emulated/0/Music/Klara/Fjord Drift.flac", "lib-b1"),
                    song("9002", "Paper Lantern", "Vega Trio", "Harbor", 185_000L, "/storage/emulated/0/NewFolder/Paper.flac", "lib-b2"),
                ),
                firstSeenAtMs = emptyMap(),
            )
            val catalogSource = MutableCatalogSource(libraryA)
            val statsA = newPlaybackStatsRepo("libA")
            statsA.importEventsFromBackup(
                listOf(
                    PlaybackStatsRepository.PlaybackEvent("101", 1_700_000_100_000L, 120_000L, libraryItemId = "lib-a1"),
                    PlaybackStatsRepository.PlaybackEvent("103", 1_700_000_200_000L, 90_000L, libraryItemId = "lib-a3"),
                ),
                clearExisting = true,
            )
            db.favoritesDao().replaceAll(
                listOf(
                    FavoritesEntity(songId = 101L, isFavorite = true, timestamp = 10L, libraryItemId = "lib-a1"),
                    FavoritesEntity(songId = 102L, isFavorite = true, timestamp = 20L, libraryItemId = "lib-a2"),
                )
            )
            db.lyricsDao().replaceAll(
                listOf(LyricsEntity(songId = 101L, content = "la la fjord", isSynced = false, source = "local", libraryItemId = "lib-a1"))
            )
            db.engagementDao().replaceAll(
                listOf(SongEngagementEntity(songId = "102", libraryItemId = "lib-a2", playCount = 5, totalPlayDurationMs = 500_000L, lastPlayedTimestamp = 50L))
            )
            db.transitionDao().replaceAllRules(
                listOf(TransitionRuleEntity(playlistId = "pl-1", fromTrackId = "101", toTrackId = "102", settings = TransitionSettings()))
            )

            var playlistsA = listOf(Playlist(id = "pl-1", name = "Road", songIds = listOf("101", "102", "103"), source = "LOCAL"))
            val playlistPrefs = mockk<PlaylistPreferencesRepository>(relaxed = true)
            coEvery { playlistPrefs.getPlaylistsOnce() } answers { playlistsA }
            coEvery { playlistPrefs.replaceAllPlaylists(any()) } coAnswers { playlistsA = firstArg() }
            every { playlistPrefs.playlistSongOrderModesFlow } returns flowOf(emptyMap())
            every { playlistPrefs.playlistsSortOptionFlow } returns flowOf("name")
            val userPrefs = mockk<UserPreferencesRepository>(relaxed = true)
            val musicDaoA = mockk<MusicDao>(relaxed = true)
            coEvery { musicDaoA.getAllLocalSongSummaries() } returns libraryA.items.map {
                SongSummary(it.song.id.toLong(), it.title, it.song.artist, it.album, it.lengthMs)
            }

            val exportHandlers: Map<BackupSection, BackupModuleHandler> = mapOf(
                BackupSection.FAVORITES to FavoritesModuleHandler(db.favoritesDao(), gson, catalogSource),
                BackupSection.PLAYBACK_HISTORY to PlaybackHistoryModuleHandler(statsA, gson, catalogSource),
                BackupSection.PLAYLISTS to PlaylistsModuleHandler(context, playlistPrefs, userPrefs, musicDaoA, gson, catalogSource),
                BackupSection.LYRICS to LyricsModuleHandler(db.lyricsDao(), gson, catalogSource),
                BackupSection.ENGAGEMENT_STATS to EngagementStatsModuleHandler(db.engagementDao(), gson, catalogSource),
                BackupSection.TRANSITIONS to TransitionsModuleHandler(db.transitionDao(), gson, catalogSource),
            )

            val payloads = exportHandlers.entries.associate { (sec, h) -> sec.key to h.export() }
            assertTrue(payloads.getValue("favorites").contains("\"duration_ms\""))
            assertTrue(payloads.getValue("favorites").contains("\"path\""))
            assertTrue(payloads.getValue("playback_history").contains("\"artist\""))
            assertTrue(payloads.getValue("playlists").contains("\"duration_ms\""))

            val backupFile = File(tempDir, "encrypted-libA.pxpl")
            val backupUri = Uri.fromFile(backupFile)
            val manifest = BackupManifest(
                modules = payloads.keys.associateWith { key -> BackupModuleInfo(sizeBytes = 64) }
            )
            BackupWriter(context, gson).write(backupUri, manifest, payloads, passphrase = "secret-password").getOrThrow()
            // Verify PXPL header & AES encryption unchanged
            val header = backupFile.inputStream().use { it.readNBytes(4) }.decodeToString()
            assertEquals("PXPL", header)

            // Switch to empty Device B with Library B
            db.favoritesDao().clearAll()
            db.lyricsDao().deleteAll()
            db.engagementDao().clearAllEngagements()
            db.transitionDao().clearAllRules()
            playlistsA = emptyList()
            catalogSource.catalog = libraryB
            val statsB = newPlaybackStatsRepo("libB")
            val reader = BackupReader(context, gson, BackupFormatDetector(), LegacyPayloadAdapter())
            val validation = ValidationPipeline(
                BackupFileValidator(context, BackupFormatDetector()),
                ManifestValidator(),
                ModuleSchemaValidator(com.lostf1sh.pixelplayeross.data.backup.validation.ContentSanitizer()),
            )
            val restoreHandlers: Map<BackupSection, BackupModuleHandler> = mapOf(
                BackupSection.FAVORITES to FavoritesModuleHandler(db.favoritesDao(), gson, catalogSource),
                BackupSection.PLAYBACK_HISTORY to PlaybackHistoryModuleHandler(statsB, gson, catalogSource),
                BackupSection.PLAYLISTS to PlaylistsModuleHandler(context, playlistPrefs, userPrefs, musicDaoA, gson, catalogSource),
                BackupSection.LYRICS to LyricsModuleHandler(db.lyricsDao(), gson, catalogSource),
                BackupSection.ENGAGEMENT_STATS to EngagementStatsModuleHandler(db.engagementDao(), gson, catalogSource),
                BackupSection.TRANSITIONS to TransitionsModuleHandler(db.transitionDao(), gson, catalogSource),
            )
            val inspector = BackupManager(
                context = context,
                backupWriter = BackupWriter(context, gson),
                backupReader = reader,
                restorePlanner = RestorePlanner(reader, restoreHandlers),
                restoreExecutor = RestoreExecutor(reader, validation, restoreHandlers),
                validationPipeline = validation,
                backupHistoryRepository = mockk(relaxed = true),
                handlers = emptyMap(),
            )

            val plan = inspector.inspectBackup(backupUri, passphrase = "secret-password").getOrThrow()
            assertEquals(manifest.modules.keys, plan.availableModules.map { it.key }.toSet())

            val (result, counts) = inspector.restoreWithReport(backupUri, plan) {}
            assertEquals(RestoreResult.Success, result)

            // Verify every matched entry resolved cleanly by portable keys even though songIds changed (and 101 was reused!).
            assertEquals(0, counts.resolvedById)
            // favorites:2 + history:1 + playlists:2 + lyrics:1 + engagement:1 + transitions:2 = 9 matched by keys
            // history:1 missing + playlists:1 missing = 2 unresolved
            assertEquals(9, counts.resolvedByKeys)
            assertEquals(2, counts.unresolved)

            assertEquals(setOf(9001L, 9002L), db.favoritesDao().getAllFavoritesOnce().map { it.songId }.toSet())
            assertEquals(listOf("9001"), statsB.exportEventsForBackup().map { it.songId })
            assertEquals(listOf("9001", "9002"), playlistsA.single().songIds)
            assertEquals(listOf(9001L), db.lyricsDao().getAll().map { it.songId })
            assertEquals(listOf("9002"), db.engagementDao().getAllEngagements().map { it.songId })
            val restoredRule = db.transitionDao().getAllRulesOnce().single()
            assertEquals("9001", restoredRule.fromTrackId)
            assertEquals("9002", restoredRule.toTrackId)
        }
    }

    @Test
    fun `merge restore keeps existing data adds missing and skips duplicates when run twice`() {
        runBlocking {
            val song1 = song("1", "Song One", "Artist A")
            val song2 = song("2", "Song Two", "Artist B")
            val song3 = song("3", "Song Three", "Artist C")
            val catalogSource = MutableCatalogSource(LibraryCatalog.of(listOf(song1, song2, song3), emptyMap()))

            // Prepare backup containing song1 and song2 + Playlist X [1, 2]
            val backupStats = newPlaybackStatsRepo("mergeBackup")
            backupStats.importEventsFromBackup(
                listOf(
                    PlaybackStatsRepository.PlaybackEvent("1", 1000L, 180_000L),
                    PlaybackStatsRepository.PlaybackEvent("2", 2000L, 180_000L),
                ),
                clearExisting = true,
            )
            db.favoritesDao().replaceAll(
                listOf(
                    FavoritesEntity(1L, true, 10L, "lib-1"),
                    FavoritesEntity(2L, true, 20L, "lib-2"),
                )
            )
            var currentPlaylists = listOf(Playlist(id = "pl-x", name = "Mix", songIds = listOf("1", "2"), source = "LOCAL"))
            val playlistPrefs = mockk<PlaylistPreferencesRepository>(relaxed = true)
            coEvery { playlistPrefs.getPlaylistsOnce() } answers { currentPlaylists }
            coEvery { playlistPrefs.replaceAllPlaylists(any()) } coAnswers { currentPlaylists = firstArg() }
            every { playlistPrefs.playlistSongOrderModesFlow } returns flowOf(emptyMap())
            every { playlistPrefs.playlistsSortOptionFlow } returns flowOf("name")
            val musicDao = mockk<MusicDao>(relaxed = true)
            coEvery { musicDao.getAllLocalSongSummaries() } returns emptyList()

            val favHandler = FavoritesModuleHandler(db.favoritesDao(), gson, catalogSource)
            val histStatsTarget = newPlaybackStatsRepo("mergeTarget")
            val histHandlerBackup = PlaybackHistoryModuleHandler(backupStats, gson, catalogSource)
            val histHandlerTarget = PlaybackHistoryModuleHandler(histStatsTarget, gson, catalogSource)
            val plHandler = PlaylistsModuleHandler(context, playlistPrefs, mockk(relaxed = true), musicDao, gson, catalogSource)

            val favPayload = favHandler.export()
            val histPayload = histHandlerBackup.export()
            val plPayload = plHandler.export()

            // Seed target app with song1 (already there) + song3 (app-only item that must NOT be wiped)
            db.favoritesDao().replaceAll(
                listOf(
                    FavoritesEntity(1L, true, 999L, "lib-1"),
                    FavoritesEntity(3L, true, 30L, "lib-3"),
                )
            )
            histStatsTarget.importEventsFromBackup(
                listOf(
                    PlaybackStatsRepository.PlaybackEvent("1", 1000L, 180_000L),
                    PlaybackStatsRepository.PlaybackEvent("3", 3000L, 180_000L),
                ),
                clearExisting = true,
            )
            currentPlaylists = listOf(
                Playlist(id = "pl-x", name = "Mix", songIds = listOf("1", "3"), source = "LOCAL"),
                Playlist(id = "pl-app", name = "App Only", songIds = listOf("3"), source = "LOCAL"),
            )

            // Run MERGE once
            val favCounts1 = favHandler.restoreWithReport(favPayload, BackupRestoreMode.MERGE)
            val histCounts1 = histHandlerTarget.restoreWithReport(histPayload, BackupRestoreMode.MERGE)
            plHandler.restoreWithReport(plPayload, BackupRestoreMode.MERGE)

            assertEquals(2, favCounts1.resolvedById)
            assertEquals(2, histCounts1.resolvedById)
            assertEquals(setOf(1L, 2L, 3L), db.favoritesDao().getAllFavoritesOnce().map { it.songId }.toSet())
            // Existing timestamp on song1 kept (not overwritten)
            assertEquals(999L, db.favoritesDao().getAllFavoritesOnce().first { it.songId == 1L }.timestamp)
            assertEquals(listOf(1000L to "1", 2000L to "2", 3000L to "3"), histStatsTarget.exportEventsForBackup().map { it.timestamp to it.songId })
            assertEquals(2, currentPlaylists.size)
            assertEquals(listOf("1", "3", "2"), currentPlaylists.first { it.id == "pl-x" }.songIds)
            assertEquals(listOf("3"), currentPlaylists.first { it.id == "pl-app" }.songIds)

            // Run MERGE a second time -> strictly identical, 0 duplicates
            favHandler.restoreWithReport(favPayload, BackupRestoreMode.MERGE)
            histHandlerTarget.restoreWithReport(histPayload, BackupRestoreMode.MERGE)
            plHandler.restoreWithReport(plPayload, BackupRestoreMode.MERGE)

            assertEquals(3, db.favoritesDao().getAllFavoritesOnce().size)
            assertEquals(3, histStatsTarget.exportEventsForBackup().size)
            assertEquals(listOf("1", "3", "2"), currentPlaylists.first { it.id == "pl-x" }.songIds)
        }
    }

    @Test
    fun `legacy pxpl without portable keys restores identically in replace mode`() {
        runBlocking {
            val catalogSource = MutableCatalogSource(LibraryCatalog.of(listOf(song("50", "Modern Track", "Modern Artist")), emptyMap()))
            val favHandler = FavoritesModuleHandler(db.favoritesDao(), gson, catalogSource)
            val stats = newPlaybackStatsRepo("legacy")
            val histHandler = PlaybackHistoryModuleHandler(stats, gson, catalogSource)

            // Old payload with NO portable keys (title/artist/album/duration_ms/path absent), referencing songId 77
            val legacyFavPayload = """[{"songId":77,"isFavorite":true,"timestamp":555,"libraryItemId":null}]"""
            val legacyHistPayload = """[{"songId":"77","timestamp":777000,"durationMs":60000}]"""

            favHandler.restore(legacyFavPayload)
            histHandler.restore(legacyHistPayload)

            val restoredFav = db.favoritesDao().getAllFavoritesOnce().single()
            assertEquals(77L, restoredFav.songId)
            assertEquals("", restoredFav.libraryItemId)
            assertEquals(listOf("77"), stats.exportEventsForBackup().map { it.songId })
        }
    }
}
