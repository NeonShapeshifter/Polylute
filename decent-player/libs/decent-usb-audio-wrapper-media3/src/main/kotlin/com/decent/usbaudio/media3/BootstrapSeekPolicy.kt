package com.decent.usbaudio.media3

/**
 * Whether the bootstrap seek after a flush may be skipped because the engine is already at the
 * target (AUD-117).
 *
 * The explicit target is trusted only when it was issued for this very flush — a user seek that
 * already moved the engine, where re-seeking would run the barrier twice ("pff"). A stale explicit
 * target was how "previous" at 5.4 s was skipped: the item start had left it at 0, the session's seek
 * to 0 matched it, and the engine stayed at 5.4 s. Otherwise only the engine's live position counts.
 */
object BootstrapSeekPolicy {
    const val SAME_POSITION_US = 100_000L

    fun alreadyThere(targetUs: Long, enginePositionUs: Long, explicitTargetUs: Long, explicitIsFresh: Boolean): Boolean =
        (explicitIsFresh && explicitTargetUs >= 0 && kotlin.math.abs(explicitTargetUs - targetUs) < SAME_POSITION_US) ||
            kotlin.math.abs(enginePositionUs - targetUs) < SAME_POSITION_US

    /**
     * Whether a buffer belongs to the playing item's stream (AUD-138/139): Media3 reports each
     * stream's renderer offset as its output starts, so a newer offset than the playing item's is the
     * next item, read ahead. Unknown offsets do not exclude a buffer.
     */
    fun bufferFromPlayingStream(latestOffsetUs: Long?, playingOffsetUs: Long?): Boolean =
        latestOffsetUs == null || playingOffsetUs == null || latestOffsetUs == playingOffsetUs

    /** What the first renderer buffer after an arming event does to the engine. */
    sealed class Decision {
        /** Read-ahead, or too late to be a position: the engine stays where it is. */
        object Refused : Decision()

        /** The engine is where the buffer says: confirm the placement, no seek. */
        object AlreadyThere : Decision()

        /** Send the engine to [targetUs]. */
        class Seek(val targetUs: Long) : Decision()

        /** An explicit seek is still owed and outranks the buffer. */
        object ExplicitOwed : Decision()

        /** No offset yet, or no usable position: nothing to decide. */
        object None : Decision()
    }

    /**
     * The one place the sink decides what a buffer may do to the engine, shared with the long-run
     * simulation so the test drives the rule the device runs.
     */
    fun decide(
        placement: EnginePlacement,
        engine: Any,
        fromPlayingStream: Boolean,
        enginePositionUs: Long,
        windowOffsetKnown: Boolean,
        explicitOwed: Boolean,
        bufferPositionUs: Long,
        explicitTargetUs: Long,
        explicitIsFresh: Boolean,
    ): Decision {
        if (!placement.bufferMayPlace(engine, fromPlayingStream, enginePositionUs)) {
            return Decision.Refused
        }
        if (windowOffsetKnown && !explicitOwed) {
            if (bufferPositionUs < 0) return Decision.None
            return if (alreadyThere(bufferPositionUs, enginePositionUs, explicitTargetUs, explicitIsFresh)) {
                Decision.AlreadyThere
            } else {
                Decision.Seek(bufferPositionUs)
            }
        }
        return if (explicitOwed) Decision.ExplicitOwed else Decision.None
    }
}
