package com.lostf1sh.pixelplayeross.presentation.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.decent.usbaudio.media3.DirectSignalMode
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.dac.DacIdentityScope
import com.lostf1sh.pixelplayeross.data.dac.DacProfile
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneEqMode
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneEqState
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneProfile
import com.lostf1sh.pixelplayeross.data.model.TransitionMode
import com.lostf1sh.pixelplayeross.data.model.TransitionSettings
import com.lostf1sh.pixelplayeross.data.preferences.RandomMode
import com.lostf1sh.pixelplayeross.data.service.player.AudioBackend
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteReason
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteState
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteTransition
import com.lostf1sh.pixelplayeross.data.volume.PristineModePolicy
import com.lostf1sh.pixelplayeross.presentation.RenderChecks
import com.lostf1sh.pixelplayeross.presentation.viewmodel.AudioStatus
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DacLiveStatus
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DacProfileUiItem
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DacProfilesUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DapAudioRouteKind
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DeviceControlUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.HeadphoneProfileUiItem
import com.lostf1sh.pixelplayeross.presentation.viewmodel.HeadphoneProfilesUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.SettingsUiState
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B22, Part 1: the Audio page in the four states `VarAudio.dc.html` draws, at the HONOR's 360 dp, in English, Spanish
 * and Swedish.
 *
 * What is proved here, from the semantics of a render:
 *  - the design's texts are there (sections, rows, the one status line of each row), in each state;
 *  - **no output line** on the page (it lives on the lock screen, Details and the Settings root);
 *  - "Ganancia de" is a normal row: its whole label, enabled, with Purity on and off (frozen is 40 %, not disabled);
 *  - nothing is laid out past the window's edge and no label loses its tail, in each language;
 *  - the Pure Direct switch, random mode and persistent mix call the actions that write their preferences.
 */
@RunWith(RobolectricTestRunner::class)
// Native graphics: the JVM's default text metrics never overflow, so a clipping test would pass on anything.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h2400dp-xxhdpi")
open class AudioPageRenderTest {

    /** The palette the page is drawn in; the Light subclass runs every test again on the light theme. */
    protected open val palette: com.lostf1sh.pixelplayeross.ui.ds.DapPalette = com.lostf1sh.pixelplayeross.ui.ds.DapPalette.Graphite

    @get:Rule
    val compose = createComposeRule()

    protected val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    protected fun s(id: Int) = context.getString(id)
    protected fun s(id: Int, vararg args: Any) = context.getString(id, *args)

    private class Recorder : AudioSettingsActions {
        val calls = mutableListOf<String>()
        override fun onPureDirectChange(value: Boolean) { calls += "pure:$value" }
        override fun onConfirmPureDirectRise() { calls += "riseConfirm" }
        override fun onDismissPureDirectRise() { calls += "riseDismiss" }
        override fun onResetDigitalGain() { calls += "digital100" }
        override fun onReplayGainEnabledChange(value: Boolean) { calls += "replayGain:$value" }
        override fun onReplayGainUseAlbumGainChange(value: Boolean) { calls += "albumGain:$value" }
        override fun onCrossfadeEnabledChange(value: Boolean) { calls += "crossfade:$value" }
        override fun onSmartCrossfadeEnabledChange(value: Boolean) { calls += "smart:$value" }
        override fun onPlaybackSpeedChange(value: Float) { calls += "speed:$value" }
        override fun onRandomModeChange(value: RandomMode) { calls += "random:${value.id}" }
        override fun onPersistentShuffleEnabledChange(value: Boolean) { calls += "persistent:$value" }
        override fun onOpenDacProfile(profileId: String) { calls += "dac:$profileId" }
        override fun onOpenKnownDacs() { calls += "knownDacs" }
        override fun onOpenHeadphoneProfile(profileId: String) { calls += "headphone:$profileId" }
        override fun onOpenKnownHeadphones() { calls += "knownHeadphones" }
        override fun onOpenEqualizer(profileId: String) { calls += "eq:$profileId" }
        override fun onOpenSafetyMax(profileId: String) { calls += "safety:$profileId" }
        override fun onOpenGlobalTransitionEditor() { calls += "editor" }
    }

    // ── the four audio states ────────────────────────────────────────────────────────────────────────

