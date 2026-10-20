package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.decent.usbaudio.media3.DirectSignalMode
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneProfile
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneProfileState
import com.lostf1sh.pixelplayeross.data.service.player.AudioBackend
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteReason
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteState
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteTransition
import com.lostf1sh.pixelplayeross.data.volume.PristineModePolicy
import com.lostf1sh.pixelplayeross.presentation.components.PristineFrozenFeature
import com.lostf1sh.pixelplayeross.presentation.components.PristineLockCopy
import org.junit.jupiter.api.Test

/**
 * B21: the one status model, over the combinations the screens have to tell apart.
 *
 * Every expectation is a *text the screens already printed* (a string resource id taken from the same
 * `PristineLockCopy` they used), so these tests pin that moving a screen onto [AudioStatus] changed no word:
 *
 *  1. no DAC (the phone, the HONOR with nothing plugged in);
 *  2. DAC + Purity (Pure Direct on, USB Direct carrying an untouched stream);
 *  3. DAC + Resonance (Pure Direct off, USB Direct carrying the app's DSP);
 *  4. DAC + crossfade on, which the Direct route cannot carry: Android route.
 */
class AudioStatusTest {

    private fun directRoute(mode: DirectSignalMode, dac: Boolean? = true) = AudioRouteState(
        actualBackend = AudioBackend.USB_DIRECT,
        desiredBackend = AudioBackend.USB_DIRECT,
        transition = AudioRouteTransition.DIRECT_ACTIVE,
        reason = if (mode == DirectSignalMode.PRISTINE) AudioRouteReason.DIRECT_PRISTINE else AudioRouteReason.DIRECT_PROCESSED,
        usbDacPresent = dac,
        signalMode = mode,
    )

    private fun androidRoute(reason: AudioRouteReason, dac: Boolean?) = AudioRouteState(
        actualBackend = AudioBackend.ANDROID_AUDIO,
        transition = AudioRouteTransition.ANDROID_ACTIVE,
        reason = reason,
        usbDacPresent = dac,
    )

