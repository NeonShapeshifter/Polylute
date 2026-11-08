package com.lostf1sh.pixelplayeross.data.worker

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.lostf1sh.pixelplayeross.data.database.PixelPlayerDatabase
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/**
 * B58 2 (#68, "Also"): a file moved with `mv` keeps its MediaStore `_ID`, its dates and its identity evidence, and only its path changes. "Update library" used to keep the old path
 * (the row was never read again) until a Full Rescan. Written against the worker's public inputs only, so it runs in the tree as it was and fails there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class B58MovedFileTest {
    private lateinit var context: Context
    private lateinit var db: PixelPlayerDatabase
    private lateinit var media: LibraryRefreshIntegrationTest.AudioProvider
    private lateinit var prefs: UserPreferencesRepository
    private val dao get() = db.musicDao()

    private class Memory : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = transform(state.value).also { state.value = it }
    }

    @Before fun setup() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context as Application).grantPermissions(Manifest.permission.READ_MEDIA_AUDIO)
        db = Room.inMemoryDatabaseBuilder(context, PixelPlayerDatabase::class.java).addCallback(PixelPlayerDatabase.createRuntimeArtifactsCallback()).allowMainThreadQueries().build()
        media = LibraryRefreshIntegrationTest.AudioProvider()
        ShadowContentResolver.registerProviderInternal("media", media)
        prefs = UserPreferencesRepository(Memory(), Json { ignoreUnknownKeys = true })
        prefs.includeDirectories(listOf("/music", "/other"))
        prefs.setLastSyncTimestamp(LibraryRefreshIntegrationTest.MARKER)
    }

    @After fun close() { db.close(); media.sql.close() }

    private suspend fun updateLibrary(): ListenableWorker.Result = TestListenableWorkerBuilder<SyncWorker>(context)
        .setInputData(workDataOf(SyncWorker.INPUT_SYNC_MODE to SyncMode.INCREMENTAL.name, SyncWorker.INPUT_RUN_MAINTENANCE to false))
        .setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                SyncWorker(appContext, workerParameters, dao, db.libraryItemOverrideDao(), mockk(relaxed = true), prefs, mockk(relaxed = true))
        }).build().doWork()

    private suspend fun seedKnown() {
        media.add(1, "Known", LibraryRefreshIntegrationTest.MARKER / 1000 + 1)
        assertTrue(updateLibrary() is ListenableWorker.Result.Success)
        prefs.setLastSyncTimestamp(LibraryRefreshIntegrationTest.MARKER)
        media.sql.execSQL("UPDATE audio SET date_modified=20, date_added=20")
        db.openHelper.writableDatabase.execSQL("UPDATE songs SET media_store_date_modified=20, media_store_date_added=20")
        media.metadataIds.clear()
    }

    @Test fun update_library_picks_up_a_file_moved_with_mv() = runBlocking {
        seedKnown()
        val identity = dao.getActiveSongByLocator(1)!!.libraryItemId
        assertEquals("/music/1.flac", dao.getActiveSongByLocator(1)!!.filePath)
        // `mv /music/1.flac /other/moved/1.flac`: same id, same dates, same size, same tags
        media.sql.execSQL("UPDATE audio SET _data='/other/moved/1.flac' WHERE _id=1")
        assertTrue(updateLibrary() is ListenableWorker.Result.Success)
        val moved = dao.getActiveSongByLocator(1)!!
        assertEquals("/other/moved/1.flac", moved.filePath)
        assertEquals("/other/moved", moved.parentDirectoryPath)
        assertEquals("the identity does not change when a file moves", identity, moved.libraryItemId)
        assertEquals(listOf(1L), media.metadataIds.toList())
    }

    @Test fun a_file_that_did_not_move_is_not_read_again() = runBlocking {
        seedKnown()
        assertTrue(updateLibrary() is ListenableWorker.Result.Success)
        assertTrue("nothing moved, nothing is read", media.metadataIds.isEmpty())
        media.sql.execSQL("UPDATE audio SET _data='/other/moved/1.flac' WHERE _id=1")
        assertTrue(updateLibrary() is ListenableWorker.Result.Success)
        media.metadataIds.clear()
        assertTrue(updateLibrary() is ListenableWorker.Result.Success)
        assertTrue("once picked up, it is not read again", media.metadataIds.isEmpty())
    }
}
