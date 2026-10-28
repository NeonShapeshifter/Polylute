package com.lostf1sh.pixelplayeross.data.musicinfo

import android.app.Application
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.database.MusicDao
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.repository.ArtistImageRepository
import io.mockk.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class B43ImagesTest {
    private val dir = Files.createTempDirectory("b43-images").toFile()
    private val cache = MusicInfoCache(dir, { 0L })
    private val png = ByteArrayOutputStream().also { stream ->
        Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.BLUE); compress(Bitmap.CompressFormat.PNG, 100, stream); recycle()
        }
    }.toByteArray()
    private val requests = mutableListOf<HttpUrl>()
    private var relation = "https://commons.wikimedia.org/wiki/File:A_photo.png"
    private var thumbnail = png
    private val client = OkHttpClient.Builder().addInterceptor { chain ->
        val url = chain.request().url; requests += url
        val body = when {
            url.encodedPath == "/ws/2/artist" -> """{"artists":[{"id":"a","name":"A","score":100}]}""".toByteArray()
            url.encodedPath == "/ws/2/artist/a" -> """{"relations":[{"type":"image","url":{"resource":"$relation"}},{"type":"wikidata","url":{"resource":"https://www.wikidata.org/wiki/Q1"}}]}""".toByteArray()
            url.host == "commons.wikimedia.org" && url.encodedPath == "/w/api.php" -> """{"query":{"pages":{"1":{"imageinfo":[{"thumburl":"https://upload.wikimedia.org/thumb/a.png","descriptionurl":"https://commons.wikimedia.org/wiki/File:A_photo.png","extmetadata":{"Artist":{"value":"Jane"},"LicenseShortName":{"value":"CC BY 4.0"}}}]}}}}""".toByteArray()
            url.host == "upload.wikimedia.org" -> thumbnail
            url.queryParameter("action") == "wbgetclaims" -> """{"claims":{"P18":[{"mainsnak":{"datavalue":{"value":"A photo.png"}}}]}}""".toByteArray()
            else -> "<html>this is a page</html>".toByteArray()
        }
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(body.toResponseBody((if (body === thumbnail) "image/png" else "text/html").toMediaType())).build()
    }.build()
    private fun service(): MusicInfoService {
        val http = MusicInfoHttp(client, minIntervalMs = 0, nowMs = { 0L }, sleep = {})
        return MusicInfoService(cache, MusicBrainzClient(http), CoverArtArchiveClient(http), WikidataClient(http),
            CommonsClient(http), WikipediaClient(http), { true }, { true }, { true }, { listOf("en") })
    }
    @After fun cleanup() { dir.deleteRecursively() }
    @Test fun `a Commons file page saves a decoded thumbnail and its credit`() = runBlocking {
        val photo = service().fetchArtistPhoto("A")!!
        assertThat(File(photo.path).readBytes()).isEqualTo(png)
        assertThat(photo.author).isEqualTo("Jane")
        assertThat(photo.license).isEqualTo("CC BY 4.0")
        assertThat(requests.single { it.host == "commons.wikimedia.org" }.queryParameter("iiurlwidth")).isEqualTo("800")
    }
    @Test fun `an upload relation also resolves Commons metadata`() = runBlocking {
        relation = "https://upload.wikimedia.org/wikipedia/commons/a/ab/A_photo.png"
        val photo = service().fetchArtistPhoto("A")!!
        assertThat(photo.author).isEqualTo("Jane")
        assertThat(File(photo.path).readBytes()).isEqualTo(png)
    }
    @Test fun `HTML with status 200 falls through to Wikidata and is never saved`() = runBlocking {
        relation = "https://artist.example/photo"
        val photo = service().fetchArtistPhoto("A")!!
        assertThat(File(photo.path).readBytes()).isEqualTo(png)
        assertThat(requests.any { it.queryParameter("action") == "wbgetclaims" }).isTrue()
    }
    @Test fun `undecodable thumbnails are not saved`() = runBlocking {
        thumbnail = "<html>no image</html>".toByteArray()
        assertThat(service().fetchArtistPhoto("A")).isNull()
        assertThat(cache.get(MusicInfoCache.Kind.ARTIST_PHOTO, MusicInfoService.photoKey("A"))).isNull()
    }
    @Test fun `startup removes only broken cached files and their exact database references`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val folder = File(context.filesDir, MusicInfoRepository.FOLDER).apply { deleteRecursively() }
        File(context.filesDir, "music-info-images-1.1.1.done").delete()
        val preferences = mockk<UserPreferencesRepository>(relaxed = true)
        every { preferences.musicInfoArtistPhotosEnabledFlow } returns flowOf(true)
        val dao = mockk<MusicDao>(relaxed = true)
        var row: String? = null
        coEvery { dao.clearArtistImagesUnder(any()) } answers { if (row == firstArg<String>()) row = null; 1 }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repo = MusicInfoRepository(context, preferences, client, dao, scope)
            val broken = repo.cache.putFile(MusicInfoCache.Kind.ARTIST_PHOTO, MusicInfoService.photoKey("A"), "<html/>".toByteArray(), "img", null)
            val good = repo.cache.putFile(MusicInfoCache.Kind.COVER, "good", png, "png", null)
            row = broken.absolutePath
            ArtistImageRepository(repo, dao, preferences).retireDeezerOnce()
            assertThat(broken.exists()).isFalse()
            assertThat(row).isNull()
            assertThat(repo.cache.isMissing(MusicInfoCache.Kind.ARTIST_PHOTO, MusicInfoService.photoKey("A"))).isFalse()
            assertThat(good.readBytes()).isEqualTo(png)
            requests.clear()
            assertThat(repo.service.fetchArtistPhoto("A")).isNotNull()
            assertThat(requests).isNotEmpty()
        } finally { scope.cancel(); folder.deleteRecursively(); File(context.filesDir, "music-info-images-1.1.1.done").delete() }
    }
}
