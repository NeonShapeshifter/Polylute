package com.lostf1sh.pixelplayeross.data.media

import android.app.Application
import android.app.PendingIntent
import android.app.RecoverableSecurityException
import android.app.RemoteAction
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.work.ListenableWorker
import com.lostf1sh.pixelplayeross.presentation.viewmodel.AlbumTrackEdit
import com.lostf1sh.pixelplayeross.presentation.viewmodel.resolveBatchTrackFields
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.gagravarr.ogg.OggFile
import org.gagravarr.opus.OpusFile
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B57 1.3, the editor's hard rules, on real synthetic files: the **date** goes to the file's date tag (every writer), **the audio never changes** (the bytes of the
 * audio payload are identical before and after, which makes the decoded samples identical; where `ffmpeg` is installed the decoded samples' MD5 is compared as well),
 * and a **whole album** of 12 tracks saved keeps its 12 identities.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class B57EditorDateAndAudioTest {
    private lateinit var rig: EditorIdentityRig
    @Before fun setUp() { rig = EditorIdentityRig() }
    @After fun tearDown() { rig.close() }

    private val cover: ByteArray = run {
        val random = java.util.Random(57)
        val image = java.awt.image.BufferedImage(320, 320, java.awt.image.BufferedImage.TYPE_INT_RGB)
        for (x in 0 until 320) for (y in 0 until 320) image.setRGB(x, y, random.nextInt(0xFFFFFF))
        java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(image, "jpg", it) }.toByteArray()
    }
    private val lyrics = (1..300).joinToString("\n") { "line $it of a synthetic lyric" }

    private suspend fun edit(editor: SongMetadataEditor, id: Long, title: String = "Cine (Remastered)", track: Int = 3, disc: Int? = 1, withCover: Boolean = true, date: String? = null) =
        editor.editSongMetadata(
            songId = id, newTitle = title, newArtist = "Gemini Sound", newAlbum = "Noche (Deluxe)", newAlbumArtist = null, newComposer = null, newGenre = "Rock",
            newLyrics = lyrics, newTrackNumber = track, newDiscNumber = disc, coverArtUpdate = if (withCover) CoverArtUpdate(cover) else null, newDate = date,
        )

    // ── the date tag ───────────────────────────────────────────────────────────────────────────────

    private fun dateTagOf(file: File): String? = if (file.extension == "opus") {
        OpusFile(file).use { it.tags.getComments("DATE").firstOrNull() }
    } else {
        AudioFileIO.read(file).tag?.getFirst(FieldKey.YEAR)?.takeIf { it.isNotBlank() }
    }

    private fun dateIsWrittenAndTheLibraryRowFollows(fixture: String) = runBlocking {
        val file = rig.addFixture(1, fixture, "Cine")
        rig.firstScan()
        val result = edit(rig.editor(), 1, date = "2019-05-03")
        assertTrue("${result.error} ${result.errorMessage}", result.success)
        assertTrue("the date tag of $fixture: ${dateTagOf(file)}", dateTagOf(file).orEmpty().startsWith("2019"))
        assertEquals("the library row shows the year without a rescan", 2019, rig.dao.getSongByIdOnce(1)!!.year)
    }

    @Test fun the_date_is_written_to_a_flac_file() = dateIsWrittenAndTheLibraryRowFollows("tone.flac")
    @Test fun the_date_is_written_to_an_ogg_vorbis_file() = dateIsWrittenAndTheLibraryRowFollows("tone-vorbis.ogg")
    @Test fun the_date_is_written_to_an_opus_file() = dateIsWrittenAndTheLibraryRowFollows("tone-opus.opus")
    @Test fun the_date_is_written_to_an_mp3_file() = dateIsWrittenAndTheLibraryRowFollows("tone.mp3")
    @Test fun the_date_is_written_to_an_m4a_file() = dateIsWrittenAndTheLibraryRowFollows("tone-aac.m4a")

    @Test fun no_date_leaves_the_tag_alone_a_blank_one_removes_it_and_a_bad_one_is_refused() = runBlocking {
        val file = rig.addFixture(1, "tone-vorbis.ogg", "Cine")
        rig.firstScan()
        val editor = rig.editor()
        assertTrue(edit(editor, 1, date = "2018").success)
        assertEquals("2018", dateTagOf(file))
        assertTrue(edit(editor, 1, date = null).success)
        assertEquals("a null date leaves the tag as it was", "2018", dateTagOf(file))
        val before = file.readBytes()
        val refused = edit(editor, 1, date = "19")
        assertFalse(refused.success)
        assertEquals(MetadataEditError.INVALID_INPUT, refused.error)
        assertTrue("the file is untouched", before.contentEquals(file.readBytes()))
        assertTrue(edit(editor, 1, date = "").success)
        assertEquals("a blank date removes the tag", null, dateTagOf(file))
        assertEquals(0, rig.dao.getSongByIdOnce(1)!!.year)
    }

    // ── the audio never changes ────────────────────────────────────────────────────────────────────

    /** The bytes that carry the sound, without any tag: if they are identical the decoder gets identical input. */
    private fun payloadDigest(file: File): String {
        val bytes = file.readBytes()
        val digest = MessageDigest.getInstance("SHA-256")
        when (file.extension) {
            "flac" -> {
                var at = 4
                do {
                    val last = bytes[at].toInt() and 0x80 != 0
                    val length = ((bytes[at + 1].toInt() and 0xFF) shl 16) or ((bytes[at + 2].toInt() and 0xFF) shl 8) or (bytes[at + 3].toInt() and 0xFF)
                    at += 4 + length
                } while (!last)
                digest.update(bytes, at, bytes.size - at)
            }
            "ogg", "opus" -> {
                val headers = if (file.extension == "opus") 2 else 3
                FileInputStream(file).use { stream ->
                    val reader = OggFile(stream).packetReader
                    var index = 0
                    while (true) {
                        val packet = reader.nextPacket ?: break
                        if (index++ >= headers) digest.update(packet.data)
                    }
                }
            }
            "mp3" -> {
                var start = 0
                if (bytes.size > 10 && bytes[0] == 'I'.code.toByte() && bytes[1] == 'D'.code.toByte() && bytes[2] == '3'.code.toByte()) {
                    start = 10 + ((bytes[6].toInt() and 0x7F) shl 21 or ((bytes[7].toInt() and 0x7F) shl 14) or ((bytes[8].toInt() and 0x7F) shl 7) or (bytes[9].toInt() and 0x7F))
                }
                var end = bytes.size
                if (end - 128 > start && bytes[end - 128] == 'T'.code.toByte() && bytes[end - 127] == 'A'.code.toByte() && bytes[end - 126] == 'G'.code.toByte()) end -= 128
                digest.update(bytes, start, end - start)
            }
            "m4a" -> {
                var at = 0
                while (at + 8 <= bytes.size) {
                    var size = ((bytes[at].toLong() and 0xFF) shl 24) or ((bytes[at + 1].toLong() and 0xFF) shl 16) or ((bytes[at + 2].toLong() and 0xFF) shl 8) or (bytes[at + 3].toLong() and 0xFF)
                    val type = String(bytes, at + 4, 4, Charsets.ISO_8859_1)
                    var header = 8
                    if (size == 1L) { size = java.nio.ByteBuffer.wrap(bytes, at + 8, 8).long; header = 16 }
                    if (size == 0L) size = (bytes.size - at).toLong()
                    if (type == "mdat") { digest.update(bytes, at + header, (size - header).toInt()); break }
                    at += size.toInt()
                }
            }
            else -> error("no payload reader for ${file.extension}")
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun flacStreamInfoMd5(file: File): String {
        val bytes = file.readBytes()
        // "fLaC", then the STREAMINFO block (type 0): 4 bytes of header and 34 of body; the MD5 of the unencoded audio is the body's last 16 bytes
        return bytes.copyOfRange(4 + 4 + 18, 4 + 4 + 34).joinToString("") { "%02x".format(it) }
    }

    private fun ffmpegDecodedMd5(file: File): String? = try {
        val process = ProcessBuilder("ffmpeg", "-v", "error", "-i", file.absolutePath, "-map", "0:a:0", "-f", "md5", "-").redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText().trim()
        if (process.waitFor() == 0 && out.startsWith("MD5=")) out else null
    } catch (e: java.io.IOException) { null }

    private fun audioDoesNotChange(fixture: String, cover: Boolean = true) = runBlocking {
        val file = rig.addFixture(1, fixture, "Cine")
        rig.firstScan()
        val payloadBefore = payloadDigest(file)
        val streamInfoBefore = if (file.extension == "flac") flacStreamInfoMd5(file) else null
        val decodedBefore = ffmpegDecodedMd5(file)
        val sizeBefore = file.length()

        val result = edit(rig.editor(), 1, withCover = cover, date = "2019-05-03")

        assertTrue("${result.error} ${result.errorMessage}", result.success)
        assertNotEquals("the tag write really changed the file", sizeBefore, file.length())
        assertEquals("the audio payload of $fixture is byte for byte the same", payloadBefore, payloadDigest(file))
        if (streamInfoBefore != null) assertEquals("the STREAMINFO MD5 of $fixture", streamInfoBefore, flacStreamInfoMd5(file))
        decodedBefore?.let { assertEquals("the decoded samples' MD5 of $fixture (ffmpeg)", it, ffmpegDecodedMd5(file)) }
        Unit
    }

    @Test fun flac_audio_is_unchanged_by_a_tag_write() = audioDoesNotChange("tone.flac", cover = false)
    @Test fun high_resolution_flac_audio_is_unchanged_by_a_tag_write() = audioDoesNotChange("tone-highres.flac", cover = false)
    @Test fun ogg_vorbis_audio_is_unchanged_by_a_tag_write() = audioDoesNotChange("tone-vorbis.ogg", cover = false)
    @Test fun opus_audio_is_unchanged_by_a_tag_write_with_a_cover() = audioDoesNotChange("tone-opus.opus")
    @Test fun mp3_audio_is_unchanged_by_a_tag_write() = audioDoesNotChange("tone.mp3", cover = false)
    @Test fun m4a_aac_audio_is_unchanged_by_a_tag_write() = audioDoesNotChange("tone-aac.m4a", cover = false)
    @Test fun m4a_alac_audio_is_unchanged_by_a_tag_write() = audioDoesNotChange("tone-alac.m4a", cover = false)

    @Test fun the_decoded_samples_are_compared_on_this_host_when_ffmpeg_is_here() {
        assumeTrue("ffmpeg is not installed", ffmpegDecodedMd5(File(javaClass.classLoader!!.getResource("editor/tone.flac")!!.toURI())) != null)
    }

    // ── a whole album ──────────────────────────────────────────────────────────────────────────────

    @Test fun twelve_tracks_saved_as_an_album_keep_their_twelve_identities_and_each_gets_its_own_values() = runBlocking {
        val files = (1L..12L).map { rig.addFixture(it, "tone-opus.opus", "Old $it", track = it.toInt()) }
        rig.firstScan()
        val items = (1L..12L).associateWith { rig.giveUserState(it) }
        val itemsBefore = rig.items()
        val editor = rig.editor()
        val perTrack = (1L..12L).associate { it.toString() to AlbumTrackEdit("New title $it", 13 - it.toInt(), if (it > 6) 2 else 1) }
        val songs = (1L..12L).map { rig.dao.getSongByIdOnce(it)!! }.map {
            com.lostf1sh.pixelplayeross.data.model.Song(id = it.id.toString(), title = it.title, artist = it.artistName, artistId = it.artistId, album = it.albumName, albumId = it.albumId, path = it.filePath, contentUriString = "", albumArtUriString = null, duration = it.duration, trackNumber = it.trackNumber, mimeType = null, bitrate = null, sampleRate = null)
        }

        var saved = 0
        val failures = mutableListOf<String>()
        for (song in songs) {
            val (title, track, disc) = resolveBatchTrackFields(perTrack[song.id], null, null, null, song)
            val result = editor.editSongMetadata(
                songId = song.id.toLong(), newTitle = title, newArtist = "Gemini Sound", newAlbum = "Noche (Deluxe)", newAlbumArtist = "Gemini Sound", newComposer = null, newGenre = "Rock",
                newLyrics = "", newTrackNumber = track, newDiscNumber = disc, coverArtUpdate = CoverArtUpdate(cover), newDate = "2019-05-03",
            )
            if (result.success) saved++ else failures += result.errorMessage.orEmpty()
        }

        assertEquals("12 saved / 0 failed: $failures", 12, saved)
        for (id in 1L..12L) {
            val row = rig.dao.getSongByIdOnce(id)!!
            assertEquals("New title $id", row.title)
            assertEquals(13 - id.toInt(), row.trackNumber)
            assertEquals(if (id > 6) 2 else 1, row.discNumber)
            assertEquals("Noche (Deluxe)", row.albumName)
            assertEquals(2019, row.year)
            assertEquals("2019-05-03", dateTagOf(files[(id - 1).toInt()]))
        }
        assertTrue(rig.rescan() is ListenableWorker.Result.Success)
        assertEquals(itemsBefore.map { it.libraryItemId }.toSet(), rig.items().map { it.libraryItemId }.toSet())
        assertEquals(12, rig.items().size)
        assertTrue(rig.items().all { it.state == "ACTIVE" })
        for ((id, item) in items) {
            assertEquals(item, rig.dao.getSongByLocatorOnce(id)!!.libraryItemId)
            assertTrue("favourite of $id kept", rig.userStateOf(item).favourite)
            assertEquals(7, rig.userStateOf(item).plays)
        }
        assertNotNull(files)
    }
}

/** B57 1.3: Android 10 (API 29) writes by the legacy path and asks the listener when MediaStore refuses; the second attempt after the grant must succeed and keep the identity. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class B57EditorAndroid10Test {
    private lateinit var rig: EditorIdentityRig
    @Before fun setUp() { rig = EditorIdentityRig() }
    @After fun tearDown() { rig.close() }

    private fun recoverable() = RecoverableSecurityException(
        Exception("not yours"), "Allow Polylute to edit this file?",
        RemoteAction(Icon.createWithResource(rig.context, android.R.drawable.ic_menu_edit), "Allow", "Allow", PendingIntent.getActivity(rig.context, 0, Intent(), PendingIntent.FLAG_IMMUTABLE)),
    )

    @Test fun a_refused_media_store_write_asks_for_permission_and_the_retry_keeps_the_identity() = runBlocking {
        val file = rig.addFixture(1, "tone-vorbis.ogg", "Cine")
        rig.firstScan()
        val item = rig.giveUserState(1)
        val itemsBefore = rig.items()
        val lyrics = (1..300).joinToString("\n") { "line $it of a synthetic lyric" }
        fun attempt() = runBlocking {
            rig.editor().editSongMetadata(
                songId = 1, newTitle = "Cine (Remastered)", newArtist = "Gemini Sound", newAlbum = "Noche", newAlbumArtist = null, newComposer = null, newGenre = "Rock",
                newLyrics = lyrics, newTrackNumber = 1, newDiscNumber = null, newDate = "2019",
            )
        }

        rig.store.denyNextUpdate = recoverable()
        val first = attempt()
        assertFalse(first.success)
        assertEquals(MetadataEditError.NO_WRITE_PERMISSION, first.error)
        assertNotNull("the listener is asked, through the intent the system gave", first.permissionIntentSender)
        assertTrue("the first attempt had already rewritten the file by path", file.length() > 7_400)

        val second = attempt() // after the grant
        assertTrue("${second.error} ${second.errorMessage}", second.success)
        assertTrue(rig.rescan() is ListenableWorker.Result.Success)
        assertEquals(itemsBefore.map { it.libraryItemId }.toSet(), rig.items().map { it.libraryItemId }.toSet())
        assertEquals(rig.expectedUserState, rig.userStateOf(item))
    }

    @Test fun the_view_model_asks_for_a_write_request_only_from_android_11() {
        val source = File("src/main/java/com/lostf1sh/pixelplayeross/presentation/viewmodel/PlayerViewModel.kt").readText()
        // single song, batch and album all take the 11+ createWriteRequest path behind the same version check; on 10 the editor's RecoverableSecurityException above is the path
        val gated = Regex("""SDK_INT >= android\.os\.Build\.VERSION_CODES\.R""").findAll(source).count()
        assertTrue("createWriteRequest is behind an R check ($gated)", gated >= 2)
    }
}
