package com.lostf1sh.pixelplayeross.data.offline

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.database.LyricsDao
import com.lostf1sh.pixelplayeross.data.database.LyricsEntity
import com.lostf1sh.pixelplayeross.data.database.MusicDao
import com.lostf1sh.pixelplayeross.data.model.ArtistRef
import com.lostf1sh.pixelplayeross.data.model.LyricsSourcePreference
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.musicinfo.AboutKind
import com.lostf1sh.pixelplayeross.data.musicinfo.MusicBrainzClient
import com.lostf1sh.pixelplayeross.data.musicinfo.MusicInfoCache
import com.lostf1sh.pixelplayeross.data.musicinfo.MusicInfoRepository
import com.lostf1sh.pixelplayeross.data.musicinfo.MusicInfoService
import com.lostf1sh.pixelplayeross.data.network.lyrics.LrcLibApiService
import com.lostf1sh.pixelplayeross.data.network.lyrics.LrcLibResponse
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.repository.LyricsRepositoryImpl
import com.lostf1sh.pixelplayeross.data.repository.MusicRepository
import com.lostf1sh.pixelplayeross.data.stats.LedgerProvider
import io.mockk.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import com.lostf1sh.pixelplayeross.data.repository.MusicRepositoryImpl
import com.lostf1sh.pixelplayeross.presentation.viewmodel.LyricsStateHolder
import com.lostf1sh.pixelplayeross.presentation.viewmodel.LyricsSearchUiState
import com.lostf1sh.pixelplayeross.di.DispatcherProvider
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Uses only the B43 public APIs so exactly these tests run against the untouched B43 snapshot. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class B43Fix1DeletedDownloadsTest {
    private val root = Files.createTempDirectory("b43-fix1-deletions").toFile()
    private val scopes = mutableListOf<CoroutineScope>()
    private val requests = CopyOnWriteArrayList<String>()
    private val lyricRequests = AtomicInteger()
    private var photoGate: Pair<CompletableDeferred<Unit>, CompletableDeferred<Unit>>? = null
    private var lyricsGate: Pair<CompletableDeferred<Unit>, CompletableDeferred<Unit>>? = null
    private val rows = ConcurrentHashMap<Long, LyricsEntity>()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private fun context(dir: File = root) = object : ContextWrapper(app) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = dir
    }
    private val first = song("1", "item-one", "A")
    private val second = song("2", "item-two", "B")
    private fun song(id: String, identity: String, artist: String) = Song.emptySong().copy(
        id = id, libraryItemId = identity, title = "Song $id", artist = artist, artistId = 1,
        artists = listOf(ArtistRef(1, artist.substringBefore(','), true)), album = "Album", albumId = 1,
        duration = 180_000, path = "", contentUriString = "", albumArtUriString = null,
    )
    private val png by lazy {
        ByteArrayOutputStream().also { out ->
            Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply {
                eraseColor(android.graphics.Color.BLUE)
                compress(Bitmap.CompressFormat.PNG, 100, out)
                recycle()
            }
        }.toByteArray()
    }
    private val client = OkHttpClient.Builder().addInterceptor { chain ->
        val url = chain.request().url
        requests += url.toString()
        if (url.host == "upload.wikimedia.org") {
            photoGate?.let { (started, release) ->
                started.complete(Unit)
                runBlocking { release.await() }
            }
        }
        val query = url.queryParameter("query").orEmpty()
        val artist = query.substringAfter("artist:\"", "A").substringBefore('"')
        val title = query.substringAfter('"').substringBefore('"').ifBlank { "Album" }
        val body = when {
            url.host == "upload.wikimedia.org" || url.host == "coverartarchive.org" -> png
            url.host == "commons.wikimedia.org" -> """{"query":{"pages":{"1":{"imageinfo":[{"thumburl":"https://upload.wikimedia.org/photo.png","descriptionurl":"https://commons.wikimedia.org/wiki/File:Photo.png"}]}}}}""".toByteArray()
            url.encodedPath == "/ws/2/artist" -> """{"artists":[{"id":"artist-$artist","name":"$artist","score":100}]}""".toByteArray()
            url.encodedPath == "/ws/2/release" -> """{"releases":[{"id":"release","title":"$title","score":100}]}""".toByteArray()
            url.encodedPath == "/ws/2/release-group" -> """{"release-groups":[{"id":"group","title":"$title","score":100,"artist-credit":[{"artist":{"name":"$artist"}}]}]}""".toByteArray()
            url.host == "musicbrainz.org" -> """{"annotation":"Downloaded description","relations":[{"type":"image","url":{"resource":"https://commons.wikimedia.org/wiki/File:Photo.png"}}]}""".toByteArray()
            else -> "{}".toByteArray()
        }
        val type = if (url.host in setOf("upload.wikimedia.org", "coverartarchive.org")) "image/png" else "application/json"
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
            .body(body.toResponseBody(type.toMediaType())).build()
    }.build()
    private val prefs = mockk<UserPreferencesRepository>(relaxed = true).also {
        every { it.musicInfoArtistPhotosEnabledFlow } returns flowOf(true)
        every { it.musicInfoCoversEnabledFlow } returns flowOf(true)
        every { it.musicInfoDescriptionsEnabledFlow } returns flowOf(true)
        every { it.externalLyricsEnabledFlow } returns flowOf(true)
    }
    private val dao = mockk<MusicDao>(relaxed = true)
    private val lyricsDao = mockk<LyricsDao>(relaxed = true).also { dao ->
        coEvery { dao.getLyricsResolvedByIdentity(any()) } answers { rows[firstArg()] }
        coEvery { dao.getLyricsForLibraryItem(any()) } answers { rows.values.firstOrNull { it.libraryItemId == firstArg<String>() } }
        coEvery { dao.insert(any()) } answers { rows[firstArg<LyricsEntity>().songId] = firstArg() }
        coEvery { dao.deleteLyrics(any()) } answers { rows.remove(firstArg()); Unit }
        coEvery { dao.deleteLyricsForLibraryItem(any()) } answers { rows.entries.removeIf { it.value.libraryItemId == firstArg<String>() }; Unit }
        coEvery { dao.deleteAll() } answers { rows.clear() }
    }
    private val api = mockk<LrcLibApiService>(relaxed = true).also { api ->
        coEvery { api.searchLyrics(any(), any(), any(), any()) } coAnswers {
            lyricRequests.incrementAndGet()
            lyricsGate?.let { (started, release) ->
                if (!started.isCompleted) started.complete(Unit)
                release.await()
            }
            val title = secondArg<String?>() ?: "Song 1"
            val artist = thirdArg<String?>() ?: "A"
            arrayOf(LrcLibResponse(1, title, artist, "Album", 180.0, plainLyrics = "Downloaded lyric line", syncedLyrics = null))
        }
    }
    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
    private fun info(dir: File = root) = MusicInfoRepository(context(dir), prefs, client, dao, scope())
    private fun lyrics(dir: File = root) = LyricsRepositoryImpl(context(dir), api, lyricsDao, client, prefs)
    private fun seed(info: MusicInfoRepository, song: Song) {
        val main = MusicBrainzClient.firstCreditedArtist(song.artist)
        info.cache.putFile(MusicInfoCache.Kind.ARTIST_PHOTO, MusicInfoService.photoKey(main), png, "png", null)
        info.cache.putText(MusicInfoCache.Kind.ABOUT, MusicInfoService.aboutKey(AboutKind.ARTIST, main, null, "und"), about("und"))
        for (artist in setOf(main, song.artist)) {
            info.cache.putFile(MusicInfoCache.Kind.COVER, MusicInfoService.coverKey(song.album, artist), png, "png", null)
            info.cache.putText(MusicInfoCache.Kind.ABOUT, MusicInfoService.aboutKey(AboutKind.ALBUM, song.album, artist, "und"), about("und"))
        }
        rows[song.id.toLong()] = LyricsEntity(song.id.toLong(), "Downloaded lyric line", false, "remote", song.libraryItemId)
        File(root, "lyrics/${song.id}.json").apply {
            parentFile.mkdirs()
            writeText("""{"plainLyrics":"Downloaded lyric line"}""")
        }
    }
    private fun about(language: String) = """{"text":"Description","title":"Album","language":"$language","pageUrl":"https://en.wikipedia.org/wiki/Album"}"""
    private fun downloads(info: MusicInfoRepository, lyrics: LyricsRepositoryImpl, songs: List<Song>): LegacyOfflineDownloads {
        val music = mockk<MusicRepository>(relaxed = true)
        coEvery { music.getAllSongsOnce() } returns songs
        every { music.getSongsByIds(any()) } answers { flowOf(songs.filter { it.id in firstArg<List<String>>() }) }
        mockkObject(WorkManager.Companion)
        every { WorkManager.getInstance(any<Context>()) } returns mockk(relaxed = true)
        return LegacyOfflineDownloads(context(), music, scope(), mockk<LedgerProvider>(relaxed = true), info, lyrics)
    }
    private suspend fun runJob(downloads: LegacyOfflineDownloads, info: MusicInfoRepository, lyrics: LyricsRepositoryImpl, songs: List<Song>) {
        val id = downloads.state.value!!.id
        val params = mockk<WorkerParameters>(relaxed = true) {
            every { inputData } returns workDataOf("batch" to id)
            every { this@mockk.id } returns UUID.randomUUID()
        }
        val music = mockk<MusicRepository>(relaxed = true)
        every { music.getSongsByIds(any()) } answers { flowOf(songs.filter { it.id in firstArg<List<String>>() }) }
        LegacyOfflineDownloadWorker(context(), params, downloads, music, lyrics, info, dao).doWork()
    }
    private suspend fun settle() {
        withTimeout(20_000) {
            while (scopes.any { scope -> scope.coroutineContext[Job]!!.children.any { !it.isCompleted } }) delay(10)
        }
    }
    @After fun close() {
        scopes.forEach { it.cancel() }
        unmockkObject(WorkManager.Companion)
        root.deleteRecursively()
    }
    @Test fun `artist deletion survives fresh repositories and automatic playback without downloading again`() = runBlocking<Unit> {
        val initial = info()
        seed(initial, first)
        downloads(initial, lyrics(), listOf(first)).deleteArtistInfo("A", listOf(first))
        requests.clear()
        val fresh = info()
        fresh.requestSongInfo(first)
        assertThat(lyrics().getLyrics(first, LyricsSourcePreference.API_FIRST)).isNull()
        settle()
        assertThat(requests).isEmpty()
        assertThat(lyricRequests.get()).isEqualTo(0)
        assertThat(fresh.service.cachedArtistPhoto("A")).isNull()
        assertThat(File(root, "lyrics/1.json").exists()).isFalse()
    }
    @Test fun `compound album artist deletion matches primary artist lookups and every description language`() = runBlocking<Unit> {
        val song = first.copy(artist = "A, B")
        val initial = info()
        seed(initial, song)
        initial.cache.putText(MusicInfoCache.Kind.ABOUT, MusicInfoService.aboutKey(AboutKind.ALBUM, "Album", "A", "fr"), about("fr"))
        downloads(initial, lyrics(), listOf(song)).deleteAlbumInfo("Album", "A, B", listOf(song))
        requests.clear()
        val fresh = info()
        fresh.requestSongInfo(song)
        assertThat(lyrics().getLyrics(song)).isNull()
        settle()
        assertThat(fresh.service.fetchCover("Album", "A")).isNull()
        assertThat(fresh.service.fetchAbout(AboutKind.ALBUM, "Album", "A")).isNull()
        assertThat(fresh.cache.get(MusicInfoCache.Kind.ABOUT, MusicInfoService.aboutKey(AboutKind.ALBUM, "Album", "A", "fr"))).isNull()
        assertThat(requests).isEmpty()
        assertThat(lyricRequests.get()).isEqualTo(0)
        assertThat(OfflineTarget(OfflineKind.COVERS, "Album", albumArtist = "A, B").sharedKey())
            .isEqualTo(OfflineTarget(OfflineKind.COVERS, "Album", albumArtist = "A").sharedKey())
    }
    @Test fun `a library job completes deleted targets with their own reason and no misses or retries`() = runBlocking<Unit> {
        val initial = info()
        seed(initial, first)
        val lyrics = lyrics()
        val downloads = downloads(initial, lyrics, listOf(first))
        downloads.deleteArtistInfo("A", listOf(first))
        downloads.deleteAlbumInfo("Album", "A", listOf(first))
        requests.clear()
        downloads.start(null, OfflineKind.entries.toSet())
        runJob(downloads, initial, lyrics, listOf(first))
        val batch = downloads.state.value!!
        assertThat(batch.status).isEqualTo(OfflineStatus.COMPLETE)
        assertThat(batch.next).isEqualTo(batch.total)
        assertThat(batch.available + batch.unavailable + batch.failed).isEqualTo(0)
        assertThat(batch.retries).isEmpty()
        val journal = File(root, "offline-downloads/jobs/${batch.planId}/results.jsonl").readLines()
            .map(::JSONObject).filter { it.has("index") }
        assertThat(journal).hasSize(batch.total)
        assertThat(journal.all { it.optBoolean("deletedByUser") }).isTrue()
        assertThat(requests).isEmpty()
        assertThat(lyricRequests.get()).isEqualTo(0)
    }
    @Test fun `deletion clears existing retries and wakes an active job while a paused job stays paused`() = runBlocking<Unit> {
        for (paused in listOf(false, true)) {
            val info = info()
            val lyrics = lyrics()
            val downloads = downloads(info, lyrics, listOf(first))
            downloads.start(null, setOf(OfflineKind.LYRICS))
            val initial = downloads.state.value!!
            downloads.record(initial.id, 0, OfflineResult(first.title, 0, 0, 1))
            assertThat(downloads.state.value!!.retries).isNotEmpty()
            if (paused) downloads.cancel()
            val work = WorkManager.getInstance(context())
            clearMocks(work, answers = false)
            downloads.deleteArtistInfo("A", listOf(first))
            val updated = downloads.state.value!!
            assertThat(updated.retries).isEmpty()
            assertThat(updated.failed).isEqualTo(0)
            verify(exactly = if (paused) 0 else 1) {
                work.enqueueUniqueWork(any(), androidx.work.ExistingWorkPolicy.REPLACE, any<androidx.work.OneTimeWorkRequest>())
            }
            if (paused) {
                assertThat(updated.status).isEqualTo(OfflineStatus.PAUSED)
            } else {
                runJob(downloads, info, lyrics, listOf(first))
                assertThat(downloads.state.value!!.status).isEqualTo(OfflineStatus.COMPLETE)
                assertThat(downloads.results().single().available).isEqualTo(0)
            }
        }
        assertThat(requests).isEmpty()
        assertThat(lyricRequests.get()).isEqualTo(0)
    }

    @Test fun `refresh after restart restores its artist without restoring another deleted artist`() = runBlocking<Unit> {
        val initial = info()
        seed(initial, first)
        seed(initial, second)
        val downloads = downloads(initial, lyrics(), listOf(first, second))
        downloads.deleteArtistInfo("A", listOf(first))
        downloads.deleteArtistInfo("B", listOf(second))
        val fresh = info()
        fresh.refreshArtistInfo("A")
        assertThat(fresh.service.cachedArtistPhoto("A")).isNotNull()
        requests.clear()
        assertThat(info().service.fetchArtistPhoto("B")).isNull()
        assertThat(requests).isEmpty()
    }
    @Test fun `explicit preparation restores only its selected targets after a library job skipped them`() = runBlocking<Unit> {
        val initial = info()
        val lyrics = lyrics()
        val downloads = downloads(initial, lyrics, listOf(first, second))
        downloads.deleteArtistInfo("A", listOf(first))
        downloads.deleteArtistInfo("B", listOf(second))
        downloads.start(null, setOf(OfflineKind.ARTIST_PHOTOS, OfflineKind.LYRICS))
        runJob(downloads, initial, lyrics, listOf(first, second))
        downloads.start(listOf("1"), setOf(OfflineKind.ARTIST_PHOTOS, OfflineKind.LYRICS))
        runJob(downloads, initial, lyrics, listOf(first, second))
        assertThat(initial.service.cachedArtistPhoto("A")).isNotNull()
        assertThat(File(root, "lyrics/1.json").isFile).isTrue()
        requests.clear()
        assertThat(info().service.fetchArtistPhoto("B")).isNull()
        assertThat(lyrics().getLyrics(second)).isNull()
        assertThat(requests).isEmpty()
    }
    @Test fun `one song lyrics search clears only that songs deletion`() = runBlocking<Unit> {
        val initial = info()
        val repository = lyrics()
        val downloads = downloads(initial, repository, listOf(first, second))
        downloads.deleteArtistInfo("A", listOf(first))
        downloads.deleteArtistInfo("B", listOf(second))
        assertThat(repository.searchRemote(first).isSuccess).isTrue()
        val calls = lyricRequests.get()
        assertThat(lyrics().getLyrics(second)).isNull()
        assertThat(lyricRequests.get()).isEqualTo(calls)
    }
    @Test fun `global downloaded data deletion clears music info and lyrics marks`() = runBlocking<Unit> {
        val initial = info()
        val repository = lyrics()
        downloads(initial, repository, listOf(first)).deleteArtistInfo("A", listOf(first))
        val markFile = File(root, "music-info/deleted-downloads.txt")
        assertThat(markFile.isFile).isTrue()
        initial.deleteDownloadedData()
        val fresh = info()
        assertThat(fresh.service.fetchArtistPhoto("A")).isNotNull()
        assertThat(lyrics().getLyrics(first)).isNotNull()
        // Durable marks really existed, not just an empty in-memory collection.
        assertThat(markFile.exists()).isFalse()
    }
    @Test fun `a deletion follows library identity when the media store locator changes`() = runBlocking<Unit> {
        downloads(info(), lyrics(), listOf(first)).deleteArtistInfo("A", listOf(first))
        val rescanned = first.copy(id = "900")
        assertThat(lyrics().getLyrics(rescanned)).isNull()
        assertThat(lyricRequests.get()).isEqualTo(0)
    }
    @Test fun `a marked song still returns its local lrc with API first selected`() = runBlocking<Unit> {
        val path = File(root, "Local.flac")
        val sidecar = File(root, "Local.lrc").apply { writeText("[00:01.00]Local lyric line") }
        val song = first.copy(path = path.absolutePath)
        downloads(info(), lyrics(), listOf(song)).deleteArtistInfo("A", listOf(song))
        val result = lyrics().getLyrics(song, LyricsSourcePreference.API_FIRST)
        assertThat(result?.synced?.firstOrNull()?.line).isEqualTo("Local lyric line")
        assertThat(result?.areFromRemote).isFalse()
        assertThat(lyricRequests.get()).isEqualTo(0)
        assertThat(sidecar.readText()).isEqualTo("[00:01.00]Local lyric line")
    }
    @Test fun `embedded lyrics remain local while other songs remain marked`() = runBlocking<Unit> {
        val downloads = downloads(info(), lyrics(), listOf(first, second))
        downloads.deleteArtistInfo("A", listOf(first))
        downloads.deleteArtistInfo("B", listOf(second))
        val embedded = first.copy(lyrics = "Embedded lyric line")
        assertThat(lyrics().getLyrics(embedded)?.plain).containsExactly("Embedded lyric line")
        assertThat(lyrics().getLyrics(second)).isNull()
        assertThat(lyricRequests.get()).isEqualTo(0)
    }
    @Test fun `a corrupt oversized line is ignored and logged once across new instances`() = runBlocking<Unit> {
        File(root, "music-info/deleted-downloads.txt").apply {
            parentFile.mkdirs()
            writeText("broken".repeat(100_000))
        }
        val messages = CopyOnWriteArrayList<String>()
        val tree = object : timber.log.Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                if (tag == "DeletedDownloads") messages += message
            }
        }
        timber.log.Timber.plant(tree)
        try {
            assertThat(info().service.fetchArtistPhoto("A")).isNotNull()
            assertThat(info().service.fetchArtistPhoto("A")).isNotNull()
            assertThat(messages).hasSize(1)
        } finally {
            timber.log.Timber.uproot(tree)
        }
    }
    @Test fun `a warm remote cache in another repository cannot bypass a later deletion`() = runBlocking<Unit> {
        val initial = info()
        seed(initial, first)
        val warm = lyrics()
        assertThat(warm.getLyrics(first)).isNotNull()
        downloads(initial, lyrics(), listOf(first)).deleteArtistInfo("A", listOf(first))
        assertThat(warm.getLyrics(first)).isNull()
        assertThat(lyricRequests.get()).isEqualTo(0)
    }

    @Test fun `a JSON cache warmed during a forced lookup remains remote after another repository deletes it`() = runBlocking<Unit> {
        val initial = info()
        seed(initial, first)
        val warm = lyrics()
        assertThat(warm.getLyrics(first, forceRefresh = true)).isNotNull()
        downloads(initial, lyrics(), listOf(first)).deleteArtistInfo("A", listOf(first))
        assertThat(warm.getLyrics(first)).isNull()
        assertThat(lyricRequests.get()).isEqualTo(0)
    }

    @Test fun `explicit preparation of covers does not lift descriptions photos or lyrics marks`() = runBlocking<Unit> {
        val initial = info()
        val repository = lyrics()
        val downloads = downloads(initial, repository, listOf(first))
        downloads.deleteArtistInfo("A", listOf(first))
        downloads.deleteAlbumInfo("Album", "A", listOf(first))
        downloads.start(listOf("1"), setOf(OfflineKind.COVERS))
        runJob(downloads, initial, repository, listOf(first))
        assertThat(initial.service.cachedCover("Album", "A")).isNotNull()
        requests.clear()
        val fresh = info()
        assertThat(fresh.service.fetchArtistPhoto("A")).isNull()
        assertThat(fresh.service.fetchAbout(AboutKind.ARTIST, "A")).isNull()
        assertThat(fresh.service.fetchAbout(AboutKind.ALBUM, "Album", "A")).isNull()
        assertThat(lyrics().getLyrics(first)).isNull()
        assertThat(requests).isEmpty()
        assertThat(lyricRequests.get()).isEqualTo(0)
    }

    @Test fun `manual lyrics query lifts only the current songs mark through the real repository`() = runBlocking<Unit> {
        val repository = lyrics()
        val downloads = downloads(info(), repository, listOf(first, second))
        downloads.deleteArtistInfo("A", listOf(first))
        downloads.deleteArtistInfo("B", listOf(second))
        val music = MusicRepositoryImpl(context(), prefs, mockk(relaxed = true), mockk(relaxed = true), dao,
            repository, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true))
        val dispatchers = object : DispatcherProvider {
            override val main = Dispatchers.Unconfined
            override val io = Dispatchers.IO
            override val default = Dispatchers.Default
        }
        val holder = LyricsStateHolder(music, prefs, mockk(relaxed = true), scope(), dispatchers)
        val method = holder.javaClass.methods.filter { it.name == "searchLyricsManually" }
            .maxBy { it.parameterCount }
        if (method.parameterCount == 3) {
            method.invoke(holder, first.title, first.artist, first)
        } else {
            method.invoke(holder, first.title, first.artist)
        }
        withTimeout(10_000) {
            holder.searchUiState.first { it !is LyricsSearchUiState.Idle && it !is LyricsSearchUiState.Loading }
        }
        assertThat(repository.getLyrics(first)).isNotNull()
        val count = lyricRequests.get()
        assertThat(lyrics().getLyrics(second)).isNull()
        assertThat(lyricRequests.get()).isEqualTo(count)
    }

    @Test fun `a photo response in flight cannot recreate an image after its deletion`() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        photoGate = started to release
        val initial = info()
        val lookup = async(Dispatchers.IO) { initial.service.fetchArtistPhoto("A") }
        withTimeout(10_000) { started.await() }
        try {
            downloads(info(), lyrics(), listOf(first)).deleteArtistInfo("A", listOf(first))
        } finally {
            release.complete(Unit)
        }
        assertThat(lookup.await()).isNull()
        assertThat(File(root, "music-info").listFiles().orEmpty().any { it.name.startsWith("photo_") }).isFalse()
        assertThat(info().cache.isMissing(MusicInfoCache.Kind.ARTIST_PHOTO, MusicInfoService.photoKey("A"))).isFalse()
    }

    @Test fun `a lyrics response in flight cannot recreate database or JSON values after deletion`() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        lyricsGate = started to release
        val repository = lyrics()
        val lookup = async(Dispatchers.IO) { repository.getLyrics(first) }
        withTimeout(10_000) { started.await() }
        try {
            downloads(info(), lyrics(), listOf(first)).deleteArtistInfo("A", listOf(first))
        } finally {
            release.complete(Unit)
        }
        assertThat(lookup.await()).isNull()
        assertThat(rows[first.id.toLong()]).isNull()
        assertThat(File(root, "lyrics/1.json").exists()).isFalse()
    }

    @Test fun `a song without library identity uses its locator across repository restarts`() = runBlocking<Unit> {
        val song = first.copy(libraryItemId = "")
        downloads(info(), lyrics(), listOf(song)).deleteArtistInfo("A", listOf(song))
        assertThat(lyrics().getLyrics(song)).isNull()
        assertThat(lyricRequests.get()).isEqualTo(0)
    }

    @Test fun `refreshing an album lifts its songs marks without restoring another artists downloads`() = runBlocking<Unit> {
        val initial = info()
        val repository = lyrics()
        val downloads = downloads(initial, repository, listOf(first, second))
        downloads.deleteAlbumInfo("Album", "A", listOf(first))
        downloads.deleteArtistInfo("B", listOf(second))
        val method = downloads.javaClass.methods.firstOrNull { it.name == "refreshAlbumInfo" }
        if (method == null) {
            initial.service.refreshAlbum("Album", "A")
        } else {
            suspendCoroutine<Any?> { continuation ->
                try {
                    val result = method.invoke(downloads, "Album", "A", listOf(first), continuation)
                    if (result !== COROUTINE_SUSPENDED) continuation.resume(result)
                } catch (error: Throwable) {
                    continuation.resumeWithException(error)
                }
            }
        }
        assertThat(initial.service.cachedCover("Album", "A")).isNotNull()
        assertThat(repository.getLyrics(first)).isNotNull()
        val calls = lyricRequests.get()
        assertThat(lyrics().getLyrics(second)).isNull()
        assertThat(lyricRequests.get()).isEqualTo(calls)
    }

    @Test fun `the marks file alone restores deletion decisions in a new application directory`() = runBlocking<Unit> {
        downloads(info(), lyrics(), listOf(first)).deleteArtistInfo("A", listOf(first))
        val source = File(root, "music-info/deleted-downloads.txt")
        assertThat(source.isFile).isTrue()
        val copiedRoot = File(root, "new-process").apply { mkdirs() }
        val destination = File(copiedRoot, "music-info/deleted-downloads.txt")
        destination.parentFile.mkdirs()
        source.copyTo(destination)
        requests.clear()
        assertThat(info(copiedRoot).service.fetchArtistPhoto("A")).isNull()
        assertThat(lyrics(copiedRoot).getLyrics(first)).isNull()
        assertThat(requests).isEmpty()
        assertThat(lyricRequests.get()).isEqualTo(0)
    }

}
