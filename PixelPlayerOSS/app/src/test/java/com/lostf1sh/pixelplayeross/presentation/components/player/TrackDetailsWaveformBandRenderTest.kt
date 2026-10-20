package com.lostf1sh.pixelplayeross.presentation.components.player

import androidx.activity.OnBackPressedDispatcher
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.media.Waveform
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.presentation.B29Renders
import com.lostf1sh.pixelplayeross.presentation.navigation.DapBackAuthority
import com.lostf1sh.pixelplayeross.presentation.navigation.LocalDapBackNavigation
import com.lostf1sh.pixelplayeross.presentation.viewmodel.OutputDetails
import com.lostf1sh.pixelplayeross.presentation.viewmodel.OutputRoute
import com.lostf1sh.pixelplayeross.presentation.viewmodel.TrackDetailsUiState
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.sin

/**
 * B29-fix1 F7.2: the band between the title block and "Origen" on the Details sheet.
 *
 * It is not a leftover. It is the **waveform strip** (56 dp: the song's peak and RMS envelope, which also seeks), and the owner kept it
 * when he looked at Details in B22 ("Details stays as today"). While the waveform is being decoded the strip holds a loading line, a
 * 2 dp bar across the full width: that is the divider at y 395 px in the reviewer's screenshot, in the middle of a 112 px strip, with
 * nothing else in it. When a decode ends with none (a file the decoder cannot read) it holds a one-line note instead.
 *
 * Nothing is removed. These renders show the three things the strip holds, at the HONOR's size (720 x 1610 px, density 2.0), and the
 * test pins that a loading strip is the one loading line (not a stray extra rule), so the next device check can tell "still decoding"
 * from "decoded and drew nothing" by the log line `waveform ready|none after N ms`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xhdpi")
class TrackDetailsWaveformBandRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private val song = Song.emptySong().copy(id = "1", title = "(Irreversible)", artist = "Dillom", album = "Por cesárea", duration = 180_000L)

    private fun envelope(): Waveform {
        val n = 200
        val peaks = FloatArray(n) { i -> (0.35f + 0.55f * abs(sin(i / 9f)) * (0.6f + 0.4f * sin(i / 31f))).coerceIn(0f, 1f) }
        return Waveform(bucketCount = n, peaks = peaks, rms = FloatArray(n) { peaks[it] * 0.6f }, sampleRate = 48_000, durationMs = 180_000L)
    }

    private fun show(state: TrackDetailsUiState) = compose.setContent {
        DapTheme(baseTheme = DapBaseTheme.Graphite, accent = DapAccent.Sage) {
            CompositionLocalProvider(LocalDapBackNavigation provides DapBackAuthority(OnBackPressedDispatcher())) {
                Box(Modifier.fillMaxSize().background(Dap.background)) {
                    TrackDetailsSheet(state = state, currentPositionProvider = { 60_000L }, totalDurationMs = 180_000L, onSeek = {}, onBackClick = {})
                }
            }
        }
    }

    private fun base() = TrackDetailsUiState(song = song, output = OutputDetails(route = OutputRoute.SPEAKER))

    @Test
    fun `while the waveform is decoding the strip says so, above the loading line, and not that there is none`() {
        show(base().copy(isLoadingWaveform = true))
        compose.waitForIdle()
        // B31 P2.5: the strip held only a 2 dp line, which the owner read as an empty band. It now says what it waits for.
        compose.onNodeWithText(context.getString(R.string.track_details_waveform_loading)).assertExists()
        // the note that says there is none is not there
        assertThat(
            compose.onAllNodesWithTextCount(context.getString(R.string.track_details_waveform_unavailable)),
        ).isEqualTo(0)
        B29Renders.saveFix1(compose, "F7-details-waveform-strip-loading")
        com.lostf1sh.pixelplayeross.presentation.B31Renders.save(compose, "P2-details-waveform-strip-loading")
    }

    @Test
    fun `a decoded waveform fills the strip`() {
        show(base().copy(waveform = envelope()))
        compose.waitForIdle()
        B29Renders.saveFix1(compose, "F7-details-waveform-strip-ready")
    }

    @Test
    fun `a decode that ends with none leaves one line of text in the strip`() {
        show(base())
        compose.waitForIdle()
        compose.onNodeWithText(context.getString(R.string.track_details_waveform_unavailable)).assertExists()
        B29Renders.saveFix1(compose, "F7-details-waveform-strip-unavailable")
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String): Int =
        onAllNodesWithText(text).fetchSemanticsNodes().size
}
