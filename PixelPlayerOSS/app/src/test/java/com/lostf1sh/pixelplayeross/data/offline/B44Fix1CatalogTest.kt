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
class B44Fix1CatalogTest {
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
    private val db = Room.inMemoryDatabaseBuilder(app, PixelPlayerDatabase::class.java)
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
        Song.emptySong().copy(id = "${index + 1}", libraryItemId = "item-$index", title = "Song $index",
            artist = "Artist ${album % 660}", albumArtist = "Artist ${album % 660}", album = "Album $album", albumId = album.toLong())
    }
    private fun controller() = MusicInfoDownloads(context, db, music, lyrics, info)

    @Before fun setUp(): Unit = runBlocking {
        mockkObject(WorkManager.Companion)
        every { WorkManager.getInstance(any<Context>()) } returns manager
        val operation = mockk<Operation> { every { result } returns Futures.immediateFuture(Operation.SUCCESS) }
        every { manager.cancelUniqueWork(any()) } returns operation
        every { info.service } returns service
        every { info.deletedDownloads } returns cache.deletedDownloads
        coEvery { music.getAllSongsOnce() } answers { songs }
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
        unmockkObject(WorkManager.Companion)
        folder.deleteRecursively()
    }

    @Test fun `HONOR sized catalog opens immediately and refreshes in bounded bulk transactions`(): Unit = runBlocking {
        val downloads = controller()
        transactions.set(0)
        val initialStart = System.nanoTime()
        downloads.open(null)
        val initialMs = (System.nanoTime() - initialStart) / 1_000_000.0
        val initialTransactions = transactions.get()
        songs = songs.mapIndexed { index, song -> if (index == 0) song.copy(title = "Changed title") else song }
        db.musicDao().insertArtistsIgnoreConflicts(listOf(ArtistEntity(999999, "Metadata edit", 0)))
        transactions.set(0)
        val start = System.nanoTime()
        val refresh = async(Dispatchers.Default) { downloads.open(null) }
        // The old API returned the scope only after importing every saved item.
        val immediateScope = downloads.javaClass.methods.firstOrNull { it.name == "scopeKey" }
        val scope = immediateScope?.invoke(downloads, null, null) as? String ?: refresh.await()
        val page = db.musicInfoDiscoveryDao().artists(scope, "").load(PagingSource.LoadParams.Refresh(null, 24, false))
        val firstPageMs = (System.nanoTime() - start) / 1_000_000.0
        refresh.await()
        val fullMs = (System.nanoTime() - start) / 1_000_000.0
        val fullTransactions = transactions.get()

        val saved = db.musicInfoDiscoveryDao().scopeRows(scope).count { it.saved }
        transactions.set(0)
        val repeatStart = System.nanoTime()
        controller().open(null)
        val noChangeMs = (System.nanoTime() - repeatStart) / 1_000_000.0
        val repeatTransactions = transactions.get()
        println("B44-fix1 HOST catalog 4800 songs 660 artists 1100 albums firstPageMs=$firstPageMs fullRefreshMs=$fullMs noChangeOpenMs=$noChangeMs fullTransactions=$fullTransactions noChangeTransactions=$repeatTransactions saved=$saved initialRefreshMs=$initialMs initialTransactions=$initialTransactions")
        assertThat(page).isInstanceOf(PagingSource.LoadResult.Page::class.java)
        assertThat((page as PagingSource.LoadResult.Page).data).hasSize(24)
        assertThat(saved).isEqualTo(240)
        assertThat(firstPageMs).isLessThan(1000.0)
        assertThat(fullMs).isLessThan(5000.0)
        assertThat(initialMs).isLessThan(5000.0)
        assertThat(db.musicInfoDiscoveryDao().scopeRows(scope).first { it.songId == "1" && it.kind == "LYRICS" }.title).isEqualTo("Changed title")
        coVerify(exactly = 0) { lyrics.getStoredLyrics(any()) }
        assertThat(noChangeMs).isLessThan(300.0)
        assertThat(fullTransactions).isAtMost(12)

        assertThat(repeatTransactions).isEqualTo(0)
    }
    @Test fun `a catalog stamp never hides membership lost during database recovery`(): Unit = runBlocking {
        val downloads = controller()
        val scope = downloads.open(null)
        db.musicInfoDiscoveryDao().clearScopeAssets(scope)
        db.musicInfoDiscoveryDao().clearScopeArtists(scope)
        downloads.open(null)
        assertThat(db.musicInfoDiscoveryDao().scopeRows(scope)).isNotEmpty()
    }

}
