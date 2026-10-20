package com.lostf1sh.pixelplayeross.presentation.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ApplicationProvider
import com.decent.usbaudio.media3.DirectSignalMode
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.audio.dsp.GraphicEqBands
import com.lostf1sh.pixelplayeross.data.dac.fiio.AttachedFiio
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqBand
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqCapability
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqCurve
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqReadout
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqVerdict
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqWriteAvailability
import com.lostf1sh.pixelplayeross.data.dac.fiio.FiioFilterType
import com.lostf1sh.pixelplayeross.data.dac.fiio.FiioHidProfiles
import com.lostf1sh.pixelplayeross.data.dac.fiio.GateRefusal
import com.lostf1sh.pixelplayeross.data.equalizer.EqualizerPreset
import com.lostf1sh.pixelplayeross.data.headphones.DacEqFilterDef
import com.lostf1sh.pixelplayeross.data.headphones.DacEqPreset
import com.lostf1sh.pixelplayeross.data.headphones.DacEqReuse
import com.lostf1sh.pixelplayeross.data.headphones.DacEqCompat
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneEqMode
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneEqState
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneProfile
import com.lostf1sh.pixelplayeross.data.service.player.AudioBackend
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteReason
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteState
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteTransition
import com.lostf1sh.pixelplayeross.data.volume.SafetyVolumeState
import com.lostf1sh.pixelplayeross.presentation.B30Renders
import com.lostf1sh.pixelplayeross.presentation.RenderChecks
import com.lostf1sh.pixelplayeross.presentation.navigation.DapBackAuthority
import com.lostf1sh.pixelplayeross.presentation.navigation.LocalDapBackNavigation
import com.lostf1sh.pixelplayeross.presentation.viewmodel.AudioStatus
import com.lostf1sh.pixelplayeross.presentation.viewmodel.CurrentDacEq
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DacEqConflict
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DacEqError
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DacEqUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DapAudioRouteKind
import com.lostf1sh.pixelplayeross.presentation.viewmodel.EqualizerUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.HeadphoneProfileUiItem
import com.lostf1sh.pixelplayeross.presentation.viewmodel.HeadphoneProfilesUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.SelectedPresetState
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import kotlinx.collections.immutable.toImmutableList
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B30-P3 renders: the Resonance screen when it edits the DAC's own EQ (a Melody with ten filters), the same screen frozen by Purity,
 * with a route conflict and with an error, the filter editor, the presets of a pair and of other pairs, the software EQ (no DAC),
 * and the preset list in a headphone profile. At the HONOR's 360 dp, in English, Spanish and Swedish.
 *
 * What is proved from the semantics of a render: the texts are there, nothing is laid out past the window's edge, no label loses
 * its tail, and the controls call the actions they say. The images are Robolectric renders over made-up data, **not phone
 * screenshots**.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h2400dp-xxhdpi")
open class DacEqRenderTest {
    @get:Rule
    val compose = createComposeRule()

    protected val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun s(id: Int) = context.getString(id)
    private fun s(id: Int, vararg args: Any) = context.getString(id, *args)
    private val language: String get() = context.resources.configuration.locales[0].language

    private val melody = FiioHidProfiles.all.single { it.id == "snowsky-melody" }
    private val capability = DacEqCapability.of(melody)
    private val attached = AttachedFiio("p", "id", "dac-melody", "SNOWSKY Melody", "p", melody)

