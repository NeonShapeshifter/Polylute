package com.lostf1sh.pixelplayeross.data.library

import android.app.Application
import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.identity.DormantCleanupRig
import com.lostf1sh.pixelplayeross.data.worker.LibraryRefreshIntegrationTest
import com.lostf1sh.pixelplayeross.data.worker.SyncMode
import com.lostf1sh.pixelplayeross.data.worker.SyncWorker
import io.mockk.mockk
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B41-fix1 (B11): a file the library leaves out because the listener's minimum duration says so is not "incomplete" and is not asked for on
 * every folder walk. Only an unknown or zero duration is incomplete, and such a file is asked once and remembered.
 *
 * Run against the real sync and the real walk over a fake MediaStore, with the minimum at 10 s: a 5 s file with a known duration is in the
 * walk's index as complete, so the walk asks about nothing, however many times it runs, and the library does not list it. A file with a
 * duration of zero is asked once. (B41's prompt described the 5 s file as asked on every walk; the code and the device log of B40 do not do
 * that, so these tests pin what is true. See B41-fix1-resultado.md, B11.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class B41Fix1ShortFileWalkTest {
    private lateinit var rig: DormantCleanupRig
    private val marker = LibraryRefreshIntegrationTest.MARKER
    private val tree: File = Files.createTempDirectory("short-file-walk").toFile()
    private val music = File(tree, "Music/DAP music")
    private val asked = mutableListOf<List<String>>()
    private val declined = mutableSetOf<String>()

    @Before
    fun setUp() {
        rig = DormantCleanupRig()
        SyncWorker.fileScannerFactory = { context ->
            LibraryFileScanner(
                context = context,
                memory = object : LibraryFileScanner.Memory {
                    override fun lastWalkAtMs() = 0L
                    override fun noteWalk(atMs: Long) = Unit
                    override fun declined(candidate: LibraryFileWalk.Candidate) = "${candidate.path}|${candidate.size}|${candidate.modifiedMs}" in declined
                    override fun decline(candidate: LibraryFileWalk.Candidate) { declined += "${candidate.path}|${candidate.size}|${candidate.modifiedMs}" }
                },
                // Android indexes what it is asked about, and answers that it has done so; a file with no duration still has none afterwards
                scan = { paths, _ ->
                    asked += paths
                    emptySet()
                },
            )
        }
    }

    @After
    fun tearDown() {
        SyncWorker.fileScannerFactory = { context -> LibraryFileScanner(context) }
        rig.close()
        tree.deleteRecursively()
    }

    private fun file(name: String): File = File(music, name).also { it.parentFile.mkdirs(); it.writeBytes(ByteArray(2048)) }

    private suspend fun updateLibrary(): ListenableWorker.Result = TestListenableWorkerBuilder<SyncWorker>(rig.context)
        .setInputData(
            workDataOf(
                SyncWorker.INPUT_SYNC_MODE to SyncMode.INCREMENTAL.name,
                SyncWorker.INPUT_RUN_MAINTENANCE to false,
                SyncWorker.INPUT_WALK_FOLDERS to true,
            ),
        )
        .setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                SyncWorker(appContext, workerParameters, rig.dao, rig.db.libraryItemOverrideDao(), mockk(relaxed = true), rig.prefs, mockk(relaxed = true))
        }).build().doWork()

    private suspend fun titles() = rig.dao.getAllSongs().first().map { it.title }.toSet()

    /** A row as Android indexed it: the right size and time, and [duration] (SQL) as the duration. */
    private fun indexed(id: Long, f: File, duration: String) {
        rig.media.add(id, f.nameWithoutExtension, marker / 1000 + 5, path = f.path)
        rig.media.sql.execSQL(
            "UPDATE audio SET _size = ?, date_modified = ?, duration = $duration WHERE _id = ?",
            arrayOf<Any>(f.length(), f.lastModified() / 1000, id),
        )
    }

    private suspend fun setUpFolder() {
        rig.prefs.updateDirectorySelections(setOf(music.path), setOf(LibraryFolders.NOTHING_BY_DEFAULT))
        rig.prefs.setLastSyncTimestamp(marker)
        rig.prefs.setMinSongDuration(10_000)
    }

    @Test
    fun `a 5 s file with a known duration under a 10 s minimum is not asked about, however many walks`() = runBlocking<Unit> {
        setUpFolder()
        val short = file("short.flac")
        val long = file("long.flac")
        indexed(1, short, duration = "5000")
        indexed(2, long, duration = "180000")
        repeat(3) { assertThat(updateLibrary()).isInstanceOf(ListenableWorker.Result.Success::class.java) }
        assertThat(asked).isEmpty()
        // the minimum is the listener's: the library has the long one and not the short one
        assertThat(titles()).containsExactly("long")
    }

    @Test
    fun `a 0-duration file under the same minimum is still asked, once`() = runBlocking<Unit> {
        setUpFolder()
        val zero = file("zero.flac")
        indexed(1, zero, duration = "0")
        repeat(3) { updateLibrary() }
        assertThat(asked.flatten()).containsExactly(zero.path)
    }

    @Test
    fun `the scanner's incomplete rule has no minimum duration in it`() {
        val rule = File("src/main/java/com/lostf1sh/pixelplayeross/utils/MediaStoreSelectionUtils.kt").readText()
        val fn = rule.substringAfter("fun isMediaRowIncomplete").substringBefore("\n}\n")
        assertThat(fn).doesNotContain("minDuration")
        assertThat(fn).doesNotContain("minSong")
    }

    @Test
    fun `the walk's log line says why it asks, so a file asked about on every walk can be named`() {
        fun c(why: LibraryFileWalk.Why) = LibraryFileWalk.Candidate("/m/a", 1L, 2L, why)
        assertThat(LibraryFileWalk.reasons(emptyList())).isEmpty()
        assertThat(LibraryFileWalk.reasons(listOf(c(LibraryFileWalk.Why.STALE)))).isEqualTo(" (1 STALE)")
        assertThat(LibraryFileWalk.reasons(listOf(c(LibraryFileWalk.Why.INCOMPLETE), c(LibraryFileWalk.Why.MISSING), c(LibraryFileWalk.Why.MISSING))))
            .isEqualTo(" (2 MISSING, 1 INCOMPLETE)")
        val scanner = File("src/main/java/com/lostf1sh/pixelplayeross/data/library/LibraryFileScanner.kt").readText()
        assertThat(scanner).contains("LibraryFileWalk.reasons(toAsk)")
    }
}
