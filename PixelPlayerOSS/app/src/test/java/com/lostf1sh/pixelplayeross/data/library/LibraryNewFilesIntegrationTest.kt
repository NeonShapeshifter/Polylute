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
 * B40-F3, against the real sync: Room in memory, the real worker over a fake MediaStore, the real folder walk over a real folder tree.
 * A file copied into an included folder that MediaStore does not have yet appears in the library after "Update library", because the walk
 * asks Android's scanner (stood in for here by a function that adds the row, as Android does) before the sync reads MediaStore. A file in
 * a folder that is not included stays out. Synthetic data only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LibraryNewFilesIntegrationTest {
    private lateinit var rig: DormantCleanupRig
    private val marker = LibraryRefreshIntegrationTest.MARKER
    private val tree: File = Files.createTempDirectory("new-files").toFile()
    private val music = File(tree, "Music/DAP music")
    private val other = File(tree, "Music/Other stuff")
    private val asked = mutableListOf<List<String>>()
    private var nextId = 500L

    @Before
    fun setUp() {
        rig = DormantCleanupRig()
        // Android's scanner, as far as the app is concerned: it adds a MediaStore row for each file it is asked to index
        SyncWorker.fileScannerFactory = { context ->
            LibraryFileScanner(
                context = context,
                memory = object : LibraryFileScanner.Memory {
                    override fun lastWalkAtMs() = 0L
                    override fun noteWalk(atMs: Long) = Unit
                    override fun declined(candidate: LibraryFileWalk.Candidate) = false
                    override fun decline(candidate: LibraryFileWalk.Candidate) = Unit
                },
                scan = { paths, _ ->
                    asked += paths
                    paths.forEach { path -> rig.media.add(nextId++, File(path).nameWithoutExtension, marker / 1000 + 5, path = path, duration = 200_000L + nextId) }
                    emptySet()
                },
                readIndex = { roots ->
                    val index = HashMap<String, LibraryFileWalk.Indexed>()
                    rig.media.sql.rawQuery("SELECT _data, _size, date_modified FROM audio", null).use { c ->
                        while (c.moveToNext()) {
                            val path = c.getString(0)
                            if (roots.any { path.startsWith("$it/") }) index[path] = LibraryFileWalk.Indexed(c.getLong(1), c.getLong(2))
                        }
                    }
                    index
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

    private fun file(folder: File, name: String): File = File(folder, name).also { it.parentFile.mkdirs(); it.writeBytes(ByteArray(2048)) }

    /** "Update library": the incremental sync the listener's own ask runs, with the folder walk on. */
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

    @Test
    fun `a file copied into an included folder appears after Update library, without MediaStore having it before`() = runBlocking<Unit> {
        rig.prefs.updateDirectorySelections(setOf(music.path), setOf(LibraryFolders.NOTHING_BY_DEFAULT))
        rig.prefs.setLastSyncTimestamp(marker)
        val known = file(music, "Artist/Album/known.flac")
        rig.media.add(1, "known", marker / 1000 + 1, path = known.path, duration = 180_000L)
        rig.media.sql.execSQL("UPDATE audio SET _size = ?, date_modified = ?", arrayOf<Any>(known.length(), known.lastModified() / 1000))
        assertThat(updateLibrary()).isInstanceOf(ListenableWorker.Result.Success::class.java)
        assertThat(titles()).containsExactly("known")

        // a copy from a PC: on disk, not in MediaStore
        val copied = file(music, "Artist/Album/copied from pc.flac")
        assertThat(rig.media.sql.rawQuery("SELECT COUNT(*) FROM audio WHERE _data = ?", arrayOf(copied.path)).use { it.moveToFirst(); it.getInt(0) }).isEqualTo(0)

        assertThat(updateLibrary()).isInstanceOf(ListenableWorker.Result.Success::class.java)
        assertThat(asked.flatten()).containsExactly(copied.path)
        assertThat(titles()).containsExactly("known", "copied from pc")
    }

    @Test
    fun `a file in a folder that is not included stays out, and is not even asked for`() = runBlocking<Unit> {
        rig.prefs.updateDirectorySelections(setOf(music.path), setOf(LibraryFolders.NOTHING_BY_DEFAULT))
        rig.prefs.setLastSyncTimestamp(marker)
        file(music, "in.flac")
        val outside = file(other, "out.flac")
        assertThat(updateLibrary()).isInstanceOf(ListenableWorker.Result.Success::class.java)
        assertThat(titles()).containsExactly("in")
        assertThat(asked.flatten()).doesNotContain(outside.path)
    }

    @Test
    fun `without the walk the same sync leaves the file out - that is what Update library did before`() = runBlocking<Unit> {
        rig.prefs.updateDirectorySelections(setOf(music.path), setOf(LibraryFolders.NOTHING_BY_DEFAULT))
        rig.prefs.setLastSyncTimestamp(marker)
        file(music, "copied from pc.flac")
        // the sync as it ran before this batch: no INPUT_WALK_FOLDERS
        assertThat(rig.refresh()).isInstanceOf(ListenableWorker.Result.Success::class.java)
        assertThat(titles()).isEmpty()
        assertThat(asked).isEmpty()
    }

    @Test
    fun `a walk that fails does not stop the sync`() = runBlocking<Unit> {
        rig.prefs.updateDirectorySelections(setOf(music.path), setOf(LibraryFolders.NOTHING_BY_DEFAULT))
        rig.prefs.setLastSyncTimestamp(marker)
        val known = file(music, "known.flac")
        rig.media.add(1, "known", marker / 1000 + 1, path = known.path, duration = 180_000L)
        SyncWorker.fileScannerFactory = { context ->
            LibraryFileScanner(context, scan = { _, _ -> error("the scanner is not there") }, readIndex = { error("no MediaStore") })
        }
        assertThat(updateLibrary()).isInstanceOf(ListenableWorker.Result.Success::class.java)
        assertThat(titles()).containsExactly("known")
    }
}
