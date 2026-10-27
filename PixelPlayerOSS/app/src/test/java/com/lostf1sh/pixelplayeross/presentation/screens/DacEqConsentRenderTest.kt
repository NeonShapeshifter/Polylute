package com.lostf1sh.pixelplayeross.presentation.screens

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.dac.fiio.AttachedFiio
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqBand
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqCapability
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqCurve
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqReadout
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqVerdict
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqWriteAvailability
import com.lostf1sh.pixelplayeross.data.dac.fiio.FiioFilterType
import com.lostf1sh.pixelplayeross.data.dac.fiio.FiioHidProfiles
import com.lostf1sh.pixelplayeross.presentation.RenderChecks
import com.lostf1sh.pixelplayeross.presentation.components.DacEqConsentDialog
import com.lostf1sh.pixelplayeross.presentation.navigation.DapBackAuthority
import com.lostf1sh.pixelplayeross.presentation.navigation.LocalDapBackNavigation
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DacEqOffer
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DacEqUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.EqualizerUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.AudioStatus
import com.lostf1sh.pixelplayeross.data.equalizer.EqualizerPreset
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneEqMode
import com.lostf1sh.pixelplayeross.data.service.player.AudioBackend
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteReason
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteState
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteTransition
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DapAudioRouteKind
import com.lostf1sh.pixelplayeross.presentation.viewmodel.FeatureStatusKind
import com.decent.usbaudio.media3.DirectSignalMode
import androidx.compose.ui.test.performScrollTo
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import com.lostf1sh.pixelplayeross.data.preferences.BackNavigationMode
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.volume.SafetyVolumeController
import com.lostf1sh.pixelplayeross.di.ModalInputEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B40-F2 renders: the DAC's EQ screen when the DAC could not be read (it says so, and shows no Off, no 0 dB and no flat curve), the
 * "same correction twice" note, the way back to Polylute's EQ, the software screen's offer, and the permission dialog. At 360 dp, in
 * English, Spanish and Swedish.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h2400dp-xxhdpi")
