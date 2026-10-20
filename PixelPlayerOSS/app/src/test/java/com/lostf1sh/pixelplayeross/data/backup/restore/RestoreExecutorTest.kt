package com.lostf1sh.pixelplayeross.data.backup.restore

import com.lostf1sh.pixelplayeross.data.backup.model.BackupMessage
import com.lostf1sh.pixelplayeross.R
import android.net.Uri
import com.lostf1sh.pixelplayeross.data.backup.format.BackupReader
import com.lostf1sh.pixelplayeross.data.backup.model.BackupManifest
import com.lostf1sh.pixelplayeross.data.backup.model.BackupModuleInfo
import com.lostf1sh.pixelplayeross.data.backup.model.BackupSection
import com.lostf1sh.pixelplayeross.data.backup.model.BackupValidationResult
import com.lostf1sh.pixelplayeross.data.backup.model.DeviceInfo
import com.lostf1sh.pixelplayeross.data.backup.model.ModuleRestoreDetail
import com.lostf1sh.pixelplayeross.data.backup.model.RestorePlan
import com.lostf1sh.pixelplayeross.data.backup.model.RestoreResult
import com.lostf1sh.pixelplayeross.data.backup.module.BackupModuleHandler
import com.lostf1sh.pixelplayeross.data.backup.validation.ValidationPipeline
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RestoreExecutorTest {

    private val backupReader: BackupReader = mockk()
    private val validationPipeline: ValidationPipeline = mockk()
    private val favoritesHandler: BackupModuleHandler = mockk(relaxed = true)
    private val playbackHistoryHandler: BackupModuleHandler = mockk(relaxed = true)

    private val executor = RestoreExecutor(
        backupReader = backupReader,
        validationPipeline = validationPipeline,
        handlers = mapOf(
            BackupSection.FAVORITES to favoritesHandler,
            BackupSection.PLAYBACK_HISTORY to playbackHistoryHandler
        )
    )

    private val backupUri: Uri = mockk(relaxed = true)

    @Test
    fun `execute rolls back the module that fails during restore`() = runTest {
        val plan = restorePlan(
            selectedModules = setOf(BackupSection.FAVORITES, BackupSection.PLAYBACK_HISTORY)
        )

        coEvery { favoritesHandler.snapshot() } returns "favorites-snapshot"
        coEvery { playbackHistoryHandler.snapshot() } returns "history-snapshot"
        coEvery { backupReader.readModulePayload(backupUri, BackupSection.FAVORITES.key) } returns
            Result.success("favorites-payload")
        coEvery { backupReader.readModulePayload(backupUri, BackupSection.PLAYBACK_HISTORY.key) } returns
            Result.success("history-payload")
        every {
            validationPipeline.validateModulePayload(any(), any(), any())
        } returns BackupValidationResult.Valid
        coEvery { favoritesHandler.restore("favorites-payload") } returns Unit
        coEvery { playbackHistoryHandler.restore("history-payload") } throws IllegalStateException("boom")
        coEvery { favoritesHandler.rollback("favorites-snapshot") } returns Unit
        coEvery { playbackHistoryHandler.rollback("history-snapshot") } returns Unit

        val result = executor.execute(backupUri, plan) { }

        val failure = assertInstanceOf(RestoreResult.TotalFailure::class.java, result)
        // The section name is an argument now, so the assertion names the section rather than
        // a rendered English word inside a sentence.
        assertEquals(R.string.backup_error_rolled_back, failure.error.resId)
        assertTrue(failure.error.args.contains(BackupSection.PLAYBACK_HISTORY))
        coVerify(exactly = 1) { favoritesHandler.rollback("favorites-snapshot") }
        coVerify(exactly = 1) { playbackHistoryHandler.rollback("history-snapshot") }
    }

    @Test
    fun `execute fails when a selected module payload is missing`() = runTest {
        val plan = restorePlan(selectedModules = setOf(BackupSection.FAVORITES))

        coEvery { favoritesHandler.snapshot() } returns "favorites-snapshot"
        coEvery { backupReader.readModulePayload(backupUri, BackupSection.FAVORITES.key) } returns
            Result.failure(IllegalArgumentException("Module 'favorites' not found in backup"))

        val result = executor.execute(backupUri, plan) { }

        val failure = assertInstanceOf(RestoreResult.TotalFailure::class.java, result)
        // The failure is a sentence with arguments, so the test asserts its shape: which sentence,
        // which section, and that the underlying reason survived rather than collapsing into
        // "unknown error". A unit test has no Context, so the rendered text is not asserted here.
        assertEquals(R.string.backup_error_rolled_back, failure.error.resId)
        assertTrue(failure.error.args.contains(BackupSection.FAVORITES))
        assertEquals(
            listOf("Module 'favorites' not found in backup"),
            failure.error.args.filterIsInstance<BackupMessage>()
                .filter { it.resId == R.string.backup_detail_raw }
                .map { it.args.single() as String },
        )
        coVerify(exactly = 0) { favoritesHandler.restore(any()) }
    }

    @Test
    fun `execute fails before loading oversized module payload`() = runTest {
        val plan = restorePlan(
            selectedModules = setOf(BackupSection.PLAYBACK_HISTORY),
            moduleSizeBytes = BackupReader.MAX_MODULE_PAYLOAD_BYTES + 1L
        )

        coEvery { playbackHistoryHandler.snapshot() } returns "history-snapshot"

        val result = executor.execute(backupUri, plan) { }

        val failure = assertInstanceOf(RestoreResult.TotalFailure::class.java, result)
        // The size check's own sentence is carried as the reason inside the outer failure, so the
        // assertion checks the nesting rather than flattening it.
        assertEquals(R.string.backup_error_rolled_back, failure.error.resId)
        assertTrue(failure.error.args.contains(BackupSection.PLAYBACK_HISTORY))
        assertTrue(
            failure.error.args.filterIsInstance<BackupMessage>()
                .any { it.resId == R.string.backup_error_payload_over_limit }
        )
        coVerify(exactly = 0) { backupReader.readModulePayload(any(), any()) }
        coVerify(exactly = 0) { playbackHistoryHandler.restore(any()) }
    }


    // ── B35 (RT-02): cancelling a restore rolls back what it already replaced ─────────────────────────

    /**
     * A module that keeps its data in a field and, like every real handler (`FavoritesModuleHandler`, the stores), moves to
     * `Dispatchers.IO` to write: in a cancelled context that switch throws before anything is written.
     */
    private class StoreHandler(
        override val section: BackupSection,
        var data: String,
        private val whileRestoring: suspend (StoreHandler) -> Unit = {},
        private val whileRollingBack: suspend () -> Unit = {},
    ) : BackupModuleHandler {
        override suspend fun export(): String = data
        override suspend fun countEntries(): Int = 1
        override suspend fun snapshot(): String = data
        override suspend fun restore(payload: String) = withContext(Dispatchers.IO) {
            data = payload
            whileRestoring(this@StoreHandler)
        }
        override suspend fun rollback(snapshot: String) = withContext(Dispatchers.IO) {
            whileRollingBack()
            data = snapshot
        }
    }

    private fun payloadsFor(vararg sections: BackupSection) {
        sections.forEach { section ->
            coEvery { backupReader.readModulePayload(backupUri, section.key) } returns Result.success("new-${section.key}")
        }
        every { validationPipeline.validateModulePayload(any(), any(), any()) } returns BackupValidationResult.Valid
    }

    private fun executorOver(vararg handlers: StoreHandler) =
        RestoreExecutor(backupReader, validationPipeline, handlers.associateBy { it.section })

    private val both = setOf(BackupSection.FAVORITES, BackupSection.PLAYBACK_HISTORY)

    @Test
    fun `cancelling while the next module restores puts the first module back and ends cancelled`() = runBlocking<Unit> {
        payloadsFor(BackupSection.FAVORITES, BackupSection.PLAYBACK_HISTORY)
        val entered = CompletableDeferred<Unit>()
        val favorites = StoreHandler(BackupSection.FAVORITES, "old-favorites")
        val history = StoreHandler(BackupSection.PLAYBACK_HISTORY, "old-history", whileRestoring = {
            entered.complete(Unit)
            awaitCancellation()
        })
        var outcome: RestoreResult? = null
        val job = launch { outcome = executorOver(favorites, history).execute(backupUri, restorePlan(both)) { } }
        entered.await()
        assertEquals("new-favorites", favorites.data)                       // the first module really was replaced

        job.cancelAndJoin()

        assertEquals("old-favorites", favorites.data)
        assertEquals("old-history", history.data)                           // the module that was mid-write is back too
        assertTrue(job.isCancelled)
        assertEquals(null, outcome)                                         // a cancelled restore reports nothing: not a success, not a failure
    }

    @Test
    fun `a rollback that suspends still runs to its end after a cancellation`() = runBlocking<Unit> {
        payloadsFor(BackupSection.FAVORITES, BackupSection.PLAYBACK_HISTORY)
        val entered = CompletableDeferred<Unit>()
        val favorites = StoreHandler(BackupSection.FAVORITES, "old-favorites", whileRollingBack = { delay(50) })
        val history = StoreHandler(BackupSection.PLAYBACK_HISTORY, "old-history", whileRestoring = {
            entered.complete(Unit)
            awaitCancellation()
        }, whileRollingBack = { delay(50) })
        val job = launch { executorOver(favorites, history).execute(backupUri, restorePlan(both)) { } }
        entered.await()

        job.cancelAndJoin()

        assertEquals("old-favorites", favorites.data)
        assertEquals("old-history", history.data)
    }

    @Test
    fun `a module that cannot be rolled back does not stop the others being put back on cancellation`() = runBlocking<Unit> {
        payloadsFor(BackupSection.FAVORITES, BackupSection.PLAYBACK_HISTORY)
        val entered = CompletableDeferred<Unit>()
        val favorites = StoreHandler(BackupSection.FAVORITES, "old-favorites")
        val history = StoreHandler(BackupSection.PLAYBACK_HISTORY, "old-history", whileRestoring = {
            entered.complete(Unit)
            awaitCancellation()
        }, whileRollingBack = { throw java.io.IOException("disk gone") })
        val job = launch { executorOver(favorites, history).execute(backupUri, restorePlan(both)) { } }
        entered.await()

        job.cancelAndJoin()

        assertEquals("old-favorites", favorites.data)                       // rolled back after the history's rollback failed
        assertTrue(job.isCancelled)
    }

    @Test
    fun `cancelling during the snapshot writes nothing and rolls nothing back`() = runBlocking<Unit> {
        payloadsFor(BackupSection.FAVORITES, BackupSection.PLAYBACK_HISTORY)
        val entered = CompletableDeferred<Unit>()
        val favorites = StoreHandler(BackupSection.FAVORITES, "old-favorites")
        val history = object : BackupModuleHandler {
            override val section = BackupSection.PLAYBACK_HISTORY
            var rolledBack = false
            override suspend fun export() = "old-history"
            override suspend fun countEntries() = 1
            override suspend fun snapshot(): String {
                entered.complete(Unit)
                awaitCancellation()
            }
            override suspend fun restore(payload: String) = Unit
            override suspend fun rollback(snapshot: String) { rolledBack = true }
        }
        var outcome: RestoreResult? = null
        val job = launch {
            outcome = RestoreExecutor(backupReader, validationPipeline, mapOf(BackupSection.FAVORITES to favorites, BackupSection.PLAYBACK_HISTORY to history))
                .execute(backupUri, restorePlan(both)) { }
        }
        entered.await()

        job.cancelAndJoin()

        assertEquals("old-favorites", favorites.data)                       // never touched
        assertEquals(false, history.rolledBack)
        assertTrue(job.isCancelled)
        assertEquals(null, outcome)                                         // not the "snapshot failed" total failure it used to turn into
    }

    @Test
    fun `an ordinary failure in the next module still rolls the first one back as before`() = runBlocking<Unit> {
        payloadsFor(BackupSection.FAVORITES, BackupSection.PLAYBACK_HISTORY)
        val favorites = StoreHandler(BackupSection.FAVORITES, "old-favorites")
        val history = StoreHandler(BackupSection.PLAYBACK_HISTORY, "old-history", whileRestoring = { throw IllegalStateException("read error") })

        val result = executorOver(favorites, history).execute(backupUri, restorePlan(both)) { }

        val failure = assertInstanceOf(RestoreResult.TotalFailure::class.java, result)
        assertEquals(R.string.backup_error_rolled_back, failure.error.resId)
        assertEquals("old-favorites", favorites.data)
        assertEquals("old-history", history.data)
    }

    @Test
    fun `an ordinary failure whose rollback also fails is said not to be rolled back`() = runBlocking<Unit> {
        payloadsFor(BackupSection.FAVORITES, BackupSection.PLAYBACK_HISTORY)
        val favorites = StoreHandler(BackupSection.FAVORITES, "old-favorites")
        val history = StoreHandler(BackupSection.PLAYBACK_HISTORY, "old-history",
            whileRestoring = { throw IllegalStateException("read error") },
            whileRollingBack = { throw java.io.IOException("disk gone") })

        val result = executorOver(favorites, history).execute(backupUri, restorePlan(both)) { }

        val partial = assertInstanceOf(RestoreResult.PartialFailure::class.java, result)
        assertEquals(false, partial.rolledBack)
        assertEquals("old-favorites", favorites.data)                       // the module that could be put back was
    }

    private fun restorePlan(
        selectedModules: Set<BackupSection>,
        moduleSizeBytes: Long = 32
    ): RestorePlan {
        val modules = selectedModules.associate { section ->
            section.key to BackupModuleInfo(
                checksum = "sha256:test",
                entryCount = 1,
                sizeBytes = moduleSizeBytes
            )
        }
        return RestorePlan(
            manifest = BackupManifest(
                schemaVersion = BackupManifest.CURRENT_SCHEMA_VERSION,
                appVersion = "test",
                appVersionCode = 1,
                createdAt = 1_700_000_000_000,
                deviceInfo = DeviceInfo(),
                modules = modules
            ),
            backupUri = backupUri.toString(),
            availableModules = selectedModules,
            selectedModules = selectedModules,
            moduleDetails = selectedModules.associateWith {
                ModuleRestoreDetail(
                    entryCount = 1,
                    sizeBytes = moduleSizeBytes,
                    willOverwrite = true
                )
            }
        )
    }
}
