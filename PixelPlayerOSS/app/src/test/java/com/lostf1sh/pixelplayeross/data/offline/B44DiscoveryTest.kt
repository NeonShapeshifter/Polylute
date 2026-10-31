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
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class B44DiscoveryTest {
    private val dir = Files.createTempDirectory("b44-discovery").toFile()
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
    private val songs = (1..70).map { Song.emptySong().copy(id = "$it", libraryItemId = "identity-$it", title = "Song $it", artist = "Rauw Alejandro", album = if (it < 36) "Saturno" else "Cosa Nuestra") }
    private lateinit var downloads: MusicInfoDownloads

    @Before fun setup() {
        mockkObject(WorkManager.Companion)
        every { WorkManager.getInstance(any<Context>()) } returns manager
        val operation = mockk<Operation> { every { result } returns Futures.immediateFuture(Operation.SUCCESS) }
        every { manager.cancelUniqueWork(any()) } returns operation
        every { info.service } returns service
        every { info.deletedDownloads } returns deleted
        coEvery { service.cachedArtistPhoto(any()) } returns null
        coEvery { service.cachedCover(any(), any()) } returns null
        coEvery { service.cachedAbout(any(), any(), any()) } returns null
        coEvery { lyrics.getStoredLyrics(any()) } returns null
        coEvery { music.getAllSongsOnce() } returns songs
        every { music.getSongsForAlbum(any()) } returns flowOf(songs)
        every { music.getSongsByIds(any()) } answers { flowOf(songs.filter { it.id in firstArg<List<String>>() }) }
        downloads = MusicInfoDownloads(context, db, music, lyrics, info)
    }

    @After fun close() {
        db.close()
        unmockkObject(WorkManager.Companion)
        dir.deleteRecursively()
    }

    @Test fun `Rauw discovery has two albums and seventy songs but nothing selected or saved`(): Unit = runBlocking {
        val scope = downloads.open(null)
        val rows = dao.scopeRows(scope)
        assertThat(rows.count { it.kind == InfoKind.COVER.name }).isEqualTo(2)
        assertThat(rows.count { it.kind == InfoKind.DESCRIPTION.name }).isEqualTo(2)
        assertThat(rows.count { it.kind == InfoKind.PHOTO.name }).isEqualTo(1)
        assertThat(rows.count { it.kind == InfoKind.LYRICS.name }).isEqualTo(70)
        assertThat(rows.none { it.selected }).isTrue()
        val photo = rows.single { it.kind == InfoKind.PHOTO.name }
        coEvery { service.discover(InfoKind.PHOTO, any(), any()) } returns ResolvedInfo("resolved-url", 1234)
        downloads.checkOne(photo)
        assertThat(dao.get(photo.key)?.payload).isEqualTo("resolved-url")
        assertThat(dao.get(photo.key)?.sourceBytes).isEqualTo(1234)
        coVerify(exactly = 0) { service.saveDiscovered(any(), any(), any(), any()) }
        assertThat(File(dir, "music-info/index.json").exists()).isFalse()
    }

    @Test fun `failed checks retry on Find while fresh conclusive results are retained`(): Unit = runBlocking {
        val scope = downloads.open(null)
        val photos = dao.scopeRows(scope).first { it.kind == InfoKind.PHOTO.name }
        coEvery { service.discover(any(), any(), any()) } throws IOException("offline")
        downloads.checkOne(photos)
        assertThat(dao.get(photos.key)?.availability).isEqualTo("FAILED")
        dao.find(scope, System.currentTimeMillis() - MusicInfoDownloads.FRESH_MS, 0)
        assertThat(dao.get(photos.key)?.findPending).isTrue()
        coEvery { service.discover(any(), any(), any()) } returns ResolvedInfo("url")
        downloads.checkOne(photos)
        dao.find(scope, System.currentTimeMillis() - MusicInfoDownloads.FRESH_MS, 0)
        assertThat(dao.get(photos.key)?.findPending).isFalse()
        dao.checked(photos.key, "AVAILABLE", "url", null, 1)
        dao.find(scope, System.currentTimeMillis() - MusicInfoDownloads.FRESH_MS, 0)
        assertThat(dao.get(photos.key)?.findPending).isTrue()
    }

    @Test fun `a paused queue stays paused when more selections are appended and survives a new controller`(): Unit = runBlocking {
        val scope = downloads.open(null)
        val rows = dao.scopeRows(scope).take(2)
        for (row in rows) dao.checked(row.key, "AVAILABLE", "resolved", null, System.currentTimeMillis())
        dao.select(rows[0].key, true)
        downloads.appendSelected(scope)
        downloads.pause()
        dao.select(rows[1].key, true)
        downloads.appendSelected(scope)
        val restarted = MusicInfoDownloads(context, db, music, lyrics, info)
        restarted.initialize()
        assertThat(dao.queueOnce()?.paused).isTrue()
        assertThat(dao.downloadProgress().first().total).isEqualTo(2)
        verify(exactly = 1) { manager.enqueueUniqueWork(MusicInfoDownloads.DOWNLOAD_WORK, any(), any<OneTimeWorkRequest>()) }
        restarted.resume()
        assertThat(dao.queueOnce()?.paused).isFalse()
        verify(exactly = 2) { manager.enqueueUniqueWork(MusicInfoDownloads.DOWNLOAD_WORK, any(), any<OneTimeWorkRequest>()) }
    }

    @Test fun `download uses the discovered payload and a failure stays in the one queue with backoff`(): Unit = runBlocking {
        val scope = downloads.open(null)
        val row = dao.scopeRows(scope).first { it.kind == InfoKind.PHOTO.name }
        dao.checked(row.key, "AVAILABLE", "resolved", null, System.currentTimeMillis())
        dao.select(row.key, true)
        downloads.appendSelected(scope)
        coEvery { service.saveDiscovered(InfoKind.PHOTO, any(), any(), "resolved") } returns false
        downloads.downloadOne(dao.get(row.key)!!)
        val failed = dao.get(row.key)!!
        assertThat(failed.downloadState).isEqualTo("RETRY")
        assertThat(failed.retryAt).isAtLeast(System.currentTimeMillis() + 29_000)
        assertThat(failed.availability).isEqualTo("AVAILABLE")
        coEvery { service.saveDiscovered(InfoKind.PHOTO, any(), any(), "resolved") } returns true
        downloads.downloadOne(failed)
        assertThat(dao.get(row.key)?.saved).isTrue()
        coVerify(exactly = 0) { service.discover(any(), any(), any()) }
        assertThat(dao.downloadProgress().first().done).isEqualTo(1)
    }

    @Test fun `lyrics discovery keeps text without saving and skips local lyrics`(): Unit = runBlocking {
        val scope = downloads.open(null)
        val row = dao.scopeRows(scope).first { it.kind == InfoKind.LYRICS.name }
        coEvery { lyrics.discoverLyrics(any()) } returns "[00:01.00] Hola"
        downloads.checkOne(row)
        assertThat(dao.get(row.key)?.payload).isEqualTo("[00:01.00] Hola")
        coVerify(exactly = 0) { lyrics.saveDiscoveredLyrics(any(), any()) }
        coEvery { lyrics.hasLocalLyrics(any()) } returns true
        downloads.checkOne(row)
        assertThat(dao.get(row.key)?.availability).isEqualTo("LOCAL")
        coVerify(exactly = 1) { lyrics.discoverLyrics(any()) }
    }

    @Test fun `deletion marks persist until the corresponding item is explicitly selected`(): Unit = runBlocking {
        val scope = downloads.open(null)
        val row = dao.scopeRows(scope).first { it.kind == InfoKind.PHOTO.name }
        dao.checked(row.key, "AVAILABLE", "resolved", null, System.currentTimeMillis())
        dao.saved(row.key, true)
        dao.select(row.key, true)
        downloads.deleteSelected(scope)
        assertThat(deleted.contains(row.key)).isTrue()
        assertThat(dao.get(row.key)?.saved).isFalse()
        assertThat(dao.get(row.key)?.selected).isFalse()
        dao.select(row.key, true)
        downloads.appendSelected(scope)
        assertThat(deleted.contains(row.key)).isFalse()
    }

    @Test fun `legacy queues are cancelled without reading or changing their files`(): Unit = runBlocking {
        val old = File(dir, "offline-downloads/queue.json").apply { parentFile.mkdirs(); writeText("not even valid JSON") }
        downloads.initialize()
        downloads.initialize()
        assertThat(old.readText()).isEqualTo("not even valid JSON")
        assertThat(dao.nextDownload(Long.MAX_VALUE)).isNull()
        verify(exactly = 1) { manager.cancelUniqueWork("offline-music-downloads") }
    }

    @Test fun `artist splitting and accent insensitive search use library names`() {
        val plan = downloads.plan(listOf(songs.first().copy(artist = "Beyoncé, Rauw Alejandro")))
        assertThat(plan.filter { it.kind == InfoKind.PHOTO.name }.map { it.title }).containsExactly("Beyoncé", "Rauw Alejandro")
        assertThat(infoSearch("BEYONCÉ")).isEqualTo("beyonce")
    }

    @Test fun `a contributing artist scope lists its albums and songs while sharing only one asset row`(): Unit = runBlocking {
        val song = songs.first().copy(artist = "A, B", albumArtist = "A, B")
        coEvery { music.getAllSongsOnce() } returns listOf(song)
        // Changing the mocked source must also represent the real Room write.
        db.musicDao().insertArtistsIgnoreConflicts(listOf(com.lostf1sh.pixelplayeross.data.database.ArtistEntity(999999, "Changed fixture", 0)))
        every { music.getSongsByIds(any()) } returns flowOf(listOf(song))
        val scope = downloads.open(listOf(song.id), artistFilter = "B")
        val page = dao.artists(scope, "b").load(androidx.paging.PagingSource.LoadParams.Refresh(null, 24, false))
            as androidx.paging.PagingSource.LoadResult.Page<Int, InfoArtist>
        assertThat(page.data.map { it.artistName }).containsExactly("B")
        val choices = dao.choices(scope, "b").first()
        assertThat(choices.map { it.kind }).containsAtLeast("PHOTO", "BIO", "COVER", "DESCRIPTION", "LYRICS")
        val all = downloads.open(null)
        val aCover = dao.choices(all, "a").first().single { it.kind == "COVER" }
        val bCover = dao.choices(all, "b").first().single { it.kind == "COVER" }
        assertThat(aCover.key).isEqualTo(bCover.key)
        assertThat(dao.scopeRows(all).count { it.kind == "COVER" }).isEqualTo(1)
    }

    @Test fun `opening a changed library refreshes scope membership without deleting discovery history`(): Unit = runBlocking {
        val scope = downloads.open(null)
        val previousCount = dao.scopeRows(scope).size
        coEvery { music.getAllSongsOnce() } returns songs.take(1)
        // Changing the mocked source must also represent the real Room write.
        db.musicDao().insertArtistsIgnoreConflicts(listOf(com.lostf1sh.pixelplayeross.data.database.ArtistEntity(999999, "Changed fixture", 0)))
        downloads.open(null)
        assertThat(dao.scopeRows(scope)).hasSize(5)
        assertThat(previousCount).isGreaterThan(5)
        assertThat(dao.get(DownloadKeys.lyrics(songs.last()))).isNotNull()
    }

    @Test fun `importing saved state cannot overwrite a download completed during the import`(): Unit = runBlocking {
        val scope = downloads.open(null)
        val row = dao.scopeRows(scope).first()
        dao.checked(row.key, "AVAILABLE", "payload", null, System.currentTimeMillis())
        dao.select(row.key, true)
        dao.appendSelected(scope)
        dao.downloaded(row.key)
        dao.saved(row.key, false, observedState = "QUEUED")
        assertThat(dao.get(row.key)?.saved).isTrue()
    }

    @Test fun `artist paging does not reuse the same lazy key for two spellings of a name`(): Unit = runBlocking {
        val variants = listOf(songs[0].copy(artist = "Beyonce"), songs[1].copy(artist = "Beyoncé"))
        coEvery { music.getAllSongsOnce() } returns variants
        // Changing the mocked source must also represent the real Room write.
        db.musicDao().insertArtistsIgnoreConflicts(listOf(com.lostf1sh.pixelplayeross.data.database.ArtistEntity(999999, "Changed fixture", 0)))
        val scope = downloads.open(null)
        val page = dao.artists(scope, "beyonce").load(androidx.paging.PagingSource.LoadParams.Refresh(null, 24, false))
            as androidx.paging.PagingSource.LoadResult.Page<Int, InfoArtist>
        assertThat(page.data.map { it.artistKey }.distinct()).hasSize(page.data.size)
        assertThat(page.data.map { it.artistName }).containsExactly("Beyonce", "Beyoncé")
    }
}
