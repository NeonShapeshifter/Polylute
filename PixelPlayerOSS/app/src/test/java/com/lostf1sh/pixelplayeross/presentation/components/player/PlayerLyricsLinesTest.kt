package com.lostf1sh.pixelplayeross.presentation.components.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.lostf1sh.pixelplayeross.data.model.Lyrics
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.model.SyncedLine
import com.lostf1sh.pixelplayeross.presentation.viewmodel.LyricsSearchUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlaybackAudioMetadata
import com.lostf1sh.pixelplayeross.presentation.viewmodel.StablePlayerState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B41: two lines of synced lyrics under the cover. The line follows the position (through an instrumental gap too); unsynced lyrics or
 * none show nothing and hold no room; a tap opens the lyrics view; with the switch off nothing is read; a playback tick that does not
 * change the line recomposes nothing.
 */
@OptIn(InternalComposeTracingApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w411dp-h900dp-xxhdpi")
class PlayerLyricsLinesTest {

    @get:Rule
    val compose = createComposeRule()

    private fun synced(vararg lines: Pair<Int, String>) = Lyrics(synced = lines.map { (t, s) -> SyncedLine(time = t, line = s) })

    private val song = Lyrics(
        synced = listOf(
            SyncedLine(1_000, "Abre la puerta, bebé"),
            SyncedLine(5_000, "que estoy afuera"),
            SyncedLine(9_000, "Estás grande ya"),
        )
    )

    // ── what the two lines are ───────────────────────────────────────────────────────────────────────

    @Test
    fun `the lines follow the position, line after line`() {
        // before the first line nothing is being sung: the first line is the one to come
        assertEquals(CoverLyricsLines.Shown(null, "Abre la puerta, bebé"), CoverLyricsLines.at(song, 0L, 0))
        assertEquals(CoverLyricsLines.Shown("Abre la puerta, bebé", "que estoy afuera"), CoverLyricsLines.at(song, 1_000L, 0))
        assertEquals(CoverLyricsLines.Shown("Abre la puerta, bebé", "que estoy afuera"), CoverLyricsLines.at(song, 4_999L, 0))
        assertEquals(CoverLyricsLines.Shown("que estoy afuera", "Estás grande ya"), CoverLyricsLines.at(song, 5_000L, 0))
        // the last line has nothing after it
        assertEquals(CoverLyricsLines.Shown("Estás grande ya", null), CoverLyricsLines.at(song, 60_000L, 0))
    }

    @Test
    fun `an instrumental gap shows nothing being sung and the line that is about to come`() {
        val withGap = synced(1_000 to "first", 5_000 to "", 12_000 to "second", 16_000 to "third")
        assertEquals(CoverLyricsLines.Shown("first", "second"), CoverLyricsLines.at(withGap, 3_000L, 0))
        // 5 s: the blank line is the gap
        assertEquals(CoverLyricsLines.Shown(null, "second"), CoverLyricsLines.at(withGap, 5_000L, 0))
        assertEquals(CoverLyricsLines.Shown(null, "second"), CoverLyricsLines.at(withGap, 11_999L, 0))
        // and it is over when the next line's time comes
        assertEquals(CoverLyricsLines.Shown("second", "third"), CoverLyricsLines.at(withGap, 12_000L, 0))
    }

    @Test
    fun `the song's own sync offset moves the lines`() {
        assertEquals(CoverLyricsLines.Shown("que estoy afuera", "Estás grande ya"), CoverLyricsLines.at(song, 4_000L, 1_000))
        assertEquals(CoverLyricsLines.Shown("Abre la puerta, bebé", "que estoy afuera"), CoverLyricsLines.at(song, 4_000L, 0))
        assertEquals(CoverLyricsLines.Shown(null, "Abre la puerta, bebé"), CoverLyricsLines.at(song, 1_500L, -1_000))
    }

    @Test
    fun `unsynced lyrics, none, and a synced list with nothing in it show nothing`() {
        assertNull(CoverLyricsLines.at(null, 1_000L, 0))
        assertNull(CoverLyricsLines.at(Lyrics(plain = listOf("a line", "another")), 1_000L, 0))
        assertNull(CoverLyricsLines.at(Lyrics(synced = emptyList()), 1_000L, 0))
        assertNull(CoverLyricsLines.at(synced(1_000 to "", 2_000 to "  "), 1_500L, 0))
    }

    // ── what is drawn ────────────────────────────────────────────────────────────────────────────────

    private fun lyricsState(provider: () -> Lyrics?, underCover: Boolean = true, offset: Int = 0) = PlayerLyricsState(
        searchState = LyricsSearchUiState.Idle,
        stablePlayerStateFlow = MutableStateFlow(StablePlayerState()),
        playbackPositionFlow = MutableStateFlow(0L),
        externalLyricsEnabled = false, lyricsSyncOffset = offset, immersiveLyricsEnabled = false, immersiveLyricsTimeout = 4000L,
        isImmersiveTemporarilyDisabled = false, isShuffleEnabled = false, repeatMode = 0, isFavorite = false,
        lyricsProvider = provider, lyricsResolveCompletedProvider = { true }, underCover = underCover,
    )

    private fun progressState(position: () -> Long) = PlayerProgressState.of(
        song = Song(
            id = "1", title = "2K16", artist = "Omar Courtz", artistId = 7L, album = "Millo", albumId = 9L, path = "/m/1.flac",
            contentUriString = "content://1", albumArtUriString = null, duration = 245_000L, mimeType = "audio/flac", bitrate = 1000, sampleRate = 44100,
        ),
        metadata = PlaybackAudioMetadata(), showFileInfo = false,
        currentPositionProvider = position, totalDurationProvider = { 245_000L }, isPlayingProvider = { true },
    )

    @Test
    fun `the player draws the line that is sung and the one that comes next, and follows the position`() {
        var position by mutableLongStateOf(1_500L)
        compose.setContent {
            PlayerLyricsLines(lyricsState({ song }), progressState { position }, onOpen = {})
        }
        compose.onNodeWithText("Abre la puerta, bebé").assertExists()
        compose.onNodeWithText("que estoy afuera").assertExists()
        compose.onAllNodesWithText("Estás grande ya").assertCountEquals(0)
        position = 5_200L
        compose.waitForIdle()
        compose.onNodeWithText("que estoy afuera").assertExists()
        compose.onNodeWithText("Estás grande ya").assertExists()
        compose.onAllNodesWithText("Abre la puerta, bebé").assertCountEquals(0)
    }

    @Test
    fun `with no synced lyrics nothing is drawn and no room is held`() {
        var lyrics by mutableStateOf<Lyrics?>(song)
        var markerTop = -1f
        compose.setContent {
            Column(Modifier.fillMaxWidth()) {
                PlayerLyricsLines(lyricsState({ lyrics }), progressState { 2_000L }, onOpen = {})
                Box(Modifier.height(10.dp).onGloballyPositioned { markerTop = it.positionInRoot().y })
            }
        }
        compose.waitForIdle()
        assertTrue("with synced lyrics the marker is below the two lines (was $markerTop)", markerTop > 0f)
        lyrics = Lyrics(plain = listOf("only plain lines"))
        compose.waitForIdle()
        compose.onAllNodesWithText("Abre la puerta, bebé").assertCountEquals(0)
        assertEquals("no gap where the lines were", 0f, markerTop, 0.5f)
        lyrics = null
        compose.waitForIdle()
        assertEquals(0f, markerTop, 0.5f)
    }

    @Test
    fun `a tap on the lines opens the lyrics view`() {
        var opened = 0
        compose.setContent {
            PlayerLyricsLines(lyricsState({ song }), progressState { 2_000L }, onOpen = { opened++ })
        }
        compose.onNodeWithText("Abre la puerta, bebé").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `with the switch off nothing is drawn and neither the lyrics nor the position are read`() {
        var lyricsReads = 0
        var positionReads = 0
        compose.setContent {
            PlayerLyricsLines(
                lyricsState({ lyricsReads++; song }, underCover = false),
                progressState { positionReads++; 2_000L },
                onOpen = {},
            )
        }
        compose.waitForIdle()
        assertEquals(0, lyricsReads)
        assertEquals(0, positionReads)
        compose.onAllNodesWithText("Abre la puerta, bebé").assertCountEquals(0)
    }

    private class Counter : CompositionTracer {
        val runs = mutableMapOf<String, Int>()
        override fun isTraceInProgress(): Boolean = true
        override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
            val name = info.substringBefore(" (").substringAfterLast('.')
            runs[name] = (runs[name] ?: 0) + 1
        }
        override fun traceEventEnd() {}
    }

    @Test
    fun `a playback tick that does not change the line recomposes nothing, a new line recomposes once`() {
        var position by mutableLongStateOf(1_100L)
        compose.setContent {
            PlayerLyricsLines(lyricsState({ song }), progressState { position }, onOpen = {})
        }
        compose.waitForIdle()
        val counter = Counter()
        Composer.setTracer(counter)
        try {
            // ten seconds of one-second ticks would be one line; here, every tick inside the first line
            for (p in listOf(1_300L, 1_900L, 2_500L, 3_100L, 3_700L, 4_300L, 4_900L)) {
                position = p
                compose.waitForIdle()
            }
            assertEquals(counter.runs.toString(), 0, counter.runs["PlayerLyricsLines"] ?: 0)
            counter.runs.clear()
            position = 5_100L
            compose.waitForIdle()
            assertEquals(counter.runs.toString(), 1, counter.runs["PlayerLyricsLines"] ?: 0)
        } finally {
            Composer.setTracer(object : CompositionTracer {
                override fun isTraceInProgress() = false
                override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {}
                override fun traceEventEnd() {}
            })
        }
    }
}
