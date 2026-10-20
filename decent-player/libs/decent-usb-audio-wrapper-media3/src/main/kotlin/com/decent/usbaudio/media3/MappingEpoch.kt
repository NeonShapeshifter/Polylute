package com.decent.usbaudio.media3

/**
 * Where a mapping epoch's anchor came from.
 *
 * Recorded so a latch can state its own provenance. The anchor's *numeric age* says nothing about
 * its validity — an old anchor is correct whenever the incoming presentation timestamp describes
 * that same media position. Only the pairing matters.
 */
enum class AnchorSource {
    /** The target of an explicit seek, taken at the moment the seek was issued. */
    EXPLICIT_SEEK_TARGET,

    /** The position the host declared for an item start (`declareItemStartTarget`). */
    DECLARED_ITEM_START,

    /** The position a restored session resumed at, declared with the restore. */
    RESTORED_POSITION,

    /** The position a new item begins at, at the item transition itself. */
    ITEM_TRANSITION_START,

    /** A restart the host declared at a position reset the sink could not otherwise resolve. */
    IMPLICIT_RESTART,

    /** No authoritative position was available. A latch from here is refused, not guessed. */
    UNKNOWN,
}

/**
 * The renderer↔media mapping, as a single value whose parts share one lifetime.
 *
 * The defect this replaces was a pairing of two independently-mutable fields: a renderer-domain
 * presentation timestamp minus a media-position anchor, where the offset could be cleared while the
 * anchor stayed behind from an earlier position. That made a cross-position pair *representable*,
 * and once latched the resulting constant was baked into every position read for the life of the
 * epoch — the engine kept advancing, the sink stayed internally exact, and the Player-visible
 * position was displaced by the difference until the stuck detector fired.
 *
 * Here the two values cannot drift apart: [Unlatched] already carries the anchor it will be latched
 * against, and [Latched] carries the anchor it was in fact latched against.
 */
sealed interface MappingState {

    /** No epoch has been begun. A latch is impossible — there is nothing to subtract. */
    data object None : MappingState

    /**
     * An epoch is open and its anchor is known; the mapping is waiting for the first presentation
     * timestamp that belongs to it.
     */
    data class Unlatched(
        val epochId: Long,
        val anchorMediaPositionUs: Long,
        val anchorSource: AnchorSource,
    ) : MappingState

    /** The mapping is established, together with the anchor it was established from. */
    data class Latched(
        val epochId: Long,
        val anchorMediaPositionUs: Long,
        val anchorSource: AnchorSource,
        val windowOffsetUs: Long,
    ) : MappingState
}

/**
 * Owns the mapping epoch: begins epochs at position boundaries and latches exactly once per epoch.
 *
 * Pure and synchronous — no Android dependencies, no clock, no threads — so its behaviour is
 * directly testable and its invariants are not entangled with the sink's lifecycle.
 *
 * Not thread-safe by design: the sink drives it from the playback thread, the same thread that
 * already owns every other mapping decision.
 */
class MappingEpochTracker {

    var state: MappingState = MappingState.None
        private set

    private var lastEpochId: Long = 0L

    /** Latches attempted while no epoch was open. Non-zero means a position reset was missed. */
    var refusedLatchCount: Long = 0L
        private set

    /**
     * Opens a NEW_POSITION_EPOCH at [anchorMediaPositionUs].
     *
     * Called only where the media position genuinely changes — an explicit seek, an item start, a
     * restore, a restart from ENDED. A machinery rebuild at an unchanged position must call
     * [preserve] instead, so a realign cannot silently re-anchor the mapping at the wrong moment.
     */
    fun beginEpoch(anchorMediaPositionUs: Long, source: AnchorSource): MappingState.Unlatched {
        val opened = MappingState.Unlatched(++lastEpochId, anchorMediaPositionUs, source)
        state = opened
        return opened
    }

    /**
     * A SAME_POSITION_REBUILD: an engine was recreated, the signal path realigned, or the stream was
     * reconfigured without the media position changing.
     *
     * Preserves whatever state exists, including a latched mapping. This is the operation the old
     * `windowOffsetUs = -1` was wrongly used for — clearing the offset while leaving the anchor
     * alive is precisely how a cross-position pair became representable.
     */
    fun preserve(): MappingState = state

    /**
     * Establishes the mapping from the first presentation timestamp of the open epoch.
     *
     * Refuses when no epoch is open: without an anchor there is no position to subtract, and
     * guessing one is what produced the corruption. Refuses when the epoch is already latched, so a
     * rebuild cannot re-latch from a timestamp belonging to a later position.
     *
     * @return the resulting state; callers should treat `None` as "mapping not established".
     */
    fun latch(presentationTimeUs: Long): MappingState = when (val current = state) {
        is MappingState.Unlatched -> {
            val latched = MappingState.Latched(
                epochId = current.epochId,
                anchorMediaPositionUs = current.anchorMediaPositionUs,
                anchorSource = current.anchorSource,
                windowOffsetUs = presentationTimeUs - current.anchorMediaPositionUs,
            )
            state = latched
            latched
        }
        // Already latched: preserved, never recomputed.
        is MappingState.Latched -> current
        // No anchor exists, so no mapping can be established from this timestamp.
        MappingState.None -> {
            refusedLatchCount++
            MappingState.None
        }
    }

    /**
     * The renderer-domain offset, or -1 when the mapping is not established.
     *
     * Derived rather than stored: there is one authoritative copy, in [MappingState.Latched].
     */
    val windowOffsetUs: Long
        get() = (state as? MappingState.Latched)?.windowOffsetUs ?: -1L

    /** The anchor of the open or latched epoch, or -1 when no epoch has been begun. */
    val anchorMediaPositionUs: Long
        get() = when (val current = state) {
            is MappingState.Unlatched -> current.anchorMediaPositionUs
            is MappingState.Latched -> current.anchorMediaPositionUs
            MappingState.None -> -1L
        }

    val epochId: Long
        get() = when (val current = state) {
            is MappingState.Unlatched -> current.epochId
            is MappingState.Latched -> current.epochId
            MappingState.None -> -1L
        }

    val anchorSource: AnchorSource
        get() = when (val current = state) {
            is MappingState.Unlatched -> current.anchorSource
            is MappingState.Latched -> current.anchorSource
            MappingState.None -> AnchorSource.UNKNOWN
        }

    val isLatched: Boolean get() = state is MappingState.Latched

    /**
     * Diagnostic cross-check only: the offset the latch produced against the renderer's expected
     * period offset.
     *
     * A mismatch is evidence that the pair spanned two positions. It is deliberately NOT an
     * acceptance rule — the expected offset is supplied by the caller, because a multi-period
     * timeline legitimately uses an offset other than the first period's seed, and a numeric
     * threshold on the offset would detect the symptom rather than enforce the invariant.
     */
    fun periodOffsetMismatchUs(expectedPeriodOffsetUs: Long): Long? {
        val latched = state as? MappingState.Latched ?: return null
        return latched.windowOffsetUs - expectedPeriodOffsetUs
    }

    /** Process or stream teardown only — never a position reset. */
    fun reset() {
        state = MappingState.None
        lastEpochId = 0L
        refusedLatchCount = 0L
    }
}
