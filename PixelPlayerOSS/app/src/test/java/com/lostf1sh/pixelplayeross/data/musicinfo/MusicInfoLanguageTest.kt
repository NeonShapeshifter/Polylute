package com.lostf1sh.pixelplayeross.data.musicinfo

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * B40-fix1 (item 7): a description is kept per language. `aboutKey` used to ignore its `language`, so a Spanish summary cached first was
 * returned after the app was switched to Swedish. Now the key carries the language; what is shown is the first language of the app's list
 * that is on the phone; a language preferred over it that is neither cached nor known to be missing is asked for first; a "missing" mark
 * is for its own language; and entries written before the key had a language are still read by the language stored in them.
 */
class MusicInfoLanguageTest {
    private val dir: File = Files.createTempDirectory("music-info-language").toFile()
    private val requests = mutableListOf<Request>()
    private var languages = listOf("es", "en")
    private var clock = 0L
    private var swedishPageExists = true

    @AfterEach
    fun cleanUp() { dir.deleteRecursively() }

    private fun summary(language: String, text: String) =
        """{"type":"standard","title":"Radiohead","extract":"$text","content_urls":{"desktop":{"page":"https://$language.wikipedia.org/wiki/Radiohead"}}}"""

    private val client = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        requests += request
        val url = request.url.toString()
        val body = when {
            "musicbrainz.org/ws/2/artist?" in url -> """{"artists":[{"id":"a-1","name":"Radiohead","score":100}]}"""
            "musicbrainz.org/ws/2/artist/a-1" in url ->
                """{"relations":[{"type":"wikidata","url":{"resource":"https://www.wikidata.org/wiki/Q44190"}}]}"""
            "action=wbgetentities" in url -> {
                val sv = if (swedishPageExists) ""","svwiki":{"title":"Radiohead"}""" else ""
                """{"entities":{"Q44190":{"sitelinks":{"enwiki":{"title":"Radiohead"},"eswiki":{"title":"Radiohead"}$sv}}}}"""
            }
            "es.wikipedia.org/api/rest_v1/page/summary/Radiohead" in url -> summary("es", "Radiohead es una banda británica.")
            "sv.wikipedia.org/api/rest_v1/page/summary/Radiohead" in url -> summary("sv", "Radiohead är ett brittiskt band.")
            "en.wikipedia.org/api/rest_v1/page/summary/Radiohead" in url -> summary("en", "Radiohead are an English rock band.")
            else -> null
        }
        val builder = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
        if (body == null) builder.code(404).message("Not Found").body("".toResponseBody("application/json".toMediaType())).build()
        else builder.code(200).message("OK").body(body.toResponseBody("application/json".toMediaType())).build()
    }.build()

    private val http = MusicInfoHttp(client, nowMs = { clock }, sleep = { clock += it })
    private val cache = MusicInfoCache(dir, limitBytes = { 10L * 1024 * 1024 }, now = { clock })

    private fun service() = MusicInfoService(
        cache = cache, brainz = MusicBrainzClient(http), coverArt = CoverArtArchiveClient(http), wikidata = WikidataClient(http),
        commons = CommonsClient(http), wikipedia = WikipediaClient(http),
        artistPhotosEnabled = { true }, coversEnabled = { true }, descriptionsEnabled = { true }, languages = { languages },
    )

    private fun wikipediaHosts() = requests.map { it.url.host }.filter { it.endsWith("wikipedia.org") }

    @Test
    fun `a Spanish description cached first is not shown after the app is switched to Swedish`() = runBlocking<Unit> {
        val s = service()
        assertThat(s.fetchAbout(AboutKind.ARTIST, "Radiohead")?.language).isEqualTo("es")
        languages = listOf("sv", "en")
        // Spanish is not one of the app's languages now, and English was never downloaded: nothing on the phone to show
        assertThat(s.cachedAbout(AboutKind.ARTIST, "Radiohead", null)).isNull()
        requests.clear()
        val swedish = s.fetchAbout(AboutKind.ARTIST, "Radiohead")!!
        assertThat(swedish.language).isEqualTo("sv")
        assertThat(swedish.text).isEqualTo("Radiohead är ett brittiskt band.")
        assertThat(wikipediaHosts()).contains("sv.wikipedia.org")
    }

    @Test
    fun `switching back finds the Spanish description on the phone and makes no request`() = runBlocking<Unit> {
        val s = service()
        s.fetchAbout(AboutKind.ARTIST, "Radiohead")
        languages = listOf("sv", "en")
        s.fetchAbout(AboutKind.ARTIST, "Radiohead")
        languages = listOf("es", "en")
        requests.clear()
        val back = s.fetchAbout(AboutKind.ARTIST, "Radiohead")!!
        assertThat(back.language).isEqualTo("es")
        assertThat(back.text).startsWith("Radiohead es una banda")
        assertThat(requests).isEmpty()
        assertThat(s.cachedAbout(AboutKind.ARTIST, "Radiohead", null)?.language).isEqualTo("es")
    }

    @Test
    fun `without a Swedish page the English one is used, and the Swedish miss is remembered so it is not asked again`() = runBlocking<Unit> {
        swedishPageExists = false
        languages = listOf("sv", "en")
        val s = service()
        assertThat(s.fetchAbout(AboutKind.ARTIST, "Radiohead")?.language).isEqualTo("en")
        requests.clear()
        assertThat(s.fetchAbout(AboutKind.ARTIST, "Radiohead")?.language).isEqualTo("en")
        assertThat(requests).isEmpty()
    }

    @Test
    fun `a missing mark is for its own language, so a Swedish miss does not hide the Spanish page`() = runBlocking<Unit> {
        swedishPageExists = false
        languages = listOf("sv")
        val s = service()
        assertThat(s.fetchAbout(AboutKind.ARTIST, "Radiohead")).isNull()
        requests.clear()
        assertThat(s.fetchAbout(AboutKind.ARTIST, "Radiohead")).isNull()
        assertThat(requests).isEmpty()
        languages = listOf("es", "en")
        assertThat(s.fetchAbout(AboutKind.ARTIST, "Radiohead")?.language).isEqualTo("es")
    }

    @Test
    fun `an entry written before the key had a language is still read by the language stored in it`() = runBlocking<Unit> {
        val legacy = MusicInfoService.aboutKey(AboutKind.ARTIST, "Radiohead", null)
        cache.putText(
            MusicInfoCache.Kind.ABOUT, legacy,
            JSONObject().put("text", "Radiohead es una banda británica.").put("title", "Radiohead")
                .put("pageUrl", "https://es.wikipedia.org/wiki/Radiohead").put("language", "es").toString(),
        )
        val s = service()
        // the app is in Spanish: what was downloaded is shown, and nothing is asked
        assertThat(s.cachedAbout(AboutKind.ARTIST, "Radiohead", null)?.language).isEqualTo("es")
        assertThat(s.fetchAbout(AboutKind.ARTIST, "Radiohead")?.language).isEqualTo("es")
        assertThat(requests).isEmpty()
        // in Swedish it is not shown, and Swedish is asked for
        languages = listOf("sv", "en")
        assertThat(s.cachedAbout(AboutKind.ARTIST, "Radiohead", null)).isNull()
        assertThat(s.fetchAbout(AboutKind.ARTIST, "Radiohead")?.language).isEqualTo("sv")
    }

    @Test
    fun `the key carries the language`() {
        val es = MusicInfoService.aboutKey(AboutKind.ARTIST, "Radiohead", null, "es")
        val sv = MusicInfoService.aboutKey(AboutKind.ARTIST, "Radiohead", null, "sv")
        assertThat(es).isNotEqualTo(sv)
        assertThat(MusicInfoService.aboutKey(AboutKind.ARTIST, "Radiohead", null)).isNotEqualTo(es)
    }
}
