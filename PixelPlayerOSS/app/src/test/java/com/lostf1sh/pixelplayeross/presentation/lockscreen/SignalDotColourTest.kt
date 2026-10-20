package com.lostf1sh.pixelplayeross.presentation.lockscreen

import androidx.activity.OnBackPressedDispatcher
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.media3.common.Player
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.presentation.B31Renders
import com.lostf1sh.pixelplayeross.presentation.components.player.TrackDetailsSheet
import com.lostf1sh.pixelplayeross.presentation.navigation.DapBackAuthority
import com.lostf1sh.pixelplayeross.presentation.navigation.LocalDapBackNavigation
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DirectOutputMode
import com.lostf1sh.pixelplayeross.presentation.viewmodel.OutputDetails
import com.lostf1sh.pixelplayeross.presentation.viewmodel.OutputRoute
import com.lostf1sh.pixelplayeross.presentation.viewmodel.TrackDetailsUiState
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.ds.DapSizes
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import com.lostf1sh.pixelplayeross.ui.ds.LocalDapAccent
import com.lostf1sh.pixelplayeross.ui.ds.LocalDapAppAccent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B31 P3.1: **the signal dot is one colour.** The filled mark before the output line (the signal reaches the DAC untouched) wore the
 * cover's colour on the lock screen since B29-L11, which read like a sample-rate code. On the lock line and in Details it now reads
 * the app's accent, however the surface around it is themed; the cover's colour stays on the decorative controls (the play button).
 *
 * Each test paints the cover's colour (a loud red) over the surface and reads the dot's own pixel from the rendered root.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xhdpi")
class SignalDotColourTest {
    @get:Rule
    val compose = createComposeRule()

    private val cover = Color(0xFFE53935)
    private var appAccent = Color.Unspecified

    private fun song() = Song(
        id = "1", title = "39", artist = "Queen", artistId = 7L, album = "A Night at the Opera", albumId = 9L, path = "/m/1.flac",
        contentUriString = "content://1", albumArtUriString = null, duration = 280_000L, mimeType = "audio/flac", bitrate = 1003, sampleRate = 44100,
    )

    @Composable
    private fun Themed(content: @Composable () -> Unit) {
        DapTheme(baseTheme = DapBaseTheme.Graphite, accent = DapAccent.Sage) {
            appAccent = LocalDapAppAccent.current
            CompositionLocalProvider(LocalDapBackNavigation provides DapBackAuthority(OnBackPressedDispatcher())) {
                Box(Modifier.fillMaxSize().background(Dap.background)) { content() }
            }
        }
    }

    /** The pixel of the mark that stands before the text [label]: 6 dp wide, a small gap, then the text. */
    private fun markPixel(label: String): Int {
        val text = compose.onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val density = compose.density
        val x = text.left - with(density) { (DapSpacing.Sm + DapSizes.StateMark / 2).toPx() }
        val y = (text.top + text.bottom) / 2
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        return bitmap.getPixel(x.toInt(), y.toInt())
    }

    @Test
    fun `the lock line's filled mark is the app's accent while the surface wears the cover's`() {
        compose.setContent {
            Themed {
                // exactly what DapLockSurface does: the cover's accent over the whole surface
                CompositionLocalProvider(LocalDapAccent provides cover) {
                    DapLockScreen(
                        song = song(), isPlaying = true, isShuffleEnabled = false, repeatMode = Player.REPEAT_MODE_OFF,
                        currentPositionProvider = { 0L }, totalDuration = 280_000L, outputLine = "USB Direct 96 kHz 24-bit", outputUntouched = true,
                        batteryPercent = 80,
                        onPrevious = {}, onPlayPause = {}, onNext = {}, onToggleShuffle = {}, onToggleRepeat = {}, onDismiss = {},
                    )
                }
            }
        }
        compose.waitForIdle()
        assertThat(appAccent).isNotEqualTo(cover)
        assertThat(markPixel("USB Direct 96 kHz 24-bit")).isEqualTo(appAccent.toArgb())
        B31Renders.save(compose, "P3-lock-signal-dot-app-accent")
    }

    @Test
    fun `a hollow mark stays hollow, the same on every song`() {
        compose.setContent {
            Themed {
                CompositionLocalProvider(LocalDapAccent provides cover) {
                    DapLockScreen(
                        song = song(), isPlaying = true, isShuffleEnabled = false, repeatMode = Player.REPEAT_MODE_OFF,
                        currentPositionProvider = { 0L }, totalDuration = 280_000L, outputLine = "Android route 48 kHz", outputUntouched = false,
                        batteryPercent = null,
                        onPrevious = {}, onPlayPause = {}, onNext = {}, onToggleShuffle = {}, onToggleRepeat = {}, onDismiss = {},
                    )
                }
            }
        }
        compose.waitForIdle()
        // inside the ring there is the surface, never the accent
        assertThat(markPixel("Android route 48 kHz")).isNotEqualTo(cover.toArgb())
        assertThat(markPixel("Android route 48 kHz")).isNotEqualTo(appAccent.toArgb())
    }

    @Test
    fun `the marks of Details are the app's accent when something around them wears the cover's`() {
        compose.setContent {
            Themed {
                CompositionLocalProvider(LocalDapAccent provides cover) {
                    TrackDetailsSheet(
                        state = TrackDetailsUiState(
                            song = Song.emptySong().copy(id = "1", title = "(Irreversible)", artist = "Dillom", album = "Por cesárea", duration = 180_000L),
                            output = OutputDetails(route = OutputRoute.USB_DIRECT, directMode = DirectOutputMode.PURITY),
                        ),
                        currentPositionProvider = { 0L }, totalDurationMs = 180_000L, onSeek = {}, onBackClick = {},
                    )
                }
            }
        }
        compose.waitForIdle()
        assertThat(markPixel("USB Direct · Purity")).isEqualTo(appAccent.toArgb())
        B31Renders.save(compose, "P3-details-signal-dot-app-accent")
    }
}
