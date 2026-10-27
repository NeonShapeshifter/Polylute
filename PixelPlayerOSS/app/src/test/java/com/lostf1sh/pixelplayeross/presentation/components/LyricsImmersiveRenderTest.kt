package com.lostf1sh.pixelplayeross.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.Lyrics
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.model.SyncedLine
import com.lostf1sh.pixelplayeross.data.repository.parseBestEmbeddedLyricsField
import com.lostf1sh.pixelplayeross.presentation.viewmodel.LyricsSearchUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.StablePlayerState
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.utils.LyricsUtils
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B40-F5: the lyrics sheet in its immersive mode, rendered at the HONOR's size over made-up synced lyrics. The clock is the test's, so
 * "after four quiet seconds" is exact. The sheet is the real one (the same composable the player opens), with only the player's state and
 * the two actions the page can take (seek, play/pause) replaced.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xhdpi")
class LyricsImmersiveRenderTest {
    @get:Rule
    val compose = createComposeRule()

    private val song = Song(
        id = "1", title = "La Costa Del Silencio", artist = "Mägo de Oz", artistId = 7L, album = "Gaia", albumId = 9L, path = "/m/1.flac",
        contentUriString = "content://1", albumArtUriString = null, duration = 240_000L, mimeType = "audio/flac", bitrate = 1003, sampleRate = 44100,
    )

    private val lines = (0 until 40).map { SyncedLine(time = it * 5_000, line = "Line number $it of a song that is playing") }

    private val position = MutableStateFlow(102_000L)
    private val playerState = MutableStateFlow(StablePlayerState())
    private val seeks = mutableListOf<Long>()

