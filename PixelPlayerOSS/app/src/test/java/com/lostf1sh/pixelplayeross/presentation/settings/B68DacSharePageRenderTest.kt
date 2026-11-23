package com.lostf1sh.pixelplayeross.presentation.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import com.decent.usbaudio.media3.DirectSignalMode
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.dap.mode.EffectiveProductMode
import com.lostf1sh.pixelplayeross.data.preferences.RandomMode
import com.lostf1sh.pixelplayeross.data.service.player.AudioBackend
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteReason
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteState
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteTransition
import com.lostf1sh.pixelplayeross.data.service.player.DacSharePolicy
import com.lostf1sh.pixelplayeross.presentation.RenderChecks
import com.lostf1sh.pixelplayeross.presentation.viewmodel.AudioStatus
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DacProfilesUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DeviceControlUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.HeadphoneProfilesUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.SettingsUiState
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B68 item 5: an honest Audio page. The share row's subtitle says what the engine will do for the product mode and the Purity choice
 * ([DacSharePolicy.subtitle]), and when the DAC is released the existing "the DAC is shared…" status shows, also in the owner's case (app
 * mode, Purity off, nothing for the app's DSP to do), where before B68 the page said only "Off".
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h2400dp-xxhdpi")
open class B68DacSharePageRenderTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun s(id: Int) = context.getString(id)
    private fun s(id: Int, vararg args: Any) = context.getString(id, *args)

    private object NoActions : AudioSettingsActions {
        override fun onPureDirectChange(value: Boolean) = Unit
        override fun onConfirmPureDirectRise() = Unit
        override fun onDismissPureDirectRise() = Unit
        override fun onResetDigitalGain() = Unit
        override fun onReplayGainEnabledChange(value: Boolean) = Unit
        override fun onReplayGainUseAlbumGainChange(value: Boolean) = Unit
        override fun onCrossfadeEnabledChange(value: Boolean) = Unit
        override fun onSmartCrossfadeEnabledChange(value: Boolean) = Unit
        override fun onPlaybackSpeedChange(value: Float) = Unit
        override fun onRandomModeChange(value: RandomMode) = Unit
        override fun onPersistentShuffleEnabledChange(value: Boolean) = Unit
        override fun onOpenDacProfile(profileId: String) = Unit
        override fun onOpenKnownDacs() = Unit
        override fun onOpenHeadphoneProfile(profileId: String) = Unit
        override fun onOpenKnownHeadphones() = Unit
        override fun onOpenEqualizer(profileId: String) = Unit
        override fun onUseDacEq(profileId: String) = Unit
        override fun onOpenSafetyMax(profileId: String) = Unit
        override fun onOpenGlobalTransitionEditor() = Unit
        override fun onShowFixedOutputNotice() = Unit
        override fun onDacShareChange(value: Boolean) = Unit
        override fun onResonanceLimiterChange(value: Boolean) = Unit
        override fun onAskUsbPermission() = Unit
    }

    /** USB Direct, untouched signal (Purity on, or Purity off with nothing for the DSP to do). */
    private val directPristine = AudioRouteState(
        actualBackend = AudioBackend.USB_DIRECT, transition = AudioRouteTransition.DIRECT_ACTIVE,
        signalMode = DirectSignalMode.PRISTINE, usbDacPresent = true, permissionGranted = true, reason = AudioRouteReason.DIRECT_PRISTINE,
    )

    /** The DAC handed to other apps: Polylute paused on the Android route, the DAC still attached. */
    private val shared = AudioRouteState(
        actualBackend = AudioBackend.ANDROID_AUDIO, transition = AudioRouteTransition.ANDROID_ACTIVE,
        usbDacPresent = true, permissionGranted = true, reason = AudioRouteReason.DAC_SHARED_WITH_OTHER_APPS,
    )

    private fun audio(purity: Boolean, route: AudioRouteState) =
        AudioStatus(purityPreference = purity, route = route, sinkDirectActive = route.isDirectActive)

    private fun state(audio: AudioStatus, mode: EffectiveProductMode?, shareOn: Boolean? = true) = AudioSettingsState.from(
        ui = SettingsUiState(dacShareChoice = shareOn),
        playbackSpeed = 1f,
        audio = audio,
        dacs = DacProfilesUiState(),
        headphones = HeadphoneProfilesUiState(),
        device = DeviceControlUiState(audio = audio),
        productMode = mode,
    )

    private fun setPage(state: AudioSettingsState) = compose.setContent {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = DapSpacing.Gutter)) {
            AudioSettingsScreen(state, NoActions, mountInputBridge = false)
        }
    }

    private fun assertShows(text: String) = compose.onAllNodesWithText(text, substring = false)[0].assertExists()
    private fun assertAbsent(text: String) = compose.onAllNodesWithText(text, substring = false).assertCountEquals(0)

    private val subtitles get() = listOf(R.string.audio_dac_share_sub_app, R.string.audio_dac_share_sub_purity, R.string.audio_dac_share_sub)

    private fun assertOnlySubtitle(id: Int) {
        assertShows(s(id))
        for (other in subtitles - id) assertAbsent(s(other))
    }

    // ── the subtitle: what the engine will actually do ───────────────────────────────────────────

    @Test
    fun `app mode, Purity off - other apps can use the DAC while Polylute is paused`() {
        setPage(state(audio(purity = false, route = directPristine), EffectiveProductMode.APP))
        assertOnlySubtitle(R.string.audio_dac_share_sub_app)
        RenderChecks.assertNoCutText(compose, "app mode, Purity off")
    }

    @Test
    fun `app mode, Purity on - the same - Purity shares too`() {
        setPage(state(audio(purity = true, route = directPristine), EffectiveProductMode.APP))
        assertOnlySubtitle(R.string.audio_dac_share_sub_app)
    }

    @Test
    fun `the Launcher level with Purity on - Purity keeps the DAC`() {
        val st = state(audio(purity = true, route = directPristine), EffectiveProductMode.LAUNCHER)
        assertEquals(DacSharePolicy.Subtitle.DAP_PURITY_KEEPS, st.dacShareSubtitle)
        setPage(st)
        assertOnlySubtitle(R.string.audio_dac_share_sub_purity)
    }

    @Test
    fun `Pinned and Dedicated - no share switch at all (owner 11-23)`() {
        for (mode in listOf(EffectiveProductMode.PINNED, EffectiveProductMode.DEDICATED_ACTIVE)) {
            for (purity in listOf(true, false)) {
                assertEquals(DacSharePolicy.Subtitle.HIDDEN, state(audio(purity = purity, route = directPristine), mode).dacShareSubtitle)
            }
        }
        setPage(state(audio(purity = true, route = directPristine), EffectiveProductMode.DEDICATED_ACTIVE))
        compose.onAllNodesWithText(s(R.string.audio_dac_share_title)).assertCountEquals(0)
        compose.onAllNodesWithText(s(R.string.audio_dac_share_sub_purity)).assertCountEquals(0)
    }

    @Test
    fun `a DAP level in Resonance - as before`() {
        setPage(state(audio(purity = false, route = directPristine), EffectiveProductMode.LAUNCHER))
        assertOnlySubtitle(R.string.audio_dac_share_sub)
    }

    // ── the shared status, when the DAC is released ───────────────────────────────────────────────

    @Test
    fun `the owner's case - app mode, Purity off, no DSP, released - the page says the DAC is shared`() {
        val released = audio(purity = false, route = shared)
        setPage(state(released, EffectiveProductMode.APP))
        assertShows(s(R.string.pristine_status_with_reason, s(R.string.pristine_status_off), s(R.string.route_reason_dac_shared)))
        RenderChecks.assertNoCutText(compose, "shared, Purity off")
    }

    @Test
    fun `app mode, Purity on, released - the page says the DAC is shared`() {
        val released = audio(purity = true, route = shared)
        setPage(state(released, EffectiveProductMode.APP))
        assertShows(s(R.string.pristine_status_with_reason, s(R.string.pristine_status_only_direct), s(R.string.route_reason_dac_shared)))
    }

    @Test
    fun `not released - no shared status`() {
        setPage(state(audio(purity = false, route = directPristine), EffectiveProductMode.APP))
        compose.onAllNodesWithText(s(R.string.route_reason_dac_shared), substring = true).assertCountEquals(0)
    }

    @Test
    fun `nothing stored - the switch is on in app mode and off at every DAP level or an unknown mode`() {
        assertEquals(null, SettingsUiState().dacShareChoice)
        val pristine = audio(purity = true, route = directPristine)
        assertEquals(true, state(pristine, EffectiveProductMode.APP, shareOn = null).dacShareEnabled)
        listOf(EffectiveProductMode.LAUNCHER, EffectiveProductMode.PINNED, EffectiveProductMode.DEDICATED_SETUP_REQUIRED,
            EffectiveProductMode.DEDICATED_ACTIVE, null).forEach { mode ->
            assertEquals("mode $mode", false, state(pristine, mode, shareOn = null).dacShareEnabled)
        }
    }

    @Test
    fun `the listener's own choice wins over the role either way`() {
        val pristine = audio(purity = true, route = directPristine)
        assertEquals(false, state(pristine, EffectiveProductMode.APP, shareOn = false).dacShareEnabled)
        assertEquals(true, state(pristine, EffectiveProductMode.DEDICATED_ACTIVE, shareOn = true).dacShareEnabled)
    }
}

@Config(sdk = [35], application = android.app.Application::class, qualifiers = "es-w360dp-h2400dp-xxhdpi")
class B68DacSharePageRenderSpanishTest : B68DacSharePageRenderTest()

@Config(sdk = [35], application = android.app.Application::class, qualifiers = "sv-w360dp-h2400dp-xxhdpi")
class B68DacSharePageRenderSwedishTest : B68DacSharePageRenderTest()