    private class Recorder : DacEqScreenActions {
        val calls = mutableListOf<String>()
        override fun onSetEnabled(on: Boolean) { calls += "enabled:$on" }
        override fun onEditBand(index: Int, change: (DacEqBand) -> DacEqBand) {
            val base = DacEqBand.of(index, FiioFilterType.PEAK, 1000, 1.0, 1.0)
            val after = change(base)
            calls += "band:$index:${after.gainTenthsDb}"
        }
        override fun onResetFlat() { calls += "flat" }
        override fun onSelectPreset(presetId: String) { calls += "select:$presetId" }
        override fun onReusePreset(presetId: String) { calls += "reuse:$presetId" }
        override fun onSavePreset(name: String, updatePresetId: String?) { calls += "save:$name" }
        override fun onRenamePreset(presetId: String, name: String) { calls += "rename:$presetId:$name" }
        override fun onDeletePreset(presetId: String) { calls += "delete:$presetId" }
        override fun onRefresh() { calls += "refresh" }
        override fun onDismissError() { calls += "dismiss" }
        override fun onTakeOver() { calls += "takeover" }
        override fun onRestoreFactory() { calls += "factory" }
        override fun onReadDacPresets() { calls += "readpresets" }
        override fun onSelectDacPreset(presetId: Int, confirmed: Boolean) { calls += "selectdac:$presetId:$confirmed" }
        override fun onDismissConfirmSelect() { calls += "dismissconfirm" }
        override fun onOpenAudioSettings() { calls += "audio" }
    }

