package com.lostf1sh.pixelplayeross.data.offline

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.musicinfo.*
import io.mockk.*
import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class B52SourceAndTagsContractTest {
    private val ids = (1..5).map { "0000000$it-0000-4000-8000-00000000000$it" }

    @After fun cleanup() {
        unmockkAll()
        runCatching {
            val type = Class.forName("com.lostf1sh.pixelplayeross.data.network.SourceWaits")
            type.getMethod("clear", String::class.java).invoke(type.getField("INSTANCE").get(null), "MusicBrainz")
        }
    }

    private fun parsed(fields: Map<String, Array<String>>): Any {
        val type = Class.forName("com.lostf1sh.pixelplayeross.data.media.MusicBrainzTags")
        return type.getMethod("read", Map::class.java).invoke(type.getField("INSTANCE").get(null), fields)
    }

    private fun verifyIds(fields: Map<String, Array<String>>) {
        val result = parsed(fields)
        listOf("Recording", "Release", "ReleaseGroup", "Artist", "AlbumArtist").forEachIndexed { index, field ->
            assertThat(result.javaClass.getMethod("get$field").invoke(result)).isEqualTo(ids[index])
        }
    }

    @Test fun `Vorbis and FLAC MusicBrainz properties preserve all five identifier kinds`() {
        verifyIds(listOf("MUSICBRAINZ_TRACKID", "MUSICBRAINZ_ALBUMID", "MUSICBRAINZ_RELEASEGROUPID", "MUSICBRAINZ_ARTISTID", "MUSICBRAINZ_ALBUMARTISTID")
            .mapIndexed { i, key -> key to arrayOf(ids[i]) }.toMap())
    }

    @Test fun `ID3 TXXX MusicBrainz descriptions map track album release group and artist identifiers`() {
        verifyIds(listOf("Track", "Album", "Release Group", "Artist", "Album Artist")
            .mapIndexed { i, key -> "TXXX:MusicBrainz $key Id" to arrayOf(ids[i]) }.toMap())
    }

    @Test fun `MP4 iTunes freeform MusicBrainz fields preserve the identifiers`() {
        verifyIds(listOf("Track", "Album", "Release Group", "Artist", "Album Artist")
            .mapIndexed { i, key -> "----:com.apple.iTunes:MusicBrainz $key Id" to arrayOf(ids[i].uppercase()) }.toMap())
    }

    @Test fun `invalid tag values are ignored and an explicit recording ID takes precedence`() {
        val result = parsed(mapOf("MUSICBRAINZ_RECORDINGID" to arrayOf(ids[0]), "MUSICBRAINZ_TRACKID" to arrayOf(ids[1]), "MUSICBRAINZ_ARTISTID" to arrayOf("not an ID")))
        assertThat(result.javaClass.getMethod("getRecording").invoke(result)).isEqualTo(ids[0])
        assertThat(result.javaClass.getMethod("getArtist").invoke(result)).isNull()
    }

    @Test fun `known MusicBrainz IDs bypass name searches for artist release and release group`() = runBlocking {
        val http = mockk<MusicInfoHttp>()
        val resolver: suspend (String, String, String?) -> String? = { kind, _, _ ->
            when (kind) { "artist" -> ids[3]; "release" -> ids[1]; else -> ids[2] }
        }
        val ctor = MusicBrainzClient::class.java.constructors.firstOrNull { it.parameterCount == 3 }
        org.junit.Assert.assertNotNull("MusicBrainz must accept scanned identifier hints", ctor)
        val client = ctor!!.newInstance(http, "https://musicbrainz.org/ws/2/", resolver) as MusicBrainzClient
        assertThat(client.findArtist("Artist")?.id).isEqualTo(ids[3])
        assertThat(client.findRelease("Album", "Artist")?.id).isEqualTo(ids[1])
        assertThat(client.findReleaseGroup("Album", "Artist")?.id).isEqualTo(ids[2])
        coVerify(exactly = 0) { http.json(any()) }
    }

    @Test fun `without a tag ID an exact name search remains the fallback`() = runBlocking {
        val http = mockk<MusicInfoHttp>()
        coEvery { http.json(any()) } returns JSONObject("""{"artists":[{"id":"${ids[3]}","name":"Artist","score":100}]}""")
        assertThat(MusicBrainzClient(http).findArtist("Artist")?.id).isEqualTo(ids[3])
        coVerify(exactly = 1) { http.json(match { it.queryParameter("query") == "artist:\"Artist\"" }) }
    }

    @Test fun `HTTP 429 exposes the source and Retry After without making a network request`() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(429).message("Slow down")
                .header("Retry-After", "40").body("{}".toResponseBody()).build()
        }.build()
        val before = System.currentTimeMillis()
        var failure: IOException? = null
        try { MusicInfoHttp(client).json("https://musicbrainz.org/ws/2/artist".toHttpUrl()) }
        catch (error: IOException) { failure = error }
        val getWait = failure?.javaClass?.methods?.firstOrNull { it.name == "getWait" }
        org.junit.Assert.assertNotNull("Rate limits must carry a typed source wait", getWait)
        val wait = getWait!!.invoke(failure)
        assertThat(wait.javaClass.getMethod("getSource").invoke(wait)).isEqualTo("MusicBrainz")
        assertThat(wait.javaClass.getMethod("getRetryAt").invoke(wait) as Long).isAtLeast(before + 40_000)
    }
}
