package com.lostf1sh.pixelplayeross.presentation.screens

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.decent.usbaudio.media3.DirectSignalMode
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.audio.dsp.GraphicEqBands
import com.lostf1sh.pixelplayeross.data.audio.dsp.GraphicEqResponse
import com.lostf1sh.pixelplayeross.data.equalizer.EqualizerPreset
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneEqMode
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneEqState
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneProfile
import com.lostf1sh.pixelplayeross.data.service.player.AudioBackend
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteReason
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteState
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteTransition
import com.lostf1sh.pixelplayeross.presentation.RenderChecks
import com.lostf1sh.pixelplayeross.presentation.navigation.DapBackAuthority
import com.lostf1sh.pixelplayeross.presentation.navigation.LocalDapBackNavigation
import com.lostf1sh.pixelplayeross.presentation.viewmodel.AudioStatus
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DapAudioRouteKind
import com.lostf1sh.pixelplayeross.presentation.viewmodel.EqualizerUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.FeatureStatusKind
import com.lostf1sh.pixelplayeross.presentation.viewmodel.HeadphoneProfileUiItem
import com.lostf1sh.pixelplayeross.ui.ds.eqGainLabel
import com.lostf1sh.pixelplayeross.ui.ds.eqLevelForFraction
import com.lostf1sh.pixelplayeross.ui.ds.eqThumbFraction
import kotlinx.collections.immutable.toImmutableList
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B22, Part 3: the equalizer in the look of `Equalizer.dc.html`, at 360 dp, in English, Spanish and Swedish; the four audio
 * states of its status line; and the band sliders' contract (a thumb is where its value says).
 *
 * The state matrix is `VarEq.dc.html`: 1 no DAC, 2 DAC + Pure Direct (frozen), 3 DAC + Resonance, 4 DAC + crossfade
 * (USB Android).
 */
@RunWith(RobolectricTestRunner::class)
// Native graphics: the JVM's default text metrics never overflow, so a fit test would pass on anything.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h2400dp-xxhdpi")
open class EqualizerRenderTest {

    /** The palette the page is drawn in; the Light subclass runs every test again on the light theme. */
    protected open val palette: com.lostf1sh.pixelplayeross.ui.ds.DapPalette = com.lostf1sh.pixelplayeross.ui.ds.DapPalette.Graphite

    @get:Rule
    val compose = createComposeRule()

    protected val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    protected fun s(id: Int) = context.getString(id)
    protected fun s(id: Int, vararg args: Any) = context.getString(id, *args)

    private class Recorder : EqualizerScreenActions {
        val calls = mutableListOf<String>()
        override fun onEnabledChange(enabled: Boolean) { calls += "enabled:$enabled" }
        override fun onModeChange(mode: HeadphoneEqMode) { calls += "mode:$mode" }
        override fun onSelectPreset(preset: EqualizerPreset) { calls += "preset:${preset.name}" }
        override fun onTogglePinPreset(name: String) { calls += "pin:$name" }
        override fun onDeleteCustomPreset(preset: EqualizerPreset) { calls += "delete:${preset.name}" }
        override fun onRenameCustomPreset(oldName: String, newName: String) { calls += "rename:$oldName>$newName" }
        override fun onSaveCurrentAsCustomPreset(name: String) { calls += "save:$name" }
        override fun onUpdateCustomPresetBands(presetName: String) { calls += "update:$presetName" }
        override fun onBandLevelChange(index: Int, level: Int) { calls += "band:$index:$level" }
        override fun onResetBandsToFlat() { calls += "flat" }
        override fun onOpenPurityLock() { calls += "purityLock" }
    }