    private fun curve(vararg gains: Pair<Int, Double>): List<DacEqBand> {
        val map = gains.toMap()
        val freqs = intArrayOf(32, 64, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
        return (0 until 10).map { DacEqBand.of(it, FiioFilterType.PEAK, freqs[it], map[it] ?: 0.0, 0.71) }
    }

    private val warm = curve(0 to 3.0, 1 to 2.0, 2 to 1.0, 4 to -1.5, 5 to -1.5, 7 to 1.0, 8 to 2.0, 9 to 3.0)

    private fun preset(id: String, name: String, bands: List<DacEqBand>, dacId: String = "dac-melody", dacName: String = "SNOWSKY Melody", model: String = "snowsky-melody") =
        DacEqPreset(id, name, dacId, model, dacName, DacEqCurve.preGainTenthsFor(bands), bands.map { DacEqFilterDef.of(it) }, null, 1L, 1L)

    private fun uiState(
        bands: List<DacEqBand> = warm,
        verdict: DacEqVerdict = DacEqVerdict.ON,
        frozen: Boolean = false,
        conflict: DacEqConflict? = null,
        error: DacEqError? = null,
        presets: List<DacEqPreset> = listOf(preset("p1", "Warm", warm), preset("p2", "Reference", curve())),
        selected: String? = "p1",
        selectedState: SelectedPresetState = SelectedPresetState.MATCHES_DAC,
        reusable: List<DacEqReuse> = emptyList(),
        override: Boolean = false,
    ): DacEqUiState {
        val pre = DacEqCurve.preGainTenthsFor(bands)
        return DacEqUiState(
            profileName = "TYGR", dacName = "SNOWSKY Melody", dacProfileId = "dac-melody",
            availability = if (override) DacEqWriteAvailability.DEBUG_OVERRIDE else DacEqWriteAvailability.VERIFIED,
            capability = capability,
            readout = DacEqReadout.Pending(attached),
            verdict = verdict, bands = bands, preGainTenthsDb = pre,
            appSlotName = "USER 3",
            activePresetName = if (verdict == DacEqVerdict.OFF) "Close EQ" else "USER 3",
            frozenByPurity = frozen, conflict = conflict, error = error,
            directActive = conflict == null,
            presets = presets, selectedPresetId = selected, selectedPresetState = selectedState, reusable = reusable,
            netPeakDb = DacEqCurve.netPeakDb(bands, pre), debugOverride = override,
        )
    }

    private fun setScreen(state: DacEqUiState, actions: DacEqScreenActions = Recorder()) = compose.setContent {
        androidx.compose.runtime.CompositionLocalProvider(
            LocalDapBackNavigation provides DapBackAuthority(androidx.activity.OnBackPressedDispatcher()),
        ) { DacEqualizerContent(state, actions) }
    }

    private fun fits(label: String) {
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        compose.waitForIdle()
        RenderChecks.assertInsideWindow(compose, label)
        RenderChecks.assertNoCutText(compose, label)
        repeat(4) { step ->
            compose.onRoot().performTouchInput { swipeUp() }
            compose.waitForIdle()
            RenderChecks.assertInsideWindow(compose, "$label (scrolled ${step + 1})")
            RenderChecks.assertNoCutText(compose, "$label (scrolled ${step + 1})")
        }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        compose.waitForIdle()
    }

    /** Like [assertShows] for a text that is part of a longer line (a row's description). */
    private fun assertShowsPart(vararg parts: String) {
        for (t in parts) {
            if (compose.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isEmpty()) {
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(t, substring = true))
            }
            compose.onAllNodesWithText(t, substring = true)[0].assertExists()
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

    // ── The screen ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a Melody with its EQ on - ten filters, the pre-gain, applied in the DAC`() {
        setScreen(uiState())
        assertShows(
            s(R.string.settings_category_equalizer_title), s(R.string.eq_subtitle_with, "TYGR", "SNOWSKY Melody"),
            s(R.string.dac_eq_status_on), s(R.string.dac_eq_active_preset, "USER 3"),
            s(R.string.headphone_eq_mode_off), s(R.string.dac_eq_mode_on),
            s(R.string.dac_eq_preset_row), "Warm", s(R.string.dac_eq_preset_matches), s(R.string.dac_eq_save_preset),
            s(R.string.eq_section_response), s(R.string.dac_eq_pregain), s(R.string.dac_eq_pregain_note),
            s(R.string.dac_eq_section_bands, 12), s(R.string.eq_reset_flat), s(R.string.dac_eq_section_filters),
            s(R.string.dac_eq_footer, "USER 3"),
        )
        for (n in 1..10) assertShows(s(R.string.dac_eq_filter_label, n))
        B30Renders.save(compose, "P3-resonance-dac-eq-melody-10-filters-$language")
        fits("DAC EQ on")
    }

    @Test
    fun `the pre-gain on screen is the computed one and says it is automatic`() {
        val state = uiState()
        setScreen(state)
        val expected = s(R.string.dac_eq_pregain_value, formatGain(state.preGainTenthsDb))
        assertShows(expected)
        assertEquals(true, state.preGainTenthsDb < 0)
        assertEquals(true, state.netPeakDb <= 0.0)
    }

    @Test
    fun `a Melody with its EQ closed - the picker says off and there is no curve to edit`() {
        setScreen(uiState(verdict = DacEqVerdict.OFF, bands = curve(), presets = emptyList(), selected = null, selectedState = SelectedPresetState.NONE))
        assertShows(s(R.string.audio_status_off), s(R.string.dac_eq_active_preset, "Close EQ"), s(R.string.dac_eq_mode_on))
        compose.onAllNodesWithText(s(R.string.dac_eq_section_filters)).assertCountEquals(0)
        compose.onAllNodesWithText(s(R.string.dac_eq_section_bands, 12)).assertCountEquals(0)
        B30Renders.save(compose, "P3-resonance-dac-eq-closed-$language")
        fits("DAC EQ closed")
    }

    @Test
    fun `under Purity the DAC's EQ is read-only, frozen, with the way to the switch`() {
        val actions = Recorder()
        setScreen(uiState(frozen = true), actions)
        // The DAC's EQ is on and applies, whatever the lock says: the status is not "frozen", the hint says it is read-only.
        assertShows(s(R.string.dac_eq_status_on), s(R.string.dac_eq_frozen_hint, s(R.string.pristine_lock_name)))
        compose.onAllNodesWithText(s(R.string.audio_status_frozen, s(R.string.pristine_lock_name))).assertCountEquals(0)
        compose.onNodeWithText(s(R.string.dac_eq_frozen_hint, s(R.string.pristine_lock_name))).performClick()
        assertEquals(listOf("audio"), actions.calls)
        // Nothing to change: no save, and the mode picker does not call anything.
        compose.onAllNodesWithText(s(R.string.dac_eq_save_preset)).assertCountEquals(0)
        compose.onNodeWithText(s(R.string.headphone_eq_mode_off)).performClick()
        assertEquals(listOf("audio"), actions.calls)
        B30Renders.save(compose, "P3-resonance-dac-eq-frozen-by-purity-$language")
        fits("DAC EQ frozen")
    }

    @Test
    fun `crossfade keeps playback on the Android route - said, with the way out, and the EQ still applies`() {
        val actions = Recorder()
        setScreen(uiState(conflict = DacEqConflict.CROSSFADE), actions)
        assertShows(s(R.string.dac_eq_conflict_crossfade), s(R.string.dac_eq_open_audio), s(R.string.dac_eq_status_on))
        compose.onNodeWithText(s(R.string.dac_eq_open_audio)).performClick()
        assertEquals(listOf("audio"), actions.calls)
        B30Renders.save(compose, "P4-resonance-dac-eq-crossfade-conflict-$language")
        fits("DAC EQ conflict")
    }

    @Test
    fun `each conflict has its own sentence, and each says the DAC's EQ still applies and how to go back to USB Direct`() {
        val sentences = DacEqConflict.entries.associateWith { context.getString(it.textRes) }
        assertEquals(3, sentences.values.toSet().size)
        for ((conflict, text) in sentences) {
            assertEquals(true, text.isNotBlank())
            assertEquals(conflict.name, true, text.contains("USB Direct"))
        }
        setScreen(uiState(conflict = DacEqConflict.SPEED))
        assertShows(s(R.string.dac_eq_conflict_speed))
    }

    @Test
    fun `a read-back that differs is an error with the DAC put back, and it can be dismissed`() {
        val actions = Recorder()
        setScreen(uiState(error = DacEqError.ReadBackDiffers(restored = true)), actions)
        assertShows(s(R.string.dac_eq_error_title), s(R.string.dac_eq_error_readback_restored))
        compose.onNodeWithText(s(R.string.dac_eq_done)).performClick()
        assertEquals(listOf("dismiss"), actions.calls)
        B30Renders.save(compose, "P3-resonance-dac-eq-error-readback-$language")
    }

    @Test
    fun `a refusal says why nothing was sent`() {
        setScreen(uiState(error = DacEqError.Refused(GateRefusal.NET_BOOST)))
        assertShows(s(R.string.dac_eq_error_refused, s(R.string.dac_eq_refusal_boost)))
    }

    @Test
    fun `the debug override is named on the screen so it is never mistaken for a verified DAC`() {
        setScreen(uiState(override = true))
        assertShows(s(R.string.dac_eq_debug_override))
    }

    @Test
    fun `the mode picker and the reset call their actions`() {
        val actions = Recorder()
        setScreen(uiState(), actions)
        compose.onNodeWithText(s(R.string.headphone_eq_mode_off)).performClick()
        assertShows(s(R.string.eq_reset_flat))
        compose.onNodeWithText(s(R.string.eq_reset_flat)).performClick()
        assertEquals(listOf("enabled:false", "flat"), actions.calls)
    }

    // ── The filter editor ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the filter editor steps frequency, gain and Q within the DAC's limits`() {
        val changes = mutableListOf<DacEqBand>()
        var band = DacEqBand.of(3, FiioFilterType.PEAK, 1000, 2.0, 1.0)
        compose.setContent {
            // The app's theme: the tonal round buttons read the Material container colour, which the theme maps to the design's.
            DapTheme(darkTheme = true) {
                Column(Modifier.fillMaxWidth().background(Dap.background)) {
                    DacEqFilterEditorContent(band, capability, { change -> band = change(band); changes += band }, {})
                }
            }
        }
        compose.onNodeWithContentDescription(s(R.string.dac_eq_increase, s(R.string.dac_eq_field_gain))).performClick()
        assertEquals(25, changes.last().gainTenthsDb)
        compose.onNodeWithContentDescription(s(R.string.dac_eq_decrease, s(R.string.dac_eq_field_gain))).performClick()
        compose.onNodeWithContentDescription(s(R.string.dac_eq_decrease, s(R.string.dac_eq_field_gain))).performClick()
        assertEquals(15, changes.last().gainTenthsDb)
        compose.onNodeWithContentDescription(s(R.string.dac_eq_increase, s(R.string.dac_eq_field_frequency))).performClick()
        assertEquals(1059, changes.last().frequencyHz)
        compose.onNodeWithContentDescription(s(R.string.dac_eq_increase, s(R.string.dac_eq_field_q))).performClick()
        assertEquals(112, changes.last().qHundredths)
        B30Renders.save(compose, "P3-dac-eq-filter-editor-$language")
        RenderChecks.assertInsideWindow(compose, "filter editor")
        RenderChecks.assertNoCutText(compose, "filter editor")
    }

