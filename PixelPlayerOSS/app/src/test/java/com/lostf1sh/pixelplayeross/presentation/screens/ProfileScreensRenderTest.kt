package com.lostf1sh.pixelplayeross.presentation.screens

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.decent.usbaudio.media3.DirectSignalMode
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.dac.DacIdentityScope
import com.lostf1sh.pixelplayeross.data.dac.DacProfile
import com.lostf1sh.pixelplayeross.data.equalizer.EqualizerPreset
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneEqMode
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneEqState
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneProfile
import com.lostf1sh.pixelplayeross.data.service.player.AudioBackend
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteState
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteTransition
import com.lostf1sh.pixelplayeross.data.volume.SafetyVolumeState
import com.lostf1sh.pixelplayeross.presentation.navigation.DapBackAuthority
import com.lostf1sh.pixelplayeross.presentation.navigation.LocalDapBackNavigation
import com.lostf1sh.pixelplayeross.presentation.viewmodel.AudioStatus
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DacLiveStatus
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DacProfileUiItem
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DacProfilesUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.EqualizerUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.HeadphoneProfileUiItem
import com.lostf1sh.pixelplayeross.presentation.viewmodel.HeadphoneProfilesUiState
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B21: the EQ, headphone-profile, Safety Max and DAC-profile screens, split into state + actions, still say the same
 * words, and the two defects fixed in the batch stay fixed:
 *
 *  - **item 4**: a profile whose EQ is off says "Saved: Off" once, not "Saved: Off · OFF";
 *  - **item 5**: the "Change" of the DAC profile's *Headphones* section sits at the end of the header's row, centred on
 *    the same line as its label; it used to float in the middle of the row, top-aligned.
 */
