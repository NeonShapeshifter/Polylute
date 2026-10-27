package com.lostf1sh.pixelplayeross.presentation.components.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.lostf1sh.pixelplayeross.data.model.Lyrics
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.model.SyncedLine
import com.lostf1sh.pixelplayeross.presentation.viewmodel.LyricsSearchUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlaybackAudioMetadata
import com.lostf1sh.pixelplayeross.presentation.viewmodel.StablePlayerState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B41-fix1 (B7): the line being sung under the cover takes up to two lines instead of being cut at the end of one ("Dice, qué bella camina
 * esa muchacha cu..." on the HONOR), shrinks a little only when two are not enough (never under 85 % of its size), and the block keeps one
 * height while lines change, so the title and the controls under it never jump; the next line stays one line, dim, and may be cut.
 *
 * It names nothing the batch added (the block's height is read from a box around it, the text's layout from its semantics), so it
 * compiles against the tree as it was before the batch, where `a_long_current_line_takes_two_lines_with_no_ellipsis` and the shrink test
 * **fail**: the line was one line and ended in an ellipsis. The block's height was already steady before; that test is a control.
 * Real text metrics (native graphics), because wrapping is what is being measured.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w411dp-h900dp-xxhdpi")
class B41Fix1LyricsTwoLinesProbeTest {

    @get:Rule
    val compose = createComposeRule()

    private var current by mutableStateOf("Dice, qué bella camina esa muchacha cuando se mueve")
    private var next by mutableStateOf("¿Cómo dice? Qué bella camina esa muchacha")
    private var blockHeight = -1

    private fun lyricsState(lyrics: Lyrics) = PlayerLyricsState(
        searchState = LyricsSearchUiState.Idle,
        stablePlayerStateFlow = MutableStateFlow(StablePlayerState()),
        playbackPositionFlow = MutableStateFlow(0L),
        externalLyricsEnabled = false, lyricsSyncOffset = 0, immersiveLyricsEnabled = false, immersiveLyricsTimeout = 4000L,
        isImmersiveTemporarilyDisabled = false, isShuffleEnabled = false, repeatMode = 0, isFavorite = false,
        lyricsProvider = { lyrics }, lyricsResolveCompletedProvider = { true }, underCover = true,
    )

    private fun progressState() = PlayerProgressState.of(
        song = Song(
            id = "1", title = "En La Cama", artist = "Nicky Jam", artistId = 7L, album = "Exitos", albumId = 9L, path = "/m/1.flac",
            contentUriString = "content://1", albumArtUriString = null, duration = 157_000L, mimeType = "audio/flac", bitrate = 1000, sampleRate = 44100,
        ),
        metadata = PlaybackAudioMetadata(), showFileInfo = false,
        currentPositionProvider = { 2_000L }, totalDurationProvider = { 157_000L }, isPlayingProvider = { true },
    )

    /** The block, in a column as wide as the HONOR's page (336 dp at 1.0 density: the cover's width), with a marker under it. */
    private fun show() {
        compose.setContent {
            // the song's two lines are one synced line (being sung from 1 s) and the next from 30 s: both are chosen by the state above
            val lyrics = Lyrics(synced = listOf(SyncedLine(1_000, current), SyncedLine(30_000, next)))
            Column(Modifier.width(336.dp)) {
                Box(Modifier.onGloballyPositioned { blockHeight = it.size.height }) {
                    PlayerLyricsLines(lyricsState(lyrics), progressState(), onOpen = {})
                }
            }
        }
        compose.waitForIdle()
    }

    private fun layoutOf(text: String): TextLayoutResult {
        // (the block is one clickable, whose merged node holds both lines: each line is looked up on its own)
        val node = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.first()
    }

    private fun anEllipsisIn(layout: TextLayoutResult) = (0 until layout.lineCount).any { layout.isLineEllipsized(it) }

    @Test
    fun a_long_current_line_takes_two_lines_with_no_ellipsis() {
        show()
        val layout = layoutOf(current)
        assertEquals("the line that was cut on the HONOR takes two lines", 2, layout.lineCount)
        assertFalse("and is not cut", anEllipsisIn(layout))
    }

    @Test
    fun a_line_that_two_lines_cannot_hold_shrinks_within_the_bound_and_a_longer_one_is_cut_at_the_bound() {
        show()
        val base = layoutOf(current).layoutInput.style.fontSize.value
        // find, by growing the line one word at a time, the first that two lines of full size cannot hold: it must shrink, not be cut
        var words = current
        var shrunk: Float? = null
        for (i in 1..40) {
            words += " cuando baila"
            current = words
            compose.waitForIdle()
            val layout = layoutOf(words)
            val ratio = layout.layoutInput.style.fontSize.value / base
            if (ratio < 0.999f) {
                shrunk = ratio
                assertEquals("shrunk, it takes two lines", 2, layout.lineCount)
                assertFalse("shrunk, it is not cut", anEllipsisIn(layout))
                break
            }
            assertFalse("at full size it is not cut before it has had to shrink", anEllipsisIn(layout))
        }
        assertTrue("a line that needs more than two lines shrinks", shrunk != null)
        assertTrue("never under 85 % (was $shrunk)", shrunk!! >= 0.85f - 1e-4f)
        // a very long line: shrunk as far as it may go, and then cut at the end of the second line
        current = "Dice, qué bella camina esa muchacha cuando se mueve ".repeat(8).trim()
        compose.waitForIdle()
        val longest = layoutOf(current)
        assertEquals(0.85f, longest.layoutInput.style.fontSize.value / base, 1e-3f)
        assertEquals(2, longest.lineCount)
        assertTrue("and is cut at the end of the second line", anEllipsisIn(longest))
    }

    @Test
    fun the_next_line_stays_one_line_dim_and_may_be_cut() {
        show()
        next = "¿Cómo dice? Qué bella camina esa muchacha cuando se mueve por toda la calle de noche"
        compose.waitForIdle()
        val layout = layoutOf(next)
        assertEquals(1, layout.lineCount)
        assertTrue(anEllipsisIn(layout))
    }

    @Test
    fun the_block_keeps_its_height_whatever_the_line_is() {
        show()
        val heights = mutableListOf<Pair<String, Int>>()
        for (text in listOf(
            "Short",
            "Dice, qué bella camina esa muchacha cuando se mueve",
            "Dice, qué bella camina esa muchacha cuando se mueve ".repeat(3).trim(),
            "Dice, qué bella camina esa muchacha cuando se mueve ".repeat(9).trim(),
            "·",
        )) {
            current = text
            compose.waitForIdle()
            heights += text.take(12) to blockHeight
        }
        val first = heights.first().second
        assertTrue("a measured block", first > 0)
        for ((label, h) in heights) assertEquals("height with \"$label\"", first, h)
    }
}
