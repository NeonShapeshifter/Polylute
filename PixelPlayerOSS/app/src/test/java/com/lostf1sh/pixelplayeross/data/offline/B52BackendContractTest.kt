package com.lostf1sh.pixelplayeross.data.offline

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.*
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.Futures
import com.lostf1sh.pixelplayeross.data.database.*
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.musicinfo.*
import com.lostf1sh.pixelplayeross.data.repository.*
import io.mockk.*
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Uses only APIs present at HEAD, with reflection for newly required actions, so failures are behavioral. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class B52BackendContractTest {
    private val dir = Files.createTempDirectory("b52-backend").toFile()
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val context = object : ContextWrapper(app) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = dir
    }
    private val db = Room.inMemoryDatabaseBuilder(app, PixelPlayerDatabase::class.java).build()
    private val dao get() = db.musicInfoDiscoveryDao()
    private val service = mockk<MusicInfoService>(relaxed = true)
    private val info = mockk<MusicInfoRepository>(relaxed = true)
    private val lyrics = mockk<LyricsRepository>(relaxed = true)
    private val music = mockk<MusicRepository>(relaxed = true)
    private val manager = mockk<WorkManager>(relaxed = true)
    private val deleted = DeletedDownloads(File(dir, "deleted.txt"))
    private val song = Song.emptySong().copy(id = "52", libraryItemId = "identity-52", title = "Song", artist = "Artist", album = "Album")
    private lateinit var downloads: MusicInfoDownloads

    @Before fun setup() {
        mockkObject(WorkManager.Companion)
        every { WorkManager.getInstance(any<Context>()) } returns manager
        val operation = mockk<Operation> { every { result } returns Futures.immediateFuture(Operation.SUCCESS) }
        every { manager.cancelUniqueWork(any()) } returns operation
        every { info.service } returns service
        every { info.deletedDownloads } returns deleted
        coEvery { music.getAllSongsOnce() } returns listOf(song)
        every { music.getSongsForAlbum(any()) } returns flowOf(listOf(song))
        every { music.getSongsByIds(any()) } returns flowOf(listOf(song))
        coEvery { service.discover(any(), any(), any()) } returns ResolvedInfo("payload", 52)
        coEvery { lyrics.discoverLyrics(any()) } returns "lyrics"
        downloads = MusicInfoDownloads(context, db, music, lyrics, info)
    }

    @After fun close() { db.close(); unmockkAll(); dir.deleteRecursively() }

    private suspend fun action(name: String, vararg args: Any): Any? = suspendCoroutineUninterceptedOrReturn { continuation ->
        val method = downloads.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == args.size + 1 }
        org.junit.Assert.assertNotNull("Missing required downloader action $name", method)
        try { method!!.invoke(downloads, *args, continuation) }
        catch (error: InvocationTargetException) { throw error.targetException }
    }
    private fun token(result: Any): String = result.javaClass.getMethod("getToken").invoke(result) as String
    private suspend fun ready(): String {
        val scope = downloads.open(null)
        dao.scopeRows(scope).forEach { dao.checked(it.key, "AVAILABLE", "payload", 52, System.currentTimeMillis()) }
        return scope
    }

    @Test fun `first Find selects available unsaved items once and preserves a later deselection`() = runBlocking {
        val scope = downloads.open(null)
        downloads.find(scope)
        while (true) downloads.checkOne(dao.nextCheck() ?: break)
        val rows = dao.scopeRows(scope)
        assertThat(rows.filter { it.availability == "AVAILABLE" }.all { it.selected }).isTrue()
        dao.select(rows.first().key, false)
        downloads.find(scope)
        assertThat(dao.get(rows.first().key)?.selected).isFalse()
        val restarted = MusicInfoDownloads(context, db, music, lyrics, info)
        restarted.initialize()
        restarted.find(scope)
        assertThat(dao.get(rows.first().key)?.selected).isFalse()
    }

    @Test fun `first Find never chooses an asset the listener deleted`() = runBlocking {
        val scope = downloads.open(null)
        val row = dao.scopeRows(scope).first { it.kind == "PHOTO" }
        dao.deleted(row.key)
        deleted.mark(listOf(row.key))
        downloads.find(scope)
        while (true) downloads.checkOne(dao.nextCheck() ?: break)
        assertThat(dao.scopeRows(scope).filter { it.key != row.key && it.availability == "AVAILABLE" }.all { it.selected }).isTrue()
        assertThat(dao.get(row.key)?.selected).isFalse()
    }

    @Test fun `delete keeps the physical data and durable mark untouched until its Undo window closes`() = runBlocking {
        val scope = ready()
        val row = dao.scopeRows(scope).first { it.kind == "PHOTO" }
        dao.saved(row.key, true)
        dao.select(row.key, true)
        val result: Any = downloads.deleteSelected(scope)
        assertThat(File(dir, "deleted.txt").exists()).isFalse()
        coVerify(exactly = 0) { info.deleteDiscovered(any(), any(), any(), any()) }
        assertThat(dao.get(row.key)?.saved).isFalse()
        assertThat(action("undo", token(result))).isEqualTo(true)
        assertThat(dao.get(row.key)?.saved).isTrue()
        assertThat(deleted.contains(row.key)).isFalse()
    }

    @Test fun `expired delete writes the lasting mark and physically deletes the chosen item`() = runBlocking {
        val scope = ready()
        val row = dao.scopeRows(scope).first { it.kind == "PHOTO" }
        dao.saved(row.key, true)
        dao.select(row.key, true)
        downloads.deleteSelected(scope)
        action("finishExpiredUndo", Long.MAX_VALUE)
        assertThat(File(dir, "deleted.txt").exists()).isTrue()
        assertThat(deleted.contains(row.key)).isTrue()
        coVerify(exactly = 1) { info.deleteDiscovered(InfoKind.PHOTO, row.title, row.artistName, row.songId) }
    }

    @Test fun `Cancel clears the global pending queue and Undo restores it while saved items and Find stay intact`() = runBlocking {
        val scope = ready()
        val rows = dao.scopeRows(scope).take(3)
        rows.forEach { dao.select(it.key, true) }
        downloads.appendSelected(scope)
        dao.downloaded(rows[0].key)
        dao.find(scope, Long.MAX_VALUE, 0)
        downloads.pause()
        val result = action("cancel")!!
        assertThat(result.javaClass.getMethod("getCount").invoke(result)).isEqualTo(2)
        assertThat(dao.get(rows[0].key)?.saved).isTrue()
        assertThat(dao.nextDownload(Long.MAX_VALUE)).isNull()
        assertThat(dao.nextCheck()).isNotNull()
        assertThat(rows.drop(1).all { dao.get(it.key)?.availability == "AVAILABLE" && !deleted.contains(it.key) }).isTrue()
        assertThat(action("undo", token(result))).isEqualTo(true)
        assertThat(rows.drop(1).all { dao.get(it.key)?.downloadState == "QUEUED" }).isTrue()
        assertThat(dao.queueOnce()?.paused).isTrue()
    }

    @Test fun `a running row says downloading and a late response after Cancel and Undo cannot save`() = runBlocking {
        val scope = ready()
        val row = dao.scopeRows(scope).first { it.kind == "PHOTO" }
        dao.select(row.key, true)
        downloads.appendSelected(scope)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var commits = 0
        coEvery { service.saveDiscovered(any(), any(), any(), any()) } coAnswers {
            started.complete(Unit)
            release.await()
            deleted.commitIfAllowed(row.key) { commits++; true } == true
        }
        val job = launch(Dispatchers.Default) { downloads.downloadOne(dao.get(row.key)!!) }
        try {
            withTimeout(10_000) { started.await() }
            assertThat(dao.get(row.key)?.downloadState).isEqualTo("DOWNLOADING")
            val cancelled = action("cancel")!!
            action("undo", token(cancelled))
            release.complete(Unit)
            job.join()
            assertThat(commits).isEqualTo(0)
            assertThat(dao.get(row.key)?.saved).isFalse()
            assertThat(dao.get(row.key)?.downloadState).isEqualTo("QUEUED")
        } finally { release.complete(Unit); job.cancelAndJoin() }
    }

    @Test fun `Done acknowledges the completed global queue without deleting saved assets`() = runBlocking {
        val scope = ready()
        val row = dao.scopeRows(scope).first()
        dao.select(row.key, true)
        downloads.appendSelected(scope)
        dao.downloaded(row.key)
        action("done")
        assertThat(dao.downloadProgress().first().total).isEqualTo(0)
        assertThat(dao.get(row.key)?.saved).isTrue()
    }

    @Test fun `an expired physical delete failure persists a visible error and Retry completes it`() = runBlocking {
        val scope = ready()
        val row = dao.scopeRows(scope).first { it.kind == "PHOTO" }
        dao.saved(row.key, true); dao.select(row.key, true)
        coEvery { info.deleteDiscovered(any(), any(), any(), any()) } throws java.io.IOException("read only cache")
        val pending: Any = downloads.deleteSelected(scope)
        var failed = false
        try { action("finishExpiredUndo", Long.MAX_VALUE) } catch (_: java.io.IOException) { failed = true }
        assertThat(failed).isTrue()
        val method = dao.javaClass.methods.single { it.name == "undoActions" }
        @Suppress("UNCHECKED_CAST")
        val actions = (method.invoke(dao) as Flow<List<Any>>).first()
        assertThat(actions.single().javaClass.getMethod("getFailed").invoke(actions.single())).isEqualTo(true)
        coEvery { info.deleteDiscovered(any(), any(), any(), any()) } just Runs
        action("retryDelete", token(pending), Long.MAX_VALUE)
        assertThat((method.invoke(dao) as Flow<*>).first() as List<*>).isEmpty()
    }

    @Test fun `page Refresh reports a swallowed lookup failure instead of a false success`() = runBlocking {
        ready()
        coEvery { info.refreshArtistInfo(any()) } coAnswers {
            com.lostf1sh.pixelplayeross.data.network.noteLookupFailure()
            null
        }
        var failed = false
        try { OfflineDownloads(info, lyrics, downloads).refreshArtistInfo("Artist", listOf(song)) }
        catch (_: java.io.IOException) { failed = true }
        assertThat(failed).isTrue()
    }

    private suspend fun seedSong() {
        db.musicDao().insertArtistsIgnoreConflicts(listOf(ArtistEntity(52, "Artist", 1)))
        db.musicDao().insertAlbumsIgnoreConflicts(listOf(AlbumEntity(52, "Album", "Artist", 52, null, 1, 0, 0)))
        db.musicDao().insertSongs(listOf(song.copy(artistId = 52, albumId = 52).toEntity("/music/Song.flac", "/music").copy(libraryItemId = song.libraryItemId)))
        db.musicDao().seedLibraryItemsFromProjections()
    }

    @Test fun `shared lyrics persistence binds a fallback to the existing library item`() = runBlocking {
        seedSong()
        db.lyricsDao().insert(LyricsEntity(52, "downloaded fallback", source = "remote"))
        assertThat(db.lyricsDao().getLyricsForLibraryItem("identity-52")?.content).isEqualTo("downloaded fallback")
        assertThat(db.musicDao().getAllLibraryItems().map { it.libraryItemId }).containsExactly("identity-52")
        Unit
    }

    @Test fun `Delete all includes old unnamed cache entries and remote lyrics with Undo while embedded lyrics survive`() = runBlocking {
        seedSong()
        db.lyricsDao().insert(LyricsEntity(52, "downloaded words", source = "remote", libraryItemId = "identity-52"))
        db.lyricsDao().insert(LyricsEntity(54, "embedded words", source = "embedded", libraryItemId = "identity-54"))
        var requests = 0
        val http = okhttp3.OkHttpClient.Builder().addInterceptor { requests++; throw java.io.IOException("No HTTP in this test") }.build()
        val scopeJob = SupervisorJob()
        val realInfo = MusicInfoRepository(context, mockk(relaxed = true), http, db.musicDao(), CoroutineScope(scopeJob + Dispatchers.Default))
        try {
            realInfo.cache.putFile(MusicInfoCache.Kind.ARTIST_PHOTO, "orphanartist", byteArrayOf(1, 2, 3), "img", null)
            downloads = MusicInfoDownloads(context, db, music, lyrics, realInfo)
            coEvery { lyrics.deleteDownloadedLyrics(any()) } coAnswers {
                db.lyricsDao().deleteLyricsForLibraryItem(firstArg<Song>().libraryItemId)
            }
            val pending = action("deleteAllDownloaded")!!
            assertThat(pending.javaClass.getMethod("getCount").invoke(pending)).isEqualTo(2)
            assertThat(realInfo.service.cachedArtistPhoto("Orphan Artist")).isNull()
            assertThat(db.lyricsDao().getLyricsForLibraryItem("identity-52")).isNotNull()
            action("undo", token(pending))
            assertThat(realInfo.service.cachedArtistPhoto("Orphan Artist")).isNotNull()
            action("deleteAllDownloaded")
            action("finishExpiredUndo", Long.MAX_VALUE)
            assertThat(realInfo.cache.savedEntries()).isEmpty()
            assertThat(db.lyricsDao().getLyricsForLibraryItem("identity-52")).isNull()
            assertThat(db.lyricsDao().getLyrics(54)?.content).isEqualTo("embedded words")
            assertThat(realInfo.deletedDownloads.contains(DownloadKeys.photo("Orphan Artist"))).isTrue()
            realInfo.deletedDownloads.allow(listOf(DownloadKeys.photo("Orphan Artist")))
            assertThat(realInfo.deletedDownloads.contains(DownloadKeys.photo("Orphan Artist"))).isFalse()
            assertThat(requests).isEqualTo(0)
        } finally { scopeJob.cancel() }
    }
}
