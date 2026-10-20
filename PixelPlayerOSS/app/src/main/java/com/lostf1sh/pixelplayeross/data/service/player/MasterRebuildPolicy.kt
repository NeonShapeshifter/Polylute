package com.lostf1sh.pixelplayeross.data.service.player

/**
 * Whether the master player must be reconstructed for a route.
 *
 * Three independent reasons, and the third is the subtle one:
 *
 * - **The backend moved.** Android and Direct need structurally different sinks, so a change of
 *   backend always needs a new player.
 * - **The player in hand cannot serve the backend it is being asked for.** `publishUsbDiagnostics`
 *   writes `desiredBackend` the moment a DAC becomes available, which can happen after the running
 *   player was already built Android-only. On the next evaluation the backend has not "changed",
 *   so the first reason does not fire — and without the second, the player can never claim the
 *   exclusive route. Direct would be permanently unreachable, not intermittently slow.
 * - **The player in hand has the Direct sink but never got the chance to claim it.** The claim
 *   lives inside `UsbAudioSink.configure()`, which the audio renderer only reaches once the player
 *   has been prepared from a media item. A Direct-capable player built against an empty queue
 *   therefore cannot claim, and the second reason will not rebuild it again either — it is built
 *   for the backend it is being asked for. Without the third reason that state is a dead end; with
 *   it, the claim is retried exactly once, when a source actually appears.
 *
 * ## The three states this keeps apart
 *
 * This decision never conflates what the player *is* with what it has *done*:
 *
 * | concept | fact | question |
 * |---|---|---|
 * | capability | [playerBuiltWithDirectAccess] | was the player built with the Direct sink? |
 * | desire | [decidedBackend] | does the routing policy want Direct now? |
 * | claim | [directIsActive] | does the Direct sink own the route? |
 *
 * Capability is not claim. A player can hold the Direct sink and be routed to Android, which is
 * exactly the state [needsClaimAttempt] exists to leave — once, and only while there is something
 * to claim with.
 *
 * ## Why this cannot oscillate
 *
 * [directClaimAttempted] is a latch the engine sets when it builds a Direct-capable player *that
 * had something to claim with*, and clears only by building a new one. Every input to
 * [shouldRebuild] therefore changes only when the engine acts, so:
 *
 * - Direct active → [directIsActive] short-circuits both capability reasons. Fixed point.
 * - Android steady → no backend change, no capability mismatch, no desire for Direct. Fixed point.
 * - Direct wanted, capable, unclaimed, nothing to claim with → all three reasons false, and they
 *   stay false for as long as nothing changes. Not a fixed point that refuses to move, but one
 *   that is waiting on a fact rather than on a timer.
 * - A source appears → [needsClaimAttempt] fires **once**; the rebuild it authorises sets the
 *   latch, so the identical facts decide the same way from then on.
 *
 * The failure this replaces is the opposite one: an earlier architecture rebuilt whenever Direct
 * was wanted and not active, which on the HONOR meant roughly eleven `ExoPlayer` constructions per
 * second. Retrying on "claimability changed" rather than on "Direct is still inactive" is what
 * separates the two.
 *
 * This is a pure decision so the ordering hazard can be tested without an ExoPlayer.
 */