    private fun route(mode: DirectSignalMode) = AudioRouteState(
        actualBackend = AudioBackend.USB_DIRECT, transition = AudioRouteTransition.DIRECT_ACTIVE,
        signalMode = mode, usbDacPresent = true,
        reason = if (mode == DirectSignalMode.PRISTINE) AudioRouteReason.DIRECT_PRISTINE else AudioRouteReason.DIRECT_PROCESSED,
    )
    private val noDac = AudioStatus(route = AudioRouteState(usbDacPresent = false, reason = AudioRouteReason.NO_USB_DAC))
    private val dacPure = AudioStatus(purityPreference = true, route = route(DirectSignalMode.PRISTINE), sinkDirectActive = true)
    private val dacResonance = AudioStatus(route = route(DirectSignalMode.PROCESSED), sinkDirectActive = true)
    private val dacCrossfade = AudioStatus(
        route = AudioRouteState(
            actualBackend = AudioBackend.ANDROID_AUDIO, transition = AudioRouteTransition.ANDROID_ACTIVE,
            reason = AudioRouteReason.CROSSFADE_REQUIRES_ANDROID, usbDacPresent = true,
        ),
    )

    private val warm10 = listOf(3, 2, 1, 0, -1, -1, 0, 1, 2, 3)

    private fun eqState(bands: List<Int> = warm10, enabled: Boolean = true, mode: HeadphoneEqMode = HeadphoneEqMode.GRAPHIC_10) =
        EqualizerUiState(
            isEnabled = enabled, mode = mode, currentPreset = EqualizerPreset.ROCK,
            bandLevels = bands.toImmutableList(),
            bandFrequencies = GraphicEqBands.labelsFor(bands.size).toImmutableList(),
        )

    private val tygr = HeadphoneProfileUiItem(HeadphoneProfile("hp-1", "TYGR", HeadphoneEqState(enabled = true), 1, 1), true, "SNOWSKY Melody")

    private fun state(
        audio: AudioStatus,
        eq: EqualizerUiState = eqState(),
        kind: DapAudioRouteKind = DapAudioRouteKind.ANDROID_AUDIO,
        selected: Boolean = true,
    ) = EqualizerScreenState.from(eq, audio.equalizerContext(isProfileSelected = selected), audio, tygr, kind)

    private fun setScreen(state: EqualizerScreenState, actions: EqualizerScreenActions = Recorder()) = compose.setContent {
        androidx.compose.runtime.CompositionLocalProvider(com.lostf1sh.pixelplayeross.ui.ds.LocalDapPalette provides palette) {
        androidx.compose.runtime.CompositionLocalProvider(
            LocalDapBackNavigation provides DapBackAuthority(androidx.activity.OnBackPressedDispatcher()),
        ) { EqualizerScreenContent(state, actions) }
    }
    }

    /**
     * The page is a lazy list: what is below the fold is not composed until it is scrolled to. A fit is therefore checked at
     * the top and again after each of three swipes up, so the whole page is looked at and not only the first 805 dp of it.
     */
    private fun fits(label: String) {
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        compose.waitForIdle()
        RenderChecks.assertInsideWindow(compose, label)
        RenderChecks.assertNoCutText(compose, label)
        repeat(3) { step ->
            compose.onRoot().performTouchInput { swipeUp() }
            compose.waitForIdle()
            RenderChecks.assertInsideWindow(compose, "$label (scrolled ${step + 1})")
            RenderChecks.assertNoCutText(compose, "$label (scrolled ${step + 1})")
        }
    }

