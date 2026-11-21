package com.lostf1sh.pixelplayeross.data.backup

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.lostf1sh.pixelplayeross.data.backup.format.*
import com.lostf1sh.pixelplayeross.data.backup.model.*
import com.lostf1sh.pixelplayeross.data.backup.validation.BackupFileValidator
import com.lostf1sh.pixelplayeross.data.musicinfo.MusicInfoHttp
import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okio.*
import org.junit.Assert.*
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import com.lostf1sh.pixelplayeross.data.backup.module.*
import com.lostf1sh.pixelplayeross.data.database.MusicDao
import com.lostf1sh.pixelplayeross.data.preferences.*
import com.lostf1sh.pixelplayeross.data.model.Playlist
import com.lostf1sh.pixelplayeross.data.repository.GenreImageRepository
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Audit witnesses: passing means the reported defect is reproduced. No device or real files. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class B63BackupFaultTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun reader() = BackupReader(context, Gson(), BackupFormatDetector(), LegacyPayloadAdapter())

    @Test fun unknownLengthImageStopsAtItsLimit() = runBlocking {
        val payload = ByteArray(65536) { 1 }
        var consumed = 0L
        val body = object : ResponseBody() {
            override fun contentType() = "image/png".toMediaType()
            override fun contentLength() = -1L
            private val input = object : ForwardingSource(Buffer().write(payload)) {
                override fun read(sink: Buffer, byteCount: Long): Long = super.read(sink, byteCount).also { if (it > 0) consumed += it }
            }.buffer()
            override fun source() = input
        }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body).build()
        }.build()
        val http = MusicInfoHttp(client, minIntervalMs = 0, nowMs = { 0L }, sleep = {})
        assertNull(http.bytes("https://example.org/cover.png".toHttpUrl(), 16))
        println("B63 image limit=16 bytes consumed=$consumed result=null")
        assertTrue("bounded upstream buffering", consumed <= 8192L)
    }

    @Test fun failedArtistImageWriteIsReported() = runBlocking {
        val dao = mockk<MusicDao>(relaxed = true)
        coEvery { dao.getArtistIdByName("Audit artist") } returns 424242L
        val target = File(context.filesDir, "artist_art_424242.jpg").apply { mkdirs() }
        try {
            val handler = ArtistImagesModuleHandler(context, dao, Gson(), mockk<GenreImageRepository>(relaxed = true))
            val outcome = runCatching { handler.restore("""[{"artistName":"Audit artist","imageUrl":"","customImageBase64":"eA=="}]""") }
            println("B63 artist image cannot be written; restore success=${outcome.isSuccess}")
            assertTrue(outcome.isFailure)
            assertNotNull(outcome.exceptionOrNull())
            coVerify(exactly = 0) { dao.updateArtistCustomImage(any(), any()) }
        } finally { target.deleteRecursively() }
    }

    @Test fun playlistRollbackRestoresOriginalCoverBytes() = runBlocking {
        val target = File(context.filesDir, "playlist_cover_b63-playlist.jpg").apply { writeText("ORIGINAL") }
        try {
            var rows = listOf(Playlist("b63-playlist", "Audit", emptyList(), coverImageUri = target.absolutePath))
            val prefs = mockk<PlaylistPreferencesRepository>(relaxed = true)
            coEvery { prefs.getPlaylistsOnce() } answers { rows }
            every { prefs.playlistSongOrderModesFlow } returns flowOf(emptyMap())
            every { prefs.playlistsSortOptionFlow } returns flowOf("name")
            coEvery { prefs.replaceAllPlaylists(any()) } coAnswers { rows = firstArg() }
            val handler = PlaylistsModuleHandler(context, prefs, mockk(relaxed = true), mockk(relaxed = true), Gson())
            val snapshot = handler.snapshot()
            handler.restore("""{"playlists":[{"id":"b63-playlist","name":"Audit","songIds":[],"source":"LOCAL"}],"coverImages":{"b63-playlist":"UkVQTEFDRU1FTlQ="}}""")
            assertEquals("REPLACEMENT", target.readText())
            handler.rollback(snapshot)
            println("B63 playlist rollback restored path=${rows.single().coverImageUri}; bytes=${target.readText()}")
            assertEquals(target.absolutePath, rows.single().coverImageUri)
            assertEquals("ORIGINAL", target.readText())
        } finally { target.delete() }
    }

}
