package com.lostf1sh.pixelplayeross.data.musicinfo

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitAll
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class MusicBrainzPrimaryTest {
    private val dir = Files.createTempDirectory("brainz-primary").toFile()
    @After fun cleanup() { dir.deleteRecursively() }
    private val requests = mutableListOf<String>()
    private var annotation: String? = "Artist information from MusicBrainz"
    private var image = true
    private var brainzFound = true
    private var imageNetworkFailure = false
    private val client = OkHttpClient.Builder().addInterceptor { chain ->
        val url = chain.request().url
        requests += url.toString()
        val body = when {
            url.host == "images.example.org" -> {
                if (imageNetworkFailure) throw IOException("temporary image failure")
                byteArrayOf(1, 2, 3).toResponseBody("image/jpeg".toMediaType())
            }
            url.encodedPath == "/ws/2/artist" -> (if (brainzFound) """{"artists":[{"id":"a-1","name":"Fixture","score":100}]}""" else """{"artists":[]}""").toResponseBody("application/json".toMediaType())
            url.encodedPath == "/ws/2/artist/a-1" -> org.json.JSONObject().apply {
                annotation?.let { put("annotation", it) }
                put("relations", org.json.JSONArray().apply {
                    if (image) put(org.json.JSONObject("""{"type":"image","url":{"resource":"https://images.example.org/photo.jpg"}}"""))
                    put(org.json.JSONObject("""{"type":"wikidata","url":{"resource":"https://www.wikidata.org/wiki/Q1"}}"""))
                })
            }.toString().toResponseBody("application/json".toMediaType())
            url.host == "www.wikidata.org" && url.queryParameter("action") == "wbsearchentities" ->
                """{"search":[{"id":"Q1","label":"Fixture","description":"English singer"}]}""".toResponseBody("application/json".toMediaType())
            url.host == "www.wikidata.org" -> """{"entities":{"Q1":{"sitelinks":{"enwiki":{"title":"Fixture"}}}}}""".toResponseBody("application/json".toMediaType())
            url.host == "en.wikipedia.org" -> """{"type":"standard","title":"Fixture","extract":"Wikipedia fallback","content_urls":{"desktop":{"page":"https://en.wikipedia.org/wiki/Fixture"}}}""".toResponseBody("application/json".toMediaType())
            else -> throw IOException("Unexpected request: $url")
        }
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body).build()
    }.build()
    private fun service(enabled: Boolean = true): MusicInfoService {
        val http = MusicInfoHttp(client, minIntervalMs = 0, nowMs = { 0L }, sleep = {})
        return MusicInfoService(MusicInfoCache(dir, { 0L }), MusicBrainzClient(http), CoverArtArchiveClient(http),
            WikidataClient(http), CommonsClient(http), WikipediaClient(http), { enabled }, { enabled }, { enabled }, { listOf("en") })
    }
    @Test fun `MusicBrainz text is preferred and remains available offline without Wikipedia`() = runBlocking {
        val service = service()
        assertThat(service.fetchAbout(AboutKind.ARTIST, "Fixture")?.text).isEqualTo(annotation)
        val count = requests.size
        assertThat(service.cachedAbout(AboutKind.ARTIST, "Fixture", null)?.pageUrl).isEqualTo("https://musicbrainz.org/artist/a-1")
        assertThat(requests).hasSize(count)
        assertThat(requests.none { "wikipedia.org" in it || "wikidata.org" in it }).isTrue()
    }
    @Test fun `MusicBrainz image is preferred and no Wikimedia image lookup is made`() = runBlocking {
        val photo = service().fetchArtistPhoto("Fixture")!!
        assertThat(java.io.File(photo.path).readBytes().toList()).containsExactly(1.toByte(), 2.toByte(), 3.toByte()).inOrder()
        assertThat(photo.provider).isEqualTo("MusicBrainz")
        assertThat(requests.none { "wikimedia.org" in it || "wikidata.org" in it }).isTrue()
    }
    @Test fun `Wikipedia is used when MusicBrainz has no text`() = runBlocking {
        annotation = null
        assertThat(service().fetchAbout(AboutKind.ARTIST, "Fixture")?.text).isEqualTo("Wikipedia fallback")
        assertThat(requests.any { "wikipedia.org" in it }).isTrue()
    }
    @Test fun `manual download is explicit consent and does not enable normal automatic requests`() = runBlocking {
        val service = service(enabled = false)
        assertThat(service.fetchArtistPhoto("Fixture")).isNull()
        assertThat(service.fetchAbout(AboutKind.ARTIST, "Fixture")).isNull()
        assertThat(requests).isEmpty()
        assertThat(service.fetchArtistPhoto("Fixture", userRequested = true)).isNotNull()
        val count = requests.size
        assertThat(service.fetchAbout(AboutKind.ARTIST, "Different artist")).isNull()
        assertThat(requests).hasSize(count)
    }
    @Test fun `the Wikipedia fallback can work when MusicBrainz has no matching entity`() = runBlocking {
        brainzFound = false
        assertThat(service().fetchAbout(AboutKind.ARTIST, "Fixture")?.text).isEqualTo("Wikipedia fallback")
        assertThat(requests.any { "wbsearchentities" in it }).isTrue()
    }
    @Test fun `a failed primary image transfer is not cached as permanently missing`() = runBlocking {
        val service = service()
        imageNetworkFailure = true
        assertThat(service.fetchArtistPhoto("Fixture")).isNull()
        imageNetworkFailure = false
        assertThat(service.fetchArtistPhoto("Fixture")?.provider).isEqualTo("MusicBrainz")
    }
    @Test fun `fallback rejects a namesake city and keeps the matching musical entity`() {
        val json = org.json.JSONObject("""{"search":[{"id":"Q1","label":"Fixture","description":"French city"},{"id":"Q2","label":"Fixture","description":"English singer"}]}""")
        assertThat(WikidataClient.parseMusicSearch(json, "Fixture", null)).isEqualTo("Q2")
    }
    @Test fun `parallel photos and descriptions share the artist search and entity details`() = runBlocking {
        val service = service()
        listOf(async(Dispatchers.IO) { service.fetchArtistPhoto("Fixture", true) },
            async(Dispatchers.IO) { service.fetchAbout(AboutKind.ARTIST, "Fixture", userRequested = true) }).awaitAll()
        assertThat(requests.count { it.toHttpUrl().encodedPath == "/ws/2/artist" }).isEqualTo(1)
        assertThat(requests.count { it.toHttpUrl().encodedPath == "/ws/2/artist/a-1" }).isEqualTo(1)
    }
    @Test fun `unlimited persistent downloads do not evict images or descriptions`() {
        val cache = MusicInfoCache(dir, { 0L })
        repeat(12) { cache.putFile(MusicInfoCache.Kind.ARTIST_PHOTO, "photo$it", ByteArray(4096), "jpg", null) }
        cache.putText(MusicInfoCache.Kind.ABOUT, "about", "description")
        val cold = MusicInfoCache(dir, { 0L })
        repeat(12) { assertThat(cold.get(MusicInfoCache.Kind.ARTIST_PHOTO, "photo$it")?.file?.isFile).isTrue() }
        assertThat(cold.get(MusicInfoCache.Kind.ABOUT, "about")?.meta).isEqualTo("description")
    }
}
