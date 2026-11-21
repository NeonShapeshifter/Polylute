package com.lostf1sh.pixelplayeross.data.backup.format

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.lostf1sh.pixelplayeross.data.backup.model.*
import com.lostf1sh.pixelplayeross.data.backup.validation.BackupFileValidator
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import com.lostf1sh.pixelplayeross.data.database.*
import com.lostf1sh.pixelplayeross.data.preferences.*
import com.lostf1sh.pixelplayeross.data.backup.module.*
import com.lostf1sh.pixelplayeross.data.model.Playlist
import com.lostf1sh.pixelplayeross.data.stats.PlaybackStatsRepository
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class B63BackupRoundTripTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val gson = Gson()
    private fun reader() = BackupReader(context, gson, BackupFormatDetector(), LegacyPayloadAdapter())
    @Test fun `five thousand lyrics and fifty thousand events and two hundred covered playlists round trip`() = runBlocking {
        val expectedLyrics = List(5000) { i -> LyricsEntity(i.toLong(), "Synthetic text $i " + "lorem synthetic ".repeat(273)) }
        val expectedEvents = List(50000) { i -> PlaybackStatsRepository.PlaybackEvent((i % 5000).toString(), 1000000L+i, 60000) }
        val cover = java.io.ByteArrayOutputStream().also { out ->
            android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888)
                .apply { eraseColor(0xff336699.toInt()) }.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        }.toByteArray()
        val originals = List(200) { i -> File(context.filesDir, "b63-source-$i.png").apply { writeBytes(cover) } }
        val expectedPlaylists = List(200) { i -> Playlist("b63-$i", "Synthetic $i", List(25) { ((i*25+it)%5000).toString() }, coverImageUri = originals[i].path) }
        var lyrics = expectedLyrics
        var events = expectedEvents
        var playlists = expectedPlaylists
        val lyricsDao = mockk<LyricsDao>(relaxed = true)
        coEvery { lyricsDao.getAll() } answers { lyrics }
        coEvery { lyricsDao.replaceAll(any()) } coAnswers { lyrics = firstArg() }
        val stats = mockk<PlaybackStatsRepository>(relaxed = true)
        coEvery { stats.exportEventsForBackup() } answers { events }
        coEvery { stats.importEventsFromBackup(any(), any()) } coAnswers { events = firstArg() }
        val prefs = mockk<PlaylistPreferencesRepository>(relaxed = true)
        coEvery { prefs.getPlaylistsOnce() } answers { playlists }
        coEvery { prefs.replaceAllPlaylists(any()) } coAnswers { playlists = firstArg() }
        every { prefs.playlistSongOrderModesFlow } returns flowOf(emptyMap())
        every { prefs.playlistsSortOptionFlow } returns flowOf("name")
        val dao = mockk<MusicDao>(relaxed = true)
        coEvery { dao.getAllLocalSongSummaries() } returns List(5000) { i -> SongSummary(i.toLong(), "Synthetic $i", "Group", "Release", 180000) }
        val handlers = listOf(LyricsModuleHandler(lyricsDao,gson), PlaybackHistoryModuleHandler(stats,gson),
            PlaylistsModuleHandler(context,prefs,mockk(relaxed=true),dao,gson))
        val file = File.createTempFile("b63-library", ".pxpl", context.cacheDir)
        try {
            val expected = handlers.associate { it.section.key to it.export() }
            assertTrue(expected.getValue("lyrics").length > BackupReader.MAX_MODULE_PAYLOAD_BYTES)
            val uri = Uri.fromFile(file)
            BackupWriter(context,gson).write(uri,BackupManifest(),expected).getOrThrow()
            val actual = reader().readAllModulePayloads(uri).getOrThrow()
            assertEquals(expected,actual)
            lyrics = emptyList(); events = emptyList(); playlists = emptyList()
            for(handler in handlers) handler.restore(reader().readModulePayload(uri,handler.section.key).getOrThrow())
            assertEquals(expectedLyrics,lyrics)
            assertEquals(expectedEvents,events)
            assertEquals(expectedPlaylists.map { it.copy(coverImageUri=null) },playlists.map { it.copy(coverImageUri=null) })
            playlists.forEach { assertArrayEquals(cover,File(it.coverImageUri!!).readBytes()) }
            assertFalse(BackupFileValidator(context,BackupFormatDetector()).validate(uri) is BackupValidationResult.Invalid)
            println("B63 synthetic lyricsChars=${expected.getValue("lyrics").length} historyEvents=${events.size} playlists=${playlists.size} archiveBytes=${file.length()}")
        } finally {
            file.delete(); originals.forEach { it.delete() }; playlists.forEach { it.coverImageUri?.let(::File)?.delete() }
        }
    }
    @Test fun `two old small modules remain readable`() = runBlocking {
        val file = File.createTempFile("b63-old", ".pxpl", context.cacheDir)
        try {
            val expected = linkedMapOf("favorites" to "[]", "lyrics" to "[]")
            file.outputStream().use { raw ->
                raw.write(BackupFormatDetector.PXPL_MAGIC)
                java.util.zip.ZipOutputStream(raw).use { zip ->
                    for((key,value) in mapOf("manifest.json" to gson.toJson(BackupManifest())) + expected.mapKeys { "${it.key}.json" }) {
                        zip.putNextEntry(java.util.zip.ZipEntry(key)); zip.write(value.toByteArray()); zip.closeEntry()
                    }
                }
            }
            assertEquals(expected,reader().readAllModulePayloads(Uri.fromFile(file)).getOrThrow())
            assertEquals(3,reader().readManifest(Uri.fromFile(file)).getOrThrow().schemaVersion)
        } finally { file.delete() }
    }
    @Test fun `writer refuses a module that its default reader limit cannot accept`() = runBlocking {
        val file = File.createTempFile("b63-limit", ".pxpl", context.cacheDir)
        try {
            val result = BackupWriter(context,gson).write(Uri.fromFile(file),BackupManifest(),mapOf("global_settings" to "[\"${"a".repeat(BackupReader.MAX_MODULE_PAYLOAD_BYTES+1)}\"]"))
            assertTrue(result.isFailure)
            assertTrue((result.exceptionOrNull() as BackupMessageException).backupMessage.args.contains(BackupSection.GLOBAL_SETTINGS))
        } finally { file.delete() }
    }
    @Test fun `writer rejects arrays beyond the importer entry limit including nested playlists`() = runBlocking {
        val count = com.lostf1sh.pixelplayeross.data.backup.validation.ModuleSchemaValidator.MAX_ENTRIES_PER_MODULE + 1
        val rows = (1..count).joinToString(prefix="[",postfix="]") { "{\"songId\":\"1\",\"timestamp\":$it,\"durationMs\":60000}" }
        val playlists = (1..count).joinToString(prefix="{\"playlists\":[",postfix="]}") { "{\"id\":\"p$it\",\"name\":\"Synthetic $it\",\"songIds\":[],\"source\":\"LOCAL\"}" }
        for((key,payload) in mapOf("playback_history" to rows,"playlists" to playlists)) {
            val file = File.createTempFile("b63-entry-limit", ".pxpl", context.cacheDir)
            try {
                val result = BackupWriter(context,gson).write(Uri.fromFile(file),BackupManifest(),mapOf(key to payload))
                assertTrue("writer must reject $key before reporting success",result.isFailure)
                assertTrue(result.exceptionOrNull()!!.message.orEmpty().contains(key))
            } finally { file.delete() }
        }
    }
}
