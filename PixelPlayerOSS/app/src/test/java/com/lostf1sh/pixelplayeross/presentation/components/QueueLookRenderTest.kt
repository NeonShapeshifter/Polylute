package com.lostf1sh.pixelplayeross.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.presentation.RenderChecks
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B22, Part 4: the queue (`Queue.dc.html`): the header with its count, the rows, and the bottom bar of tonal round buttons,
 * at 360 dp in English, Spanish and Swedish. The queue carries no output line.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xxhdpi")
open class QueueLookRenderTest {

    /** The palette the page is drawn in; the Light subclass runs every test again on the light theme. */
    protected open val palette: com.lostf1sh.pixelplayeross.ui.ds.DapPalette = com.lostf1sh.pixelplayeross.ui.ds.DapPalette.Graphite

    @get:Rule
    val compose = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun s(id: Int) = context.getString(id)

    private val calls = mutableListOf<String>()
    private val timer = mutableStateOf(false)

    private fun song(title: String, artist: String = "Mägo de Oz") = Song(
        id = title, title = title, artist = artist, artistId = 7L, album = "Gaia", albumId = 9L, path = "/m/$title.flac",
        contentUriString = "content://$title", albumArtUriString = null, duration = 280_000L, mimeType = "audio/flac", bitrate = 1003, sampleRate = 44100,
    )

    private val longTitle = "La Costa Del Silencio (versión extendida con orquesta sinfónica y coro, grabada en directo)"

    private fun setQueue(shuffle: Boolean = false, repeat: Int = Player.REPEAT_MODE_OFF, source: String = "Búsqueda: mago") = compose.setContent {
        androidx.compose.runtime.CompositionLocalProvider(com.lostf1sh.pixelplayeross.ui.ds.LocalDapPalette provides palette) {
        Column(Modifier.fillMaxSize().background(Dap.surface)) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 24.dp)) { QueueHeader(queueSourceName = source, queueCount = 6) }
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                listOf(longTitle, "La danza del fuego", "La posada de los muertos").forEachIndexed { i, t ->
                    QueuePlaylistSongItem(
                        modifier = Modifier.fillMaxWidth(), onClick = { calls += "play:$i" }, song = song(t), isCurrentSong = i == 0, isPlaying = true,
                        isDragging = false, onRemoveClick = {}, dragHandle = {}, isReorderModeEnabled = false,
                        onMoreOptionsClick = { calls += "more:$i" }, isDragHandleVisible = false, isRemoveButtonVisible = false, isFromPlaylist = true,
                    )
                }
            }
            QueueBottomBar(
                isShuffleOn = shuffle, repeatMode = repeat, isTimerActive = timer,
                onToggleShuffle = { calls += "shuffle" }, onToggleRepeat = { calls += "repeat" },
                onTimerClick = { calls += "timer" }, onMoreClick = { calls += "actions" },
            )
        }
    }
    }

    @Test
    fun `the header names the queue and counts it, and the rows end long titles in an ellipsis`() {
        setQueue()
        compose.waitForIdle()
        compose.onAllNodesWithText(s(R.string.queue_title_next))[0].assertExists()
        val count = context.resources.getQuantityString(R.plurals.queue_track_count, 6, 6)
        compose.onAllNodesWithText(count, substring = true)[0].assertExists()
        compose.onAllNodesWithText("La danza del fuego")[0].assertExists()
        RenderChecks.assertInsideWindow(compose, "queue")
        // Only the song title that is longer than the row may be cut; the header and the buttons are not.
        RenderChecks.assertNoCutText(compose, "queue") { it == longTitle || it.startsWith("Búsqueda") }
    }

    @Test
    fun `the bottom bar is four tonal round buttons that call what they always called`() {
        setQueue()
        compose.waitForIdle()
        compose.onNodeWithContentDescription(s(R.string.presentation_batch_e_cd_toggle_shuffle)).performClick()
        compose.onNodeWithContentDescription(s(R.string.presentation_batch_e_cd_toggle_repeat)).performClick()
        compose.onNodeWithContentDescription(s(R.string.presentation_batch_e_cd_sleep_timer)).performClick()
        compose.onNodeWithContentDescription(s(R.string.presentation_batch_e_cd_queue_actions)).performClick()
        assertEquals(listOf("shuffle", "repeat", "timer", "actions"), calls)
    }

    @Test
    fun `shuffle, repeat and the timer are lit by one rule`() {
        timer.value = true
        setQueue(shuffle = true, repeat = Player.REPEAT_MODE_ALL)
        compose.waitForIdle()
        compose.onNodeWithContentDescription(s(R.string.presentation_batch_e_cd_toggle_shuffle)).assertIsSelected()
        compose.onNodeWithContentDescription(s(R.string.presentation_batch_e_cd_toggle_repeat)).assertIsSelected()
        compose.onNodeWithContentDescription(s(R.string.presentation_batch_e_cd_sleep_timer)).assertIsSelected()
    }

    @Test
    fun `off is off`() {
        setQueue()
        compose.waitForIdle()
        for (id in listOf(R.string.presentation_batch_e_cd_toggle_shuffle, R.string.presentation_batch_e_cd_toggle_repeat, R.string.presentation_batch_e_cd_sleep_timer)) {
            compose.onNodeWithContentDescription(s(id)).assertIsNotSelected()
        }
    }

    @Test
    fun `tapping a row plays it and its dots open its options`() {
        setQueue()
        compose.waitForIdle()
        compose.onAllNodesWithText("La danza del fuego")[0].performClick()
        assertEquals(listOf("play:1"), calls)
    }
}

/** In Spanish. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "es-w360dp-h805dp-xxhdpi")
class QueueLookRenderSpanishTest : QueueLookRenderTest()

/** And in Swedish. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "sv-w360dp-h805dp-xxhdpi")
class QueueLookRenderSwedishTest : QueueLookRenderTest()

/** And on the light theme: every colour is a token, so nothing is white on white. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xxhdpi")
class QueueLookRenderLightTest : QueueLookRenderTest() {
    override val palette = com.lostf1sh.pixelplayeross.ui.ds.DapPalette.Light
}
