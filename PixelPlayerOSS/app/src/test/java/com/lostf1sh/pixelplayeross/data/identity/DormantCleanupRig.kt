package com.lostf1sh.pixelplayeross.data.identity

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
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.database.PixelPlayerDatabase
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.stats.LibraryIdentityLookup
import com.lostf1sh.pixelplayeross.data.stats.PlaybackStatsRepository
import com.lostf1sh.pixelplayeross.data.stats.TrackEndReason
import com.lostf1sh.pixelplayeross.data.worker.LibraryRefreshIntegrationTest
import com.lostf1sh.pixelplayeross.data.worker.SyncMode
import com.lostf1sh.pixelplayeross.data.worker.SyncWorker
import com.lostf1sh.pixelplayeross.presentation.viewmodel.ListeningStatsTracker
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowContentResolver

/**
 * The pieces the clean-up tests share: real Room (in memory), the real library sync over a fake MediaStore, the real history file and a
 * way to build a real tracker over it. Only the tracker's identity lookup can be replaced, because what the tests of the clean-up
 * vary is when that answer reaches the tracker. Synthetic data only.
 */
internal class DormantCleanupRig : AutoCloseable {
    val context: Context = ApplicationProvider.getApplicationContext()
    val media = LibraryRefreshIntegrationTest.AudioProvider()
    val db: PixelPlayerDatabase
    val prefs: UserPreferencesRepository
    val history: PlaybackStatsRepository
    val dao get() = db.musicDao()
    private val marker = LibraryRefreshIntegrationTest.MARKER

    private class MemoryPreferences : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = transform(state.value).also { state.value = it }
    }

    init {
        shadowOf(context as Application).grantPermissions(Manifest.permission.READ_MEDIA_AUDIO)
        db = Room.inMemoryDatabaseBuilder(context, PixelPlayerDatabase::class.java)
            .addCallback(PixelPlayerDatabase.createRuntimeArtifactsCallback()).allowMainThreadQueries().build()
        ShadowContentResolver.registerProviderInternal("media", media)
        prefs = UserPreferencesRepository(MemoryPreferences(), Json { ignoreUnknownKeys = true })
        runBlocking { prefs.setLastSyncTimestamp(marker) }
        history = PlaybackStatsRepository(context)
    }

    override fun close() {
        db.close()
        media.sql.close()
    }

    /** A real tracker over the real history, asking [lookup] which library item a locator is (the real one unless a test says). */
    fun trackerWith(lookup: LibraryIdentityLookup = LibraryIdentityLookup(dao)) = ListeningStatsTracker(history, lookup)

    fun cleanupOver(tracker: ListeningStatsTracker) = DormantItemCleanup(db.libraryItemDao(), history, tracker)

    suspend fun refresh(): ListenableWorker.Result = TestListenableWorkerBuilder<SyncWorker>(context)
        .setInputData(workDataOf(SyncWorker.INPUT_SYNC_MODE to SyncMode.INCREMENTAL.name, SyncWorker.INPUT_RUN_MAINTENANCE to false))
        .setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                SyncWorker(appContext, workerParameters, dao, db.libraryItemOverrideDao(), mockk(relaxed = true), prefs, mockk(relaxed = true))
        }).build().doWork()

    suspend fun refreshSucceeds() = assertThat(refresh()).isInstanceOf(ListenableWorker.Result.Success::class.java)

    /** The library holds the given tracks (locator to title), each with a length of its own so none is taken for another. */
    suspend fun libraryOf(vararg tracks: Pair<Long, String>) {
        tracks.forEachIndexed { index, (id, title) -> media.add(id, title, marker / 1000 + 1, duration = 180_000L + index * 20_000L) }
        refreshSucceeds()
        prefs.setLastSyncTimestamp(marker)
        media.sql.execSQL("UPDATE audio SET date_modified=20, date_added=20")
        db.openHelper.writableDatabase.execSQL("UPDATE songs SET media_store_date_modified=20, media_store_date_added=20")
        media.metadataIds.clear()
    }

    suspend fun identityOf(locator: Long) = dao.getActiveSongByLocator(locator)!!.libraryItemId

    /** The library loses every file: its items are dormant after the sync. */
    suspend fun everyFileGoes() {
        media.sql.execSQL("DELETE FROM audio")
        refreshSucceeds()
    }

    /** The file of the first track comes back under another number. */
    suspend fun fileReturnsAt(locator: Long, title: String, durationMs: Long = 180_000L) {
        media.add(locator, title, 20, duration = durationMs)
        refreshSucceeds()
    }

    /** Two minutes of [tracker]'s open session pass (Robolectric's clock moves on its own), and the listen is closed. */
    fun endTwoMinuteListen(tracker: ListeningStatsTracker) {
        android.os.SystemClock.sleep(120_000)
        tracker.finalizeCurrentSession(endReason = TrackEndReason.USER_NEXT)
    }

    /** One listen of [locator] for two minutes, by the real tracker, and waits for the real repository to have written it. */
    fun listenTo(tracker: ListeningStatsTracker, locator: Long, already: Int = 0) {
        tracker.onTrackChanged(locator.toString(), 0L, 180_000L, 0L, true)
        endTwoMinuteListen(tracker)
        waitFor { runBlocking { history.exportEventsForBackup() }.size >= already + 1 }
    }

    fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        check(condition()) { "the condition did not hold within five seconds" }
    }
}
