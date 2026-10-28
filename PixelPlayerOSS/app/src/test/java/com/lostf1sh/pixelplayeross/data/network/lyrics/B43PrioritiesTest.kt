package com.lostf1sh.pixelplayeross.data.network.lyrics

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.network.RequestPriority
import com.lostf1sh.pixelplayeross.data.musicinfo.MusicInfoHttp
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.offline.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class B43PrioritiesTest {
    @Test fun `the current song goes next after the in flight lyrics request`() = runTest {
        val gate = LrcLibRequestPacer({ testScheduler.currentTime }, { 0L }, { delay(it) })
        val release = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val first = async(RequestPriority(true)) { gate.request { order += "in flight"; release.await() } }
        testScheduler.runCurrent()
        val batches = (1..4).map { index -> async(RequestPriority(true)) { gate.request { order += "batch $index" } } }
        testScheduler.runCurrent()
        val live = async { gate.request { order += "current song" } }
        testScheduler.runCurrent()
        release.complete(Unit)
        (listOf(first, live) + batches).awaitAll()
        assertThat(order.take(2)).containsExactly("in flight", "current song").inOrder()
    }
    @Test fun `Commons is paced independently and faster while MusicBrainz keeps its interval`() = runTest {
        var clock = 0L
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("{}".toResponseBody()).build()
        }.build()
        val http = MusicInfoHttp(client, nowMs = { clock }, sleep = { clock += it })
        http.json("https://commons.wikimedia.org/a".toHttpUrl())
        http.json("https://commons.wikimedia.org/b".toHttpUrl())
        assertThat(clock).isEqualTo(250L)
        http.json("https://musicbrainz.org/a".toHttpUrl())
        val mbStart = clock
        http.json("https://musicbrainz.org/b".toHttpUrl())
        assertThat(clock - mbStart).isAtLeast(1000L)
    }
    @Test fun `a favourite artist precedes an unplayed artist in every selected kind`() {
        val rest = Song.emptySong().copy(id = "1", artist = "Rest", album = "Rest")
        val favorite = rest.copy(id = "2", artist = "Favorite", album = "Favorite", isFavorite = true)
        val plan = OfflinePlan.build(listOf(rest, favorite), setOf(OfflineKind.ARTIST_PHOTOS, OfflineKind.COVERS))
        assertThat(plan.first().title).isEqualTo("Favorite")
    }
}
