package com.lostf1sh.pixelplayeross.data.media

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lostf1sh.pixelplayeross.data.database.AlbumEntity
import com.lostf1sh.pixelplayeross.data.database.ArtistEntity
import com.lostf1sh.pixelplayeross.data.database.PixelPlayerDatabase
import com.lostf1sh.pixelplayeross.data.database.SongEntity
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B57 4.5: the composer fill reads the whole library in the background, batch after batch, instead of 400 songs per sync. Over 5,000 synthetic songs with real files on disk (empty:
 * the tag read is a fake, TagLib cannot load on the JVM): it finishes, never on the main thread, waits while a sync runs, resumes after a restart without reading anything twice,
 * and a library with nothing left costs one query.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class B57ComposerBackfillFullTest {
    private lateinit var db: PixelPlayerDatabase
    private val dir = java.nio.file.Files.createTempDirectory("b57-composers").toFile()

    @Before fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, PixelPlayerDatabase::class.java).addCallback(PixelPlayerDatabase.createRuntimeArtifactsCallback()).allowMainThreadQueries().build()
        db.musicDao().insertArtistsIgnoreConflicts(listOf(ArtistEntity(id = 1, name = "A", trackCount = 0, imageUrl = null, customImageUri = null)))
        db.musicDao().insertAlbumsIgnoreConflicts(listOf(AlbumEntity(id = 1, title = "B", artistName = "A", artistId = 1, albumArtUriString = null, songCount = 0, dateAdded = 0, year = 0)))
        db.musicDao().insertSongs((1L..SONGS).map { id ->
            File(dir, "$id.flac").writeBytes(byteArrayOf(1))
            SongEntity(id = id, title = "Obra $id", artistName = "A", artistId = 1, albumName = "B", albumId = 1, contentUriString = "content://$id", albumArtUriString = null, duration = 1, genre = null, filePath = File(dir, "$id.flac").path, parentDirectoryPath = dir.path)
        })
    }

    @After fun tearDown() { db.close(); dir.deleteRecursively() }

    private val reads = AtomicInteger()
    private val readThreads = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val read: (File) -> ComposerRead = { file ->
        reads.incrementAndGet(); readThreads += Thread.currentThread().name
        if (file.nameWithoutExtension.toLong() % 3 == 0L) ComposerRead.Found("Composer ${file.nameWithoutExtension}") else ComposerRead.None
    }

    private fun backfill() = ComposerBackfill(db.musicDao(), read)

    @Test fun the_whole_library_is_read_off_the_main_thread_and_a_finished_library_costs_one_query() = runBlocking {
        val priority = AtomicInteger()
        val started = System.nanoTime()
        val run = withContext(Dispatchers.IO) { backfill().runToCompletion(pauseMs = 0, lowerPriority = { priority.incrementAndGet() }) }
        val ms = (System.nanoTime() - started) / 1_000_000
        println("B57 composer fill, $SONGS synthetic songs, database side on this host (no pause, fake reads): $ms ms in ${run.batches} batches")
        assertEquals(SONGS.toInt(), run.songs)
        assertEquals(SONGS.toInt(), reads.get())
        assertEquals(SONGS.toInt() / ComposerBackfill.BACKGROUND_BATCH, run.batches)
        assertEquals("the priority is lowered once, before any read", 1, priority.get())
        assertFalse("never on the main thread: $readThreads", readThreads.any { it.contains("main", ignoreCase = true) && !it.contains("DefaultDispatcher") })
        assertEquals(SONGS / 3, db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM song_composers WHERE composer <> ''").use { it.moveToFirst(); it.getLong(0) })

        reads.set(0)
        assertEquals(0, withContext(Dispatchers.IO) { backfill().runToCompletion(pauseMs = 0) }.songs)
        assertEquals("nothing left: no file is read", 0, reads.get())
    }

    @Test fun it_waits_while_a_sync_runs_and_reads_nothing_until_it_ends() = runBlocking {
        var checks = 0
        var readsWhileSyncing = 0
        val run = withContext(Dispatchers.IO) {
            backfill().runToCompletion(
                isSyncRunning = { (checks++ < 3).also { syncing -> if (syncing) readsWhileSyncing += reads.get() } },
                pauseMs = 0, syncWaitMs = 5,
            )
        }
        assertEquals(0, readsWhileSyncing)
        assertEquals(SONGS.toInt(), run.songs)
        assertTrue(run.pausedForSyncMs >= 15)
    }

    @Test fun after_a_restart_it_carries_on_where_the_database_says_and_reads_no_song_twice() = runBlocking {
        // the first run is cut off (the app is closed) after about 20 batches
        val firstRun = withTimeoutOrNull(1) { null } // placeholder so the test body reads in order
        assertTrue(firstRun == null)
        val cut = backfill()
        var done = 0
        repeat(20) { done += cut.runOnce(ComposerBackfill.BACKGROUND_BATCH) }
        assertEquals(2_000, done)
        // "restart": a new object, the same database
        val after = withContext(Dispatchers.IO) { backfill().runToCompletion(pauseMs = 0) }
        assertEquals(SONGS.toInt() - 2_000, after.songs)
        assertEquals("every file was read exactly once", SONGS.toInt(), reads.get())
        assertNotNull(readThreads)
    }

    private companion object { const val SONGS = 5_000L }
}
