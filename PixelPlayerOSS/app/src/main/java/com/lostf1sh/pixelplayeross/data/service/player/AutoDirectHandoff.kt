package com.lostf1sh.pixelplayeross.data.service.player

/**
 * One automatic Direct handoff: the app pauses briefly so the existing route machinery can claim
 * USB Direct, then restores playback itself.
 *
 * ## Why this exists
 *
 * `isDirectTransitionAllowed` refuses Direct while audio is audible, and that rule is correct — the
 * claim tears down and rebuilds the audio pipeline, so entering it mid-song would be heard as a gap.
 * The cost is that attaching a DAC during playback leaves Direct unreachable until the user pauses
 * by hand. Measured on the HONOR LGN-NX3, the transition itself takes about 350 ms; this class
 * spends that pause on the user's behalf and gives the playback back.
 *
 * ## What it deliberately is not
 *
 * This is *not* a policy. It never decides whether Direct is wanted, never claims anything, and
 * never touches [MasterRebuildCycle]. It reacts to exactly one situation — the route decision came
 * back [AudioRouteReason.SAFETY_TRANSITION_DEFERRED], which the pure policy only reaches after every
 * other gate has already passed — and it gets out of the way for everything else. `AudioRoutePolicy`
 * and `isDirectTransitionAllowed` are unchanged and remain the only authority on the route.
 *
 * It is also state-driven rather than timed. The sequence is: pause, wait for the engine to report
 * it is genuinely not playing, let the ordinary reevaluation run, and resume only once
 * [AudioRouteState.transition] is actually [AudioRouteTransition.DIRECT_ACTIVE]. It never resumes on
 * `desiredBackend`, on [MasterRebuildFacts.playerBuiltWithDirectAccess], on `DIRECT_ELIGIBLE`, or on
 * a guessed delay. [timeoutMs] bounds only the *waiting*, so a Direct failure cannot strand the user
 * in silence; it is not a substitute for observing the state.
 *
 * ## User intent
 *
 * The pause this class issues is indistinguishable from a user pause at the Media3 callback —
 * `onPlayWhenReadyChanged` reports `PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST` for both — so the
 * handoff tracks that it issued one, and consumes exactly that callback. Every other play/pause
 * event during the window is the user taking control, and any of them cancels the resume. The user
 * always wins.
 *
 * Not thread-safe: it is driven from the engine's main-thread route loop.
 */
