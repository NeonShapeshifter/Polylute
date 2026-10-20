package com.lostf1sh.pixelplayeross.data.network

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.BuildConfig
import com.lostf1sh.pixelplayeross.data.musicbrainz.MusicBrainzApiService
import com.lostf1sh.pixelplayeross.data.network.lyrics.LrcLibApiService
import com.lostf1sh.pixelplayeross.di.AppModule
import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test

/**
 * B37 (C3): one User-Agent, and it names Polylute.
 *
 * The three clients said `PixelPlayerOSS/1.0` (twice) and `PixelPlayerOSS/<version> (https://github.com/PixelPlayerHQ/PixelPlayerOSS)`, which is
 * the upstream's address, handed to MusicBrainz as the way to reach us. What each client **sends** is read here: a recording interceptor is
 * added as the last interceptor of the client the app builds, so it sees the request after every header the client adds, and answers without
 * touching a network. The Retrofit services for LRCLIB and Deezer are driven through the same clients.
 */
class HttpUserAgentTest {

    private val version = BuildConfig.VERSION_NAME

    /** Every request that reached the end of a client's interceptor chain. */
    private class Recorder : Interceptor {
        val seen = mutableListOf<Request>()
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            seen += request
            return Response.Builder()
                .request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("{}".toResponseBody("application/json".toMediaType()))
                .build()
        }
    }

    private fun recording(client: OkHttpClient, recorder: Recorder) = client.newBuilder().addInterceptor(recorder).build()

    private fun get(client: OkHttpClient, url: String = "https://example.invalid/") =
        client.newCall(Request.Builder().url(url).get().build()).execute().close()

    private fun Headers.pairs() = (0 until size).map { name(it) to value(it) }

    @Test
    fun `the general client names Polylute with its version`() {
        val recorder = Recorder()
        get(recording(AppModule.provideOkHttpClient(), recorder))
        assertThat(recorder.seen.single().header("User-Agent")).isEqualTo("Polylute/$version (Android; Music Player)")
    }

    @Test
    fun `the lyrics client names Polylute with its version and still asks for JSON`() {
        val recorder = Recorder()
        get(recording(AppModule.provideFastOkHttpClient(), recorder))
        val request = recorder.seen.single()
        assertThat(request.header("User-Agent")).isEqualTo("Polylute/$version (Android; Music Player)")
        assertThat(request.header("Accept")).isEqualTo("application/json")
    }

    @Test
    fun `MusicBrainz is told who to contact and it is this project`() {
        val service = MusicBrainzApiService(OkHttpClient())
        val field = MusicBrainzApiService::class.java.getDeclaredField("client").apply { isAccessible = true }
        val recorder = Recorder()
        get(recording(field.get(service) as OkHttpClient, recorder), "https://musicbrainz.org/ws/2/recording")
        val request = recorder.seen.single()
        assertThat(request.header("User-Agent"))
            .isEqualTo("Polylute/$version ( https://github.com/NeonShapeshifter/Polylute )")
        assertThat(request.header("Accept")).isEqualTo("application/json")
    }

    @Test
    fun `LRCLIB and Deezer requests carry the same User-Agent as their client`() {
        val lyrics = Recorder()
        val lrclib = AppModule.provideRetrofit(recording(AppModule.provideFastOkHttpClient(), lyrics))
            .create(LrcLibApiService::class.java)
        runCatching { runBlocking { lrclib.getLyrics("t", "a", "b", 1) } }

        val artists = Recorder()
        val deezer = AppModule.provideDeezerApiService(
            AppModule.provideDeezerRetrofit(recording(AppModule.provideOkHttpClient(), artists)),
        )
        runCatching { runBlocking { deezer.searchArtist("a") } }

        assertThat(lyrics.seen.single().url.encodedPath).isEqualTo("/api/get")
        assertThat(artists.seen.single().url.encodedPath).isEqualTo("/search/artist")
        listOf(lyrics, artists).forEach {
            assertThat(it.seen.single().header("User-Agent")).isEqualTo("Polylute/$version (Android; Music Player)")
        }
    }

    @Test
    fun `no header of any client names PixelPlayerOSS`() {
        val recorders = listOf(Recorder(), Recorder(), Recorder())
        get(recording(AppModule.provideOkHttpClient(), recorders[0]))
        get(recording(AppModule.provideFastOkHttpClient(), recorders[1]))
        val musicBrainz = MusicBrainzApiService::class.java.getDeclaredField("client").apply { isAccessible = true }
            .get(MusicBrainzApiService(OkHttpClient())) as OkHttpClient
        get(recording(musicBrainz, recorders[2]), "https://musicbrainz.org/ws/2/recording")
        val headers = recorders.flatMap { it.seen }.flatMap { it.headers.pairs() }
        assertThat(headers).isNotEmpty()
        headers.forEach { (name, value) ->
            assertThat(name.lowercase()).doesNotContain("pixelplayeross")
            assertThat(value.lowercase()).doesNotContain("pixelplayeross")
            assertThat(value.lowercase()).doesNotContain("pixelplayerhq")
        }
    }

    @Test
    fun `only the two OkHttp builders and the MusicBrainz client set a User-Agent, and none spells the old name`() {
        val main = File(root, "src/main/java")
        val setters = main.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .associate { it.name to Regex("\"User-Agent\"").findAll(it.readText()).count() }
            .filterValues { it > 0 }
        assertThat(setters).containsExactly("AppModule.kt", 2, "MusicBrainzApiService.kt", 1)
        listOf("di/AppModule.kt", "data/musicbrainz/MusicBrainzApiService.kt").forEach { relative ->
            val text = File(main, "com/lostf1sh/pixelplayeross/$relative").readText()
            assertThat(text).doesNotContain("PixelPlayerOSS")
            assertThat(text).doesNotContain("PixelPlayerHQ")
        }
    }

    private companion object {
        val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "src/main/AndroidManifest.xml").isFile }
    }
}
