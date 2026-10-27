package com.lostf1sh.pixelplayeross.presentation.viewmodel

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.Album
import com.lostf1sh.pixelplayeross.data.model.Artist
import com.lostf1sh.pixelplayeross.data.musicinfo.*
import com.lostf1sh.pixelplayeross.data.repository.ArtistImageRepository
import com.lostf1sh.pixelplayeross.data.repository.MusicRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.util.Collections
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * B40-fix2 item 3: an English fallback in the cache does not stop the screens from asking for the app's language.
 * `an_album_page_upgrades_a_cached_english_fallback_to_the_requested_language` is the outside review's case of B40-fix1 (`ReviewB40Fix1Test`),
 * ported as it came: it failed on the fix1 code, where both view models fetched only when nothing at all was cached. The screens show what
 * is cached at once and then always ask the service, which decides: it asks for a language it prefers over what is shown unless that
 * language is marked missing for the item or the switch is off. Both view models, over the real service and a fake server.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class B40Fix2AboutUpgradeTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var dir: File
    private val requests = Collections.synchronizedList(mutableListOf<String>())
    private lateinit var cache: MusicInfoCache

    /** While set, the fake server holds every answer until the test lets go: what the screen shows meanwhile is what is on the phone. */
    @Volatile
    private var held: java.util.concurrent.CountDownLatch? = null

    @Before
    fun setUp() {
        dir = File(context.cacheDir, "fix2-about-upgrade").apply { deleteRecursively(); mkdirs() }
        cache = MusicInfoCache(dir, { 100000L })
        requests.clear()
    }

    @After
    fun tearDown() { dir.deleteRecursively() }

    private fun cachedIn(kind: AboutKind, name: String, artist: String?, language: String, text: String) {
        cache.putText(
            MusicInfoCache.Kind.ABOUT, MusicInfoService.aboutKey(kind, name, artist, language),
            JSONObject().put("text", text).put("title", name).put("language", language)
                .put("pageUrl", "https://$language.wikipedia.org/wiki/Fixture").toString(),
        )
    }

    private fun service(descriptions: Boolean = true, languages: List<String> = listOf("sv", "en")): MusicInfoService {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url.toString()
            requests += url
            held?.await(5, java.util.concurrent.TimeUnit.SECONDS)
            val body = when {
                "release-group?" in url -> """{"release-groups":[{"id":"g1","title":"Fixture Album","score":100}]}"""
                "artist?" in url -> """{"artists":[{"id":"a1","name":"Fixture","score":100}]}"""
                "musicbrainz.org" in url -> """{"relations":[{"type":"wikidata","url":{"resource":"https://www.wikidata.org/wiki/Q1"}}]}"""
                "wikidata.org" in url -> """{"entities":{"Q1":{"sitelinks":{"svwiki":{"title":"Fixture"}}}}}"""
                else -> """{"title":"Fixture","extract":"Swedish description","content_urls":{"desktop":{"page":"https://sv.wikipedia.org/wiki/Fixture"}}}"""
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody()).build()
        }.build()
        val http = MusicInfoHttp(client, minIntervalMs = 0, nowMs = { 0L })
        return MusicInfoService(
            cache, MusicBrainzClient(http), CoverArtArchiveClient(http), WikidataClient(http), CommonsClient(http),
            WikipediaClient(http), { false }, { false }, { descriptions }, { languages },
        )
    }

    private fun info(service: MusicInfoService): MusicInfoRepository = mockk { every { this@mockk.service } returns service }

    private fun album(service: MusicInfoService): AlbumDetailViewModel {
        val music = mockk<MusicRepository>(relaxed = true)
        every { music.getAlbumById(1L) } returns flowOf(Album(1L, "Fixture Album", "Fixture", 2000, 0L, null, 1))
        every { music.getSongsForAlbum(1L) } returns flowOf(emptyList())
        return AlbumDetailViewModel(context, music, info(service), SavedStateHandle(mapOf("albumId" to "1")))
    }

    private fun artist(service: MusicInfoService): ArtistDetailViewModel {
        val music = mockk<MusicRepository>(relaxed = true)
        every { music.getArtistById(7L) } returns flowOf(Artist(id = 7L, name = "Fixture", songCount = 1))
        every { music.getSongsForArtist(7L) } returns flowOf(emptyList())
        val images = mockk<ArtistImageRepository>(relaxed = true)
        coEvery { images.getEffectiveArtistImageUrl(any(), any()) } returns null
        return ArtistDetailViewModel(context, music, images, info(service), mockk(relaxed = true), SavedStateHandle(mapOf("artistId" to "7")))
    }

    /** Lets the view model and the service's IO work settle: waits for [done], or a short time for nothing to happen. */
    private fun settle(about: StateFlow<com.lostf1sh.pixelplayeross.presentation.components.MusicInfoAboutState>, done: () -> Boolean = { false }) {
        val end = System.currentTimeMillis() + 1500L
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            if (done()) break
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun wikipediaRequests() = requests.filter { "wikipedia.org" in it || "wikidata.org" in it || "musicbrainz.org" in it }

    // ── the review's case, ported as it came ─────────────────────────────────────────────────────

    @Test
    fun an_album_page_upgrades_a_cached_english_fallback_to_the_requested_language() {
        val dir = File(context.cacheDir, "fix1-review-language").apply { deleteRecursively(); mkdirs() }
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val cache = MusicInfoCache(dir, { 100000L })
        cache.putText(MusicInfoCache.Kind.ABOUT, MusicInfoService.aboutKey(AboutKind.ALBUM, "Fixture Album", "Fixture", "en"),
            JSONObject().put("text", "English fallback").put("title", "Fixture Album").put("language", "en")
                .put("pageUrl", "https://en.wikipedia.org/wiki/Fixture").toString())
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url.toString()
            requests += url
            val body = when {
                "release-group?" in url -> """{"release-groups":[{"id":"g1","title":"Fixture Album","score":100}]}"""
                "musicbrainz.org" in url -> """{"relations":[{"type":"wikidata","url":{"resource":"https://www.wikidata.org/wiki/Q1"}}]}"""
                "wikidata.org" in url -> """{"entities":{"Q1":{"sitelinks":{"svwiki":{"title":"Fixture"}}}}}"""
                else -> """{"title":"Fixture","extract":"Swedish description","content_urls":{"desktop":{"page":"https://sv.wikipedia.org/wiki/Fixture"}}}"""
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody()).build()
        }.build()
        val http = MusicInfoHttp(client, minIntervalMs = 0, nowMs = { 0L })
        val service = MusicInfoService(cache, MusicBrainzClient(http), CoverArtArchiveClient(http), WikidataClient(http), CommonsClient(http),
            WikipediaClient(http), { false }, { false }, { true }, { listOf("sv", "en") })
        val info = mockk<MusicInfoRepository> { every { this@mockk.service } returns service }
        val music = mockk<MusicRepository>(relaxed = true)
        every { music.getAlbumById(1L) } returns flowOf(Album(1L, "Fixture Album", "Fixture", 2000, 0L, null, 1))
        every { music.getSongsForAlbum(1L) } returns flowOf(emptyList())
        try {
            val model = AlbumDetailViewModel(context, music, info, SavedStateHandle(mapOf("albumId" to "1")))
            val end = System.currentTimeMillis() + 1500L
            while (System.currentTimeMillis() < end) {
                shadowOf(Looper.getMainLooper()).idle()
                if (model.about.value.description?.language == "sv") break
                Thread.sleep(10)
            }
            assertThat(model.about.value.description?.language).isEqualTo("sv")
            assertThat(requests.any { "sv.wikipedia.org" in it }).isTrue()
        } finally { dir.deleteRecursively() }
    }

    // ── the same, for both pages ─────────────────────────────────────────────────────────────────

    @Test
    fun the_album_page_shows_the_cached_english_at_once_and_then_swedish() {
        cachedIn(AboutKind.ALBUM, "Fixture Album", "Fixture", "en", "English fallback")
        held = java.util.concurrent.CountDownLatch(1)
        val model = album(service())
        // while the network is still being asked, the screen already has what is on the phone (offline too)
        settle(model.about) { model.about.value.description != null }
        assertThat(model.about.value.description?.language).isEqualTo("en")
        assertThat(model.about.value.description?.text).isEqualTo("English fallback")
        held!!.countDown()
        settle(model.about) { model.about.value.description?.language == "sv" }
        assertThat(model.about.value.description?.language).isEqualTo("sv")
        assertThat(model.about.value.description?.text).isEqualTo("Swedish description")
    }

    @Test
    fun the_artist_page_upgrades_a_cached_english_fallback_to_swedish() {
        cachedIn(AboutKind.ARTIST, "Fixture", null, "en", "English fallback")
        val model = artist(service())
        settle(model.about) { model.about.value.description?.language == "sv" }
        assertThat(model.about.value.description?.language).isEqualTo("sv")
        assertThat(requests.any { "sv.wikipedia.org" in it }).isTrue()
    }

    @Test
    fun with_swedish_marked_missing_for_the_item_nothing_is_asked_and_english_stays_on_both_pages() {
        cachedIn(AboutKind.ALBUM, "Fixture Album", "Fixture", "en", "English fallback")
        cachedIn(AboutKind.ARTIST, "Fixture", null, "en", "English fallback")
        cache.putMissing(MusicInfoCache.Kind.ABOUT, MusicInfoService.aboutKey(AboutKind.ALBUM, "Fixture Album", "Fixture", "sv"))
        cache.putMissing(MusicInfoCache.Kind.ABOUT, MusicInfoService.aboutKey(AboutKind.ARTIST, "Fixture", null, "sv"))
        val albumModel = album(service())
        settle(albumModel.about)
        val artistModel = artist(service())
        settle(artistModel.about)
        assertThat(wikipediaRequests()).isEmpty()
        assertThat(albumModel.about.value.description?.language).isEqualTo("en")
        assertThat(artistModel.about.value.description?.language).isEqualTo("en")
    }

    @Test
    fun with_the_switch_off_nothing_is_asked_and_the_cached_english_is_shown_on_both_pages() {
        cachedIn(AboutKind.ALBUM, "Fixture Album", "Fixture", "en", "English fallback")
        cachedIn(AboutKind.ARTIST, "Fixture", null, "en", "English fallback")
        val albumModel = album(service(descriptions = false))
        settle(albumModel.about)
        val artistModel = artist(service(descriptions = false))
        settle(artistModel.about)
        assertThat(requests).isEmpty()
        assertThat(albumModel.about.value.description?.language).isEqualTo("en")
        assertThat(artistModel.about.value.description?.language).isEqualTo("en")
    }

    @Test
    fun a_description_already_in_the_apps_language_is_shown_without_a_request() {
        cachedIn(AboutKind.ALBUM, "Fixture Album", "Fixture", "sv", "Redan på svenska")
        cachedIn(AboutKind.ARTIST, "Fixture", null, "sv", "Redan på svenska")
        val albumModel = album(service())
        settle(albumModel.about)
        val artistModel = artist(service())
        settle(artistModel.about)
        assertThat(requests).isEmpty()
        assertThat(albumModel.about.value.description?.text).isEqualTo("Redan på svenska")
        assertThat(artistModel.about.value.description?.text).isEqualTo("Redan på svenska")
    }

    @Test
    fun the_view_models_no_longer_decide_when_to_ask() {
        for (name in listOf("AlbumDetailViewModel", "ArtistDetailViewModel")) {
            val source = File("src/main/java/com/lostf1sh/pixelplayeross/presentation/viewmodel/$name.kt").readText().replace("\r\n", "\n")
            val about = source.substringAfter("private fun loadAbout(").substringBefore("private val _artistColorScheme").substringBefore("\n    init {")
            assertThat(about).doesNotContain("description == null")
            assertThat(about).contains("service.fetchAbout(")
        }
    }
}