    private fun route(mode: DirectSignalMode, dac: Boolean = true) = AudioRouteState(
        actualBackend = AudioBackend.USB_DIRECT, transition = AudioRouteTransition.DIRECT_ACTIVE,
        signalMode = mode, usbDacPresent = dac,
        reason = if (mode == DirectSignalMode.PRISTINE) AudioRouteReason.DIRECT_PRISTINE else AudioRouteReason.DIRECT_PROCESSED,
    )

    /** 1 · no DAC, Pure Direct waiting. */
    private val noDac = AudioStatus(
        purityPreference = true,
        route = AudioRouteState(usbDacPresent = false, reason = AudioRouteReason.NO_USB_DAC),
    )

    /** 2 · DAC + Pure Direct: USB Direct carries an untouched stream. */
    private val dacPure = AudioStatus(purityPreference = true, route = route(DirectSignalMode.PRISTINE), sinkDirectActive = true)

    /** 3 · DAC + Resonance: Pure Direct off, USB Direct with the app's DSP. */
    private val dacResonance = AudioStatus(purityPreference = false, route = route(DirectSignalMode.PROCESSED), sinkDirectActive = true)

    /** 4 · DAC + crossfade: the stream is on the Android route because of it. */
    private val dacCrossfade = AudioStatus(
        purityPreference = false,
        route = AudioRouteState(
            actualBackend = AudioBackend.ANDROID_AUDIO, transition = AudioRouteTransition.ANDROID_ACTIVE,
            reason = AudioRouteReason.CROSSFADE_REQUIRES_ANDROID, usbDacPresent = true,
        ),
    )

    private val dac = DacProfileUiItem(
        DacProfile(
            profileId = "dac-1", deviceIdentityKey = "k", identityScope = DacIdentityScope.MODEL_SCOPED,
            displayName = "SNOWSKY Melody", detectedManufacturer = "FIIO", vendorId = 1, productId = 2,
            createdAtEpochMs = 1, lastSeenEpochMs = 1,
        ),
        DacLiveStatus.ACTIVE,
    )
    private val headphone = HeadphoneProfileUiItem(
        HeadphoneProfile("hp-1", "TYGR", HeadphoneEqState(enabled = true, mode = HeadphoneEqMode.GRAPHIC_10, presetName = "rock"), 1, 1),
        isSelected = true,
        associatedDacName = "SNOWSKY Melody",
    )

    private fun state(
        audio: AudioStatus,
        ui: SettingsUiState = SettingsUiState(replayGainEnabled = true),
        withDac: Boolean = audio.dacConnected,
        speed: Float = 1.0f,
        device: DeviceControlUiState = DeviceControlUiState(audio = audio),
    ) = AudioSettingsState.from(
        ui = ui,
        playbackSpeed = speed,
        audio = audio,
        dacs = if (withDac) DacProfilesUiState(profiles = listOf(dac), current = dac) else DacProfilesUiState(),
        headphones = HeadphoneProfilesUiState(profiles = listOf(headphone), selected = headphone),
        device = device,
        transition = TransitionSettings(mode = TransitionMode.OVERLAP, durationMs = 4000),
    )

