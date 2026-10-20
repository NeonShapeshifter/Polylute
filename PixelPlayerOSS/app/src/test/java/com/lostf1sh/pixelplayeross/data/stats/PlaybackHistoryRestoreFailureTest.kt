package com.lostf1sh.pixelplayeross.data.stats

import android.net.Uri
import android.util.AtomicFile
import com.google.gson.Gson
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.backup.format.BackupReader
import com.lostf1sh.pixelplayeross.data.backup.model.BackupManifest
import com.lostf1sh.pixelplayeross.data.backup.model.BackupMessage
import com.lostf1sh.pixelplayeross.data.backup.model.BackupMessageException
import com.lostf1sh.pixelplayeross.data.backup.model.BackupModuleInfo
import com.lostf1sh.pixelplayeross.data.backup.model.BackupSection
import com.lostf1sh.pixelplayeross.data.backup.model.BackupValidationResult
import com.lostf1sh.pixelplayeross.data.backup.model.RestorePlan
import com.lostf1sh.pixelplayeross.data.backup.model.RestoreResult
import com.lostf1sh.pixelplayeross.data.backup.module.BackupModuleHandler
import com.lostf1sh.pixelplayeross.data.backup.module.PlaybackHistoryModuleHandler
import com.lostf1sh.pixelplayeross.data.backup.restore.RestoreExecutor
import com.lostf1sh.pixelplayeross.data.backup.validation.ValidationPipeline
import io.mockk.EqMatcher
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * B35 (RT-03): a history the disk refused is not a restore that worked. The real repository, the real handler and the real executor over a
 * history file whose `AtomicFile.startWrite` is made to fail. The auditor's probe (history write failed -> `Success`, old history
 * kept) is the first case, turned into a regression.
 */
class PlaybackHistoryRestoreFailureTest {

    private val uri: Uri = mockk(relaxed = true)

    private fun plan(vararg sections: BackupSection) = RestorePlan(
        manifest = BackupManifest(modules = sections.associate { it.key to BackupModuleInfo(sizeBytes = 2) }),
        backupUri = "backup",
        availableModules = sections.toSet(),
        selectedModules = sections.toSet(),
        moduleDetails = emptyMap(),
    )

    private fun executorOver(repository: PlaybackStatsRepository, extra: Map<BackupSection, BackupModuleHandler> = emptyMap(), payload: String = "[]"): RestoreExecutor {
        val reader = mockk<BackupReader>()
        val validation = mockk<ValidationPipeline>()
        coEvery { reader.readModulePayload(any(), any()) } returns Result.success(payload)
        every { validation.validateModulePayload(any(), any(), any()) } returns BackupValidationResult.Valid
        return RestoreExecutor(reader, validation, mapOf(BackupSection.PLAYBACK_HISTORY to PlaybackHistoryModuleHandler(repository, Gson())) + extra)
    }

    private fun failWrites(file: File, failures: Int = Int.MAX_VALUE) {
        var calls = 0
        every { constructedWith<AtomicFile>(EqMatcher(file)).startWrite() } answers {
            if (calls++ < failures) throw IOException("injected write failure") else FileOutputStream(file)
        }
    }