@RunWith(RobolectricTestRunner::class)
// A tall window (the HONOR is 720x1610 px), so a lazy list composes every row instead of the ones that fit 470 dp.
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w411dp-h2400dp-xxhdpi")
class ProfileScreensRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    /** A screen as the shell hosts it: with the back authority its scaffold reads. */
    private fun setScreen(content: @androidx.compose.runtime.Composable () -> Unit) = compose.setContent {
        androidx.compose.runtime.CompositionLocalProvider(
            LocalDapBackNavigation provides DapBackAuthority(androidx.activity.OnBackPressedDispatcher()),
            content = content,
        )
    }

    private fun s(id: Int) = context.getString(id)
    private fun s(id: Int, vararg args: Any) = context.getString(id, *args)

    private val direct = AudioRouteState(
        actualBackend = AudioBackend.USB_DIRECT, transition = AudioRouteTransition.DIRECT_ACTIVE,
        signalMode = DirectSignalMode.PRISTINE, usbDacPresent = true,
    )
    private val purityOn = AudioStatus(purityPreference = true, route = direct, sinkDirectActive = true)

    // ── Equalizer ────────────────────────────────────────────────────────────────────────────────────

    // The equalizer (B22) has its own file: `EqualizerRenderTest`.

    // ── Headphone profile ────────────────────────────────────────────────────────────────────────────

    private class HpRecorder : HeadphoneProfileScreenActions {
        val calls = mutableListOf<String>()
        override fun onBack() { calls += "back" }
        override fun onSelect(profileId: String) { calls += "select:$profileId" }
        override fun onRename(profileId: String, name: String) { calls += "rename:$profileId:$name" }
        override fun onForget(profileId: String) { calls += "forget:$profileId" }
        override fun onOpenEqualizer(profileId: String) { calls += "eq:$profileId" }
        override fun onOpenSafetyMax(profileId: String) { calls += "safety:$profileId" }
        override fun onBassBoostEnabledChange(enabled: Boolean) { calls += "bass:$enabled" }
        override fun onBassBoostStrengthChange(strength: Int) { calls += "bassStrength:$strength" }
        override fun onVirtualizerEnabledChange(enabled: Boolean) { calls += "virtualizer:$enabled" }
        override fun onVirtualizerStrengthChange(strength: Int) { calls += "virtualizerStrength:$strength" }
        override fun onLoudnessEnhancerEnabledChange(enabled: Boolean) { calls += "loudness:$enabled" }
        override fun onLoudnessEnhancerStrengthChange(strength: Int) { calls += "loudnessStrength:$strength" }
    }

    private fun headphone(id: String, eq: HeadphoneEqState) = HeadphoneProfile(id, "TYGR", eq, 1, 1)

    private fun profileState(eq: HeadphoneEqState, audio: AudioStatus, selected: Boolean = true): HeadphoneProfileScreenState {
        val item = HeadphoneProfileUiItem(headphone("tygr", eq), isSelected = selected)
        val profiles = HeadphoneProfilesUiState(profiles = listOf(item), selected = item.takeIf { selected })
        return HeadphoneProfileScreenState.from("tygr", profiles, EqualizerUiState(), audio, SafetyVolumeState())
    }

    @Test
    fun `a profile whose EQ is off says it once - item 4`() {
        setScreen { HeadphoneProfileScreenContent(profileState(HeadphoneEqState(enabled = false), purityOn), HpRecorder()) }
        compose.onAllNodesWithText(s(R.string.profile_audio_saved_eq_off)).assertCountEquals(1)
        // What B19 printed: the saved mode, and the effect, both saying "off".
        val before = s(R.string.profile_audio_saved_eq_summary, s(R.string.headphone_eq_mode_off), s(R.string.headphone_eq_off))
        compose.onAllNodesWithText(before).assertCountEquals(0)
        // Not even the "off" label of the effect is printed beside it.
        compose.onAllNodesWithText(s(R.string.headphone_eq_off), substring = true).assertCountEquals(0)
    }

    @Test
    fun `a profile whose EQ is on says its mode and what the output does with it`() {
        val on = HeadphoneEqState(enabled = true, mode = HeadphoneEqMode.GRAPHIC_10)
        setScreen { HeadphoneProfileScreenContent(profileState(on, purityOn), HpRecorder()) }
        compose.onNodeWithText(
            s(R.string.profile_audio_saved_eq_summary, s(R.string.headphone_eq_mode_10), s(R.string.eq_output_pure_direct))
        ).assertExists()
        compose.onAllNodesWithText(s(R.string.headphone_eq_preset_label))[0].assertExists()
    }

    @Test
    fun `the profile page's rows open the EQ and Safety Max, and select the profile when it is not the selected one`() {
        val actions = HpRecorder()
        setScreen { HeadphoneProfileScreenContent(profileState(HeadphoneEqState(), AudioStatus()), actions) }
        compose.onNodeWithText(s(R.string.settings_category_equalizer_title)).performClick()
        compose.onNodeWithText(s(R.string.headphone_manage)).performClick()
        assertEquals(listOf("eq:tygr", "safety:tygr"), actions.calls)
    }

    @Test
    fun `an unselected profile offers to select itself`() {
        val actions = HpRecorder()
        setScreen { HeadphoneProfileScreenContent(profileState(HeadphoneEqState(), AudioStatus(), selected = false), actions) }
        compose.onNodeWithText(s(R.string.headphone_select)).performClick()
        assertEquals(listOf("select:tygr"), actions.calls)
    }

    @Test
    fun `a profile that is gone says so`() {
        val empty = HeadphoneProfileScreenState.from("x", HeadphoneProfilesUiState(), EqualizerUiState(), AudioStatus(), SafetyVolumeState())
        setScreen { HeadphoneProfileScreenContent(empty, HpRecorder()) }
        compose.onNodeWithText(s(R.string.headphone_profile_not_found)).assertExists()
    }

    // ── DAC profile ──────────────────────────────────────────────────────────────────────────────────

    private class DacRecorder : DacProfileScreenActions {
        val calls = mutableListOf<String>()
        override fun onBack() { calls += "back" }
        override fun onRefresh() { calls += "refresh" }
        override fun onRename(profileId: String, name: String) { calls += "rename:$profileId:$name" }
        override fun onForget(profileId: String) { calls += "forget:$profileId" }
        override fun onChangeHeadphone() { calls += "changeHeadphone" }
    }

    private fun dac() = DacProfile("melody", "k", DacIdentityScope.MODEL_SCOPED, "SNOWSKY Melody", null, null, 0x2972, 0x0126, null, 1L, 1L)

    private fun dacState(canChange: Boolean = true): DacProfileScreenState {
        val item = DacProfileUiItem(dac(), DacLiveStatus.ACTIVE)
        val hp = HeadphoneProfileUiItem(headphone("tygr", HeadphoneEqState()), true)
        return DacProfileScreenState.from(
            "melody",
            DacProfilesUiState(profiles = listOf(item), current = item, currentAdvertisedRates = listOf(44100, 48000), currentBestBitDepth = 32),
            HeadphoneProfilesUiState(profiles = listOf(hp), selected = hp, associationByDacProfileId = mapOf("melody" to "tygr")),
            canChangeHeadphone = canChange,
        )
    }

    @Test
    fun `a DAC profile shows the device, its capabilities, the remembered headphones and Change`() {
        val actions = DacRecorder()
        setScreen { DacProfileScreenContent(dacState(), actions) }
        for (id in listOf(R.string.dac_device_title, R.string.dac_usb_id, R.string.dac_capabilities, R.string.dac_advertised_rates,
            R.string.dac_advertised_depth, R.string.headphone_settings_title, R.string.presentation_batch_f_change)) {
            compose.onAllNodesWithText(s(id))[0].assertExists()
        }
        compose.onAllNodesWithText("SNOWSKY Melody")[0].assertExists()
        compose.onAllNodesWithText("TYGR")[0].assertExists()
        compose.onNodeWithText(s(R.string.presentation_batch_f_change)).performClick()
        assertEquals(listOf("refresh", "changeHeadphone"), actions.calls)
    }

    @Test
    fun `without a Change to offer the Headphones section has no action`() {
        setScreen { DacProfileScreenContent(dacState(canChange = false), DacRecorder()) }
        compose.onAllNodesWithText(s(R.string.presentation_batch_f_change)).assertCountEquals(0)
    }

    @Test
    fun `the Change of the Headphones section is at the end of its header, centred on the same line - item 5`() {
        setScreen { DacProfileScreenContent(dacState(), DacRecorder()) }
        assertHeaderActionAligned(R.string.headphone_settings_title, R.string.presentation_batch_f_change)
    }

    /**
     * The header is one row: the label on the left, the action at the right edge, both on the same centre line.
     * B19's header put the action straight after the label and top-aligned the two, so a 48 dp action hung below
     * an 11 sp label (its centre ~17 dp lower) and sat in the middle of the row.
     */
    private fun assertHeaderActionAligned(headerRes: Int, actionRes: Int) {
        val header = compose.onAllNodesWithText(s(headerRes))[0].getBoundsInRoot()
        val action = compose.onNodeWithText(s(actionRes)).getBoundsInRoot()
        val rowRight = compose.onRoot().getBoundsInRoot().right
        val headerCentre = (header.top + header.bottom).value / 2f
        val actionCentre = (action.top + action.bottom).value / 2f
        assertTrue("centres differ: header $headerCentre dp, action $actionCentre dp", kotlin.math.abs(headerCentre - actionCentre) <= 1.5f)
        // The action ends where the row ends (within the content gutter), not a label's width from its start.
        assertTrue("action ends at ${action.right}, row at $rowRight", rowRight.value - action.right.value <= 24f)
        assertTrue("action starts before the header ends?", action.left.value > header.right.value)
    }

    // ── Lists ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the known DACs list is its profiles, and opens one`() {
        val calls = mutableListOf<String>()
        val actions = object : KnownDacsActions {
            override fun onBack() { calls += "back" }
            override fun onOpenProfile(profileId: String) { calls += "open:$profileId" }
            override fun onRefresh() { calls += "refresh" }
        }
        val state = KnownDacsState(listOf(DacProfileUiItem(dac(), DacLiveStatus.CONNECTED)))
        setScreen { KnownDacsContent(state, actions) }
        compose.onNodeWithText("SNOWSKY Melody").performClick()
        assertEquals(listOf("refresh", "open:melody"), calls)
        compose.onAllNodesWithContentDescription(s(R.string.dac_status_connected))[0].assertExists()
    }

    @Test
    fun `the known headphones list is its profiles, and opens one`() {
        val calls = mutableListOf<String>()
        val actions = object : KnownHeadphonesActions {
            override fun onBack() { calls += "back" }
            override fun onOpenProfile(profileId: String) { calls += "open:$profileId" }
            override fun onCreate(name: String) { calls += "create:$name" }
        }
        val state = KnownHeadphonesState(listOf(HeadphoneProfileUiItem(headphone("tygr", HeadphoneEqState()), true)))
        setScreen { KnownHeadphonesContent(state, actions) }
        compose.onNodeWithText("TYGR").performClick()
        assertEquals(listOf("open:tygr"), calls)
        compose.onAllNodesWithText(s(R.string.headphone_new))[0].assertExists()
    }
}
