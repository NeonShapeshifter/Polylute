package com.lostf1sh.pixelplayeross.data.musicinfo

import android.app.Application
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.database.*
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.network.lyrics.LrcLibApiService
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.repository.LyricsRepositoryImpl
import io.mockk.*
import java.io.*
import java.nio.file.Files
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class B43ManualInfoTest {
    private val dir = Files.createTempDirectory("b43-manual").toFile()
    private val cache = MusicInfoCache(dir, { 0L })
    private fun png(color: Int) = ByteArrayOutputStream().also { out -> Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply {
        eraseColor(color); compress(Bitmap.CompressFormat.PNG, 100, out); recycle()
    } }.toByteArray()
    private val old = png(android.graphics.Color.BLUE)
    private val fresh = png(android.graphics.Color.RED)
    private var image = fresh
    private var failImage = false
    private val requests = mutableListOf<String>()
    private val client = OkHttpClient.Builder().addInterceptor { chain ->
        val url = chain.request().url; requests += url.toString()
        if (failImage && url.host == "upload.wikimedia.org") throw IOException("offline")
        val body = when {
            url.encodedPath == "/ws/2/artist" -> """{"artists":[{"id":"a","name":"A","score":100}]}""".toByteArray()
            url.encodedPath == "/ws/2/artist/a" -> """{"annotation":"Improved description","relations":[{"type":"image","url":{"resource":"https://commons.wikimedia.org/wiki/File:A.png"}}]}""".toByteArray()
            url.host == "commons.wikimedia.org" -> """{"query":{"pages":{"1":{"imageinfo":[{"thumburl":"https://upload.wikimedia.org/a.png","descriptionurl":"https://commons.wikimedia.org/wiki/File:A.png"}]}}}}""".toByteArray()
            url.host == "upload.wikimedia.org" -> image
            else -> null
        }
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(if (body == null) 404 else 200).message("Fixture")
            .body((body ?: byteArrayOf()).toResponseBody((if (url.host == "upload.wikimedia.org") "image/png" else "application/json").toMediaType())).build()
    }.build()
    private fun service(): MusicInfoService {
        val http = MusicInfoHttp(client, minIntervalMs = 0, nowMs = { 0L }, sleep = {})
        return MusicInfoService(cache, MusicBrainzClient(http), CoverArtArchiveClient(http), WikidataClient(http), CommonsClient(http),
            WikipediaClient(http), { false }, { false }, { false }, { listOf("en") })
    }
    @Test fun `refresh persists the new decoded photo reference for subsequent page visits`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val dao = mockk<MusicDao>(relaxed = true)
        coEvery { dao.getArtistIdByNormalizedName("A") } returns 7L
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val info = MusicInfoRepository(context, mockk(relaxed = true), client, dao, scope)
        try {
            val previous = info.cache.putFile(MusicInfoCache.Kind.ARTIST_PHOTO, MusicInfoService.photoKey("A"), old, "png", null)
            info.refreshArtistInfo("A")
            val photo = info.service.cachedArtistPhoto("A")!!
            assertThat(File(photo.path).readBytes()).isEqualTo(fresh)
            assertThat(photo.path).isNotEqualTo(previous.absolutePath)
            coVerify { dao.updateArtistImageUrl(7L, photo.path) }
        } finally { scope.cancel(); File(context.filesDir, MusicInfoRepository.FOLDER).deleteRecursively() }
    }
    @Test fun `refresh revisions stay counted and scoped deletion removes every retained revision`() {
        val key = MusicInfoService.photoKey("A")
        val previous = cache.putFile(MusicInfoCache.Kind.ARTIST_PHOTO, key, old, "png", null)
        val current = cache.putFile(MusicInfoCache.Kind.ARTIST_PHOTO, key, fresh, "png", null)
        assertThat(previous.exists()).isTrue()
        assertThat(cache.usedBytes()).isEqualTo(old.size.toLong() + fresh.size)
        cache.remove(MusicInfoCache.Kind.ARTIST_PHOTO, key)
        assertThat(previous.exists() || current.exists()).isFalse()
        assertThat(cache.usedBytes()).isEqualTo(0)
    }
    @After fun clean() { dir.deleteRecursively() }
    @Test fun `manual refresh bypasses a good cached image and missing description marks with automatic switches off`() = runBlocking {
        cache.putFile(MusicInfoCache.Kind.ARTIST_PHOTO, MusicInfoService.photoKey("A"), old, "png", null)
        cache.putMissing(MusicInfoCache.Kind.ABOUT, MusicInfoService.aboutKey(AboutKind.ARTIST, "A", null, "en"))
        val service = service()
        service.refreshArtist("A")
        assertThat(File(service.cachedArtistPhoto("A")!!.path).readBytes()).isEqualTo(fresh)
        assertThat(service.cachedAbout(AboutKind.ARTIST, "A", null)?.text).isEqualTo("Improved description")
        assertThat(requests.any { "upload.wikimedia.org" in it }).isTrue()
    }
    @Test fun `an undecodable replacement or a failed request keeps the original image`() = runBlocking {
        val file = cache.putFile(MusicInfoCache.Kind.ARTIST_PHOTO, MusicInfoService.photoKey("A"), old, "png", null)
        val service = service()
        image = "<html/>".toByteArray()
        service.refreshArtist("A")
        assertThat(service.cachedArtistPhoto("A")!!.path).isEqualTo(file.absolutePath)
        assertThat(file.readBytes()).isEqualTo(old)
        failImage = true
        service.refreshArtist("A")
        assertThat(file.readBytes()).isEqualTo(old)
    }
    @Test fun `album deletion removes downloaded cover description and remote lyrics while preserving embedded art and local lrc`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val dao = mockk<MusicDao>(relaxed = true)
        val lyricsDao = mockk<LyricsDao>(relaxed = true)
        coEvery { lyricsDao.getLyricsResolvedByIdentity(1L) } returns LyricsEntity(1L, "remote words", false, "remote")
        coEvery { lyricsDao.getLyricsResolvedByIdentity(2L) } returns LyricsEntity(2L, "local words", false, "sidecar")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val prefs = mockk<UserPreferencesRepository>(relaxed = true)
        val info = MusicInfoRepository(context, prefs, client, dao, scope)
        val lyrics = LyricsRepositoryImpl(context, mockk<LrcLibApiService>(), lyricsDao, client, prefs)
        val song = Song.emptySong().copy(id = "1", album = "Album", artist = "A", path = File(dir, "one.flac").path)
        val local = File(dir, "one.lrc").apply { writeText("local sidecar") }
        val embedded = File(dir, "embedded.jpg").apply { writeBytes(old) }
        val json = File(context.filesDir, "lyrics/1.json").apply { parentFile.mkdirs(); writeText("downloaded") }
        val cover = info.cache.putFile(MusicInfoCache.Kind.COVER, MusicInfoService.coverKey("Album", "A"), old, "png", null)
        info.cache.putText(MusicInfoCache.Kind.ABOUT, MusicInfoService.aboutKey(AboutKind.ALBUM, "Album", "A", "en"),
            """{"text":"About album","title":"Album","language":"en","pageUrl":"https://en.wikipedia.org/wiki/Album"}""")
        try {
            info.deleteAlbumInfo("Album", "A", listOf("1"))
            lyrics.deleteDownloadedLyrics(song)
            lyrics.deleteDownloadedLyrics(song.copy(id = "2"))
            assertThat(cover.exists()).isFalse()
            assertThat(info.service.cachedAbout(AboutKind.ALBUM, "Album", "A")).isNull()
            assertThat(json.exists()).isFalse()
            coVerify(exactly = 1) { lyricsDao.deleteLyrics(1L) }
            coVerify(exactly = 0) { lyricsDao.deleteLyrics(2L) }
            assertThat(local.readText()).isEqualTo("local sidecar")
            assertThat(embedded.readBytes()).isEqualTo(old)
        } finally { scope.cancel(); File(context.filesDir, MusicInfoRepository.FOLDER).deleteRecursively() }
    }
}