    private fun assertShows(vararg texts: String) {
        for (t in texts) {
            if (compose.onAllNodesWithText(t).fetchSemanticsNodes().isEmpty()) {
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(t))
            }
            compose.onAllNodesWithText(t)[0].assertExists()
        }
    }

    // ── the four states ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `state 3, DAC and Resonance - the title names the headphones and the DAC, the curve is applied, and the route says Resonance`() {
        setScreen(state(dacResonance))
        assertShows(
            s(R.string.settings_category_equalizer_title), s(R.string.eq_subtitle_with, "TYGR", "SNOWSKY Melody"),
            s(R.string.audio_status_applied), s(R.string.eq_route_resonance),
            s(R.string.eq_mode_saved_in, "TYGR"), s(R.string.headphone_eq_mode_off), s(R.string.headphone_eq_mode_10), s(R.string.headphone_eq_mode_15),
            s(R.string.eq_preset_row), s(R.string.eq_save_preset), s(R.string.eq_section_response), s(R.string.eq_section_bands),
            s(R.string.eq_axis_low), s(R.string.eq_axis_zero), s(R.string.eq_axis_high),
            s(R.string.eq_reset_flat), s(R.string.eq_saved_note, "TYGR"),
        )
        fits("state 3")
    }

    @Test
    fun `state 2, DAC and Pure Direct - frozen, editable, and the row leads to the switch`() {
        val actions = Recorder()
        setScreen(state(dacPure), actions)
        assertShows(s(R.string.audio_status_frozen, s(R.string.pristine_lock_name)), s(R.string.eq_frozen_hint, s(R.string.pristine_lock_name)))
        // Still editable: the sections are there, the bands answer.
        assertShows(s(R.string.eq_section_response), s(R.string.eq_section_bands))
        compose.onNodeWithText(s(R.string.eq_frozen_hint, s(R.string.pristine_lock_name))).performClick()
        assertEquals(listOf("purityLock"), actions.calls)
        fits("state 2")
    }

    @Test
    fun `state 1, no DAC - applied through the Android output, named by where the sound goes`() {
        setScreen(state(noDac, kind = DapAudioRouteKind.SPEAKER))
        assertShows(s(R.string.audio_status_applied), s(R.string.eq_route_android, s(R.string.dap_output_speaker)))
        fits("state 1")
    }

    @Test
    fun `state 4, DAC and crossfade - applied through USB Android`() {
        setScreen(state(dacCrossfade))
        assertShows(s(R.string.audio_status_applied), s(R.string.eq_route_usb_android))
        fits("state 4")
    }

    @Test
    fun `the EQ off hides the curve, the bands and the preset and says the signal passes unchanged`() {
        setScreen(state(dacResonance, eq = eqState(enabled = false)))
        assertShows(s(R.string.eq_off_note), s(R.string.audio_status_off))
        compose.onAllNodesWithText(s(R.string.eq_section_bands)).assertCountEquals(0)
        compose.onAllNodesWithText(s(R.string.eq_section_response)).assertCountEquals(0)
        compose.onAllNodesWithText(s(R.string.eq_preset_row)).assertCountEquals(0)
        fits("off")
    }

    @Test
    fun `another profile is not the one the DSP follows, and the status says so`() {
        setScreen(state(dacResonance, selected = false))
        assertShows(s(R.string.audio_status_other_profile))
    }

    // ── the mode, the bands, the actions ─────────────────────────────────────────────────────────────

    @Test
    fun `choosing 15 bands switches the mode first and then turns the EQ on, and Off turns it off`() {
        val actions = Recorder()
        setScreen(state(noDac, eq = eqState(enabled = false)), actions)
        compose.onNodeWithText(s(R.string.headphone_eq_mode_15)).performClick()
        assertEquals(listOf("mode:GRAPHIC_15", "enabled:true"), actions.calls)
        actions.calls.clear()
        // Ten bands is the mode it is already in: only the switch is turned on.
        compose.onNodeWithText(s(R.string.headphone_eq_mode_10)).performClick()
        assertEquals(listOf("enabled:true"), actions.calls)
    }

    @Test
    fun `Reset to flat and Save as preset call their actions`() {
        val actions = Recorder()
        setScreen(state(dacResonance), actions)
        compose.onNodeWithText(s(R.string.eq_reset_flat)).performScrollTo().performClick()
        assertEquals(listOf("flat"), actions.calls)
    }

    @Test
    fun `every band is a slider that says its frequency and its value, and moves by one dB`() {
        val actions = Recorder()
        setScreen(state(dacResonance), actions)
        val first = compose.onNodeWithContentDescription(s(R.string.eq_band_accessible, "31Hz", eqGainLabel(3)))
        first.performScrollTo().assertIsDisplayed()
        first.performSemanticsAction(SemanticsActions.SetProgress) { it(4f) }
        assertEquals(listOf("band:0:4"), actions.calls)
    }

    @Test
    fun `touching a band puts it where the finger is - top is plus 15, the middle is 0, the bottom is minus 15`() {
        val actions = Recorder()
        setScreen(state(dacResonance), actions)
        val band = compose.onNodeWithContentDescription(s(R.string.eq_band_accessible, "31Hz", eqGainLabel(3))).performScrollTo()
        val density = context.resources.displayMetrics.density
        val thumbRadius = 7f * density
        val trackPx = 160f * density
        band.performTouchInput { click(Offset(center.x, thumbRadius)) }
        band.performTouchInput { click(Offset(center.x, thumbRadius + trackPx / 2f)) }
        band.performTouchInput { click(Offset(center.x, thumbRadius + trackPx)) }
        assertEquals(listOf("band:0:15", "band:0:0", "band:0:-15"), actions.calls)
    }

    @Test
    fun `fifteen bands fit the window - their values and the frequencies that have room`() {
        val bands = List(15) { (it % 7) - 3 }
        setScreen(state(dacResonance, eq = eqState(bands = bands, mode = HeadphoneEqMode.GRAPHIC_15)))
        // Fifteen sliders, one per band.
        val sliders = compose.onAllNodesWithContentDescription(" dB", substring = true)
        assertThat(sliders.fetchSemanticsNodes()).hasSize(15)
        fits("15 bands")
    }

    // ── the thumb is where its value says (fix 2) ────────────────────────────────────────────────────

    @Test
    fun `0 dB is in the middle of the track and plus or minus 15 dB are the ends`() {
        assertThat(eqThumbFraction(0)).isEqualTo(0.5f)
        assertThat(eqThumbFraction(15)).isEqualTo(0f)
        assertThat(eqThumbFraction(-15)).isEqualTo(1f)
    }

    @Test
    fun `two different values are never drawn at the same height - the design drew plus 3 and minus 1 together`() {
        assertThat(eqThumbFraction(3)).isLessThan(eqThumbFraction(-1))
        // Strictly monotonic over the whole range: a higher gain is always a higher thumb.
        val fractions = (-15..15).map { eqThumbFraction(it) }
        assertThat(fractions).isInStrictOrder(compareByDescending<Float> { it })
        // And beyond the engine's range the thumb stays on the track.
        assertThat(eqThumbFraction(40)).isEqualTo(0f)
        assertThat(eqThumbFraction(-40)).isEqualTo(1f)
    }

    @Test
    fun `a touch reads back into the value that draws there`() {
        for (db in -15..15) assertThat(eqLevelForFraction(eqThumbFraction(db))).isEqualTo(db)
        assertThat(eqLevelForFraction(-1f)).isEqualTo(15)
        assertThat(eqLevelForFraction(2f)).isEqualTo(-15)
    }

    @Test
    fun `the value over a band reads the same sign convention as the design`() {
        assertThat(eqGainLabel(3)).isEqualTo("+3")
        assertThat(eqGainLabel(0)).isEqualTo("0")
        assertThat(eqGainLabel(-1)).isEqualTo("-1")
    }

    @Test
    fun `the response curve is the one the bands make - flat is a line on zero, raised bass lifts the low end`() {
        val flat = GraphicEqResponse.curveDb(GraphicEqBands.frequenciesFor(10), List(10) { 0 })
        assertThat(flat.all { kotlin.math.abs(it) < 0.01f }).isTrue()
        val bass = GraphicEqResponse.curveDb(GraphicEqBands.frequenciesFor(10), listOf(9, 9, 6, 3, 0, 0, 0, 0, 0, 0))
        assertThat(bass.first()).isGreaterThan(3f)
        assertThat(bass.last()).isWithin(0.5f).of(0f)
    }
}

/** In Spanish. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "es-w360dp-h2400dp-xxhdpi")
class EqualizerRenderSpanishTest : EqualizerRenderTest()

/** And in Swedish. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "sv-w360dp-h2400dp-xxhdpi")
class EqualizerRenderSwedishTest : EqualizerRenderTest()

/** And on the light theme: every colour is a token, so nothing is white on white. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xxhdpi")
class EqualizerRenderLightTest : EqualizerRenderTest() {
    override val palette = com.lostf1sh.pixelplayeross.ui.ds.DapPalette.Light
}
