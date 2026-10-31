package com.decent.usbaudio.media3

/** Validates the epoch anchor against Media3's renderer offset before a latch. */
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
     * AUD-138: the presentation time to latch the playing item's epoch with when the buffer at hand
     * belongs to the next item's stream: the playing item's own renderer offset plus the anchor, so
     * the latched offset is Media3's for this item. `pts - anchor` of the foreign buffer latched the
     * next item's offset instead of the playing item's.
     */
    fun latchPresentationForForeignBuffer(playingOffsetUs: Long, anchorUs: Long): Long = playingOffsetUs + anchorUs
}
