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
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.service.PlaybackActivityTracker
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
 * B56 phase 1.2 (#49): a refresh the listener asked for (Library menu "Refresh", pull to refresh) runs at once even while music plays.
 *
 * Owner log 2026-11-03 00:43:31 and 00:44:02: `SyncWorker deferring INCREMENTAL sync (playback active, attempt=N)` -> RETRY, for the sync the listener had just
 * asked for. The automatic ones (storage change, foreground catch-up, startup) keep deferring. The "asked for" mark travels in the work's input data under the
 * key written out here; the tests do not use the Kotlin constant so that the same file runs against the tree that does not have it yet.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class B56ManualRefreshWhilePlayingTest {
    private lateinit var context: Context
    private lateinit var db: PixelPlayerDatabase
    private lateinit var media: LibraryRefreshIntegrationTest.AudioProvider
    private lateinit var prefs: UserPreferencesRepository

    @Before
    fun setup() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context as Application).grantPermissions(Manifest.permission.READ_MEDIA_AUDIO)
        db = Room.inMemoryDatabaseBuilder(context, PixelPlayerDatabase::class.java)
            .addCallback(PixelPlayerDatabase.createRuntimeArtifactsCallback()).allowMainThreadQueries().build()
        media = LibraryRefreshIntegrationTest.AudioProvider()
        ShadowContentResolver.registerProviderInternal("media", media)
        prefs = UserPreferencesRepository(MemoryPreferences(), Json { ignoreUnknownKeys = true })
        prefs.setLastSyncTimestamp(LibraryRefreshIntegrationTest.MARKER)
        media.add(1, "Known", LibraryRefreshIntegrationTest.MARKER / 1000 + 1)
    }

    @After
    fun close() {
        PlaybackActivityTracker.setPlaybackActive(false)
        db.close()
        media.sql.close()
    }

    private suspend fun runSync(asked: Boolean, attempt: Int = 0): ListenableWorker.Result {
        val input = workDataOf(
            SyncWorker.INPUT_SYNC_MODE to SyncMode.INCREMENTAL.name,
            SyncWorker.INPUT_RUN_MAINTENANCE to false,
            ASKED_FOR_KEY to asked,
        )
        return TestListenableWorkerBuilder<SyncWorker>(context)
            .setInputData(input)
            .setRunAttemptCount(attempt)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                    SyncWorker(
                        appContext, workerParameters, db.musicDao(), db.libraryItemOverrideDao(),
                        mockk(relaxed = true), prefs, mockk(relaxed = true),
                    )
            }).build().doWork()
    }

    @Test
    fun `a refresh the listener asked for runs while music plays`() = runBlocking {
        PlaybackActivityTracker.setPlaybackActive(true)

        val result = runSync(asked = true)

        assertTrue("an asked-for sync must run, not be put off: $result", result is ListenableWorker.Result.Success)
        assertEquals("the song reached the library", 1, db.musicDao().getAllSongs().first().size)
    }

    @Test
    fun `an automatic sync still waits while music plays`() = runBlocking {
        PlaybackActivityTracker.setPlaybackActive(true)

        val result = runSync(asked = false)

        assertTrue("an automatic sync must still be put off: $result", result is ListenableWorker.Result.Retry)
    }

    @Test
    fun `an automatic sync that has waited long enough runs`() = runBlocking {
        PlaybackActivityTracker.setPlaybackActive(true)

        val result = runSync(asked = false, attempt = 5)

        assertTrue("the deferral cap is unchanged: $result", result is ListenableWorker.Result.Success)
    }

    @Test
    fun `nothing is put off when no music plays`() = runBlocking {
        PlaybackActivityTracker.setPlaybackActive(false)

        assertTrue(runSync(asked = false) is ListenableWorker.Result.Success)
    }

    private class MemoryPreferences : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }

    private companion object {
        const val ASKED_FOR_KEY = "input_requested_by_listener"
    }
}
