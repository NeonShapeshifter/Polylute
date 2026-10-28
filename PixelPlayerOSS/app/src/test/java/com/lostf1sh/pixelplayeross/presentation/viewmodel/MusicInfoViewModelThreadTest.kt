package com.lostf1sh.pixelplayeross.presentation.viewmodel

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.Album
import com.lostf1sh.pixelplayeross.data.model.Artist
import com.lostf1sh.pixelplayeross.data.musicinfo.MusicBrainzClient
import com.lostf1sh.pixelplayeross.data.musicinfo.MusicInfoCache
import com.lostf1sh.pixelplayeross.data.musicinfo.MusicInfoHttp
import com.lostf1sh.pixelplayeross.data.musicinfo.MusicInfoRepository
import com.lostf1sh.pixelplayeross.data.musicinfo.MusicInfoService
import com.lostf1sh.pixelplayeross.data.musicinfo.CommonsClient
import com.lostf1sh.pixelplayeross.data.musicinfo.CoverArtArchiveClient
import com.lostf1sh.pixelplayeross.data.musicinfo.WikidataClient
import com.lostf1sh.pixelplayeross.data.musicinfo.WikipediaClient
import com.lostf1sh.pixelplayeross.data.repository.ArtistImageRepository
import com.lostf1sh.pixelplayeross.data.repository.MusicRepository
import com.lostf1sh.pixelplayeross.presentation.components.MusicInfoAboutState
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.net.URL
import java.util.Collections
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import timber.log.Timber

/**
 * B40-fix1 (item 3), through the two real view models: the description is asked for from `viewModelScope`, whose dispatcher is the main
 * looper (Robolectric's test thread is it). The fake server records whether each request arrived on the main looper; none may, and the
 * description must still reach the screen. A `NetworkOnMainThreadException` that does reach the service is logged as a programming
 * error, not as "no answer".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MusicInfoViewModelThreadTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val onMain = Collections.synchronizedList(mutableListOf<Boolean>())
    private lateinit var dir: File
    private var throwOnMain: Throwable? = null

    @Before
    fun setUp() {
        dir = File(context.cacheDir, "music-info-vm-threads").apply { deleteRecursively(); mkdirs() }
    }

    @After
    fun tearDown() {
        Timber.uprootAll()
        dir.deleteRecursively()
    }

    private val client = OkHttpClient.Builder().addInterceptor { chain ->
        val isMain = Looper.myLooper() == Looper.getMainLooper()
        onMain += isMain
        throwOnMain?.let { if (isMain) throw it }
        val url = chain.request().url.toString()
        val body = when {
            "musicbrainz.org/ws/2/artist?" in url -> """{"artists":[{"id":"a-1","name":"Fixture","score":100}]}"""
            "musicbrainz.org/ws/2/release-group?" in url -> """{"release-groups":[{"id":"g-1","title":"Fixture Album","score":100}]}"""
            "musicbrainz.org/ws/2/" in url ->
                """{"relations":[{"type":"wikidata","url":{"resource":"https://www.wikidata.org/wiki/Q1"}}]}"""
            "action=wbgetentities" in url -> """{"entities":{"Q1":{"sitelinks":{"enwiki":{"title":"Fixture"}}}}}"""
            "en.wikipedia.org" in url ->
                """{"type":"standard","title":"Fixture","extract":"Fixture description","content_urls":{"desktop":{"page":"https://en.wikipedia.org/wiki/Fixture"}}}"""
            else -> null
        }
        val builder = Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
        if (body == null) builder.code(404).message("Not Found").body("".toResponseBody("application/json".toMediaType())).build()
        else builder.code(200).message("OK").body(body.toResponseBody("application/json".toMediaType())).build()
    }.build()

    private fun service(): MusicInfoService {
        val http = MusicInfoHttp(client, minIntervalMs = 0, nowMs = { 0L }, sleep = { })
        return MusicInfoService(
            MusicInfoCache(dir, { 10L * 1024 * 1024 }, { 0L }), MusicBrainzClient(http), CoverArtArchiveClient(http), WikidataClient(http),
            CommonsClient(http), WikipediaClient(http), { false }, { false }, { true }, { listOf("en") },
        )
    }

    private fun repository(service: MusicInfoService): MusicInfoRepository = mockk(relaxed = true) { every { this@mockk.service } returns service }

    private fun waitFor(about: kotlinx.coroutines.flow.StateFlow<MusicInfoAboutState>) {
        val deadline = System.currentTimeMillis() + 8_000
        while (about.value.description == null && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
    }

    @Test
    fun the_album_page_asks_for_its_description_off_the_main_thread_and_still_gets_it() {
        val musicRepository = mockk<MusicRepository>(relaxed = true)
        val album = Album(id = 1L, title = "Fixture Album", artist = "Fixture", year = 2000, dateAdded = 0L, albumArtUriString = null, songCount = 1)
        every { musicRepository.getAlbumById(1L) } returns flowOf(album)
        every { musicRepository.getSongsForAlbum(1L) } returns flowOf(emptyList())
        val model = AlbumDetailViewModel(context, musicRepository, repository(service()), SavedStateHandle(mapOf("albumId" to "1")))
        waitFor(model.about)
        assertThat(model.about.value.description?.text).isEqualTo("Fixture description")
        assertThat(onMain).isNotEmpty()
        assertThat(onMain).doesNotContain(true)
    }

    @Test
    fun the_artist_page_asks_for_its_description_off_the_main_thread_and_still_gets_it() {
        val musicRepository = mockk<MusicRepository>(relaxed = true)
        every { musicRepository.getArtistById(7L) } returns flowOf(Artist(id = 7L, name = "Fixture", songCount = 1))
        every { musicRepository.getSongsForArtist(7L) } returns flowOf(emptyList())
        val images = mockk<ArtistImageRepository>(relaxed = true)
        coEvery { images.getEffectiveArtistImageUrl(any(), any()) } returns null
        val model = ArtistDetailViewModel(
            context, musicRepository, images, repository(service()), mockk(relaxed = true), SavedStateHandle(mapOf("artistId" to "7")),
        )
        waitFor(model.about)
        assertThat(model.about.value.description?.text).isEqualTo("Fixture description")
        assertThat(onMain).isNotEmpty()
        assertThat(onMain).doesNotContain(true)
    }

    @Test
    fun a_main_thread_violation_that_does_reach_the_service_is_logged_as_a_programming_error_not_as_no_answer() = kotlinx.coroutines.runBlocking<Unit> {
        // the service's own dispatcher is replaced by the caller's thread here, so the request does arrive on the main looper
        val http = MusicInfoHttp(client, minIntervalMs = 0, nowMs = { 0L }, sleep = { })
        val onCaller = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) = block.run()
        }
        val sut = MusicInfoService(
            MusicInfoCache(dir, { 10L * 1024 * 1024 }, { 0L }), MusicBrainzClient(http), CoverArtArchiveClient(http), WikidataClient(http),
            CommonsClient(http), WikipediaClient(http), { false }, { false }, { true }, { listOf("en") }, onCaller,
        )
        throwOnMain = android.os.NetworkOnMainThreadException()
        val lines = mutableListOf<Pair<Int, String>>()
        Timber.plant(object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) { lines += priority to message }
        })
        assertThat(sut.fetchAbout(com.lostf1sh.pixelplayeross.data.musicinfo.AboutKind.ARTIST, "Fixture")).isNull()
        assertThat(lines.any { (priority, message) -> priority == android.util.Log.ERROR && "PROGRAMMING ERROR" in message }).isTrue()
    }
}
