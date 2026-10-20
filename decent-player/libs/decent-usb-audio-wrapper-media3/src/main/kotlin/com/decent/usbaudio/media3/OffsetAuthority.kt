package com.decent.usbaudio.media3

/**
 * Where a latch's anchor comes from when the epoch's own anchor is wrong (AUD-136).
 *
 * Measured on the device:
 * - PREV (seek to 0) reached the engine as an explicit seek that completed in 44 ms, before Media3's
 *   flush. The flush saw no seek owed, replaced the explicit epoch with the host mirror's stale
 *   167 443 ms, latched `pts - 167 s` and bootstrapped the engine to 167 s: the track jumped to its
 *   end and ended by itself.
 * - A seek to 168 696 ms on a 165 634 ms track latched `pts - 168.7 s`, although the first buffer
 *   is the last frame (165.6 s): an offset 3 s wrong, carried into the next item.
 */
object OffsetAuthority {
    /** Below this the epoch's anchor stands: an ordinary seek's first buffer is a frame before it. */
    const val ANCHOR_TOLERANCE_US = 500_000L
    private const val DURATION_MARGIN_US = 1_000_000L

    /**
     * The buffer's media position according to Media3 (`pts - offset of its stream`) when it
     * contradicts the epoch's anchor and is a position this item can have; else null (latch as before).
     */
    fun media3Anchor(presentationTimeUs: Long, media3OffsetUs: Long?, epochAnchorUs: Long, durationUs: Long): Long? {
        if (media3OffsetUs == null) return null
        val mediaPositionUs = presentationTimeUs - media3OffsetUs
        if (mediaPositionUs < 0L) return null
        if (durationUs > 0L && mediaPositionUs > durationUs + DURATION_MARGIN_US) return null
        if (kotlin.math.abs(mediaPositionUs - epochAnchorUs) <= ANCHOR_TOLERANCE_US) return null
        return mediaPositionUs
    }

    /**
     * A flush keeps an unlatched explicit epoch opened by the seek this reset belongs to, even when
     * the engine already completed that seek: the command's target is the authority, not the host.
     */
    fun keepExplicitEpochAtFlush(
        latched: Boolean,
        anchorSource: AnchorSource,
        explicitIssuedAtResetCount: Long,
        positionResetCount: Long,
    ): Boolean =
        !latched && anchorSource == AnchorSource.EXPLICIT_SEEK_TARGET &&
            explicitIssuedAtResetCount >= positionResetCount - 1

    /**
     * AUD-138: a seek past the end of the item is a seek to its end — a natural end, the next item
     * from 0. Measured: a fast-forward to 228 995 ms on a 225 333 ms track.
     */
    fun seekTargetWithinItem(targetUs: Long, durationUs: Long): Long =
        if (durationUs > 0L && targetUs > durationUs) durationUs else targetUs

    /**
     * AUD-138: the presentation time to latch the playing item's epoch with when the buffer at hand
     * belongs to the next item's stream: the playing item's own renderer offset plus the anchor, so
     * the latched offset is Media3's for this item. `pts - anchor` of the foreign buffer latched the
     * next item's offset instead (Media3 229 s, engine 7.7 s).
     */
    fun latchPresentationForForeignBuffer(playingOffsetUs: Long, anchorUs: Long): Long = playingOffsetUs + anchorUs
}
