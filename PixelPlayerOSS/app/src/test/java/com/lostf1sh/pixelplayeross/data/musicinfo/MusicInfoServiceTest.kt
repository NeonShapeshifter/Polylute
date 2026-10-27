package com.lostf1sh.pixelplayeross.data.musicinfo

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.network.HttpUserAgent
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * B40-F4: the music-info clients and service against a fake server (an OkHttp interceptor that answers by address): the query each client
 * builds, the answer it reads, the one request a second, the attribution kept with what was downloaded, the three opt-in gates, the
 * language fallback, the cache limit and its deletion, and that nothing of Deezer is left.
 */
class MusicInfoServiceTest {
    private val dir: File = Files.createTempDirectory("music-info").toFile()
    private val requests = mutableListOf<Request>()
    private val failing = mutableSetOf<String>()
    private var clock = 0L
    private val sleeps = mutableListOf<Long>()

    @AfterEach
    fun cleanUp() { dir.deleteRecursively() }

    /** What the fake server says, by address; anything it does not know is a 404. */
    private val answers = mutableMapOf<(String) -> Boolean, () -> ByteArray?>()

    private fun answer(match: (String) -> Boolean, body: () -> ByteArray?) { answers[match] = body }
    private fun json(text: String) = text.toByteArray()

    private val client = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        requests += request
        val url = request.url.toString()
        if (failing.any { it in url }) throw IOException("no network")
        val body = answers.entries.firstOrNull { it.key(url) }?.value?.invoke()
        val builder = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
        if (body == null) {
            builder.code(404).message("Not Found").body("".toResponseBody("application/json".toMediaType())).build()
        } else {
            builder.code(200).message("OK").body(body.toResponseBody("application/json".toMediaType())).build()
        }
    }.build()

    private val http = MusicInfoHttp(client, nowMs = { clock }, sleep = { clock += it; sleeps += it })
    private var covers = true
    private var photos = true
    private var descriptions = true
    private var limit = 10L * 1024 * 1024
    private val cache = MusicInfoCache(dir, limitBytes = { limit }, now = { clock })
    private var languages = listOf("es", "en")

    private fun service() = MusicInfoService(
        cache = cache,
        brainz = MusicBrainzClient(http),
        coverArt = CoverArtArchiveClient(http),
        wikidata = WikidataClient(http),
        commons = CommonsClient(http),
        wikipedia = WikipediaClient(http),
        artistPhotosEnabled = { photos },
        coversEnabled = { covers },
        descriptionsEnabled = { descriptions },
        languages = { languages },
    )

    private val jpeg = ByteArray(2_000) { it.toByte() }

    private fun theArtistExists() {
        answer({ "musicbrainz.org/ws/2/artist?" in it }) {
            json("""{"artists":[{"id":"a-1","name":"Radiohead","score":100},{"id":"a-2","name":"Radiohead Tribute","score":95}]}""")
        }
        answer({ "musicbrainz.org/ws/2/artist/a-1" in it }) {
            json("""{"relations":[{"type":"official homepage","url":{"resource":"https://radiohead.com"}},{"type":"wikidata","url":{"resource":"https://www.wikidata.org/wiki/Q44190"}}]}""")
        }
    }

    private fun thePhotoExists() {
        theArtistExists()
        answer({ "action=wbgetclaims" in it }) {
            json("""{"claims":{"P18":[{"mainsnak":{"datavalue":{"value":"Radiohead in 2008.jpg"}}}]}}""")
        }
        answer({ "commons.wikimedia.org/w/api.php" in it }) {
            json(
                """{"query":{"pages":{"123":{"imageinfo":[{"thumburl":"https://upload.wikimedia.org/thumb/radiohead.jpg","url":"https://upload.wikimedia.org/radiohead.jpg",
                "descriptionurl":"https://commons.wikimedia.org/wiki/File:Radiohead_in_2008.jpg","extmetadata":{"Artist":{"value":"<a href=\"//commons.wikimedia.org/wiki/User:Jane\">Jane Doe</a> &amp; friends"},
                "LicenseShortName":{"value":"CC BY-SA 2.0"},"LicenseUrl":{"value":"https://creativecommons.org/licenses/by-sa/2.0"}}}]}}}}""",
            )
        }
        answer({ "upload.wikimedia.org" in it }) { jpeg }
    }

    private fun theArticleExists() {
        theArtistExists()
        answer({ "action=wbgetentities" in it }) {
            json("""{"entities":{"Q44190":{"sitelinks":{"enwiki":{"title":"Radiohead"},"eswiki":{"title":"Radiohead"}}}}}""")
        }
        answer({ "es.wikipedia.org/api/rest_v1/page/summary/Radiohead" in it }) {
            json("""{"type":"standard","title":"Radiohead","extract":"Radiohead es una banda británica.","content_urls":{"desktop":{"page":"https://es.wikipedia.org/wiki/Radiohead"}}}""")
        }
        answer({ "en.wikipedia.org/api/rest_v1/page/summary/Radiohead" in it }) {
            json("""{"type":"standard","title":"Radiohead","extract":"Radiohead are an English rock band.","content_urls":{"desktop":{"page":"https://en.wikipedia.org/wiki/Radiohead"}}}""")
        }
    }

    // ── queries and answers ──────────────────────────────────────────────────────────────────────

    @Test
    fun `an artist search builds its query, names the project, goes over HTTPS and reads the match`() = runBlocking<Unit> {
        theArtistExists()
        val match = MusicBrainzClient(http).findArtist("Radiohead")
        assertThat(match?.id).isEqualTo("a-1")
        val url = requests.single().url
        assertThat(url.isHttps).isTrue()
        assertThat(url.encodedPath).isEqualTo("/ws/2/artist")
        assertThat(url.queryParameter("query")).isEqualTo("artist:\"Radiohead\"")
        assertThat(url.queryParameter("fmt")).isEqualTo("json")
        assertThat(requests.single().header("User-Agent")).isEqualTo(HttpUserAgent.MUSICBRAINZ)
    }

    @Test
    fun `a query escapes the characters Lucene reads as syntax`() {
        assertThat(MusicBrainzClient.escape("AC/DC")).isEqualTo("AC\\/DC")
        assertThat(MusicBrainzClient.escape("What? (live)")).isEqualTo("What\\? \\(live\\)")
    }

    @Test
    fun `a match needs the same name and a score of 90, so a similar artist is not taken for it`() {
        val json = org.json.JSONObject("""{"artists":[{"id":"x","name":"Radiohead Tribute","score":100},{"id":"y","name":"Radiohead","score":70},{"id":"z","name":"RADIOHEAD","score":95}]}""")
        assertThat(MusicBrainzClient.parseArtistSearch(json, "Radiohead")?.id).isEqualTo("z")
        assertThat(MusicBrainzClient.parseArtistSearch(org.json.JSONObject("""{"artists":[{"id":"x","name":"Other","score":100}]}"""), "Radiohead")).isNull()
        assertThat(MusicBrainzClient.parseArtistSearch(null, "Radiohead")).isNull()
    }

    @Test
    fun `the wikidata relation is read from the artist's links`() {
        val relations = org.json.JSONObject("""{"relations":[{"type":"wikidata","url":{"resource":"https://www.wikidata.org/wiki/Q44190"}}]}""")
        assertThat(MusicBrainzClient.parseWikidataRelation(relations)).isEqualTo("Q44190")
        assertThat(MusicBrainzClient.parseWikidataRelation(org.json.JSONObject("""{"relations":[]}"""))).isNull()
    }

    @Test
    fun `a Commons answer gives the thumbnail, the author as plain text and the licence`() {
        val answer = org.json.JSONObject(
            """{"query":{"pages":{"1":{"imageinfo":[{"thumburl":"https://upload.wikimedia.org/t.jpg","descriptionurl":"https://commons.wikimedia.org/wiki/File:A.jpg",
            "extmetadata":{"Artist":{"value":"<a href=\"x\">Jane Doe</a> &amp; Co"},"LicenseShortName":{"value":"CC BY 4.0"},"LicenseUrl":{"value":"https://creativecommons.org/licenses/by/4.0"}}}]}}}}""",
        )
        val image = CommonsClient.parseImageInfo(answer, "A.jpg")!!
        assertThat(image.author).isEqualTo("Jane Doe & Co")
        assertThat(image.license).isEqualTo("CC BY 4.0")
        assertThat(image.licenseUrl).isEqualTo("https://creativecommons.org/licenses/by/4.0")
        assertThat(image.pageUrl).isEqualTo("https://commons.wikimedia.org/wiki/File:A.jpg")
        // a picture on a non-HTTPS address is not taken
        assertThat(CommonsClient.parseImageInfo(org.json.JSONObject("""{"query":{"pages":{"1":{"imageinfo":[{"thumburl":"http://x/t.jpg"}]}}}}"""), "A.jpg")).isNull()
    }

    @Test
    fun `a Wikipedia summary needs text and an HTTPS page, and a disambiguation page is not a description`() {
        val ok = org.json.JSONObject("""{"title":"T","extract":" Text. ","content_urls":{"desktop":{"page":"https://en.wikipedia.org/wiki/T"}}}""")
        assertThat(WikipediaClient.parseSummary(ok, "en")?.extract).isEqualTo("Text.")
        assertThat(WikipediaClient.parseSummary(org.json.JSONObject("""{"type":"disambiguation","extract":"x","content_urls":{"desktop":{"page":"https://x"}}}"""), "en")).isNull()
        assertThat(WikipediaClient.parseSummary(org.json.JSONObject("""{"extract":"x","content_urls":{"desktop":{"page":"http://x"}}}"""), "en")).isNull()
        assertThat(WikipediaClient.parseSummary(null, "en")).isNull()
    }

    // ── the pipelines ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `an artist photo is found through MusicBrainz, Wikidata and Commons, and keeps its author and licence`() = runBlocking<Unit> {
        thePhotoExists()
        val photo = service().fetchArtistPhoto("Radiohead")!!
        assertThat(File(photo.path).readBytes()).isEqualTo(jpeg)
        assertThat(photo.author).isEqualTo("Jane Doe & friends")
        assertThat(photo.license).isEqualTo("CC BY-SA 2.0")
        assertThat(photo.licenseUrl).isEqualTo("https://creativecommons.org/licenses/by-sa/2.0")
        assertThat(photo.pageUrl).isEqualTo("https://commons.wikimedia.org/wiki/File:Radiohead_in_2008.jpg")
        assertThat(requests.map { it.url.host }).containsExactly(
            "musicbrainz.org", "musicbrainz.org", "www.wikidata.org", "commons.wikimedia.org", "upload.wikimedia.org",
        ).inOrder()
        // the attribution comes back with the picture from the cache, offline
        requests.clear()
        val again = service().cachedArtistPhoto("Radiohead")!!
        assertThat(again.author).isEqualTo("Jane Doe & friends")
        assertThat(again.license).isEqualTo("CC BY-SA 2.0")
        assertThat(requests).isEmpty()
    }

    @Test
    fun `an album cover is the front of the release the search found`() = runBlocking<Unit> {
        answer({ "musicbrainz.org/ws/2/release?" in it }) { json("""{"releases":[{"id":"r-1","title":"OK Computer","score":100}]}""") }
        answer({ "coverartarchive.org/release/r-1/front-500" in it }) { jpeg }
        val file = service().fetchCover("OK Computer", "Radiohead")!!
        assertThat(file.readBytes()).isEqualTo(jpeg)
        val query = requests.first().url.queryParameter("query")
        assertThat(query).isEqualTo("release:\"OK Computer\" AND artist:\"Radiohead\"")
        assertThat(service().cachedCover("OK Computer", "Radiohead")).isEqualTo(file)
    }

    @Test
    fun `when the edition has no cover another edition of the album is tried`() = runBlocking<Unit> {
        answer({ "musicbrainz.org/ws/2/release?" in it }) { json("""{"releases":[{"id":"r-1","title":"OK Computer","score":100}]}""") }
        answer({ "musicbrainz.org/ws/2/release-group?" in it }) { json("""{"release-groups":[{"id":"g-1","title":"OK Computer","score":100}]}""") }
        answer({ "coverartarchive.org/release-group/g-1/front-500" in it }) { jpeg }
        assertThat(service().fetchCover("OK Computer", "Radiohead")).isNotNull()
    }

    @Test
    fun `a description is the Wikipedia summary in the app's language, with its page and language`() = runBlocking<Unit> {
        theArticleExists()
        val description = service().fetchAbout(AboutKind.ARTIST, "Radiohead")!!
        assertThat(description.text).isEqualTo("Radiohead es una banda británica.")
        assertThat(description.language).isEqualTo("es")
        assertThat(description.pageUrl).isEqualTo("https://es.wikipedia.org/wiki/Radiohead")
        assertThat(service().cachedAbout(AboutKind.ARTIST, "Radiohead", null)?.pageUrl).isEqualTo("https://es.wikipedia.org/wiki/Radiohead")
    }

    @Test
    fun `without a page in the app's language the English one is used`() = runBlocking<Unit> {
        theArticleExists()
        languages = listOf("sv", "en")   // the fake has no Swedish page
        val description = service().fetchAbout(AboutKind.ARTIST, "Radiohead")!!
        assertThat(description.language).isEqualTo("en")
        assertThat(description.text).startsWith("Radiohead are")
    }

    @Test
    fun `an album description goes through the release group`() = runBlocking<Unit> {
        answer({ "musicbrainz.org/ws/2/release-group?" in it }) { json("""{"release-groups":[{"id":"g-1","title":"OK Computer","score":100}]}""") }
        answer({ "musicbrainz.org/ws/2/release-group/g-1" in it }) { json("""{"relations":[{"type":"wikidata","url":{"resource":"https://www.wikidata.org/wiki/Q211"}}]}""") }
        answer({ "action=wbgetentities" in it }) { json("""{"entities":{"Q211":{"sitelinks":{"enwiki":{"title":"OK Computer"}}}}}""") }
        answer({ "en.wikipedia.org/api/rest_v1/page/summary/OK_Computer" in it }) {
            json("""{"title":"OK Computer","extract":"OK Computer is an album.","content_urls":{"desktop":{"page":"https://en.wikipedia.org/wiki/OK_Computer"}}}""")
        }
        languages = listOf("en")
        assertThat(service().fetchAbout(AboutKind.ALBUM, "OK Computer", "Radiohead")?.text).isEqualTo("OK Computer is an album.")
    }

    // ── the opt-in gates ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `with every switch off no request is made, for any kind`() = runBlocking<Unit> {
        thePhotoExists(); theArticleExists()
        covers = false; photos = false; descriptions = false
        val s = service()
        assertThat(s.fetchArtistPhoto("Radiohead")).isNull()
        assertThat(s.fetchCover("OK Computer", "Radiohead")).isNull()
        assertThat(s.fetchAbout(AboutKind.ARTIST, "Radiohead")).isNull()
        assertThat(requests).isEmpty()
        assertThat(http.sent).isEmpty()
    }

    @Test
    fun `each switch is its own - photos on does not let a description or a cover out`() = runBlocking<Unit> {
        thePhotoExists(); theArticleExists()
        covers = false; photos = true; descriptions = false
        val s = service()
        assertThat(s.fetchAbout(AboutKind.ARTIST, "Radiohead")).isNull()
        assertThat(s.fetchCover("OK Computer", "Radiohead")).isNull()
        assertThat(requests).isEmpty()
        assertThat(s.fetchArtistPhoto("Radiohead")).isNotNull()
    }

    @Test
    fun `what was downloaded is still shown when the switch is turned off, and costs no request`() = runBlocking<Unit> {
        thePhotoExists()
        service().fetchArtistPhoto("Radiohead")
        photos = false
        requests.clear()
        assertThat(service().fetchArtistPhoto("Radiohead")).isNotNull()
        assertThat(requests).isEmpty()
    }

    // ── the rate limit ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `requests go one at a time, at least a second apart`() = runBlocking<Unit> {
        thePhotoExists()
        service().fetchArtistPhoto("Radiohead")
        assertThat(requests.size).isAtLeast(5)
        // five requests in a row were separated by waits of at least the interval minus what the work itself took (nothing, here)
        assertThat(sleeps.size).isAtLeast(4)
        assertThat(sleeps.all { it >= MusicInfoHttp.MIN_INTERVAL_MS }).isTrue()
        assertThat(MusicInfoHttp.MIN_INTERVAL_MS).isAtLeast(1_000L)
    }

    @Test
    fun `an address that is not HTTPS is refused before anything is sent`() {
        val result = runCatching { runBlocking { http.json("http://musicbrainz.org/ws/2/artist".toHttpUrlForTest()) } }
        assertThat(result.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(requests).isEmpty()
    }

    private fun String.toHttpUrlForTest() = okhttp3.HttpUrl.Builder().scheme("http").host("musicbrainz.org").addPathSegments("ws/2/artist").build()

    // ── nothing found, and no network ────────────────────────────────────────────────────────────

    @Test
    fun `a lookup that finds nothing is remembered and not repeated`() = runBlocking<Unit> {
        answer({ "musicbrainz.org/ws/2/artist?" in it }) { json("""{"artists":[]}""") }
        val s = service()
        assertThat(s.fetchArtistPhoto("Nobody")).isNull()
        val first = requests.size
        assertThat(s.fetchArtistPhoto("Nobody")).isNull()
        assertThat(requests.size).isEqualTo(first)
        // thirty days later it is asked again
        clock += MusicInfoCache.MISSING_TTL_MS + 1
        s.fetchArtistPhoto("Nobody")
        assertThat(requests.size).isGreaterThan(first)
    }

    @Test
    fun `no network is not an answer - nothing is remembered and the next try asks again`() = runBlocking<Unit> {
        thePhotoExists()
        failing += "musicbrainz.org"
        val s = service()
        assertThat(s.fetchArtistPhoto("Radiohead")).isNull()
        failing.clear()
        assertThat(s.fetchArtistPhoto("Radiohead")).isNotNull()
    }

    // ── the cache ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `over the limit the one used longest ago goes first`() {
        limit = 5_000
        val old = cache.putFile(MusicInfoCache.Kind.COVER, "old", ByteArray(2_000), "jpg", null)
        clock += 10
        val middle = cache.putFile(MusicInfoCache.Kind.COVER, "middle", ByteArray(2_000), "jpg", null)
        clock += 10
        // the old one is used again: now the middle one is the one used longest ago
        assertThat(cache.get(MusicInfoCache.Kind.COVER, "old")).isNotNull()
        clock += 10
        val newest = cache.putFile(MusicInfoCache.Kind.COVER, "newest", ByteArray(2_000), "jpg", null)
        assertThat(cache.usedBytes()).isAtMost(5_000L)
        assertThat(middle.exists()).isFalse()
        assertThat(old.exists()).isTrue()
        assertThat(newest.exists()).isTrue()
        assertThat(cache.get(MusicInfoCache.Kind.COVER, "middle")).isNull()
    }

    @Test
    fun `the cache survives a restart, and an entry whose file the system removed is gone`() {
        val file = cache.putFile(MusicInfoCache.Kind.COVER, "a", ByteArray(100), "jpg", "meta")
        cache.putText(MusicInfoCache.Kind.ABOUT, "b", "text")
        val restarted = MusicInfoCache(dir, limitBytes = { limit }, now = { clock })
        assertThat(restarted.get(MusicInfoCache.Kind.COVER, "a")?.file).isEqualTo(file)
        assertThat(restarted.get(MusicInfoCache.Kind.ABOUT, "b")?.meta).isEqualTo("text")
        file.delete()
        assertThat(MusicInfoCache(dir, limitBytes = { limit }, now = { clock }).get(MusicInfoCache.Kind.COVER, "a")).isNull()
    }

    @Test
    fun `delete downloaded data removes every file and the index, and says what it freed`() {
        cache.putFile(MusicInfoCache.Kind.COVER, "a", ByteArray(1_000), "jpg", null)
        cache.putFile(MusicInfoCache.Kind.ARTIST_PHOTO, "b", ByteArray(500), "jpg", null)
        cache.putText(MusicInfoCache.Kind.ABOUT, "c", "some text")
        assertThat(cache.clear()).isAtLeast(1_500L)
        assertThat(cache.usedBytes()).isEqualTo(0L)
        assertThat(dir.exists()).isFalse()
        assertThat(cache.get(MusicInfoCache.Kind.COVER, "a")).isNull()
    }

    @Test
    fun `the cache knows which paths are its own, so a stored artist picture can be told from the listener's`() {
        val file = cache.putFile(MusicInfoCache.Kind.ARTIST_PHOTO, "a", ByteArray(10), "jpg", null)
        assertThat(cache.owns(file.absolutePath)).isTrue()
        assertThat(cache.owns("/storage/emulated/0/Pictures/mine.jpg")).isFalse()
        assertThat(cache.owns(null)).isFalse()
    }

    // ── Deezer is gone ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `no Deezer request is left in the code - no address, no client, no qualifier, no switch`() {
        val main = File("src/main")
        val hits = main.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "kts" || it.extension == "xml") }
            .filter { !it.path.contains("/build/") }
            .flatMap { file ->
                val text = file.readText()
                listOf("api.deezer.com", "dzcdn.net", "DeezerApiService", "DeezerRetrofit", "DeezerSearchResponse", "externalArtistImagesEnabled")
                    .filter { it in text }.map { "${file.name}: $it" }
            }.toList()
        assertThat(hits).isEmpty()
        assertThat(File("src/main/java/com/lostf1sh/pixelplayeross/data/network/deezer").exists()).isFalse()
    }
}
