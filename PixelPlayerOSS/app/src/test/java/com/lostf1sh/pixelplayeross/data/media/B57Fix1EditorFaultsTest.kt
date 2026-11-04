package com.lostf1sh.pixelplayeross.data.media

import android.app.Application
import androidx.work.ListenableWorker
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.audio.flac.FlacInfoReader
import org.jaudiotagger.tag.FieldKey
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B57-fix1 item 5, the editor faults B57 found, each on a real synthetic file:
 *
 * 1. a WAV edit used to fail every time (JAudioTagger's WavTag refuses to delete the lyrics), and JAudioTagger moved the audio of some WAV layouts. WAV edits now work
 *    through [WavTagWriter] and the audio chunks stay byte for byte;
 * 2. on the JAudioTagger branch (Ogg Vorbis, the fallback) the cover was **deleted** and the new one never written, so editing any field of such a file cost its cover;
 * 3. hi-resolution FLAC is no longer routed away from TagLib (the host probe `taglib-highres-probe.py` shows TagLib writes it safely) and the payload guard is the
 *    safety net for it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class B57Fix1EditorFaultsTest {
    private lateinit var rig: EditorIdentityRig
    @Before fun setUp() { rig = EditorIdentityRig() }
    @After fun tearDown() { rig.close() }

    private val cover: ByteArray = run {
        val random = java.util.Random(5701)
        val image = java.awt.image.BufferedImage(320, 320, java.awt.image.BufferedImage.TYPE_INT_RGB)
        for (x in 0 until 320) for (y in 0 until 320) image.setRGB(x, y, random.nextInt(0xFFFFFF))
        java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(image, "jpg", it) }.toByteArray()
    }
    private val lyrics = (1..200).joinToString("\n") { "line $it of a synthetic lyric" }

    private suspend fun edit(
        editor: SongMetadataEditor,
        id: Long,
        title: String = "New title",
        lyrics: String = this.lyrics,
        cover: CoverArtUpdate? = CoverArtUpdate(this.cover),
        date: String? = "2020-05-01",
        disc: Int? = 2,
        trackGain: String? = null,
    ) = editor.editSongMetadata(
        songId = id, newTitle = title, newArtist = "New artist", newAlbum = "New album", newAlbumArtist = "New album artist", newComposer = "New composer", newGenre = "Rock",
        newLyrics = lyrics, newTrackNumber = 5, newDiscNumber = disc, newReplayGainTrackGainDb = trackGain, coverArtUpdate = cover, newDate = date,
    )

    // ── WAV ────────────────────────────────────────────────────────────────────────────────────────

    /** The bytes of every chunk that is not a tag, in order: what the decoder gets. */
    private fun wavAudioChunks(file: File): List<ByteArray> {
        val bytes = file.readBytes()
        val out = ArrayList<ByteArray>()
        var at = 12
        while (at + 8 <= bytes.size) {
            val id = String(bytes, at, 4, Charsets.ISO_8859_1)
            val length = (bytes[at + 4].toInt() and 0xFF) or ((bytes[at + 5].toInt() and 0xFF) shl 8) or ((bytes[at + 6].toInt() and 0xFF) shl 16) or ((bytes[at + 7].toInt() and 0xFF) shl 24)
            val end = at + 8 + length + (length and 1)
            if (!id.equals("id3 ", ignoreCase = true) && id != "LIST") out += bytes.copyOfRange(at, minOf(end, bytes.size))
            at = end
        }
        return out
    }

    private fun assertWavAudioIsTheSame(before: List<ByteArray>, file: File) {
        val after = wavAudioChunks(file)
        assertEquals("the same audio chunks, in the same order", before.size, after.size)
        before.indices.forEach { assertArrayEquals("chunk $it is byte for byte as it was", before[it], after[it]) }
    }

    private fun wavEditWorksAndTheAudioDoesNotMove(fixture: String) = runBlocking {
        val file = rig.addFixture(1, fixture, "Old title")
        rig.firstScan()
        val item = rig.giveUserState(1)
        val itemsBefore = rig.items()
        val audioBefore = wavAudioChunks(file)

        val result = edit(rig.editor(), 1)

        assertTrue("the WAV edit: ${result.error} ${result.errorMessage}", result.success)
        assertWavAudioIsTheSame(audioBefore, file)
        // every field is in the file now: read back with a second, independent reader
        val tag = (AudioFileIO.read(file).tag as org.jaudiotagger.tag.wav.WavTag).iD3Tag
        assertNotNull("an ID3v2 chunk was written", tag)
        assertEquals("New title", tag.getFirst(FieldKey.TITLE))
        assertEquals("New artist", tag.getFirst(FieldKey.ARTIST))
        assertEquals("New album", tag.getFirst(FieldKey.ALBUM))
        assertEquals("New album artist", tag.getFirst(FieldKey.ALBUM_ARTIST))
        assertEquals("New composer", tag.getFirst(FieldKey.COMPOSER))
        assertEquals("Rock", tag.getFirst(FieldKey.GENRE))
        assertEquals("5", tag.getFirst(FieldKey.TRACK))
        assertEquals("2", tag.getFirst(FieldKey.DISC_NO))
        assertTrue(tag.getFirst(FieldKey.YEAR).startsWith("2020"))
        assertTrue(tag.getFirst(FieldKey.LYRICS).startsWith("line 1 of"))
        assertArrayEquals("the cover is the one that was chosen", cover, tag.firstArtwork.binaryData)
        assertEquals("the library row follows without a rescan", "New title", rig.dao.getSongByIdOnce(1)!!.title)
        assertTrue(rig.rescan() is ListenableWorker.Result.Success)
        assertEquals("no new item was minted", itemsBefore.map { it.libraryItemId }.toSet(), rig.items().map { it.libraryItemId }.toSet())
        assertTrue(rig.items().all { it.state == "ACTIVE" })
        assertEquals(rig.expectedUserState, rig.userStateOf(item))
    }

    @Test fun a_bare_wav_can_be_edited_and_keeps_its_audio_and_its_identity() = wavEditWorksAndTheAudioDoesNotMove("tone.wav")
    @Test fun a_wav_with_a_list_info_chunk_can_be_edited_and_keeps_its_audio() = wavEditWorksAndTheAudioDoesNotMove("tone-info.wav")

    // The layout JAudioTagger corrupts: LIST before the audio, an id3 chunk after it (probe: the data chunk's hash changed).
    @Test fun a_wav_with_list_before_the_audio_and_id3_after_it_keeps_its_audio() = wavEditWorksAndTheAudioDoesNotMove("tone-id3.wav")

    @Test fun the_info_chunk_and_the_id3_chunk_agree_and_the_other_info_fields_are_kept() = runBlocking {
        val file = rig.addFixture(1, "tone-info.wav", "Old title")
        rig.firstScan()
        assertTrue(edit(rig.editor(), 1, cover = null).success)
        val bytes = file.readBytes()
        val text = String(bytes, Charsets.ISO_8859_1)
        assertTrue("INFO title updated", text.contains("INAM") && text.contains("New title\u0000"))
        assertFalse("the old title is gone from the INFO chunk", text.contains("Old title"))
        assertTrue("the INFO fields that were not edited are kept (ISFT)", text.contains("ISFT"))
        assertTrue("INFO artist updated", text.contains("New artist\u0000"))
        // the file is still well formed: the RIFF size is the file's size
        val riffSize = (bytes[4].toInt() and 0xFF) or ((bytes[5].toInt() and 0xFF) shl 8) or ((bytes[6].toInt() and 0xFF) shl 16) or ((bytes[7].toInt() and 0xFF) shl 24)
        assertEquals(bytes.size - 8, riffSize)
    }

    @Test fun a_wav_edit_twice_is_stable() = runBlocking {
        val file = rig.addFixture(1, "tone-id3.wav", "Old title")
        rig.firstScan()
        val audio = wavAudioChunks(file)
        val editor = rig.editor()
        assertTrue(edit(editor, 1).success)
        assertTrue(edit(editor, 1, title = "Third title", lyrics = "", cover = CoverArtUpdate(isDeletion = true), date = "").success)
        assertWavAudioIsTheSame(audio, file)
        val tag = (AudioFileIO.read(file).tag as org.jaudiotagger.tag.wav.WavTag).iD3Tag
        assertEquals("Third title", tag.getFirst(FieldKey.TITLE))
        assertEquals("", tag.getFirst(FieldKey.LYRICS))
        assertNull("the cover was removed", tag.firstArtwork)
    }

    @Test fun a_wav_the_writer_cannot_read_with_certainty_is_refused_before_anything_is_written() = runBlocking {
        val file = rig.addFixture(1, "tone.wav", "Old title")
        rig.firstScan()
        // a streamed WAV: the data chunk claims more bytes than the file has
        val bytes = file.readBytes()
        val dataAt = String(bytes, Charsets.ISO_8859_1).indexOf("data")
        bytes[dataAt + 4] = 0xFF.toByte(); bytes[dataAt + 5] = 0xFF.toByte(); bytes[dataAt + 6] = 0xFF.toByte(); bytes[dataAt + 7] = 0xFF.toByte()
        file.writeBytes(bytes)
        rig.store.reindex(file.absolutePath)
        assertFalse(WavTagWriter.canEdit(file))
        val before = file.readBytes()
        val result = edit(rig.editor(), 1)
        assertFalse(result.success)
        assertTrue("the file is untouched", before.contentEquals(file.readBytes()))
    }

    // ── covers on the JAudioTagger branch ──────────────────────────────────────────────────────────

    private fun pictureOf(file: File): ByteArray? = AudioFileIO.read(file).tag?.firstArtwork?.binaryData

    @Test fun an_ogg_vorbis_cover_is_written() = runBlocking {
        val file = rig.addFixture(1, "tone-vorbis.ogg", "Old title")
        rig.firstScan()
        val result = edit(rig.editor(), 1)
        assertTrue("${result.error} ${result.errorMessage}", result.success)
        assertNull("the cover was written, so nothing is reported", result.coverNote)
        assertArrayEquals(cover, pictureOf(file))
    }

    @Test fun editing_a_text_field_keeps_the_cover_of_an_ogg_vorbis_file() = runBlocking {
        // The edit sheet re-sends the file's own cover on every save so that it is not lost; the writer must put it back.
        val file = rig.addFixture(1, "tone-vorbis.ogg", "Old title")
        rig.firstScan()
        val editor = rig.editor()
        assertTrue(edit(editor, 1).success)
        val second = edit(editor, 1, title = "Another title", cover = CoverArtUpdate(pictureOf(file)!!))
        assertTrue(second.success)
        assertArrayEquals("the cover survived a second edit", cover, pictureOf(file))
        assertEquals("Another title", AudioFileIO.read(file).tag.getFirst(FieldKey.TITLE))
    }

    @Test fun a_high_resolution_flac_cover_is_written_by_the_fallback_writer() = runBlocking {
        val file = rig.addFixture(1, "tone-highres.flac", "Old title")
        rig.firstScan()
        val result = edit(rig.editor(), 1)
        assertTrue("${result.error} ${result.errorMessage}", result.success)
        assertArrayEquals(cover, pictureOf(file))
    }

    @Test fun a_cover_can_be_removed_on_the_jaudiotagger_branch() = runBlocking {
        val file = rig.addFixture(1, "tone-vorbis.ogg", "Old title")
        rig.firstScan()
        val editor = rig.editor()
        assertTrue(edit(editor, 1).success)
        assertTrue(edit(editor, 1, cover = CoverArtUpdate(isDeletion = true)).success)
        assertNull(pictureOf(file))
    }

    @Test fun a_cover_that_cannot_be_written_is_reported_and_never_costs_the_old_cover() = runBlocking {
        val file = rig.addFixture(1, "tone-vorbis.ogg", "Old title")
        rig.firstScan()
        val editor = rig.editor()
        assertTrue(edit(editor, 1).success)
        // bytes that the writer cannot turn into a picture field
        val result = editor.editSongMetadata(
            songId = 1, newTitle = "Fourth title", newArtist = "New artist", newAlbum = "New album", newAlbumArtist = null, newComposer = null, newGenre = "Rock",
            newLyrics = "", newTrackNumber = 5, newDiscNumber = 2, coverArtUpdate = CoverArtUpdate(ByteArray(0), mimeType = "image/jpeg"),
        )
        assertTrue("the text was saved", result.success)
        assertEquals("Fourth title", AudioFileIO.read(file).tag.getFirst(FieldKey.TITLE))
        assertNotNull("and the result says the cover was not written", result.coverNote)
        assertArrayEquals("the old cover is still there", cover, pictureOf(file))
    }

    // ── hi-resolution FLAC ─────────────────────────────────────────────────────────────────────────

    @Test fun every_flac_goes_to_tag_lib_first_and_wav_never_to_jaudiotagger() {
        // TagLib writes 24-bit up to 384 kHz and 32-bit FLAC safely (host probe), so the editor no longer routes high-resolution files away from it; the payload guard is the net under it.
        assertEquals(listOf(SongMetadataEditor.Writer.TAG_LIB, SongMetadataEditor.Writer.J_AUDIO_TAGGER), SongMetadataEditor.writersFor("flac", tagLibAvailable = true))
        assertEquals(listOf(SongMetadataEditor.Writer.TAG_LIB, SongMetadataEditor.Writer.J_AUDIO_TAGGER), SongMetadataEditor.writersFor("mp3", tagLibAvailable = true))
        assertEquals("on a host without TagLib the fallback writes", listOf(SongMetadataEditor.Writer.J_AUDIO_TAGGER), SongMetadataEditor.writersFor("flac", tagLibAvailable = false))
        assertEquals(listOf(SongMetadataEditor.Writer.WAV), SongMetadataEditor.writersFor("wav", tagLibAvailable = true))
        assertEquals(listOf(SongMetadataEditor.Writer.WAV), SongMetadataEditor.writersFor("wav", tagLibAvailable = false))
        assertEquals(listOf(SongMetadataEditor.Writer.J_AUDIO_TAGGER), SongMetadataEditor.writersFor("ogg", tagLibAvailable = true))
        assertEquals(listOf(SongMetadataEditor.Writer.VORBIS_JAVA), SongMetadataEditor.writersFor("opus", tagLibAvailable = true))
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun the_payload_guard_accepts_an_edit_that_only_changed_tags() = runBlocking {
        for (fixture in listOf("tone.flac", "tone-highres.flac")) {
            val original = rig.addFixture(if (fixture == "tone.flac") 1 else 2, fixture, "Old title")
            val copy = File(rig.musicDir, "copy-$fixture").also { original.copyTo(it) }
            val tagged = AudioFileIO.read(copy).also { it.tagOrCreateAndSetDefault.setField(FieldKey.TITLE, "Different") }
            tagged.commit()
            assertTrue("$fixture: tags only", AudioPayloadGuard.sameAudio(original, copy, "flac"))
        }
        val wav = rig.addFixture(3, "tone-id3.wav", "Old title")
        val wavCopy = File(rig.musicDir, "copy.wav")
        WavTagWriter.edit(wav, wavCopy, mapOf(WavTagWriter.InfoId.TITLE to "Different")) { it.setField(FieldKey.TITLE, "Different") }
        assertTrue(AudioPayloadGuard.sameAudio(wav, wavCopy, "wav"))
    }

    @Test fun the_payload_guard_refuses_a_flac_or_a_wav_whose_audio_changed() = runBlocking {
        val flac = rig.addFixture(1, "tone.flac", "Old title")
        val flacCopy = File(rig.musicDir, "damaged.flac")
        val bytes = flac.readBytes()
        bytes[bytes.size - 100] = (bytes[bytes.size - 100].toInt() xor 0x55).toByte() // one byte of audio
        flacCopy.writeBytes(bytes)
        assertFalse(AudioPayloadGuard.sameAudio(flac, flacCopy, "flac"))
        val shorter = File(rig.musicDir, "shorter.flac").also { it.writeBytes(flac.readBytes().copyOf(flac.length().toInt() - 1)) }
        assertFalse("a missing last byte is a change", AudioPayloadGuard.sameAudio(flac, shorter, "flac"))

        val wav = rig.addFixture(2, "tone-id3.wav", "Old title")
        val wavBytes = wav.readBytes()
        val dataAt = String(wavBytes, Charsets.ISO_8859_1).indexOf("data") + 8
        wavBytes[dataAt + 1000] = (wavBytes[dataAt + 1000].toInt() xor 0x55).toByte()
        val wavCopy = File(rig.musicDir, "damaged.wav").also { it.writeBytes(wavBytes) }
        assertFalse(AudioPayloadGuard.sameAudio(wav, wavCopy, "wav"))
        assertEquals("another format has no runtime check", true, AudioPayloadGuard.sameAudio(wav, wavCopy, "mp3"))
        assertTrue(sha256(wavBytes) != sha256(wav.readBytes()))
    }
}
