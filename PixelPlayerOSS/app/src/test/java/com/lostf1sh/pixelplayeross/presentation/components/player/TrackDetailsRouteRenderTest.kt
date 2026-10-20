package com.lostf1sh.pixelplayeross.presentation.components.player

import androidx.activity.OnBackPressedDispatcher
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.presentation.B29Renders
import com.lostf1sh.pixelplayeross.presentation.RenderChecks
import com.lostf1sh.pixelplayeross.presentation.navigation.DapBackAuthority
import com.lostf1sh.pixelplayeross.presentation.navigation.LocalDapBackNavigation
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DirectOutputMode
import com.lostf1sh.pixelplayeross.presentation.viewmodel.OutputDetails
import com.lostf1sh.pixelplayeross.presentation.viewmodel.OutputRoute
import com.lostf1sh.pixelplayeross.presentation.viewmodel.TrackDetailsUiState
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B29-L4 (N-01): Details says plainly whether USB Direct is carrying the stream, and which signal it carries.
 *
 * USB Direct active: "USB Direct · Purity" or "USB Direct · Resonance". Not active: "Android route · …" (speaker, USB,
 * Bluetooth, wired), and the words Purity and Resonance appear nowhere on the sheet (they belong to USB Direct). The sheet
 * follows the route while it is open: it is driven here by the same kind of state flow the view model publishes.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xhdpi")
open class TrackDetailsRouteRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun s(id: Int, vararg args: Any) = context.getString(id, *args)

    private val song = Song.emptySong().copy(id = "1", title = "BIAF<3", artist = "Young Miko", album = "att.", duration = 180_000L)

    private fun state(output: OutputDetails) = TrackDetailsUiState(song = song, output = output)

    private val ui = MutableStateFlow(state(OutputDetails(route = OutputRoute.SPEAKER)))

    private fun show() = compose.setContent {
        DapTheme(baseTheme = DapBaseTheme.Graphite, accent = DapAccent.Sage) {
            CompositionLocalProvider(LocalDapBackNavigation provides DapBackAuthority(OnBackPressedDispatcher())) {
                Box(Modifier.fillMaxSize().background(Dap.background)) {
                    val current by ui.collectAsState()
                    TrackDetailsSheet(
                        state = current, currentPositionProvider = { 0L }, totalDurationMs = 180_000L,
                        onSeek = {}, onBackClick = {},
                    )
                }
            }
        }
    }

    /** The Output group is below the waveform and the Source group: scroll to the row, then it must be on screen. */
    private fun seen(text: String) {
        compose.onNodeWithText(text, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
    }

    private fun noModeWords() {
        assertEquals(0, compose.onAllNodesWithText("Purity", substring = true, useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("Resonance", substring = true, useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("Resonancia", substring = true, useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("Resonans", substring = true, useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    fun `USB Direct with the untouched signal says USB Direct and Purity`() {
        ui.value = state(OutputDetails(route = OutputRoute.USB_DIRECT, directMode = DirectOutputMode.PURITY, deviceName = "SNOWSKY Melody", confirmedRateHz = 44_100))
        show()
        seen(s(R.string.track_details_route_usb_direct_purity))
        RenderChecks.assertInsideWindow(compose, "details direct purity")
        B29Renders.save(compose, "L4-details-usb-direct-purity")
    }

    @Test
    fun `USB Direct with the application's DSP says USB Direct and Resonance`() {
        ui.value = state(OutputDetails(route = OutputRoute.USB_DIRECT, directMode = DirectOutputMode.RESONANCE, deviceName = "SNOWSKY Melody", confirmedRateHz = 44_100))
        show()
        seen(s(R.string.track_details_route_usb_direct_resonance))
        B29Renders.save(compose, "L4-details-usb-direct-resonance")
    }

    @Test
    fun `the Android route says so and never says Purity or Resonance`() {
        for ((route, text) in listOf(
            OutputRoute.SPEAKER to s(R.string.track_details_route_android_via, s(R.string.track_details_route_speaker)),
            OutputRoute.USB_ANDROID to s(R.string.track_details_route_android_via, s(R.string.track_details_route_usb_android)),
            OutputRoute.BLUETOOTH to s(R.string.track_details_route_android_via, s(R.string.track_details_route_bluetooth)),
            OutputRoute.WIRED to s(R.string.track_details_route_android_via, s(R.string.track_details_route_wired)),
            OutputRoute.ANDROID_AUDIO to s(R.string.track_details_route_android),
        )) {
            ui.value = state(OutputDetails(route = route))
            if (route == OutputRoute.SPEAKER) show()
            compose.waitForIdle()
            seen(text)
            noModeWords()
        }
        ui.value = state(OutputDetails(route = OutputRoute.SPEAKER))
        compose.waitForIdle()
        B29Renders.save(compose, "L4-details-android-route-speaker")
    }

    @Test
    fun `a Direct stream whose mode is not known says USB Direct and nothing more`() {
        ui.value = state(OutputDetails(route = OutputRoute.USB_DIRECT, directMode = null))
        show()
        seen(s(R.string.track_details_route_usb_direct))
        noModeWords()
    }

    @Test
    fun `the sheet follows the route while it is open`() {
        ui.value = state(OutputDetails(route = OutputRoute.SPEAKER))
        show()
        seen(s(R.string.track_details_route_android_via, s(R.string.track_details_route_speaker)))

        // the DAC is claimed: Direct, untouched
        ui.value = state(OutputDetails(route = OutputRoute.USB_DIRECT, directMode = DirectOutputMode.PURITY))
        compose.waitForIdle()
        seen(s(R.string.track_details_route_usb_direct_purity))
        assertEquals(0, compose.onAllNodesWithText(s(R.string.track_details_route_android_via, s(R.string.track_details_route_speaker)), useUnmergedTree = true).fetchSemanticsNodes().size)

        // the Purity lock is released: the same Direct stream now carries the application's DSP
        ui.value = state(OutputDetails(route = OutputRoute.USB_DIRECT, directMode = DirectOutputMode.RESONANCE))
        compose.waitForIdle()
        seen(s(R.string.track_details_route_usb_direct_resonance))
        assertEquals(0, compose.onAllNodesWithText(s(R.string.track_details_route_usb_direct_purity), useUnmergedTree = true).fetchSemanticsNodes().size)

        // the DAC leaves: back on the Android route, with no trace of the Direct modes
        ui.value = state(OutputDetails(route = OutputRoute.WIRED))
        compose.waitForIdle()
        seen(s(R.string.track_details_route_android_via, s(R.string.track_details_route_wired)))
        noModeWords()
    }
}

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "es-w360dp-h805dp-xhdpi")
class TrackDetailsRouteRenderSpanishTest : TrackDetailsRouteRenderTest()

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "sv-w360dp-h805dp-xhdpi")
class TrackDetailsRouteRenderSwedishTest : TrackDetailsRouteRenderTest()
