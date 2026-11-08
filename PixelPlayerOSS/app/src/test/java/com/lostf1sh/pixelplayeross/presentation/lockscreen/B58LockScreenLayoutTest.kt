package com.lostf1sh.pixelplayeross.presentation.lockscreen

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.presentation.B58Renders
import com.lostf1sh.pixelplayeross.ui.ds.DapPalette
import com.lostf1sh.pixelplayeross.ui.ds.LocalDapPalette
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B58 7 (#77): the DAP lock screen with a smaller clock, higher, and a bigger cover, at the HONOR's 720 x 1610 px (360 x 805 dp, xhdpi). Written against the screen's public composable only
 * (the clock and the cover are found by what they show), so the same file runs in the tree as it was and writes the "before" PNGs. The rules of B22/B25 (flick, wake window, freeze under
 * lock, sleep) are in their own tests, which run unchanged.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xhdpi")
class B58LockScreenLayoutTest {
    @get:Rule val compose = createComposeRule()

    private val context get() = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
    private val song = Song(
        id = "1", title = "La Costa Del Silencio", artist = "Artist One", artistId = 7L, album = "Album One", albumId = 9L, path = "/m/1.flac",
        contentUriString = "content://1", albumArtUriString = null, duration = 280_000L, mimeType = "audio/flac", bitrate = 1003, sampleRate = 44100,
    )

    private fun show(palette: DapPalette) {
        compose.setContent {
            CompositionLocalProvider(LocalDapPalette provides palette) {
                DapLockScreen(
                    song = song, isPlaying = true, isShuffleEnabled = false, repeatMode = androidx.media3.common.Player.REPEAT_MODE_ALL,
                    currentPositionProvider = { 180_000L }, totalDuration = 280_000L,
                    outputLine = "SNOWSKY Melody · USB Direct · 16 bits · 44,1 kHz", outputUntouched = true, batteryPercent = 33,
                    onPrevious = {}, onPlayPause = {}, onNext = {}, onToggleShuffle = {}, onToggleRepeat = {}, onDismiss = {}, onRevealingChange = {},
                )
            }
        }
        compose.waitForIdle()
    }

    /** dp of a px value at the test's density (xhdpi = 2). */
    private fun dp(px: Float) = px / 2f

    /** The clock's time and the cover's square, by the tags the screen puts on them (the "before" copy gets the same two tags and nothing else). */
    private fun clockBounds() = compose.onNodeWithTag("lock_clock").fetchSemanticsNode().boundsInRoot

    private fun coverBounds() = compose.onNodeWithTag("lock_cover").fetchSemanticsNode().boundsInRoot

    @Test fun the_clock_is_smaller_and_higher_and_the_cover_bigger_in_the_dark() {
        show(DapPalette.Graphite)
        check()
        B58Renders.save(compose, "lock-screen-dark")
    }

    @Test fun the_clock_is_smaller_and_higher_and_the_cover_bigger_in_the_light() {
        show(DapPalette.Light)
        check()
        B58Renders.save(compose, "lock-screen-light")
    }

    private fun check() {
        val clock = clockBounds()
        val cover = coverBounds()
        println("B58 lock layout [${B58Renders.phase}] clock top=${dp(clock.top)}dp height=${dp(clock.height)}dp, cover ${dp(cover.width)}x${dp(cover.height)}dp at top=${dp(cover.top)}dp")
        // before: the clock started 48 dp down and was 64 sp (a line about 64 dp tall); the cover was 216 dp square
        if (B58Renders.phase == "after") {
            assertThat(dp(clock.top)).isAtMost(20f)
            assertThat(dp(clock.height)).isAtMost(62f)
            assertThat(dp(cover.width)).isAtLeast(260f)
            assertThat(dp(cover.width)).isAtMost(280.5f)
            assertThat(cover.width).isWithin(1f).of(cover.height)
        }
        // whatever the size: the cover is square, centred, below the clock and wholly on the screen
        assertThat(cover.top).isAtLeast(clock.bottom)
        assertThat(cover.left).isAtLeast(0f)
        assertThat(cover.right).isAtMost(720f)
        assertThat(cover.bottom).isAtMost(1610f)
        assertThat((cover.left + cover.right) / 2f).isWithin(2f).of(360f)
    }

    @Test fun nothing_else_is_pushed_out_the_title_the_controls_and_the_way_out_are_still_on_the_screen() {
        show(DapPalette.Graphite)
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        listOf(song.title, context.getString(R.string.lock_screen_open_app)).forEach { text ->
            val b = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot
            assertThat(b.bottom).isAtMost(root.bottom)
            assertThat(b.top).isAtLeast(coverBounds().bottom)
        }
    }
}
