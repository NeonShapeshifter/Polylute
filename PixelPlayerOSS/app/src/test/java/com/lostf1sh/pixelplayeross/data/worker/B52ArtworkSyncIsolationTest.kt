package com.lostf1sh.pixelplayeross.data.worker

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.*
import androidx.work.testing.TestListenableWorkerBuilder
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.database.PixelPlayerDatabase
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.repository.FolderArtworkRefresh
import io.mockk.*
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class B52ArtworkSyncIsolationTest {
    @Test fun `artwork failure preserves the evidence commit and successful sync`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(context as Application).grantPermissions(Manifest.permission.READ_MEDIA_AUDIO)
        val db = Room.inMemoryDatabaseBuilder(context, PixelPlayerDatabase::class.java)
            .addCallback(PixelPlayerDatabase.createRuntimeArtifactsCallback()).allowMainThreadQueries().build()
        val media = LibraryRefreshIntegrationTest.AudioProvider()
        ShadowContentResolver.registerProviderInternal("media", media)
        val store = object : DataStore<Preferences> {
            override val data = MutableStateFlow(emptyPreferences())
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = transform(data.value).also { data.value = it }
        }
        val prefs = UserPreferencesRepository(store, Json { ignoreUnknownKeys = true })
        val artwork = mockk<FolderArtworkRefresh>()
        var evidenceAtRefresh = false
        val dao = db.musicDao()
        coEvery { artwork.refresh() } coAnswers {
            val items = dao.getAllLibraryItems()
            evidenceAtRefresh = items.isNotEmpty() && items.all { it.fileSignature.isNotBlank() }
            throw IOException("folder unavailable")
        }
        try {
            media.add(52, "Fixture", 20)
            val worker = TestListenableWorkerBuilder<SyncWorker>(context)
                .setInputData(workDataOf(SyncWorker.INPUT_SYNC_MODE to SyncMode.INCREMENTAL.name, SyncWorker.INPUT_RUN_MAINTENANCE to false))
                .setWorkerFactory(object : WorkerFactory() {
                    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                        SyncWorker(appContext, workerParameters, dao, db.libraryItemOverrideDao(), mockk(relaxed = true), prefs, mockk(relaxed = true), artwork)
                }).build()
            assertThat(worker.doWork()).isInstanceOf(ListenableWorker.Result.Success::class.java)
            assertThat(evidenceAtRefresh).isTrue()
            assertThat(dao.getAllLibraryItems().map { it.fileSignature }).containsNoDuplicates()
            assertThat(dao.getAllLibraryItems().single().fileSignature).isNotEmpty()
        } finally { db.close(); media.sql.close(); unmockkAll() }
    }
}