data class MasterRebuildFacts(
    /** The policy decision's backend for this evaluation. */
    val decidedBackend: AudioBackend,
    /** The backend the engine was already aiming at before this evaluation. */
    val previousDesiredBackend: AudioBackend,
    /** Whether the master player in hand was constructed with the USB Direct sink enabled. */
    val playerBuiltWithDirectAccess: Boolean,
    /** Whether Direct is already streaming. A working stream is never rebuilt. */
    val directIsActive: Boolean,
    /** Whether a master player exists to rebuild. */
    val masterInitialised: Boolean,
    /**
     * Whether there is now a source a Direct sink could be configured from — the master player has
     * at least one media item.
     *
     * This is the claimability the third reason keys on. It is deliberately a statement about
     * whether the sink *can* be configured, not about whether it succeeded: a failed claim must not
     * license another rebuild, or that is the old oscillation again.
     */
    val directClaimable: Boolean = false,
    /**
     * Whether the Direct-capable player in hand has already been given its claim opportunity
     * against a claimable source.
     *
     * Latched by the engine, not derived here, because no fact in this data class can distinguish
     * "built while the queue was empty" from "built while it was not" — both leave
     * [playerBuiltWithDirectAccess] true and [directIsActive] false. Defaults to `false` so that a
     * player which has never been asked to claim is always allowed its one attempt.
     */
    val directClaimAttempted: Boolean = false,
) {
    /**
     * The backend did not change, but the player cannot serve what the route now wants.
     */
    val needsCapabilityRebuild: Boolean
        get() = !directIsActive && playerBuiltWithDirectAccess != (decidedBackend == AudioBackend.USB_DIRECT)

    /**
     * Direct is wanted and the sink to serve it is already in hand, but the claim was never made —
     * because when that player was built there was nothing for the sink to configure itself from.
     * Now there is, and this window has not been spent.
     *
     * Spent at most once per claimability window, and cleared only when a newly built player makes
     * the question meaningful again.
     */
    val needsClaimAttempt: Boolean
        get() = !directIsActive &&
            decidedBackend == AudioBackend.USB_DIRECT &&
            playerBuiltWithDirectAccess &&
            directClaimable &&
            !directClaimAttempted

    val backendChanged: Boolean
        get() = decidedBackend != previousDesiredBackend

    /** The single question the engine asks: rebuild the master, or leave it alone? */
    val shouldRebuild: Boolean
        get() = masterInitialised && (backendChanged || needsCapabilityRebuild || needsClaimAttempt)
}

/**
 * Keeps the route loop from re-entering a master rebuild.
 *
 * `rebuildPlayersPreservingMasterState` is not a leaf. It calls `refreshUsbAudioDiagnostics`, which
 * ends by asking for a route reevaluation. Running that request inline evaluates the route against a
 * half-built engine: the new player exists, but its queue has not been restored and
 * `recordDirectCapability` has not run, so [MasterRebuildFacts.playerBuiltWithDirectAccess] still
 * describes the player that was just released. [MasterRebuildFacts.needsCapabilityRebuild] then
 * authorises the very same rebuild again, before the state that would clear it can be written —
 * and that recursion has no termination.
 *
 * Measured on the HONOR LGN-NX3, a DAC attaching against a player built Android-only produced 40
 * rebuilds in 1.611 s (about 25 `ExoPlayer` constructions per second), every one of them reporting
 * `builtWithDirectAccess=false` and `mediaItems=0` — the queue is destroyed by the first level of
 * the recursion, because the restore also sits after the re-entry. The process was killed by the
 * system.
 *
 * So a reevaluation asked for during a rebuild is *remembered* rather than run, and is replayed once
 * the engine is whole again — after the queue is restored and the capability latch is written. The
 * replay then reads true facts, and those facts are a fixed point: the rebuild it just performed is
 * exactly what makes `needsCapabilityRebuild` and `needsClaimAttempt` false.
 *
 * At most one replay is owed per rebuild, because further requests coalesce: the replay re-reads the
 * world rather than replaying each request. Nothing here is a timer, a sleep, or a poll — the replay
 * is authorised by an event the engine already has, namely the rebuild finishing.
 *
 * Not thread-safe by design: it guards the engine's main-thread route loop, and every caller of
 * `reevaluateAutomaticRoute` is on that thread (the off-main USB callbacks marshal to it first).
 */
class MasterRebuildCycle {
    /** Whether a master rebuild is currently being assembled. */
    var isRebuilding: Boolean = false
        private set

    private var reevaluationOwed = false

    /**
     * Claims the right to rebuild. Returns `false` when a rebuild is already in flight, in which
     * case the caller must not proceed: it would replace the player the in-flight rebuild is still
     * assembling, and release the one under construction.
     */
    fun beginRebuild(): Boolean {
        if (isRebuilding) return false
        isRebuilding = true
        reevaluationOwed = false
        return true
    }

    /** Records that a route reevaluation was asked for while a rebuild was in flight. */
    fun deferReevaluation() {
        reevaluationOwed = true
    }

    /**
     * Ends the rebuild and reports whether a deferred reevaluation is owed. Clears the debt, so the
     * caller replays at most once.
     */
    fun endRebuild(): Boolean {
        isRebuilding = false
        val owed = reevaluationOwed
        reevaluationOwed = false
        return owed
    }
}