open class DacEqConsentRenderTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun s(id: Int) = context.getString(id)
    private fun s(id: Int, vararg args: Any) = context.getString(id, *args)

    private val melody = FiioHidProfiles.all.single { it.id == "snowsky-melody" }
    private val attached = AttachedFiio("p", "id", "dac-melody", "SNOWSKY Melody", "p", melody)

    /** The dialog is a window of its own and mounts the modal input bridge, which asks Hilt for the volume authority; Hilt answers with doubles. */
    @Before
    fun hiltAnswers() {
        val repository = mockk<UserPreferencesRepository>(relaxed = true)
        every { repository.backNavigationModeFlow } returns flowOf(BackNavigationMode.DEFAULT)
        val entryPoint = object : ModalInputEntryPoint {
            override fun safetyVolumeController(): SafetyVolumeController = mockk(relaxed = true)
            override fun userPreferencesRepository(): UserPreferencesRepository = repository
        }
        mockkStatic(EntryPointAccessors::class)
        every {
            EntryPointAccessors.fromApplication(ApplicationProvider.getApplicationContext<android.content.Context>(), ModalInputEntryPoint::class.java)
        } returns entryPoint
    }

    @After
    fun hiltIsBack() = unmockkStatic(EntryPointAccessors::class)

    private class Recorder : DacEqScreenActions {
        val calls = mutableListOf<String>()
        override fun onSetEnabled(on: Boolean) { calls += "enabled:$on" }
        override fun onEditBand(index: Int, change: (DacEqBand) -> DacEqBand) = Unit
        override fun onResetFlat() = Unit
        override fun onSelectPreset(presetId: String) = Unit
        override fun onReusePreset(presetId: String) = Unit
        override fun onSavePreset(name: String, updatePresetId: String?) = Unit
        override fun onRenamePreset(presetId: String, name: String) = Unit
        override fun onDeletePreset(presetId: String) = Unit
        override fun onRefresh() { calls += "refresh" }
        override fun onDismissError() = Unit
        override fun onTakeOver() = Unit
        override fun onRestoreFactory() = Unit
        override fun onReadDacPresets() = Unit
        override fun onSelectDacPreset(presetId: Int, confirmed: Boolean) = Unit
        override fun onDismissConfirmSelect() = Unit
        override fun onOpenAudioSettings() = Unit
        override fun onBackToSoftwareEq() { calls += "back" }
        override fun onTurnOffSoftwareEq() { calls += "twice" }
    }

    private fun curve(): List<DacEqBand> {
        val freqs = intArrayOf(32, 64, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
        return (0 until 10).map { DacEqBand.of(it, FiioFilterType.PEAK, freqs[it], if (it == 0) 2.0 else 0.0, 0.71) }
    }

    private fun state(
        readout: DacEqReadout = DacEqReadout.Pending(attached),
        verdict: DacEqVerdict = DacEqVerdict.ON,
        availability: DacEqWriteAvailability = DacEqWriteAvailability.USER_CONFIRMED,
        softwareAlsoOn: Boolean = false,
    ): DacEqUiState {
        val bands = curve()
        val pre = DacEqCurve.preGainTenthsFor(bands)
        return DacEqUiState(
            profileName = "TYGR", dacName = "SNOWSKY Melody", dacProfileId = "dac-melody", availability = availability,
            capability = DacEqCapability.of(melody), readout = readout, verdict = verdict, bands = bands, preGainTenthsDb = pre,
            appSlotName = "USER 3", activePresetName = "USER 3", directActive = true, netPeakDb = DacEqCurve.netPeakDb(bands, pre),
            softwareEqAlsoOn = softwareAlsoOn,
        )
    }

    private fun setScreen(state: DacEqUiState, actions: DacEqScreenActions = Recorder()) = compose.setContent {
        androidx.compose.runtime.CompositionLocalProvider(
            LocalDapBackNavigation provides DapBackAuthority(androidx.activity.OnBackPressedDispatcher()),
        ) { DacEqualizerContent(state, actions) }
    }

    private fun showsSomewhere(text: String): Boolean {
        if (compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()) {
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))
        }
        return compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun `a DAC that could not be read says so - no Off, no 0 dB and no flat curve are shown as if they were read`() {
        val actions = Recorder()
        setScreen(state(readout = DacEqReadout.Unreadable(attached, "no answer"), verdict = DacEqVerdict.UNKNOWN), actions)
        assertThat(showsSomewhere(s(R.string.dac_eq_unread_title))).isTrue()
        assertThat(showsSomewhere(s(R.string.dac_eq_unread_body))).isTrue()
        // what an unread DAC must not show: the mode picker's Off/On, the response graph, the pre-gain, the filter rows
        compose.onAllNodesWithText(s(R.string.headphone_eq_mode_off)).assertCountEquals(0)
        compose.onAllNodesWithText(s(R.string.dac_eq_pregain)).assertCountEquals(0)
        compose.onAllNodesWithText(s(R.string.dac_eq_section_filters)).assertCountEquals(0)
        compose.onAllNodesWithText(s(R.string.eq_section_response)).assertCountEquals(0)
        compose.onNodeWithText(s(R.string.dac_eq_read_again)).performScrollTo().performClick()
        assertThat(actions.calls).contains("refresh")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(s(R.string.dac_eq_unread_title)))
        RenderChecks.assertInsideWindow(compose, "unread")
        RenderChecks.assertNoCutText(compose, "unread")
    }

    @Test
    fun `a DAC that was read still shows its picker, curve and pre-gain as before`() {
        setScreen(state())
        assertThat(showsSomewhere(s(R.string.headphone_eq_mode_off))).isTrue()
        assertThat(showsSomewhere(s(R.string.dac_eq_pregain))).isTrue()
        compose.onAllNodesWithText(s(R.string.dac_eq_unread_title)).assertCountEquals(0)
    }

    @Test
    fun `the same correction twice is said, with one tap to turn the software EQ off`() {
        val actions = Recorder()
        setScreen(state(softwareAlsoOn = true), actions)
        assertThat(showsSomewhere(s(R.string.dac_eq_twice_note))).isTrue()
        compose.onNodeWithText(s(R.string.dac_eq_twice_action)).performScrollTo().performClick()
        assertThat(actions.calls).containsExactly("twice")
        RenderChecks.assertNoCutText(compose, "twice")
    }

    @Test
    fun `nothing is said about twice when the software EQ is off`() {
        setScreen(state(softwareAlsoOn = false))
        compose.onAllNodesWithText(s(R.string.dac_eq_twice_note)).assertCountEquals(0)
    }

    @Test
    fun `with the listener's permission the screen offers the way back to Polylute's EQ, and it calls the action`() {
        val actions = Recorder()
        setScreen(state(availability = DacEqWriteAvailability.USER_CONFIRMED), actions)
        assertThat(showsSomewhere(s(R.string.dac_eq_back_to_software))).isTrue()
        compose.onNodeWithText(s(R.string.dac_eq_back_to_software)).performScrollTo().performClick()
        assertThat(actions.calls).contains("back")
        assertThat(showsSomewhere(s(R.string.dac_eq_back_to_software_sub))).isTrue()
    }

    @Test
    fun `a verified profile, or the reviewer's override, has no permission to take back`() {
        setScreen(state(availability = DacEqWriteAvailability.VERIFIED))
        compose.onAllNodesWithText(s(R.string.dac_eq_back_to_software)).assertCountEquals(0)
    }

    @Test
    fun `the permission dialog says what will happen, names the DAC and its slot, and offers Allow and Not now`() {
        var allowed = 0
        var dismissed = 0
        compose.setContent {
            DacEqConsentDialog(DacEqOffer("SNOWSKY Melody", "USER 3"), onAllow = { allowed++ }, onDismiss = { dismissed++ })
        }
        compose.waitForIdle()
        compose.onNodeWithText(s(R.string.dac_eq_consent_title)).assertExists()
        compose.onNodeWithText(s(R.string.dac_eq_consent_body, "SNOWSKY Melody", "USER 3")).assertExists()
        compose.onNodeWithText(s(R.string.dac_eq_consent_not_now)).performClick()
        compose.onNodeWithText(s(R.string.dac_eq_consent_allow)).performClick()
        assertThat(allowed).isEqualTo(1)
        assertThat(dismissed).isEqualTo(1)
    }

    @Test
    fun `the software screen offers the DAC's own EQ, naming the DAC, and the offer calls its action`() {
        val calls = mutableListOf<String>()
        val actions = object : EqualizerScreenActions {
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
            override fun onUseDacEq() { calls += "use" }
        }
        val eq = EqualizerUiState()
        val screen = EqualizerScreenState(
            eq = eq, output = com.lostf1sh.pixelplayeross.presentation.viewmodel.EqualizerOutputState.APPLIED, audio = AudioStatus(),
            profileName = "TYGR", dacOffer = DacEqOffer("SNOWSKY Melody", "USER 3"),
        )
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                LocalDapBackNavigation provides DapBackAuthority(androidx.activity.OnBackPressedDispatcher()),
            ) { EqualizerScreenContent(screen, actions) }
        }
        compose.onNodeWithText(s(R.string.dac_eq_offer_title)).assertExists()
        compose.onNodeWithText(s(R.string.dac_eq_offer_sub, "SNOWSKY Melody")).assertExists()
        compose.onNodeWithText(s(R.string.dac_eq_offer_title)).performClick()
        assertThat(calls).containsExactly("use")
    }

    @Test
    fun `no offer, no row`() {
        val actions = object : EqualizerScreenActions {
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
        val screen = EqualizerScreenState(
            eq = EqualizerUiState(), output = com.lostf1sh.pixelplayeross.presentation.viewmodel.EqualizerOutputState.APPLIED,
            audio = AudioStatus(), profileName = "TYGR",
        )
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                LocalDapBackNavigation provides DapBackAuthority(androidx.activity.OnBackPressedDispatcher()),
            ) { EqualizerScreenContent(screen, actions) }
        }
        compose.onAllNodesWithText(s(R.string.dac_eq_offer_title)).assertCountEquals(0)
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "es-w360dp-h2400dp-xxhdpi")
class DacEqConsentRenderSpanishTest : DacEqConsentRenderTest()

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "sv-w360dp-h2400dp-xxhdpi")
class DacEqConsentRenderSwedishTest : DacEqConsentRenderTest()
