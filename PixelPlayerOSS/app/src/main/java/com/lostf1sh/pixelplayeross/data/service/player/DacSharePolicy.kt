package com.lostf1sh.pixelplayeross.data.service.player

import com.lostf1sh.pixelplayeross.dap.mode.EffectiveProductMode

/**
 * B68 (the owner's option D): **who may hand the DAC to other apps**, in one place. [DacShareController.active] (when it lets go) and the
 * route policy's `AudioRouteFacts.dacShareAllowed` (whether a released DAC turns into the Android route) both read this, so the two layers
 * that keep Purity on the DAC at a DAP level cannot disagree about it: either one alone is enough.
 *
 * The rule:
 *  - the listener's switch ("Share the DAC with other apps") is off: never;
 *  - [EffectiveProductMode.APP] (Polylute is not the home app, a phone with its own launcher): **Purity and Resonance** both share;
 *  - [EffectiveProductMode.LAUNCHER] (Polylute is the home app, other apps can still be opened): Resonance shares, **Purity never**;
 *  - [EffectiveProductMode.PINNED] and `DEDICATED_*`: **never** (owner 11-23: no other app can be opened there, so there is nobody to
 *    hand the DAC to; the switch is not even shown);
 *  - the mode is not known yet (null): taken as LAUNCHER, so an unknown never lets Purity go.
 *
 * "Resonance" here is **the listener's Purity choice being off** (the Purity lock not in force: [com.lostf1sh.pixelplayeross.data.volume.PristineModePolicy.lockInForce],
 * fed by the same `pristineLockFlow` the Audio page shows), not "the app's DSP is in the signal". Until B68 it was the second, so Purity off
 * with nothing to process (the owner's case: their Resonance is the Melody's own EQ) counted as Purity and never shared (B68-audit H1).
 *
 * Releasing only ever happens while Polylute is not playing (paused, idle or without audio focus), and play takes the DAC back with the
 * ordinary Direct claim, so this decides nothing about the signal while Polylute plays: Pristine stays bit-perfect.
 */
object DacSharePolicy {
    /**
     * The switch as it stands. The listener's own [choice] wins; with nothing stored (null) it follows Polylute's role: **on in
     * [EffectiveProductMode.APP]** (a phone where Polylute is one app among others: YouTube, a voice note, a video must reach the DAC
     * once Polylute pauses) and **off at a DAP level** or while the mode is not known (the HONOR in Dedicated keeps the DAC, as before).
     * Owner 11-22. The Audio page shows this same value, so the switch never says one thing while the engine does another.
     */
    fun switchOn(choice: Boolean?, effectiveMode: EffectiveProductMode?): Boolean = choice ?: (effectiveMode == EffectiveProductMode.APP)

    /** The same, with the mode read only when no choice is stored (asking Android for the mode is a few binder calls). */
    inline fun switchOn(choice: Boolean?, effectiveMode: () -> EffectiveProductMode?): Boolean =
        choice ?: (effectiveMode() == EffectiveProductMode.APP)

    /** Whether the DAC may be handed to other apps. Pure. */
    fun shareAllowed(switchOn: Boolean, purityInForce: Boolean, effectiveMode: EffectiveProductMode?): Boolean =
        switchOn && modeAllows(purityInForce, effectiveMode)

    /**
     * The same rule, with the mode read **only when it decides** (the switch on): asking Android for the product mode is a few binder
     * calls, and the default listener (switch off) never needs it.
     */
    inline fun shareAllowed(switchOn: Boolean, purityInForce: Boolean, effectiveMode: () -> EffectiveProductMode?): Boolean =
        switchOn && modeAllows(purityInForce, effectiveMode())

    /** What the mode allows once the switch is on. */
    @PublishedApi
    internal fun modeAllows(purityInForce: Boolean, effectiveMode: EffectiveProductMode?): Boolean = when (effectiveMode) {
        EffectiveProductMode.APP -> true
        EffectiveProductMode.PINNED, EffectiveProductMode.DEDICATED_SETUP_REQUIRED, EffectiveProductMode.DEDICATED_ACTIVE -> false
        EffectiveProductMode.LAUNCHER, null -> !purityInForce
    }

    /** What the share switch's subtitle on the Audio page says: what the engine will actually do, for this mode and Purity choice. */
    enum class Subtitle {
        /** Polylute is a normal app: other apps can use the DAC while it is paused, in Purity and in Resonance. */
        APP_MODE,

        /** A DAP level with Purity on: Purity keeps the DAC. */
        DAP_PURITY_KEEPS,

        /** Launcher level in Resonance: shared while paused, as before B68. */
        DAP_RESONANCE,

        /** Pinned or Dedicated: no other app can be opened, so the switch is not shown at all (owner 11-23). */
        HIDDEN,
    }

    /** [purityPreference] is the Purity switch as the Audio page shows it; a null mode reads as a DAP level, like [shareAllowed]. */
    fun subtitle(purityPreference: Boolean, effectiveMode: EffectiveProductMode?): Subtitle = when {
        effectiveMode == EffectiveProductMode.APP -> Subtitle.APP_MODE
        effectiveMode == EffectiveProductMode.PINNED || effectiveMode == EffectiveProductMode.DEDICATED_SETUP_REQUIRED ||
            effectiveMode == EffectiveProductMode.DEDICATED_ACTIVE -> Subtitle.HIDDEN
        purityPreference -> Subtitle.DAP_PURITY_KEEPS
        else -> Subtitle.DAP_RESONANCE
    }
}
