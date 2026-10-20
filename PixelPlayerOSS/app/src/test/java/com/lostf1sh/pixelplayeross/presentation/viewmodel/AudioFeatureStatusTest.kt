package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.decent.usbaudio.media3.DirectSignalMode
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.service.player.AudioBackend
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteReason
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteState
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteTransition
import com.lostf1sh.pixelplayeross.presentation.components.PristineFrozenFeature
import org.junit.jupiter.api.Test

/**
 * B22: the one status line, over the four audio states `VarAudio.dc.html` draws. Each row of the Audio page, and the
 * equalizer, gets its words from these functions: the page prints nothing of its own about what reaches the signal.
 *
 *  1. no DAC (Pure Direct waiting);
 *  2. DAC + Pure Direct;
 *  3. DAC + Resonance;
 *  4. DAC + crossfade, which the Direct route cannot carry: Android route.
 */
class AudioFeatureStatusTest {

    private fun direct(mode: DirectSignalMode) = AudioRouteState(
        actualBackend = AudioBackend.USB_DIRECT, transition = AudioRouteTransition.DIRECT_ACTIVE,
        signalMode = mode, usbDacPresent = true,
        reason = if (mode == DirectSignalMode.PRISTINE) AudioRouteReason.DIRECT_PRISTINE else AudioRouteReason.DIRECT_PROCESSED,
    )

    private val noDac = AudioStatus(purityPreference = true, route = AudioRouteState(usbDacPresent = false, reason = AudioRouteReason.NO_USB_DAC))
    private val dacPure = AudioStatus(purityPreference = true, route = direct(DirectSignalMode.PRISTINE), sinkDirectActive = true)
    private val dacResonance = AudioStatus(purityPreference = false, route = direct(DirectSignalMode.PROCESSED), sinkDirectActive = true)
    private val dacCrossfade = AudioStatus(
        route = AudioRouteState(
            actualBackend = AudioBackend.ANDROID_AUDIO, transition = AudioRouteTransition.ANDROID_ACTIVE,
            reason = AudioRouteReason.CROSSFADE_REQUIRES_ANDROID, usbDacPresent = true,
        ),
    )

