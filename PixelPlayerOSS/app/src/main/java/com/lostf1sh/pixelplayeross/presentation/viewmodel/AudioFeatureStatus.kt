package com.lostf1sh.pixelplayeross.presentation.viewmodel

import androidx.annotation.StringRes
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqLabel
import androidx.compose.runtime.Immutable
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.presentation.components.PristineFrozenFeature

/**
 * B22: the one status line.
 *
 * Every row that says something about the sound (the equalizer, ReplayGain, Safety Max, crossfade, speed) ends in
 * the same short line, drawn by the same component, and every one of them is answered here from [AudioStatus]:
 * no screen writes a sentence of its own. The words are the design's:
 *
 * | [FeatureStatusKind] | Drawn as | When |
 * |---|---|---|
 * | [APPLIED][FeatureStatusKind.APPLIED] | `● Se aplica` | saved on, and it reaches the signal |
 * | [FROZEN][FeatureStatusKind.FROZEN] | `○ Congelado · Pure Direct` | the lock is in force and the setting is on, or the control cannot be changed meanwhile |
 * | [OFF][FeatureStatusKind.OFF] | `Desactivado` | saved off. It wins over "frozen": there is nothing to freeze |
 * | [BYPASSED][FeatureStatusKind.BYPASSED] | `○ Omitido · mantenimiento` | a Maintenance override has the DSP off |
 * | [OTHER_PROFILE][FeatureStatusKind.OTHER_PROFILE] | `○ Otros auriculares en uso` | the profile is not the one the DSP follows |
 *
 * [FeatureStatus.androidRoute] is a tag that follows an applied word: it works, and it plays through the Android
 * route instead of USB Direct (`alt_route Ruta Android`).
 *
 * Pure: a value and a string id, so the four audio states can be asserted without a composition.
 */
enum class FeatureStatusKind { APPLIED, FROZEN, OFF, BYPASSED, OTHER_PROFILE }

@Immutable
data class FeatureStatus(
    val kind: FeatureStatusKind,
    @StringRes val textRes: Int,
    /** Applied, and through the Android route (crossfade, speed, an effect with a USB DAC connected). */
    val androidRoute: Boolean = false,
    /** B41: the one value the sentence names (the DAC's name); null keeps the line's usual argument, the lock's name. */
    val arg: String? = null,
) {
    companion object {
        val Applied = FeatureStatus(FeatureStatusKind.APPLIED, R.string.audio_status_applied)
        val AppliedViaAndroid = FeatureStatus(FeatureStatusKind.APPLIED, R.string.audio_status_applied, androidRoute = true)
        val Frozen = FeatureStatus(FeatureStatusKind.FROZEN, R.string.audio_status_frozen)
        val Off = FeatureStatus(FeatureStatusKind.OFF, R.string.audio_status_off)
        val BypassedByMaintenance = FeatureStatus(FeatureStatusKind.BYPASSED, R.string.audio_status_bypassed_maintenance)
        val OtherProfile = FeatureStatus(FeatureStatusKind.OTHER_PROFILE, R.string.audio_status_other_profile)

        /** B40-F8: on, and the stream in play is on USB Direct, where the limiter is not (the Android route's chain does not run there). */
        val LimiterAndroidOnly = FeatureStatus(FeatureStatusKind.OFF, R.string.audio_status_limiter_android_only)

        /** B40-F8: on, and the stream in play was configured before it was: it joins the signal at the next seek or song. */
        val LimiterNextSong = FeatureStatus(FeatureStatusKind.OFF, R.string.audio_status_limiter_next_song)
    }
}

/** What "Sin cortes" says: songs join with no gap, where, or why not. */
enum class GaplessStatus(@StringRes val textRes: Int, val applied: Boolean) {
    /** Crossfade is in effect: the songs overlap instead of joining. */
    CROSSFADE(R.string.audio_gapless_crossfade, applied = false),

    /** Joined exactly, through USB Direct. */
    YES_DIRECT(R.string.audio_gapless_yes_direct, applied = true),

    /** Joined exactly, through whatever route is carrying the stream. */
    YES(R.string.audio_gapless_yes, applied = true),
}

/** The equalizer, for the profile the DSP follows (`isProfileSelected`) or for another one. */
fun AudioStatus.equalizerStatus(isProfileSelected: Boolean, enabled: Boolean): FeatureStatus = when {
    !isProfileSelected -> FeatureStatus.OtherProfile
    !enabled -> FeatureStatus.Off
    maintenanceDspBypass -> FeatureStatus.BypassedByMaintenance
    purityInForce -> FeatureStatus.Frozen
    else -> FeatureStatus.Applied
}

/**
 * B40-F8: Resonance's peak limiter. Off wins; then the Maintenance kill, then the lock (a frozen setting is kept but does not reach the
 * signal); then the route: on USB Direct the Android chain does not run, so it says so instead of "applied"; on the Android route it is
 * applied when the stream in play has it, and "from the next song" when the switch was turned on after the stream was configured.
 */
