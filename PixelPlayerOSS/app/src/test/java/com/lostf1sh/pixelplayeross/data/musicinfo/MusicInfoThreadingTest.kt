package com.lostf1sh.pixelplayeross.data.musicinfo

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * B40-fix1 (item 3): the descriptions asked for the internet from `viewModelScope`, which is the main thread; on Android that is a
 * `NetworkOnMainThreadException`, which the service swallowed as "no answer", so a description never appeared. The service now owns its
 * threading: every question and every read of the cache runs on its own IO dispatcher whoever calls it. The "main thread" here is a
 * single named thread the caller switches to, and the fake server records the thread each request arrived on.
 */
class MusicInfoThreadingTest {
    private val dir: File = Files.createTempDirectory("music-info-threads").toFile()
    private val mainLike = Executors.newSingleThreadExecutor { Thread(it, "fake-main") }
    private val fakeMain = mainLike.asCoroutineDispatcher()
    private val requestThreads = mutableListOf<String>()

    @AfterEach
    fun cleanUp() {
        mainLike.shutdownNow()
        dir.deleteRecursively()
    }

    private val client = OkHttpClient.Builder().addInterceptor { chain ->
        requestThreads += Thread.currentThread().name
        val url = chain.request().url.toString()
        val body = when {
            "musicbrainz.org/ws/2/artist?" in url -> """{"artists":[{"id":"a-1","name":"Radiohead","score":100}]}"""
            "musicbrainz.org/ws/2/artist/a-1" in url ->
                """{"relations":[{"type":"wikidata","url":{"resource":"https://www.wikidata.org/wiki/Q44190"}}]}"""
            "action=wbgetentities" in url -> """{"entities":{"Q44190":{"sitelinks":{"enwiki":{"title":"Radiohead"}}}}}"""
            "action=wbgetclaims" in url -> """{"claims":{"P18":[{"mainsnak":{"datavalue":{"value":"Radiohead in 2008.jpg"}}}]}}"""
            "commons.wikimedia.org/w/api.php" in url ->
                """{"query":{"pages":{"1":{"imageinfo":[{"thumburl":"https://upload.wikimedia.org/t.jpg","descriptionurl":"https://commons.wikimedia.org/wiki/File:R.jpg"}]}}}}"""
            "en.wikipedia.org/api/rest_v1/page/summary/Radiohead" in url ->
                """{"type":"standard","title":"Radiohead","extract":"Radiohead are a band.","content_urls":{"desktop":{"page":"https://en.wikipedia.org/wiki/Radiohead"}}}"""
            else -> null
        }
        val builder = Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
        if (body == null) builder.code(404).message("Not Found").body("".toResponseBody("application/json".toMediaType())).build()
        else builder.code(200).message("OK").body(body.toResponseBody("application/json".toMediaType())).build()
    }.build()

    private val http = MusicInfoHttp(client, minIntervalMs = 0, nowMs = { 0L }, sleep = { })
    private val cache = MusicInfoCache(dir, limitBytes = { 10L * 1024 * 1024 }, now = { 0L })

    /** The IO dispatcher of the service, counting what is dispatched through it. */
    private class CountingIo(private val inner: CoroutineDispatcher = Dispatchers.IO) : CoroutineDispatcher() {
        val dispatched = AtomicInteger()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatched.incrementAndGet()
            inner.dispatch(context, block)
        }
    }

    private val io = CountingIo()

    private fun service() = MusicInfoService(
        cache = cache, brainz = MusicBrainzClient(http), coverArt = CoverArtArchiveClient(http), wikidata = WikidataClient(http),
        commons = CommonsClient(http), wikipedia = WikipediaClient(http),
        artistPhotosEnabled = { true }, coversEnabled = { true }, descriptionsEnabled = { true }, languages = { listOf("en") }, io = io,
    )

    @Test
    fun `a description asked for from the main thread leaves it for the network`() = runBlocking<Unit> {
        val description = withContext(fakeMain) { service().fetchAbout(AboutKind.ARTIST, "Radiohead") }
        assertThat(description?.text).isEqualTo("Radiohead are a band.")
        assertThat(requestThreads).isNotEmpty()
        assertThat(requestThreads).doesNotContain("fake-main")
    }

    @Test
    fun `an artist photo asked for from the main thread leaves it for the network`() = runBlocking<Unit> {
        withContext(fakeMain) { service().fetchArtistPhoto("Radiohead") }
        assertThat(requestThreads).isNotEmpty()
        assertThat(requestThreads).doesNotContain("fake-main")
    }

    @Test
    fun `an album cover asked for from the main thread leaves it for the network`() = runBlocking<Unit> {
        withContext(fakeMain) { service().fetchCover("OK Computer", "Radiohead") }
        assertThat(requestThreads).isNotEmpty()
        assertThat(requestThreads).doesNotContain("fake-main")
    }

    @Test
    fun `reading what is on the phone from the main thread goes through the IO dispatcher too`() = runBlocking<Unit> {
        val s = service()
        val before = io.dispatched.get()
        withContext(fakeMain) {
            s.cachedAbout(AboutKind.ARTIST, "Radiohead", null)
            s.cachedArtistPhoto("Radiohead")
            s.cachedCover("OK Computer", "Radiohead")
        }
        // each of the three reads was handed to the service's IO dispatcher (and came back to the caller's thread)
        assertThat(io.dispatched.get() - before).isAtLeast(3)
        assertThat(requestThreads).isEmpty()
    }

    @Test
    fun `no view model or caller wraps the service in its own dispatcher to be safe, because the service owns it`() {
        val service = File("src/main/java/com/lostf1sh/pixelplayeross/data/musicinfo/MusicInfoService.kt").readText().replace("\r\n", "\n")
        for (signature in listOf(
            "suspend fun cachedArtistPhoto(", "suspend fun cachedCover(", "suspend fun cachedAbout(",
            "suspend fun fetchArtistPhoto(", "suspend fun fetchCover(", "suspend fun fetchAbout(",
        )) {
            val line = service.substringAfter(signature).substringBefore("\n")
            assertThat(line).contains("withContext(io)")
        }
        for (path in listOf("ArtistDetailViewModel", "AlbumDetailViewModel")) {
            val vm = File("src/main/java/com/lostf1sh/pixelplayeross/presentation/viewmodel/$path.kt").readText().replace("\r\n", "\n")
            val about = vm.substringAfter("private fun loadAbout(").substringBefore("\n    init {").substringBefore("private val _artistColorScheme")
            assertThat(about).contains("viewModelScope.launch {")
            assertThat(about).contains("suspend fun current()")
            assertThat(about).doesNotContain("Dispatchers")
        }
    }
}
