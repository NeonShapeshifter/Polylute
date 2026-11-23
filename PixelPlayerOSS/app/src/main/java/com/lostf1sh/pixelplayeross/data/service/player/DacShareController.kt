package com.lostf1sh.pixelplayeross.data.service.player

import com.lostf1sh.pixelplayeross.dap.mode.EffectiveProductMode

/**
 * B39-A7: "Share the DAC with other apps", the decisions, apart from the engine that carries them out.
 *
 * B40-P1: a play the **listener** starts (the app, the notification, the lock screen, a headset or DAC button) starts as soon as the DAC is
 * back and its level is confirmed. The conservative verdict ([Host.directReady]: no ceiling and the DAC above -30 dB holds) judges
 * **automatic** resumes only (a focus gain that resumes on its own). Before, one claim waited on that verdict for every play, so a DAC at
 * -21 dB with no ceiling refused six taps in a row until the 10 s claim timeout.
 *
 * Both Polylute modes drive the DAC through the exclusive USB path, and while it holds the DAC every other app's audio (a voice note,
 * a video) goes to the phone's speaker. This gives the DAC back, only with the listener's switch on, where [DacSharePolicy] allows it
 * (B68: in app mode for Purity and Resonance; at a DAP level for Resonance only):
 *
 *  - another app taking audio focus (`LOSS`, `LOSS_TRANSIENT`) pauses Polylute, as Android always does, and releases the DAC ([onFocusLost]);
 *    a request that only asks Polylute to duck (`LOSS_TRANSIENT_CAN_DUCK`, a notification sound) never comes here: the engine ducks on the
 *    DAC and the music goes on (B68);
 *  - a paused or idle Polylute releases it too, after a short wait so a quick pause and play does not hand it back and take it again
 *    ([onPaused], [onPauseGraceElapsed]); the switch turning on, or sharing becoming allowed, while paused starts the same wait
 *    ([onSettingsChanged]);
 *  - **play** takes full audio focus first, so the other app pauses (never both at once), then takes the DAC back and resumes with the
 *    same signal mode, DSP, DAC volume and Safety Max as before: the claim is the ordinary Direct claim, whose open guard restores
 *    them ([onPlayRequested], [onRouteState]). In Purity that is `DIRECT_PRISTINE`, as if it had never let go.
 *
 * **Purity never releases at a DAP level.** [active] reads [DacSharePolicy], and so does the route policy, which ignores the released fact
 * where the policy does not allow it: two layers, either one is enough.
 *
 * It owns no clock, no thread and no platform object: the engine tells it what happened and carries out what it asks ([Host]).
 */
class DacShareController(private val host: Host) {
    interface Host {
        /** The listener's switch (off by default). */
        val enabled: Boolean

        /**
         * B68: the listener's Purity choice is in force (the lock preference with a DAC attached: `PristineModePolicy.lockInForce`). False is
         * Resonance, **whether or not the app's DSP has anything to do**.
         */
        val purityInForce: Boolean

        /** B68: what Android confirms about Polylute's role right now (null: not known, which [DacSharePolicy] reads as a DAP level). Asked only when it decides. */
        fun effectiveProductMode(): EffectiveProductMode?

        /** Hands the DAC back to Android (true) or takes it again (false): the route fact, then a new decision. */
        fun setReleased(released: Boolean)

        /** Asks for full audio focus. True when it was granted outright (the other app was told to pause). */
        fun requestFocus(): Boolean

        /** Starts playback on the master player, after the DAC is Direct again. */
        fun resume()

        /**
         * Direct is carrying the stream and the DAC's level is bounded (the handoff's own verdict): safe to start **by itself**.
         * Judges an automatic resume only.
         */
        fun directReady(): Boolean

        /**
         * Direct is carrying the stream and the DAC's level has been read back (and is inside an armed ceiling): a play the listener
         * asked for may start. Looser than [directReady] on purpose: the listener chose to play, and loud-but-known is theirs to judge.
         */
        fun userPlayReady(): Boolean

        /** A claim ended without a play the listener asked for starting ([ClaimRefusal]); the engine says so where they look, and logs it. */
        fun refused(reason: ClaimRefusal) {}

        /**
         * The hand-back needed a bus reset and the DAC's level after it could not be confirmed (B40-P4): the DAC is not announced as
         * shared, and the engine says so where the listener looks, and in one trace line.
         */
        fun releaseNotConfirmed() {}

        /** An automatic resume was held by the conservative verdict: the DAC is back, the player stays paused for the listener to start. */
        fun automaticResumeHeld() {}
    }

    /** Why a claim ended without playing. */
    enum class ClaimRefusal {
        /** Direct did not become active before the claim timeout (the claim failed). */
        DAC_NOT_BACK,

        /** Direct is active but the DAC's level was not confirmed (a write refused, or no read-back): nothing starts on a level nobody read. */
        LEVEL_UNCONFIRMED,
    }

    /** Who a start is owed to: nobody, the engine (a focus gain that resumes on its own), or the listener (a play they asked for). */
    private enum class Owed { NONE, AUTOMATIC, LISTENER }

    /** What the engine should do about a pause: nothing, or release once the pause has lasted. */
    enum class PauseOutcome { NONE, RELEASE_AFTER_GRACE }

    /** Polylute has let go of the DAC for other apps. */
    var released: Boolean = false
        private set

    /** A claim was started and waits for Direct to be active with a safe level. */
    var claiming: Boolean = false
        private set

