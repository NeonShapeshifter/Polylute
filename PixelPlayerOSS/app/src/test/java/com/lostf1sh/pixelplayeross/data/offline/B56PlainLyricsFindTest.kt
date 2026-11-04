package com.lostf1sh.pixelplayeross.data.offline

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Operation
import androidx.work.WorkManager
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.Futures
import com.lostf1sh.pixelplayeross.data.database.PixelPlayerDatabase
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.musicinfo.DeletedDownloads
import com.lostf1sh.pixelplayeross.data.musicinfo.MusicInfoRepository
import com.lostf1sh.pixelplayeross.data.musicinfo.MusicInfoService
import com.lostf1sh.pixelplayeross.data.repository.LocalLyricsTiming
import com.lostf1sh.pixelplayeross.data.repository.LyricsRepository
import com.lostf1sh.pixelplayeross.data.repository.MusicRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/**
 * B56 phase 3.1 (#43): lyrics already on the phone with no timing are no longer "local". The Lyrics type looks LRCLIB up for a timed version of them, and counts the ones
 * it cannot improve on a line of their own.
 *
 * The synthetic library: songs 1-5 have timed lyrics, 6-8 plain lyrics, 9-12 none. LRCLIB has a timed version of 6, 7, 9 and 10, only a plain one of 8, and nothing of 11 and 12.
 * Before this batch every local lyric counted as "local" (8 of 12) and nothing was looked up for them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class B56PlainLyricsFindTest {
    private val dir = Files.createTempDirectory("b56-plain-lyrics").toFile()
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
    private val songs = (1..12).map { Song.emptySong().copy(id = "$it", libraryItemId = "identity-$it", title = "Song $it", artist = "Mägo de Oz", album = "Gaia") }
    private val onPhone = mapOf(
        1 to LocalLyricsTiming.SYNCED, 2 to LocalLyricsTiming.SYNCED, 3 to LocalLyricsTiming.SYNCED, 4 to LocalLyricsTiming.SYNCED, 5 to LocalLyricsTiming.SYNCED,
        6 to LocalLyricsTiming.PLAIN, 7 to LocalLyricsTiming.PLAIN, 8 to LocalLyricsTiming.PLAIN,
        9 to LocalLyricsTiming.NONE, 10 to LocalLyricsTiming.NONE, 11 to LocalLyricsTiming.NONE, 12 to LocalLyricsTiming.NONE,
    )
    private val timed = "[00:01.00] Hola\n[00:04.50] Adiós"
    private val plain = "Hola\nAdiós"
    private val atLrclib = mapOf(6 to timed, 7 to timed, 8 to plain, 9 to timed, 10 to timed)
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
        coEvery { lyrics.localLyricsTiming(any()) } answers { onPhone.getValue(firstArg<Song>().id.toInt()) }
        coEvery { lyrics.hasLocalLyrics(any()) } answers { onPhone.getValue(firstArg<Song>().id.toInt()) != LocalLyricsTiming.NONE }
        coEvery { lyrics.discoverLyrics(any()) } answers { atLrclib[firstArg<Song>().id.toInt()] }
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

    private suspend fun findAll(): Map<Int, String> {
        val scope = downloads.open(null)
        val rows = dao.scopeRows(scope).filter { it.kind == InfoKind.LYRICS.name }
        rows.forEach { downloads.checkOne(it) }
        return rows.associate { it.songId.toInt() to dao.get(it.key)!!.availability }
    }

    @Test fun `timed lyrics on the phone are local, plain ones are looked up and only the ones LRCLIB cannot improve stay plain`(): Unit = runBlocking {
        val found = findAll()

        assertThat((1..5).map { found.getValue(it) }.toSet()).containsExactly("LOCAL")
        assertThat(found.getValue(6)).isEqualTo("AVAILABLE")                       // plain on the phone, timed at LRCLIB: an upgrade
        assertThat(found.getValue(7)).isEqualTo("AVAILABLE")
        assertThat(found.getValue(8)).isEqualTo(MusicInfoDownloads.LOCAL_PLAIN)    // LRCLIB only has a plain version too: nothing to gain
        assertThat(found.getValue(9)).isEqualTo("AVAILABLE")                       // nothing on the phone, timed at LRCLIB
        assertThat(found.getValue(10)).isEqualTo("AVAILABLE")
        assertThat(found.getValue(11)).isEqualTo("MISSING")
        assertThat(found.getValue(12)).isEqualTo("MISSING")
    }

    @Test fun `the type counts the lyrics with no timing on their own line`(): Unit = runBlocking {
        findAll()

        val counts = infoTypeCounts(dao.scopeRows(downloads.open(null))).single { it.kind == InfoKind.LYRICS }

        assertThat(counts.total).isEqualTo(12)
        assertThat(counts.local).isEqualTo(5)
        assertThat(counts.localPlain).isEqualTo(1)
        assertThat(counts.available).isEqualTo(4)
        assertThat(counts.missing).isEqualTo(2)
        println("B56 3.1 synthetic library, after: timed on phone=${counts.local} plain on phone without a better version=${counts.localPlain} upgrades+new available=${counts.available} missing=${counts.missing}")
    }

    @Test fun `a plain lyric on the phone is never offered for download when LRCLIB has no timed one`(): Unit = runBlocking {
        findAll()

        val choice = dao.scopeRows(downloads.open(null)).single { it.songId == "8" && it.kind == InfoKind.LYRICS.name }

        assertThat(choice.availability).isEqualTo(MusicInfoDownloads.LOCAL_PLAIN)
        assertThat(choice.payload).isNull()
        assertThat(infoTypeCounts(listOf(choice)).single { it.kind == InfoKind.LYRICS }.group.eligible).isEqualTo(0)
    }

    @Test fun `a screen model fed these rows keeps the no timing count`(): Unit = runBlocking {
        findAll()
        val rows = dao.scopeRows(downloads.open(null))

        val model = InfoScreenModel(rows, emptyList())

        assertThat(model.types().single { it.kind == InfoKind.LYRICS }.localPlain).isEqualTo(1)
    }
}
