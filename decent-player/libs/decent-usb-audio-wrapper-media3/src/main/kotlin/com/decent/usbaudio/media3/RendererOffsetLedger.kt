package com.decent.usbaudio.media3

/**
 * Media3's renderer offsets, as it hands them to the sink (AUD-136).
 *
 * Every stream reaching the audio sink comes with its renderer offset
 * (`AudioSink.setOutputStreamOffsetUs`, called as that stream's output starts): consecutive periods
 * get strictly increasing offsets — the previous one plus its duration — and a queue reset (a seek to
 * another item) or a renderer re-enable starts again from the initial offset, 1_000_000_000_000.
 *
 * B7-fix derived the next item's offset as "previous offset + length", so an error in one item's
 * offset (a stale latch, a seek past the end, a watchdog correction) was carried into the next —
 * measured, a track that had just started at +167 s / +204 s, which Media3 ended or sought. Here the
 * offset of the item the engine plays is Media3's own: the sequence is followed as reported, and the
 * playing item is found by counting Media3's transitions, never by adding lengths.
 *
 * Reports arrive on the playback thread, transitions on the application thread.
 */
class RendererOffsetLedger {
    private val offsets = ArrayList<Long>()
    private var playingIndex = 0

    /** One `setOutputStreamOffsetUs`. A repeat is ignored; a smaller value starts a new sequence. */
    @Synchronized
    fun onStreamOffsetReported(offsetUs: Long) {
        if (offsetUs < 0L) return   // C.TIME_UNSET when a renderer is disabled
        val last = offsets.lastOrNull()
        if (last != null && offsetUs == last) return
        if (last != null && offsetUs < last) {
            // A re-enable reads the playing period first, so it is where the new sequence starts.
            offsets.clear()
            playingIndex = 0
        }
        offsets += offsetUs
        if (offsets.size > MAX_OFFSETS) {
            offsets.removeAt(0)
            playingIndex = (playingIndex - 1).coerceAtLeast(0)
        }
    }

    /**
     * Media3's item transition. AUTO and REPEAT move to the next period of the same sequence; any
     * other reason (a seek to another item, a playlist change) re-enabled the renderers, and the
     * most recent report is the playing item's.
     */
    @Synchronized
    fun onItemTransition(reason: Int) {
        playingIndex = when (reason) {
            TRANSITION_REASON_REPEAT, TRANSITION_REASON_AUTO -> playingIndex + 1
            else -> (offsets.size - 1).coerceAtLeast(0)
        }
    }

    /** The playing item's renderer offset, or null while Media3 has not reported it yet. */
    @Synchronized
    fun playingOffsetUs(): Long? = offsets.getOrNull(playingIndex)

    /** The offset of the stream whose buffers reach the sink now (the latest report). */
    @Synchronized
    fun latestOffsetUs(): Long? = offsets.lastOrNull()

    companion object {
        // Player.MEDIA_ITEM_TRANSITION_REASON_*: REPEAT = 0, AUTO = 1.
        const val TRANSITION_REASON_REPEAT = 0
        const val TRANSITION_REASON_AUTO = 1
        private const val MAX_OFFSETS = 8
    }
}
