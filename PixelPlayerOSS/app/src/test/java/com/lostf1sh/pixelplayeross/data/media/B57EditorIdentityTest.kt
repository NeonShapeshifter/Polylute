package com.lostf1sh.pixelplayeross.data.media

import android.app.Application
import androidx.work.ListenableWorker
import com.lostf1sh.pixelplayeross.data.database.LibraryItemEntity
import com.lostf1sh.pixelplayeross.data.identity.EditedEvidence
import com.lostf1sh.pixelplayeross.data.identity.EditedFileFacts
import com.lostf1sh.pixelplayeross.data.identity.ItemEvidence
import io.mockk.coEvery
import io.mockk.spyk
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B57 1.1, the P0 B56 found: a tag write that changes the file's size used to make the next scan mint a new identity and leave the old item dormant with the favourites, plays,
 * playlists, lyrics and bookmarks. Every test here runs the real editor on a real (synthetic) file, a real library scan and the real database, and asks the same question:
 * after edit + rescan, is the item still the same item, ACTIVE, with everything that hangs off it?
 *
 * One editor function writes every tag, so each path through it (a single song, several songs, a cover, lyrics into the file, ReplayGain) gets its own test, and the
 * single-song one runs for every format the editor writes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class B57EditorIdentityTest {
    private lateinit var rig: EditorIdentityRig

    @Before fun setUp() { rig = EditorIdentityRig() }
    @After fun tearDown() { rig.close() }

    /** A real, decodable JPEG (pseudo-random noise, ~100 KB: far more than any padding) generated here; no artwork from anyone's library. */
    private val cover: ByteArray = run {
        val random = java.util.Random(57)
        val image = java.awt.image.BufferedImage(320, 320, java.awt.image.BufferedImage.TYPE_INT_RGB)
        for (x in 0 until 320) for (y in 0 until 320) image.setRGB(x, y, random.nextInt(0xFFFFFF))
        java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(image, "jpg", it) }.toByteArray()
    }
    private val longLyrics = (1..400).joinToString("\n") { "line $it of a synthetic lyric used only to make the tag grow" }

    private suspend fun edit(
        editor: SongMetadataEditor,
        id: Long,
        title: String = "Cine (Remastered)",
        artist: String = "Gemini Sound feat. A",
        album: String = "Noche (Deluxe)",
        track: Int = 3,
        disc: Int? = 1,
        lyrics: String = "",
        cover: CoverArtUpdate? = null,
        trackGain: String? = null,
        albumGain: String? = null,
    ) = editor.editSongMetadata(
        songId = id, newTitle = title, newArtist = artist, newAlbum = album, newAlbumArtist = null, newComposer = null, newGenre = "Rock",
        newLyrics = lyrics, newTrackNumber = track, newDiscNumber = disc, newReplayGainTrackGainDb = trackGain, newReplayGainAlbumGainDb = albumGain, coverArtUpdate = cover,
    )

    private fun assertItemsKept(before: List<LibraryItemEntity>) {
        val after = rig.items()
        assertEquals("no new item was minted", before.map { it.libraryItemId }.toSet(), after.map { it.libraryItemId }.toSet())
        assertTrue("every item is ACTIVE", after.all { it.state == "ACTIVE" })
        assertEquals(0, after.count { it.state == "DORMANT" })
    }

    private fun singleSongEditKeepsIdentity(
        fixture: String,
        expectedTitle: String = "Cine (Remastered)",
        mutate: suspend (SongMetadataEditor, Long) -> SongMetadataEditResult = { editor, id -> edit(editor, id, lyrics = longLyrics, cover = CoverArtUpdate(cover)) },
    ) = runBlocking {
        val file = rig.addFixture(1, fixture, "Cine")
        rig.firstScan()
        val item = rig.giveUserState(1)
        val itemsBefore = rig.items()
        val sizeBefore = file.length()

        val result = mutate(rig.editor(), 1)

        assertTrue("the editor wrote the file: ${result.error} ${result.errorMessage}", result.success)
        assertNotEquals("the edit changed the file's size, which is what used to lose the identity", sizeBefore, file.length())
        assertEquals("the library row follows the write without a rescan", expectedTitle, rig.dao.getSongByIdOnce(1)!!.title)
        assertTrue(rig.rescan() is ListenableWorker.Result.Success)

        assertItemsKept(itemsBefore)
        assertEquals(item, rig.dao.getSongByLocatorOnce(1)!!.libraryItemId)
        assertEquals(rig.expectedUserState, rig.userStateOf(item))
    }

    @Test fun flac_single_song_cover_and_lyrics_keep_the_identity() = singleSongEditKeepsIdentity("tone.flac")
    // B57-fix1 5.1: a WAV edit used to be refused every time (JAudioTagger's WavTag cannot delete the lyrics) and, for some layouts, moved the audio. WavTagWriter writes it now, so
    // a WAV takes the same edit + rescan as every other format: cover and lyrics included, for the three layouts a WAV has (bare, LIST/INFO, LIST before the audio and id3 after it).
    @Test fun wav_single_song_cover_and_lyrics_keep_the_identity() = singleSongEditKeepsIdentity("tone.wav")
    @Test fun wav_with_a_list_info_chunk_cover_and_lyrics_keep_the_identity() = singleSongEditKeepsIdentity("tone-info.wav")
    @Test fun wav_with_list_before_the_audio_and_id3_after_it_keeps_the_identity() = singleSongEditKeepsIdentity("tone-id3.wav")

    @Test fun ogg_vorbis_single_song_cover_and_lyrics_keep_the_identity() = singleSongEditKeepsIdentity("tone-vorbis.ogg")
    @Test fun opus_single_song_cover_and_lyrics_keep_the_identity() = singleSongEditKeepsIdentity("tone-opus.opus")
    @Test fun mp3_single_song_cover_and_lyrics_keep_the_identity() = singleSongEditKeepsIdentity("tone.mp3")
    @Test fun m4a_aac_single_song_cover_and_lyrics_keep_the_identity() = singleSongEditKeepsIdentity("tone-aac.m4a")
    @Test fun m4a_alac_single_song_cover_and_lyrics_keep_the_identity() = singleSongEditKeepsIdentity("tone-alac.m4a")
    @Test fun high_resolution_flac_written_by_the_fallback_writer_keeps_the_identity() = singleSongEditKeepsIdentity("tone-highres.flac")

    // A cover is embedded by TagLib on the phone, by vorbis-java for Opus and by JAudioTagger for Ogg Vorbis and the fallback (B57-fix1 5.2: it used to throw there and the cover was dropped, so
    // the cover path was only exercised on Opus). Each of the three writers gets a cover-alone case, and each really grows the file.
    @Test fun a_cover_alone_keeps_the_identity() = singleSongEditKeepsIdentity("tone-opus.opus", expectedTitle = "Cine") { editor, id ->
        edit(editor, id, title = "Cine", artist = "Gemini Sound", album = "Noche", track = 1, disc = null, cover = CoverArtUpdate(cover))
    }

    @Test fun a_cover_alone_through_the_jaudiotagger_branch_keeps_the_identity() = singleSongEditKeepsIdentity("tone-vorbis.ogg", expectedTitle = "Cine") { editor, id ->
        edit(editor, id, title = "Cine", artist = "Gemini Sound", album = "Noche", track = 1, disc = null, cover = CoverArtUpdate(cover))
    }

    @Test fun a_cover_alone_on_a_wav_keeps_the_identity() = singleSongEditKeepsIdentity("tone.wav", expectedTitle = "Cine") { editor, id ->
        edit(editor, id, title = "Cine", artist = "Gemini Sound", album = "Noche", track = 1, disc = null, cover = CoverArtUpdate(cover))
    }

    @Test fun lyrics_written_into_the_file_keep_the_identity() = singleSongEditKeepsIdentity("tone.flac") { editor, id ->
        edit(editor, id, lyrics = longLyrics)
    }

    @Test fun replaygain_with_a_lyric_keeps_the_identity() = singleSongEditKeepsIdentity("tone.flac", expectedTitle = "Cine") { editor, id ->
        // ReplayGain alone is a few bytes and a FLAC's padding absorbs it, so the size would not move; the lyric makes the file grow and the gain values are written with it.
        edit(editor, id, title = "Cine", artist = "Gemini Sound", album = "Noche", track = 1, disc = null, trackGain = "-6.50", albumGain = "-5.25", lyrics = longLyrics)
    }

    @Test fun a_tag_that_shrinks_the_file_keeps_the_identity() = runBlocking {
        // Ogg Vorbis rewrites its comment header, so unlike a FLAC (whose padding keeps the size) it really shrinks.
        val file = rig.addFixture(1, "tone-vorbis.ogg", "Cine")
        rig.firstScan()
        val item = rig.giveUserState(1)
        val editor = rig.editor()
        // Two scans in a row: the first edit grows the file with a long lyric and a cover, the second takes both away.
        assertTrue(edit(editor, 1, lyrics = longLyrics, cover = CoverArtUpdate(cover)).success)
        assertTrue(rig.rescan() is ListenableWorker.Result.Success)
        val grown = file.length()
        val itemsBefore = rig.items()
        assertTrue(edit(editor, 1, title = "Cine", artist = "Gemini Sound", album = "Noche", track = 1, disc = null, lyrics = "", cover = CoverArtUpdate(isDeletion = true)).success)
        assertTrue("the file shrank: ${file.length()} < $grown", file.length() < grown)
        assertTrue(rig.rescan() is ListenableWorker.Result.Success)
        assertItemsKept(itemsBefore)
        assertEquals(item, rig.dao.getSongByLocatorOnce(1)!!.libraryItemId)
        assertEquals(rig.expectedUserState, rig.userStateOf(item))
    }

    @Test fun several_songs_edited_one_after_another_keep_every_identity() = runBlocking {
        val files = (1L..4L).map { rig.addFixture(it, "tone.flac", "Track $it", track = it.toInt()) }
        rig.firstScan()
        val items = (1L..4L).associateWith { rig.giveUserState(it) }
        val itemsBefore = rig.items()
        val editor = rig.editor()
        for (id in 1L..4L) {
            val result = edit(editor, id, title = "Track $id", artist = "Gemini Sound", album = "Noche (Deluxe)", track = id.toInt(), disc = 1, lyrics = longLyrics, cover = CoverArtUpdate(cover))
            assertTrue(result.errorMessage, result.success)
        }
        assertTrue(files.all { it.length() > 30_000 })
        assertTrue(rig.rescan() is ListenableWorker.Result.Success)
        assertItemsKept(itemsBefore)
        assertEquals(4, rig.items().size)
        for ((id, item) in items) assertEquals(item, rig.dao.getSongByLocatorOnce(id)!!.libraryItemId)
        assertTrue(items.values.all { rig.userStateOf(it).favourite && rig.userStateOf(it).lyrics == "user lyrics" })
    }

    @Test fun the_evidence_the_editor_records_is_what_the_next_scan_computes() = runBlocking {
        rig.addFixture(1, "tone.flac", "Cine")
        rig.firstScan()
        val item = rig.giveUserState(1)
        edit(rig.editor(), 1, lyrics = longLyrics, cover = CoverArtUpdate(cover))
        val recorded = rig.items().single { it.libraryItemId == item }
        assertTrue(recorded.stableKey.isNotEmpty() && recorded.fileSignature.isNotEmpty())
        assertTrue(rig.rescan() is ListenableWorker.Result.Success)
        val afterScan = rig.items().single { it.libraryItemId == item }
        assertEquals("the scan found nothing to correct", recorded.stableKey, afterScan.stableKey)
        assertEquals(recorded.fileSignature, afterScan.fileSignature)
        // and it is what the frozen functions say for the row MediaStore holds now
        val row = rig.store.row(1)
        val expected = EditedEvidence.of(EditedFileFacts(row["duration"] as Long, row["_size"] as Long, row["title"] as String, row["artist"] as String, row["album"] as String, (row["track"] as Long).toInt()))
        assertEquals(expected, ItemEvidence(recorded.stableKey, recorded.fileSignature))
    }

    // ── the half-finished edit: the write succeeded and what comes after did not ──────────────────────────

    @Test fun a_commit_that_fails_after_the_write_leaves_no_evidence_and_the_next_scan_keeps_the_identity() = runBlocking {
        val file = rig.addFixture(1, "tone.flac", "Cine")
        rig.firstScan()
        val item = rig.giveUserState(1)
        val itemsBefore = rig.items()
        val sizeBefore = file.length()
        val dao = spyk(rig.dao)
        coEvery { dao.commitEditedSongMetadata(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws IllegalStateException("disk full")
        val editor = SongMetadataEditor(rig.context, dao, rig.prefs, tagLibEnabled = false, rescanner = MediaRescanner { rig.store.reindex(it) })

        val result = edit(editor, 1, lyrics = longLyrics, cover = CoverArtUpdate(cover))

        assertTrue("the editor reports the failure", !result.success)
        assertNotEquals("but the file was written", sizeBefore, file.length())
        val parked = rig.items().single { it.libraryItemId == item }
        assertEquals("the item was left with no evidence", ItemEvidence.NONE, ItemEvidence(parked.stableKey, parked.fileSignature))
        assertTrue(rig.rescan() is ListenableWorker.Result.Success)
        assertItemsKept(itemsBefore)
        assertEquals(item, rig.dao.getSongByLocatorOnce(1)!!.libraryItemId)
        assertEquals(rig.expectedUserState, rig.userStateOf(item))
        val refilled = rig.items().single { it.libraryItemId == item }
        assertTrue("the scan recorded the evidence again", refilled.stableKey.isNotEmpty() && refilled.fileSignature.isNotEmpty())
    }

    @Test fun when_the_evidence_cannot_be_parked_the_file_is_not_written() = runBlocking {
        val file = rig.addFixture(1, "tone.flac", "Cine")
        rig.firstScan()
        rig.giveUserState(1)
        val bytesBefore = file.readBytes()
        val evidenceBefore = rig.items().single()
        val dao = spyk(rig.dao)
        coEvery { dao.parkEvidenceForSong(any()) } throws IllegalStateException("database is locked")
        val editor = SongMetadataEditor(rig.context, dao, rig.prefs, tagLibEnabled = false, rescanner = MediaRescanner { rig.store.reindex(it) })

        val result = edit(editor, 1, lyrics = longLyrics, cover = CoverArtUpdate(cover))

        assertTrue(!result.success)
        assertEquals(MetadataEditError.IO_ERROR, result.error)
        assertTrue("the audio file is byte for byte as it was", bytesBefore.contentEquals(file.readBytes()))
        assertEquals(evidenceBefore, rig.items().single())
    }

    @Test fun a_write_that_fails_with_the_file_untouched_puts_the_evidence_back() = runBlocking {
        val file = rig.addFixture(1, "tone.flac", "Cine")
        rig.firstScan()
        rig.giveUserState(1)
        file.writeBytes(ByteArray(2_000) { 3 }) // no longer audio: every writer refuses it
        rig.store.reindex(file.absolutePath)
        val evidenceBefore = rig.items().single()

        val result = edit(rig.editor(), 1, lyrics = longLyrics)

        assertTrue(!result.success)
        assertEquals("evidence restored, not left empty", evidenceBefore, rig.items().single())
    }

    @Test fun a_scanner_that_has_not_caught_up_leaves_the_evidence_empty_and_a_later_scan_keeps_the_identity() = runBlocking {
        rig.addFixture(1, "tone.flac", "Cine")
        rig.firstScan()
        val item = rig.giveUserState(1)
        val itemsBefore = rig.items()
        rig.rescanBehaviour = { /* the scanner answers but MediaStore still holds the old size */ }

        val result = edit(rig.editor(), 1, lyrics = longLyrics, cover = CoverArtUpdate(cover))
        assertTrue(result.errorMessage, result.success)

        val parked = rig.items().single { it.libraryItemId == item }
        assertEquals("not confirmed, so not recorded", ItemEvidence.NONE, ItemEvidence(parked.stableKey, parked.fileSignature))
        rig.store.reindex(File(rig.store.row(1)["_data"] as String).absolutePath) // MediaStore catches up before the next scan
        assertTrue(rig.rescan() is ListenableWorker.Result.Success)
        assertItemsKept(itemsBefore)
        assertEquals(rig.expectedUserState, rig.userStateOf(item))
    }

    // ── a scan that read the item before the edit must not put its older reading back ────────────────────

    @Test fun a_scan_that_read_the_evidence_before_the_edit_does_not_overwrite_the_edits() = runBlocking {
        rig.addFixture(1, "tone.flac", "Cine")
        rig.firstScan()
        val item = rig.giveUserState(1)
        val atScanStart = rig.items().single { it.libraryItemId == item }
        // the editor parks and then records the evidence of the file it wrote
        rig.dao.parkEvidenceForSong(1)
        rig.dao.updateLibraryItemEvidence(item, "edited-key", "edited-signature")
        // the scan, which read `atScanStart` before all that, now commits what it computed from MediaStore as it was
        val stale = LibraryItemEntity(libraryItemId = item, stableKey = "old-reading", fileSignature = "old-signature")
        rig.dao.updateLibraryItemEvidenceBatchGuarded(listOf(stale), mapOf(item to ItemEvidence(atScanStart.stableKey, atScanStart.fileSignature)))
        val after = rig.items().single { it.libraryItemId == item }
        assertEquals("edited-key", after.stableKey)
        assertEquals("edited-signature", after.fileSignature)
        // an item the scan did not read before (a new one) is still written
        rig.dao.updateLibraryItemEvidenceBatchGuarded(listOf(stale), emptyMap())
        assertEquals("old-reading", rig.items().single { it.libraryItemId == item }.stableKey)
    }

    @Test fun the_evidence_of_a_track_with_a_disc_number_uses_the_frozen_arithmetic() {
        // MediaStore reports disc 1 track 3 as 1003; the fingerprint reads it with the frozen low-12-bits / next-12-bits split, as the scan does.
        assertEquals(1003, EditedEvidence.rawTrack(3, 1))
        assertEquals(3, EditedEvidence.rawTrack(3, null))
        assertEquals(3, EditedEvidence.rawTrack(3, 0))
    }
}
