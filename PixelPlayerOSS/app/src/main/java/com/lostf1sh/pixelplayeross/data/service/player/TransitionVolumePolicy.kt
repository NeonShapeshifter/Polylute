package com.lostf1sh.pixelplayeross.data.service.player

/**
 * What the master player's volume should be when the engine abandons a transition.
 *
 * `player.volume` holds the composed gain (the user's level × ReplayGain), owned by the ReplayGain
 * processor. The engine used to write `1f` here unconditionally — on every `cancelNext`, which the
 * transition controller calls ~1.5 s into almost every track because crossfade is off by default.
 * That was an automatic jump to full level, and the processor then took it for a user gesture and
 * lost the user's setting (AUD-001/AUD-099).
 */
internal object TransitionVolumePolicy {
    /**
     * Null means "leave the volume alone": with no fade in flight nothing lowered the master
     * player, so there is nothing to restore. A fade that is cut short is put back at the composed
     * gain it started from, never at unity.
     */
    fun volumeAfterCancel(transitionWasRunning: Boolean, composedGain: Float): Float? =
        if (transitionWasRunning) composedGain.coerceIn(0f, 1f) else null
}
