package com.lostf1sh.pixelplayeross.data.library

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
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
import com.lostf1sh.pixelplayeross.utils.buildLocalAudioSelection
import com.lostf1sh.pixelplayeross.utils.isMediaRowIncomplete
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
 * B40-fix1 (item 4), against the real sync: a row Android indexed without a duration (it indexed the copy before it could read it) has the
 * right size and time, so the walk took it for up to date, and the sync then left it out for want of a duration: the song never appeared.
 * Now such a row is asked about again, and once the scan has read it the song appears. The real `readMediaStoreIndex` reads the fake
 * MediaStore here, and the rule that says "incomplete" is pinned to the sync's own selection over the same rows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LibraryIncompleteRowsTest {
    private lateinit var rig: DormantCleanupRig
    private val marker = LibraryRefreshIntegrationTest.MARKER
    private val tree: File = Files.createTempDirectory("incomplete-rows").toFile()
    private val music = File(tree, "Music/DAP music")
    private val asked = mutableListOf<List<String>>()
    private var scanFillsDuration = true
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
                // Android's scanner as far as the app is concerned: it reads the file and fills in the duration (or cannot)
                scan = { paths, _ ->
                    asked += paths
                    if (scanFillsDuration) paths.forEach { rig.media.sql.execSQL("UPDATE audio SET duration = 200000 WHERE _data = ?", arrayOf<Any>(it)) }
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

    /** A row as Android left it for a just-copied file: the right size and time, and no duration. */
    private fun indexedWithoutDuration(id: Long, f: File, duration: String = "NULL") {
        rig.media.add(id, f.nameWithoutExtension, marker / 1000 + 5, path = f.path)
        rig.media.sql.execSQL(
            "UPDATE audio SET _size = ?, date_modified = ?, duration = $duration WHERE _id = ?",
            arrayOf<Any>(f.length(), f.lastModified() / 1000, id),
        )
    }

    @Test
    fun `a row without a duration is asked about again and the song appears once the scan has read it`() = runBlocking<Unit> {
        rig.prefs.updateDirectorySelections(setOf(music.path), setOf(LibraryFolders.NOTHING_BY_DEFAULT))
        rig.prefs.setLastSyncTimestamp(marker)
        val copied = file("copied just now.flac")
        indexedWithoutDuration(1, copied)
        assertThat(updateLibrary()).isInstanceOf(ListenableWorker.Result.Success::class.java)
        assertThat(asked.flatten()).containsExactly(copied.path)
        assertThat(titles()).containsExactly("copied just now")
    }

    @Test
    fun `a row with a duration of zero is the same case`() = runBlocking<Unit> {
        rig.prefs.updateDirectorySelections(setOf(music.path), setOf(LibraryFolders.NOTHING_BY_DEFAULT))
        rig.prefs.setLastSyncTimestamp(marker)
        val copied = file("zero.flac")
        indexedWithoutDuration(1, copied, duration = "0")
        updateLibrary()
        assertThat(asked.flatten()).containsExactly(copied.path)
        assertThat(titles()).containsExactly("zero")
    }

    @Test
    fun `a file that stays without a duration is asked once, then remembered, and never appears`() = runBlocking<Unit> {
        scanFillsDuration = false
        rig.prefs.updateDirectorySelections(setOf(music.path), setOf(LibraryFolders.NOTHING_BY_DEFAULT))
        rig.prefs.setLastSyncTimestamp(marker)
        val broken = file("broken.flac")
        indexedWithoutDuration(1, broken)
        updateLibrary()
        updateLibrary()
        updateLibrary()
        assertThat(asked.flatten()).containsExactly(broken.path)
        assertThat(titles()).isEmpty()
    }

    @Test
    fun `a complete row is not asked about`() = runBlocking<Unit> {
        rig.prefs.updateDirectorySelections(setOf(music.path), setOf(LibraryFolders.NOTHING_BY_DEFAULT))
        rig.prefs.setLastSyncTimestamp(marker)
        val fine = file("fine.flac")
        indexedWithoutDuration(1, fine, duration = "180000")
        updateLibrary()
        assertThat(asked).isEmpty()
        assertThat(titles()).containsExactly("fine")
    }

    // ── the rule is the sync's rule, not a copy of it ────────────────────────────────────────────

    private data class Row(val duration: Long?, val title: String?, val mime: String?, val data: String?)

    @Test
    fun `the incomplete rule agrees with the sync's own selection on every kind of row`() {
        val rows = listOf(
            Row(180_000, "Song", "audio/flac", "/m/a.flac"),
            Row(1, "Short but known", "audio/flac", "/m/b.flac"),
            Row(0, "Zero", "audio/flac", "/m/c.flac"),
            Row(null, "Null", "audio/flac", "/m/d.flac"),
            Row(-1, "Negative", "audio/mpeg", "/m/e.mp3"),
            Row(180_000, "", "audio/flac", "/m/f.flac"),
            Row(180_000, null, "audio/flac", "/m/g.flac"),
            Row(180_000, "No path", "audio/flac", null),
            Row(0, "Midi by mime", "audio/midi", "/m/h.bin"),
            Row(null, "Midi by extension", "application/octet-stream", "/m/i.MID"),
            Row(0, "Midi by other extension", null, "/m/j.midi"),
            Row(null, "Mime in capitals", "AUDIO/X-MIDI", "/m/k.bin"),
        )
        val db = SQLiteDatabase.create(null)
        db.execSQL("CREATE TABLE audio (n INTEGER PRIMARY KEY, duration INTEGER, title TEXT, mime_type TEXT, _data TEXT)")
        rows.forEachIndexed { n, r ->
            db.execSQL("INSERT INTO audio VALUES (?, ?, ?, ?, ?)", arrayOf<Any?>(n, r.duration, r.title, r.mime, r.data))
        }
        // the listener's minimum at its smallest meaningful value: a row that one millisecond of duration lets through
        val (selection, args) = buildLocalAudioSelection(minDurationMs = 1)
        val admitted = db.rawQuery("SELECT n FROM audio WHERE $selection", args).use { c -> generateSequence { if (c.moveToNext()) c.getInt(0) else null }.toSet() }
        rows.forEachIndexed { n, r ->
            assertThat(isMediaRowIncomplete(r.duration, r.title, r.mime, r.data)).isEqualTo(n !in admitted)
        }
        db.close()
    }
}