    private fun show(lyrics: Lyrics = Lyrics(synced = lines), timeout: Long = 4_000L, enabled: Boolean = true, playing: Boolean = true) {
        playerState.value = StablePlayerState(
            currentSong = song, isPlaying = playing, totalDuration = 240_000L, lyrics = lyrics, lyricsResolveCompleted = true,
        )
        compose.setContent {
            Box(Modifier.fillMaxSize().background(Dap.background)) {
                CompositionLocalProvider(LocalMaterialTheme provides darkColorScheme()) {
                    LyricsSheet(
                        stablePlayerStateFlow = playerState,
                        playbackPositionFlow = position,
                        lyricsSearchUiState = LyricsSearchUiState.Idle,
                        resetLyricsForCurrentSong = {},
                        onSearchLyrics = {},
                        onPickResult = {},
                        onManualSearch = { _, _ -> },
                        onImportLyrics = {},
                        onDismissLyricsSearch = {},
                        lyricsSyncOffset = 0,
                        onLyricsSyncOffsetChange = {},
                        lyricsTextStyle = MaterialTheme.typography.titleLarge,
                        colorScheme = darkColorScheme(),
                        onLyricsAlignmentChange = {},
                        onShowLyricsTranslationChange = {},
                        onShowLyricsRomanizationChange = {},
                        onSeekTo = { seeks += it },
                        onPlayPause = {},
                        immersiveLyricsEnabled = enabled,
                        immersiveLyricsTimeout = timeout,
                        isImmersiveTemporarilyDisabled = false,
                        onSaveLyricsToFile = { _, _, _ -> },
                        externalLyricsEnabled = false,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    /** The play/pause disc is part of the controls: it says "Pause" while the song plays and "Play" when it does not. */
    private fun controlsShown() = listOf("Pause", "Play").any { compose.onAllNodesWithContentDescription(it).fetchSemanticsNodes().isNotEmpty() }

    private fun settle(ms: Long) {
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }

    private fun lineBounds(timeMs: Int): Rect = compose.onNodeWithTag("synced_line_$timeMs").fetchSemanticsNode().boundsInRoot

    private val rootHeight get() = compose.onRoot().fetchSemanticsNode().boundsInRoot.height

    /**
     * Where the folded page keeps the current line: the middle of the list's viewport (the page less the list's 8 dp of padding above and
     * 24 dp below, 16 and 48 px here), 32 dp (64 px) above that middle.
     */
    private val highlightCentre get() = 16f + (rootHeight - 16f - 48f) / 2f - 64f

    @Test
    fun `the controls fold away after the timeout and the show controls button appears`() {
        show()
        assertThat(controlsShown()).isTrue()
        settle(3_500)
        assertThat(controlsShown()).isTrue()
        settle(1_000)
        assertThat(controlsShown()).isFalse()
        compose.onNodeWithContentDescription("Show controls").assertIsDisplayed()
    }

    @Test
    fun `the text grows and the current line stays in the highlight zone for its new size`() {
        show()
        settle(1_000)
        val before = lineBounds(100_000)
        settle(4_000)
        settle(2_000)
        val after = lineBounds(100_000)
        assertThat(after.height).isGreaterThan(before.height * 1.2f)
        // the middle of the line is where the page keeps the current line, to a few pixels, at the size it has now
        assertThat(abs(after.center.y - highlightCentre)).isLessThan(8f)
    }

    @Test
    fun `the first tap on a folded page brings the controls back and does not seek, the next one does`() {
        show()
        settle(5_000)
        assertThat(controlsShown()).isFalse()

        compose.onNodeWithTag("synced_line_105000").performClick()
        compose.waitForIdle()
        assertThat(seeks).isEmpty()
        assertThat(controlsShown()).isTrue()

        settle(1_000)
        compose.onNodeWithTag("synced_line_105000").performClick()
        compose.waitForIdle()
        assertThat(seeks).containsExactly(105_000L)
    }

    @Test
    fun `with the controls shown a tap on a line seeks to it as it always did`() {
        show()
        compose.onNodeWithTag("synced_line_95000").performClick()
        compose.waitForIdle()
        assertThat(seeks).containsExactly(95_000L)
    }

    @Test
    fun `a scroll of the lyrics counts as being there`() {
        show()
        settle(3_000)
        compose.onNodeWithTag("synced_line_100000").performTouchInput { swipeUp() }
        settle(2_000)
        assertThat(controlsShown()).isTrue()
        // and it folds again once nothing has happened for the whole timeout after the scroll
        settle(3_000)
        assertThat(controlsShown()).isFalse()
    }

    @Test
    fun `a finger resting on the page keeps it from folding until it is lifted`() {
        show()
        compose.onNodeWithTag("synced_line_100000").performTouchInput { down(center) }
        settle(9_000)
        assertThat(controlsShown()).isTrue()
        compose.onNodeWithTag("synced_line_100000").performTouchInput { up() }
        settle(3_500)
        assertThat(controlsShown()).isTrue()
        settle(1_500)
        assertThat(controlsShown()).isFalse()
    }

    @Test
    fun `a paused song keeps its controls and pausing a folded page brings them back`() {
        show(playing = false)
        settle(9_000)
        assertThat(controlsShown()).isTrue()

        playerState.value = playerState.value.copy(isPlaying = true)
        settle(5_000)
        assertThat(controlsShown()).isFalse()

        playerState.value = playerState.value.copy(isPlaying = false)
        settle(1_000)
        assertThat(controlsShown()).isTrue()
    }

    @Test
    fun `switched off in settings it never folds`() {
        show(enabled = false)
        settle(9_000)
        assertThat(controlsShown()).isTrue()
    }

    @Test
    fun `plain lyrics have no lines that move so they never fold`() {
        show(lyrics = Lyrics(plain = (0 until 40).map { "Plain line $it of a song" }))
        settle(9_000)
        assertThat(controlsShown()).isTrue()
    }

    @Test
    fun `the current line follows the song and sits in the same place on every line`() {
        show(enabled = false)
        settle(1_500)
        val first = lineBounds(100_000).center.y
        position.value = 107_000L
        settle(1_500)
        val second = lineBounds(105_000).center.y
        position.value = 112_000L
        settle(1_500)
        val third = lineBounds(110_000).center.y
        assertThat(abs(first - second)).isLessThan(4f)
        assertThat(abs(first - third)).isLessThan(4f)
        // and a jump of several lines (a tap on a far line, a seek) ends in the same place
        for (jump in listOf(3, 6, 10)) {
            position.value = 112_000L + jump * 5_000L
            settle(1_500)
            assertThat(abs(first - lineBounds(110_000 + jump * 5_000).center.y)).isLessThan(4f)
        }
    }

    @Test
    fun `it follows the song while folded too`() {
        show()
        settle(6_000)
        assertThat(controlsShown()).isFalse()
        position.value = 152_000L
        settle(1_500)
        assertThat(abs(lineBounds(150_000).center.y - highlightCentre)).isLessThan(8f)
    }

    // -- the three places lyrics come from reach the page as the same thing --

    private val lrcBody = (0 until 40).joinToString("\n") { "[%02d:%02d.00]Line number %d of a song that is playing".format(it * 5 / 60, it * 5 % 60, it) }

    private fun assertFoldsAndFollows(lyrics: Lyrics) {
        assertThat(lyrics.synced).isNotEmpty()
        show(lyrics = lyrics)
        settle(5_500)
        assertThat(controlsShown()).isFalse()
        position.value = 152_000L
        settle(1_500)
        assertThat(abs(lineBounds(150_000).center.y - highlightCentre)).isLessThan(8f)
        compose.onNodeWithTag("synced_line_150000").performClick()
        compose.waitForIdle()
        assertThat(seeks).isEmpty()
        settle(1_000)
        compose.onNodeWithTag("synced_line_150000").performClick()
        compose.waitForIdle()
        assertThat(seeks).containsExactly(150_000L)
    }

    @Test
    fun `lyrics read from the audio file's own tag`() {
        val lyrics = parseBestEmbeddedLyricsField(mapOf("SYNCEDLYRICS" to arrayOf(lrcBody)))!!
        assertThat(lyrics.areFromRemote).isFalse()
        assertFoldsAndFollows(lyrics)
    }

    @Test
    fun `lyrics read from an lrc file next to the song`() {
        val lyrics = LyricsUtils.parseLyrics(lrcBody)
        assertThat(lyrics.areFromRemote).isFalse()
        assertFoldsAndFollows(lyrics)
    }

    @Test
    fun `lyrics from LRCLIB, with an instrumental gap in them`() {
        // what LRCLIB sends as syncedLyrics: an empty timed line marks an instrumental gap, which the page draws as the three bubbles
        val withGap = lrcBody.lines().toMutableList().also { it[3] = "[00:15.00]" }.joinToString("\n")
        val lyrics = LyricsUtils.parseLyrics(withGap).copy(areFromRemote = true)
        assertThat(lyrics.synced!!.any { it.line.isBlank() }).isTrue()
        assertFoldsAndFollows(lyrics)
    }
}
