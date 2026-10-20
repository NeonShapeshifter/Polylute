package com.lostf1sh.pixelplayeross.presentation.lockscreen

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.presentation.RenderChecks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B22, Part 2: the DAP lock screen in the look of `LockScreen.dc.html`, at the HONOR's 360×805 dp, in English, Spanish
 * and Swedish.
 *
 * What is proved: the design's texts are there (title, artist, the two times as `03:00`, the output line with the
 * battery, "Abrir la aplicación"); the five controls call what they always called; swiping up reveals and leaves; the
 * output line either fits or ends in an ellipsis and **never pushes the battery out**; and nothing but the title and
 * that line is ever cut, even with a very long title.
 */
@RunWith(RobolectricTestRunner::class)
// Native graphics: the JVM's default text metrics never overflow, so a fit test would pass on anything.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xxhdpi")
open class LockScreenRenderTest {

    /** The palette the page is drawn in; the Light subclass runs every test again on the light theme. */
    protected open val palette: com.lostf1sh.pixelplayeross.ui.ds.DapPalette = com.lostf1sh.pixelplayeross.ui.ds.DapPalette.Graphite

    @get:Rule
    val compose = createComposeRule()

    protected val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    protected fun s(id: Int) = context.getString(id)

    private fun song(title: String = "La Costa Del Silencio", artist: String = "Mägo de Oz") = Song(
        id = "1", title = title, artist = artist, artistId = 7L, album = "Gaia", albumId = 9L, path = "/m/1.flac",
        contentUriString = "content://1", albumArtUriString = null, duration = 280_000L, mimeType = "audio/flac", bitrate = 1003, sampleRate = 44100,
    )

    private class Calls {
        val list = mutableListOf<String>()
        val revealing = mutableListOf<Boolean>()
    }

    private val calls = Calls()

    private fun setLock(
        song: Song = song(),
        output: String? = "SNOWSKY Melody · USB Direct · 16 bits · 44,1 kHz",
        battery: Int? = 33,
        shuffle: Boolean = false,
        repeat: Int = Player.REPEAT_MODE_ALL,
        playing: Boolean = true,
    ) = compose.setContent {
        androidx.compose.runtime.CompositionLocalProvider(com.lostf1sh.pixelplayeross.ui.ds.LocalDapPalette provides palette) {
        DapLockScreen(
            song = song, isPlaying = playing, isShuffleEnabled = shuffle, repeatMode = repeat,
            currentPositionProvider = { 180_000L }, totalDuration = 280_000L,
            outputLine = output, outputUntouched = true, batteryPercent = battery,
            onPrevious = { calls.list += "previous" }, onPlayPause = { calls.list += "playPause" },
            onNext = { calls.list += "next" }, onToggleShuffle = { calls.list += "shuffle" },
            onToggleRepeat = { calls.list += "repeat" }, onDismiss = { calls.list += "dismiss" },
            onRevealingChange = { calls.revealing += it },
        )
    }
    }

    // ── the design's texts ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `shows the title, the artist, the two times in the design's format, the output line, the battery and the way out`() {
        setLock()
        compose.onNodeWithText("La Costa Del Silencio").assertIsDisplayed()
        compose.onNodeWithText("Mägo de Oz").assertIsDisplayed()
        compose.onNodeWithText("03:00").assertIsDisplayed()
        compose.onNodeWithText("04:40").assertIsDisplayed()
        compose.onNodeWithText("SNOWSKY Melody · USB Direct · 16 bits · 44,1 kHz").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.lock_battery_percent, 33)).assertIsDisplayed()
        compose.onNodeWithText(s(R.string.lock_screen_open_app)).assertIsDisplayed()
    }

    @Test
    fun `the five controls call what they always called`() {
        setLock()
        compose.onNodeWithContentDescription(s(R.string.player_shuffle)).performClick()
        compose.onNodeWithContentDescription(s(R.string.lock_transport_previous)).performClick()
        compose.onNodeWithContentDescription(s(R.string.cd_pause)).performClick()
        compose.onNodeWithContentDescription(s(R.string.lock_transport_next)).performClick()
        compose.onNodeWithContentDescription(s(R.string.lock_transport_repeat)).performClick()
        assertEquals(listOf("shuffle", "previous", "playPause", "next", "repeat"), calls.list)
    }

    @Test
    fun `swiping up moves the surface off the app and then leaves, and the way-out button leaves too`() {
        setLock()
        compose.onRoot().performTouchInput { swipeUp(startY = height * 0.7f, endY = height * 0.1f, durationMillis = 300) }
        compose.waitForIdle()
        assertTrue("revealing was reported: ${calls.revealing}", calls.revealing.contains(true))
        assertTrue(calls.list.toString(), calls.list.contains("dismiss"))
    }

    @Test
    fun `the way-out button leaves`() {
        setLock()
        compose.onNodeWithText(s(R.string.lock_screen_open_app)).performClick()
        compose.waitForIdle()
        assertTrue(calls.list.toString(), calls.list.contains("dismiss"))
    }

    // ── fix 1: the footer fits, or ends in an ellipsis ───────────────────────────────────────────────

    @Test
    fun `the footer fits at 360 dp, the battery stays in the window, and a longer line ends in an ellipsis rather than being clipped`() {
        setLock(output = "Un DAC con un nombre larguísimo de fabricante · USB Direct · 24 bits · 192 kHz", battery = 100)
        RenderChecks.assertInsideWindow(compose, "lock footer")
        // The battery is whole, the line gave up the width.
        compose.onNodeWithText(context.getString(R.string.lock_battery_percent, 100)).assertIsDisplayed()
        // Only the output line (and the title) may end in an ellipsis; nothing else is cut.
        RenderChecks.assertNoCutText(compose, "lock footer") { text -> text.contains("USB Direct") || text.startsWith("La Costa") }
    }

    @Test
    fun `a title that is far too long ends in an ellipsis and moves nothing out of the window`() {
        val title = "Una canción cuyo título es tan largo que no cabe en dos líneas ni en tres, en un reproductor de 360 dp de ancho, ni de lejos"
        setLock(song = song(title = title))
        RenderChecks.assertInsideWindow(compose, "long title")
        RenderChecks.assertNoCutText(compose, "long title") { it == title || it.contains("USB Direct") }
        compose.onNodeWithText(s(R.string.lock_screen_open_app)).assertIsDisplayed()
    }

    @Test
    fun `with nothing known about the output or the battery the footer is simply absent`() {
        setLock(output = null, battery = null)
        compose.onAllNodesWithText("USB Direct", substring = true).assertCountIsZero()
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountIsZero() {
        assertEquals(0, fetchSemanticsNodes().size)
    }
}

/** In Spanish, the design's own language. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "es-w360dp-h805dp-xxhdpi")
class LockScreenRenderSpanishTest : LockScreenRenderTest()

/** And in Swedish. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "sv-w360dp-h805dp-xxhdpi")
class LockScreenRenderSwedishTest : LockScreenRenderTest()

/** And on the light theme: every colour is a token, so nothing is white on white. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xxhdpi")
class LockScreenRenderLightTest : LockScreenRenderTest() {
    override val palette = com.lostf1sh.pixelplayeross.ui.ds.DapPalette.Light
}
