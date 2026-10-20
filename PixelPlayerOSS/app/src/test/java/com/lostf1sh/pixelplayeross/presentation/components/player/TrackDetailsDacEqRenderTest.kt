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
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqLabel
import com.lostf1sh.pixelplayeross.data.dac.fiio.ResonanceSource
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.presentation.B30Renders
import com.lostf1sh.pixelplayeross.presentation.RenderChecks
import com.lostf1sh.pixelplayeross.presentation.navigation.DapBackAuthority
import com.lostf1sh.pixelplayeross.presentation.navigation.LocalDapBackNavigation
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DacEqConflict
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
 * B30-P1.4 / P4: Details says which source shapes a Resonance stream, withholds Purity when the DAC's EQ is on or unknown (and says
 * "DAC EQ: unknown"), follows the DAC's EQ live, and names what keeps a DAC off USB Direct with the way to stop it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xhdpi")
open class TrackDetailsDacEqRenderTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun s(id: Int, vararg args: Any) = context.getString(id, *args)
    private val language: String get() = context.resources.configuration.locales[0].language

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

    private fun seen(text: String) {
        compose.onNodeWithText(text, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
    }

    private fun absent(text: String, substring: Boolean = false) {
        assertEquals(0, compose.onAllNodesWithText(text, substring = substring, useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    private fun direct(mode: DirectOutputMode?, source: ResonanceSource?, eq: DacEqLabel) = state(
        OutputDetails(
            route = OutputRoute.USB_DIRECT, directMode = mode, resonanceSource = source, dacEq = eq,
            deviceName = "SNOWSKY Melody", confirmedRateHz = 44_100,
        ),
    )

    @Test
    fun `Purity with the DAC's EQ closed or flat says Purity and the row says off or flat`() {
        ui.value = direct(DirectOutputMode.PURITY, null, DacEqLabel.OFF_OR_FLAT)
        show()
        seen(s(R.string.track_details_route_usb_direct_purity))
        seen(s(R.string.track_details_dac_eq_off))
        B30Renders.save(compose, "P1-details-usb-direct-purity-dac-eq-off-$language")
    }

    @Test
    fun `the DAC's EQ on says Resonance and where it comes from`() {
        ui.value = direct(DirectOutputMode.RESONANCE, ResonanceSource.DAC_EQ, DacEqLabel.ON)
        show()
        seen(s(R.string.track_details_route_usb_direct_resonance_dac))
        seen(s(R.string.track_details_dac_eq_on))
        absent(s(R.string.track_details_route_usb_direct_purity))
        B30Renders.save(compose, "P1-details-usb-direct-resonance-dac-eq-$language")
        RenderChecks.assertInsideWindow(compose, "details resonance dac eq")
    }

    @Test
    fun `the app's DSP, and both at once, are named too`() {
        ui.value = direct(DirectOutputMode.RESONANCE, ResonanceSource.APP_DSP, DacEqLabel.OFF_OR_FLAT)
        show()
        seen(s(R.string.track_details_route_usb_direct_resonance_app))
        B30Renders.save(compose, "P1-details-usb-direct-resonance-app-dsp-$language")
        ui.value = direct(DirectOutputMode.RESONANCE, ResonanceSource.BOTH, DacEqLabel.ON)
        compose.waitForIdle()
        seen(s(R.string.track_details_route_usb_direct_resonance_both))
    }

    @Test
    fun `a DAC whose EQ could not be read gets neither Purity nor Resonance, and the row says unknown`() {
        ui.value = direct(null, null, DacEqLabel.UNKNOWN)
        show()
        seen(s(R.string.track_details_route_usb_direct))
        seen(s(R.string.track_details_dac_eq_unknown))
        absent(s(R.string.track_details_route_usb_direct_purity))
        absent("Resonance", substring = true)
        B30Renders.save(compose, "P1-details-usb-direct-dac-eq-unknown-$language")
    }

    @Test
    fun `a DAC the app cannot ask has no DAC EQ row and is labelled as before`() {
        ui.value = direct(DirectOutputMode.PURITY, null, DacEqLabel.NOT_APPLICABLE)
        show()
        seen(s(R.string.track_details_route_usb_direct_purity))
        absent(s(R.string.track_details_dac_eq))
    }

    @Test
    fun `the sheet follows the DAC's EQ live - unknown, then closed, then on`() {
        ui.value = direct(null, null, DacEqLabel.UNKNOWN)
        show()
        seen(s(R.string.track_details_route_usb_direct))
        ui.value = direct(DirectOutputMode.PURITY, null, DacEqLabel.OFF_OR_FLAT)
        compose.waitForIdle()
        seen(s(R.string.track_details_route_usb_direct_purity))
        ui.value = direct(DirectOutputMode.RESONANCE, ResonanceSource.DAC_EQ, DacEqLabel.ON)
        compose.waitForIdle()
        seen(s(R.string.track_details_route_usb_direct_resonance_dac))
        absent(s(R.string.track_details_route_usb_direct_purity))
    }

    @Test
    fun `crossfade keeps a DAC off USB Direct - Details says what and how to stop it, and that the DAC's EQ still applies`() {
        ui.value = state(OutputDetails(route = OutputRoute.USB_ANDROID, dacEq = DacEqLabel.UNKNOWN, conflict = DacEqConflict.CROSSFADE, deviceName = "SNOWSKY Melody"))
        show()
        seen(s(R.string.dac_eq_conflict_crossfade))
        absent("Purity", substring = true)
        B30Renders.save(compose, "P4-details-android-route-crossfade-conflict-$language")
        RenderChecks.assertInsideWindow(compose, "details conflict")
        RenderChecks.assertNoCutText(compose, "details conflict")
    }

    @Test
    fun `no conflict sentence for a DAC the app cannot ask, or when nothing is in the way`() {
        ui.value = state(OutputDetails(route = OutputRoute.USB_ANDROID, dacEq = DacEqLabel.NOT_APPLICABLE, conflict = DacEqConflict.CROSSFADE))
        show()
        compose.waitForIdle()
        absent(s(R.string.dac_eq_conflict_crossfade))
        ui.value = state(OutputDetails(route = OutputRoute.USB_ANDROID, dacEq = DacEqLabel.UNKNOWN, conflict = null))
        compose.waitForIdle()
        absent(s(R.string.dac_eq_conflict_crossfade))
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "es-w360dp-h805dp-xhdpi")
class TrackDetailsDacEqRenderSpanishTest : TrackDetailsDacEqRenderTest()

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "sv-w360dp-h805dp-xhdpi")
class TrackDetailsDacEqRenderSwedishTest : TrackDetailsDacEqRenderTest()
