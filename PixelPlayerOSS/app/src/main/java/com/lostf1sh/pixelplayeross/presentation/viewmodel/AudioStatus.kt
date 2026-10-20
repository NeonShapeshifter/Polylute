package com.lostf1sh.pixelplayeross.presentation.viewmodel

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import com.decent.usbaudio.media3.DirectSignalMode
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqLabel
import com.lostf1sh.pixelplayeross.data.dac.fiio.ResonanceSource
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteState
import com.lostf1sh.pixelplayeross.data.volume.PristineModePolicy
import com.lostf1sh.pixelplayeross.presentation.components.PristineFrozenFeature
import com.lostf1sh.pixelplayeross.presentation.components.PristineLockCopy

/**
 * What is applied to the sound right now: one account, read by every screen that says anything about it.
 *
 * Until B21 the same facts were derived five times: the Pure Direct notices read them through
 * `PristineLockViewModel`, the Settings root and the volume block through `DeviceControlUiState`, the EQ
 * and the headphone profile through `EqualizerOutputContext`, the Details sheet through
 * `TrackDetailsViewModel`, and the lock screen's output line through `DeviceControlUiState` again. They could
 * not drift apart only because nobody had changed one of them yet.
 *
 * This is **pure**: it holds what the engine and the preferences already publish and answers questions about
 * it with the words (resource ids) the screens already used, so moving a screen onto it changes no text. It
 * decides nothing about volume or DSP; the policies in `PristineModePolicy` and the router still do.
 *
 * What is *not* here, on purpose: which Android output is in use (Bluetooth, wired, speaker; that is read from
 * the platform by [DapAudioRouteKind]) and the per-profile EQ curve (the profile owns it). The two questions
 * that depend on a profile, "is this profile the selected one" and "is its EQ on", are asked of [equalizer].
 *
 * @property purityPreference the Pure Direct switch (`pristineLockFlow`): a preference, not a fact about the stream.
 * @property route the engine's own route, as published (`DualPlayerEngine.audioRouteState`).
 * @property sinkDirectActive the USB Direct sink says it is carrying the stream (`usbAudioDiagnostics.state`).
 * @property maintenanceDspBypass a Maintenance override has the DSP graph off.
 * @property dacEq what the DAC's own EQ is doing, as far as the app could read it (B30): Purity needs it off or flat.
 */
