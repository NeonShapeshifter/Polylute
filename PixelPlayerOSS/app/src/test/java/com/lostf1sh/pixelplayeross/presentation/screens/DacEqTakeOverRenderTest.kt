package com.lostf1sh.pixelplayeross.presentation.screens

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ApplicationProvider
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.dac.fiio.AttachedFiio
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqBand
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqCapability
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqCurve
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqDefaults
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqReadout
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqVerdict
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqWriteAvailability
import com.lostf1sh.pixelplayeross.data.dac.fiio.FiioHidProfiles
import com.lostf1sh.pixelplayeross.data.dac.fiio.PreGainSense
import com.lostf1sh.pixelplayeross.presentation.B31Renders
import com.lostf1sh.pixelplayeross.presentation.RenderChecks
import com.lostf1sh.pixelplayeross.presentation.navigation.DapBackAuthority
import com.lostf1sh.pixelplayeross.presentation.navigation.LocalDapBackNavigation
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DacEqError
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DacEqUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DacPresetRow
import com.lostf1sh.pixelplayeross.presentation.viewmodel.textRes
import com.lostf1sh.pixelplayeross.data.dac.fiio.GateRefusal
import com.lostf1sh.pixelplayeross.data.preferences.BackNavigationMode
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.volume.SafetyVolumeController
import com.lostf1sh.pixelplayeross.di.ModalInputEntryPoint
import dagger.hilt.android.EntryPointAccessors
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B31 P1.3 / P1.4 renders: the Resonance screen offering to take USER 3 over, listing the DAC's own presets by name (read, not read, locked,
 * the one selected), the card after the take-over with the way back, and the two confirmations. At the HONOR's 360 dp in English, Spanish
 * and Swedish; Robolectric renders over made-up data, **not phone screenshots**, under `analysis/.../B31-evidence/renders/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h2400dp-xxhdpi")
open class DacEqTakeOverRenderTest {
    @get:Rule
    val compose = createComposeRule()

    /**
     * A dialog is a window of its own and mounts the modal input bridge, which asks Hilt for the volume authority and the Back mode
     * (`ModalInputEntryPoint`). These renders are about the screen, so Hilt answers with doubles; the bridge itself has its own tests.
     */
    @Before
    fun hiltAnswers() {
        val repository = mockk<UserPreferencesRepository>(relaxed = true)
        every { repository.backNavigationModeFlow } returns flowOf(BackNavigationMode.DEFAULT)
        val entryPoint = object : ModalInputEntryPoint {
            override fun safetyVolumeController(): SafetyVolumeController = mockk(relaxed = true)
            override fun userPreferencesRepository(): UserPreferencesRepository = repository
        }
        mockkStatic(EntryPointAccessors::class)
        every { EntryPointAccessors.fromApplication(ApplicationProvider.getApplicationContext<android.content.Context>(), ModalInputEntryPoint::class.java) } returns entryPoint
    }

    @After
    fun hiltIsBack() = unmockkStatic(EntryPointAccessors::class)

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
        override fun onEditBand(index: Int, change: (DacEqBand) -> DacEqBand) { calls += "band:$index" }
        override fun onResetFlat() { calls += "flat" }
        override fun onSelectPreset(presetId: String) { calls += "select:$presetId" }
        override fun onReusePreset(presetId: String) { calls += "reuse:$presetId" }
        override fun onSavePreset(name: String, updatePresetId: String?) { calls += "save:$name" }
        override fun onRenamePreset(presetId: String, name: String) { calls += "rename:$presetId" }
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

    private fun row(id: Int, name: String, active: Boolean = false, read: Boolean = true, pre: Int? = 0, filters: Int? = 0, locked: Boolean = false,
        app: Boolean = false, closed: Boolean = false) =
        DacPresetRow(id, name, active, app, closed, read, if (read) pre else null, if (read) filters else null, locked)

    private val unreadRows = listOf(
        row(0, "Jazz", read = false), row(1, "Pop", read = false), row(2, "Rock", read = false), row(3, "Dance", read = false),
        row(4, "R&B", read = false), row(5, "Classic", read = false), row(6, "HipHop", read = false),
        row(0xA0, "USER 1", read = false), row(0xA1, "USER 2", read = false), row(0xA2, "USER 3", read = false, app = true),
        row(0xF0, "Close EQ", active = true, closed = true, read = false),
    )

    private val readRows = listOf(
        row(0, "Jazz", pre = -35, filters = 10, locked = true), row(1, "Pop", pre = -20, filters = 10, locked = true),
        row(2, "Rock", pre = -20, filters = 10, locked = true), row(3, "Dance", pre = -30, filters = 10, locked = true),
        row(4, "R&B", pre = -30, filters = 10, locked = true), row(5, "Classic", pre = -20, filters = 10, locked = true),
        row(6, "HipHop", pre = -30, filters = 10, locked = true),
        row(0xA0, "USER 1", pre = 0, filters = 0), row(0xA1, "USER 2", pre = 0, filters = 0),
        row(0xA2, "USER 3", pre = 80, filters = 10, locked = true, app = true),
        row(0xF0, "Close EQ", active = true, closed = true, pre = 20, filters = 0),
    )

    private fun flat() = DacEqDefaults.flatBands(10)

    private fun uiState(
        owned: Boolean = false,
        rows: List<DacPresetRow> = unreadRows,
        read: Boolean = false,
        confirm: DacPresetRow? = null,
        operation: com.lostf1sh.pixelplayeross.presentation.viewmodel.DacEqOperation? = null,
        error: DacEqError? = null,
        sense: PreGainSense = PreGainSense.UNCONFIRMED,
        bands: List<DacEqBand> = flat(),
    ): DacEqUiState {
        val pre = DacEqCurve.preGainTenthsFor(bands, sense) ?: 0
        return DacEqUiState(
            profileName = "TYGR", dacName = "SNOWSKY Melody", dacProfileId = "dac-melody",
            availability = DacEqWriteAvailability.DEBUG_OVERRIDE, capability = capability,
            readout = DacEqReadout.Pending(attached), verdict = DacEqVerdict.OFF, bands = bands, preGainTenthsDb = pre,
            appSlotName = "USER 3", activePresetName = "Close EQ", error = error, directActive = true,
            netPeakDb = DacEqCurve.netPeakDb(bands, pre, sense), debugOverride = true,
            slotOwned = owned, takeOverOffered = !owned && operation == null, operation = operation,
            dacPresets = rows, presetsRead = read, confirmSelect = confirm, preGainSense = sense,
        )
    }

    private fun setScreen(state: DacEqUiState, actions: DacEqScreenActions = Recorder()) = compose.setContent {
        androidx.compose.runtime.CompositionLocalProvider(
            LocalDapBackNavigation provides DapBackAuthority(androidx.activity.OnBackPressedDispatcher()),
        ) { DacEqualizerContent(state, actions) }
    }

    private fun assertShows(vararg texts: String) {
        for (t in texts) {
            if (compose.onAllNodesWithText(t).fetchSemanticsNodes().isEmpty()) {
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(t))
            }
            compose.onAllNodesWithText(t)[0].assertExists()
        }
    }

    private fun fits(label: String) {
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        compose.waitForIdle()
        RenderChecks.assertInsideWindow(compose, label)
        RenderChecks.assertNoCutText(compose, label)
        repeat(5) { step ->
            compose.onRoot().performTouchInput { swipeUp() }
            compose.waitForIdle()
            RenderChecks.assertInsideWindow(compose, "$label (scrolled ${step + 1})")
            RenderChecks.assertNoCutText(compose, "$label (scrolled ${step + 1})")
        }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        compose.waitForIdle()
    }

    @Test
    fun `the slot is offered on the card, with what taking it over does, and a tap asks for it`() {
        val actions = Recorder()
        setScreen(uiState(), actions)
        assertShows(
            s(R.string.dac_eq_takeover_title, "USER 3"), s(R.string.dac_eq_takeover_body, "USER 3"), s(R.string.dac_eq_takeover_action, "USER 3"),
            s(R.string.dac_eq_dac_presets_title), s(R.string.dac_eq_dac_presets_note), s(R.string.dac_eq_dac_presets_read),
            s(R.string.dac_eq_dac_presets_read_note), s(R.string.dac_eq_dac_preset_unread), "Jazz", "USER 1", "Close EQ",
        )
        B31Renders.save(compose, "P1-resonance-take-over-offered-$language")
        compose.onAllNodesWithText(s(R.string.dac_eq_takeover_action, "USER 3"))[0].performClick()
        assertEquals(listOf("takeover"), actions.calls)
        fits("take-over offered")
    }

    @Test
    fun `after the take-over the card is gone, the slot is the app's, and the way back is there behind a question`() {
        val actions = Recorder()
        setScreen(uiState(owned = true), actions)
        compose.onAllNodesWithText(s(R.string.dac_eq_takeover_action, "USER 3")).assertCountEquals(0)
        assertShows(s(R.string.dac_eq_owned_title, "USER 3"), s(R.string.dac_eq_restore_factory))
        compose.onAllNodesWithText(s(R.string.dac_eq_restore_factory))[0].performClick()
        compose.waitForIdle()
        assertShows(s(R.string.dac_eq_restore_factory_title), s(R.string.dac_eq_restore_factory_note, "USER 3"))
        B31Renders.save(compose, "P1-resonance-restore-factory-question-$language")
        // Cancelling sends nothing; confirming asks for the factory curve.
        compose.onAllNodesWithText(s(R.string.cancel))[0].performClick()
        compose.waitForIdle()
        assertEquals(emptyList<String>(), actions.calls)
        compose.onAllNodesWithText(s(R.string.dac_eq_restore_factory))[0].performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText(s(R.string.dac_eq_restore_factory_confirm))[0].performClick()
        assertEquals(listOf("factory"), actions.calls)
    }

    @Test
    fun `the DAC's presets read from the DAC say what they hold, lock the ones that could play too loud, and select on a tap`() {
        val actions = Recorder()
        setScreen(uiState(owned = false, rows = readRows, read = true), actions)
        assertShows(
            s(R.string.dac_eq_dac_preset_locked), s(R.string.dac_eq_dac_preset_active), s(R.string.dac_eq_dac_preset_closed),
            s(R.string.dac_eq_dac_preset_detail, formatGain(0), 0),
        )
        compose.onAllNodesWithText(s(R.string.dac_eq_dac_presets_read)).assertCountEquals(0)
        B31Renders.save(compose, "P1-resonance-dac-presets-read-$language")
        // A locked preset does nothing; a free one selects.
        compose.onAllNodesWithText("Jazz")[0].performClick()
        assertEquals(emptyList<String>(), actions.calls)
        compose.onAllNodesWithText("USER 1")[0].performClick()
        assertEquals(listOf("selectdac:${0xA0}:false"), actions.calls)
        fits("DAC presets read")
    }

    @Test
    fun `a select while music plays asks first, and both answers are wired`() {
        val actions = Recorder()
        val user1 = readRows.first { it.id == 0xA0 }
        setScreen(uiState(rows = readRows, read = true, confirm = user1), actions)
        assertShows(s(R.string.dac_eq_select_confirm_title), s(R.string.dac_eq_select_confirm_body, "USER 1"))
        B31Renders.save(compose, "P1-resonance-select-confirm-$language")
        compose.onAllNodesWithText(s(R.string.dac_eq_select_confirm_action))[0].performClick()
        assertEquals(listOf("selectdac:${0xA0}:true"), actions.calls)
        compose.onAllNodesWithText(s(R.string.cancel))[0].performClick()
        assertEquals(listOf("selectdac:${0xA0}:true", "dismissconfirm"), actions.calls)
    }

    @Test
    fun `while the sense is unknown a curve that rises above 0 dB says why it cannot be written`() {
        val boosted = DacEqDefaults.flatBands(10).mapIndexed { i, b -> if (i == 4) b.copy(gainTenthsDb = 30) else b }
        val state = uiState(owned = true, bands = boosted, sense = PreGainSense.UNCONFIRMED)
        assertEquals(true, state.senseBlocksCurve)
        setScreen(state.copy(verdict = DacEqVerdict.ON, activePresetName = "USER 3"))
        assertShows(s(R.string.dac_eq_pregain_sense_note))
        B31Renders.save(compose, "P1-resonance-sense-unconfirmed-$language")
    }

    @Test
    fun `the refusals the new steps can give are said in words, and while a step runs one line says which`() {
        for (refusal in listOf(GateRefusal.PLAYBACK_NOT_PAUSED, GateRefusal.PRE_GAIN_SENSE_UNCONFIRMED, GateRefusal.SLOT_NOT_TAKEN_OVER,
            GateRefusal.SLOT_IS_THE_APPS, GateRefusal.CONFIRMATION_NEEDED, GateRefusal.LOW_LEVEL_NOT_CONFIRMED)) {
            assertEquals(refusal.name, true, context.getString(refusal.textRes()).isNotBlank())
        }
        setScreen(uiState(error = DacEqError.Refused(GateRefusal.PLAYBACK_NOT_PAUSED)))
        assertShows(s(R.string.dac_eq_error_refused, s(R.string.dac_eq_refusal_playing)))
        B31Renders.save(compose, "P1-resonance-refused-playing-$language")
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "es-w360dp-h2400dp-xxhdpi")
class DacEqTakeOverRenderSpanishTest : DacEqTakeOverRenderTest()

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "sv-w360dp-h2400dp-xxhdpi")
class DacEqTakeOverRenderSwedishTest : DacEqTakeOverRenderTest()