    private var owed = Owed.NONE

    /** Whether sharing is in force now: the switch is on and [DacSharePolicy] allows it for this Purity choice and product mode. */
    val active: Boolean get() = DacSharePolicy.shareAllowed(host.enabled, host.purityInForce) { host.effectiveProductMode() }

    /**
     * Another app took audio focus; [permanent] is `AUDIOFOCUS_LOSS`, otherwise `AUDIOFOCUS_LOSS_TRANSIENT`. The engine has already paused
     * the player. Never called for `LOSS_TRANSIENT_CAN_DUCK` (B68): a duck is not a reason to stop the music or let go of the DAC.
     */
    fun onFocusLost(permanent: Boolean) {
        if (!active) return
        if (permanent) owed = Owed.NONE
        release()
    }

    /**
     * Audio focus came back (the other app ended its transient request) and the engine owes the pause a resume. True when this took the
     * resume over: the DAC has to be taken again first, and playback starts once it is Direct. False: the engine resumes as it always did.
     */
    fun onFocusGained(resumeOwedByTheEngine: Boolean): Boolean {
        if (!released) return false
        if (!resumeOwedByTheEngine) return true   // stays paused and released: nothing was playing
        claim(owed = Owed.AUTOMATIC)
        return true
    }

    /** The player was paused or went idle on its own account (not an engine hold). */
    fun onPaused(): PauseOutcome = if (active && !released && !claiming) PauseOutcome.RELEASE_AFTER_GRACE else PauseOutcome.NONE

    /** The pause lasted; [playing] is whether playback has restarted since. */
    fun onPauseGraceElapsed(playing: Boolean) {
        if (playing || !active || claiming) return
        release()
    }

    /**
     * A request to play, from any controller (the app, the lock screen, a headset button, the DAC's own button). True lets it through.
     * False holds it: the DAC is released, so full audio focus is asked for first (the other app pauses) and the DAC is taken again; the
     * play is carried out by [onRouteState] once the DAC is back and its level is confirmed.
     *
     * B40-P1: a request is the listener's, so it is never judged by the conservative verdict. During a claim it is remembered
     * ([Owed.LISTENER], which also takes over a resume the engine owed), and a DAC that is already back and confirmed lets it through at once.
     */
    fun onPlayRequested(): Boolean {
        if (claiming) {
            if (host.userPlayReady()) {
                conclude()
                return true
            }
            owed = Owed.LISTENER
            return false
        }
        if (!released) return true
        if (!host.requestFocus()) return false   // another app keeps the floor (a call): nothing is claimed, nothing plays
        claim(owed = Owed.LISTENER)
        return false
    }

    /**
     * The route changed: a claim ends when Direct is active with a confirmed level. The listener's play starts then; an automatic resume
     * starts only if the conservative verdict also says so ([Host.directReady]), otherwise the player stays paused for them to start.
     */
    fun onRouteState() {
        if (!claiming || !host.userPlayReady()) return
        val owedNow = owed
        conclude()
        when (owedNow) {
            Owed.LISTENER -> host.resume()
            Owed.AUTOMATIC -> if (host.directReady()) host.resume() else host.automaticResumeHeld()
            Owed.NONE -> Unit
        }
    }

    /** The claim did not complete in time: the player stays paused, nothing is resumed on a level that was not confirmed. */
    fun onClaimTimeout(directActive: Boolean) {
        if (!claiming) return
        val listenerWaited = owed == Owed.LISTENER
        conclude()
        if (listenerWaited) host.refused(if (directActive) ClaimRefusal.LEVEL_UNCONFIRMED else ClaimRefusal.DAC_NOT_BACK)
    }

    private fun conclude() {
        claiming = false
        owed = Owed.NONE
    }

    /**
     * B40-P4: the hand-back needed a bus reset, and the DAC's level after it is not known to be the confirmed one (it may have booted back
     * at its default, often 100 %). Another app's audio must not be heard at that level, so the DAC is not shared: it is taken back, with
     * nothing to resume (the level work of the ordinary claim brings it to the saved one).
     */
    fun onReleaseNotConfirmed() {
        if (!released) return
        released = false
        claiming = false
        owed = Owed.NONE
        host.setReleased(false)
        host.releaseNotConfirmed()
    }

    /**
     * The switch, the Purity choice or the listening state changed. Where sharing is no longer allowed, a released DAC is taken again (no
     * resume: nothing was asked). B68: where it is allowed now and Polylute is [paused] with the DAC still held, the same wait as a pause
     * starts ([PauseOutcome.RELEASE_AFTER_GRACE]): turning the switch on while paused shares the DAC 3 s later, not only after the next pause.
     */
    fun onSettingsChanged(paused: Boolean): PauseOutcome {
        if (!active) {
            if (released) {
                released = false
                claiming = false
                owed = Owed.NONE
                host.setReleased(false)
            }
            return PauseOutcome.NONE
        }
        return if (paused) onPaused() else PauseOutcome.NONE
    }

    private fun release() {
        if (released) return
        released = true
        claiming = false
        host.setReleased(true)
    }

    private fun claim(owed: Owed) {
        claiming = true
        this.owed = owed
        released = false
        host.setReleased(false)
    }

    companion object {
        /** A pause or idle this long hands the DAC back. */
        const val PAUSE_GRACE_MS = 3_000L

        /** How long a claim may take before the player is left paused. */
        const val CLAIM_TIMEOUT_MS = 10_000L
    }
}
