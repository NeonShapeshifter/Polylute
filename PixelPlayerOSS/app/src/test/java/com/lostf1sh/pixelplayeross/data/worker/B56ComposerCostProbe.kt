package com.lostf1sh.pixelplayeross.data.worker

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.lostf1sh.pixelplayeross.data.database.PixelPlayerDatabase
import com.lostf1sh.pixelplayeross.data.model.SearchFilterType
import com.lostf1sh.pixelplayeross.data.preferences.PlaylistPreferencesRepository
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.repository.MusicRepositoryImpl
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadows.ShadowContentResolver

/**
 * B56 phase 6 report: what composers cost on a synthetic library of 5,000 songs, as milliseconds on this host: a full incremental sync (the worker, a fake MediaStore, real Room) and the
 * all-results search. It uses nothing the batch added except by raw SQL, so the same file runs against the tree before it and against this one. The tag read of a real file (TagLib, a
 * few ms per file on a phone) cannot be measured here: the files are not there, so the backfill only runs its database side ("COMPOSER_COST").
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class B56ComposerCostProbe {
    private class MemoryPreferences : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = transform(state.value).also { state.value = it }
    }

    @Test
    fun `sync and search on five thousand songs`(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(context as Application).grantPermissions(Manifest.permission.READ_MEDIA_AUDIO)
        val syncMs = mutableListOf<Long>()
        var searchMs = 0L
        repeat(3) { round ->
            val db = Room.inMemoryDatabaseBuilder(context, PixelPlayerDatabase::class.java)
                .addCallback(PixelPlayerDatabase.createRuntimeArtifactsCallback()).allowMainThreadQueries().build()
            val media = LibraryRefreshIntegrationTest.AudioProvider()
            ShadowContentResolver.registerProviderInternal("media", media)
            val prefs = UserPreferencesRepository(MemoryPreferences(), Json { ignoreUnknownKeys = true })
            prefs.includeDirectories(listOf("/music"))
            prefs.setLastSyncTimestamp(LibraryRefreshIntegrationTest.MARKER)
            media.sql.beginTransaction()
            for (id in 1L..5_000L) media.add(id, "Obra $id", LibraryRefreshIntegrationTest.MARKER / 1000 + id)
            media.sql.setTransactionSuccessful(); media.sql.endTransaction()

            val started = System.nanoTime()
            val result = TestListenableWorkerBuilder<SyncWorker>(context)
                .setInputData(workDataOf(SyncWorker.INPUT_SYNC_MODE to SyncMode.FULL.name, SyncWorker.INPUT_RUN_MAINTENANCE to false))
                .setWorkerFactory(object : WorkerFactory() {
                    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                        SyncWorker(appContext, workerParameters, db.musicDao(), db.libraryItemOverrideDao(), mockk(relaxed = true), prefs, mockk(relaxed = true))
                }).build().doWork()
            syncMs += (System.nanoTime() - started) / 1_000_000
            assertTrue(result is ListenableWorker.Result.Success)

            if (round == 2) {
                // 40 % of the songs carry a composer (raw SQL: the table exists only after the batch; before it the insert is skipped)
                runCatching {
                    val sql = db.openHelper.writableDatabase
                    sql.execSQL("INSERT OR REPLACE INTO song_composers (song_id, composer, read_at) SELECT id, CASE WHEN id % 5 = 0 THEN 'Ludwig van Beethoven' WHEN id % 5 = 1 THEN 'Johann Sebastian Bach; Antonio Vivaldi' ELSE '' END, 0 FROM songs")
                }
                val playlists = mockk<PlaylistPreferencesRepository>(relaxed = true)
                every { playlists.userPlaylistsFlow } returns flowOf(emptyList())
                val libraryPrefs = mockk<UserPreferencesRepository>(relaxed = true)
                every { libraryPrefs.allowedDirectoriesFlow } returns flowOf(emptySet())
                every { libraryPrefs.blockedDirectoriesFlow } returns flowOf(emptySet())
                every { libraryPrefs.minTracksPerAlbumFlow } returns flowOf(1)
                every { libraryPrefs.artistDelimitersFlow } returns flowOf(listOf(";", "/"))
                val music = MusicRepositoryImpl(context, libraryPrefs, playlists, db.searchHistoryDao(), db.musicDao(), mockk(relaxed = true), mockk(relaxed = true),
                    db.favoritesDao(), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
                val queries = listOf("obra 12", "bach", "beethoven", "vivaldi", "obra 4999")
                music.searchAll("warm", SearchFilterType.ALL).first()
                val times = (1..4).flatMap { queries.map { q -> val t = System.nanoTime(); music.searchAll(q, SearchFilterType.ALL).first(); (System.nanoTime() - t) / 1_000_000 } }
                searchMs = times.sorted()[times.size / 2]
            }
            db.close(); media.sql.close()
        }
        println("COMPOSER_COST songs=5000 sync_ms=$syncMs search_median_ms=$searchMs")
    }
}
