package com.lostf1sh.pixelplayeross.presentation.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.decent.usbaudio.media3.DirectSignalMode
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.dac.DacIdentityScope
import com.lostf1sh.pixelplayeross.data.dac.DacProfile
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqLabel
import com.lostf1sh.pixelplayeross.data.dac.fiio.PurityDacEqNote
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
import com.lostf1sh.pixelplayeross.presentation.B61Renders
import com.lostf1sh.pixelplayeross.presentation.RenderChecks
import com.lostf1sh.pixelplayeross.presentation.viewmodel.AudioStatus
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DacLiveStatus
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DacProfileUiItem
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DacProfilesUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DeviceControlUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.HeadphoneProfileUiItem
import com.lostf1sh.pixelplayeross.presentation.viewmodel.HeadphoneProfilesUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.SettingsUiState
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B61 item 3 (owner 11-07: "the point of Purity is that it locks everything"): what the Audio page says now that Purity closes the DAC's EQ, in English, Spanish and Swedish.
 *
 * With Purity on the DAC EQ row reads **off, closed by Purity** and the Purity row says its usual "untouched signal" (it no longer says the DAC's EQ is shaping the sound); "Use the DAC's EQ"
 * is not offered while Purity holds the EQ closed or while the EQ is on; the "Off" that only repeated the row's own word under Polylute EQ and ReplayGain is gone; and the share-the-DAC row sits
 * under the Purity switch it refers to. The PNGs are of made-up data (the page at the HONOR's 360 dp).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h2400dp-xxhdpi")
open class B61AudioPurityRenderTest {
    @get:Rule
    val compose = createComposeRule()

    protected open val tag: String = "en"
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun s(id: Int) = context.getString(id)

    private class Quiet : AudioSettingsActions {
        override fun onPureDirectChange(value: Boolean) {}
        override fun onConfirmPureDirectRise() {}
        override fun onDismissPureDirectRise() {}
        override fun onResetDigitalGain() {}
        override fun onReplayGainEnabledChange(value: Boolean) {}
        override fun onReplayGainUseAlbumGainChange(value: Boolean) {}
        override fun onCrossfadeEnabledChange(value: Boolean) {}
        override fun onSmartCrossfadeEnabledChange(value: Boolean) {}
        override fun onPlaybackSpeedChange(value: Float) {}
        override fun onRandomModeChange(value: RandomMode) {}
        override fun onPersistentShuffleEnabledChange(value: Boolean) {}
        override fun onOpenDacProfile(profileId: String) {}
        override fun onOpenKnownDacs() {}
        override fun onOpenHeadphoneProfile(profileId: String) {}
        override fun onOpenKnownHeadphones() {}
        override fun onOpenEqualizer(profileId: String) {}
        override fun onOpenSafetyMax(profileId: String) {}
        override fun onOpenGlobalTransitionEditor() {}
        override fun onShowFixedOutputNotice() {}
        override fun onDacShareChange(value: Boolean) {}
        override fun onAskUsbPermission() {}
    }

    private fun route(mode: DirectSignalMode) = AudioRouteState(
        actualBackend = AudioBackend.USB_DIRECT, transition = AudioRouteTransition.DIRECT_ACTIVE, signalMode = mode, usbDacPresent = true,
        reason = if (mode == DirectSignalMode.PRISTINE) AudioRouteReason.DIRECT_PRISTINE else AudioRouteReason.DIRECT_PROCESSED,
    )

    private val dac = DacProfileUiItem(
        DacProfile(
            profileId = "dac-1", deviceIdentityKey = "k", identityScope = DacIdentityScope.MODEL_SCOPED, displayName = "SNOWSKY Melody",
            detectedManufacturer = "FIIO", vendorId = 1, productId = 2, createdAtEpochMs = 1, lastSeenEpochMs = 1,
        ),
        DacLiveStatus.ACTIVE,
    )

    private fun headphone(eqOn: Boolean) = HeadphoneProfileUiItem(
        HeadphoneProfile("hp-1", "Blue 2", HeadphoneEqState(enabled = eqOn, mode = HeadphoneEqMode.GRAPHIC_10, presetName = "flat"), 1, 1),
        isSelected = true, associatedDacName = "SNOWSKY Melody",
    )

    private fun page(audio: AudioStatus, eqOn: Boolean = false, replayGain: Boolean = false) {
        val state = AudioSettingsState.from(
            ui = SettingsUiState(replayGainEnabled = replayGain),
            playbackSpeed = 1.0f,
            audio = audio,
            dacs = DacProfilesUiState(profiles = listOf(dac), current = dac),
            headphones = HeadphoneProfilesUiState(profiles = listOf(headphone(eqOn)), selected = headphone(eqOn)),
            device = DeviceControlUiState(audio = audio),
            transition = TransitionSettings(mode = TransitionMode.OVERLAP, durationMs = 4000),
        )
        compose.setContent {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = DapSpacing.Gutter)) {
                AudioSettingsScreen(state, Quiet(), mountInputBridge = false)
            }
        }
    }

    private fun top(text: String): Float = compose.onAllNodesWithText(text, substring = true)[0].getUnclippedBoundsInRoot().top.value

    /** Purity on, the DAC's EQ closed by it. */
    private val purityOn = AudioStatus(
        purityPreference = true, route = route(DirectSignalMode.PRISTINE), sinkDirectActive = true, dacEq = DacEqLabel.OFF, dacEqWritable = true,
    )

    /** Purity off, the DAC's EQ on (the owner's USER 3). */
    private val purityOffEqOn = AudioStatus(
        purityPreference = false, route = route(DirectSignalMode.PROCESSED), sinkDirectActive = true, dacEq = DacEqLabel.ON, dacEqWritable = true,
    )

    /** Purity off, the DAC's EQ off: the action that turns it on is offered. */
    private val purityOffEqOff = AudioStatus(
        purityPreference = false, route = route(DirectSignalMode.PROCESSED), sinkDirectActive = true, dacEq = DacEqLabel.OFF, dacEqWritable = true,
    )

    @Test
    fun `Purity on - the DAC EQ row says off closed by Purity, the Purity row has no contradiction, nothing to use`() {
        page(purityOn)
        compose.onAllNodesWithText(s(R.string.audio_dac_eq_closed_by_purity), substring = true).assertCountEquals(1)
        compose.onAllNodesWithText(s(R.string.pristine_status_active), substring = true).assertCountEquals(1)
        compose.onAllNodesWithText(s(R.string.audio_dac_eq_use), substring = true).assertCountEquals(0)
        compose.onAllNodesWithText(s(R.string.audio_dac_eq_on), substring = true).assertCountEquals(0)
        compose.onAllNodesWithText(s(R.string.pristine_status_dac_eq_on), substring = true).assertCountEquals(0)
        RenderChecks.assertInsideWindow(compose, "Purity on $tag")
        RenderChecks.assertNoCutText(compose, "Purity on $tag")
        B61Renders.save(compose, "audio-purity-on-$tag")
    }

    @Test
    fun `Purity off with the DAC EQ on - the EQ says on and the action that would turn it on is not offered`() {
        page(purityOffEqOn)
        compose.onAllNodesWithText(s(R.string.audio_dac_eq_on), substring = true).assertCountEquals(1)
        compose.onAllNodesWithText(s(R.string.audio_dac_eq_use), substring = true).assertCountEquals(0)
        compose.onAllNodesWithText(s(R.string.audio_dac_eq_closed_by_purity), substring = true).assertCountEquals(0)
        RenderChecks.assertInsideWindow(compose, "Purity off EQ on $tag")
        RenderChecks.assertNoCutText(compose, "Purity off EQ on $tag")
        B61Renders.save(compose, "audio-purity-off-eq-on-$tag")
    }

    @Test
    fun `Purity off with the DAC EQ off - the action is offered, and no row repeats its own Off`() {
        page(purityOffEqOff, eqOn = false, replayGain = false)
        compose.onAllNodesWithText(s(R.string.audio_dac_eq_use), substring = true).assertCountEquals(1)
        compose.onAllNodesWithText(s(R.string.audio_dac_eq_closed_by_purity), substring = true).assertCountEquals(0)
        // the standalone "Off" status line is gone under Polylute EQ and under ReplayGain: no "Off" node sits between the Polylute EQ title and the DAC EQ title, nor between ReplayGain's title and the limiter's
        val off = s(R.string.audio_status_off)
        val offTops = compose.onAllNodesWithText(off, substring = false).fetchSemanticsNodes().indices.map {
            compose.onAllNodesWithText(off, substring = false)[it].getUnclippedBoundsInRoot().top.value
        }
        val eqTitle = top(s(R.string.audio_eq_title))
        val dacEqTitle = top(s(R.string.audio_dac_eq_title))
        val rgTitle = top(s(R.string.audio_replaygain_title))
        val limiterTitle = top(s(R.string.audio_limiter_title))
        assertThat(offTops.filter { it > eqTitle && it < dacEqTitle }).isEmpty()
        assertThat(offTops.filter { it > rgTitle && it < limiterTitle }).isEmpty()
        RenderChecks.assertInsideWindow(compose, "Purity off EQ off $tag")
        RenderChecks.assertNoCutText(compose, "Purity off EQ off $tag")
        B61Renders.save(compose, "audio-purity-off-eq-off-$tag")
    }

    @Test
    fun `the share-the-DAC row sits under the Purity switch and the page reads in that order`() {
        page(purityOn)
        val pure = top(s(R.string.pristine_status_active))
        val share = top(s(R.string.audio_dac_share_title))
        val sound = top(s(R.string.audio_eq_title))
        val moreDacs = top(s(R.string.dac_more))
        assertThat(moreDacs).isLessThan(pure)          // the DACs, then Purity
        assertThat(pure).isLessThan(share)             // Purity says what it does, then the row that depends on it
        assertThat(share).isLessThan(sound)            // and both come before the Sound section
    }

    @Test
    fun `a note under the Purity switch says what did not go as asked`() {
        page(purityOn.copy(purityDacEqNote = PurityDacEqNote.REOPEN_WHEN_CONNECTED))
        compose.onAllNodesWithText(s(R.string.pristine_dac_eq_note_reopen_waits), substring = true).assertCountEquals(1)
        RenderChecks.assertInsideWindow(compose, "Purity note $tag")
        B61Renders.save(compose, "audio-purity-note-$tag")
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "es-w360dp-h2400dp-xxhdpi")
class B61AudioPurityRenderEsTest : B61AudioPurityRenderTest() { override val tag = "es" }

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "sv-w360dp-h2400dp-xxhdpi")
class B61AudioPurityRenderSvTest : B61AudioPurityRenderTest() { override val tag = "sv" }
