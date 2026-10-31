package com.lostf1sh.pixelplayeross.data.offline

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.database.MusicDao
import com.lostf1sh.pixelplayeross.data.model.Lyrics
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.musicinfo.MusicInfoRepository
import com.lostf1sh.pixelplayeross.data.musicinfo.MusicInfoService
import com.lostf1sh.pixelplayeross.data.repository.LyricsRepository
import com.lostf1sh.pixelplayeross.data.repository.MusicRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import com.lostf1sh.pixelplayeross.utils.AlbumArtUtils
import com.lostf1sh.pixelplayeross.utils.LocalArtworkUri
import java.io.IOException
import java.nio.file.Files
import java.time.Duration
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class OfflineDownloadWorkerTest {
    private val dir = Files.createTempDirectory("offline-worker").toFile()
    private val store = OfflineBatchStore(dir)
    private var continued = false
    private val songs = (1..26).map { index -> mockk<Song>(relaxed = true) {
        every { id } returns "$index"
        every { title } returns "Song $index"
        every { artist } returns "Artist"
        every { artists } returns emptyList()
        every { album } returns "Album"
        every { albumArtist } returns null
        every { albumArtUriString } returns null
    } }.associateBy { it.id }
    private val lyrics = mockk<LyricsRepository>(relaxed = true).also { coEvery { it.prepareForOffline(any()) } returns mockk<Lyrics>() }
    private val service = mockk<MusicInfoService>(relaxed = true)
    private val downloads = mockk<LegacyOfflineDownloads>(relaxed = true).also { d ->
        every { d.load("job") } answers { store.read()?.takeIf { it.id == "job" && it.active } }
        every { d.update("job", any()) } answers { store.update("job", secondArg()); }
        every { d.record("job", any(), any()) } answers { store.result("job", secondArg(), thirdArg()) != null }
        coEvery { d.continueLater("job") } coAnswers { continued = true }
    }
    private fun plan(selected: List<Song> = songs.values.toList(), kinds: Set<OfflineKind> = setOf(OfflineKind.LYRICS)) {
        store.start(OfflineBatch("job", selected.map { it.id }, kinds, targets = OfflinePlan.build(selected, kinds)), true)
    }
    @After fun cleanup() { unmockkObject(AlbumArtUtils); dir.deleteRecursively() }
    private fun worker(): LegacyOfflineDownloadWorker {
        val params = mockk<WorkerParameters>(relaxed = true) {
            every { inputData } returns workDataOf("batch" to "job")
            every { id } returns UUID.randomUUID()
        }
        val music = mockk<MusicRepository> {
            every { getSongsByIds(any()) } answers { flowOf(firstArg<List<String>>().mapNotNull { songs[it] }) }
        }
        val info = mockk<MusicInfoRepository> { every { this@mockk.service } returns this@OfflineDownloadWorkerTest.service }
        return LegacyOfflineDownloadWorker(ApplicationProvider.getApplicationContext<Context>(), params, downloads, music, lyrics, info, mockk<MusicDao>(relaxed = true))
    }
    @Test fun `a large selection is sliced and keeps completed targets for the next worker`() = runBlocking {
        plan()
        worker().doWork()
        assertThat(store.read()!!.next).isEqualTo(25)
        assertThat(continued).isTrue()
        worker().doWork()
        assertThat(store.read()!!.next).isEqualTo(26)
        assertThat(store.read()!!.status).isEqualTo(OfflineStatus.COMPLETE)
    }
    @Test fun `one failed source is reported and does not lose the following track`() = runBlocking {
        plan(songs.values.take(2))
        coEvery { lyrics.prepareForOffline(songs.getValue("1")) } throws IOException("offline")
        worker().doWork()
        assertThat(store.read()!!.status).isEqualTo(OfflineStatus.RUNNING)
        assertThat(store.read()!!.next).isEqualTo(1)
        assertThat(store.read()!!.failed).isEqualTo(1)
        worker().doWork()
        coVerify(exactly = 1) { lyrics.prepareForOffline(songs.getValue("1")) }
        coEvery { lyrics.prepareForOffline(songs.getValue("1")) } returns mockk<Lyrics>()
        store.update("job") { b -> b.copy(retries = b.retries.mapValues { it.value.copy(readyAtMs = 0) }) }
        worker().doWork()
        assertThat(store.read()!!.status).isEqualTo(OfflineStatus.COMPLETE)
    }
    @Test fun `cancellation starts no later request and commits no unfinished asset`() = runBlocking {
        plan()
        coEvery { lyrics.prepareForOffline(any()) } throws CancellationException()
        try { worker().doWork(); fail("Cancellation was swallowed") } catch (expected: CancellationException) { }
        assertThat(store.results()).isEmpty()
        assertThat(continued).isFalse()
        coVerify(exactly = 0) { service.fetchArtistPhoto(any(), any()) }
    }
    @Test fun `time budget retains a completed asset and yields before the next target`() = runBlocking {
        plan(songs.values.take(2))
        coEvery { lyrics.prepareForOffline(any()) } coAnswers {
            ShadowSystemClock.advanceBy(Duration.ofMinutes(5)); mockk<Lyrics>()
        }
        worker().doWork()
        assertThat(continued).isTrue()
        assertThat(store.read()!!.next).isEqualTo(1)
        assertThat(store.results()).hasSize(1)
    }
    @Test fun `two chosen kinds run concurrently and the artist is fetched only once`() = runBlocking {
        plan(songs.values.take(2), setOf(OfflineKind.LYRICS, OfflineKind.ARTIST_PHOTOS))
        val lyricsStarted = CompletableDeferred<Unit>()
        val photoStarted = CompletableDeferred<Unit>()
        coEvery { lyrics.prepareForOffline(any()) } coAnswers {
            lyricsStarted.complete(Unit)
            withTimeout(5000) { photoStarted.await() }
            mockk<Lyrics>()
        }
        coEvery { service.fetchArtistPhoto("Artist", true) } coAnswers {
            photoStarted.complete(Unit)
            withTimeout(5000) { lyricsStarted.await() }
            null
        }
        withTimeout(10000) { worker().doWork() }
        assertThat(store.read()!!.status).isEqualTo(OfflineStatus.COMPLETE)
        assertThat(store.read()!!.next).isEqualTo(3)
        assertThat(store.read()!!.available).isEqualTo(2)
        assertThat(store.results().single { it.unavailable == 1 }.title).isEqualTo("Artist")
        coVerify(exactly = 1) { service.fetchArtistPhoto("Artist", true) }
    }
    @Test fun `a lazy artwork locator without an image still downloads the missing cover`() = runBlocking {
        val song = songs.getValue("1")
        every { song.albumArtUriString } returns LocalArtworkUri.buildSongUri(1L)
        mockkObject(AlbumArtUtils)
        every { AlbumArtUtils.ensureAlbumArtCachedFile(any(), 1L, any(), any()) } returns null
        coEvery { service.fetchCover("Album", "Artist", true) } returns null
        plan(listOf(song), setOf(OfflineKind.COVERS))
        worker().doWork()
        coVerify(exactly = 1) { service.fetchCover("Album", "Artist", true) }
        assertThat(store.read()!!.available).isEqualTo(0)
        assertThat(store.read()!!.unavailable).isEqualTo(1)
    }
    @Test fun `an extracted local cover is reused without an online cover request`() = runBlocking {
        val song = songs.getValue("1")
        every { song.albumArtUriString } returns LocalArtworkUri.buildSongUri(1L)
        val cover = java.io.File(dir, "existing.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        mockkObject(AlbumArtUtils)
        every { AlbumArtUtils.ensureAlbumArtCachedFile(any(), 1L, any(), any()) } returns cover
        plan(listOf(song), setOf(OfflineKind.COVERS))
        worker().doWork()
        coVerify(exactly = 0) { service.fetchCover(any(), any(), any()) }
        assertThat(store.read()!!.available).isEqualTo(1)
    }
    @Test fun `pausing while a request is in flight prevents further requests and late progress`() = runBlocking {
        plan(songs.values.take(2))
        coEvery { lyrics.prepareForOffline(any()) } coAnswers {
            store.update("job") { it.copy(status = OfflineStatus.PAUSED) }
            mockk<Lyrics>()
        }
        worker().doWork()
        assertThat(store.read()!!.next).isEqualTo(0)
        coVerify(exactly = 1) { lyrics.prepareForOffline(any()) }
        assertThat(continued).isFalse()
    }
}
