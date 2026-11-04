package com.lostf1sh.pixelplayeross.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.test.core.app.ApplicationProvider
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.media.MetadataEditSupport
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.presentation.components.EditSongContent
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B57-fix1 5.1: a file the editor cannot write with certainty says so **before** the listener types: the sheet shows "Tags can't be edited for this file type" and has no Save to press. A file it can
 * write has neither the note nor a disabled Save. (The only file the editor refuses is a WAV it cannot read with certainty; a WAV it can read is edited, see B57Fix1EditorFaultsTest.)
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xhdpi")
class B57Fix1EditorSheetTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var dir: File

    @Before fun setUp() {
        dir = kotlin.io.path.createTempDirectory("b57fix1-sheet").toFile()
        // the sheet reads the file's own tags with TagLib (a native library a JVM does not load): here the file has none to show
        io.mockk.mockkObject(com.lostf1sh.pixelplayeross.data.media.AudioMetadataReader)
        io.mockk.every { com.lostf1sh.pixelplayeross.data.media.AudioMetadataReader.read(any<File>(), any(), any()) } returns null
    }
    @After fun tearDown() {
        io.mockk.unmockkObject(com.lostf1sh.pixelplayeross.data.media.AudioMetadataReader)
        dir.deleteRecursively()
    }

    private fun wav(name: String, streamed: Boolean): File = File(dir, name).also { file ->
        val bytes = javaClass.classLoader!!.getResourceAsStream("editor/tone.wav")!!.use { it.readBytes() }
        if (streamed) {
            // a streamed WAV: the data chunk claims more bytes than the file has
            val at = String(bytes, Charsets.ISO_8859_1).indexOf("data")
            for (i in 4..7) bytes[at + i] = 0xFF.toByte()
        }
        file.writeBytes(bytes)
    }

    private fun song(path: String) = Song(
        id = "1", title = "Track", artist = "Gemini Sound", artistId = 1, album = "Noche", albumId = 1, albumArtist = "Gemini Sound", path = path, contentUriString = "",
        albumArtUriString = null, duration = 200_000, genre = "Rock", trackNumber = 1, discNumber = 1, year = 2019, mimeType = null, bitrate = null, sampleRate = null,
    )

    private fun sheet(path: String, dark: Boolean = false) = compose.setContent {
        DapTheme(darkTheme = dark, baseTheme = if (dark) DapBaseTheme.Graphite else DapBaseTheme.Light, accent = DapAccent.Sage) {
            Box(Modifier.fillMaxSize()) { EditSongContent(song = song(path), onDismiss = {}, onSave = { _, _, _, _, _, _, _, _, _, _, _, _, _ -> }) }
        }
    }

    @Test fun the_support_check_says_no_only_for_a_wav_it_cannot_read_and_yes_otherwise() {
        assertTrue(MetadataEditSupport.canEdit(wav("ok.wav", streamed = false).path))
        assertFalse(MetadataEditSupport.canEdit(wav("streamed.wav", streamed = true).path))
        assertTrue("a file that is not there is the editor's own error, not the sheet's", MetadataEditSupport.canEdit(File(dir, "missing.flac").path))
        assertTrue(MetadataEditSupport.canEdit(null))
        assertTrue(MetadataEditSupport.canEdit(""))
        assertEquals(true, MetadataEditSupport.canEdit(File(dir, "x.mp3").also { it.writeBytes(ByteArray(64) { 7 }) }.path))
    }

    @Test fun an_unreadable_wav_shows_the_note_and_has_no_save_to_press() {
        sheet(wav("streamed.wav", streamed = true).path)
        compose.waitForIdle()
        compose.onNodeWithText(context.getString(R.string.edit_song_unsupported_format)).assertExists()
        compose.onNodeWithText(context.getString(R.string.action_save)).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.cancel)).assertIsEnabled()
        B57Renders.save(compose, "editor-song-unsupported-light")
    }

    @Test fun an_unreadable_wav_in_the_dark() {
        sheet(wav("streamed.wav", streamed = true).path, dark = true)
        compose.waitForIdle()
        B57Renders.save(compose, "editor-song-unsupported-dark")
    }

    @Test fun a_wav_that_can_be_edited_has_no_note_and_a_live_save() {
        sheet(wav("ok.wav", streamed = false).path)
        compose.waitForIdle()
        compose.onAllNodesWithText(context.getString(R.string.edit_song_unsupported_format)).assertCountEquals(0)
        compose.onNodeWithText(context.getString(R.string.action_save)).assertIsEnabled()
    }
}