class AutoDirectHandoff(
    /**
     * How long to wait for [AudioRouteTransition.DIRECT_ACTIVE] before falling back.
     *
     * The measured successful transition is ~350 ms on the reference device. This is roughly six
     * times that: enough headroom for a slow USB enumeration or a cold decoder, short enough that a
     * genuine failure costs the listener well under a second of silence before Android playback is
     * restored. There is exactly one attempt per attach — no retry loop.
     */
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    /** Whether a handoff is in flight. */
    var isInProgress: Boolean = false
        private set

    /** Whether playback should be restored if this handoff concludes without the user interfering. */
    private var resumeOwed = false

    /**
     * Whether a pause issued by this handoff is still awaiting its callback.
     *
     * Set when the engine is about to pause, cleared by the matching `playWhenReady == false`
     * callback. Anything left over when a later pause arrives means the user pressed it.
     */
    private var ownPausePending = false

    /** The item the user was listening to; the handoff never resumes a different one. */
    private var targetItemId: Any? = null

    private var startMs: Long = 0L

    /** `DIRECT_ACTIVE` was seen for the target item; only the volume verdict is outstanding. */
    private var directReached = false

    /**
     * Starts a handoff if this evaluation is the one situation it exists for.
     *
     * [reason] is the whole trigger. [AudioRouteReason.SAFETY_TRANSITION_DEFERRED] is produced by
     * [AudioRoutePolicy] only after processing requirements, maintenance overrides, DAC presence,
     * USB permission and source compatibility have all passed — so reaching it means Direct is
     * eligible and audibility is the sole remaining obstacle. Every other reason is left alone: no
     * handoff for a missing DAC, an unsupported source, an equalizer that needs the Android path, a
     * maintenance override, or a direct-negotiation failure.
     *
     * Returns `true` if this call started the handoff, in which case the caller issues the pause.
     * Returns `false` for a repeat evaluation while one is already running, which is what stops a
     * burst of deferred callbacks producing duplicate pauses or jobs.
     */
    fun begin(
        reason: AudioRouteReason,
        isPlaying: Boolean,
        directIsActive: Boolean,
        itemId: Any?,
        nowMs: Long,
    ): Boolean {
        if (isInProgress) return false
        if (reason != AudioRouteReason.SAFETY_TRANSITION_DEFERRED) return false
        // Already-paused playback needs no handoff: the existing machinery already reaches Direct
        // from there and must keep doing so untouched. Direct already active needs nothing either.
        if (!isPlaying || directIsActive) return false

        isInProgress = true
        resumeOwed = true
        ownPausePending = true
        directReached = false
        targetItemId = itemId
        startMs = nowMs
        return true
    }

    /**
     * Consumes the pause this handoff issued.
     *
     * Returns `true` when the `playWhenReady == false` being reported is ours, so the engine does
     * not mistake it for the user asking to stop.
     */
    fun consumeOwnPause(): Boolean {
        if (!ownPausePending) return false
        ownPausePending = false
        return true
    }

    /** The user pressed play. They have taken control; do not restore anything on their behalf. */
    fun onUserPlay() {
        if (isInProgress) cancelResume()
    }

    /** The user pressed pause after ours landed. They do not want playback back. */
    fun onUserPause() {
        if (isInProgress) cancelResume()
    }

    /**
     * The current item changed.
     *
     * A change the user caused (next, previous, a new selection) abandons the handoff outright —
     * resuming then would restore something they have moved on from. A change the *engine* caused
     * is filtered out by the caller, because the Direct rebuild restores the same queue and would
     * otherwise look like a user action. A natural end-of-track advance re-targets instead, since
     * the user did not ask for anything and the next track is what they would have heard anyway.
     */
    fun onItemChanged(itemId: Any?, userInitiated: Boolean) {
        if (!isInProgress) return
        if (userInitiated) {
            cancelResume()
        } else if (itemId != null) {
            targetItemId = itemId
        }
    }

    /**
     * The DAC is gone.
     *
     * Never resumes. With the device detached, restoring playback would push audio out of the phone
     * speaker — the platform pauses for exactly this reason
     * (`PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY`), and overriding that would be worse
     * than leaving the user paused.
     */
    fun onDeviceLost() {
        resumeOwed = false
        isInProgress = false
        ownPausePending = false
        targetItemId = null
    }

    /** Whether the wait has exceeded [timeoutMs]. */
    fun hasTimedOut(nowMs: Long): Boolean = isInProgress && nowMs - startMs >= timeoutMs

    /**
     * Whether the route has genuinely reached Direct for the item this handoff started on.
     *
     * All three conditions are required, and none of them is a proxy. `actualBackend` is what the
     * audio is really doing rather than what was asked for; `DIRECT_ACTIVE` is the sink owning the
     * route rather than being eligible for it; and the item check is what keeps a resume from
     * landing on something the user did not choose.
     */
    fun isDirectActiveReached(state: AudioRouteState, itemId: Any?): Boolean =
        isInProgress &&
            state.actualBackend == AudioBackend.USB_DIRECT &&
            state.transition == AudioRouteTransition.DIRECT_ACTIVE &&
            itemId == targetItemId

    /** What the engine should do with a handoff after a route or volume-safety update. */
    enum class ResumeStep { WAIT, RESUME, CONCLUDE_WITHOUT_RESUME }

    /**
     * Whether playback may be given back now.
     *
     * `DIRECT_ACTIVE` is necessary but not sufficient (AUD-002). It is published synchronously from
     * the sink, before the Safety controller has read the DAC's own level and clamped it, and in
     * Direct the level is the DAC's — not the Android volume the listener was hearing. So the resume
     * also waits for [volume]: [HandoffVolumeVerdict.RESUME] once the DAC is reconciled within the
     * ceiling, [HandoffVolumeVerdict.HOLD] when nothing bounds it (fixed output, or no ceiling and a
     * loud level) — the handoff then ends with the user paused, and they press play themselves.
     *
     * Reaching Direct is latched, so a volume verdict arriving after the route update still counts.
     */
    fun resumeStep(state: AudioRouteState, itemId: Any?, volume: HandoffVolumeVerdict): ResumeStep {
        if (isDirectActiveReached(state, itemId)) directReached = true
        if (!isInProgress || !directReached) return ResumeStep.WAIT
        return when (volume) {
            HandoffVolumeVerdict.WAIT -> ResumeStep.WAIT
            HandoffVolumeVerdict.RESUME -> ResumeStep.RESUME
            HandoffVolumeVerdict.HOLD -> ResumeStep.CONCLUDE_WITHOUT_RESUME
        }
    }

    /**
     * Whether the timeout should restore playback. Before Direct is reached it falls back to Android
     * playback, as before; once Direct is active and only the volume is unconfirmed, resuming would
     * be exactly the unchecked level [resumeStep] refuses, so the user is left paused.
     */
    fun timeoutResumes(): Boolean = !directReached

    /**
     * Ends the handoff and reports whether the caller should restore playback. Always clears, so a
     * handoff is concluded at most once and cannot leave a resume owed behind it.
     */
    fun conclude(): Boolean {
        val resume = isInProgress && resumeOwed
        isInProgress = false
        resumeOwed = false
        ownPausePending = false
        targetItemId = null
        return resume
    }

    private fun cancelResume() {
        resumeOwed = false
        isInProgress = false
        ownPausePending = false
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 2_000L
    }
}