fun AudioStatus.limiterStatus(enabled: Boolean): FeatureStatus = when {
    !enabled -> FeatureStatus.Off
    maintenanceDspBypass -> FeatureStatus.BypassedByMaintenance
    purityInForce -> FeatureStatus.Frozen
    route.isDirectActive -> FeatureStatus.LimiterAndroidOnly
    limiterRunning -> FeatureStatus.Applied
    else -> FeatureStatus.LimiterNextSong
}

/** ReplayGain. Same order as the equalizer: off wins, then the override, then the lock. */
fun AudioStatus.replayGainStatus(enabled: Boolean): FeatureStatus = when {
    !enabled -> FeatureStatus.Off
    maintenanceDspBypass -> FeatureStatus.BypassedByMaintenance
    purityInForce -> FeatureStatus.Frozen
    else -> FeatureStatus.Applied
}

/**
 * Crossfade. With the lock in force the control cannot be changed, so it reads "frozen" whatever was saved; otherwise
 * saved off is off, and saved on is applied, with the Android-route tag when a USB DAC is connected.
 */
fun AudioStatus.crossfadeStatus(enabled: Boolean): FeatureStatus = when {
    purityInForce -> FeatureStatus.Frozen
    !enabled -> FeatureStatus.Off
    androidRouteNote(PristineFrozenFeature.CROSSFADE) != null -> FeatureStatus.AppliedViaAndroid
    else -> FeatureStatus.Applied
}

/**
 * Playback speed. The lock freezes the control; at 1.0× there is nothing to say (null); any other speed is applied,
 * with the Android-route tag when a USB DAC is connected.
 */
fun AudioStatus.speedStatus(speed: Float): FeatureStatus? = when {
    purityInForce -> FeatureStatus.Frozen
    speed == 1.0f -> null
    androidRouteNote(PristineFrozenFeature.SPEED) != null -> FeatureStatus.AppliedViaAndroid
    else -> FeatureStatus.Applied
}

/**
 * The note that says a feature that is **not in use** would leave USB Direct if it were, shown under crossfade and
 * speed with a DAC connected and the lock off. Null when the tag already says it (the feature is on) or there is
 * nothing to say.
 */
fun AudioStatus.androidRouteHint(feature: PristineFrozenFeature, featureInUse: Boolean): Int? =
    androidRouteNote(feature)?.takeIf { !featureInUse }

/** "Sin cortes": a status, never a control. */
fun AudioStatus.gaplessStatus(crossfadeEnabled: Boolean): GaplessStatus = when {
    crossfadeEnabled && !purityInForce -> GaplessStatus.CROSSFADE
    directActive -> GaplessStatus.YES_DIRECT
    else -> GaplessStatus.YES
}

/** Safety Max, as one status word for the row that opens its page. */
fun safetyMaxStatus(state: SafetyMaxOutputState): FeatureStatus = when (state) {
    SafetyMaxOutputState.ACTIVE -> FeatureStatus.Applied
    SafetyMaxOutputState.ACTIVE_RESCALED ->
        FeatureStatus(FeatureStatusKind.APPLIED, R.string.audio_status_safety_rescaled)
    SafetyMaxOutputState.TEMPORARY_OVERRIDE ->
        FeatureStatus(FeatureStatusKind.OFF, R.string.audio_status_safety_override)
    SafetyMaxOutputState.NOT_CALIBRATED ->
        FeatureStatus(FeatureStatusKind.OFF, R.string.audio_status_safety_not_set)
    SafetyMaxOutputState.UNAVAILABLE ->
        FeatureStatus(FeatureStatusKind.BYPASSED, R.string.audio_status_safety_unavailable)
    SafetyMaxOutputState.OFF -> FeatureStatus.Off
    SafetyMaxOutputState.PROFILE_NOT_SELECTED -> FeatureStatus.OtherProfile
}

/**
 * B39-A6: what the DAC's own EQ is doing, in the same status line the app's EQ uses, so the two read side by side. Null when the DAC
 * has no EQ the app can ask about (no DAC, or one outside the FiiO/Snowsky table): there is then nothing to say about it.
 *
 * B41: the three quiet-or-not answers are told apart, because they mean different things to the listener: **off** (the DAC's own "Close
 * EQ": its real state, said with the DAC's name), **flat** (on, every band at 0 dB) and **could not be read** (nothing is known).
 * "Off or flat" read as the last one.
 *
 * @param dacName what the sentence calls the DAC.
 */
fun AudioStatus.dacEqStatus(dacName: String): FeatureStatus? = when (dacEq) {
    DacEqLabel.NOT_APPLICABLE -> null
    DacEqLabel.ON -> FeatureStatus(FeatureStatusKind.APPLIED, R.string.audio_dac_eq_on)
    DacEqLabel.OFF -> FeatureStatus(FeatureStatusKind.OFF, R.string.audio_dac_eq_closed, arg = dacName)
    DacEqLabel.FLAT -> FeatureStatus(FeatureStatusKind.OFF, R.string.audio_dac_eq_flat)
    DacEqLabel.UNKNOWN -> FeatureStatus(FeatureStatusKind.BYPASSED, R.string.audio_dac_eq_unknown)
}
