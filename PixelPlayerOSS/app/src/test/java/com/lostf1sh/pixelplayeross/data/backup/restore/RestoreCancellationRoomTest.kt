package com.lostf1sh.pixelplayeross.data.backup.restore

import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.gson.GsonBuilder
import com.lostf1sh.pixelplayeross.data.backup.format.BackupReader
import com.lostf1sh.pixelplayeross.data.backup.model.BackupManifest
import com.lostf1sh.pixelplayeross.data.backup.model.BackupModuleInfo
import com.lostf1sh.pixelplayeross.data.backup.model.BackupSection
import com.lostf1sh.pixelplayeross.data.backup.model.BackupValidationResult
import com.lostf1sh.pixelplayeross.data.backup.model.RestorePlan
import com.lostf1sh.pixelplayeross.data.backup.module.BackupModuleHandler
import com.lostf1sh.pixelplayeross.data.backup.module.FavoritesModuleHandler
import com.lostf1sh.pixelplayeross.data.backup.validation.ValidationPipeline
import com.lostf1sh.pixelplayeross.data.database.FavoritesEntity
import com.lostf1sh.pixelplayeross.data.database.PixelPlayerDatabase
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B35 (RT-02), the integration the unit tests stand in for: the real [RestoreExecutor], the real [FavoritesModuleHandler] and its Room
 * DAO over an in-memory database. Favourites is replaced, the next module is held, the restore is cancelled: the favourites of before
 * must be back (a Room write switches threads, which is exactly what a cancelled context refuses).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class RestoreCancellationRoomTest {

    private lateinit var db: PixelPlayerDatabase
    private val uri: Uri = mockk(relaxed = true)

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PixelPlayerDatabase::class.java)
            .addCallback(PixelPlayerDatabase.createRuntimeArtifactsCallback())
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun close() {
        db.close()
    }

    private val sections = setOf(BackupSection.FAVORITES, BackupSection.PLAYBACK_HISTORY)

    private fun plan() = RestorePlan(
        manifest = BackupManifest(modules = sections.associate { it.key to BackupModuleInfo(sizeBytes = 64) }),
        backupUri = "backup",
        availableModules = sections,
        selectedModules = sections,
        moduleDetails = emptyMap(),
    )

    private fun executor(entered: CompletableDeferred<Unit>?): RestoreExecutor {
        val reader = mockk<BackupReader>()
        val validation = mockk<ValidationPipeline>()
        coEvery { reader.readModulePayload(any(), BackupSection.FAVORITES.key) } returns
            Result.success("""[{"songId":2,"isFavorite":true,"timestamp":20,"libraryItemId":"item-2"}]""")
        coEvery { reader.readModulePayload(any(), BackupSection.PLAYBACK_HISTORY.key) } returns Result.success("[]")
        every { validation.validateModulePayload(any(), any(), any()) } returns BackupValidationResult.Valid
        val history = object : BackupModuleHandler {
            override val section = BackupSection.PLAYBACK_HISTORY
            override suspend fun export() = "[]"
            override suspend fun countEntries() = 0
            override suspend fun snapshot() = "[]"
            override suspend fun restore(payload: String) {
                entered?.complete(Unit)
                if (entered != null) awaitCancellation() else error("history unreadable")
            }
            override suspend fun rollback(snapshot: String) = Unit
        }
        val favorites = FavoritesModuleHandler(db.favoritesDao(), GsonBuilder().serializeNulls().create())
        return RestoreExecutor(reader, validation, mapOf(BackupSection.FAVORITES to favorites, BackupSection.PLAYBACK_HISTORY to history))
    }

    private suspend fun favoriteIds() = db.favoritesDao().getAllFavoritesOnce().map { it.songId }

    private suspend fun seedOldFavorite() {
        db.favoritesDao().insertAll(listOf(FavoritesEntity(songId = 1L, isFavorite = true, timestamp = 10L, libraryItemId = "item-1")))
    }

    @Test
    fun cancellingAfterRoomWasReplacedPutsTheOldFavouritesBack() = runBlocking<Unit> {
        seedOldFavorite()
        val entered = CompletableDeferred<Unit>()
        val job = launch { executor(entered).execute(uri, plan()) { } }
        entered.await()
        assertThat(favoriteIds()).containsExactly(2L)                       // the restore did replace them

        job.cancelAndJoin()

        assertThat(favoriteIds()).containsExactly(1L)                       // and the cancel put them back
        assertThat(db.favoritesDao().getAllFavoritesOnce().single().libraryItemId).isEqualTo("item-1")
        assertThat(job.isCancelled).isTrue()
    }

    @Test
    fun anOrdinaryFailureAfterRoomWasReplacedPutsTheOldFavouritesBackToo() = runBlocking<Unit> {
        seedOldFavorite()

        val result = executor(entered = null).execute(uri, plan()) { }

        assertThat(result).isInstanceOf(com.lostf1sh.pixelplayeross.data.backup.model.RestoreResult.TotalFailure::class.java)
        assertThat(favoriteIds()).containsExactly(1L)
    }
}
