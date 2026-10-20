package com.decent.usbaudio.media3

/**
 * Serializes signal-mode transitions and reconciles them to the latest desired mode.
 *
 * ## Why this exists
 *
 * A signal-mode change cannot be discovered by the sink's PCM path. While `NativeAudioEngine` owns
 * a local FLAC it writes to USB from its own thread, and `NativeEngineAwareLoadControl` — correctly
 * — stops ExoPlayer from loading anything, so `handleBuffer` may never be called again for the life
 * of the track. A transition that waits for a PCM callback to notice it therefore never happens on
 * exactly the tracks that need it, while the route above it reports that it did.
 *
 * So the change is *pushed* from the owner of the desired state, and this class is the boundary it
 * crosses. It does not poll, it does not weaken the load control, and it does not ask the player
 * for permission.
 *
 * ## Reconciliation rather than a queue
 *
 * Requests are not replayed. A request only earns the right to run one reconcile pass, and that
 * pass reads the *latest* desired mode rather than the mode that was current when the request was
 * made. Rapid changes therefore collapse into the fewest transitions that can reach the newest
 * state, which is what stops `EQ on → EQ off` from executing an obsolete round trip through
 * PROCESSED and back:
 *
 * ```
 * PRISTINE → request(on) → request(off) → reconcile()  // latest is PRISTINE, applied already is
 *                                                      // PRISTINE: no transition at all
 * ```
 *
 * A request arriving while a pass is already scheduled is dropped, because the scheduled pass will
 * read the same latest state the new one would have. That is the coalescing, and it is why the
 * count of transitions is bounded by the count of *distinct settled states*, not by the count of
 * requests.
 *
 * Not thread-safe by design: it guards a single-threaded reconcile loop, and callers marshal onto
 * that thread before touching it.
 */
class DirectSignalModeReconciler(initial: DirectSignalMode = DirectSignalMode.PRISTINE) {

    /** The mode the audio path is actually running. Only [reconcile] moves it. */
    var applied: DirectSignalMode = initial
        private set

    private var scheduled = false

    /**
     * Records that the desired mode may have changed.
     *
     * @return true when the caller should run a reconcile pass now; false when one is already owed,
     *         in which case the owed pass will observe this change too.
     */
    fun onRequest(): Boolean {
        if (scheduled) return false
        scheduled = true
        return true
    }

    /**
     * Reconciles [latestDesired] — read by the caller at the moment of the pass, not captured
     * earlier — against what the audio path is running.
     *
     * @return true when a transition must be performed. False means the pipeline already satisfies
     *         the newest request, which is the desired outcome of every stale or superseded one.
     */
    fun reconcile(latestDesired: DirectSignalMode): Boolean {
        scheduled = false
        if (latestDesired == applied) return false
        applied = latestDesired
        return true
    }

    /**
     * Forces the next reconcile to treat [mode] as unapplied.
     *
     * Used when a stream is torn down: the audio path is no longer running whatever it was, so the
     * next pass must not read a stale "already applied" and skip a transition that is genuinely
     * needed.
     */
    fun invalidate(mode: DirectSignalMode = DirectSignalMode.PRISTINE) {
        applied = mode
        scheduled = false
    }

    /**
     * Records that the audio path is now running [mode], without performing a transition.
     *
     * The sink resolves its mode from more than one place — `configure` decides it for a new stream
     * before any request can arrive — and this is how those paths keep [applied] honest. Without it
     * the first reconcile after a configure would compare the host's mode against a stale default
     * and perform a transition that had already happened.
     */
    fun markApplied(mode: DirectSignalMode) {
        applied = mode
    }
}
