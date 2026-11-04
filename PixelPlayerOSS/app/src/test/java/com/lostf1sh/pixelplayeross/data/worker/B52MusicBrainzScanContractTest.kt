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
import com.lostf1sh.pixelplayeross.data.identity.LibraryIdentity
import com.lostf1sh.pixelplayeross.data.media.AudioMetadata
import com.lostf1sh.pixelplayeross.data.media.AudioMetadataReader
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import io.mockk.*
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class B52MusicBrainzScanContractTest {
    @Test fun `scan stores IDs for two of three tagged fixtures and retagging IDs preserves every library identity`() = runBlocking {
        val idType = Class.forName("com.lostf1sh.pixelplayeross.data.model.MusicBrainzIds")
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(context as Application).grantPermissions(Manifest.permission.READ_MEDIA_AUDIO)
        val root = Files.createTempDirectory("b52-id-scan").toFile()
        val db = Room.inMemoryDatabaseBuilder(context, PixelPlayerDatabase::class.java)
            .addCallback(PixelPlayerDatabase.createRuntimeArtifactsCallback()).allowMainThreadQueries().build()
        val media = LibraryRefreshIntegrationTest.AudioProvider()
        ShadowContentResolver.registerProviderInternal("media", media)
        val store = object : DataStore<Preferences> {
            override val data = MutableStateFlow(emptyPreferences())
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = transform(data.value).also { data.value = it }
        }
        val prefs = UserPreferencesRepository(store, Json { ignoreUnknownKeys = true })
        prefs.includeDirectories(listOf(root.path))
        val ctor = idType.constructors.single { it.parameterCount == 5 }
        val id = "00000001-0000-4000-8000-000000000001"
        var firstIds = ctor.newInstance(id, id, id, id, id)
        fun metadata(ids: Any): AudioMetadata {
            val metadata = AudioMetadata(null, "Track Artist, Guest", "Album Artist", null, null, null, null, null, null, null, null, null, null, null)
            metadata.javaClass.getDeclaredField("musicBrainzIds").apply { isAccessible = true }.set(metadata, ids)
            return metadata
        }
        mockkObject(AudioMetadataReader)
        every { AudioMetadataReader.read(any<File>(), false, false) } answers {
            metadata(when (firstArg<File>().name) {
                "52.ogg" -> firstIds
                "53.ogg" -> ctor.newInstance(null, null, null, id, null)
                else -> ctor.newInstance(null, null, null, null, null)
            })
        }
        suspend fun scan(): ListenableWorker.Result = TestListenableWorkerBuilder<SyncWorker>(context)
            .setInputData(workDataOf(SyncWorker.INPUT_SYNC_MODE to SyncMode.INCREMENTAL.name, SyncWorker.INPUT_RUN_MAINTENANCE to false))
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                    SyncWorker(appContext, workerParameters, db.musicDao(), db.libraryItemOverrideDao(), mockk(relaxed = true), prefs, mockk(relaxed = true))
            }).build().doWork()
        try {
            for (number in 52..54) {
                val file = root.resolve("$number.ogg").apply { writeText("fake tag container") }
                media.add(number.toLong(), "Fixture $number", 20, path = file.path)
            }
            assertThat(scan()).isInstanceOf(ListenableWorker.Result.Success::class.java)
            val before = db.musicDao().getAllSongsList()
            assertThat(before.count { it.mbRecordingId != null || it.mbReleaseId != null || it.mbArtistId != null }).isEqualTo(2)
            suspend fun tagged(kind: String, vararg values: String): String? = suspendCoroutineUninterceptedOrReturn { continuation ->
                val method = db.musicDao().javaClass.methods.single { it.name == kind }
                method.invoke(db.musicDao(), *values, continuation)
            }
            assertThat(tagged("taggedArtistId", "Track Artist")).isEqualTo(id)
            assertThat(tagged("taggedArtistId", "Guest")).isNull()
            assertThat(tagged("taggedArtistId", "Album Artist")).isEqualTo(id)
            assertThat(tagged("taggedReleaseId", "Album", "Album Artist")).isEqualTo(id)
            assertThat(tagged("taggedReleaseGroupId", "Album", "Album Artist")).isEqualTo(id)
            db.openHelper.writableDatabase.query("SELECT mb_release_group_id, mb_album_artist_id FROM songs WHERE id=52").use {
                assertThat(it.moveToFirst()).isTrue(); assertThat(it.getString(0)).isEqualTo(id); assertThat(it.getString(1)).isEqualTo(id)
            }
            before.forEach {
                assertThat(it.stableKey).isEqualTo(LibraryIdentity.computeKey(180_000, 8_000_000, "Fixture ${it.id}", "Artist", "Album", 1, 0))
            }
            firstIds = ctor.newInstance("00000002-0000-4000-8000-000000000002", id, id, id, id)
            media.sql.execSQL("UPDATE audio SET date_modified=${System.currentTimeMillis() / 1000 + 5}")
            assertThat(scan()).isInstanceOf(ListenableWorker.Result.Success::class.java)
            val after = db.musicDao().getAllSongsList()
            assertThat(after.associate { it.id to it.libraryItemId }).isEqualTo(before.associate { it.id to it.libraryItemId })
            assertThat(after.associate { it.id to it.stableKey }).isEqualTo(before.associate { it.id to it.stableKey })
            assertThat(after.single { it.id == 52L }.mbRecordingId).isEqualTo("00000002-0000-4000-8000-000000000002")
            println("B52_MB_LIBRARY songs=3 with_ids=2 identities_unchanged_after_id_retag=true")
        } finally { db.close(); media.sql.close(); root.deleteRecursively(); unmockkAll() }
    }
}