@Immutable
data class AudioStatus(
    val purityPreference: Boolean = false,
    val route: AudioRouteState = AudioRouteState(),
    val sinkDirectActive: Boolean = false,
    val maintenanceDspBypass: Boolean = false,
    val dacEq: DacEqLabel = DacEqLabel.NOT_APPLICABLE,
) {
    /** Whether the router found a USB DAC when it last decided; null before it has looked. */
    val dacPresent: Boolean? get() = route.usbDacPresent

    /** A USB DAC is connected. */
    val dacConnected: Boolean get() = route.usbDacPresent == true

    /** USB Direct is carrying the stream, in any signal mode. */
    val directActive: Boolean get() = route.isDirectActive

    /**
     * USB Direct is carrying the stream with the application's DSP active. **It means the app's DSP and only that**: the ledger's
     * RESONANCE (Stats) and the equalizer's "bypassed" logic read it, and B30 did not change what it says. The label a screen
     * shows is [resonanceSource] / [purityHolds].
     */
    val resonance: Boolean get() = route.isDirectActive && route.signalMode == DirectSignalMode.PROCESSED

    /** USB Direct is carrying the stream and the app does nothing to the PCM (the DAC's EQ is a separate question: [dacEq]). */
    val directPristine: Boolean get() = route.isDirectActive && route.signalMode == DirectSignalMode.PRISTINE

    /** USB Direct, and the DAC's own EQ is on: the sound is shaped, inside the DAC. */
    val dacEqShaping: Boolean get() = route.isDirectActive && dacEq == DacEqLabel.ON

    /**
     * "Purity" may be shown (B30): USB Direct, the app's DSP off **and** the DAC's EQ known off or flat. A DAC whose EQ could not
     * be read does not get the word; neither does one whose EQ is on.
     */
    val purityHolds: Boolean get() = directPristine && dacEq.allowsPurity

    /** Where the shaping of a Resonance stream comes from, or null when nothing shapes it on Direct (or Direct is not carrying it). */
    val resonanceSource: ResonanceSource?
        get() = when {
            resonance && dacEqShaping -> ResonanceSource.BOTH
            resonance -> ResonanceSource.APP_DSP
            dacEqShaping -> ResonanceSource.DAC_EQ
            else -> null
        }

    /** USB Direct, the app leaves the PCM alone, and the app cannot say what the DAC's EQ does: no Purity, no Resonance. */
    val dacEqUnknownOnDirect: Boolean get() = directPristine && dacEq == DacEqLabel.UNKNOWN

    /**
     * Whether the lock is in force: the preference is on **and** a USB DAC is connected (B16-G4). Without a DAC
     * it is a preference waiting for one, and what it would freeze applies.
     */
    val purityInForce: Boolean get() = PristineModePolicy.lockInForce(purityPreference, route.usbDacPresent)

    /** What the lock's label may claim. See [PristineModePolicy.status]. */
    val purity: PristineModePolicy.Status
        get() = PristineModePolicy.statusWithDacEq(
            purityPreference,
            directActive = route.isDirectActive && sinkDirectActive,
            signalMode = route.signalMode,
            dacEq = dacEq,
        )

    /** Why the stream is not on Direct, when a *listening choice* (crossfade, speed, an effect) is the reason. */
    @get:StringRes
    val signalBlockerReason: Int? get() = PristineLockCopy.signalBlockerReason(route.reason)

    /**
     * The one sentence under the Pure Direct switch (B22: the design's four, and the ones between them):
     * - lock on, no DAC: "waiting": nothing is frozen yet, and it says what a DAC will freeze;
     * - lock on, DAC, stream untouched: "active, untouched signal";
     * - lock on, DAC, the stream is not on Direct (yet): "only in USB Direct", and the route's own reason;
     * - lock on, on Direct, something still modifies the signal: said, never reported as active;
     * - lock on, on Direct, the app leaves the PCM alone but the DAC's EQ is on (or could not be read): said, never "untouched";
     * - lock off: "off", and when it is true, why: Resonance (USB Direct with the app's DSP), or a listening choice
     *   (crossfade, speed, an effect) that keeps the stream on the Android route with a DAC connected.
     */
    val purityLine: PurityLine
        get() = when (purity) {
            PristineModePolicy.Status.OFF -> PurityLine(
                R.string.pristine_status_off,
                when (resonanceSource) {
                    ResonanceSource.BOTH -> R.string.pristine_reason_resonance_both
                    ResonanceSource.APP_DSP -> R.string.pristine_reason_resonance
                    ResonanceSource.DAC_EQ -> R.string.pristine_reason_resonance_dac
                    null -> when {
                        dacConnected && !sinkDirectActive -> PristineLockCopy.signalBlockerReason(route.reason)
                        else -> null
                    }
                },
            )
            PristineModePolicy.Status.ACTIVE -> PurityLine(R.string.pristine_status_active, null)
            PristineModePolicy.Status.ONLY_IN_DIRECT ->
                if (route.usbDacPresent == false) {
                    PurityLine(R.string.pristine_status_waiting, null)
                } else {
                    PurityLine(R.string.pristine_status_only_direct, PristineLockCopy.routeReason(route.reason))
                }
            PristineModePolicy.Status.DIRECT_NOT_PRISTINE -> PurityLine(R.string.pristine_status_not_pristine, null)
            PristineModePolicy.Status.DAC_EQ_ON -> PurityLine(R.string.pristine_status_dac_eq_on, null)
            PristineModePolicy.Status.DAC_EQ_UNKNOWN -> PurityLine(R.string.pristine_status_dac_eq_unknown, null)
        }

    /**
     * The line [PristineFrozenNote] shows under a setting the lock freezes, or null when the lock is off.
     *
     * It follows the *preference*, not [purityInForce]: with the lock on and no DAC the note still says the setting
     * "will not apply once a DAC is connected" (the `_pending` wording), which is the only place that is said.
     *
     * @param assumeDirect the digital volume's row only exists on a Direct stream, so it asks for the "is not
     *   applied" wording whatever the route says at that instant.
     */
    @StringRes
    fun frozenNotice(feature: PristineFrozenFeature, assumeDirect: Boolean = false): Int? =
        if (!purityPreference) null else PristineLockCopy.notice(
            feature,
            directActive = assumeDirect || route.isDirectActive,
            dacPresent = route.usbDacPresent != false,
        )

    /**
     * The note under crossfade and speed when the lock is not holding them but a DAC is connected: they work, and they
     * take playback off USB Direct. Null for every other combination and for the features that stay on Direct.
     */
    @StringRes
    fun androidRouteNote(feature: PristineFrozenFeature): Int? =
        if (purityInForce || !dacConnected) null else PristineLockCopy.androidRouteNote(feature)

    /**
     * What the equalizer is doing, for a profile.
     *
     * @param isProfileSelected the profile asked about is the selected one (only that one's curve reaches the DSP).
     * @param enabled its saved EQ switch.
     */
    fun equalizer(isProfileSelected: Boolean, enabled: Boolean): EqualizerOutputState =
        equalizerContext(isProfileSelected).state(enabled)

    /** The same answer as a context, for the screens and tests that ask it repeatedly. */
    fun equalizerContext(isProfileSelected: Boolean): EqualizerOutputContext = EqualizerOutputContext(
        isProfileSelected = isProfileSelected,
        directPristine = directPristine,
        pureDirect = purityPreference,
        maintenanceBypass = maintenanceDspBypass,
    )
}

/** The Pure Direct switch's sentence: [statusRes], and [reasonRes] when there is something to add (`pristine_status_with_reason`). */
@Immutable
data class PurityLine(
    @StringRes val statusRes: Int,
    @StringRes val reasonRes: Int?,
)
