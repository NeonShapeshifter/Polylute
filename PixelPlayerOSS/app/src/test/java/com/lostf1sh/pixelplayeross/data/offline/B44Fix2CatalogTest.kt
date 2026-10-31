package com.lostf1sh.pixelplayeross.data.offline

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.*
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.Futures
import com.lostf1sh.pixelplayeross.data.database.*
import com.lostf1sh.pixelplayeross.data.model.Lyrics
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.musicinfo.*
import com.lostf1sh.pixelplayeross.data.repository.*
import io.mockk.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class B44Fix2CatalogTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val folder = Files.createTempDirectory("b44-fix1-catalog").toFile()
    private val context = object : ContextWrapper(app) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = folder
    }
    private val transactions = AtomicInteger()
    private fun counted(database: androidx.sqlite.db.SupportSQLiteDatabase) =
        object : androidx.sqlite.db.SupportSQLiteDatabase by database {
            override fun beginTransaction() {
                if (!database.inTransaction()) transactions.incrementAndGet()
                database.beginTransaction()
            }
            override fun beginTransactionNonExclusive() {
                if (!database.inTransaction()) transactions.incrementAndGet()
                database.beginTransactionNonExclusive()
            }
        }
    private val databaseName = "${folder.name}.db"
    private val db = Room.databaseBuilder(app, PixelPlayerDatabase::class.java, databaseName)
        .openHelperFactory { configuration ->
            val helper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(configuration)
            object : androidx.sqlite.db.SupportSQLiteOpenHelper by helper {
                override val writableDatabase get() = counted(helper.writableDatabase)
                override val readableDatabase get() = counted(helper.readableDatabase)
            }
        }.build()
    private val music = mockk<MusicRepository>(relaxed = true)
    private val lyrics = mockk<LyricsRepository>(relaxed = true)
    private val info = mockk<MusicInfoRepository>(relaxed = true)
    private val manager = mockk<WorkManager>(relaxed = true)
    private val cache = MusicInfoCache(File(folder, "music-info"), { 0L })
    private val http = MusicInfoHttp(OkHttpClient(), minIntervalMs = 0, nowMs = { 0 }, sleep = {})
    private val service = MusicInfoService(cache, MusicBrainzClient(http), CoverArtArchiveClient(http),
        WikidataClient(http), CommonsClient(http), WikipediaClient(http), { false }, { false }, { false }, { listOf("en") })
    private var songs = (0 until 4800).map { index ->
        val album = index % 1100
        Song.emptySong().copy(id = "${index + 1}", libraryItemId = "item-$index", artistId = 0, title = "Song $index",
            artist = "Artist ${index % 1570}", albumArtist = "Artist ${index % 1570}", album = "Album $album", albumId = album.toLong())
    }
    private fun controller() = MusicInfoDownloads(context, db, music, lyrics, info)

    @Before fun setUp(): Unit = runBlocking {
        mockkObject(WorkManager.Companion)
        every { WorkManager.getInstance(any<Context>()) } returns manager
        val operation = mockk<Operation> { every { result } returns Futures.immediateFuture(Operation.SUCCESS) }
        every { manager.cancelUniqueWork(any()) } returns operation
        every { info.service } returns service
        every { info.deletedDownloads } returns cache.deletedDownloads
        db.musicDao().insertArtistsIgnoreConflicts(listOf(ArtistEntity(0, "Fixture", 4800)))
        db.musicDao().insertAlbumsIgnoreConflicts(songs.distinctBy { it.albumId }.map {
            AlbumEntity(it.albumId, it.album, it.artist, 0, null, 5, 0, 2026)
        })
        db.musicDao().insertSongsIgnoreConflicts(songs.map {
            it.toEntity("/storage/emulated/0/Music/${it.id}.flac", "/storage/emulated/0/Music")
        })
        coEvery { music.getAllSongsOnce() } coAnswers {
            db.musicDao().getAllSongs(emptyList(), false).first().map { it.toSong() }
        }
        every { music.getSongsByIds(any()) } answers { flowOf(songs.filter { it.id in firstArg<List<String>>() }) }
        coEvery { lyrics.getStoredLyrics(any()) } coAnswers {
            val song = firstArg<Song>()
            db.lyricsDao().getLyricsForLibraryItem(song.libraryItemId)?.let {
                Lyrics(plain = listOf(it.content), areFromRemote = true) to it.content
            }
        }
        val png = ByteArrayOutputStream().also { stream ->
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply {
                eraseColor(android.graphics.Color.BLUE)
                compress(Bitmap.CompressFormat.PNG, 100, stream)
                recycle()
            }
        }.toByteArray()
        repeat(40) { index ->
            cache.putFile(MusicInfoCache.Kind.ARTIST_PHOTO, MusicInfoService.photoKey("Artist $index"), png, "png", "{}")
            cache.putFile(MusicInfoCache.Kind.COVER, MusicInfoService.coverKey("Album $index", "Artist ${index % 660}"), png, "png", null)
            cache.putText(MusicInfoCache.Kind.ABOUT, MusicInfoService.aboutKey(AboutKind.ARTIST, "Artist $index", null, "en"),
                """{"text":"Biography","pageUrl":"https://en.wikipedia.org/wiki/Artist","language":"en"}""")
        }
        db.lyricsDao().insertAll(songs.take(120).map { LyricsEntity(it.id.toLong(), "Saved lyrics", source = "remote", libraryItemId = it.libraryItemId) })
    }

    @After fun tearDown() {
        db.close()
        app.deleteDatabase(databaseName)
        unmockkObject(WorkManager.Companion)
        folder.deleteRecursively()
    }

    @Test fun `an unchanged warm catalog skips song reads and saved snapshots`(): Unit = runBlocking {
        val downloads = controller()
        downloads.open(null)
        clearMocks(music, answers = false)
        val start = System.nanoTime()
        downloads.open(null)
        val elapsed = (System.nanoTime() - start) / 1_000_000.0
        println("B44-fix2 HOST no-change 4800 songs 1570 artists 1100 albums totalMs=$elapsed steps=${downloads.lastOpenSteps}")
        coVerify(exactly = 0) { music.getAllSongsOnce() }
        assertThat(downloads.lastOpenSteps.keys).doesNotContain("savedInfoSnapshot")
    }

    @Test fun `same count metadata edits still refresh a warm catalog`(): Unit = runBlocking {
        val downloads = controller()
        val scope = downloads.open(null)
        songs = songs.mapIndexed { index, song -> if (index == 0) song.copy(title = "Edited without changing count") else song }
        // This fixture mocks song reads. A real metadata edit also invalidates Room.
        db.musicDao().updateSongs(listOf(songs.first().toEntity("/storage/emulated/0/Music/1.flac", "/storage/emulated/0/Music")))
        downloads.open(null)
        assertThat(db.musicInfoDiscoveryDao().scopeRows(scope).first { it.kind == "LYRICS" && it.songId == "1" }.title)
            .isEqualTo("Edited without changing count")
    }
}
