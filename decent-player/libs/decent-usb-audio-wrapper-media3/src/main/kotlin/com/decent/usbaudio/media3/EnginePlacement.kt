package com.decent.usbaudio.media3

/**
 * Who has placed the native engine, and whether a renderer buffer may still do it (AUD-139).
 *
 * While the engine plays, the renderer's buffers are not the audible position: Media3 reads up to
 * 30-60 s ahead (`DefaultLoadControl`), and the engine discards them unplayed. Measured on the
 * device: an item started at 0 by its item-start seek, and 30.6 s later the first buffer the sink
 * saw — the read-ahead edge, 60.65 s into the track — bootstrapped the engine there.
 *
 * Only a seek moves a playing engine: the user's, the session's (every one reaches the sink through
 * `MappingPlayer`), an item start, or the sink's own realign. A renderer buffer places an engine
 * only where nothing has — a creation "awaiting caller position", such as the restore at start —
 * and only while that engine has not been heard: an engine that has played for seconds under its
 * own steam is not waiting for a position, and a buffer arriving then is read-ahead. Measured in
 * B7-fix4: an engine created inside a transition's `configure` was never placed, and the first
 * buffer — the read-ahead edge, 31 s in — reached the sink 4 min 16 s later and moved it there.
 *
 * A position reset (flush) is deliberately not a way in either. Media3 resets its position for its
 * own reasons (a renderer re-enable, an error recovery) and reads wherever its own position is; a
 * seek the listener asked for was handed to the engine explicitly before that.
 *
 * Placements come from the playback thread and from a user seek on any thread; hence synchronized.
 */
class EnginePlacement {
    private var placed: Any? = null

    /** The position-reset count at the last placement, for the log. */
    @Volatile
    var lastPlacedAtResetCount = 0L
        private set

    /** The origin of the last placement, for the log. */
    @Volatile
    var lastOrigin: String = "none"
        private set

    /** [engine] was sent to a position by [origin] while [positionResetCount] resets had happened. */
    @Synchronized
    fun onPlaced(engine: Any, positionResetCount: Long, origin: String) {
        placed = engine
        lastPlacedAtResetCount = positionResetCount
        lastOrigin = origin
    }

    @Synchronized
    fun isPlaced(engine: Any): Boolean = placed === engine

    /**
     * Whether the first buffer after an arming event may place [engine] at its own position.
     * [enginePositionUs] is how far the engine has played. A position reset does not open the door
     * (see the class note).
     */
    @Synchronized
    fun bufferMayPlace(
        engine: Any,
        bufferFromPlayingStream: Boolean,
        enginePositionUs: Long = 0L,
    ): Boolean =
        bufferFromPlayingStream && placed !== engine && enginePositionUs <= MAX_UNPLACED_ENGINE_POSITION_US

    /** What [itemStartFor] asks of the sink for a running engine at its item's start. */
    sealed class ItemStart {
        /** Something placed this engine already: nothing to do. */
        object AlreadyPlaced : ItemStart()

        /** The engine is at the declared start already: record it as placed, no seek. */
        object InPlace : ItemStart()

        /** The engine is elsewhere: send it to the declared start as an explicit item-start seek. */
        class SeekTo(val targetUs: Long) : ItemStart()
    }

    /**
     * A running engine whose item's start is being declared (the transition's `configure` created it
     * before the declaration): it is the item's engine, so it is placed at the declared start rather
     * than left for the first buffer to place — which may come minutes late and read ahead.
     */
    fun itemStartFor(engine: Any, enginePositionUs: Long, declaredStartUs: Long): ItemStart = when {
        isPlaced(engine) -> ItemStart.AlreadyPlaced
        kotlin.math.abs(enginePositionUs - declaredStartUs) < BootstrapSeekPolicy.SAME_POSITION_US -> ItemStart.InPlace
        else -> ItemStart.SeekTo(declaredStartUs)
    }

    companion object {
        /** An unplaced engine that has played this long is no longer waiting for a position. */
        const val MAX_UNPLACED_ENGINE_POSITION_US = 10_000_000L
    }
}