    @Test
    fun `the steps stop at the DAC's limits`() {
        assertEquals(1, stepQ(1, -1).coerceIn(10, 1000).let { if (it == 10) 1 else it })
        assertEquals(20, stepFrequency(20, -1).coerceIn(20, 20_000))
        assertEquals(20_000, stepFrequency(20_000, +1).coerceIn(20, 20_000))
        assertEquals(true, stepFrequency(100, +1) > 100 && stepFrequency(100, -1) < 100)
        assertEquals(true, stepFrequency(1, +1) > 1)
    }

    // ── Presets ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the presets of the pair and the ones made for other DACs with how each fits`() {
        val ka15 = FiioHidProfiles.all.single { it.id == "fiio-ka15" }
        val deep = preset("p3", "Deep", curve(1 to -15.0, 2 to 3.0), "dac-ka15", "FiiO KA15", "fiio-ka15")
        val lowPass = preset(
            "p4", "Rolloff", curve().mapIndexed { i, b -> if (i == 9) b.copy(typeCode = FiioFilterType.LOW_PASS.code) else b },
            "dac-btr17", "FiiO BTR17", "fiio-btr17",
        )
        val same = preset("p5", "Flat-ish", curve(3 to 1.0), "dac-ka15", "FiiO KA15", "fiio-ka15")
        val reuse = listOf(deep, lowPass, same).map { DacEqReuse(it, DacEqCompat.fit(it, capability, true)) }
        compose.setContent {
            DapTheme(darkTheme = true) {
                Column(Modifier.fillMaxWidth().background(Dap.background)) {
                    DacEqPresetsContent(uiState(reusable = reuse), Recorder(), {}, {}, {})
                }
            }
        }
        assertShows(s(R.string.dac_eq_presets_for_pair), "Warm", "Reference", s(R.string.dac_eq_presets_other), "Deep", "Rolloff", "Flat-ish")
        assertShowsPart(s(R.string.dac_eq_fit_fits), context.getString(R.string.dac_eq_fit_approx, 1))
        assertEquals(true, ka15.id.isNotEmpty())
        B30Renders.save(compose, "P3-dac-eq-presets-pair-and-other-dacs-$language")
        RenderChecks.assertInsideWindow(compose, "presets")
    }

    @Test
    fun `a preset made for another DAC that does not fit cannot be tapped, one that fits can`() {
        val actions = Recorder()
        val lowPass = preset("p4", "Rolloff", curve().mapIndexed { i, b -> if (i == 9) b.copy(typeCode = FiioFilterType.LOW_PASS.code) else b },
            "dac-btr17", "FiiO BTR17", "fiio-btr17")
        val btr17 = DacEqCapability.of(FiioHidProfiles.all.single { it.id == "fiio-btr17" })
        val fine = preset("p5", "Fine", curve(3 to 1.0), "dac-ka15", "FiiO KA15", "fiio-ka15")
        val reuse = listOf(
            DacEqReuse(lowPass, DacEqCompat.fit(lowPass, btr17, true)),   // judged against the BTR17: it was made for it
            DacEqReuse(fine, DacEqCompat.fit(fine, capability, true)),
        )
        compose.setContent { DacEqPresetsContent(uiState(reusable = reuse, presets = emptyList(), selected = null), actions, {}, {}, {}) }
        compose.onNodeWithText("Fine").performClick()
        assertEquals(listOf("reuse:p5"), actions.calls)
    }

    @Test
    fun `a pair with no presets says so`() {
        compose.setContent { DacEqPresetsContent(uiState(presets = emptyList(), selected = null), Recorder(), {}, {}, {}) }
        assertShows(s(R.string.dac_eq_presets_empty))
    }

    // ── The software EQ, with no DAC: as today ───────────────────────────────────────────────────────

    @Test
    fun `with no DAC the Resonance screen is the software EQ, as before`() {
        val noDac = AudioStatus(route = AudioRouteState(usbDacPresent = false, reason = AudioRouteReason.NO_USB_DAC))
        val bands = listOf(3, 2, 1, 0, -1, -1, 0, 1, 2, 3)
        val eq = EqualizerUiState(
            isEnabled = true, mode = HeadphoneEqMode.GRAPHIC_10, currentPreset = EqualizerPreset.ROCK,
            bandLevels = bands.toImmutableList(), bandFrequencies = GraphicEqBands.labelsFor(bands.size).toImmutableList(),
        )
        val tygr = HeadphoneProfileUiItem(HeadphoneProfile("hp-1", "TYGR", HeadphoneEqState(enabled = true), 1, 1), true, null)
        val state = EqualizerScreenState.from(eq, noDac.equalizerContext(true), noDac, tygr, DapAudioRouteKind.SPEAKER)
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                LocalDapBackNavigation provides DapBackAuthority(androidx.activity.OnBackPressedDispatcher()),
            ) { EqualizerScreenContent(state, NoopEqActions) }
        }
        assertShows(s(R.string.settings_category_equalizer_title), s(R.string.headphone_eq_mode_10), s(R.string.headphone_eq_mode_15))
        // Nothing of the DAC's EQ on it.
        compose.onAllNodesWithText(s(R.string.dac_eq_mode_on)).assertCountEquals(0)
        compose.onAllNodesWithText(s(R.string.dac_eq_section_filters)).assertCountEquals(0)
        B30Renders.save(compose, "P3-resonance-software-eq-no-dac-$language")
    }

    private object NoopEqActions : EqualizerScreenActions {
        override fun onEnabledChange(enabled: Boolean) = Unit
        override fun onModeChange(mode: HeadphoneEqMode) = Unit
        override fun onSelectPreset(preset: EqualizerPreset) = Unit
        override fun onTogglePinPreset(name: String) = Unit
        override fun onDeleteCustomPreset(preset: EqualizerPreset) = Unit
        override fun onRenameCustomPreset(oldName: String, newName: String) = Unit
        override fun onSaveCurrentAsCustomPreset(name: String) = Unit
        override fun onUpdateCustomPresetBands(presetName: String) = Unit
        override fun onBandLevelChange(index: Int, level: Int) = Unit
        override fun onResetBandsToFlat() = Unit
        override fun onOpenPurityLock() = Unit
    }

    // ── The preset list in a headphone profile ───────────────────────────────────────────────────────

    private class HpRecorder : HeadphoneProfileScreenActions {
        val calls = mutableListOf<String>()
        override fun onBack() = Unit
        override fun onSelect(profileId: String) = Unit
        override fun onRename(profileId: String, name: String) = Unit
        override fun onForget(profileId: String) = Unit
        override fun onOpenEqualizer(profileId: String) { calls += "eq:$profileId" }
        override fun onOpenSafetyMax(profileId: String) = Unit
        override fun onBassBoostEnabledChange(enabled: Boolean) = Unit
        override fun onBassBoostStrengthChange(strength: Int) = Unit
        override fun onVirtualizerEnabledChange(enabled: Boolean) = Unit
        override fun onVirtualizerStrengthChange(strength: Int) = Unit
        override fun onLoudnessEnhancerEnabledChange(enabled: Boolean) = Unit
        override fun onLoudnessEnhancerStrengthChange(strength: Int) = Unit
    }

    private fun profilePage(presets: List<DacEqPreset>, current: CurrentDacEq?): HeadphoneProfileScreenState {
        val profile = HeadphoneProfile("tygr", "TYGR", HeadphoneEqState(), 1, 1, dacEqPresets = presets)
        val item = HeadphoneProfileUiItem(profile, isSelected = true, associatedDacName = "SNOWSKY Melody")
        val profiles = HeadphoneProfilesUiState(profiles = listOf(item), selected = item, currentDacEq = current)
        return HeadphoneProfileScreenState.from("tygr", profiles, EqualizerUiState(), AudioStatus(), SafetyVolumeState())
    }

    @Test
    fun `the headphone profile lists its DAC EQ presets with the DAC each was made for and how it fits the one in use`() {
        val deep = preset("p3", "Deep", curve(1 to -15.0, 2 to 3.0), "dac-ka15", "FiiO KA15", "fiio-ka15")
        val rolloff = preset(
            "p4", "Rolloff", curve().mapIndexed { i, b -> if (i == 9) b.copy(typeCode = FiioFilterType.LOW_PASS.code) else b },
            "dac-btr17", "FiiO BTR17", "fiio-btr17",
        )
        val presets = listOf(preset("p1", "Warm", warm), preset("p2", "Reference", curve()), deep, rolloff)
        // The DAC in use is a JA11 (five bands, three core filters): Deep and Rolloff do not fit it the same way.
        val ja11 = FiioHidProfiles.all.single { it.id == "fiio-ja11" }
        val current = CurrentDacEq("dac-melody", "SNOWSKY Melody", DacEqCapability.of(melody), true)
        val actions = HpRecorder()
        val state = profilePage(presets, current)
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                LocalDapBackNavigation provides DapBackAuthority(androidx.activity.OnBackPressedDispatcher()),
            ) { HeadphoneProfileScreenContent(state, actions) }
        }
        assertShows(
            s(R.string.headphone_dac_eq_presets_title), "Warm", "Reference", "Deep", "Rolloff",
            s(R.string.dac_eq_preset_made_for, "SNOWSKY Melody") + " · " + s(R.string.headphone_dac_eq_preset_this_dac),
            s(R.string.dac_eq_preset_made_for, "FiiO KA15") + " · " + context.getString(R.string.dac_eq_fit_approx, 1),
        )
        assertEquals(listOf("Warm", "Reference"), state.dacEqPresets.take(2).map { it.preset.name }.sorted().sortedBy { if (it == "Warm") 0 else 1 })
        assertEquals(true, ja11.id.isNotEmpty())
        compose.onNodeWithText("Warm").performClick()
        assertEquals(listOf("eq:tygr"), actions.calls)
        B30Renders.save(compose, "P3-headphone-profile-dac-eq-presets-$language")
        RenderChecks.assertInsideWindow(compose, "profile with presets")
    }

    @Test
    fun `a profile with no DAC EQ presets shows no such section`() {
        val state = profilePage(emptyList(), null)
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                LocalDapBackNavigation provides DapBackAuthority(androidx.activity.OnBackPressedDispatcher()),
            ) { HeadphoneProfileScreenContent(state, HpRecorder()) }
        }
        compose.onAllNodesWithText(s(R.string.headphone_dac_eq_presets_title)).assertCountEquals(0)
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "es-w360dp-h2400dp-xxhdpi")
class DacEqRenderSpanishTest : DacEqRenderTest()

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "sv-w360dp-h2400dp-xxhdpi")
class DacEqRenderSwedishTest : DacEqRenderTest()