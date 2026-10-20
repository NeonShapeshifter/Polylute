package com.lostf1sh.pixelplayeross.data.headphones

import kotlin.math.pow

/**
 * Keeps a headphone profile's own boost from raising the effective level (AUD-025).
 *
 * A profile carries two kinds of added gain, and they are kept from raising the level in two different
 * places, because only one of them can be measured where it happens:
 *
 * - **Graphic EQ bands (up to +15 dB each).** The bands run in cascade and overlapping boosts add up in
 *   dB, so the loudest point of a curve is not its highest slider: all ten bands at +15 dB lift the
 *   cascade by +23.7 dB, all fifteen by +25.2 dB (audit A-01). The headroom is therefore taken off
 *   *inside the DSP*, ahead of the filter and of every clamp, from the cascade's own peak on the
 *   coefficients it runs at the stream's rate (`GraphicEqFilterBank.headroomGain`, B14-2). It used to be
 *   estimated here from the highest slider and composed into the player's volume, which is applied after
 *   the Android processor has already saturated (audit A-02) and which the bands' overlap defeated.
 *   Doing it in the DSP also ties it to the snapshot being rendered, a live preview included, and it is
 *   exactly neutral when the EQ is off, bypassed, or killed.
 * - **LoudnessEnhancer target (up to +10 dB).** A platform effect on the audio session: its gain is not
 *   observable from here, so it is taken at its declared target and [factor] — a gain contributor derived
 *   from the *active* profile, composed into the player's volume beside the user's level and ReplayGain —
 *   cancels it.
 *
 * It is deliberately a function of the profile, not of the switch. The first version lowered the
 * user's digital gain on each switch to a louder profile and never gave it back, which ratcheted the
 * level down on every A→B→A round trip (REV-1), was lost on a restart with the boosted profile
 * already selected (REV-2), and read a possibly stale gain on quick switches (REV-3).
 *
 * Bass boost and virtualizer are left out: their gain is platform-defined and not knowable here.
 */
internal object ProfileGainCompensation {
    /** The LoudnessEnhancer's added gain in dB (the EQ's headroom is the DSP's: see the class comment). */
    fun boostDb(state: HeadphoneEqState): Float {
        val normalized = state.normalized()
        return if (normalized.loudnessEnhancerEnabled) normalized.loudnessEnhancerStrength / 100f else 0f
    }

    /** The linear gain that cancels [boostDb]; exactly 1 for a profile that adds nothing. */
    fun factor(state: HeadphoneEqState): Float {
        val boost = boostDb(state)
        return if (boost <= 0f) 1f else 10f.pow(-boost / 20f)
    }
}