    @Test
    fun `a history write that fails is no success and the old history is the one on disk`() = runBlocking<Unit> {
        val (repo, dir) = newHistoryRepository()
        try {
            repo.recordPlayback("old-track", 30_000L, libraryItemId = "old-identity")
            failWrites(File(dir, "playback_history.json"))

            val result = executorOver(repo).execute(uri, plan(BackupSection.PLAYBACK_HISTORY)) { }

            // the restore failed and its own rollback could not write either: said as it is, not as a rollback that worked
            val partial = assertInstanceOf(RestoreResult.PartialFailure::class.java, result)
            assertFalse(partial.rolledBack)
            val reason = partial.failed.getValue(BackupSection.PLAYBACK_HISTORY)
            assertEquals(R.string.backup_error_history_write_failed, reason.resId)
            assertEquals(1, repo.exportEventsForBackup().size)              // the old event, still there
            assertEquals("old-track", repo.exportEventsForBackup().single().songId)
        } finally {
            releaseHistoryFiles()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a history write that fails once is rolled back and reported as a failed restore`() = runBlocking<Unit> {
        val (repo, dir) = newHistoryRepository()
        try {
            repo.recordPlayback("old-track", 30_000L, libraryItemId = "old-identity")
            failWrites(File(dir, "playback_history.json"), failures = 1)

            val result = executorOver(repo).execute(uri, plan(BackupSection.PLAYBACK_HISTORY)) { }

            val failure = assertInstanceOf(RestoreResult.TotalFailure::class.java, result)
            assertEquals(R.string.backup_error_rolled_back, failure.error.resId)
            assertTrue(failure.error.args.contains(BackupSection.PLAYBACK_HISTORY))
            assertTrue(failure.error.args.filterIsInstance<BackupMessage>().any { it.resId == R.string.backup_error_history_write_failed })
            assertEquals(listOf("old-track"), repo.exportEventsForBackup().map { it.songId })   // the rollback wrote the snapshot back
        } finally {
            releaseHistoryFiles()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a module restored before the history is rolled back when the history write fails`() = runBlocking<Unit> {
        val (repo, dir) = newHistoryRepository()
        try {
            repo.recordPlayback("old-track", 30_000L, libraryItemId = "old-identity")
            failWrites(File(dir, "playback_history.json"))
            var favorites = "old-favorites"
            val favoritesHandler = object : BackupModuleHandler {
                override val section = BackupSection.FAVORITES
                override suspend fun export() = favorites
                override suspend fun countEntries() = 1
                override suspend fun snapshot() = favorites
                override suspend fun restore(payload: String) { favorites = "new-favorites" }
                override suspend fun rollback(snapshot: String) { favorites = snapshot }
            }

            val result = executorOver(repo, mapOf(BackupSection.FAVORITES to favoritesHandler))
                .execute(uri, plan(BackupSection.FAVORITES, BackupSection.PLAYBACK_HISTORY)) { }

            assertFalse(result is RestoreResult.Success)
            assertEquals("old-favorites", favorites)                        // the operation's rollback ran for the module that did write
        } finally {
            releaseHistoryFiles()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `history restore still succeeds on writable storage`() = runBlocking<Unit> {
        val (repo, dir) = newHistoryRepository()
        try {
            repo.recordPlayback("old-track", 30_000L, libraryItemId = "old-identity")

            val result = executorOver(repo).execute(uri, plan(BackupSection.PLAYBACK_HISTORY)) { }

            assertEquals(RestoreResult.Success, result)
            assertTrue(repo.exportEventsForBackup().isEmpty())              // "[]" replaced the history
        } finally {
            releaseHistoryFiles()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `the repository raises a write it could not make and keeps what it had`() = runBlocking<Unit> {
        val (repo, dir) = newHistoryRepository()
        try {
            repo.recordPlayback("old-track", 30_000L, libraryItemId = "old-identity")
            failWrites(File(dir, "playback_history.json"))

            assertThrows(PlaybackHistoryWriteException::class.java) {
                runBlocking { repo.importEventsFromBackup(emptyList(), clearExisting = true) }
            }
            assertThrows(PlaybackHistoryWriteException::class.java) {
                runBlocking { repo.importEventsFromBackup(emptyList(), clearExisting = false) }
            }

            assertEquals(listOf("old-track"), repo.exportEventsForBackup().map { it.songId })
        } finally {
            releaseHistoryFiles()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `the handler turns it into the backup layer's own message and keeps the cause`() = runBlocking<Unit> {
        val (repo, dir) = newHistoryRepository()
        try {
            failWrites(File(dir, "playback_history.json"))

            val thrown = assertThrows(BackupMessageException::class.java) {
                runBlocking { PlaybackHistoryModuleHandler(repo, Gson()).restore("[]") }
            }

            assertEquals(R.string.backup_error_history_write_failed, thrown.backupMessage.resId)
            assertInstanceOf(PlaybackHistoryWriteException::class.java, thrown.cause)
        } finally {
            releaseHistoryFiles()
            dir.deleteRecursively()
        }
    }
}