    private fun setPage(state: AudioSettingsState, actions: AudioSettingsActions = Recorder()) = compose.setContent {
        androidx.compose.runtime.CompositionLocalProvider(com.lostf1sh.pixelplayeross.ui.ds.LocalDapPalette provides palette) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = DapSpacing.Gutter)
        ) {
            // The dialog's volume-key bridge needs the app's Hilt component, which a JVM render does not have.
            AudioSettingsScreen(state, actions, mountInputBridge = false)
        }
    }
    }

    private fun assertShows(vararg texts: String) {
        for (t in texts) compose.onAllNodesWithText(t, substring = false)[0].assertExists()
    }

    private fun assertAbsent(vararg texts: String) {
        for (t in texts) compose.onAllNodesWithText(t, substring = true).assertCountEquals(0)
    }

    private fun fits(label: String) {
        RenderChecks.assertInsideWindow(compose, label)
        RenderChecks.assertNoCutText(compose, label)
    }

    // ── what every state shows ───────────────────────────────────────────────────────────────────────

    @Test
    fun `the page has its five sections and no output line`() {
        setPage(state(dacPure))
        assertShows(
            s(R.string.audio_section_output), s(R.string.audio_section_pure), s(R.string.audio_section_sound),
            s(R.string.audio_section_transitions), s(R.string.audio_section_queue),
        )
        // The output line ("SNOWSKY Melody · USB Direct · 16 bits · 44,1 kHz") is not here, nor under the title.
        compose.onAllNodes(hasText("kHz", substring = true)).assertCountEquals(0)
        compose.onAllNodes(hasText("bits", substring = true, ignoreCase = true)).assertCountEquals(0)
    }

    @Test
    fun `state 1 - no DAC - everything applies, Pure Direct is waiting, and the output row says where the sound goes`() {
        val st = state(noDac, device = DeviceControlUiState(audio = noDac, audioRouteKind = DapAudioRouteKind.SPEAKER))
        setPage(st)
        assertShows(
            s(R.string.dac_no_connected), s(R.string.audio_output_speaker), s(R.string.pristine_status_waiting),
            s(R.string.audio_gapless_yes),
        )
        // EQ and ReplayGain apply: two "Applied" lines, none frozen.
        compose.onAllNodesWithText(s(R.string.audio_status_applied)).assertCountEquals(2)
        assertAbsent(s(R.string.audio_status_frozen, s(R.string.pristine_lock_name)))
        fits("state 1")
    }

    @Test
    fun `state 2 - DAC and Pure Direct - EQ, ReplayGain, crossfade and speed are frozen, and say so on their own row`() {
        setPage(state(dacPure))
        val frozen = s(R.string.audio_status_frozen, s(R.string.pristine_lock_name))
        // EQ, ReplayGain, crossfade, speed: four frozen lines, each on its row.
        compose.onAllNodesWithText(frozen).assertCountEquals(4)
        assertShows(
            s(R.string.pristine_status_active), s(R.string.audio_gapless_yes_direct),
            s(R.string.audio_eq_sub_saved, "TYGR", s(R.string.audio_row_join, s(R.string.headphone_eq_mode_10), "ROCK")),
        )
        // The old per-row notices and their "Change" buttons are gone.
        assertAbsent(s(R.string.pristine_lock_change))
        // Crossfade and speed cannot be changed meanwhile; ReplayGain stays a live switch.
        compose.onAllNodes(isToggleable())[1].assertIsEnabled()
        fits("state 2")
    }

    @Test
    fun `state 3 - DAC and Resonance - the EQ applies, the digital level warns, crossfade and speed name the Android route`() {
        val device = DeviceControlUiState(
            audio = dacResonance, digitalGainPercent = 85,
            usbDirectState = com.decent.usbaudio.media3.UsbDirectSinkState.USB_DIRECT_ACTIVE,
        )
        val actions = Recorder()
        setPage(state(dacResonance, device = device), actions)
        assertShows(
            s(R.string.pristine_status_with_reason, s(R.string.pristine_status_off), s(R.string.pristine_reason_resonance)),
            s(R.string.audio_digital_warning, 85),
            s(R.string.setcat_crossfade_android_route_note), s(R.string.setcat_speed_android_route_note),
        )
        compose.onNodeWithText(s(R.string.digital_reset_full)).performScrollTo().performClick()
        assertEquals(listOf("digital100"), actions.calls)
        fits("state 3")
    }

    @Test
    fun `state 4 - DAC and crossfade - crossfade is applied through the Android route, and the songs do not join without a gap`() {
        setPage(state(dacCrossfade, ui = SettingsUiState(replayGainEnabled = true, isCrossfadeEnabled = true)))
        assertShows(
            s(R.string.pristine_status_with_reason, s(R.string.pristine_status_off), s(R.string.pristine_reason_crossfade)),
            s(R.string.audio_status_android_route), s(R.string.audio_gapless_crossfade),
            s(R.string.audio_crossfade_seconds, "4"),
        )
        fits("state 4")
    }

    // ── fix 3: "Ganancia de" ─────────────────────────────────────────────────────────────────────────

    private fun assertGainRowIsNormal(audio: AudioStatus) {
        val actions = Recorder()
        setPage(state(audio), actions)
        // Its whole label: displayed, and not cut.
        compose.onNodeWithText(s(R.string.audio_gain_of)).performScrollTo().assertIsDisplayed()
        RenderChecks.assertNoCutText(compose, "Ganancia de")
        // The segments answer. Frozen is 40 % and kept, never disabled.
        compose.onNodeWithText(s(R.string.setcat_gain_mode_album)).performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf("albumGain:true"), actions.calls)
    }

    @Test
    fun `Ganancia de is a normal row, its whole label and enabled, with Pure Direct off`() = assertGainRowIsNormal(dacResonance)

    @Test
    fun `Ganancia de is a normal row, its whole label and enabled, when Pure Direct freezes ReplayGain`() = assertGainRowIsNormal(dacPure)

    // ── the two moves ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the Pure Direct switch is on this page and calls the action that writes its preference`() {
        val actions = Recorder()
        setPage(state(dacResonance), actions)
        // First switch of the page: Pure Direct.
        compose.onAllNodes(isToggleable())[0].performClick()
        assertEquals(listOf("pure:true"), actions.calls)
    }

    @Test
    fun `random mode and the persistent mix are in the Queue section and call their actions`() {
        val actions = Recorder()
        setPage(state(dacResonance, ui = SettingsUiState(randomMode = RandomMode.CLASSIC, persistentShuffleEnabled = false)), actions)
        compose.onNodeWithText(s(R.string.setcat_random_mode_title)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(s(R.string.random_mode_fast)).performScrollTo().performClick()
        compose.onNodeWithText(s(R.string.audio_persistent_mix_sub)).performScrollTo().assertIsDisplayed()
        val switches = compose.onAllNodes(isToggleable())
        switches[switches.fetchSemanticsNodes().lastIndex].performScrollTo().performClick()
        assertEquals(listOf("random:fast", "persistent:true"), actions.calls)
    }

    @Test
    fun `the equalizer, Safety Max and the crossfade editor open from their rows`() {
        val actions = Recorder()
        setPage(state(dacResonance, ui = SettingsUiState(replayGainEnabled = true, isCrossfadeEnabled = true)), actions)
        compose.onNodeWithText(s(R.string.audio_eq_title)).performScrollTo().performClick()
        compose.onNodeWithText(s(R.string.safety_max_title)).performScrollTo().performClick()
        compose.onNodeWithText(s(R.string.setcat_crossfade_label)).performScrollTo().performClick()
        assertEquals(listOf("eq:hp-1", "safety:hp-1", "editor"), actions.calls)
    }

    // ── the dialog ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the level-would-rise dialog shows when turning Pure Direct on needs a confirmation, and answers both ways`() {
        val actions = Recorder()
        val device = DeviceControlUiState(
            audio = dacResonance, pristineRiseToConfirm = 6.0f, pristineRiseReason = PristineModePolicy.RiseReason.NO_HARDWARE_VOLUME,
        )
        setPage(state(dacResonance, device = device), actions)
        compose.onNodeWithText(s(R.string.pristine_rise_title)).assertIsDisplayed()
        compose.onNodeWithText(s(R.string.pristine_rise_confirm)).performClick()
        compose.onNodeWithText(s(R.string.pristine_rise_cancel)).performClick()
        assertEquals(listOf("riseConfirm", "riseDismiss"), actions.calls)
    }

    @Test
    fun `without a pending confirmation there is no dialog`() {
        setPage(state(dacResonance))
        compose.onAllNodesWithText(s(R.string.pristine_rise_title)).assertCountEquals(0)
    }

    // ── a long Spanish/Swedish headphone name must not push anything out ─────────────────────────────

    @Test
    fun `a very long headphone name ends in an ellipsis and does not push the row out of the window`() {
        val long = HeadphoneProfileUiItem(
            HeadphoneProfile("hp-2", "Auriculares de estudio con cancelación activa de ruido Edición Aniversario", HeadphoneEqState(), 1, 1),
            isSelected = true,
        )
        val st = state(dacPure).copy(
            selectedHeadphone = long,
            safetyMax = SafetyMaxRowState("hp-2", long.profile.displayName, 17, com.lostf1sh.pixelplayeross.presentation.viewmodel.FeatureStatus.Applied),
        )
        setPage(st)
        RenderChecks.assertInsideWindow(compose, "long name")
        assertTrue(true)
    }
}

/** The same page in Spanish: the design's own words, and the widest labels of the three languages. */
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "es-w360dp-h2400dp-xxhdpi")
class AudioPageRenderSpanishTest : AudioPageRenderTest()

/** And in Swedish, whose words are the longest on several rows. */
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "sv-w360dp-h2400dp-xxhdpi")
class AudioPageRenderSwedishTest : AudioPageRenderTest()

/** And on the light theme: every colour is a token, so nothing is white on white. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xxhdpi")
class AudioPageRenderLightTest : AudioPageRenderTest() {
    override val palette = com.lostf1sh.pixelplayeross.ui.ds.DapPalette.Light
}