    // ── 1. no DAC ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `no DAC, Purity off - nothing is frozen, nothing is claimed`() {
        val status = AudioStatus(route = androidRoute(AudioRouteReason.NO_USB_DAC, dac = false))
        assertThat(status.dacConnected).isFalse()
        assertThat(status.purityInForce).isFalse()
        assertThat(status.purity).isEqualTo(PristineModePolicy.Status.OFF)
        assertThat(status.purityLine).isEqualTo(PurityLine(R.string.pristine_status_off, null))
        assertThat(status.resonance).isFalse()
        for (feature in PristineFrozenFeature.entries) {
            assertThat(status.frozenNotice(feature)).isNull()
            assertThat(status.androidRouteNote(feature)).isNull()
        }
        assertThat(status.equalizer(isProfileSelected = true, enabled = true)).isEqualTo(EqualizerOutputState.APPLIED)
    }

    @Test
    fun `no DAC, Purity on - the lock is a preference waiting for a DAC, and says so`() {
        val status = AudioStatus(purityPreference = true, route = androidRoute(AudioRouteReason.NO_USB_DAC, dac = false))
        assertThat(status.purityInForce).isFalse()
        assertThat(status.purity).isEqualTo(PristineModePolicy.Status.ONLY_IN_DIRECT)
        // B22: with no DAC the lock is "waiting": nothing is frozen yet, and the line says what a DAC will freeze.
        assertThat(status.purityLine).isEqualTo(PurityLine(R.string.pristine_status_waiting, null))
        // The notices are the "pending" wording: it will not apply once a DAC is connected.
        assertThat(status.frozenNotice(PristineFrozenFeature.CROSSFADE)).isEqualTo(R.string.pristine_lock_notice_crossfade_pending)
        assertThat(status.frozenNotice(PristineFrozenFeature.SPEED)).isEqualTo(R.string.pristine_lock_notice_speed_pending)
        assertThat(status.frozenNotice(PristineFrozenFeature.EQ)).isEqualTo(R.string.pristine_lock_notice_eq_pending)
        assertThat(status.frozenNotice(PristineFrozenFeature.REPLAY_GAIN)).isEqualTo(R.string.pristine_lock_notice_replaygain_pending)
        // Nothing is held, so the EQ still applies.
        assertThat(status.equalizer(true, true)).isEqualTo(EqualizerOutputState.APPLIED)
    }

    @Test
    fun `before the router has looked, the lock is taken as in force`() {
        val status = AudioStatus(purityPreference = true)
        assertThat(status.dacPresent).isNull()
        assertThat(status.purityInForce).isTrue()
        assertThat(status.dacConnected).isFalse()
    }

    // ── 2. DAC + Purity ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `DAC plus Purity - USB Direct carries an untouched stream and the frozen settings say they are kept, not applied`() {
        val status = AudioStatus(
            purityPreference = true,
            route = directRoute(DirectSignalMode.PRISTINE),
            sinkDirectActive = true,
        )
        assertThat(status.dacConnected).isTrue()
        assertThat(status.purityInForce).isTrue()
        assertThat(status.directPristine).isTrue()
        assertThat(status.resonance).isFalse()
        assertThat(status.purity).isEqualTo(PristineModePolicy.Status.ACTIVE)
        assertThat(status.purityLine).isEqualTo(PurityLine(R.string.pristine_status_active, null))
        assertThat(status.frozenNotice(PristineFrozenFeature.EQ)).isEqualTo(R.string.pristine_lock_notice_eq)
        assertThat(status.frozenNotice(PristineFrozenFeature.REPLAY_GAIN)).isEqualTo(R.string.pristine_lock_notice_replaygain)
        assertThat(status.frozenNotice(PristineFrozenFeature.CROSSFADE)).isEqualTo(R.string.pristine_lock_notice_crossfade)
        assertThat(status.frozenNotice(PristineFrozenFeature.SPEED)).isEqualTo(R.string.pristine_lock_notice_speed)
        // The lock holds crossfade and speed, so there is no Android-route note to give.
        assertThat(status.androidRouteNote(PristineFrozenFeature.CROSSFADE)).isNull()
        assertThat(status.equalizer(isProfileSelected = true, enabled = true)).isEqualTo(EqualizerOutputState.BYPASSED_PURE_DIRECT)
    }

    @Test
    fun `the lock is not reported active unless the route and the sink agree`() {
        val route = directRoute(DirectSignalMode.PRISTINE)
        assertThat(AudioStatus(purityPreference = true, route = route, sinkDirectActive = false).purity)
            .isEqualTo(PristineModePolicy.Status.ONLY_IN_DIRECT)
        assertThat(AudioStatus(purityPreference = true, route = directRoute(DirectSignalMode.PROCESSED), sinkDirectActive = true).purity)
            .isEqualTo(PristineModePolicy.Status.DIRECT_NOT_PRISTINE)
    }

    @Test
    fun `the digital row asks for the not-applied wording even while the route is changing`() {
        val status = AudioStatus(purityPreference = true, route = androidRoute(AudioRouteReason.STARTING, dac = true))
        assertThat(status.frozenNotice(PristineFrozenFeature.DIGITAL_VOLUME)).isEqualTo(R.string.pristine_lock_notice_digital_pending)
        assertThat(status.frozenNotice(PristineFrozenFeature.DIGITAL_VOLUME, assumeDirect = true)).isEqualTo(R.string.pristine_lock_notice_digital)
        assertThat(AudioStatus(purityPreference = false).frozenNotice(PristineFrozenFeature.DIGITAL_VOLUME, assumeDirect = true)).isNull()
    }

    // ── 3. DAC + Resonance ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `DAC plus Resonance - USB Direct carries the DSP, the EQ applies, and crossfade and speed warn of the Android route`() {
        val status = AudioStatus(
            purityPreference = false,
            route = directRoute(DirectSignalMode.PROCESSED),
            sinkDirectActive = true,
        )
        assertThat(status.resonance).isTrue()
        assertThat(status.directPristine).isFalse()
        assertThat(status.purityInForce).isFalse()
        assertThat(status.purity).isEqualTo(PristineModePolicy.Status.OFF)
        // B22: lock off on a DAC with the app's DSP in the stream is Resonance, and the line says so.
        assertThat(status.purityLine).isEqualTo(PurityLine(R.string.pristine_status_off, R.string.pristine_reason_resonance))
        for (feature in PristineFrozenFeature.entries) assertThat(status.frozenNotice(feature)).isNull()
        assertThat(status.equalizer(true, true)).isEqualTo(EqualizerOutputState.APPLIED)
        assertThat(status.equalizer(true, false)).isEqualTo(EqualizerOutputState.OFF)
        // With a DAC and the lock off, crossfade and speed work but take playback off USB Direct.
        assertThat(status.androidRouteNote(PristineFrozenFeature.CROSSFADE)).isEqualTo(R.string.setcat_crossfade_android_route_note)
        assertThat(status.androidRouteNote(PristineFrozenFeature.SPEED)).isEqualTo(R.string.setcat_speed_android_route_note)
        // The settings that stay on Direct have nothing to warn of.
        assertThat(status.androidRouteNote(PristineFrozenFeature.EQ)).isNull()
        assertThat(status.androidRouteNote(PristineFrozenFeature.REPLAY_GAIN)).isNull()
    }

    // ── 4. DAC + crossfade on -> Android route ──────────────────────────────────────────────────────

    @Test
    fun `DAC plus crossfade on - the route is Android, and the status names the listening choice as the reason`() {
        val status = AudioStatus(
            purityPreference = false,
            route = androidRoute(AudioRouteReason.CROSSFADE_REQUIRES_ANDROID, dac = true),
            sinkDirectActive = false,
        )
        assertThat(status.dacConnected).isTrue()
        assertThat(status.directActive).isFalse()
        assertThat(status.signalBlockerReason).isEqualTo(R.string.pristine_reason_crossfade)
        // "Off" would be read as "on Direct": with a DAC and a listening choice in the way, the line gives the reason.
        assertThat(status.purityLine).isEqualTo(PurityLine(R.string.pristine_status_off, R.string.pristine_reason_crossfade))
        assertThat(status.androidRouteNote(PristineFrozenFeature.CROSSFADE)).isEqualTo(R.string.setcat_crossfade_android_route_note)
        assertThat(status.frozenNotice(PristineFrozenFeature.CROSSFADE)).isNull()
    }

    @Test
    fun `a transport reason is not a listening choice`() {
        val status = AudioStatus(route = androidRoute(AudioRouteReason.USB_PERMISSION_REQUIRED, dac = true))
        assertThat(status.signalBlockerReason).isNull()
        assertThat(status.purityLine).isEqualTo(PurityLine(R.string.pristine_status_off, null))
    }

    // ── the EQ's own answers ────────────────────────────────────────────────────────────────────────

    @Test
    fun `the EQ is skipped for the reason that is true, in order`() {
        val direct = directRoute(DirectSignalMode.PRISTINE)
        assertThat(AudioStatus(route = direct).equalizer(isProfileSelected = false, enabled = true))
            .isEqualTo(EqualizerOutputState.PROFILE_NOT_SELECTED)
        assertThat(AudioStatus(route = direct).equalizer(isProfileSelected = true, enabled = false))
            .isEqualTo(EqualizerOutputState.OFF)
        assertThat(AudioStatus(route = direct, maintenanceDspBypass = true).equalizer(true, true))
            .isEqualTo(EqualizerOutputState.BYPASSED_MAINTENANCE)
        assertThat(AudioStatus(purityPreference = true, route = direct).equalizer(true, true))
            .isEqualTo(EqualizerOutputState.BYPASSED_PURE_DIRECT)
        assertThat(AudioStatus(purityPreference = false, route = direct).equalizer(true, true))
            .isEqualTo(EqualizerOutputState.BYPASSED_USB_DIRECT)
    }

    @Test
    fun `the equalizer's context for a profile comes from the status, whatever way it is asked`() {
        val a = HeadphoneProfile("a", "A", com.lostf1sh.pixelplayeross.data.headphones.HeadphoneEqState(enabled = true), 1, 1)
        val profiles = HeadphoneProfileState(listOf(a), "a")
        val status = AudioStatus(purityPreference = true, route = directRoute(DirectSignalMode.PRISTINE))
        assertThat(equalizerOutputContext(profiles, "a", status).state(true)).isEqualTo(status.equalizer(true, true))
        // The older entry point (route, switch, bypass) builds the same context.
        assertThat(equalizerOutputContext(profiles, "a", status.route, true, false)).isEqualTo(status.equalizerContext(true))
        // A named editor that is not the selected profile is not the one the DSP follows.
        assertThat(equalizerOutputContext(profiles, "b", status).state(true)).isEqualTo(EqualizerOutputState.PROFILE_NOT_SELECTED)
    }
}
