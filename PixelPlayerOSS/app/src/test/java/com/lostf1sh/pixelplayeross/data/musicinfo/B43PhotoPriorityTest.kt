package com.lostf1sh.pixelplayeross.data.musicinfo

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.network.RequestPriority
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class B43PhotoPriorityTest {
    @Test fun `an unrelated queued artist cannot retain the client gate while a host request is in flight`() = runTest {
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            chain.request().url.queryParameter("query")?.let { order += it }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("{\"artists\":[]}".toResponseBody()).build()
        }.build()
        var pause = true
        val http = MusicInfoHttp(client, nowMs = { testScheduler.currentTime }, sleep = {
            if (pause) { pause = false; started.complete(Unit); release.await() }
            delay(it)
        })
        val dir = Files.createTempDirectory("b43-host-priority").toFile()
        try {
            val service = MusicInfoService(MusicInfoCache(dir, { 0L }), MusicBrainzClient(http), CoverArtArchiveClient(http),
                WikidataClient(http), CommonsClient(http), WikipediaClient(http), { true }, { true }, { true },
                { listOf("en") }, StandardTestDispatcher(testScheduler))
            http.json("https://musicbrainz.org/seed".toHttpUrl())
            val inFlight = async(RequestPriority(true)) { http.json("https://musicbrainz.org/other-in-flight".toHttpUrl()) }
            runCurrent(); started.await()
            val background = async(RequestPriority(true)) { service.fetchArtistPhoto("Queued batch") }
            runCurrent()
            val interactive = async { service.fetchArtistPhoto("Live") }
            runCurrent(); release.complete(Unit)
            listOf(inFlight, background, interactive).awaitAll()
            assertThat(order).containsExactly("artist:\"Live\"", "artist:\"Queued batch\"").inOrder()
        } finally { dir.deleteRecursively() }
    }
    @Test fun `joining the same pending artist promotes its shared request ahead of other batch artists`() = runTest {
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            chain.request().url.queryParameter("query")?.let { order += it }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("{\"artists\":[]}".toResponseBody()).build()
        }.build()
        var pause = true
        val http = MusicInfoHttp(client, nowMs = { testScheduler.currentTime }, sleep = {
            if (pause) { pause = false; started.complete(Unit); release.await() }
            delay(it)
        })
        val brainz = MusicBrainzClient(http)
        http.json("https://musicbrainz.org/seed".toHttpUrl())
        val inFlight = async(RequestPriority(true)) { http.json("https://musicbrainz.org/in-flight".toHttpUrl()) }
        runCurrent(); started.await()
        val other = async(RequestPriority(true)) { brainz.findArtist("Other batch") }
        runCurrent()
        val same = async(RequestPriority(true)) { brainz.findArtist("Shared artist") }
        runCurrent()
        val interactive = async { brainz.findArtist("Shared artist") }
        runCurrent(); release.complete(Unit)
        listOf(inFlight, other, same, interactive).awaitAll()
        assertThat(order).containsExactly("artist:\"Shared artist\"", "artist:\"Other batch\"").inOrder()
    }
    @Test fun `an interactive artist photo passes every queued batch artist lookup`() = runTest {
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            chain.request().url.queryParameter("query")?.let { order += it }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("{\"artists\":[]}".toResponseBody()).build()
        }.build()
        var pause = true
        val http = MusicInfoHttp(client, minIntervalMs = 1100, nowMs = { testScheduler.currentTime }, sleep = {
            if (pause) { pause = false; started.complete(Unit); release.await() }
            delay(it)
        })
        val dir = Files.createTempDirectory("b43-photo-priority").toFile()
        try {
            val service = MusicInfoService(MusicInfoCache(dir, { 0L }), MusicBrainzClient(http), CoverArtArchiveClient(http),
                WikidataClient(http), CommonsClient(http), WikipediaClient(http), { true }, { true }, { true },
                { listOf("en") }, StandardTestDispatcher(testScheduler))
            http.json("https://musicbrainz.org/seed".toHttpUrl())
            val first = async(RequestPriority(true)) { service.fetchArtistPhoto("In flight") }
            runCurrent(); started.await()
            val batch = (1..4).map { async(RequestPriority(true)) { service.fetchArtistPhoto("Batch $it") } }
            runCurrent()
            val interactive = async { service.fetchArtistPhoto("Live") }
            runCurrent(); release.complete(Unit)
            (listOf(first, interactive) + batch).awaitAll()
            assertThat(order.take(2)).containsExactly("artist:\"In flight\"", "artist:\"Live\"").inOrder()
        } finally { dir.deleteRecursively() }
    }
}