    // ── 1 · no DAC ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `state 1, no DAC - everything applies, nothing is frozen, and there is no Android route to name`() {
        assertThat(noDac.equalizerStatus(true, enabled = true)).isEqualTo(FeatureStatus.Applied)
        assertThat(noDac.replayGainStatus(true)).isEqualTo(FeatureStatus.Applied)
        assertThat(noDac.crossfadeStatus(true)).isEqualTo(FeatureStatus.Applied)
        assertThat(noDac.crossfadeStatus(false)).isEqualTo(FeatureStatus.Off)
        assertThat(noDac.speedStatus(1.0f)).isNull()
        assertThat(noDac.speedStatus(1.5f)).isEqualTo(FeatureStatus.Applied)
        assertThat(noDac.androidRouteHint(PristineFrozenFeature.CROSSFADE, featureInUse = false)).isNull()
        assertThat(noDac.gaplessStatus(false)).isEqualTo(GaplessStatus.YES)
    }

    // ── 2 · DAC + Pure Direct ───────────────────────────────────────────────────────────────────────

    @Test
    fun `state 2, DAC and Pure Direct - saved EQ and ReplayGain are frozen, crossfade and speed are frozen whatever was saved`() {
        assertThat(dacPure.equalizerStatus(true, enabled = true)).isEqualTo(FeatureStatus.Frozen)
        assertThat(dacPure.replayGainStatus(true)).isEqualTo(FeatureStatus.Frozen)
        assertThat(dacPure.crossfadeStatus(true)).isEqualTo(FeatureStatus.Frozen)
        assertThat(dacPure.crossfadeStatus(false)).isEqualTo(FeatureStatus.Frozen)
        assertThat(dacPure.speedStatus(1.0f)).isEqualTo(FeatureStatus.Frozen)
        assertThat(dacPure.speedStatus(1.5f)).isEqualTo(FeatureStatus.Frozen)
        // Songs join exactly, through USB Direct, even if crossfade was saved on: the lock holds it.
        assertThat(dacPure.gaplessStatus(true)).isEqualTo(GaplessStatus.YES_DIRECT)
    }

    @Test
    fun `off wins over frozen - a setting that is saved off has nothing to freeze`() {
        assertThat(dacPure.equalizerStatus(true, enabled = false)).isEqualTo(FeatureStatus.Off)
        assertThat(dacPure.replayGainStatus(false)).isEqualTo(FeatureStatus.Off)
    }

    @Test
    fun `a maintenance override says it, and the other profile says it before anything else`() {
        val maintenance = dacResonance.copy(maintenanceDspBypass = true)
        assertThat(maintenance.equalizerStatus(true, true)).isEqualTo(FeatureStatus.BypassedByMaintenance)
        assertThat(maintenance.replayGainStatus(true)).isEqualTo(FeatureStatus.BypassedByMaintenance)
        assertThat(maintenance.equalizerStatus(isProfileSelected = false, enabled = true)).isEqualTo(FeatureStatus.OtherProfile)
        assertThat(maintenance.equalizerStatus(true, enabled = false)).isEqualTo(FeatureStatus.Off)
    }

    // ── 3 · DAC + Resonance ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `state 3, DAC and Resonance - EQ and ReplayGain apply, and an unused crossfade or speed warns of the Android route`() {
        assertThat(dacResonance.equalizerStatus(true, enabled = true)).isEqualTo(FeatureStatus.Applied)
        assertThat(dacResonance.replayGainStatus(true)).isEqualTo(FeatureStatus.Applied)
        assertThat(dacResonance.crossfadeStatus(false)).isEqualTo(FeatureStatus.Off)
        assertThat(dacResonance.speedStatus(1.0f)).isNull()
        assertThat(dacResonance.androidRouteHint(PristineFrozenFeature.CROSSFADE, featureInUse = false))
            .isEqualTo(R.string.setcat_crossfade_android_route_note)
        assertThat(dacResonance.androidRouteHint(PristineFrozenFeature.SPEED, featureInUse = false))
            .isEqualTo(R.string.setcat_speed_android_route_note)
        // The features that stay on Direct have nothing to warn of.
        assertThat(dacResonance.androidRouteHint(PristineFrozenFeature.EQ, featureInUse = false)).isNull()
        assertThat(dacResonance.gaplessStatus(false)).isEqualTo(GaplessStatus.YES_DIRECT)
    }

    // ── 4 · DAC + crossfade ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `state 4, DAC and crossfade - it is applied through the Android route, the tag replaces the hint, and the songs overlap`() {
        assertThat(dacCrossfade.crossfadeStatus(true)).isEqualTo(FeatureStatus.AppliedViaAndroid)
        assertThat(dacCrossfade.crossfadeStatus(true).androidRoute).isTrue()
        assertThat(dacCrossfade.speedStatus(1.25f)).isEqualTo(FeatureStatus.AppliedViaAndroid)
        // A feature that is in use does not also carry the hint: the tag says it.
        assertThat(dacCrossfade.androidRouteHint(PristineFrozenFeature.CROSSFADE, featureInUse = true)).isNull()
        assertThat(dacCrossfade.gaplessStatus(true)).isEqualTo(GaplessStatus.CROSSFADE)
        assertThat(dacCrossfade.gaplessStatus(true).applied).isFalse()
    }

    // ── the words ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `each kind has the design's words`() {
        assertThat(FeatureStatus.Applied.textRes).isEqualTo(R.string.audio_status_applied)
        assertThat(FeatureStatus.Frozen.textRes).isEqualTo(R.string.audio_status_frozen)
        assertThat(FeatureStatus.Off.textRes).isEqualTo(R.string.audio_status_off)
        assertThat(FeatureStatus.BypassedByMaintenance.textRes).isEqualTo(R.string.audio_status_bypassed_maintenance)
        assertThat(FeatureStatus.OtherProfile.textRes).isEqualTo(R.string.audio_status_other_profile)
        assertThat(FeatureStatus.Frozen.kind).isEqualTo(FeatureStatusKind.FROZEN)
        assertThat(FeatureStatus.Off.kind).isEqualTo(FeatureStatusKind.OFF)
    }

    @Test
    fun `Safety Max reads its own output state`() {
        assertThat(safetyMaxStatus(SafetyMaxOutputState.ACTIVE)).isEqualTo(FeatureStatus.Applied)
        assertThat(safetyMaxStatus(SafetyMaxOutputState.OFF)).isEqualTo(FeatureStatus.Off)
        assertThat(safetyMaxStatus(SafetyMaxOutputState.PROFILE_NOT_SELECTED)).isEqualTo(FeatureStatus.OtherProfile)
        assertThat(safetyMaxStatus(SafetyMaxOutputState.NOT_CALIBRATED).textRes).isEqualTo(R.string.audio_status_safety_not_set)
        assertThat(safetyMaxStatus(SafetyMaxOutputState.UNAVAILABLE).kind).isEqualTo(FeatureStatusKind.BYPASSED)
        assertThat(safetyMaxStatus(SafetyMaxOutputState.ACTIVE_RESCALED).kind).isEqualTo(FeatureStatusKind.APPLIED)
        assertThat(safetyMaxStatus(SafetyMaxOutputState.TEMPORARY_OVERRIDE).kind).isEqualTo(FeatureStatusKind.OFF)
    }
}
